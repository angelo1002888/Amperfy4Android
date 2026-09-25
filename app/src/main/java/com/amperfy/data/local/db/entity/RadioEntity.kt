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
 * 服务器电台快照。
 *
 * 复合主键 (account_id, server_id)；仅对 account_scope 建 FK 并级联删除。
 * 字段：title→name、streamUrl→stream_url、siteUrl→home_page_url。
 *
 * stream_url 是电台原始流 URL 的服务器快照本体（非账户拼接 URL），电台直连播放，允许存
 * （「原始 streamUrl 直连」）。
 *
 * RadiosScreen 有字母索引，故保留三派生键 search_key/section_key/sort_key。
 * cover_art 为服务器快照字段（Subsonic 电台可有封面）。
 */
@Entity(
    tableName = "radio",
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
        Index("account_id", "section_key", "sort_key", "server_id"),
    ],
)
data class RadioEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "home_page_url") val homePageUrl: String? = null,
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    // 派生键
    @ColumnInfo(name = "search_key") val searchKey: String,
    @ColumnInfo(name = "section_key") val sectionKey: String,
    @ColumnInfo(name = "sort_key") val sortKey: String,
)
