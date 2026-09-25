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
import com.amperfy.data.remote.dto.SongDto
import com.amperfy.data.remote.dto.Starred2Container
import com.amperfy.data.remote.dto.StarredResponse
import com.amperfy.data.remote.dto.SubsonicResponse
import com.amperfy.testutil.RepositoryFixture
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * 收藏同步差集清理合同。
 *
 * 锁定 `syncFavoriteElements` 的反向差集清理语义（写块位于
 * LibraryLocalStore.applyFavoriteSnapshot）：
 * 1. 本地已收藏但不在服务器 getStarred2 列表中的条目，取消收藏标记（starred=null），
 *    **限本账户**——他账户的收藏不受影响（对应 iOS
 *    SubsonicLibrarySyncer.syncFavoriteLibraryElements 差集处理）；
 * 2. 服务器仍收藏的歌曲逐首 upsert 时**保留本地所有权字段**（isDownloaded 等），
 *    远端快照不得覆盖。
 *
 * 走真实同步路径：coEvery 显式 stub [com.amperfy.data.remote.SubsonicApi.requestFavoriteElements]
 * 返回构造 StarredResponse（status=ok，通过 requireOk 校验），经
 * `MusicRepositoryImpl.syncFavoriteElements` → `applyFavoriteSnapshot` 落库后读回断言。
 *
 * P3 批次 1b：seed 换 Room（starred/所有权预置经 DAO 插行 + song_local_state.cache_path），
 * applyFavoriteSnapshot 走 Room override（upsert + clearStarredExcept 差集），断言经 repo 读路径
 * 读回领域模型；API stub 与断言语义不变。所有权保留在 Room 由物理隔离（远端 upsert 不触
 * song_local_state）天然成立。
 */
@RunWith(AndroidJUnit4::class)
class FavoriteSyncContractTest {

    companion object {
        // ISO 日期字符串，SongDto.toSong 经 parseStarredDate 映射为非空时间戳
        private const val STARRED_DATE = "2024-01-15T10:30:00.000Z"
        private const val LOCAL_DOWNLOAD_PATH = "/data/cache/s1.mp3"
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

    /** stub 账户 A 的 getStarred2 响应（status=ok，starred2 只含指定歌曲，无 artist/album） */
    private fun stubFavorites(songs: List<SongDto>) {
        coEvery {
            fx.subsonicApiA.requestFavoriteElements(any())
        } returns Response.success(
            SubsonicResponse(
                StarredResponse(
                    status = "ok",
                    starred2 = Starred2Container(
                        artist = null,
                        album = null,
                        song = songs,
                    ),
                )
            )
        )
    }

    /**
     * seed 账户 A 三类均已收藏的本地实体 + 账户 B 一首已收藏歌曲（Room 插行）。
     * s1 带完整本地所有权字段（cache_path = downloadPath，映射后 isDownloaded=true）以验证 upsert 不覆盖。
     */
    private fun seedLocalFavorites() = runBlocking {
        val a = fx.accountA.ident
        val b = fx.accountB.ident
        fx.db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(a, "s1", "Song One").copy(starredAt = 1_000L),
                // 账户 B 的收藏歌曲——「限本账户」差集不得触及
                DbTestFixture.song(b, "sB", "Song B").copy(starredAt = 2_000L),
            )
        )
        fx.db.albumDao().upsertRemote(
            listOf(DbTestFixture.album(a, "al1", "Album One").copy(starredAt = 1_000L))
        )
        fx.db.artistDao().upsertRemote(
            listOf(DbTestFixture.artist(a, "ar1", "Artist One").copy(starredAt = 1_000L))
        )
        // s1 已缓存（cache_path 非空）——领域模型映射 isDownloaded=true、downloadPath=cache_path
        fx.db.songLocalStateDao().setCachePath(a, "s1", LOCAL_DOWNLOAD_PATH)
        Unit
    }

    // ==================== 用例 ====================

    @Test
    fun favoriteSync_clearsLocalStarsMissingFromServer() = runBlocking {
        seedLocalFavorites()
        // 服务器只回传 s1 收藏（不含 al1/ar1）：本地 al1/ar1 应被反向清理
        stubFavorites(listOf(SongDto(id = "s1", title = "Song One", starred = STARRED_DATE)))

        val result = fx.repositoryA.syncFavoriteElements()

        assertTrue("syncFavoriteElements must succeed with stubbed ok response", result.isSuccess)

        val song = fx.repositoryA.getSongById("s1")
        assertNotNull("s1 must survive the favorite sync", song)
        assertNotNull(
            "s1 remains starred on the server, so its star must be kept",
            song!!.starred
        )
        assertTrue(
            "Contract §3.3/§10: local isDownloaded must survive the favorite upsert",
            song.isDownloaded
        )
        assertEquals(
            "Contract §3.3/§10: local downloadPath must not be overwritten by the favorite upsert",
            LOCAL_DOWNLOAD_PATH,
            song.downloadPath
        )

        assertNull(
            "al1 is absent from server getStarred2 → its local star must be cleared",
            fx.repositoryA.getAlbumById("al1")?.starred
        )
        assertNull(
            "ar1 is absent from server getStarred2 → its local star must be cleared",
            fx.repositoryA.getArtistById("ar1")?.starred
        )

        assertNotNull(
            "限本账户：account B's favorite song must not be touched by account A sync",
            fx.repositoryB.getSongById("sB")?.starred
        )
    }
}
