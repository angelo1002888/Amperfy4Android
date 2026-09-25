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
import kotlinx.coroutines.flow.Flow

/**
 * song_directory 关系 DAO。
 *
 * 从 song 表拆出，防止普通歌曲同步覆盖目录关联。setDirectory 两步 upsert；clearForSongs 供
 * 目录差集清理（删目录仅解除歌曲关联，不删歌曲）。所有方法带 account_id。
 *
 * P3 批次 4a 扩充（纯新增不接线，4b 收口 RoomLibraryLocalStore 委托）：
 * observeSongsWithLocalIn 出目录内歌曲的 [SongWithLocal] 投影（按 track 升序）——
 * 关系在独立表，故须 JOIN song_directory 取关联、
 * LEFT JOIN song_local_state 出缓存/播放次数。
 */
@Dao
interface SongDirectoryDao {
    /** 内部助手：确保 (account_id, song_id) 行存在。仅供 setDirectory 调用。 */
    @Query(
        "INSERT OR IGNORE INTO song_directory (account_id, song_id, directory_id) " +
            "VALUES (:accountId, :songId, :directoryId)",
    )
    suspend fun insertIfAbsent(accountId: String, songId: String, directoryId: String)

    @Query(
        "UPDATE song_directory SET directory_id = :directoryId " +
            "WHERE account_id = :accountId AND song_id = :songId",
    )
    suspend fun updateDirectory(accountId: String, songId: String, directoryId: String)

    /** 设置歌曲所属目录。行不存在时先插入。 */
    @Transaction
    suspend fun setDirectory(accountId: String, songId: String, directoryId: String) {
        insertIfAbsent(accountId, songId, directoryId)
        updateDirectory(accountId, songId, directoryId)
    }

    /** 解除一批歌曲的目录关联（目录差集清理用，不删歌曲）。 */
    @Query("DELETE FROM song_directory WHERE account_id = :accountId AND song_id IN (:songIds)")
    suspend fun clearForSongs(accountId: String, songIds: List<String>)

    /** 某目录下的歌曲 id 列表。 */
    @Query(
        "SELECT song_id FROM song_directory " +
            "WHERE account_id = :accountId AND directory_id = :directoryId",
    )
    suspend fun getSongIdsIn(accountId: String, directoryId: String): List<String>

    // ==================== SongWithLocal 投影查询（P3 批次 4a） ====================

    /**
     * 某目录下的歌曲（+ 本地状态），限定本账户、按 track 升序。
     *
     * 关联经 INNER JOIN song_directory（双列匹配 account_id + song_id）——关系表无行的歌曲
     * 天然不出现（无目录归属的歌曲不匹配）；本地状态经 LEFT JOIN
     * song_local_state，缺行时 cache_path 为 null、play_count 由 COALESCE 兜底 0（列名口径与
     * [SongDao.SONG_WITH_LOCAL] 一致）。
     *
     * 排序 `song.track, song.server_id`：track 升序、null 排在最前（SQLite ORDER BY 升序
     * NULL 优先）；server_id 为同 track 的稳定 tie-break。
     */
    @Query(
        "SELECT song.*, " +
            "song_local_state.cache_path AS cache_path, " +
            "COALESCE(song_local_state.play_count, 0) AS play_count " +
            "FROM song " +
            "JOIN song_directory " +
            "  ON song_directory.account_id = song.account_id " +
            " AND song_directory.song_id = song.server_id " +
            "LEFT JOIN song_local_state " +
            "  ON song_local_state.account_id = song.account_id " +
            " AND song_local_state.song_id = song.server_id " +
            "WHERE song.account_id = :accountId AND song_directory.directory_id = :directoryId " +
            "ORDER BY song.track, song.server_id",
    )
    fun observeSongsWithLocalIn(accountId: String, directoryId: String): Flow<List<SongWithLocal>>
}
