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
 * 服务器播客快照。
 *
 * 复合主键 (account_id, server_id)；仅对 account_scope 建 FK 并级联删除。
 * 字段：title、depiction→description、coverArt、episodeCount、remoteStatus。
 *
 * remote_status 为 Int（对照 iOS RemoteStatus：available=0/deleted=1），
 * 服务器软删除播客保留、查询时过滤。
 *
 * PodcastsScreen 有搜索但无字母索引；PodcastsShowType「按名」排序需下推，故加 search_key + sort_key，
 * **不加** section_key。
 */
@Entity(
    tableName = "podcast",
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
        Index("account_id", "sort_key", "server_id"),
    ],
)
data class PodcastEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    /** 服务器远端状态（available=0/deleted=1） */
    @ColumnInfo(name = "remote_status") val remoteStatus: Int = 0,
    @ColumnInfo(name = "episode_count") val episodeCount: Int = 0,
    // 派生键（无字母索引，不含 section_key）
    @ColumnInfo(name = "search_key") val searchKey: String,
    @ColumnInfo(name = "sort_key") val sortKey: String,
)
