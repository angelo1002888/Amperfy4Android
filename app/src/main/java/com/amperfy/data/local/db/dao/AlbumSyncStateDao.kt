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
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.AlbumSyncStateEntity

/**
 * album_sync_state 同步状态 DAO。
 *
 * setSongsSynced 为 upsert 语义，采用「INSERT OR IGNORE + UPDATE」两步（避免 SQLite UPSERT
 * 语法在 minSdk 26 框架 SQLite 上不受支持），保证专辑详情同步标记不依赖此前是否已写过
 * newest/recent 序号。
 */
@Dao
interface AlbumSyncStateDao {
    @Upsert
    suspend fun upsert(row: AlbumSyncStateEntity)

    /** 内部助手：确保 (account_id, album_id) 行存在（索引缺省 0），已存在则忽略。 */
    @Query(
        "INSERT OR IGNORE INTO album_sync_state " +
            "(account_id, album_id, is_songs_synced, newest_index, recent_index) " +
            "VALUES (:accountId, :albumId, 0, 0, 0)",
    )
    suspend fun insertIfAbsent(accountId: String, albumId: String)

    @Query(
        "UPDATE album_sync_state SET is_songs_synced = :synced " +
            "WHERE account_id = :accountId AND album_id = :albumId",
    )
    suspend fun updateSongsSynced(accountId: String, albumId: String, synced: Boolean)

    /** 设置歌曲元数据已同步标志。行不存在时先插入。 */
    @Transaction
    suspend fun setSongsSynced(accountId: String, albumId: String, synced: Boolean) {
        insertIfAbsent(accountId, albumId)
        updateSongsSynced(accountId, albumId, synced)
    }

    @Query("SELECT * FROM album_sync_state WHERE account_id = :accountId AND album_id = :albumId")
    suspend fun getByAlbumId(accountId: String, albumId: String): AlbumSyncStateEntity?

    // ==================== P3 批次 1a 扩充：newest/recent 序号（applyAlbumListPage 用） ====================

    /** offset==0 重写前清空本账户全部 newest 序号。 */
    @Query("UPDATE album_sync_state SET newest_index = 0 WHERE account_id = :accountId")
    suspend fun clearNewestIndexes(accountId: String)

    /** offset==0 重写前清空本账户全部 recent 序号。 */
    @Query("UPDATE album_sync_state SET recent_index = 0 WHERE account_id = :accountId")
    suspend fun clearRecentIndexes(accountId: String)

    @Query(
        "UPDATE album_sync_state SET newest_index = :index " +
            "WHERE account_id = :accountId AND album_id = :albumId",
    )
    suspend fun updateNewestIndex(accountId: String, albumId: String, index: Int)

    @Query(
        "UPDATE album_sync_state SET recent_index = :index " +
            "WHERE account_id = :accountId AND album_id = :albumId",
    )
    suspend fun updateRecentIndex(accountId: String, albumId: String, index: Int)

    /** 设置 newest 序号（只动该列；行不存在时先插入，不误清 recent/is_songs_synced）。 */
    @Transaction
    suspend fun setNewestIndex(accountId: String, albumId: String, index: Int) {
        insertIfAbsent(accountId, albumId)
        updateNewestIndex(accountId, albumId, index)
    }

    /** 设置 recent 序号（只动该列；行不存在时先插入）。 */
    @Transaction
    suspend fun setRecentIndex(accountId: String, albumId: String, index: Int) {
        insertIfAbsent(accountId, albumId)
        updateRecentIndex(accountId, albumId, index)
    }
}
