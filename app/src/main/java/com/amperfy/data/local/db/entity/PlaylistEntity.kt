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
 * 服务器播放列表快照。
 *
 * 复合主键 (account_id, server_id)；仅对 account_scope 建 FK 并级联删除。
 *
 * **不含 last_played**——该字段仅本地维护（Subsonic 不返回），归 playlist_local_state，
 * 避免服务器整对象同步覆盖本地最近播放时间。
 *
 * 播放列表 UI 排序按 Name/LastPlayed/ChangeDate/Duration 且无字母索引，故**不加**
 * section_key/sort_key 三派生键；仅保留 search_key 供本地搜索。多排序模式的
 * ORDER BY 由 P3 Store 下推，DAO observeAll 默认按 name。
 */
@Entity(
    tableName = "playlist",
    primaryKeys = ["account_id", "server_id"],
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
data class PlaylistEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "comment") val comment: String? = null,
    @ColumnInfo(name = "owner") val owner: String? = null,
    @ColumnInfo(name = "is_public") val isPublic: Boolean = false,
    @ColumnInfo(name = "song_count") val songCount: Int = 0,
    @ColumnInfo(name = "duration") val duration: Int = 0,
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    /** 服务器 created 时间戳，null = 未知 */
    @ColumnInfo(name = "created_at") val createdAt: Long? = null,
    /** 服务器 changed 时间戳，null = 未知 */
    @ColumnInfo(name = "changed_at") val changedAt: Long? = null,
    /** 本地搜索键，由 LibraryTextKeyNormalizer 与 name 原子生成 */
    @ColumnInfo(name = "search_key") val searchKey: String,
)
