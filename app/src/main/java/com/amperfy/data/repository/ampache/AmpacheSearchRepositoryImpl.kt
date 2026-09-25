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

package com.amperfy.data.repository.ampache

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.store.SearchHistoryStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.SearchHistoryEntry
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.repository.SearchRepository
import com.amperfy.data.repository.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * 搜索域的 Ampache 实现（对应 iOS `AmpacheLibrarySyncer` searchArtists/searchAlbums/searchSongs，
 * :1309-1384）。
 *
 * 与 Subsonic 的端点级差异：Subsonic 是单一 search3，Ampache 是**三端点分立**
 * ——艺术家/专辑复用列表端点的 `filter` 模糊匹配，只有歌曲有专属 `search_songs`；
 * 三类各限 40 条（[AmpacheApi.SEARCH_RESULT_LIMIT]，iOS 三处硬编码同值）。
 *
 * 与 Subsonic 侧实现相同的两条纪律：三路并发；**结果不落库**（避免污染本地库、覆盖既有
 * 关系/统计/收藏），点进详情页时各页自行 fetch 同步。
 *
 * 出处：Ampache API 移植 Batch 2。
 */
internal class AmpacheSearchRepositoryImpl(
    ampacheApi: AmpacheApi,
    authSession: AmpacheAuthSession,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val searchHistoryStore: SearchHistoryStore,
    boundAccountInfo: AccountInfo?,
) : BaseAmpacheRepository(
    ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    SearchRepository {

    // ==================== 搜索历史（本地，与后端无关） ====================

    override fun getSearchHistory(): Flow<List<SearchHistoryEntry>> =
        searchHistoryStore.observeHistory(currentAccountId)

    override suspend fun addSearchHistory(entry: SearchHistoryEntry) {
        try {
            searchHistoryStore.add(currentAccountId, entry)
        } catch (e: Exception) {
            android.util.Log.e(AMPACHE_LOG_TAG, "addSearchHistory error", e)
        }
    }

    override suspend fun clearSearchHistory() {
        try {
            searchHistoryStore.clear(currentAccountId)
        } catch (e: Exception) {
            android.util.Log.e(AMPACHE_LOG_TAG, "clearSearchHistory error", e)
        }
    }

    /**
     * 远程搜索三端点并发。
     *
     * 离线返回 **failure 而非空成功**（与 Subsonic 侧同一约定）：SearchViewModel 以
     * `search(q).getOrNull() == null` 表示「远程不可用」并回退整库本地搜索；
     * 返回空成功反而会把本地结果覆盖成「无结果」。
     */
    override suspend fun search(query: String): Result<SearchResult> {
        if (!isSyncAllowed) return Result.failure(IllegalStateException("No network connection"))
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Search") {
            coroutineScope {
                val aid = currentAccountId
                val artistsTask = async { ampacheApi.requestSearchArtists(query) }
                val albumsTask = async { ampacheApi.requestSearchAlbums(query) }
                val songsTask = async { ampacheApi.requestSearchSongs(query) }

                SearchResult(
                    artists = artistsTask.await().items.map { it.toArtist() },
                    albums = albumsTask.await().items.map { it.toAlbum() },
                    songs = songsTask.await().items.map { it.toSong(aid) },
                )
            }
        }
    }
}
