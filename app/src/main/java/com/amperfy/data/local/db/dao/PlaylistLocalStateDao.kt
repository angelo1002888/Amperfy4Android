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
import com.amperfy.data.local.db.entity.PlaylistLocalStateEntity

/**
 * playlist_local_state 纯本地状态 DAO。
 *
 * 服务器同步物理上不能触碰本表——remote upsert 只写 playlist 表。
 * setLastPlayed 采用「INSERT OR IGNORE + UPDATE」两步 upsert（避免 SQLite UPSERT 语法在
 * minSdk 26 框架 SQLite 上不受支持，与批次 1 一致）。时间由调用方传入，DAO 不取系统时钟。
 */
@Dao
interface PlaylistLocalStateDao {
    /** 内部助手：确保 (account_id, playlist_id) 行存在（last_played 缺省 NULL），已存在则忽略。 */
    @Query(
        "INSERT OR IGNORE INTO playlist_local_state (account_id, playlist_id, last_played) " +
            "VALUES (:accountId, :playlistId, NULL)",
    )
    suspend fun insertIfAbsent(accountId: String, playlistId: String)

    @Query(
        "UPDATE playlist_local_state SET last_played = :lastPlayed " +
            "WHERE account_id = :accountId AND playlist_id = :playlistId",
    )
    suspend fun updateLastPlayed(accountId: String, playlistId: String, lastPlayed: Long)

    /** 设置最近播放时间戳。行不存在时先插入。 */
    @Transaction
    suspend fun setLastPlayed(accountId: String, playlistId: String, lastPlayed: Long) {
        insertIfAbsent(accountId, playlistId)
        updateLastPlayed(accountId, playlistId, lastPlayed)
    }

    @Query(
        "SELECT * FROM playlist_local_state " +
            "WHERE account_id = :accountId AND playlist_id = :playlistId",
    )
    suspend fun get(accountId: String, playlistId: String): PlaylistLocalStateEntity?

    // ==================== 本地状态清理（P3 批次 2a） ====================
    // 本表仅 FK→account_scope 不级联 playlist——删/prune 播放列表时本表行不会随 FK 消失，
    // 须由 Store 显式清，使 lastPlayed 随播放列表删除一并消失，避免同 id 重建时
    // 旧 last_played 复活。

    /** 删除单个播放列表的本地状态行（deletePlaylist 用；不存在自然 no-op）。 */
    @Query("DELETE FROM playlist_local_state WHERE account_id = :accountId AND playlist_id = :playlistId")
    suspend fun deleteByPlaylistId(accountId: String, playlistId: String)

    /**
     * replacePlaylists prune：删除不在 keepPlaylistIds 内的本地状态行（限本账户）。
     * keepPlaylistIds 为空 = 全清（NOT IN 空表语义，与 library 域 clearStarredExcept 同模式）。
     */
    @Query("DELETE FROM playlist_local_state WHERE account_id = :accountId AND playlist_id NOT IN (:keepPlaylistIds)")
    suspend fun deleteAllExcept(accountId: String, keepPlaylistIds: List<String>)
}
