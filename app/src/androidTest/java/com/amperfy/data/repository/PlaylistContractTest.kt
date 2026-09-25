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
import com.amperfy.data.remote.dto.EmptyResponse
import com.amperfy.data.remote.dto.PlaylistDetailDto
import com.amperfy.data.remote.dto.PlaylistDetailResponse
import com.amperfy.data.remote.dto.SongDto
import com.amperfy.data.remote.dto.SubsonicError
import com.amperfy.data.remote.dto.SubsonicResponse
import com.amperfy.testutil.RepositoryFixture
import io.mockk.coEvery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * 播放列表顺序/重复/整表替换/重排合同。
 *
 * 走真实同步路径：coEvery 显式 stub [com.amperfy.data.remote.SubsonicApi] 返回构造 DTO
 * （requestPlaylistSongs → PlaylistDetailResponse；requestPlaylistUpdate → EmptyResponse），
 * 经 `MusicRepositoryImpl.syncPlaylistDetails` / `updatePlaylistOrder` 落库后
 * 用 `getPlaylistSongs` Flow 读回断言。
 *
 * 实际调用链（批次 2b 读码确认，playlist 域已走 Room）：
 * - 详情同步：`syncPlaylistDetails` → requireOk(requestPlaylistSongs).playlist →
 *   RoomPlaylistLocalStore.applyPlaylistDetails 事务内 songDao.upsertRemote（本地状态在
 *   song_local_state 独立表，物理隔离结构性保留）→ playlistSongDao.replacePlaylistSongs
 *   整表替换有序关系（position=0..N-1）；
 * - 拖拽重排：PlaylistEditSheet → PlaylistEditViewModel.reorder →
 *   `appDelegate.music.updatePlaylistOrder(playlistId, orderedIds)` →
 *   **本地先行** reorderPlaylistSongs（replacePlaylistSongs 整表替换）后再上传
 *   requestPlaylistUpdate(songIndexToRemove=0..N-1, songIdToAdd=新序) requireOk。
 *
 * 已按冻结合同偏差 #1 收口（批次 2b）：iOS PlaylistDetailVC 的拖拽重排「先本地持久化、
 * 再上传，上传失败只记日志不回滚本地顺序」——Android `updatePlaylistOrder` 现为本地先行、
 * 失败不回滚。[reorderUploadFailure_localOrderPersistedNotRolledBack] 断言失败后本地已是新顺序。
 */
@RunWith(AndroidJUnit4::class)
class PlaylistContractTest {

    companion object {
        private const val PLAYLIST_ID = "pl1"
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

    // ==================== stub 辅助 ====================

    private fun songDto(id: String, title: String) = SongDto(
        id = id,
        title = title,
        artist = "Fixture Artist",
        duration = 100,
    )

    /** stub 账户 A 的 getPlaylist 详情响应（status=ok，通过 requireOk 校验） */
    private fun stubPlaylistDetail(entries: List<SongDto>, name: String = "Test Playlist") {
        coEvery {
            fx.subsonicApiA.requestPlaylistSongs(any(), any())
        } returns Response.success(
            SubsonicResponse(
                PlaylistDetailResponse(
                    status = "ok",
                    playlist = PlaylistDetailDto(
                        id = PLAYLIST_ID,
                        name = name,
                        songCount = entries.size,
                        duration = entries.sumOf { it.duration },
                        entry = entries,
                    ),
                )
            )
        )
    }

    /** stub 账户 A 的 updatePlaylist 响应（成功 status=ok / 失败 status=failed+error） */
    private fun stubPlaylistUpdate(ok: Boolean) {
        val body = if (ok) {
            EmptyResponse(status = "ok")
        } else {
            EmptyResponse(status = "failed", error = SubsonicError(50, "user not authorized"))
        }
        coEvery {
            fx.subsonicApiA.requestPlaylistUpdate(
                any(), any(), any(), any(), any(), any(), any()
            )
        } returns Response.success(SubsonicResponse(body))
    }

    private suspend fun readBackSongIds(): List<String> =
        fx.repositoryA.getPlaylistSongs(PLAYLIST_ID).first().map { it.id }

    // ==================== 用例 ====================

    @Test
    fun syncPlaylistDetails_preservesServerSongOrder() = runBlocking {
        stubPlaylistDetail(
            listOf(songDto("s1", "One"), songDto("s2", "Two"), songDto("s3", "Three"), songDto("s4", "Four"))
        )

        val result = fx.repositoryA.syncPlaylistDetails(PLAYLIST_ID)

        assertTrue("syncPlaylistDetails must succeed with stubbed ok response", result.isSuccess)
        assertEquals(
            "Songs must be read back in exactly the server payload order",
            listOf("s1", "s2", "s3", "s4"),
            readBackSongIds()
        )
        assertEquals(
            "Playlist songCount must match the synced entry count",
            4,
            fx.repositoryA.getPlaylistById(PLAYLIST_ID)?.songCount
        )
    }

    @Test
    fun syncPlaylistDetails_keepsDuplicateSongAtBothPositions() = runBlocking {
        // 同一 serverId 在列表中出现两次（服务端合法场景：不能以 (playlist_id, song_id) 去重）
        stubPlaylistDetail(
            listOf(songDto("s1", "One"), songDto("s2", "Two"), songDto("s1", "One"))
        )

        val result = fx.repositoryA.syncPlaylistDetails(PLAYLIST_ID)

        assertTrue("syncPlaylistDetails must succeed with stubbed ok response", result.isSuccess)
        assertEquals(
            "Duplicate song must appear twice at its exact positions",
            listOf("s1", "s2", "s1"),
            readBackSongIds()
        )
    }

    @Test
    fun resync_fullyReplacesPreviousOrderWithoutResidue() = runBlocking {
        stubPlaylistDetail(
            listOf(songDto("s1", "One"), songDto("s2", "Two"), songDto("s3", "Three"), songDto("s4", "Four"))
        )
        assertTrue(fx.repositoryA.syncPlaylistDetails(PLAYLIST_ID).isSuccess)

        // 服务器再次下发不同顺序/子集：整表替换，旧关系不得残留（后续服务端同步可覆盖）
        stubPlaylistDetail(listOf(songDto("s3", "Three"), songDto("s1", "One")))
        val result = fx.repositoryA.syncPlaylistDetails(PLAYLIST_ID)

        assertTrue("Second syncPlaylistDetails must succeed", result.isSuccess)
        assertEquals(
            "Re-sync must fully replace the old order with the new payload, no residue",
            listOf("s3", "s1"),
            readBackSongIds()
        )
        assertEquals(
            "songCount must reflect the replaced list",
            2,
            fx.repositoryA.getPlaylistById(PLAYLIST_ID)?.songCount
        )
    }

    @Test
    fun updatePlaylistOrder_movesFirstSongAndKeepsPositionsContiguous() = runBlocking {
        stubPlaylistDetail(listOf(songDto("s1", "One"), songDto("s2", "Two"), songDto("s3", "Three")))
        assertTrue(fx.repositoryA.syncPlaylistDetails(PLAYLIST_ID).isSuccess)

        // 把第 0 首移到第 2 位：[s1,s2,s3] → [s2,s3,s1]
        stubPlaylistUpdate(ok = true)
        val result = fx.repositoryA.updatePlaylistOrder(PLAYLIST_ID, listOf("s2", "s3", "s1"))

        assertTrue("updatePlaylistOrder must succeed when the server accepts the upload", result.isSuccess)
        assertEquals(
            "Reordered list must match the requested order with contiguous positions and no holes",
            listOf("s2", "s3", "s1"),
            readBackSongIds()
        )
        assertEquals(
            "songCount must stay unchanged after an equal-length reorder",
            3,
            fx.repositoryA.getPlaylistById(PLAYLIST_ID)?.songCount
        )
    }

    /**
     * 冻结合同收口（批次 2b）：拖拽重排本地先行——先 replacePlaylistSongs 整表替换
     * 本地顺序，再上传。上传失败（status=failed）时**不回滚**本地顺序（对齐 iOS PlaylistDetailVC
     * 失败只记日志），故失败后本地已是新顺序 [s2, s3, s1]。
     */
    @Test
    fun reorderUploadFailure_localOrderPersistedNotRolledBack() = runBlocking {
        stubPlaylistDetail(listOf(songDto("s1", "One"), songDto("s2", "Two"), songDto("s3", "Three")))
        assertTrue(fx.repositoryA.syncPlaylistDetails(PLAYLIST_ID).isSuccess)

        stubPlaylistUpdate(ok = false)
        val result = fx.repositoryA.updatePlaylistOrder(PLAYLIST_ID, listOf("s2", "s3", "s1"))

        assertTrue("updatePlaylistOrder must report failure when the server rejects", result.isFailure)
        assertEquals(
            "Frozen contract §3.3 (local-first): a failed upload must NOT roll back the locally " +
                "persisted new order",
            listOf("s2", "s3", "s1"),
            readBackSongIds()
        )
    }
}
