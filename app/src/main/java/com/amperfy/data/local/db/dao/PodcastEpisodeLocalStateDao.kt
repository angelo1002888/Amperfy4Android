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
import androidx.room.Transaction
import com.amperfy.data.local.db.entity.PodcastEpisodeLocalStateEntity

/**
 * podcast_episode_local_state 纯本地状态 DAO（Batch 4 落地，表在 Batch 2 已建）。
 *
 * 结构与写法**逐条对称 [SongLocalStateDao]**：本表只承载本地写（cache_path、play_progress），
 * 服务器同步物理上不能触碰本表——remote upsert 只写 podcast_episode 表。写方法均为 upsert 语义
 * （行不存在时先 INSERT），避免“先有本地状态才能有远端行”的顺序耦合。
 *
 * upsert 同样用「INSERT OR IGNORE + UPDATE」两步（同一 @Transaction）而非 SQLite UPSERT
 * `ON CONFLICT DO UPDATE`——后者需 SQLite 3.24（Android API 30+），本项目 minSdk 26 的
 * 框架 SQLite 不支持。
 */
@Dao
interface PodcastEpisodeLocalStateDao {
    /** 内部助手：确保 (account_id, episode_id) 行存在，已存在则忽略。 */
    @Query(
        "INSERT OR IGNORE INTO podcast_episode_local_state (account_id, episode_id) " +
            "VALUES (:accountId, :episodeId)",
    )
    suspend fun insertIfAbsent(accountId: String, episodeId: String)

    @Query(
        "UPDATE podcast_episode_local_state SET cache_path = :cachePath " +
            "WHERE account_id = :accountId AND episode_id = :episodeId",
    )
    suspend fun updateCachePath(accountId: String, episodeId: String, cachePath: String?)

    @Query(
        "UPDATE podcast_episode_local_state " +
            "SET play_progress_ms = :progressMs, play_progress_updated_at = :updatedAt " +
            "WHERE account_id = :accountId AND episode_id = :episodeId",
    )
    suspend fun updateProgressNullable(
        accountId: String,
        episodeId: String,
        progressMs: Long?,
        updatedAt: Long?,
    )

    /** 设置缓存相对路径（null = 清空缓存标记）。行不存在时先插入。 */
    @Transaction
    suspend fun setCachePath(accountId: String, episodeId: String, cachePath: String?) {
        insertIfAbsent(accountId, episodeId)
        updateCachePath(accountId, episodeId, cachePath)
    }

    /**
     * 保存/清零单集播放进度（progressMs/updatedAt 同为 null = 清零写 NULL）。行不存在时先插入。
     * 有效性判定由调用方完成，本方法只落值（与 [SongLocalStateDao.setProgressNullable] 同口径）。
     */
    @Transaction
    suspend fun setProgressNullable(
        accountId: String,
        episodeId: String,
        progressMs: Long?,
        updatedAt: Long?,
    ) {
        insertIfAbsent(accountId, episodeId)
        updateProgressNullable(accountId, episodeId, progressMs, updatedAt)
    }

    @Query(
        "SELECT * FROM podcast_episode_local_state " +
            "WHERE account_id = :accountId AND episode_id = :episodeId",
    )
    suspend fun get(accountId: String, episodeId: String): PodcastEpisodeLocalStateEntity?

    /** 清除本账户全部单集的缓存标记（Delete Cache 全清用，对照 SongLocalStateDao.clearAllCachePaths）。 */
    @Query("UPDATE podcast_episode_local_state SET cache_path = NULL WHERE account_id = :accountId")
    suspend fun clearAllCachePaths(accountId: String)
}
