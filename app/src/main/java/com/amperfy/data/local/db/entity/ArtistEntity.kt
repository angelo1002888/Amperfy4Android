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
 * 服务器艺术家快照。
 *
 * 复合主键 (account_id, server_id)，不再使用 localId 拼接字段。
 * 仅保存服务器下发的标量快照字段 + 三派生键；**不含 albumCount/songCount/duration**——
 * 这些统计改由 DAO 聚合 Projection 计算，避免陈旧统计字段。
 *
 * FK 仅对 account_scope(account_id) 建立并级联删除（登出清理）；与 album/song 之间
 * 是 eventually-consistent 的标量 ID 关系，不建 FK。
 *
 * 派生键 search_key/section_key/sort_key 由 LibraryTextKeyNormalizer 与 name 原子生成。
 */
@Entity(
    tableName = "artist",
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
data class ArtistEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    /** 收藏时间戳；null = 未收藏 */
    @ColumnInfo(name = "starred_at") val starredAt: Long? = null,
    /** 评分 0-5，0 = 未评分（非空标量） */
    @ColumnInfo(name = "rating") val rating: Int = 0,
    // 派生键（唯一维护者为 LibraryTextKeyNormalizer）
    @ColumnInfo(name = "search_key") val searchKey: String,
    @ColumnInfo(name = "section_key") val sectionKey: String,
    @ColumnInfo(name = "sort_key") val sortKey: String,
)
