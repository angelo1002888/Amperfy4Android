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

package com.amperfy.data.local.db.store

import androidx.room.withTransaction
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.entity.AccountScopeEntity
import com.amperfy.data.local.db.entity.DownloadEntryEntity
import com.amperfy.data.local.db.mapper.toPodcastEpisode
import com.amperfy.data.local.db.mapper.toSong
import com.amperfy.data.local.store.DownloadLocalStore
import com.amperfy.data.model.DownloadEntityType
import com.amperfy.data.model.DownloadItem
import com.amperfy.data.model.DownloadRef
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * DownloadLocalStore 的 Room 全量实现（专题 15 P4 批次 1）。
 *
 * 下载状态机（requested/finished/failed/cancel/retry/clear/启动恢复）全部走 download_entry 表，
 * 构造只需 [AmperfyDatabase]。
 *
 * 语义要点：状态由 finish_date/error_date 派生（无独立 status 列）、
 * markFailed 带「已完成不覆盖」守卫（SQL 内 `finish_date IS NULL`）、upsertRequested 对已存在
 * 记录只清两日期保留原 creation_date（对应 iOS download.reset()）、启动恢复按 creation_date 升序。
 *
 * [observeItems] 口径：**只观察 download_entry 不 JOIN 实体**（普通歌曲元数据同步
 * 不触发 Downloads Flow），拿到条目后**按 entity_type 分组各做一次 IN 批量装配**，
 * 实体已不在库中的条目省略、装配后按 download_entry 的 creation_date 原序回列。
 * 缓存态权威源为 song_local_state.cache_path / podcast_episode_local_state.cache_path。
 *
 * Batch 4：下载对象泛化到播客单集（对齐 iOS DownloadMO 以 AbstractPlayable 为对象）——
 * 单条记录的状态机方法一律带 entity_type 谓词（歌曲与单集的服务端 id 可能撞号）。
 */
class RoomDownloadLocalStore(
    private val db: AmperfyDatabase,
) : DownloadLocalStore {

    private val songDao get() = db.songDao()
    private val songLocalStateDao get() = db.songLocalStateDao()
    private val episodeDao get() = db.podcastEpisodeDao()
    private val episodeLocalStateDao get() = db.podcastEpisodeLocalStateDao()
    private val downloadEntryDao get() = db.downloadEntryDao()

    /**
     * 保证账户租户根 account_scope 行存在。download_entry FK→account_scope 级联，
     * 缺父行时写入会被 FK 拒绝——唯一写入口 [upsertRequested] 在事务内第一步幂等调用
     * （与 RoomSearchHistoryStore.ensureScope 同口径；账户生命周期侧另有 RoomAccountLocalStore
     * 维护租户根，两者并存互不冲突）。
     */
    private suspend fun ensureScope(accountId: String) {
        db.accountScopeDao().upsert(AccountScopeEntity(accountId))
    }

    /**
     * 下载列表：只观察 download_entry，每次发射按类型各一次 IN 批量取实体装配
     * （禁逐条）；实体已不在库中的条目省略（条目本身保留）。
     * 状态布尔由时间列派生。
     */
    override fun observeItems(accountId: String): Flow<List<DownloadItem>> =
        downloadEntryDao.observeEntries(accountId)
            .map { entries ->
                if (entries.isEmpty()) {
                    emptyList()
                } else {
                    val byType = entries.groupBy { DownloadEntityType.fromRaw(it.entityType) }

                    val songsById = byType[DownloadEntityType.SONG]
                        ?.let { rows ->
                            songDao.getRowsByServerIds(accountId, rows.map { it.songId })
                                .associateBy { it.song.serverId }
                        }
                        ?: emptyMap()

                    val episodesById = byType[DownloadEntityType.PODCAST_EPISODE]
                        ?.let { rows ->
                            episodeDao.getByServerIdsWithTitle(accountId, rows.map { it.songId })
                                .associateBy { it.episode.serverId }
                        }
                        ?: emptyMap()

                    // 装配保持 download_entry 的 creation_date 原序（entries 已按该列升序）
                    entries.mapNotNull { entry ->
                        val isDownloading = entry.finishDate == null && entry.errorDate == null
                        when (DownloadEntityType.fromRaw(entry.entityType)) {
                            DownloadEntityType.SONG -> {
                                val song = songsById[entry.songId]?.toSong() ?: return@mapNotNull null
                                DownloadItem(
                                    song = song,
                                    isDownloading = isDownloading,
                                    isFinished = entry.finishDate != null,
                                    isError = entry.errorDate != null,
                                )
                            }

                            DownloadEntityType.PODCAST_EPISODE -> {
                                val episode = episodesById[entry.songId]?.toPodcastEpisode()
                                    ?: return@mapNotNull null
                                DownloadItem(
                                    episode = episode,
                                    isDownloading = isDownloading,
                                    isFinished = entry.finishDate != null,
                                    isError = entry.errorDate != null,
                                )
                            }
                        }
                    }
                }
            }
            .distinctUntilChanged()

    /**
     * 请求下载：已存在则 reset（清 finish/error，**保留原 creation_date**，对应 iOS download.reset()）；
     * 不存在则以 [now] 建新行。先读后写故包事务，事务内第一步保租户根。
     */
    override suspend fun upsertRequested(
        accountId: String,
        songId: String,
        now: Long,
        entityType: DownloadEntityType,
    ) {
        db.withTransaction {
            ensureScope(accountId)
            if (downloadEntryDao.getByEntity(accountId, entityType.name, songId) != null) {
                downloadEntryDao.resetForRetry(accountId, entityType.name, songId, updatedAt = now)
            } else {
                downloadEntryDao.upsert(
                    DownloadEntryEntity(
                        accountId = accountId,
                        entityType = entityType.name,
                        songId = songId,
                        creationDate = now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    override suspend fun markFinished(
        accountId: String,
        songId: String,
        now: Long,
        entityType: DownloadEntityType,
    ) {
        downloadEntryDao.markFinished(accountId, entityType.name, songId, finishDate = now, updatedAt = now)
    }

    /** 失败/取消：「已成功的记录不覆盖」守卫在 SQL 内（`finish_date IS NULL`）。 */
    override suspend fun markFailed(
        accountId: String,
        songId: String,
        now: Long,
        entityType: DownloadEntityType,
    ) {
        downloadEntryDao.markFailed(accountId, entityType.name, songId, errorDate = now, updatedAt = now)
    }

    override suspend fun markAllUnfinishedFailed(accountId: String, now: Long) {
        downloadEntryDao.markAllUnfinishedFailed(accountId, errorDate = now, updatedAt = now)
    }

    override suspend fun clearFinished(accountId: String) {
        // 纯删除单语句天然原子，无 FK 父行依赖，不需 ensureScope / 显式事务。
        downloadEntryDao.deleteFinished(accountId)
    }

    override suspend fun failedDownloads(accountId: String): List<DownloadRef> =
        downloadEntryDao.getFailedRefs(accountId)
            .map { DownloadRef(DownloadEntityType.fromRaw(it.entityType), it.songId) }

    override suspend fun requestedDownloads(accountId: String): List<DownloadRef> =
        downloadEntryDao.getRequestedRefs(accountId)
            .map { DownloadRef(DownloadEntityType.fromRaw(it.entityType), it.songId) }

    override suspend fun deleteEntry(accountId: String, songId: String, entityType: DownloadEntityType) {
        downloadEntryDao.deleteByEntity(accountId, entityType.name, songId)
    }

    override suspend fun getSong(accountId: String, songId: String): Song? =
        songDao.getWithLocalByServerId(accountId, songId)?.toSong()

    override suspend fun getEpisode(accountId: String, episodeId: String): PodcastEpisode? =
        episodeDao.getWithLocalByServerId(accountId, episodeId)?.toPodcastEpisode()

    /**
     * 下载完成标记缓存。Room 歌曲不在库 → 返回 false（不写，调用方记日志语义不变）；
     * 存在 → 写 song_local_state.cache_path。
     */
    override suspend fun setSongCached(accountId: String, songId: String, relativePath: String): Boolean {
        if (songDao.getByServerId(accountId, songId) == null) return false
        songLocalStateDao.setCachePath(accountId, songId, relativePath)
        return true
    }

    /** 单集侧同口径：不在库返回 false，存在则写 podcast_episode_local_state.cache_path。 */
    override suspend fun setEpisodeCached(accountId: String, episodeId: String, relativePath: String): Boolean {
        if (episodeDao.getByServerId(accountId, episodeId) == null) return false
        episodeLocalStateDao.setCachePath(accountId, episodeId, relativePath)
        return true
    }

    /** 删除缓存：Room 歌曲存在才清 cache_path（避免孤儿本地状态行）。 */
    override suspend fun clearSongCached(accountId: String, songId: String) {
        if (songDao.getByServerId(accountId, songId) != null) {
            songLocalStateDao.setCachePath(accountId, songId, null)
        }
    }

    /** 删除缓存：单集存在才清 cache_path（同歌曲侧的防孤儿行口径）。 */
    override suspend fun clearEpisodeCached(accountId: String, episodeId: String) {
        if (episodeDao.getByServerId(accountId, episodeId) != null) {
            episodeLocalStateDao.setCachePath(accountId, episodeId, null)
        }
    }

    override suspend fun clearAllSongsCached(accountId: String) {
        songLocalStateDao.clearAllCachePaths(accountId)
    }

    override suspend fun clearAllEpisodesCached(accountId: String) {
        episodeLocalStateDao.clearAllCachePaths(accountId)
    }
}
