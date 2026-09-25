/*
 * Amperfy4Android - an unofficial Android port of Amperfy
 * Copyright (c) 2026 angelo
 * Based on Amperfy for iOS, Copyright (c) 2019-2025 Maximilian Bauer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.amperfy.data.local.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.store.RoomLibraryLocalStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RoomLibraryLocalStore 读路径合同。
 *
 * P5 起 Store 为 Room 单实现（过渡期的委托构造参数已删除），读方法全部直落 DAO。
 * 覆盖：LIKE %/_ 字面转义、recent/newest JOIN + LIMIT 20、无状态行=未同步、三缓存推导
 * （含经 album.artist 的间接艺术家）、getArtistSongs/getArtistAlbums 并集去重。
 */
@RunWith(AndroidJUnit4::class)
class RoomLibraryStoreReadTest {

    private lateinit var db: AmperfyDatabase
    private lateinit var store: RoomLibraryLocalStore
    private val acct = DbTestFixture.ACCT_A

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
        store = RoomLibraryLocalStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun searchByKey_escapesWildcards() = runBlocking {
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(acct, "s1", title = "50% off"),
                DbTestFixture.song(acct, "s2", title = "plain"),
                DbTestFixture.song(acct, "s3", title = "a_b"),
                DbTestFixture.song(acct, "s4", title = "axb"),
            ),
        )

        // % 作字面：仅命中含 "50%" 的行
        assertEquals(setOf("s1"), store.searchSongs(acct, "50%").first().map { it.id }.toSet())
        // _ 作字面：仅命中 "a_b" 不命中 "axb"
        assertEquals(setOf("s3"), store.searchSongs(acct, "a_b").first().map { it.id }.toSet())
    }

    @Test
    fun recentAlbums_joinsSyncStateOrderedLimit20() = runBlocking {
        db.albumDao().upsertRemote((0 until 25).map { DbTestFixture.album(acct, "al$it") })
        (0 until 25).forEach { db.albumSyncStateDao().setRecentIndex(acct, "al$it", it + 1) }

        val recent = store.recentAlbums(acct).first()
        assertEquals(20, recent.size)
        assertEquals(1, recent.first().recentIndex)
        assertEquals(20, recent.last().recentIndex)
    }

    @Test
    fun getAlbumIdsWithoutSyncedSongs_noStateRowMeansUnsynced() = runBlocking {
        db.albumDao().upsertRemote(listOf(DbTestFixture.album(acct, "al1"), DbTestFixture.album(acct, "al2"), DbTestFixture.album(acct, "al3")))
        db.albumSyncStateDao().setSongsSynced(acct, "al1", true)
        db.albumSyncStateDao().setSongsSynced(acct, "al2", false)
        // al3 无 album_sync_state 行 → COALESCE 视为未同步

        assertEquals(setOf("al2", "al3"), store.getAlbumIdsWithoutSyncedSongs(acct).toSet())
    }

    @Test
    fun cachedDerivations_albumArtistGenre() = runBlocking {
        db.artistDao().upsertRemote(listOf(DbTestFixture.artist(acct, "arA", "A"), DbTestFixture.artist(acct, "arB", "B")))
        db.albumDao().upsertRemote(listOf(DbTestFixture.album(acct, "alA", artistId = "arA"), DbTestFixture.album(acct, "alB", artistId = "arA")))
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(acct, "s1", albumId = "alA", artistId = "arB").copy(genre = "Rock"),
                DbTestFixture.song(acct, "s2", albumId = "alB", artistId = "arB").copy(genre = "Jazz"),
            ),
        )
        db.songLocalStateDao().setCachePath(acct, "s1", "songs/s1.mp3") // 仅 s1 缓存

        assertEquals(setOf("alA"), store.getCachedAlbumIds(acct).first())
        // 直接 artist_id=arB ∪ 经 album.artist_id=arA
        assertEquals(setOf("arA", "arB"), store.getCachedArtistIds(acct).first())
        assertEquals(setOf("Rock"), store.getCachedGenreNames(acct).first())
    }

    @Test
    fun getArtistSongsAndAlbums_unionDedup() = runBlocking {
        db.artistDao().upsertRemote(listOf(DbTestFixture.artist(acct, "ar1", "One")))
        db.albumDao().upsertRemote(listOf(DbTestFixture.album(acct, "al1", artistId = "ar1"), DbTestFixture.album(acct, "alX", artistId = null)))
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(acct, "s1", albumId = "al1", artistId = "ar1"),   // 两路命中
                DbTestFixture.song(acct, "s2", albumId = "al1", artistId = "other"),  // 仅 album 路
                DbTestFixture.song(acct, "s3", albumId = "alX", artistId = "ar1"),    // 仅直接路
            ),
        )

        assertEquals(setOf("s1", "s2", "s3"), store.getArtistSongs(acct, "ar1").first().map { it.id }.toSet())
        assertEquals(setOf("al1", "alX"), store.getArtistAlbums(acct, "ar1").first().map { it.id }.toSet())
    }
}
