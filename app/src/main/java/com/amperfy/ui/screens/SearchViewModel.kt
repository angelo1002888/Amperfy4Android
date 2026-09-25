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

package com.amperfy.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.SearchEntityType
import com.amperfy.data.model.SearchHistoryEntry
import com.amperfy.data.model.Song
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.appendContextQueue
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SearchViewModel - 对应 iOS: SearchVC
 *
 * 搜索流程对齐 iOS：
 * - 在线 + scope=All：调用 search3（艺术家/专辑/歌曲）+ 本地播放列表搜索
 * - 离线 + scope=All：搜索整个本地资料库（仅远程同步被挡，不强制按缓存过滤）
 * - scope=Cached：仅搜索本地，四类均按「含缓存歌曲」过滤（iOS: SearchVC.swift:595-658）
 * - 每个类目最多 10 条（iOS: categoryItemLimit = 10）
 * - 查询为空时展示搜索历史
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    companion object {
        private const val TAG = "SearchViewModel"
        const val CATEGORY_ITEM_LIMIT = 10   // 对应 iOS: SearchVC.categoryItemLimit
        private const val DEBOUNCE_MS = 300L
    }

    /** 搜索作用域 - 对应 iOS: scopeButtonTitles ["All", "Cached"] */
    enum class SearchScope { ALL, CACHED }

    /** 搜索结果四分类 - 对应 iOS: SearchSection（Artist/Album/Playlist/Song） */
    data class SearchResults(
        val artists: List<Artist> = emptyList(),
        val albums: List<Album> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val songs: List<Song> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && songs.isEmpty()
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _scope = MutableStateFlow(SearchScope.ALL)
    val scope: StateFlow<SearchScope> = _scope.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val isOfflineMode = appDelegate.settings.isOfflineMode

    /** 搜索历史（查询为空时展示）- 对应 iOS: searchHistory */
    val searchHistory: StateFlow<List<SearchHistoryEntry>> =
        appDelegate.search.getSearchHistory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 播放状态/下载进度（用于歌曲行指示器）
    val currentPlayingSong = appDelegate.player.currentSong
    val downloadProgressMap = appDelegate.downloader.downloadProgressMap

    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        _query.value = text
        scheduleSearch()
    }

    fun setScope(newScope: SearchScope) {
        if (_scope.value == newScope) return
        _scope.value = newScope
        scheduleSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val q = _query.value.trim()
        if (q.isEmpty()) {
            _results.value = SearchResults()
            _isSearching.value = false
            return
        }
        // 防抖前即置 true，避免输入首字符后旧结果为空时闪现「No Results」
        _isSearching.value = true
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            performSearch(q)
        }
    }

    private suspend fun performSearch(q: String) {
        try {
            val onlyCached = _scope.value == SearchScope.CACHED

            // 在线 + All：远程 search3；离线或 Cached：本地搜索
            // （对齐 iOS：离线只挡远程同步，All 作用域仍搜整个本地库）
            val remote = if (!onlyCached && !isOfflineMode.value) {
                appDelegate.search.search(q).getOrNull()
            } else null

            // 播放列表始终走本地（Subsonic search3 不返回播放列表，对齐 iOS）
            var playlists = appDelegate.playlists.searchPlaylists(q).first()
            if (onlyCached) {
                val cachedPlaylistIds = appDelegate.playlists.getCachedPlaylistIds().first()
                playlists = playlists.filter { it.id in cachedPlaylistIds }
            }

            _results.value = if (remote != null) {
                SearchResults(
                    artists = remote.artists.take(CATEGORY_ITEM_LIMIT),
                    albums = remote.albums.take(CATEGORY_ITEM_LIMIT),
                    playlists = playlists.take(CATEGORY_ITEM_LIMIT),
                    songs = remote.songs.take(CATEGORY_ITEM_LIMIT)
                )
            } else {
                var artists = appDelegate.library.searchArtists(q).first()
                var albums = appDelegate.library.searchAlbums(q).first()
                var songs = appDelegate.library.searchSongs(q).first()
                if (onlyCached) {
                    // Cached 作用域：四类均按「含缓存歌曲」过滤（iOS: SearchVC.swift:595-658）
                    val cachedArtistIds = appDelegate.library.getCachedArtistIds().first()
                    val cachedAlbumIds = appDelegate.library.getCachedAlbumIds().first()
                    artists = artists.filter { it.id in cachedArtistIds }
                    albums = albums.filter { it.id in cachedAlbumIds }
                    songs = songs.filter { it.isDownloaded }
                }
                SearchResults(
                    artists = artists.take(CATEGORY_ITEM_LIMIT),
                    albums = albums.take(CATEGORY_ITEM_LIMIT),
                    playlists = playlists.take(CATEGORY_ITEM_LIMIT),
                    songs = songs.take(CATEGORY_ITEM_LIMIT)
                )
            }
            _isSearching.value = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 被新一次搜索取消：必须重抛以保持结构化取消，且不动 isSearching（新搜索已置 true）
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "performSearch error", e)
            _isSearching.value = false
        }
    }

    /** 清空搜索历史 - 对应 iOS: "Clear Search History" */
    fun clearHistory() {
        viewModelScope.launch { appDelegate.search.clearSearchHistory() }
    }

    private fun recordHistory(entry: SearchHistoryEntry) {
        viewModelScope.launch {
            appDelegate.search.addSearchHistory(entry.copy(searchedAt = System.currentTimeMillis()))
        }
    }

    // ==================== 点击/播放 ====================
    // 对应 iOS: didSelectRowAt → createOrUpdateSearchHistory + 导航/播放

    fun onArtistTapped(artist: Artist) = recordHistory(artist.toHistoryEntry())
    fun onAlbumTapped(album: Album) = recordHistory(album.toHistoryEntry())
    fun onPlaylistTapped(playlist: Playlist) = recordHistory(playlist.toHistoryEntry())

    /**
     * 点击历史条目：刷新搜索时间戳置顶
     * 对应 iOS: didSelectRowAt(history) -> createOrUpdateSearchHistory（SearchVC.swift:459-465）
     */
    fun onHistoryTapped(entry: SearchHistoryEntry) = recordHistory(entry)

    /**
     * 点击 SONG 类型历史：从本地库解析歌曲并单曲播放（快照与实体解耦，实体不在库中则忽略）
     * 对应 iOS: 历史歌曲行是 PlayableTableCell，单击播放（SearchVC.swift:347-351）
     */
    fun playHistorySong(entry: SearchHistoryEntry) {
        viewModelScope.launch {
            recordHistory(entry)
            val song = appDelegate.library.getSongById(entry.entityId) ?: return@launch
            playSingle(song)
        }
    }

    /**
     * 点击歌曲：只播放该一首（单曲队列），并记录历史
     * 对应 iOS: PlayableTableCell 单击 = PlayContext(containable: song)（PlayableTableCell.swift:402-413）
     */
    fun playSong(song: Song) {
        viewModelScope.launch {
            playSingle(song)
            recordHistory(song.toHistoryEntry())
        }
    }

    /**
     * 菜单 Shuffle：搜索结果行的播放上下文与单击一致，即该单曲自身
     * （对应 iOS PlayContext(containable: song)），故乱序播放的对象也是这一首
     */
    fun shuffleSong(song: Song) {
        viewModelScope.launch {
            appDelegate.player.playShuffled(
                songs = listOf(song.toPlayable()),
                contextType = PlayContextType.SEARCH,
                contextId = null,
                contextName = "Search"
            )
            recordHistory(song.toHistoryEntry())
        }
    }

    private fun playSingle(song: Song) {
        appDelegate.player.playPlaylist(
            songs = listOf(song.toPlayable()),
            startIndex = 0,
            contextType = PlayContextType.SEARCH,
            contextId = null,
            contextName = "Search"
        )
    }

    // ==================== 歌曲 More 菜单操作（复用扩展函数，与各详情页一致） ====================

    /** 滑动/More 菜单结果协调（加入播放列表选择器） */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /**
     * 切换收藏：以搜索结果快照的 starred 判定现状（在线搜索结果不写本地库，
     * 用 toggleSongFavorite 按本地库判定会对库外歌曲恒执行 star），
     * 成功后更新快照让红心即时刷新（iOS 结果是 Core Data 活对象，天然刷新）
     */
    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val newState = song.starred == null
            appDelegate.library.setSongFavorite(song.id, newState)
                .onSuccess {
                    val ts = if (newState) System.currentTimeMillis() else null
                    updateSongInResults(song.id) { it.copy(starred = ts) }
                }
                .onFailure { android.util.Log.e(TAG, "Toggle favorite failed", it) }
        }
    }

    fun setSongRating(song: Song, rating: Int) {
        viewModelScope.launch {
            appDelegate.library.updateSongRating(song.id, rating)
                .onSuccess { updateSongInResults(song.id) { it.copy(rating = rating) } }
                .onFailure { android.util.Log.e(TAG, "Set rating failed", it) }
        }
    }

    /** 更新结果快照中的歌曲行（收藏/评分操作后即时反映到 UI）。 */
    private fun updateSongInResults(songId: String, transform: (Song) -> Song) {
        _results.update { r ->
            r.copy(songs = r.songs.map { if (it.id == songId) transform(it) else it })
        }
    }

    fun addToQueueNext(song: Song) = song.addToQueueNext(appDelegate, viewModelScope)
    fun addToQueueLater(song: Song) = song.addToQueueLater(appDelegate, viewModelScope)
    fun insertContextQueue(song: Song) = song.insertContextQueue(appDelegate, viewModelScope)
    fun appendContextQueue(song: Song) = song.appendContextQueue(appDelegate, viewModelScope)

    fun addToPlaylist(song: Song) = swipeCoordinator.requestPlaylistSelector(listOf(song.id))

    fun downloadSong(song: Song) {
        viewModelScope.launch { appDelegate.downloader.downloadSong(song) }
    }

    fun deleteCache(song: Song) {
        viewModelScope.launch { appDelegate.downloader.deleteSongCache(song) }
    }

    fun getCoverArtUrl(coverArtId: String?): String? {
        val id = coverArtId ?: return null
        val credentials = appDelegate.credentials.getCredentials() ?: return null
        return appDelegate.mediaUrls.getCoverArtUrl(id, credentials.username, credentials.password, credentials.serverUrl)
    }

    private fun Song.toPlayable() = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)

    private fun Artist.toHistoryEntry() = SearchHistoryEntry(
        // subtitle 与结果行口径一致（"N Albums • M Songs"），无统计时回退 "Artist"
        entityId = id, type = SearchEntityType.ARTIST, name = name,
        subtitle = getSubtitle() ?: "Artist", coverArt = coverArt
    )

    private fun Album.toHistoryEntry() = SearchHistoryEntry(
        entityId = id, type = SearchEntityType.ALBUM, name = name, subtitle = artist, coverArt = coverArt
    )

    private fun Playlist.toHistoryEntry() = SearchHistoryEntry(
        entityId = id, type = SearchEntityType.PLAYLIST,
        name = name, subtitle = "$songCount Song${if (songCount == 1) "" else "s"}", coverArt = coverArt
    )

    private fun Song.toHistoryEntry() = SearchHistoryEntry(
        entityId = id, type = SearchEntityType.SONG, name = title, subtitle = artist, coverArt = coverArt
    )
}
