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
 * 搜索历史快照（对应 iOS SearchHistoryItem）。
 *
 * 主键 (account_id, type, entity_id)（type 与 entityId 拆为独立列），保证同一账户内同一实体只保留一条记录（再次点击更新时间）。
 * 仅对 account_scope 建 FK 并级联删除。存储被搜索实体的快照，避免与实体生命周期耦合。
 * 点击结果时倒序按 searched_at 展示，INDEX(account_id, searched_at)。
 */
@Entity(
    tableName = "search_history",
    primaryKeys = ["account_id", "type", "entity_id"],
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
        Index("account_id", "searched_at"),
    ],
)
data class SearchHistoryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    /** 实体类型：ARTIST / ALBUM / PLAYLIST / SONG */
    @ColumnInfo(name = "type") val type: String,
    /** 被搜索实体服务端 id */
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "name") val name: String = "",
    @ColumnInfo(name = "subtitle") val subtitle: String = "",
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    @ColumnInfo(name = "searched_at") val searchedAt: Long = 0,
)
