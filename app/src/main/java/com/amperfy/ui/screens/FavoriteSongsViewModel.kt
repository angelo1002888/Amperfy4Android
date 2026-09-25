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
import com.amperfy.data.local.FavoriteSongSortType
import com.amperfy.data.model.Playable
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.PlayerMode
import com.amperfy.data.model.PlayContext
import com.amperfy.data.model.Song
import com.amperfy.data.model.toPlayableWithCredentials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Import queue extension functions
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.appendContextQueue
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipePlayContext
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.ui.util.SortSectionMode
import com.amperfy.ui.util.SwipeActionCoordinator

/**
 * 收藏歌曲排序档的分段模式 —— iOS 的 Favorite Songs 就是 `SongsVC` 挂 favorites 过滤，
 * 故与普通歌曲页共用 `SongElementSortType.asSectionIndexType`
 * （FetchedResultsControllers.swift:118-131）：
 * name→alphabet、rating→rating、duration→durationSong、
 * **starredDate→none / addedDate→newestOrRecent（两档均无索引、无分段）**。
 *
 * 放在本文件（而非 `FavoriteSongSortType` 声明处）以免 `data.local` 反向依赖 `ui.util`。
 */
val FavoriteSongSortType.sectionMode: SortSectionMode
    get() = when (this) {
        FavoriteSongSortType.NAME -> SortSectionMode.ALPHABET
        FavoriteSongSortType.RATING -> SortSectionMode.RATING
        FavoriteSongSortType.DURATION -> SortSectionMode.DURATION_SONG
        FavoriteSongSortType.STARRED_DATE, FavoriteSongSortType.DATE_ADDED -> SortSectionMode.NONE
    }

/**
 * 行间分割线是否按段边界区分形态（段间全宽 / 段内 16dp inset）——与 [SongSortType] 同规则：
 * 按 Albums 页 iOS 实机观测（2026-08-10）同构推广，rating/duration 段间全宽，
 * name 例外（字母组间仍 16dp inset），starredDate/addedDate 不分段。
 */
val FavoriteSongSortType.hasFullWidthSectionDividers: Boolean
    get() = this == FavoriteSongSortType.RATING || this == FavoriteSongSortType.DURATION

/**
 * FavoriteSongsViewModel - ViewModel for Favorite Songs screen
 *
 * 对应iOS: FavoriteSongsVC的数据管理逻辑
 * 使用FavoriteSongsFetchedResultsController获取收藏歌曲
 */
@HiltViewModel
class FavoriteSongsViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    // UI状态（排序方式从设置恢复，对应 iOS favoriteSongSortSetting）
    private val _uiState = MutableStateFlow(
        FavoriteSongsUiState(sortType = appDelegate.settings.favoriteSongsSortSetting.value)
    )
    val uiState: StateFlow<FavoriteSongsUiState> = _uiState.asStateFlow()

    // 所有收藏歌曲
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())

    // 排序 + 过滤后的歌曲列表
    val filteredSongs: StateFlow<List<Song>> = combine(
        _allSongs,
        _uiState
    ) { songs, state ->
        // 排序（对应 iOS SongElementSortType：Name/Rating/Duration/Starred date）
        val sorted = when (state.sortType) {
            // NAME：store 已按 sort_key 排序返回（P3 批次 1c 下沉）；中文标题从码点序改为拼音分区序，与右侧字母索引一致（行为变化已记文档 15）
            FavoriteSongSortType.NAME -> songs
            FavoriteSongSortType.RATING -> songs.sortedWith(
                compareByDescending<Song> { it.rating ?: 0 }.thenBy { it.title.lowercase() }
            )
            // DURATION：时长升序（iOS SongMO.durationSortedFetchRequest 的
            // `combinedDuration ascending: true`，SongMO+CoreDataClass.swift:115-131）
            FavoriteSongSortType.DURATION -> songs.sortedBy { it.duration }
            FavoriteSongSortType.STARRED_DATE -> songs.sortedByDescending { it.starred ?: 0L }
            // 与 SongsViewModel 的 DATE_ADDED 同源实现（有 created 的排前、再按时间倒序）
            FavoriteSongSortType.DATE_ADDED -> songs.sortedWith(
                compareByDescending<Song> { it.created != null }
                    .thenByDescending { it.created ?: Long.MIN_VALUE }
            )
        }
        val searchText = state.searchText
        if (searchText.isBlank()) {
            sorted
        } else {
            sorted.filter { song ->
                song.title.contains(searchText, ignoreCase = true) ||
                (song.artist?.contains(searchText, ignoreCase = true) == true) ||
                song.album.contains(searchText, ignoreCase = true)
            }
        }
    }
        // 排序/过滤移出主线程（与 SongsViewModel 同模式），避免订阅瞬间在转场帧执行
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 播放器状态
    val isPlaying = appDelegate.player.isPlaying
    val currentPlayingSong = appDelegate.player.currentSong

    // 下拉刷新状态（对应 iOS SongsVC 的 refreshControl）
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadFavoriteSongs()
    }

    /**
     * 处理下拉刷新 - 对应 iOS SongsVC.handleRefresh（Favorite Songs 即 SongsVC 挂 favorites
     * 过滤，handleRefresh 与普通 Songs 完全相同）：
     * iOS 走 AutoDownloadLibrarySyncer.syncNewestLibraryElements()（同步最新专辑 + 对新专辑
     * 扇出同步歌曲），Android 同名仓库方法对齐该语义；离线模式直接结束刷新（对齐 iOS isOnlineMode 守卫）
     */
    fun refresh() {
        viewModelScope.launch {
            if (appDelegate.settings.isOfflineMode.value) {
                _isRefreshing.value = false
                return@launch
            }
            _isRefreshing.value = true
            try {
                val newestSongs = appDelegate.library
                    .syncNewestLibraryElements()
                    .getOrNull()
                    .orEmpty()
                // Auto cache latest Songs（Settings→Account→Auto Cache）：新出现专辑的歌曲自动下载
                // 对应 iOS AutoDownloadLibrarySyncer.swift:73-80
                // 该设置为账户级（AccountSettingsStore），须按 active 账户 ident 读；
                // 读全局旧键会导致设置页开关对当前账户不生效
                val autoCacheEnabled = appDelegate.accountSettings
                    .settings(appDelegate.accounts.activeAccountId ?: "")
                    .value.isAutoCacheLatestSongs
                if (newestSongs.isNotEmpty() && autoCacheEnabled) {
                    appDelegate.downloader.downloadSongs(newestSongs)
                }
            } catch (e: Exception) {
                android.util.Log.e("FavoriteSongsVM", "Refresh failed", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * 加载收藏歌曲
     * 对应iOS: FavoriteSongsFetchedResultsController.fetch()
     */
    private fun loadFavoriteSongs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            appDelegate.library.getFavoriteSongs().collect { songs ->
                _allSongs.value = songs
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 搜索文本变化
     */
    fun onSearchTextChanged(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    /**
     * 切换排序方式并持久化
     * 对应iOS: SongsVC.change(sortType:) + saveSortPreference（favoriteSongSortSetting）
     */
    fun changeSortType(sortType: FavoriteSongSortType) {
        appDelegate.settings.setFavoriteSongsSortSetting(sortType)
        _uiState.update { it.copy(sortType = sortType) }
    }

    /**
     * 下载全部收藏歌曲
     * 对应iOS: SongsVC.createActionButtonMenu "Download Favorite Songs"
     * （超过 [MANY_SONGS_WARNING_THRESHOLD] 时由调用方先弹 "Many Songs" 确认，与 iOS 一致）
     */
    fun downloadAllFavorites() {
        appDelegate.downloader.downloadSongs(_allSongs.value)
    }

    /** 当前收藏歌曲数（供调用方判断是否需要弹「Many Songs」确认） */
    fun favoriteSongsCount(): Int = _allSongs.value.size

    /** 歌曲行菜单 Download（iOS: EntityPreviewActionBuilder.createDownloadAction） */
    fun downloadSong(song: Song) {
        appDelegate.downloader.downloadSong(song)
    }

    /** 歌曲行菜单 Delete Cache（iOS: EntityPreviewActionBuilder.createDeleteCacheAction） */
    fun deleteSongCache(song: Song) {
        viewModelScope.launch {
            appDelegate.downloader.deleteSongCache(song)
        }
    }

    /**
     * 播放指定歌曲
     *
     * 上下文口径与滑动/菜单 PLAY 同源（见 [buildPlayContext]）：当前展示列表自被点曲起截
     * [MAX_PLAY_CONTEXT_COUNT] 首、起始索引 0——iOS 行点击与滑动走的是同一个
     * `convertIndexPathToPlayContext`（SongsVC.swift:276-288；Favorite Songs 即 SongsVC
     * 挂 favorites 过滤），故与 [SongsViewModel.playSong] 完全同构。
     * 此前为「全列表 + startIndex=index」，与 iOS 的 40 首截断不符。
     */
    fun playSong(song: Song) {
        viewModelScope.launch {
            val contextSongs = withContext(Dispatchers.Default) {
                val songs = filteredSongs.value
                val index = songs.indexOfFirst { it.id == song.id }
                if (index >= 0) {
                    val endIndex = minOf(index + MAX_PLAY_CONTEXT_COUNT, songs.size)
                    songs.subList(index, endIndex).map { it.toPlayable() }
                } else null
            }
            if (contextSongs != null) {
                appDelegate.player.playPlaylist(
                    songs = contextSongs,
                    startIndex = 0,
                    contextType = PlayContextType.NONE,
                    contextId = null,
                    contextName = "Favorite Songs"
                )
            }
        }
    }

    /**
     * 播放全部
     */
    fun playAll() {
        viewModelScope.launch {
            val songs = filteredSongs.value
            if (songs.isNotEmpty()) {
                val playContext = PlayContext(
                    index = 0,
                    name = "Favorite Songs",
                    playables = songs.map { it.toPlayable() },
                    type = PlayerMode.MUSIC
                )
                appDelegate.player.playPlaylist(
                    songs = playContext.playables,
                    startIndex = playContext.index,
                    contextType = PlayContextType.NONE,
                    contextId = null,
                    contextName = playContext.name
                )
            }
        }
    }

    /**
     * 随机播放全部
     */
    fun shuffleAll() {
        viewModelScope.launch {
            val songs = filteredSongs.value
            if (songs.isNotEmpty()) {
                val shuffledSongs = songs.shuffled()
                val playContext = PlayContext(
                    index = 0,
                    name = "Favorite Songs",
                    playables = shuffledSongs.map { it.toPlayable() },
                    type = PlayerMode.MUSIC
                )
                appDelegate.player.playPlaylist(
                    songs = playContext.playables,
                    startIndex = 0,
                    contextType = PlayContextType.NONE,
                    contextId = null,
                    contextName = playContext.name
                )
            }
        }
    }

    /**
     * 切换收藏状态
     * 对应iOS: EntityPreviewActionBuilder.createFavoriteMenu
     */
    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            try {
                appDelegate.library.toggleSongFavorite(song.id)
            } catch (e: Exception) {
                android.util.Log.e("FavoriteSongsVM", "Toggle favorite error", e)
            }
        }
    }

    /**
     * 添加到播放队列
     */
    fun addToQueue(song: Song) {
        viewModelScope.launch {
            val playable = song.toPlayable()
            appDelegate.player.addToQueue(playable)
        }
    }

    /**
     * 添加到播放列表
     */
    fun addToPlaylist(song: Song) {
        swipeCoordinator.requestPlaylistSelector(listOf(song.id))
    }

    // Methods from BasePlayableViewModel (no longer inheriting)

    // ═══════════════════════════════════════════════════════════
    // Queue Operations - Using extension functions from SongQueueExtensions.kt
    // Replaces duplicate methods previously copied from BasePlayableViewModel
    // ═══════════════════════════════════════════════════════════

    /**
     * Convert Song to Playable with credentials
     * 对应iOS: Song.toPlayable()
     */
    private fun Song.toPlayable() = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)

    /**
     * Add song to user queue (next in queue)
     * 对应iOS: Player.insertUserQueue()
     */
    fun addToQueueNext(song: Song) {
        song.addToQueueNext(appDelegate, viewModelScope)
    }

    /**
     * Add song to user queue (later in queue)
     * 对应iOS: Player.appendUserQueue()
     */
    fun addToQueueLater(song: Song) {
        song.addToQueueLater(appDelegate, viewModelScope)
    }

    /**
     * Insert song at beginning of context queue
     * 对应iOS: Player.insertContextQueue()
     */
    fun insertContextQueue(song: Song) {
        song.insertContextQueue(appDelegate, viewModelScope)
    }

    /**
     * Append song to end of context queue
     * 对应iOS: Player.appendContextQueue()
     */
    fun appendContextQueue(song: Song) {
        song.appendContextQueue(appDelegate, viewModelScope)
    }

    /**
     * 滑动动作配置与结果协调（删除缓存确认、播放列表选择器）
     */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val isOfflineMode = appDelegate.settings.isOfflineMode

    /**
     * 处理滑动动作
     *
     * PLAY / PLAY_SHUFFLED 带上本页真实播放上下文（对应 iOS SongsVC.swift:276-288——
     * Favorite Songs 即 SongsVC 挂 favorites 过滤，与普通 Songs 同一实现）。
     */
    fun handleSwipeAction(song: Song, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(
                    song.handleSwipeAction(action, appDelegate, buildPlayContext(song))
                )
            } catch (e: Exception) {
                android.util.Log.e("FavoriteSongsVM", "Error handling swipe action", e)
            }
        }
    }

    /**
     * 构造滑动/菜单播放上下文：当前展示列表（排序 + 搜索过滤后）自被滑动曲起截
     * [MAX_PLAY_CONTEXT_COUNT] 首、起始索引 0，上下文名 = iOS filterTitle
     * "Favorite Songs"（SongsVC.swift:138）。被滑动曲已不在列表中时返回 null，
     * 由 handleSwipeAction 退化为单曲上下文。
     */
    private fun buildPlayContext(song: Song): SwipePlayContext? {
        val list = filteredSongs.value
        val index = list.indexOfFirst { it.id == song.id }
        if (index < 0) return null
        return SwipePlayContext(
            contextType = PlayContextType.NONE,
            contextId = null,
            contextName = "Favorite Songs",
            songs = list.drop(index).take(MAX_PLAY_CONTEXT_COUNT),
            startIndex = 0
        )
    }
}

/**
 * FavoriteSongs UI状态
 */
data class FavoriteSongsUiState(
    val isLoading: Boolean = false,
    val searchText: String = "",
    // 默认对齐 iOS defaultValueForFavorite = .starredDate（实际取值由 ViewModel 从设置读入）
    val sortType: FavoriteSongSortType = FavoriteSongSortType.STARRED_DATE,
    val error: String? = null
)
