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
import com.amperfy.data.remote.dto.AlbumDetailDto
import com.amperfy.data.remote.dto.AlbumDetailResponse
import com.amperfy.data.remote.dto.PlaylistDetailDto
import com.amperfy.data.remote.dto.PlaylistDetailResponse
import com.amperfy.data.remote.dto.PlaylistDto
import com.amperfy.data.remote.dto.PlaylistsContainer
import com.amperfy.data.remote.dto.PlaylistsResponse
import com.amperfy.data.remote.dto.SongDto
import com.amperfy.data.remote.dto.SubsonicError
import com.amperfy.data.remote.dto.SubsonicResponse
import com.amperfy.testutil.RepositoryFixture
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * 远端/本地字段所有权合同。
 *
 * 合同：远端同步 upsert 只拥有服务端快照字段（标题/名称/评分等），不得覆盖本地状态字段
 * （下载状态、播放进度、本地播放计数、同步标志、lastPlayed）。全部用例走真实同步路径
 * （coEvery 显式 stub SubsonicApi 返回构造 DTO），不直接写库模拟 upsert。
 *
 * 实际调用链（本批次读码确认）：
 * - `syncAlbumDetails` → requireOk(requestAlbum).album（AlbumDetailResponse 实现
 *   SubsonicStatusResponse，status=failed 抛异常转 Result.failure）→
 *   upsertAlbum（显式保留 isCached/isSongsSynced/newestIndex/recentIndex）→ 逐首
 *   歌曲 upsert（显式保留 isDownloaded/downloadPath/
 *   directoryId/playProgressMs/playProgressUpdatedAt/playCount）→ 末尾 isSongsSynced = true；
 * - `syncPlaylists` → requestPlaylists → upsertPlaylistMetadata（逐字段赋值，不触碰
 *   songs 与 lastPlayed）+ 差集 prune（限本账户，seed 的列表须在 stub 响应中出现）；
 * - `syncPlaylistDetails` → requireOk(requestPlaylistSongs).playlist → 逐字段赋值元数据 +
 *   整表重建 songs，同样不触碰 lastPlayed。
 *
 * 播放进度/本地播放计数的保留合同已在歌曲 upsert 点位落地：
 * 已有本地记录时 playProgressMs/playProgressUpdatedAt/playCount 一律保留本地值
 * （playCount 本地独占、开播 +1，对齐 iOS SsSongParserDelegate 不导入服务端计数）。见
 * [remoteUpsert_preservesPlayProgress] / [remoteUpsert_preservesLocalPlayCount]。
 *
 * P3 批次 1b：album/song 相关用例 seed 换 Room——远端快照走 album/song 表、本地状态走
 * song_local_state（cache_path/play_progress/play_count）与 album_sync_state（is_songs_synced/
 * newest/recent index），isCached 为 AlbumWithState 计算列（名下有已缓存歌曲 = 物理隔离下
 * 的结构性保留）。P3 批次 2b：两个 playlist 用例 seed 也换 Room——远端快照走 playlist 表、
 * 本地 lastPlayed 走 playlist_local_state（syncPlaylists/syncPlaylistDetails 走 RoomPlaylistLocalStore，
 * lastPlayed 保留合同由表级物理隔离保证）。断言：域模型字段经 repo 读回，
 * playProgress/is_songs_synced 等不在域模型的字段直接读 song_local_state / album_sync_state 行。
 */
@RunWith(AndroidJUnit4::class)
class OwnershipContractTest {

    companion object {
        private const val ALBUM_ID = "al1"
        private const val SONG_ID = "s1"
        private const val PLAYLIST_ID = "pl1"
        private const val LOCAL_DOWNLOAD_PATH = "/data/cache/s1.mp3"
        private const val LOCAL_PROGRESS_MS = 123_456L
        private const val LOCAL_PLAY_COUNT = 7
        private const val SERVER_PLAY_COUNT = 99
        private const val LOCAL_LAST_PLAYED = 1_720_000_000_000L
    }

    private lateinit var fx: RepositoryFixture

    @Before
    fun setUp() {
        fx = RepositoryFixture.create()
        seedLocalState()
    }

    @After
    fun tearDown() {
        fx.close()
    }

    private val accountA get() = fx.accountA.ident

    /**
     * seed 本地状态：
     * - 专辑 al1（Room）：isSongsSynced=true、newestIndex=3、recentIndex=2；isCached=true 经名下
     *   已缓存歌曲 s1（album_id=al1 + cache_path 非空）派生（Room 无陈旧 isCached 存储列）；
     * - 歌曲 s1（Room）：cache_path=downloadPath（isDownloaded=true）、playProgressMs、本地 playCount；
     * - 播放列表 pl1（Room）：远端快照 + playlist_local_state.last_played 非空。
     */
    private fun seedLocalState() = runBlocking {
        // Room：专辑远端快照 + 同步态
        fx.db.albumDao().upsertRemote(listOf(DbTestFixture.album(accountA, ALBUM_ID, "Old Album")))
        fx.db.albumSyncStateDao().setSongsSynced(accountA, ALBUM_ID, true)
        fx.db.albumSyncStateDao().setNewestIndex(accountA, ALBUM_ID, 3)
        fx.db.albumSyncStateDao().setRecentIndex(accountA, ALBUM_ID, 2)

        // Room：歌曲远端快照（挂 al1，令 isCached(al1) 经缓存推导为 true）+ 本地状态
        fx.db.songDao().upsertRemote(
            listOf(DbTestFixture.song(accountA, SONG_ID, "Old Title", albumId = ALBUM_ID))
        )
        fx.db.songLocalStateDao().setCachePath(accountA, SONG_ID, LOCAL_DOWNLOAD_PATH)
        fx.db.songLocalStateDao().setProgress(accountA, SONG_ID, LOCAL_PROGRESS_MS, LOCAL_PROGRESS_MS)
        // play_count 无直接 setter（本地独占、开播 +1），按语义 +1 累计到目标值
        repeat(LOCAL_PLAY_COUNT) { fx.db.songLocalStateDao().incrementPlayCount(accountA, SONG_ID) }

        // Room：播放列表远端快照 + 本地 lastPlayed（playlist 域 batch 2b 起走 Room）。
        // lastPlayed 是纯本地状态，落 playlist_local_state；远端 upsert 物理隔离于该表，合同由
        // 表级隔离保证。
        fx.db.playlistDao().upsertRemote(
            listOf(DbTestFixture.playlist(accountA, PLAYLIST_ID, "Old Playlist"))
        )
        fx.db.playlistLocalStateDao().setLastPlayed(accountA, PLAYLIST_ID, LOCAL_LAST_PLAYED)
        Unit
    }

    // ==================== stub 辅助 ====================

    /** stub getAlbum：服务端下发新标题的专辑 + 新元数据的歌曲（含服务端 playCount） */
    private fun stubAlbumDetail() {
        coEvery {
            fx.subsonicApiA.requestAlbum(any(), any())
        } returns Response.success(
            SubsonicResponse(
                AlbumDetailResponse(
                    status = "ok",
                    album = AlbumDetailDto(
                        id = ALBUM_ID,
                        name = "New Album",
                        songCount = 1,
                        duration = 100,
                        song = listOf(
                            SongDto(
                                id = SONG_ID,
                                title = "New Title",
                                duration = 100,
                                playCount = SERVER_PLAY_COUNT,
                            )
                        ),
                    ),
                )
            )
        )
    }

    /** stub getAlbum：HTTP 200 + status="failed"（Subsonic 业务失败形态，携带新数据以验证不落库） */
    private fun stubAlbumDetailFailedStatus() {
        coEvery {
            fx.subsonicApiA.requestAlbum(any(), any())
        } returns Response.success(
            SubsonicResponse(
                AlbumDetailResponse(
                    status = "failed",
                    album = AlbumDetailDto(
                        id = ALBUM_ID,
                        name = "New Album",
                        songCount = 1,
                        duration = 100,
                    ),
                    error = SubsonicError(code = 70, message = "The requested data was not found."),
                )
            )
        )
    }

    private fun stubPlaylists() {
        coEvery {
            fx.subsonicApiA.requestPlaylists(any())
        } returns Response.success(
            SubsonicResponse(
                PlaylistsResponse(
                    status = "ok",
                    playlists = PlaylistsContainer(
                        playlist = listOf(PlaylistDto(id = PLAYLIST_ID, name = "Renamed Playlist"))
                    ),
                )
            )
        )
    }

    private fun stubPlaylistDetail() {
        coEvery {
            fx.subsonicApiA.requestPlaylistSongs(any(), any())
        } returns Response.success(
            SubsonicResponse(
                PlaylistDetailResponse(
                    status = "ok",
                    playlist = PlaylistDetailDto(
                        id = PLAYLIST_ID,
                        name = "Renamed Playlist",
                        songCount = 0,
                        entry = emptyList(),
                    ),
                )
            )
        )
    }

    // 域模型经 repo 读回；playProgress/is_songs_synced 等不在域模型的字段直接读本地状态表行。
    private suspend fun findSong() = fx.repositoryA.getSongById(SONG_ID)

    private suspend fun findAlbum() = fx.repositoryA.getAlbumById(ALBUM_ID)

    private suspend fun findSongLocalState() =
        fx.db.songLocalStateDao().get(accountA, SONG_ID)

    private suspend fun findAlbumSyncState() =
        fx.db.albumSyncStateDao().getByAlbumId(accountA, ALBUM_ID)

    // ==================== 用例 ====================

    @Test
    fun albumDetailSync_updatesSongMetadataButKeepsDownloadState() = runBlocking {
        stubAlbumDetail()

        val result = fx.repositoryA.syncAlbumDetails(ALBUM_ID)

        assertTrue("syncAlbumDetails must succeed with stubbed response", result.isSuccess)
        val song = findSong()
        assertNotNull("Song must still exist after remote upsert", song)
        assertEquals("Remote-owned title must be updated by the sync", "New Title", song!!.title)
        assertTrue("Local isDownloaded must survive the remote upsert", song.isDownloaded)
        assertEquals(
            "Local downloadPath must survive the remote upsert",
            LOCAL_DOWNLOAD_PATH,
            song.downloadPath
        )
    }

    /**
     * 冻结合同：remote upsert 不得覆盖播放进度。
     * 歌曲 upsert 点位显式保留 playProgressMs/playProgressUpdatedAt
     * （本地所有权字段，远端快照不携带，靠 upsert 保留清单存续）。
     */
    @Test
    fun remoteUpsert_preservesPlayProgress() = runBlocking {
        stubAlbumDetail()

        assertTrue(fx.repositoryA.syncAlbumDetails(ALBUM_ID).isSuccess)

        // playProgress 不在域模型上——直接读 song_local_state 行（远端 upsert 物理不触本表）
        val local = findSongLocalState()
        assertNotNull(local)
        assertEquals(
            "Contract §3.3: remote upsert must preserve local playProgressMs",
            LOCAL_PROGRESS_MS,
            local!!.playProgressMs
        )
        assertEquals(
            "Contract §3.3: remote upsert must preserve local playProgressUpdatedAt",
            LOCAL_PROGRESS_MS,
            local.playProgressUpdatedAt
        )
    }

    /**
     * 冻结合同：playCount 本地独占（开播 +1，对齐 iOS SsSongParserDelegate
     * 不导入服务端计数）——已有本地记录时 upsert 保留本地值，不被服务端值覆盖；
     * 新歌仍以服务端值作种子（最小修复止血，是否彻底停止导入留到 Room 迁移）。
     */
    @Test
    fun remoteUpsert_preservesLocalPlayCount() = runBlocking {
        stubAlbumDetail()

        assertTrue(fx.repositoryA.syncAlbumDetails(ALBUM_ID).isSuccess)

        assertEquals(
            "Contract §3.3: local playCount must survive the remote upsert (not overwritten by server value)",
            LOCAL_PLAY_COUNT,
            findSong()!!.playCount
        )
    }

    @Test
    fun albumDetailSync_updatesAlbumMetadataButKeepsLocalSyncFlags() = runBlocking {
        stubAlbumDetail()

        assertTrue(fx.repositoryA.syncAlbumDetails(ALBUM_ID).isSuccess)

        val album = findAlbum()
        assertNotNull("Album must still exist after remote upsert", album)
        assertEquals("Remote-owned album name must be updated", "New Album", album!!.name)
        assertEquals("Local newestIndex must survive the remote upsert", 3, album.newestIndex)
        assertEquals("Local recentIndex must survive the remote upsert", 2, album.recentIndex)
        assertTrue("Local isCached must survive the remote upsert", album.isCached)
        // isSongsSynced 不在域模型上——直接读 album_sync_state 行
        assertTrue(
            "Local isSongsSynced must survive the remote upsert",
            findAlbumSyncState()?.isSongsSynced == true
        )
    }

    /**
     * status 校验合同：Subsonic 业务失败（HTTP 200 + status="failed"）必须被
     * requireOk 识别（AlbumDetailResponse 实现 SubsonicStatusResponse），
     * 同步返回 Result.failure（requireOk 抛 Exception 经调用方 try/catch 转换）且不落库。
     */
    @Test
    fun albumDetailSync_failedStatus_isRejectedAndLeavesDataUntouched() = runBlocking {
        stubAlbumDetailFailedStatus()

        val result = fx.repositoryA.syncAlbumDetails(ALBUM_ID)

        assertTrue("status=failed must be rejected via requireOk", result.isFailure)
        val cause = result.exceptionOrNull()
        assertNotNull("Failure must carry the requireOk exception", cause)
        assertTrue(
            "Exception must describe the Subsonic status failure, got: ${cause?.message}",
            cause!!.message.orEmpty().contains("status=failed")
        )
        assertEquals(
            "Local album must stay untouched after a rejected sync",
            "Old Album",
            findAlbum()!!.name
        )
    }

    @Test
    fun playlistListSync_updatesMetadataButKeepsLastPlayed() = runBlocking {
        stubPlaylists()

        val result = fx.repositoryA.syncPlaylists()

        assertTrue("syncPlaylists must succeed with stubbed response", result.isSuccess)
        val playlist = fx.repositoryA.getPlaylistById(PLAYLIST_ID)
        assertNotNull("Playlist must survive the sync (present in server payload, not pruned)", playlist)
        assertEquals("Remote-owned name must be updated", "Renamed Playlist", playlist!!.name)
        assertEquals(
            "Local-only lastPlayed must survive the metadata upsert",
            LOCAL_LAST_PLAYED,
            playlist.lastPlayed
        )
    }

    @Test
    fun playlistDetailSync_keepsLastPlayed() = runBlocking {
        stubPlaylistDetail()

        assertTrue(fx.repositoryA.syncPlaylistDetails(PLAYLIST_ID).isSuccess)

        val playlist = fx.repositoryA.getPlaylistById(PLAYLIST_ID)
        assertNotNull(playlist)
        assertEquals(
            "Local-only lastPlayed must survive the detail sync",
            LOCAL_LAST_PLAYED,
            playlist!!.lastPlayed
        )
    }
}
