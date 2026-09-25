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

import com.amperfy.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 播客域仓库：播客频道与单集的读取、计数、同步与服务器端单集删除。
 * 对应 iOS: PodcastsVC / PodcastDetailVC 的数据源 + SubsonicLibrarySyncer 的 podcast 方法。
 * 出处：Repository 拆分批次 1——方法自 MusicRepository 原样搬移，签名与注释不变。
 */
interface PodcastRepository {
    // ==================== 播客（Phase 6.4） ====================
    // 对应 iOS: PodcastsVC / PodcastDetailVC / SubsonicLibrarySyncer podcast 方法

    /** 观察全部播客（按 title 排序；服务器已删除的 remoteStatus=deleted 项过滤，对齐 iOS FRC） */
    fun getAllPodcasts(): Flow<List<Podcast>>
    /** 观察单个播客 */
    fun observePodcastById(podcastId: String): Flow<Podcast?>
    /** 某播客的单集（publishDate 降序，iOS Podcast.episodes sortByPublishDate） */
    fun getPodcastEpisodes(podcastId: String): Flow<List<PodcastEpisode>>
    /** 跨播客全部单集（publishDate 降序，iOS PodcastEpisodesReleaseDateFetchedResultsController） */
    fun getAllPodcastEpisodes(): Flow<List<PodcastEpisode>>
    /** 播客计数（LibrarySettings 统计行，Phase 6.4；只计 remoteStatus=available） */
    fun observePodcastCount(): Flow<Long>
    /**
     * 同步播客列表（getPodcasts includeEpisodes=false）。
     * 对应 iOS: syncDownPodcastsWithoutEpisodes——服务器已删项标记 remoteStatus=deleted（软删除）
     */
    suspend fun syncPodcasts(): Result<Unit>
    /**
     * 同步单个播客的单集（getPodcasts id + includeEpisodes=true）。
     * 对应 iOS: sync(podcast:)——服务器已删单集标 status=deleted（不物理删除），并重算 episodeCount
     */
    suspend fun syncPodcastDetails(podcastId: String): Result<Unit>
    /**
     * 同步播客列表 + 跨播客最新单集（getNewestPodcasts count=20）。
     *
     * 对应 iOS: AutoDownloadLibrarySyncer.syncNewestPodcastEpisodes（:95-115）——
     * 返回**本次新增的最新单集**（同步前后各取 newest 20 做差集）供自动缓存消费；
     * 初次填充（同步前 newest 为空）一律返回空列表，避免首次同步把 20 集全下下来。
     */
    suspend fun syncNewestPodcastEpisodes(): Result<List<PodcastEpisode>>
    /** 服务器删除单集（deletePodcastEpisode）。成功后调用方应重新 syncPodcastDetails 刷新状态 */
    suspend fun deletePodcastEpisodeOnServer(episodeId: String): Result<Unit>
}
