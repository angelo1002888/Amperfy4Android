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
import com.amperfy.data.local.db.entity.SongEntity
import com.amperfy.data.local.db.entity.SongLocalStateEntity
import com.amperfy.data.remote.dto.DirectoryDto
import com.amperfy.data.remote.dto.DirectoryResponse
import com.amperfy.data.remote.dto.SongDto
import com.amperfy.data.remote.dto.SubsonicResponse
import com.amperfy.testutil.RepositoryFixture
import io.mockk.coEvery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * 目录同步差集/本地所有权合同。
 *
 * 锁定 `replaceDirectoryChildren` 的两条冻结语义：
 * 1. 服务器负载中消失的歌曲**仅解除目录关联，不删除歌曲实体**
 *    （iOS removeFromSongs，本地下载/缓存不受影响）；
 * 2. 目录重同步时逐首 upsert **保留本地所有权字段**（缓存路径/播放次数/播放进度），
 *    远端快照不得覆盖。
 *
 * 走真实同步路径：coEvery 显式 stub [com.amperfy.data.remote.SubsonicApi.requestMusicDirectory]
 * 返回构造 DirectoryResponse（status=ok，通过 requireOk 校验），经
 * `MusicRepositoryImpl.syncDirectory` → `replaceDirectoryChildren` 落库后读回断言。
 *
 * **P3 批次 4b 换绑 Room**：directory 域读写已走 [com.amperfy.data.local.db.store.RoomLibraryLocalStore]
 * （生产 fixture 的 libraryLocalStore 即该 Room 实现），故 seed 改用 [DbTestFixture] 行构造器 +
 * `fx.db` 各 DAO，断言改读 `fx.db` DAO 行。两条冻结语义逐条不变，
 * 介质差异带来的等义替换（本地所有权在 song_local_state 分表、目录关联在 song_directory 分表）：
 * - isDownloaded → song_local_state.cache_path 非空；downloadPath → cache_path 值；
 * - playCount → song_local_state.play_count（无任意值写入口，经 incrementPlayCount 累加到期望值）；
 * - playProgressMs → song_local_state.play_progress_ms；
 * - directoryId → [com.amperfy.data.local.db.dao.SongDirectoryDao.getSongIdsIn] 是否含该歌；
 * - 「实体存续」→ song 表行仍可 getByServerId 取到。
 */
@RunWith(AndroidJUnit4::class)
class DirectorySyncContractTest {

    companion object {
        private const val DIRECTORY_ID = "D"
        private const val LOCAL_DOWNLOAD_PATH = "/data/cache/s1.mp3"
        private const val LOCAL_PLAY_COUNT = 3
        private const val LOCAL_PROGRESS_MS = 1234L
        private const val SERVER_PLAY_COUNT = 99
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

    /** getMusicDirectory 的 <child isDir=false> 歌曲元素（playCount 用服务端值以验证保留） */
    private fun songChild(id: String, title: String) = SongDto(
        id = id,
        title = title,
        duration = 100,
        playCount = SERVER_PLAY_COUNT,
        isDir = false,
    )

    /** stub 账户 A 的 getMusicDirectory 响应（status=ok，通过 requireOk 校验） */
    private fun stubDirectory(children: List<SongDto>) {
        coEvery {
            fx.subsonicApiA.requestMusicDirectory(any(), any())
        } returns Response.success(
            SubsonicResponse(
                DirectoryResponse(
                    status = "ok",
                    directory = DirectoryDto(
                        id = DIRECTORY_ID,
                        name = "Test Directory",
                        child = children,
                    ),
                )
            )
        )
    }

    /**
     * seed 一首归属目录 D 的本地歌曲，带完整本地所有权字段（经 Room DAO）。
     * play_count 无任意值写入口（服务器同步物理上不能触碰该列），故调 incrementPlayCount 累加
     * 到 [LOCAL_PLAY_COUNT]。
     */
    private suspend fun seedSongInDirectory() {
        val acct = fx.accountA.ident
        fx.db.songDao().upsertRemote(
            listOf(DbTestFixture.song(accountId = acct, serverId = "s1", title = "Old One")),
        )
        fx.db.songLocalStateDao().setCachePath(acct, "s1", LOCAL_DOWNLOAD_PATH)
        repeat(LOCAL_PLAY_COUNT) { fx.db.songLocalStateDao().incrementPlayCount(acct, "s1") }
        fx.db.songLocalStateDao().setProgress(acct, "s1", LOCAL_PROGRESS_MS, LOCAL_PROGRESS_MS)
        fx.db.songDirectoryDao().setDirectory(acct, "s1", DIRECTORY_ID)
    }

    /** 直接读 Room 层歌曲行（判实体存续） */
    private suspend fun findSong(serverId: String): SongEntity? =
        fx.db.songDao().getByServerId(fx.accountA.ident, serverId)

    /** 直接读 Room 层本地状态行（缓存/播放次数/播放进度不在远端快照表上） */
    private suspend fun findLocalState(serverId: String): SongLocalStateEntity? =
        fx.db.songLocalStateDao().get(fx.accountA.ident, serverId)

    /** 目录关联（song_directory 关系表） */
    private suspend fun linkedSongIds(): List<String> =
        fx.db.songDirectoryDao().getSongIdsIn(fx.accountA.ident, DIRECTORY_ID)

    private suspend fun directorySongIds(): List<String> =
        fx.repositoryA.getDirectorySongs(DIRECTORY_ID).first().map { it.id }

    // ==================== 用例 ====================

    @Test
    fun directorySync_unlinksMissingSongsWithoutDeletingThem() = runBlocking {
        seedSongInDirectory()
        // 服务器负载不含 s1（只含另一首 s2）：s1 应被解除目录关联但实体存续
        stubDirectory(listOf(songChild("s2", "Two")))

        val result = fx.repositoryA.syncDirectory(DIRECTORY_ID)

        assertTrue("syncDirectory must succeed with stubbed ok response", result.isSuccess)
        assertFalse(
            "s1 must be unlinked from directory D (no longer returned by getDirectorySongs)",
            directorySongIds().contains("s1")
        )
        assertFalse(
            "s1 must have no song_directory row for D anymore",
            linkedSongIds().contains("s1")
        )
        assertNotNull("s1 entity must still exist after being unlinked (iOS removeFromSongs)", findSong("s1"))
        assertEquals(
            "Unlinked song must keep its local cache state (only directory link removed)",
            LOCAL_DOWNLOAD_PATH,
            findLocalState("s1")?.cachePath
        )
    }

    @Test
    fun directorySync_preservesLocalOwnershipFieldsOnResync() = runBlocking {
        seedSongInDirectory()
        // 服务器负载仍含 s1（带服务端 playCount=99）：upsert 必须保留本地所有权字段
        stubDirectory(listOf(songChild("s1", "New One")))

        val result = fx.repositoryA.syncDirectory(DIRECTORY_ID)

        assertTrue("syncDirectory must succeed with stubbed ok response", result.isSuccess)
        assertNotNull("s1 must survive the re-sync", findSong("s1"))

        val localState = findLocalState("s1")
        assertNotNull("s1 local state row must survive the re-sync", localState)
        assertEquals(
            "Contract §3.3/§10: local cache path must survive the directory re-sync",
            LOCAL_DOWNLOAD_PATH,
            localState!!.cachePath
        )
        assertEquals(
            "Contract §3.3/§10: local playCount must not be overwritten by server value",
            LOCAL_PLAY_COUNT,
            localState.playCount
        )
        assertEquals(
            "Contract §3.3/§10: local playProgressMs must survive the directory re-sync",
            LOCAL_PROGRESS_MS,
            localState.playProgressMs
        )
        assertTrue(
            "s1 must stay linked to directory D after the re-sync",
            linkedSongIds().contains("s1")
        )
    }
}
