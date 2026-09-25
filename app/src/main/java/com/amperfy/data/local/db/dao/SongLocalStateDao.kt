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
import com.amperfy.data.local.db.entity.SongLocalStateEntity

/**
 * song_local_state 纯本地状态 DAO。
 *
 * 本表只承载本地写：cache_path、play_progress、play_count。服务器同步物理上不能触碰本表——
 * remote upsert 只写 song 表。三个写方法均为 upsert 语义（行不存在时先 INSERT），
 * 避免“先有本地状态才能有远端行”的顺序耦合。
 *
 * upsert 用「INSERT OR IGNORE + UPDATE」两步（同一 @Transaction）而非 SQLite UPSERT
 * `ON CONFLICT DO UPDATE`——后者需 SQLite 3.24（Android API 30+），本项目 minSdk 26 的
 * 框架 SQLite 不支持，两步写在全 API 级别均安全（DAO 纪律）。
 */
@Dao
interface SongLocalStateDao {
    /** 内部助手：确保 (account_id, song_id) 行存在（play_count 缺省 0），已存在则忽略。 */
    @Query(
        "INSERT OR IGNORE INTO song_local_state (account_id, song_id, play_count) " +
            "VALUES (:accountId, :songId, 0)",
    )
    suspend fun insertIfAbsent(accountId: String, songId: String)

    @Query(
        "UPDATE song_local_state SET cache_path = :cachePath " +
            "WHERE account_id = :accountId AND song_id = :songId",
    )
    suspend fun updateCachePath(accountId: String, songId: String, cachePath: String?)

    @Query(
        "UPDATE song_local_state " +
            "SET play_progress_ms = :progressMs, play_progress_updated_at = :updatedAt " +
            "WHERE account_id = :accountId AND song_id = :songId",
    )
    suspend fun updateProgress(accountId: String, songId: String, progressMs: Long, updatedAt: Long)

    @Query(
        "UPDATE song_local_state SET play_count = play_count + 1 " +
            "WHERE account_id = :accountId AND song_id = :songId",
    )
    suspend fun bumpPlayCount(accountId: String, songId: String)

    /** 设置缓存相对路径（null = 清空缓存标记）。行不存在时先插入。 */
    @Transaction
    suspend fun setCachePath(accountId: String, songId: String, cachePath: String?) {
        insertIfAbsent(accountId, songId)
        updateCachePath(accountId, songId, cachePath)
    }

    /** 保存单曲播放进度及写入时刻。行不存在时先插入。 */
    @Transaction
    suspend fun setProgress(accountId: String, songId: String, progressMs: Long, updatedAt: Long) {
        insertIfAbsent(accountId, songId)
        updateProgress(accountId, songId, progressMs, updatedAt)
    }

    /**
     * 本地播放次数 +1。行不存在时先插入（play_count=0）再 +1。
     * 服务器同步物理上不能触碰本方法/本表。
     */
    @Transaction
    suspend fun incrementPlayCount(accountId: String, songId: String) {
        insertIfAbsent(accountId, songId)
        bumpPlayCount(accountId, songId)
    }

    @Query("SELECT * FROM song_local_state WHERE account_id = :accountId AND song_id = :songId")
    suspend fun get(accountId: String, songId: String): SongLocalStateEntity?

    // ==================== P3 批次 1a 扩充 ====================

    /** 清除本账户全部歌曲的缓存标记（Delete Cache 全清用）。 */
    @Query("UPDATE song_local_state SET cache_path = NULL WHERE account_id = :accountId")
    suspend fun clearAllCachePaths(accountId: String)

    @Query(
        "UPDATE song_local_state " +
            "SET play_progress_ms = :progressMs, play_progress_updated_at = :updatedAt " +
            "WHERE account_id = :accountId AND song_id = :songId",
    )
    suspend fun updateProgressNullable(accountId: String, songId: String, progressMs: Long?, updatedAt: Long?)

    /**
     * 保存/清零单曲播放进度（progressMs/updatedAt 同为 null = 清零写 NULL）。行不存在时先插入
     * （保持两步 upsert 模式）。有效性判定由调用方完成，本方法只落值。
     */
    @Transaction
    suspend fun setProgressNullable(accountId: String, songId: String, progressMs: Long?, updatedAt: Long?) {
        insertIfAbsent(accountId, songId)
        updateProgressNullable(accountId, songId, progressMs, updatedAt)
    }
}
