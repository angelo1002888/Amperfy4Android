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

package com.amperfy.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.PodcastEntity
import kotlinx.coroutines.flow.Flow

/**
 * podcast 远端快照 DAO。
 *
 * observeAll 按 sort_key COLLATE BINARY + server_id tie-break（PodcastsShowType 按名排序）。
 *
 * P3 批次 3a 扩充（纯新增不接线，3b 收口 RoomLibraryLocalStore 委托）：
 * - 存活过滤读（remote_status=0）：observeActive/observeActiveLimited/observeByServerId/
 *   observeActiveCount；
 *   排序为 `sort_key COLLATE BINARY, server_id`（拼音分区序，
 *   属 1c 同款「码点序→分区序」变化，3b 记录）。
 * - 软删除差集 markRemoteDeletedMissing（差集标 remote_status=1）。
 * - **保留 episodeCount 的两步 upsert**（existing-or-create，只写
 *   title/depiction/coverArt/remoteStatus=0、保留 episodeCount）：insertIgnore 建新行（已存在忽略）
 *   + updateChannelMetadata 更新元数据但不触碰 episode_count。@Upsert 会整行覆盖 episode_count，
 *   故此路径**禁用** upsertRemote。
 * - recalcEpisodeCount 按非删除单集数重算。
 */
@Dao
interface PodcastDao {
    /** 远端快照全行 upsert（Remote 后缀强制）。 */
    @Upsert
    suspend fun upsertRemote(rows: List<PodcastEntity>)

    @Query(
        "SELECT * FROM podcast WHERE account_id = :accountId " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeAll(accountId: String): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcast WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): PodcastEntity?

    // ==================== 存活过滤读（P3 批次 3a，remote_status=0） ====================

    /**
     * 存活播客（remote_status = 0）。
     * 软删除（remote_status=1）不出现；排序按 sort_key COLLATE BINARY + server_id tie-break。
     */
    @Query(
        "SELECT * FROM podcast WHERE account_id = :accountId AND remote_status = 0 " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeActive(accountId: String): Flow<List<PodcastEntity>>

    /**
     * 存活播客前 20 条（Home 用）。
     * 排序同 observeActive。
     */
    @Query(
        "SELECT * FROM podcast WHERE account_id = :accountId AND remote_status = 0 " +
            "ORDER BY sort_key COLLATE BINARY, server_id LIMIT 20",
    )
    fun observeActiveLimited(accountId: String): Flow<List<PodcastEntity>>

    /**
     * 观察单个播客（主键直查）。
     * 不过滤 remote_status——软删除的播客详情仍可打开。
     */
    @Query("SELECT * FROM podcast WHERE account_id = :accountId AND server_id = :serverId")
    fun observeByServerId(accountId: String, serverId: String): Flow<PodcastEntity?>

    /** 存活播客数（remote_status = 0）。 */
    @Query("SELECT COUNT(*) FROM podcast WHERE account_id = :accountId AND remote_status = 0")
    fun observeActiveCount(accountId: String): Flow<Long>

    // ==================== 软删除差集 + 保留 episodeCount 的两步 upsert（P3 批次 3a） ====================

    /**
     * 软删除差集：不在 keepServerIds 内的播客标 remote_status=1。
     * keepServerIds 为本次服务器返回的存活播客 server_id；空列表 = 全标删除（NOT IN () 边界）。
     * 幂等：已是 1 的行再标 1 无副作用；**不物理删除**（软删除）。
     */
    @Query(
        "UPDATE podcast SET remote_status = 1 " +
            "WHERE account_id = :accountId AND server_id NOT IN (:keepServerIds)",
    )
    suspend fun markRemoteDeletedMissing(accountId: String, keepServerIds: List<String>)

    /**
     * 两步 upsert 第 1 步：仅插入不存在的播客行（已存在则 IGNORE，保留其 episode_count）。
     * 即「已存在则沿用、否则新建」——新建行 episode_count 由传入
     * 实体决定（Store 应建 episode_count=0 的新行，等待后续 recalc/详情同步补齐）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<PodcastEntity>)

    /**
     * 两步 upsert 第 2 步：更新频道元数据 + 复活 remote_status=0，**不触碰 episode_count**
     * （只写 title/depiction/coverArt/remoteStatus=0，保留 episodeCount）。
     * search_key/sort_key 随 title 由 Store 经 LibraryTextKeyNormalizer 原子生成后传入。
     * 行不存在时自然 no-op（新行已由 insertIgnore 建好）。
     */
    @Query(
        "UPDATE podcast SET title = :title, description = :description, cover_art = :coverArt, " +
            "search_key = :searchKey, sort_key = :sortKey, remote_status = 0 " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updateChannelMetadata(
        accountId: String,
        serverId: String,
        title: String,
        description: String,
        coverArt: String?,
        searchKey: String,
        sortKey: String,
    )

    /**
     * 频道元数据更新，**保持 remote_status 不变**（与 [updateChannelMetadata] 同列，唯独不写
     * remote_status）：applyPodcastDetails 的频道更新只赋 title/depiction/coverArt、
     * 不触碰 remoteStatus（详情同步不复活软删频道），故经本方法。分工——
     * replacePodcasts 用 [updateChannelMetadata]（复活 remote_status=0），
     * applyPodcastDetails 用本方法（保持 status）。
     * search_key/sort_key 随 title 由 Store 经 LibraryTextKeyNormalizer 原子生成后传入。
     * 行不存在时自然 no-op。
     */
    @Query(
        "UPDATE podcast SET title = :title, description = :description, cover_art = :coverArt, " +
            "search_key = :searchKey, sort_key = :sortKey " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updateChannelMetadataKeepStatus(
        accountId: String,
        serverId: String,
        title: String,
        description: String,
        coverArt: String?,
        searchKey: String,
        sortKey: String,
    )

    /**
     * 按非删除单集数重算 episode_count（该频道下
     * status != DELETED 的单集计数）。deletedRaw 由调用方传
     * PodcastEpisodeRemoteStatus.DELETED.raw（不在 SQL 写魔法数字）。
     */
    @Query(
        "UPDATE podcast SET episode_count = (" +
            "SELECT COUNT(*) FROM podcast_episode " +
            "WHERE podcast_episode.account_id = :accountId " +
            "  AND podcast_episode.podcast_id = :serverId " +
            "  AND podcast_episode.status != :deletedRaw" +
            ") WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun recalcEpisodeCount(accountId: String, serverId: String, deletedRaw: Int)
}
