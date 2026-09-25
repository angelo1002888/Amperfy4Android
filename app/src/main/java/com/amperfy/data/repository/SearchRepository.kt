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

package com.amperfy.data.repository

import com.amperfy.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 搜索域仓库：远程全局搜索（search3，不落库）与搜索历史读写。
 * 对应 iOS: SearchVC 的数据源 + LibraryStorage 的 SearchHistory 方法。
 * 出处：Repository 拆分批次 1——方法自 MusicRepository 原样搬移，签名与注释不变。
 */
interface SearchRepository {
    // ==================== 搜索历史 ====================
    // 对应 iOS: LibraryStorage.getSearchHistory / createOrUpdateSearchHistory / deleteSearchHistory
    /** 观察搜索历史（按最近搜索时间倒序）。对应 iOS: getSearchHistory() */
    fun getSearchHistory(): Flow<List<SearchHistoryEntry>>
    /** 记录/更新一条搜索历史（点击搜索结果时）。对应 iOS: createOrUpdateSearchHistory(container:) */
    suspend fun addSearchHistory(entry: SearchHistoryEntry)
    /** 清空搜索历史。对应 iOS: deleteSearchHistory() */
    suspend fun clearSearchHistory()

    suspend fun search(query: String): Result<SearchResult>
}

data class SearchResult(
    val artists: List<Artist>,
    val albums: List<Album>,
    val songs: List<Song>
)
