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
import androidx.room.Query
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.PodcastEpisodeEntity
import kotlinx.coroutines.flow.Flow

/**
 * podcast_episode 远端快照 DAO。
 *
 * 双显示模式：observeEpisodesOf 为详情页某频道单集、observeAllOfActivePodcasts 为 Episodes 显示模式，
 * 均按 publish_date DESC。markDeletedMissing 为软删除差集（对齐 iOS sync(podcast:) diff / P1 合同）：
 * 把该频道中不在 keepIds 内的单集标 status = :deletedRaw（调用方传 PodcastEpisodeRemoteStatus.DELETED.raw，
 * 不在 SQL 写魔法数字），**不物理删除**。
 *
 * P3 批次 3b 收口：单集列表查询须带父频道名（缺失时兜底 "Unknown Podcast"；
 * 父频道名不落在单集表——podcast 独立表，须 JOIN 带出），
 * 返回 [EpisodeWithPodcastTitle] 投影（JOIN podcast 取 `title AS podcast_title`）：
 * - observeEpisodesOf（详情页某频道，无外部调用方）**直接改造**为投影版，用 LEFT JOIN（详情页不过滤
 *   频道状态，软删频道详情仍可开，podcast_title 可能为 null，由映射层兜底 "Unknown Podcast"）；
 * - observeAllOfActivePodcastsWithTitle / newestOfActivePodcastsWithTitle 为投影变体，INNER JOIN
 *   （存活频道过滤，podcast_title 实际非空），Store 的 Episodes 模式 / Home 用；其 3a 裸实体版
 *   observeAllOfActivePodcasts / newestOfActivePodcasts 保留（RoomPodcastRadioDaoTest 仍按裸实体断言，
 *   该测试文件不在本批边界内，故不改签名而新增变体）。
 * observeAllEpisodes（裸实体、无频道名）保留供旧调用。
 *
 * Batch 4：凡返回 [EpisodeWithPodcastTitle] 的查询一律再 LEFT JOIN podcast_episode_local_state
 * （account_id + episode_id 双列匹配）带出 `cache_path`——领域 PodcastEpisode 的
 * isDownloaded/cachePath 由该列派生（与歌曲侧 song LEFT JOIN song_local_state 同口径）；
 * 本地状态表**不** FK podcast_episode，故软删/差集不会连带清掉缓存记录。
 */
@Dao
interface PodcastEpisodeDao {
    /** 远端快照全行 upsert（Remote 后缀强制）。 */
    @Upsert
    suspend fun upsertRemote(rows: List<PodcastEpisodeEntity>)

    /**
     * 某频道单集（详情页），按发布日期倒序。返回 [EpisodeWithPodcastTitle] 投影：
     * LEFT JOIN podcast 取 `title AS podcast_title`——详情页不过滤频道状态（软删频道详情仍可开），
     * 无匹配频道时 podcast_title 为 null，映射层兜底 "Unknown Podcast"。
     */
    @Query(
        "SELECT podcast_episode.*, podcast.title AS podcast_title, " +
            "podcast_episode_local_state.cache_path AS cache_path FROM podcast_episode " +
            "LEFT JOIN podcast " +
            "  ON podcast.account_id = podcast_episode.account_id " +
            " AND podcast.server_id = podcast_episode.podcast_id " +
            "LEFT JOIN podcast_episode_local_state " +
            "  ON podcast_episode_local_state.account_id = podcast_episode.account_id " +
            " AND podcast_episode_local_state.episode_id = podcast_episode.server_id " +
            "WHERE podcast_episode.account_id = :accountId AND podcast_episode.podcast_id = :podcastId " +
            "ORDER BY podcast_episode.publish_date DESC",
    )
    fun observeEpisodesOf(accountId: String, podcastId: String): Flow<List<EpisodeWithPodcastTitle>>

    @Query("SELECT * FROM podcast_episode WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): PodcastEpisodeEntity?

    /**
     * 单集点查 + 父频道名 + 本地缓存态（Batch 4：下载管线内的单集解析/已下载复查，
     * 对照 SongDao.getWithLocalByServerId）。JOIN 口径同 [getByServerIdsWithTitle]。
     */
    @Query(
        "SELECT podcast_episode.*, podcast.title AS podcast_title, " +
            "podcast_episode_local_state.cache_path AS cache_path FROM podcast_episode " +
            "LEFT JOIN podcast " +
            "  ON podcast.account_id = podcast_episode.account_id " +
            " AND podcast.server_id = podcast_episode.podcast_id " +
            "LEFT JOIN podcast_episode_local_state " +
            "  ON podcast_episode_local_state.account_id = podcast_episode.account_id " +
            " AND podcast_episode_local_state.episode_id = podcast_episode.server_id " +
            "WHERE podcast_episode.account_id = :accountId AND podcast_episode.server_id = :serverId",
    )
    suspend fun getWithLocalByServerId(accountId: String, serverId: String): EpisodeWithPodcastTitle?

    /**
     * 批量按 server_id 取单集 + 父频道名（P4 批次 2：播放队列恢复一次 IN 装配，禁逐条回表）。
     *
     * JOIN 口径**沿用 [observeEpisodesOf] 的 LEFT JOIN**（而非 Episodes 模式/Home 的 INNER JOIN +
     * remote_status=0）——按 id 点查/批量取语义等同详情页：不过滤频道状态，父频道软删或缺失时
     * 单集仍可取出（podcast_title 为 null，由映射/装配层兜底 "Unknown Podcast"）。队列恢复必须
     * 与之一致：已在队列中的单集不能因父频道软删而被判定「引用缺失」整条丢弃（「引用缺失」只
     * 针对单集本身不在库中）。缺失的 id 不出现在结果里，由调用方记日志跳过。
     */
    @Query(
        "SELECT podcast_episode.*, podcast.title AS podcast_title, " +
            "podcast_episode_local_state.cache_path AS cache_path FROM podcast_episode " +
            "LEFT JOIN podcast " +
            "  ON podcast.account_id = podcast_episode.account_id " +
            " AND podcast.server_id = podcast_episode.podcast_id " +
            "LEFT JOIN podcast_episode_local_state " +
            "  ON podcast_episode_local_state.account_id = podcast_episode.account_id " +
            " AND podcast_episode_local_state.episode_id = podcast_episode.server_id " +
            "WHERE podcast_episode.account_id = :accountId " +
            "AND podcast_episode.server_id IN (:serverIds)",
    )
    suspend fun getByServerIdsWithTitle(accountId: String, serverIds: List<String>): List<EpisodeWithPodcastTitle>

    // ==================== 跨频道存活单集读（P3 批次 3a，纯新增不接线） ====================

    /**
     * 全部「存活频道」的单集（父频道 remote_status = 0，
     * 单集自身 status 不过滤——deleted 单集仍显示「Deleted on server」标签）。
     *
     * INNER JOIN podcast 双列匹配 + remote_status=0：
     * - 排除 podcast_id 为 NULL / 指向未知频道的单集；
     * - 排除软删除频道（remote_status=1）名下的单集。
     * 按 publish_date DESC。
     */
    @Query(
        "SELECT podcast_episode.* FROM podcast_episode " +
            "INNER JOIN podcast " +
            "  ON podcast.account_id = podcast_episode.account_id " +
            " AND podcast.server_id = podcast_episode.podcast_id " +
            "WHERE podcast_episode.account_id = :accountId AND podcast.remote_status = 0 " +
            "ORDER BY podcast_episode.publish_date DESC",
    )
    fun observeAllOfActivePodcasts(accountId: String): Flow<List<PodcastEpisodeEntity>>

    /**
     * 存活频道单集前 20 条（Home 用，按
     * publish_date 倒序）。JOIN 语义同上。
     */
    @Query(
        "SELECT podcast_episode.* FROM podcast_episode " +
            "INNER JOIN podcast " +
            "  ON podcast.account_id = podcast_episode.account_id " +
            " AND podcast.server_id = podcast_episode.podcast_id " +
            "WHERE podcast_episode.account_id = :accountId AND podcast.remote_status = 0 " +
            "ORDER BY podcast_episode.publish_date DESC LIMIT 20",
    )
    fun newestOfActivePodcasts(accountId: String): Flow<List<PodcastEpisodeEntity>>

    // ==================== 带父频道名的投影变体（P3 批次 3b，Store Episodes 模式 / Home 用） ====================

    /**
     * observeAllOfActivePodcasts 的投影变体：SELECT 加 `podcast.title AS podcast_title`，
     * 返回 [EpisodeWithPodcastTitle]（Store getAllPodcastEpisodes 用，映射时带出频道名）。
     * INNER JOIN + remote_status=0 过滤语义与裸实体版**逐字一致**（存活频道单集、按 publish_date DESC，
     * podcast_title 实际非空）。裸实体版保留供 3a DAO 测试断言（该测试不在本批边界内）。
     */
    @Query(
        "SELECT podcast_episode.*, podcast.title AS podcast_title, " +
            "podcast_episode_local_state.cache_path AS cache_path FROM podcast_episode " +
            "INNER JOIN podcast " +
            "  ON podcast.account_id = podcast_episode.account_id " +
            " AND podcast.server_id = podcast_episode.podcast_id " +
            "LEFT JOIN podcast_episode_local_state " +
            "  ON podcast_episode_local_state.account_id = podcast_episode.account_id " +
            " AND podcast_episode_local_state.episode_id = podcast_episode.server_id " +
            "WHERE podcast_episode.account_id = :accountId AND podcast.remote_status = 0 " +
            "ORDER BY podcast_episode.publish_date DESC",
    )
    fun observeAllOfActivePodcastsWithTitle(accountId: String): Flow<List<EpisodeWithPodcastTitle>>

    /**
     * newestOfActivePodcasts 的投影变体：SELECT 加 `podcast.title AS podcast_title`，
     * 返回 [EpisodeWithPodcastTitle]（Store newestPodcastEpisodes 用）。JOIN/LIMIT 语义与裸实体版一致。
     */
    @Query(
        "SELECT podcast_episode.*, podcast.title AS podcast_title, " +
            "podcast_episode_local_state.cache_path AS cache_path FROM podcast_episode " +
            "INNER JOIN podcast " +
            "  ON podcast.account_id = podcast_episode.account_id " +
            " AND podcast.server_id = podcast_episode.podcast_id " +
            "LEFT JOIN podcast_episode_local_state " +
            "  ON podcast_episode_local_state.account_id = podcast_episode.account_id " +
            " AND podcast_episode_local_state.episode_id = podcast_episode.server_id " +
            "WHERE podcast_episode.account_id = :accountId AND podcast.remote_status = 0 " +
            "ORDER BY podcast_episode.publish_date DESC LIMIT 20",
    )
    fun newestOfActivePodcastsWithTitle(accountId: String): Flow<List<EpisodeWithPodcastTitle>>

    /**
     * 软删除差集（P1 合同语义）：把该频道中不在 keepIds 内的单集标 status = :deletedRaw，
     * 不物理删除。keepIds 为本次解析出的存活单集 server_id；deletedRaw 由调用方传
     * PodcastEpisodeRemoteStatus.DELETED.raw（不在 SQL 写魔法数字）。
     */
    @Query(
        "UPDATE podcast_episode SET status = :deletedRaw " +
            "WHERE account_id = :accountId AND podcast_id = :podcastId " +
            "AND server_id NOT IN (:keepIds)",
    )
    suspend fun markDeletedMissing(
        accountId: String,
        podcastId: String,
        keepIds: List<String>,
        deletedRaw: Int,
    )
}
