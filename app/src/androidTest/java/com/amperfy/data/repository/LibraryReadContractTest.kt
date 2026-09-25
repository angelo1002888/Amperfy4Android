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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 库读路径合同。
 *
 * 走真实读路径：`MusicRepositoryImpl` 委托 `LibraryLocalStore`/`PlaylistLocalStore`
 * （批次 4 收口），本测试经 fixture 的绑定账户实例 `getAlbumArtists()`/`getPlaylistSongs()`
 * 读回断言，锁定两条易在换存储引擎时退化的合同：
 *
 * 1. Album Artists 只含名下有**直接关联专辑**的艺术家，
 *    合辑歌手（仅作为某专辑内某歌 song.artist）不得入列；跨账户隔离。
 * 2. `getPlaylistSongs` 的成员歌曲缓存状态变化必须在**同一收集器存活期间**触发再发射
 *    （下载完成后缓存图标实时刷新依赖它），不得靠重新订阅规避。Room 靠 song_local_state
 *    表级失效实现该再发射。
 *
 * P3 批次 1b：用例 1（Album Artists）seed 换 Room（getAlbumArtists 走 Room 谓词
 * EXISTS(album.artist_id = artist.server_id)）。P3 批次 2b：用例 2 也换 Room——playlist 域已迁移，
 * 触发介质为 song_local_state.cache_path（表级失效再发射）。
 */
@RunWith(AndroidJUnit4::class)
class LibraryReadContractTest {

    companion object {
        private const val ARTIST_X = "artistX"
        private const val ARTIST_Y = "artistY"
        private const val ARTIST_Z = "artistZ"
        private const val PLAYLIST_ID = "pl1"
        private const val SONG_ID = "s1"
    }

    private lateinit var fx: RepositoryFixture

    @Before
    fun setUp() {
        fx = RepositoryFixture.create()
    }

    @After
    fun tearDown() {
        fx.close()
    }

    /**
     * Album Artists 谓词合同：
     * - 账户 A 艺术家 X 名下有直接关联专辑 P（album.artist == X）——应入列；
     * - 账户 A 艺术家 Y 仅作为专辑 P 内某歌的 song.artist（合辑歌手），名下无直接专辑——应排除；
     * - 账户 B 艺术家 Z 名下有直接专辑（同构，但另一账户）——应因账户隔离排除。
     */
    @Test
    fun getAlbumArtists_excludesArtistsWithoutDirectAlbums() = runBlocking {
        // Room seed（关系经标量 artist_id/album_id 表达，非受管对象挂接）
        fx.db.artistDao().upsertRemote(
            listOf(
                DbTestFixture.artist(fx.accountA.ident, ARTIST_X, "Artist X"),
                DbTestFixture.artist(fx.accountA.ident, ARTIST_Y, "Artist Y"),
                // 账户 B 噪声：同构艺术家 Z 有直接关联专辑，锁定账户隔离
                DbTestFixture.artist(fx.accountB.ident, ARTIST_Z, "Artist Z"),
            )
        )
        fx.db.albumDao().upsertRemote(
            listOf(
                DbTestFixture.album(fx.accountA.ident, "albumP", "Album P", artistId = ARTIST_X),
                DbTestFixture.album(fx.accountB.ident, "albumZ", "Album Z", artistId = ARTIST_Z),
            )
        )
        // 专辑 P 内一首歌的 song.artist == Y（合辑歌手场景，Y 名下无直接专辑）
        fx.db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(fx.accountA.ident, "songPY", "P Song by Y", albumId = "albumP", artistId = ARTIST_Y),
            )
        )

        val albumArtistIds = fx.repositoryA.getAlbumArtists().first().map { it.id }.toSet()

        assertEquals(
            "Album Artists must contain only artists with a directly-linked album; " +
                "compilation-only singers (song.artist without album.artist) and other accounts' " +
                "artists must be excluded",
            setOf(ARTIST_X),
            albumArtistIds
        )
    }

    /**
     * 表级失效再发射合同：单一收集器存活期间，成员歌曲缓存状态由未缓存→已缓存时
     * getPlaylistSongs 必须再次发射（不重新订阅）。Room 版靠 song_local_state 表级失效
     * （getPlaylistSongs 经 observeSongsWithLocalOf 引用 song_local_state）。
     */
    @Test
    fun getPlaylistSongs_reEmitsWhenMemberSongDownloadStateChanges() = runBlocking {
        // Room 造数：播放列表 pl1 含歌曲 s1（未缓存），关系经 playlist_song 整表替换建立
        fx.db.playlistDao().upsertRemote(
            listOf(DbTestFixture.playlist(fx.accountA.ident, PLAYLIST_ID, "Test Playlist"))
        )
        fx.db.songDao().upsertRemote(
            listOf(DbTestFixture.song(fx.accountA.ident, SONG_ID, "Song 1"))
        )
        fx.db.playlistSongDao().replacePlaylistSongs(fx.accountA.ident, PLAYLIST_ID, listOf(SONG_ID))

        val channel = Channel<List<com.amperfy.data.model.Song>>(Channel.UNLIMITED)
        val collectJob = launch(Dispatchers.Default) {
            fx.repositoryA.getPlaylistSongs(PLAYLIST_ID).collect { channel.send(it) }
        }

        try {
            withTimeout(5000) {
                // 1. 首次发射：含 s1 且未下载
                val firstSong = channel.receive().firstOrNull { it.id == SONG_ID }
                assertNotNull("First emission must contain s1", firstSong)
                assertFalse("s1 must start as not downloaded", firstSong!!.isDownloaded)

                // 2. 同一收集器存活期间，写 song_local_state.cache_path 令 s1 变为已缓存
                fx.db.songLocalStateDao().setCachePath(fx.accountA.ident, SONG_ID, "songs/s1.mp3")

                // 3. 继续从同一收集器接收，直至 s1.isDownloaded == true（表级失效触发再发射）
                while (true) {
                    val s1 = channel.receive().firstOrNull { it.id == SONG_ID }
                    if (s1 != null && s1.isDownloaded) break
                }
            }
        } finally {
            collectJob.cancel()
        }
    }
}
