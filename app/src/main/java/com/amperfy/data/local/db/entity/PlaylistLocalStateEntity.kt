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
 * 播放列表纯本地状态。
 *
 * 与 playlist 表按所有权拆分：服务器快照写 playlist 表、本地状态写本表，物理上服务器同步
 * 无法覆盖 last_played（与 song_local_state 同构）。
 * 主键 (account_id, playlist_id)。仅 FK account_scope 并级联删除——**不** FK playlist：
 * 远端差集删除不得连带删除本地状态，该语义由接口保证。
 *
 * - last_played：本地记录的最近播放时间戳（毫秒），null = 从未播放。
 */
@Entity(
    tableName = "playlist_local_state",
    primaryKeys = ["account_id", "playlist_id"],
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
data class PlaylistLocalStateEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "last_played") val lastPlayed: Long? = null,
)
