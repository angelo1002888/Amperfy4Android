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
 * 服务器流派快照。
 *
 * Subsonic Genre 无服务器 id，原始 name 是精确业务键，复合主键 (account_id, name)。
 * **禁止对 name 做 Unicode normalize 后作主键**——服务端可能把规范等价字符串视为两个不同名称。
 * 身份列 name 与用于搜索的 search_key 分开：name 原样存储、绑定参数比较；search_key 供大小写/子串搜索。
 *
 * album_count/song_count 为 getGenres 服务器快照（与 iOS 由关系反推的已知差异）。
 * FK 仅对 account_scope 建立并级联删除。
 */
@Entity(
    tableName = "genre",
    primaryKeys = ["account_id", "name"],
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
        Index("account_id", "sort_key", "name"),
        Index("account_id", "section_key", "sort_key", "name"),
    ],
)
data class GenreEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    /** 身份列：原样存储，禁止规范化 */
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "album_count") val albumCount: Int = 0,
    @ColumnInfo(name = "song_count") val songCount: Int = 0,
    // 派生键（与身份列 name 分开）
    @ColumnInfo(name = "search_key") val searchKey: String,
    @ColumnInfo(name = "section_key") val sectionKey: String,
    @ColumnInfo(name = "sort_key") val sortKey: String,
)
