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

package com.amperfy.data.local.store

import com.amperfy.data.model.DownloadEntityType
import com.amperfy.data.model.DownloadItem
import com.amperfy.data.model.DownloadRef
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * 下载记录与播放对象缓存状态的持久化边界（P1）
 *
 * DownloadManager 的全部数据库访问收口于此：下载状态机（requested/finished/failed/
 * cancel/retry/clear）、启动恢复查询、缓存标记。方法一律带 accountId，
 * 只出入领域模型或标量，不出现数据库类型。
 * P4 批次 1 起为 Room 全量实现（data/local/db/store/RoomDownloadLocalStore）。
 *
 * Batch 4 泛化到播客单集（对齐 iOS DownloadMO 以 AbstractPlayable 为下载对象）：
 * 单条记录的方法带 [DownloadEntityType]（默认 SONG，保持歌曲侧调用方原样），
 * 缓存标记按类型分两组（song_local_state / podcast_episode_local_state）。
 */
interface DownloadLocalStore {

    /**
     * 指定账户的下载记录列表（按 creationDate 升序，DownloadsScreen 数据源）。
     * 实现内部完成 download 条目 + 实体的**批量**装配（每种类型一次 IN 查询），
     * 禁止逐条查询；实体已不在库中的条目从列表中省略（记录本身保留）。
     */
    fun observeItems(accountId: String): Flow<List<DownloadItem>>

    /**
     * 请求下载时建立/重置记录：已存在则清空 finish/error（沿用原 creationDate，
     * 对应 iOS download.reset()）；不存在则以 [now] 为 creationDate 新建。
     */
    suspend fun upsertRequested(
        accountId: String,
        songId: String,
        now: Long,
        entityType: DownloadEntityType = DownloadEntityType.SONG,
    )

    /** 标记下载成功（finishDate = now） */
    suspend fun markFinished(
        accountId: String,
        songId: String,
        now: Long,
        entityType: DownloadEntityType = DownloadEntityType.SONG,
    )

    /** 标记下载失败/取消（errorDate = now；已成功的记录不覆盖） */
    suspend fun markFailed(
        accountId: String,
        songId: String,
        now: Long,
        entityType: DownloadEntityType = DownloadEntityType.SONG,
    )

    /**
     * Cancel all：全部未完成（finishDate/errorDate 均空）记录标记失败
     * （对应 iOS DownloadRequestManager.cancelDownloads，记录保留在列表）
     */
    suspend fun markAllUnfinishedFailed(accountId: String, now: Long)

    /**
     * Clear finished：删除已结束（finishDate 或 errorDate 非空）的记录
     * （对应 iOS DownloadRequestManager.clearFinishedDownloads）
     */
    suspend fun clearFinished(accountId: String)

    /** Retry failed：全部失败（errorDate 非空）记录的类型化引用 */
    suspend fun failedDownloads(accountId: String): List<DownloadRef>

    /**
     * 启动恢复：未完成（finishDate/errorDate 均空）记录的类型化引用，按 creationDate 升序
     * （对应 iOS setupDownloadQueue / getRequestedDownloads）
     */
    suspend fun requestedDownloads(accountId: String): List<DownloadRef>

    /** 删除单条下载记录（启动恢复时实体已不在库中的无法恢复条目） */
    suspend fun deleteEntry(
        accountId: String,
        songId: String,
        entityType: DownloadEntityType = DownloadEntityType.SONG,
    )

    /** 按账户 + songId 查歌曲（下载管线内的歌曲解析/已下载复查） */
    suspend fun getSong(accountId: String, songId: String): Song?

    /** 按账户 + episodeId 查播客单集（下载管线内的单集解析/已下载复查） */
    suspend fun getEpisode(accountId: String, episodeId: String): PodcastEpisode?

    /**
     * 下载完成：标记歌曲已缓存（isDownloaded=true + 相对 filesDir 的 [relativePath]）。
     * @return false = 歌曲不在库中，未写入（调用方记日志）
     */
    suspend fun setSongCached(accountId: String, songId: String, relativePath: String): Boolean

    /**
     * 下载完成：标记单集已缓存（写 podcast_episode_local_state.cache_path）。
     * @return false = 单集不在库中，未写入（调用方记日志）
     */
    suspend fun setEpisodeCached(accountId: String, episodeId: String, relativePath: String): Boolean

    /** 删除缓存：清除歌曲缓存标记（isDownloaded=false + downloadPath=null） */
    suspend fun clearSongCached(accountId: String, songId: String)

    /** 删除缓存：清除单集缓存标记 */
    suspend fun clearEpisodeCached(accountId: String, episodeId: String)

    /** 清除全部缓存：该账户所有已缓存歌曲清除缓存标记 */
    suspend fun clearAllSongsCached(accountId: String)

    /** 清除全部缓存：该账户所有已缓存单集清除缓存标记 */
    suspend fun clearAllEpisodesCached(accountId: String)
}
