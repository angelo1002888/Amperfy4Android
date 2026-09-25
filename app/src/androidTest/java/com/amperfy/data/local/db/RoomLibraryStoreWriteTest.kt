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
import com.amperfy.data.local.store.AlbumListKind
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Genre
import com.amperfy.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RoomLibraryLocalStore 写路径合同。
 *
 * P5 起 Store 为 Room 单实现（过渡期的委托构造参数已删除），写方法全部直落 DAO。
 * setSongStarred 曾是唯一双写方法，P3 批次 4b 拆除双写、P5 删掉委托本身，只余 Room 侧断言。
 */
@RunWith(AndroidJUnit4::class)
class RoomLibraryStoreWriteTest {

    private lateinit var db: AmperfyDatabase
    private val acct = DbTestFixture.ACCT_A

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun store() = RoomLibraryLocalStore(db)

    private fun artist(id: String, name: String = "Name$id", starred: Long? = null, rating: Int = 0) =
        Artist(id = id, name = name, starred = starred, rating = rating)

    private fun album(id: String, artistId: String? = null) =
        Album(id = id, name = "Album$id", artist = "A", artistId = artistId)

    private fun song(id: String, albumId: String? = null, artistId: String? = null) =
        Song(id = id, title = "Song$id", artist = "A", album = "Al", duration = 1, path = "p", albumId = albumId, artistId = artistId)

    // ---- applyFavoriteSnapshot ----

    @Test
    fun applyFavoriteSnapshot_preservesArtistRating() = runBlocking {
        // 预置已评分艺术家（rating=5, starred=111）
        db.artistDao().upsertRemote(listOf(DbTestFixture.artist(acct, "ar1", "Old").copy(rating = 5, starredAt = 111)))

        store().applyFavoriteSnapshot(
            acct,
            artists = listOf(artist("ar1", name = "New", starred = 999, rating = 0)),
            albums = emptyList(),
            songs = emptyList(),
        )

        val ar1 = db.artistDao().getByServerId(acct, "ar1")!!
        assertEquals("New", ar1.name)      // 除 rating 外的列已更新
        assertEquals(999L, ar1.starredAt)
        assertEquals(5, ar1.rating)         // rating 保留
    }

    @Test
    fun applyFavoriteSnapshot_reverseClearsStaleStars() = runBlocking {
        db.artistDao().upsertRemote(
            listOf(
                DbTestFixture.artist(acct, "ar1", "K").copy(starredAt = 100),
                DbTestFixture.artist(acct, "ar3", "S").copy(starredAt = 222),
            ),
        )
        // 快照只含 ar1 → ar3 应被取消收藏
        store().applyFavoriteSnapshot(acct, artists = listOf(artist("ar1", starred = 100)), albums = emptyList(), songs = emptyList())

        assertNotNull(db.artistDao().getByServerId(acct, "ar1")!!.starredAt)
        assertNull(db.artistDao().getByServerId(acct, "ar3")!!.starredAt)
    }

    @Test
    fun applyFavoriteSnapshot_emptyListClearsAll() = runBlocking {
        db.artistDao().upsertRemote(listOf(DbTestFixture.artist(acct, "ar1", "K").copy(starredAt = 100)))
        // 空列表 = 全清（NOT IN 空表语义）
        store().applyFavoriteSnapshot(acct, artists = emptyList(), albums = emptyList(), songs = emptyList())
        assertNull(db.artistDao().getByServerId(acct, "ar1")!!.starredAt)
    }

    @Test
    fun applyFavoriteSnapshot_preservesSongLocalState() = runBlocking {
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, "s1")))
        db.songLocalStateDao().setCachePath(acct, "s1", "songs/s1.mp3")

        store().applyFavoriteSnapshot(acct, artists = emptyList(), albums = emptyList(), songs = listOf(song("s1")))

        // 远端全行 upsert 不触碰本地状态表（结构性保留）
        assertEquals("songs/s1.mp3", db.songLocalStateDao().get(acct, "s1")!!.cachePath)
    }

    // ---- applyAlbumListPage ----

    @Test
    fun applyAlbumListPage_offset0_rewritesIndexesLeavingSyncedFlag() = runBlocking {
        db.albumSyncStateDao().setSongsSynced(acct, "al1", true)

        store().applyAlbumListPage(acct, AlbumListKind.NEWEST, offset = 0, albums = listOf(album("al1"), album("al2"), album("al3")))

        assertEquals(1, db.albumSyncStateDao().getByAlbumId(acct, "al1")!!.newestIndex)
        assertEquals(2, db.albumSyncStateDao().getByAlbumId(acct, "al2")!!.newestIndex)
        assertEquals(3, db.albumSyncStateDao().getByAlbumId(acct, "al3")!!.newestIndex)
        // is_songs_synced 不被 applyAlbumListPage 触动
        assertEquals(true, db.albumSyncStateDao().getByAlbumId(acct, "al1")!!.isSongsSynced)
    }

    @Test
    fun applyAlbumListPage_offsetAppendsWithoutClearing() = runBlocking {
        val s = store()
        s.applyAlbumListPage(acct, AlbumListKind.NEWEST, 0, listOf(album("al1"), album("al2"), album("al3")))
        s.applyAlbumListPage(acct, AlbumListKind.NEWEST, 3, listOf(album("al4"), album("al5")))

        assertEquals(1, db.albumSyncStateDao().getByAlbumId(acct, "al1")!!.newestIndex)
        assertEquals(4, db.albumSyncStateDao().getByAlbumId(acct, "al4")!!.newestIndex)
        assertEquals(5, db.albumSyncStateDao().getByAlbumId(acct, "al5")!!.newestIndex)
    }

    @Test
    fun applyAlbumListPage_offset0ClearsPreviousIndexes() = runBlocking {
        val s = store()
        s.applyAlbumListPage(acct, AlbumListKind.NEWEST, 0, listOf(album("al1"), album("al2"), album("al3")))
        s.applyAlbumListPage(acct, AlbumListKind.NEWEST, 0, listOf(album("al2")))

        assertEquals(0, db.albumSyncStateDao().getByAlbumId(acct, "al1")!!.newestIndex)
        assertEquals(1, db.albumSyncStateDao().getByAlbumId(acct, "al2")!!.newestIndex)
        assertEquals(0, db.albumSyncStateDao().getByAlbumId(acct, "al3")!!.newestIndex)
    }

    // ---- upsertAlbumSongs ----

    @Test
    fun upsertAlbumSongs_marksSyncedAndKeepsLocalState() = runBlocking {
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, "s1", albumId = "al1")))
        db.songLocalStateDao().setCachePath(acct, "s1", "songs/s1.mp3")

        store().upsertAlbumSongs(acct, "al1", listOf(song("s1", albumId = "al1")))

        assertEquals(true, db.albumSyncStateDao().getByAlbumId(acct, "al1")!!.isSongsSynced)
        assertEquals("songs/s1.mp3", db.songLocalStateDao().get(acct, "s1")!!.cachePath)
    }

    // ---- replaceGenres ----

    @Test
    fun replaceGenres_diffDeletesAndUpserts() = runBlocking {
        db.genreDao().upsertRemote(listOf(DbTestFixture.genre(acct, "Rock"), DbTestFixture.genre(acct, "Jazz")))

        store().replaceGenres(acct, listOf(Genre("Jazz", 1, 2), Genre("Pop", 0, 0)))

        assertNull(db.genreDao().getByName(acct, "Rock"))
        assertNotNull(db.genreDao().getByName(acct, "Jazz"))
        assertNotNull(db.genreDao().getByName(acct, "Pop"))
    }

    // ---- setSongStarred 纯 Room 写 ----

    /**
     * P3 批次 4b 拆除双写、P5 删除委托构造参数后的最终形态：写只落 Room。
     * Room 侧写入生效的断言自双写时代起逐字不变。
     */
    @Test
    fun setSongStarred_writesRoomOnly() = runBlocking {
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, "s1")))

        store().setSongStarred(acct, "s1", 555)

        assertEquals(555L, db.songDao().getByServerId(acct, "s1")!!.starredAt)
    }
}
