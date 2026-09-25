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

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.*
import com.amperfy.data.remote.SubsonicApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * 搜索域实现：远程全局搜索（search3 三路并发，不落库）与搜索历史读写。
 * 方法体自 MusicRepositoryImpl 逐字搬移。
 *
 * 出处：Repository 拆分批次 2。
 */
internal class SearchRepositoryImpl(
    subsonicApi: SubsonicApi,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val searchHistoryStore: com.amperfy.data.local.store.SearchHistoryStore,
    boundAccountInfo: AccountInfo?,
) : BaseSubsonicRepository(
    subsonicApi, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    SearchRepository {

    // ==================== 搜索历史 ====================

    override fun getSearchHistory(): Flow<List<SearchHistoryEntry>> =
        searchHistoryStore.observeHistory(currentAccountId)

    override suspend fun addSearchHistory(entry: SearchHistoryEntry) {
        try {
            searchHistoryStore.add(currentAccountId, entry)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "addSearchHistory error", e)
        }
    }

    override suspend fun clearSearchHistory() {
        try {
            searchHistoryStore.clear(currentAccountId)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "clearSearchHistory error", e)
        }
    }

    /**
     * 远程搜索（search3）— 对应 iOS SearchVC 的 searchArtists/searchAlbums/searchSongs。
     *
     * Subsonic 的 search3 单次请求按 *Count 参数返回各类目，这里分三次请求以分别控制
     * 艺术家/专辑/歌曲的数量（与 iOS 三个独立 searchXxx 同步请求一致）。
     *
     * 注意：结果仅映射为领域模型直接返回，不写入本地数据库，避免污染本地资料库及覆盖既有
     * 关系/统计/收藏数据；点击搜索结果进入详情页时，详情页会各自 fetch 同步该实体。
     *
     * 离线：静默跳过远程请求（对应 iOS searchArtists/searchAlbums/searchSongs 首行
     * `guard isSyncAllowed`，SubsonicLibrarySyncer.swift:1187/1213/1239）。返回 failure
     * 而非空结果——[com.amperfy.ui.screens.SearchViewModel] 以 `search(q).getOrNull() == null`
     * 表示「远程不可用」并回退整库本地搜索（无任何错误 UI），正是 iOS 离线时展示本地
     * Core Data 结果的等价行为；返回空成功反而会把本地结果覆盖成「无结果」。
     */
    override suspend fun search(query: String): Result<SearchResult> {
        if (!isSyncAllowed) return Result.failure(IllegalStateException("No network connection"))
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            // 三类并行请求（对齐 iOS 三个独立 searchXxx；串行延迟为并行 3 倍）。
            // 任一请求 HTTP/业务状态失败即抛异常 → Result.failure，由 SearchViewModel
            // 回退本地搜索（对齐 iOS 远程失败仅上报 eventLogger 后继续本地搜索）
            coroutineScope {
                val aid = currentAccountId
                val artistsResp = async { subsonicApi.requestSearchArtists(searchText = query) }
                val albumsResp = async { subsonicApi.requestSearchAlbums(searchText = query) }
                val songsResp = async { subsonicApi.requestSearchSongs(searchText = query) }

                val artists = requireOk(artistsResp.await(), "Search artists").searchResult3?.artist?.map { it.toArtist() } ?: emptyList()
                val albums = requireOk(albumsResp.await(), "Search albums").searchResult3?.album?.map { it.toAlbum() } ?: emptyList()
                val songs = requireOk(songsResp.await(), "Search songs").searchResult3?.song?.map { it.toSong(aid) } ?: emptyList()

                Result.success(SearchResult(artists = artists, albums = albums, songs = songs))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "search error", e)
            Result.failure(e)
        }
    }

}
