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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ArtistWithCounts 聚合投影合同。
 *
 * 锁定的统计口径：
 * - 直接专辑计数（album.artist_id = X）；
 * - 合辑歌手（只出现在他人专辑歌曲中）的 albumCount/songCount；
 * - 两路并集去重（既有名下专辑又有合辑歌，同一歌曲经直接/album 两路只计一次）；
 * - duration 为相关歌曲时长和；
 * - 跨账户隔离（相同 server_id 不串账户）。
 */
@RunWith(AndroidJUnit4::class)
class ArtistCountsProjectionTest {

    private lateinit var db: AmperfyDatabase

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 布置：
     * - ar1 拥有 al1、alC 两张专辑；al1 含 s1/s2（artist=ar1），alC 含 sC（artist=ar2，合辑客串）。
     * - ar2 无名下专辑，仅以 sC 出现在 alC。
     * - ar3 无任何关联。
     */
    private suspend fun seedLibrary(accountId: String = DbTestFixture.ACCT_A) {
        db.artistDao().upsertRemote(
            listOf(
                DbTestFixture.artist(accountId, serverId = "ar1", name = "Artist1"),
                DbTestFixture.artist(accountId, serverId = "ar2", name = "Artist2"),
                DbTestFixture.artist(accountId, serverId = "ar3", name = "Artist3"),
            ),
        )
        db.albumDao().upsertRemote(
            listOf(
                DbTestFixture.album(accountId, serverId = "al1", artistId = "ar1"),
                DbTestFixture.album(accountId, serverId = "alC", artistId = "ar1"),
            ),
        )
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(accountId, serverId = "s1", albumId = "al1", artistId = "ar1").copy(duration = 100),
                DbTestFixture.song(accountId, serverId = "s2", albumId = "al1", artistId = "ar1").copy(duration = 200),
                DbTestFixture.song(accountId, serverId = "sC", albumId = "alC", artistId = "ar2").copy(duration = 50),
            ),
        )
    }

    @Test
    fun ownerArtist_unionDedupAndDuration() = runBlocking {
        seedLibrary()
        val ar1 = db.artistDao().getWithCountsByServerId(DbTestFixture.ACCT_A, "ar1")!!
        // 相关专辑 = {al1, alC}（名下两张）；直接歌曲 s1/s2 的 album_id 仅 al1，并集仍 2
        assertEquals(2, ar1.relatedAlbumCount)
        // 相关歌曲 = 直接 {s1,s2} ∪ 名下专辑歌曲 {s1,s2,sC} = {s1,s2,sC}，去重后 3
        assertEquals(3, ar1.relatedSongCount)
        // 时长 = 100 + 200 + 50
        assertEquals(350, ar1.relatedDuration)
    }

    @Test
    fun compilationGuestArtist_countsFromForeignAlbumSong() = runBlocking {
        seedLibrary()
        val ar2 = db.artistDao().getWithCountsByServerId(DbTestFixture.ACCT_A, "ar2")!!
        // 无名下专辑，但直接歌曲 sC 的 album_id=alC 计入相关专辑
        assertEquals(1, ar2.relatedAlbumCount)
        assertEquals(1, ar2.relatedSongCount)
        assertEquals(50, ar2.relatedDuration)
    }

    @Test
    fun unrelatedArtist_allZero() = runBlocking {
        seedLibrary()
        val ar3 = db.artistDao().getWithCountsByServerId(DbTestFixture.ACCT_A, "ar3")!!
        assertEquals(0, ar3.relatedAlbumCount)
        assertEquals(0, ar3.relatedSongCount)
        assertEquals(0, ar3.relatedDuration)
    }

    @Test
    fun crossAccount_isolated() = runBlocking {
        seedLibrary(DbTestFixture.ACCT_A)
        // B 账户下相同 server_id 但只有 1 首歌
        db.artistDao().upsertRemote(listOf(DbTestFixture.artist(DbTestFixture.ACCT_B, serverId = "ar1", name = "Artist1")))
        db.albumDao().upsertRemote(listOf(DbTestFixture.album(DbTestFixture.ACCT_B, serverId = "al1", artistId = "ar1")))
        db.songDao().upsertRemote(
            listOf(DbTestFixture.song(DbTestFixture.ACCT_B, serverId = "s1", albumId = "al1", artistId = "ar1").copy(duration = 7)),
        )

        val aArtist = db.artistDao().getWithCountsByServerId(DbTestFixture.ACCT_A, "ar1")!!
        val bArtist = db.artistDao().getWithCountsByServerId(DbTestFixture.ACCT_B, "ar1")!!
        assertEquals(3, aArtist.relatedSongCount)
        assertEquals(350, aArtist.relatedDuration)
        assertEquals(1, bArtist.relatedSongCount)
        assertEquals(7, bArtist.relatedDuration)
    }
}
