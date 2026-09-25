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
 * 歌曲纯本地状态。
 *
 * 与 song 表按所有权拆分：远端快照写 song 表、本地状态写本表，物理上服务器同步无法覆盖本地状态。
 * 主键 (account_id, song_id)。仅 FK account_scope 并级联删除——**不** FK song 表：
 * 远端差集删除不得连带删除本地状态，该语义由接口保证而非级联。
 *
 * - cache_path：缓存文件相对路径，null = 未缓存（替代 isDownloaded/downloadPath 双状态）；
 * - play_progress_ms / play_progress_updated_at：单曲播放进度及写入时刻，null = 无保存进度；
 * - play_count：纯本地播放次数，播放器确认从头播放时同一事务内 +1；服务器 DTO/remote upsert
 *   物理上不能接收该列。
 */
@Entity(
    tableName = "song_local_state",
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
        Index("account_id"),
    ],
)
data class SongLocalStateEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "cache_path") val cachePath: String? = null,
    @ColumnInfo(name = "play_progress_ms") val playProgressMs: Long? = null,
    @ColumnInfo(name = "play_progress_updated_at") val playProgressUpdatedAt: Long? = null,
    @ColumnInfo(name = "play_count", defaultValue = "0") val playCount: Int = 0,
)
