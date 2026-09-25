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

package com.amperfy.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 专辑同步状态。
 *
 * 从 album 快照中拆出的本地/派生同步态，主键 (account_id, album_id)。
 * 仅 FK account_scope 并级联删除——**不** FK album 表（远端差集删除不连带本地同步态）。
 *
 * - is_songs_synced：是否已同步该专辑歌曲元数据（对应 iOS isSongsMetaDataSynced）；
 * - newest_index / recent_index：在服务器 Newest/Recent 列表中的序号（1 起，0 = 不在列表），
 *   由 getAlbumList2 返回顺序写入。
 */
@Entity(
    tableName = "album_sync_state",
    primaryKeys = ["account_id", "album_id"],
    foreignKeys = [
        ForeignKey(
            entity = AccountScopeEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("account_id"),
        Index("account_id", "newest_index"),
        Index("account_id", "recent_index"),
        Index("account_id", "is_songs_synced"),
    ],
)
data class AlbumSyncStateEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "album_id") val albumId: String,
    @ColumnInfo(name = "is_songs_synced", defaultValue = "0") val isSongsSynced: Boolean = false,
    @ColumnInfo(name = "newest_index", defaultValue = "0") val newestIndex: Int = 0,
    @ColumnInfo(name = "recent_index", defaultValue = "0") val recentIndex: Int = 0,
)
