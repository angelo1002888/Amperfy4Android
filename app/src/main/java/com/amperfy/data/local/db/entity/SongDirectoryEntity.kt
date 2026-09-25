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
 * Song 与 Directory 的 to-one 关系。
 *
 * 从 song 表拆出 directory 关联单独成表，防止普通歌曲元数据同步（remote upsert song）
 * 覆盖目录关联——与 song_local_state 同构。
 * 主键 (account_id, song_id)。仅 FK account_scope 并级联删除——**不** FK directory/song：
 * 目录差集清理（删目录仅解除歌曲关联，不删歌曲）语义由接口保证，非级联。
 * INDEX(account_id, directory_id)。
 */
@Entity(
    tableName = "song_directory",
    primaryKeys = ["account_id", "song_id"],
    foreignKeys = [
        ForeignKey(
            entity = AccountScopeEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("account_id", "directory_id"),
    ],
)
data class SongDirectoryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "directory_id") val directoryId: String,
)
