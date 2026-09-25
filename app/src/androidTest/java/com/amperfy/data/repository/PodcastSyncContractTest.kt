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
import com.amperfy.data.local.db.entity.PodcastEntity
import com.amperfy.data.local.db.entity.PodcastEpisodeEntity
import com.amperfy.data.model.PodcastEpisodeRemoteStatus
import com.amperfy.data.remote.dto.PodcastChannelDto
import com.amperfy.data.remote.dto.PodcastEpisodeDto
import com.amperfy.data.remote.dto.PodcastsContainer
import com.amperfy.data.remote.dto.PodcastsResponse
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
 * 播客详情同步差集/软删除合同（专题 15 P1 批次 5c）。
 *
 * 锁定 `applyPodcastDetails` 的冻结语义：
 * 1. 服务器负载中消失的单集**仅标 status=DELETED，不物理删除**（iOS sync(podcast:) diff）；
 * 2. episodeCount 只计非删除单集（iOS PodcastMO.episodeCount）。
 *
 * 走真实同步路径：coEvery 显式 stub [com.amperfy.data.remote.SubsonicApi.requestPodcasts]
 * 返回构造 PodcastsResponse（status=ok，通过 requireOk 校验），经
 * `MusicRepositoryImpl.syncPodcastDetails` → `applyPodcastDetails` 落库后读回断言。
 *
 * **P3 批次 3b 换绑 Room**：podcast/episode 域 sync 写已走 [RoomLibraryLocalStore]（生产 fixture
 * 的 libraryLocalStore 即 Room 过渡组合），故 seed 改用 [DbTestFixture] 行构造器 + `fx.db` 各 DAO
 * upsert，断言改读 `fx.db` DAO 行；软删除/episodeCount
 * 语义逐条不变。
 */
@RunWith(AndroidJUnit4::class)
class PodcastSyncContractTest {

    private lateinit var fx: RepositoryFixture

    @Before
    fun setUp() {
        fx = RepositoryFixture.create()
    }

    @After
    fun tearDown() {
        fx.close()
    }

    // ==================== seed / stub 辅助 ====================

    /** seed 账户 A 播客 p1（未软删除）+ 已完成单集 e1（关联 p1）——经 Room DAO（先频道后单集，满足 FK） */
    private suspend fun seedPodcastWithEpisode() {
        fx.db.podcastDao().upsertRemote(
            listOf(DbTestFixture.podcast(accountId = fx.accountA.ident, serverId = "p1", title = "Podcast One")),
        )
        fx.db.podcastEpisodeDao().upsertRemote(
            listOf(
                DbTestFixture.episode(
                    accountId = fx.accountA.ident,
                    serverId = "e1",
                    podcastId = "p1",
                    title = "Episode One",
                    status = PodcastEpisodeRemoteStatus.COMPLETED.raw,
                ),
            ),
        )
    }

    /** stub 账户 A 的 getPodcasts(includeEpisodes=true)：channel p1 仅含新单集 e2（不含 e1） */
    private fun stubPodcastDetailsWithoutE1() {
        coEvery {
            fx.subsonicApiA.requestPodcasts(any(), any(), any())
        } returns Response.success(
            SubsonicResponse(
                PodcastsResponse(
                    status = "ok",
                    podcasts = PodcastsContainer(
                        channel = listOf(
                            PodcastChannelDto(
                                id = "p1",
                                url = "https://example.com/p1",
                                title = "Podcast One",
                                description = "desc",
                                status = "completed",
                                episode = listOf(
                                    PodcastEpisodeDto(
                                        id = "e2",
                                        title = "Episode Two",
                                        channelId = "p1",
                                        status = "completed",
                                        publishDate = "2024-01-02T00:00:00",
                                    )
                                ),
                            )
                        )
                    )
                )
            )
        )
    }

    /** 直接读 Room 层单集行（status/podcast_id 不在领域模型可直读断言的形态上） */
    private suspend fun findEpisode(serverId: String): PodcastEpisodeEntity? =
        fx.db.podcastEpisodeDao().getByServerId(fx.accountA.ident, serverId)

    private suspend fun findPodcast(serverId: String): PodcastEntity? =
        fx.db.podcastDao().getByServerId(fx.accountA.ident, serverId)

    // ==================== 用例 ====================

    @Test
    fun podcastDetailsSync_marksMissingEpisodesDeletedWithoutRemoval() = runBlocking {
        seedPodcastWithEpisode()
        stubPodcastDetailsWithoutE1()

        val result = fx.repositoryA.syncPodcastDetails("p1")

        assertTrue("syncPodcastDetails must succeed with stubbed ok response", result.isSuccess)

        val e1 = findEpisode("e1")
        assertNotNull("e1 entity must still exist after being missing from server (soft delete)", e1)
        assertEquals(
            "Missing episode must be marked DELETED, not physically removed (iOS sync(podcast:) diff)",
            PodcastEpisodeRemoteStatus.DELETED.raw,
            e1!!.status
        )

        val e2 = findEpisode("e2")
        assertNotNull("e2 must be created by the detail sync", e2)
        assertEquals("e2 must be linked to podcast p1", "p1", e2!!.podcastId)

        val p1 = findPodcast("p1")
        assertNotNull(p1)
        assertEquals(
            "episodeCount counts only non-deleted episodes (e2 only, e1 excluded)",
            1,
            p1!!.episodeCount
        )
    }
}
