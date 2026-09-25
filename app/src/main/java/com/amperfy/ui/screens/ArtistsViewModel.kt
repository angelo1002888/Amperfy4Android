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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.Artist
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Playable
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/**
 * 艺术家显示过滤器
 * 对应iOS: ArtistCategoryFilter
 */
enum class ArtistDisplayFilter(val title: String) {
    ALL("Artists"),                   // 所有艺术家（iOS filterTitle .all = "Artists"）
    ALBUM_ARTISTS("Album Artists"),   // 有专辑的艺术家
    FAVORITES("Favorite Artists");    // 收藏的艺术家

    companion object {
        /** 解析持久化值（枚举名），无效或缺失回退 [ALBUM_ARTISTS]（对齐 iOS defaultValue = .albumArtists） */
        fun fromPersisted(value: String?): ArtistDisplayFilter =
            entries.find { it.name == value } ?: ALBUM_ARTISTS
    }
}

/**
 * 艺术家排序类型
 * 对应iOS: ArtistElementSortType
 */
enum class ArtistSortType {
    NAME,       // 按名称排序
    RATING,     // 按评分排序
    DURATION,   // 按时长排序
    NEWEST;     // 按最新添加排序

    companion object {
        /** 解析持久化值（枚举名），无效或缺失回退 [NAME]（对齐 iOS defaultValue = .name） */
        fun fromPersisted(value: String?): ArtistSortType =
            entries.find { it.name == value } ?: NAME
    }
}

/**
 * Artists UI状态
 */
data class ArtistsUiState(
    val displayFilter: ArtistDisplayFilter = ArtistDisplayFilter.ALBUM_ARTISTS,
    val sortType: ArtistSortType = ArtistSortType.NAME,
    val searchText: String = "",
    val isSearchActive: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
) {
    // 对应iOS的sceneTitle - 根据iOS代码，ALL和ALBUM_ARTISTS都显示"Artists"
    val sceneTitle: String
        get() = when (displayFilter) {
            ArtistDisplayFilter.ALL -> "Artists"
            ArtistDisplayFilter.ALBUM_ARTISTS -> "Artists"
            ArtistDisplayFilter.FAVORITES -> "Favorite Artists"
        }

    // 对应iOS的filterTitle
    val filterTitle: String
        get() = displayFilter.title
}

/**
 * Artists ViewModel
 * 对应iOS: ArtistsVC + ArtistFetchedResultsController
 */
@HiltViewModel
class ArtistsViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ArtistsViewModel"

        // 使用中文Collator进行拼音排序
        private val chineseCollator: Collator = Collator.getInstance(Locale.CHINESE).apply {
            strength = Collator.PRIMARY
        }

        /** 将导航参数 filter 解析为显示过滤器（默认 ALBUM_ARTISTS，与 Library「Artists」一致） */
        fun parseFilter(value: String?): ArtistDisplayFilter = when (value) {
            "favorites" -> ArtistDisplayFilter.FAVORITES
            "all" -> ArtistDisplayFilter.ALL
            else -> ArtistDisplayFilter.ALBUM_ARTISTS
        }
    }

    // 初始显示过滤器 - 导航参数优先（Library「Favorite Artists」等入口），
    // 无导航参数时读设备级持久化（对应 iOS settings.user.artistsFilterSetting）
    private val initialFilter: ArtistDisplayFilter =
        savedStateHandle.get<String>("filter")?.let { parseFilter(it) }
            ?: ArtistDisplayFilter.fromPersisted(appDelegate.settings.artistsFilterSetting.value)

    // UI状态（排序方式同样读持久化，对应 iOS settings.user.artistsSortSetting）
    private val _uiState = MutableStateFlow(
        ArtistsUiState(
            displayFilter = initialFilter,
            sortType = ArtistSortType.fromPersisted(appDelegate.settings.artistsSortSetting.value)
        )
    )
    val uiState: StateFlow<ArtistsUiState> = _uiState.asStateFlow()

    // 基础艺术家列表（根据过滤器）
    @OptIn(ExperimentalCoroutinesApi::class)
    private val baseArtists: Flow<List<Artist>> = _uiState
        .map { it.displayFilter }
        .distinctUntilChanged()
        .flatMapLatest { filter ->
            when (filter) {
                ArtistDisplayFilter.ALL -> appDelegate.library.getAllArtists()
                ArtistDisplayFilter.ALBUM_ARTISTS -> appDelegate.library.getAlbumArtists()
                ArtistDisplayFilter.FAVORITES -> appDelegate.library.getFavoriteArtists()
            }
        }

    // 过滤和排序后的艺术家列表
    val filteredArtists: StateFlow<List<Artist>> = combine(
        baseArtists,
        _uiState
    ) { artists, state ->
        var result = artists

        // 搜索过滤
        if (state.searchText.isNotEmpty()) {
            result = result.filter {
                it.name.contains(state.searchText, ignoreCase = true)
            }
        }

        // 排序
        result = when (state.sortType) {
            // NAME：store 已按 sort_key（拼音分区 + 原文，# 严格最后）排序返回（P3 批次 1c 下沉），无须客户端重排
            ArtistSortType.NAME -> result
            ArtistSortType.RATING -> result.sortedByDescending { it.rating }
            ArtistSortType.DURATION -> result.sortedByDescending { it.duration }
            // NEWEST：Room 无 addedAt 列（Artist.addedAt 恒 0，已知退化）
            ArtistSortType.NEWEST -> result.sortedByDescending { it.addedAt }
        }

        result
    }
        // 过滤/拼音排序移出主线程（与 SongsViewModel 同模式）；
        // 否则订阅瞬间（页面切换转场前几帧）在主线程执行，导致转场掉帧
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 按字母分组的艺术家（用于索引导航）
    val groupedArtists: StateFlow<Map<String, List<Artist>>> = filteredArtists
        .map { artists ->
            if (_uiState.value.sortType == ArtistSortType.NAME) {
                artists.groupBy { it.getAlphabeticSection() }
                    .toSortedMap(compareBy(chineseCollator) { it })
            } else {
                emptyMap()
            }
        }
        // 分组（逐项拼音节 + Collator 排序）同样移出主线程
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    init {
        // 初始化时自动加载数据
        // iOS: viewIsAppearing() -> updateFromRemote()
        // 注意：iOS版本只在特定过滤器下才同步，All/AlbumArtists过滤器从数据库读取
        updateFromRemote()
    }

    /**
     * 从服务器更新数据（如果需要）
     * iOS: updateFromRemote()
     *
     * 行为说明：
     * - All/AlbumArtists: 不同步，直接从数据库读取（iOS: break）
     * - Favorites: 同步收藏的艺术家
     */
    private fun updateFromRemote() {
        viewModelScope.launch {
            try {
                // 离线模式不发起远程同步（对应 iOS: guard isOnlineMode else return，ArtistsVC.swift:148）
                if (appDelegate.settings.isOfflineMode.value) {
                    android.util.Log.d(TAG, "Offline mode, skipping remote sync")
                    return@launch
                }
                when (_uiState.value.displayFilter) {
                    ArtistDisplayFilter.ALL, ArtistDisplayFilter.ALBUM_ARTISTS -> {
                        // iOS: break (不同步，从数据库读取)
                        android.util.Log.d(TAG, "Filter is All/AlbumArtists, using cached data")
                    }
                    ArtistDisplayFilter.FAVORITES -> {
                        // 同步收藏的艺术家 - iOS: syncFavoriteLibraryElements (getStarred2)
                        android.util.Log.d(TAG, "Filter is Favorites, syncing from server")
                        _uiState.update { it.copy(isLoading = true) }
                        try {
                            appDelegate.library.syncFavoriteElements()
                        } finally {
                            _uiState.update { it.copy(isLoading = false) }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to update from remote", e)
            }
        }
    }

    /**
     * 加载艺术家数据（已废弃，使用updateFromRemote）
     * 保留此方法以便手动触发完整同步
     */
    private fun loadArtists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // 第一步：先同步Albums，以便获得Albums的songCount数据
                // 这样我们可以基于Albums来估算Artist的songCount
                val albumsResult = appDelegate.library.syncAlbums()
                if (albumsResult.isFailure) {
                    android.util.Log.w(TAG, "Failed to sync albums: ${albumsResult.exceptionOrNull()?.message}")
                }

                // 第二步：同步Artists列表（包含albumCount）
                val artistsResult = appDelegate.library.syncArtists()

                if (artistsResult.isFailure) {
                    android.util.Log.w(TAG, "Failed to sync artists from server: ${artistsResult.exceptionOrNull()?.message}")
                    // 即使同步失败，也显示本地缓存的数据
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load artists", e)
                _uiState.update { it.copy(error = "Failed to load artists: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 改变显示过滤器（并持久化）
     * iOS: change(filterType:) → settings.user.artistsFilterSetting
     */
    fun setDisplayFilter(filter: ArtistDisplayFilter) {
        _uiState.update { it.copy(displayFilter = filter) }
        appDelegate.settings.setArtistsFilterSetting(filter.name)
    }

    /**
     * 改变排序类型（并持久化）
     * iOS: change(sortType:) → settings.user.artistsSortSetting
     */
    fun setSortType(sortType: ArtistSortType) {
        _uiState.update { it.copy(sortType = sortType) }
        appDelegate.settings.setArtistsSortSetting(sortType.name)
    }

    /**
     * 更新搜索文本
     * iOS: updateSearchResults(for:)
     */
    fun onSearchTextChanged(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    /**
     * 切换搜索激活状态
     */
    fun setSearchActive(active: Boolean) {
        _uiState.update {
            it.copy(
                isSearchActive = active,
                searchText = if (!active) "" else it.searchText
            )
        }
    }

    /**
     * 播放艺术家的所有歌曲
     * iOS: playContextAtIndexPathCallback
     */
    fun playArtist(artist: Artist) {
        viewModelScope.launch {
            try {
                val songs = appDelegate.library.getArtistSongs(artist.id).first()
                if (songs.isNotEmpty()) {
                    // 生成带认证流 URL（裸 toPlayable 的 path 回退会被当本地文件打开）
                    val playables = songs.map {
                        it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                    }
                    appDelegate.player.playPlaylist(
                        songs = playables,
                        startIndex = 0,
                        contextType = PlayContextType.ARTIST,
                        contextId = artist.id,
                        contextName = artist.name
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play artist", e)
                _uiState.update { it.copy(error = "Failed to play artist: ${e.message}") }
            }
        }
    }

    /**
     * 切换艺术家收藏状态
     * iOS: EntityPreviewActionBuilder - toggleFavorite
     */
    fun toggleFavorite(artist: Artist) {
        viewModelScope.launch {
            try {
                appDelegate.library.toggleArtistFavorite(artist.id)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to toggle favorite", e)
                _uiState.update { it.copy(error = "Failed to toggle favorite: ${e.message}") }
            }
        }
    }

    /**
     * 更新艺术家评分
     */
    fun updateRating(artist: Artist, rating: Int) {
        viewModelScope.launch {
            try {
                appDelegate.library.updateArtistRating(artist.id, rating)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to update rating", e)
                _uiState.update { it.copy(error = "Failed to update rating: ${e.message}") }
            }
        }
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 刷新艺术家数据（从服务器同步）
     * iOS: handleRefresh -> syncNewestLibraryElements
     */
    fun refresh() {
        // 下拉刷新时，强制同步数据（不管当前过滤器）
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // 先同步Albums
                appDelegate.library.syncAlbums()
                // 再同步Artists
                appDelegate.library.syncArtists()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to refresh artists", e)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 处理下拉刷新
     * iOS: handleRefresh() -> syncNewestLibraryElements
     *
     * 对应iOS的AutoDownloadLibrarySyncer.syncNewestLibraryElements()
     * 下拉刷新时同步最新的library元素（Artists和Albums）
     */
    fun handleRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                // 离线模式不发起远程同步（对应 iOS: guard isOnlineMode else return，ArtistsVC.swift:351）
                if (appDelegate.settings.isOfflineMode.value) {
                    _uiState.update { it.copy(isRefreshing = false) }
                    return@launch
                }
                when (_uiState.value.displayFilter) {
                    // Favorites 页刷新收藏（iOS: getStarred2）
                    ArtistDisplayFilter.FAVORITES ->
                        appDelegate.library.syncFavoriteElements()
                    else -> {
                        // iOS: AutoDownloadLibrarySyncer.syncNewestLibraryElements()
                        // 同步最新的Albums和Artists
                        appDelegate.library.syncAlbums()
                        appDelegate.library.syncArtists()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to refresh artists", e)
                _uiState.update { it.copy(error = "Failed to refresh: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * Download <filterTitle>（对应 iOS ArtistsVC.createActionButtonMenu，ArtistsVC.swift:469-503）。
     *
     * iOS 语义：按当前 displayFilter 取艺术家集合，再取这些艺术家的全部歌曲
     * （`artists.compactMap { $0.playables }.joined()`）入下载队列；
     * 歌曲数超过 [MANY_SONGS_WARNING_THRESHOLD] 时由调用方先弹确认。
     */
    suspend fun collectFilteredArtistSongs(): List<com.amperfy.data.model.Song> = try {
        val artists = when (_uiState.value.displayFilter) {
            ArtistDisplayFilter.ALL -> appDelegate.library.getAllArtists().first()
            ArtistDisplayFilter.ALBUM_ARTISTS -> appDelegate.library.getAlbumArtists().first()
            ArtistDisplayFilter.FAVORITES -> appDelegate.library.getFavoriteArtists().first()
        }
        val artistIds = artists.map { it.id }.toSet()
        if (artistIds.isEmpty()) {
            emptyList()
        } else {
            // 一次性取全库歌曲后按 artistId 过滤（逐艺术家查是 N 次查询）
            appDelegate.library.getAllSongs().first().filter { it.artistId in artistIds }
        }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Failed to collect artist songs", e)
        emptyList()
    }

    /** 把上面取到的歌曲入下载队列（确认框由调用方按阈值决定是否先弹） */
    fun downloadSongs(songs: List<com.amperfy.data.model.Song>) {
        android.util.Log.i(TAG, "Starting download for ${songs.size} songs")
        appDelegate.downloader.downloadSongs(songs)
    }

    /**
     * 滑动动作配置与结果协调（删除缓存确认、播放列表选择器）
     */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val isOfflineMode = appDelegate.settings.isOfflineMode

    /**
     * 处理滑动动作 - 对应iOS: BasicTableViewController.createSwipeAction
     */
    fun handleSwipeAction(artist: Artist, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(artist.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling swipe action", e)
            }
        }
    }

    /**
     * 含缓存歌曲的艺术家 id 集合：长按菜单据此判定 Play/Shuffle 是否可用（离线时）
     * 与 Delete Cache 是否显示（对应 iOS entityContainer.playables.hasCachedItems）
     */
    /** 全部歌曲已缓存的艺术家 id 集合（对应 iOS isCachedCompletely）：菜单隐藏 Download */
    val fullyCachedArtistIds: StateFlow<Set<String>> = appDelegate.library.getFullyCachedArtistIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val cachedArtistIds: StateFlow<Set<String>> = appDelegate.library.getCachedArtistIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * 设置艺术家评分 - 对应 iOS: EntityPreviewActionBuilder.createRatingMenu()
     */
    fun setRating(artist: Artist, rating: Int) {
        viewModelScope.launch {
            try {
                appDelegate.library.updateArtistRating(artist.id, rating)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error updating artist rating", e)
            }
        }
    }
}
