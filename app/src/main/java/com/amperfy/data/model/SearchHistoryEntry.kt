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

package com.amperfy.data.model

/**
 * 搜索结果实体类型 - 对应 iOS SearchSection 中可被搜索的实体（不含 History 本身）
 */
enum class SearchEntityType {
    ARTIST,
    ALBUM,
    PLAYLIST,
    SONG
}

/**
 * 搜索历史条目（领域模型）- 对应 iOS: SearchHistoryItem
 *
 * 存储被点击过的搜索结果快照，点击搜索结果时写入并按 [searchedAt] 倒序展示。
 * 对应 Room 持久化表 search_history（见 RoomSearchHistoryStore）。
 */
data class SearchHistoryEntry(
    val entityId: String,
    val type: SearchEntityType,
    val name: String,
    val subtitle: String = "",
    val coverArt: String? = null,
    val searchedAt: Long = 0L
)
