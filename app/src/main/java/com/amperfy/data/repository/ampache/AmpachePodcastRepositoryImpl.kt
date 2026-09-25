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

package com.amperfy.data.repository.ampache

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.repository.PodcastRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 播客域的 Ampache 实现（对应 iOS `AmpacheLibrarySyncer` 的 podcast 部分，
 * :483-583 + :1011-1015 + :1187-1221）。
 *
 * **全部远端方法前置 `requestServerPodcastSupport()` 门控**（iOS 每个播客方法首行都是
 * `let isSupported = …; guard isSupported else { return }`）——服务器 API 版本低于 420000
 * 时静默返回空结果，不发业务请求。版本判定见
 * [com.amperfy.data.remote.ampache.AmpacheApiVersion]（相对 iOS 的刻意修正）。
 *
 * 与 Subsonic 侧的端点级差异：Ampache 的 `podcasts` **不带单集**（无 includeEpisodes 参数），
 * 且没有「跨播客最新单集」端点——最新单集靠逐播客 `podcast_episodes&limit=5` 汇总
 * （iOS syncNewestPodcastEpisodes，:534-582）。
 *
 * 出处：Ampache API 移植 Batch 2。
 */
internal class AmpachePodcastRepositoryImpl(
    ampacheApi: AmpacheApi,
    authSession: AmpacheAuthSession,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val libraryLocalStore: LibraryLocalStore,
    boundAccountInfo: AccountInfo?,
) : BaseAmpacheRepository(
    ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    PodcastRepository {

    // ==================== 本地读 ====================

    override fun getAllPodcasts(): Flow<List<Podcast>> =
        libraryLocalStore.getAllPodcasts(currentAccountId)

    override fun observePodcastById(podcastId: String): Flow<Podcast?> =
        libraryLocalStore.observePodcastById(currentAccountId, podcastId)

    override fun getPodcastEpisodes(podcastId: String): Flow<List<PodcastEpisode>> =
        libraryLocalStore.getPodcastEpisodes(currentAccountId, podcastId)

    override fun getAllPodcastEpisodes(): Flow<List<PodcastEpisode>> =
        libraryLocalStore.getAllPodcastEpisodes(currentAccountId)

    override fun observePodcastCount(): Flow<Long> =
        libraryLocalStore.observePodcastCount(currentAccountId)

    // ==================== 同步 ====================

    /**
     * 播客列表（iOS syncDownPodcastsWithoutEpisodes，:1187-1221）：
     * `podcasts` 全量 → Store 侧软删除服务器已删项（remoteStatus=deleted）后 upsert。
     * 不写 episodeCount（由单集同步重算）。
     */
    override suspend fun syncPodcasts(): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync podcasts") {
            if (!authSession.requestServerPodcastSupport()) return@runAmpache
            val page = ampacheApi.requestPodcasts()
            libraryLocalStore.replacePodcasts(
                currentAccountId,
                page.items.map { it.toPodcast(String::ampacheHtml2String) },
            )
        }
    }

    /**
     * 单个播客的单集（iOS sync(podcast:)，:483-531）：
     * `podcast_episodes&filter=<id>` → Store 侧 diff（服务器已删单集标 status=DELETED，
     * 不物理删除）+ 重算 episodeCount。
     *
     * 响应里没有所属播客 id，故映射时按请求的 [podcastId] 回填（iOS 靠 delegate 注入 podcast 对象）。
     * 频道元数据本端点不回传，故 channelUpdate 传 null（Store 语义：不影响 episodes 处理）。
     */
    override suspend fun syncPodcastDetails(podcastId: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync podcast") {
            if (!authSession.requestServerPodcastSupport()) return@runAmpache
            val episodes = ampacheApi.requestPodcastEpisodes(podcastId).items
                .map { it.toPodcastEpisode(podcastId, String::ampacheHtml2String) }
            libraryLocalStore.applyPodcastDetails(currentAccountId, podcastId, null, episodes)
        }
    }

    /**
     * 跨播客最新单集（iOS syncNewestPodcastEpisodes，:534-582）：
     * 先同步播客列表，再对每个本地播客拉 `limit=5` 的最新单集。
     *
     * 返回值语义与 Subsonic 侧一致（对齐 iOS AutoDownloadLibrarySyncer）：同步前后各取本地
     * newest 集合做差集，**首次填充（旧集合为空）返回空列表**，避免一上来把 20 集全下下来。
     * iOS 用无上限 TaskGroup，这里限 [MAX_CONCURRENT_POLLS] 路。
     */
    override suspend fun syncNewestPodcastEpisodes(): Result<List<PodcastEpisode>> {
        // 守卫必须在读本地 newest 集合之前（否则离线时白读一遍再返回空差集）
        if (!isSyncAllowed) return Result.success(emptyList())
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        val aid = currentAccountId
        val oldNewestIds = libraryLocalStore.newestPodcastEpisodes(aid).first()
            .map { it.id }
            .toSet()
        syncPodcasts().onFailure { return Result.failure(it) }
        return runAmpache("Sync newest podcast episodes") {
            if (!authSession.requestServerPodcastSupport()) return@runAmpache emptyList<PodcastEpisode>()
            val podcasts = libraryLocalStore.getAllPodcasts(aid).first()
            val episodes = coroutineScope {
                val gate = Semaphore(MAX_CONCURRENT_POLLS)
                podcasts.map { podcast ->
                    async {
                        gate.withPermit {
                            ampacheApi.requestPodcastEpisodes(
                                podcast.id, limit = NEWEST_EPISODES_PER_PODCAST,
                            ).items.map {
                                it.toPodcastEpisode(podcast.id, String::ampacheHtml2String)
                            }
                        }
                    }
                }.awaitAll().flatten()
            }
            libraryLocalStore.upsertNewestPodcastEpisodes(aid, episodes)

            val newest = libraryLocalStore.newestPodcastEpisodes(aid).first()
            if (oldNewestIds.isEmpty()) emptyList() else newest.filter { it.id !in oldNewestIds }
        }
    }

    /**
     * 服务器删单集（iOS requestPodcastEpisodeDelete，:1011-1015）。
     * 成功后调用方应重新 [syncPodcastDetails] 刷新状态（与 Subsonic 侧一致）。
     */
    override suspend fun deletePodcastEpisodeOnServer(episodeId: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Delete podcast episode") {
            if (!authSession.requestServerPodcastSupport()) return@runAmpache
            ampacheApi.requestPodcastEpisodeDelete(episodeId)
            Unit
        }
    }

    private companion object {
        /** 每个播客拉几集（iOS syncNewestPodcastEpisodes 硬编码 limit: 5，:541-544） */
        const val NEWEST_EPISODES_PER_PODCAST = 5

        /** 逐播客拉最新单集的并发上限（iOS 无上限 TaskGroup） */
        const val MAX_CONCURRENT_POLLS = 4
    }
}
