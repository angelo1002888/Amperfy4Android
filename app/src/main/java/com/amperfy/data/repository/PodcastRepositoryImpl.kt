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

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.*
import com.amperfy.data.remote.SubsonicApi
import com.amperfy.data.remote.dto.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 播客域实现：播客频道与单集的读取、计数、同步与服务器端单集删除。
 * 方法体与播客私有映射（parsePodcastPublishDate/toPodcast/toPodcastEpisode）
 * 自 MusicRepositoryImpl 逐字搬移。
 *
 * 出处：Repository 拆分批次 2。
 */
internal class PodcastRepositoryImpl(
    subsonicApi: SubsonicApi,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val libraryLocalStore: com.amperfy.data.local.store.LibraryLocalStore,
    boundAccountInfo: AccountInfo?,
) : BaseSubsonicRepository(
    subsonicApi, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    PodcastRepository {

    // ==================== 播客（Phase 6.4） ====================
    // 私有转换器 toPodcast/toPodcastEpisode 已随读路径迁移至 LibraryLocalStore 实现（批次 4）

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

    /**
     * 单集发布日期解析：iOS SsPodcastEpisodeParserDelegate 格式 yyyy-MM-dd'T'HH:mm:ss
     * （UTC，取前 19 字符；服务器可能附带毫秒/时区后缀）
     */
    private fun parsePodcastPublishDate(value: String?): Long {
        if (value.isNullOrBlank() || value.length < 19) return 0
        return try {
            java.time.LocalDateTime.parse(value.take(19))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (e: Exception) {
            0
        }
    }

    /** DTO→domain 播客映射（channel 缺省 description 归空串，与写块 dto.description ?: "" 一致） */
    private fun PodcastChannelDto.toPodcast() = Podcast(
        id = id,
        title = title,
        depiction = description ?: "",
        coverArt = coverArt
    )

    /**
     * DTO→domain 单集映射：publishDate/status 解析留 repo；
     * podcastId = channelId ?: ""（空串与 null 语义一致，均不建播客关联）。
     */
    private fun PodcastEpisodeDto.toPodcastEpisode() = PodcastEpisode(
        id = id,
        title = title,
        depiction = description,
        publishDate = parsePodcastPublishDate(publishDate),
        status = PodcastEpisodeRemoteStatus.fromSubsonicString(status),
        streamId = streamId,
        duration = duration ?: 0,
        coverArt = coverArt,
        podcastId = channelId ?: ""
    )

    override suspend fun syncPodcasts(): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:1053 guard isSyncAllowed（syncDownPodcastsWithoutEpisodes）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestPodcasts(
                includeEpisodes = false
            )
            // iOS SsPodcastParserDelegate:62-63 跳过 status=="error" 的频道
            val channels = (requireOk(response, "Sync podcasts").podcasts?.channel ?: emptyList())
                .filter { it.status != "error" }
            libraryLocalStore.replacePodcasts(currentAccountId, channels.map { it.toPodcast() })
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncPodcasts error", e)
            Result.failure(e)
        }
    }

    override suspend fun syncPodcastDetails(
        podcastId: String
    ): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:521 guard isSyncAllowed（sync(podcast:)）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestPodcasts(
                includeEpisodes = true, id = podcastId
            )
            val channels = requireOk(response, "Sync podcast").podcasts?.channel ?: emptyList()
            val channel = channels.firstOrNull { it.id == podcastId } ?: channels.firstOrNull()
            val episodes = channel?.episode ?: emptyList()
            // channel 缺失或 status=error 时元数据不更新（channelUpdate=null），episodes 照常 diff+upsert
            val channelUpdate = channel?.takeIf { it.status != "error" }?.toPodcast()
            libraryLocalStore.applyPodcastDetails(
                currentAccountId, podcastId, channelUpdate, episodes.map { it.toPodcastEpisode() }
            )
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncPodcastDetails error", e)
            Result.failure(e)
        }
    }

    /**
     * 对齐 iOS AutoDownloadLibrarySyncer.syncNewestPodcastEpisodes（:95-115）：
     * 同步前后各取 newest 20 的 id 集合，返回 new − old 的差集单集供调用方自动缓存；
     * **old 为空（初次填充）返回空列表**（iOS `if !oldNewestEpisodes.isEmpty` 才返回差集），
     * 避免首次同步就把 20 集全部下载。
     */
    override suspend fun syncNewestPodcastEpisodes(): Result<List<PodcastEpisode>> {
        // iOS SubsonicLibrarySyncer.swift:606 guard isSyncAllowed —— 必须放在读本地 newest 集合
        // 与 syncPodcasts() 之前（方法开头），否则离线时会白读一遍本地再返回空差集
        if (!isSyncAllowed) return Result.success(emptyList())
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        // iOS syncNewestPodcastEpisodes：先 syncDownPodcastsWithoutEpisodes 再 getNewestPodcasts
        val oldNewestIds = libraryLocalStore.newestPodcastEpisodes(currentAccountId).first()
            .map { it.id }
            .toSet()
        syncPodcasts().onFailure { return Result.failure(it) }
        return try {
            val response = subsonicApi.requestNewestPodcasts()
            val episodes = requireOk(response, "Sync newest podcast episodes")
                .newestPodcasts?.episode ?: emptyList()
            libraryLocalStore.upsertNewestPodcastEpisodes(currentAccountId, episodes.map { it.toPodcastEpisode() })
            val newest = libraryLocalStore.newestPodcastEpisodes(currentAccountId).first()
            val newlyAdded = if (oldNewestIds.isEmpty()) {
                emptyList()
            } else {
                newest.filter { it.id !in oldNewestIds }
            }
            Result.success(newlyAdded)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncNewestPodcastEpisodes error", e)
            Result.failure(e)
        }
    }

    override suspend fun deletePodcastEpisodeOnServer(
        episodeId: String
    ): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:909 guard isSyncAllowed（requestPodcastEpisodeDelete）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestPodcastEpisodeDelete(
                id = episodeId
            )
            requireOk(response, "Delete podcast episode")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "deletePodcastEpisodeOnServer error", e)
            Result.failure(e)
        }
    }

}
