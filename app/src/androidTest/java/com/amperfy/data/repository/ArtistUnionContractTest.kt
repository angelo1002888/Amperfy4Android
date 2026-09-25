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

package com.amperfy.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.DbTestFixture
import com.amperfy.testutil.RepositoryFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Artist 相关 Song/Album 并集合同。
 *
 * ArtistDetailViewModel 数据源（实际调用链）：
 * - baseSongs  = appDelegate.music.getArtistSongs(artistId)
 * - baseAlbums = appDelegate.music.getArtistAlbums(artistId)
 *
 * 合同（与 iOS ArtistAlbumsItemsFetchedResultsController 语义一致）：
 * - Songs  = { song | song.artist == X OR song.album.artist == X } 并集，按 serverId 去重；
 * - Albums = { album | album.artist == X } ∪ { song.album | song.artist == X } 并集，按 id 去重；
 * - 同账户约束（accountId 过滤），跨账户同 serverId 的艺术家互不可见。
 *
 * P3 批次 1b：seed 换 Room（关系经标量 artist_id/album_id 表达），getArtistSongs/getArtistAlbums
 * 走 Room override（观察式 UNION 查询）复测；场景/断言语义逐条不变。
 */
@RunWith(AndroidJUnit4::class)
class ArtistUnionContractTest {

    companion object {
        private const val ARTIST_X = "artistX"
        private const val ARTIST_OTHER = "artistOther"
    }

    private lateinit var fx: RepositoryFixture

    @Before
    fun setUp() {
        fx = RepositoryFixture.create()
        seedFixtures()
    }

    @After
    fun tearDown() {
        fx.close()
    }

    /**
     * 账户 A 场景数据：
     * - 艺术家 X 直接挂 2 首无专辑单曲 songS1/songS2（song.artist == X，album == null）；
     * - 专辑 P（album.artist == X）：
     *   - songP1：artist == X 且 album.artist == X ——同时命中两支，验证去重；
     *   - songP2：artist == Other ——仅经 album.artist == X 一支计入 Songs；
     * - 专辑 Q（album.artist == Other）：
     *   - songQ1：artist == X ——经 song.artist == X 一支计入 Songs，并把 Q 拉进 Albums；
     * - 账户 B 噪声：同 serverId 的艺术家 X 挂 1 首歌，锁定并集查询的账户隔离。
     */
    private fun seedFixtures() = runBlocking {
        val a = fx.accountA.ident
        val b = fx.accountB.ident
        fx.db.artistDao().upsertRemote(
            listOf(
                DbTestFixture.artist(a, ARTIST_X, "Artist X"),
                DbTestFixture.artist(a, ARTIST_OTHER, "Other Artist"),
                // 账户 B 噪声：同 serverId 艺术家
                DbTestFixture.artist(b, ARTIST_X, "Artist X of B"),
            )
        )
        fx.db.albumDao().upsertRemote(
            listOf(
                DbTestFixture.album(a, "albumP", "Album P", artistId = ARTIST_X),
                DbTestFixture.album(a, "albumQ", "Album Q", artistId = ARTIST_OTHER),
            )
        )
        fx.db.songDao().upsertRemote(
            listOf(
                // 艺术家 X 直接挂 2 首无专辑单曲（song.artist == X，album == null）
                DbTestFixture.song(a, "songS1", "Single 1", artistId = ARTIST_X),
                DbTestFixture.song(a, "songS2", "Single 2", artistId = ARTIST_X),
                // 专辑 P（album.artist == X）：P1 同时命中两支（验证去重）、P2 仅经 album.artist == X
                DbTestFixture.song(a, "songP1", "P Song by X", albumId = "albumP", artistId = ARTIST_X),
                DbTestFixture.song(a, "songP2", "P Song by Other", albumId = "albumP", artistId = ARTIST_OTHER),
                // 专辑 Q（album.artist == Other）：Q1 经 song.artist == X 计入并把 Q 拉进 Albums
                DbTestFixture.song(a, "songQ1", "Q Song by X", albumId = "albumQ", artistId = ARTIST_X),
                // 账户 B 噪声：同 serverId 艺术家的歌曲
                DbTestFixture.song(b, "songB1", "B Song", artistId = ARTIST_X),
            )
        )
        Unit
    }

    @Test
    fun artistSongs_isUnionOfSongArtistAndAlbumArtistBranches() = runBlocking {
        val songs = fx.repositoryA.getArtistSongs(ARTIST_X).first()

        assertEquals(
            "Artist songs must be the deduplicated union of " +
                "{song.artist == X} and {song.album.artist == X}",
            setOf("songS1", "songS2", "songP1", "songP2", "songQ1"),
            songs.map { it.id }.toSet()
        )
        // songP1 同时命中两支，去重后总数必须仍是 5
        assertEquals(
            "A song matching both branches must be counted exactly once",
            5,
            songs.size
        )
    }

    @Test
    fun artistAlbums_isUnionOfAlbumArtistAndSongArtistBranches() = runBlocking {
        val albums = fx.repositoryA.getArtistAlbums(ARTIST_X).first()

        assertEquals(
            "Artist albums must be the deduplicated union of " +
                "{album.artist == X} and {album of songs with artist == X}",
            setOf("albumP", "albumQ"),
            albums.map { it.id }.toSet()
        )
        // albumP 同时命中两支（album.artist==X 且含 artist==X 的歌），去重后总数必须是 2
        assertEquals(
            "An album matching both branches must appear exactly once",
            2,
            albums.size
        )
    }

    @Test
    fun artistUnionQueries_areAccountIsolated() = runBlocking {
        val songsB = fx.repositoryB.getArtistSongs(ARTIST_X).first()

        assertEquals(
            "Account B's artist with the same serverId must only see B's songs",
            setOf("songB1"),
            songsB.map { it.id }.toSet()
        )
        assertEquals(
            "Account B's artist must have no albums",
            emptySet<String>(),
            fx.repositoryB.getArtistAlbums(ARTIST_X).first().map { it.id }.toSet()
        )
    }
}
