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
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 播放列表排序方式
 * 对应 iOS: PlaylistSortType（FetchedResultsControllers.swift）+ PlaylistsVC.createSortButtonMenu
 * 顺序与 iOS Sort 菜单完全一致：Name / Last time played / Change date / Duration
 */
enum class PlaylistSortType(val displayName: String) {
    NAME("Name"),
    LAST_PLAYED("Last time played"),
    CHANGE_DATE("Change date"),
    DURATION("Duration")
}

/**
 * 播放列表搜索作用域
 * 对应 iOS: PlaylistsVC scopeButtonTitles ["All", "Cached"]（PlaylistsVC.swift:43-46）
 */
enum class PlaylistsSearchScope { ALL, CACHED }

/**
 * PlaylistsViewModel - 播放列表列表页 ViewModel
 *
 * 对应 iOS: PlaylistsVC.swift
 *
 * 负责：观察本地播放列表、搜索过滤、排序、从服务器同步、创建/删除、
 * 以及对单个播放列表的播放/随机播放/加入队列操作。
 */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    // 排序方式持久化（对应 iOS: storage.settings.playlistsSortSetting）
    private val _sortType = MutableStateFlow(
        runCatching { PlaylistSortType.valueOf(appDelegate.settings.playlistsSortSetting.value) }
            .getOrDefault(PlaylistSortType.NAME)
    )
    val sortType: StateFlow<PlaylistSortType> = _sortType.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // 搜索作用域（Cached = 仅显示含缓存歌曲的播放列表）
    private val _searchScope = MutableStateFlow(PlaylistsSearchScope.ALL)
    val searchScope: StateFlow<PlaylistsSearchScope> = _searchScope.asStateFlow()

    fun setSearchScope(scope: PlaylistsSearchScope) {
        _searchScope.value = scope
    }

    /**
     * 经作用域 + 搜索过滤 + 排序后的播放列表
     * 数据源为本地 Room，服务器变更通过 syncFromServer 落地后自动发射
     */
    val playlists: StateFlow<List<Playlist>> = combine(
        appDelegate.playlists.getAllPlaylists(),
        appDelegate.playlists.getCachedPlaylistIds(),
        _searchText,
        _sortType,
        _searchScope
    ) { all, cachedIds, query, sort, scope ->
        var filtered = if (query.isBlank()) all
        else all.filter { it.name.contains(query, ignoreCase = true) }
        if (scope == PlaylistsSearchScope.CACHED) {
            filtered = filtered.filter { it.id in cachedIds }
        }

        when (sort) {
            PlaylistSortType.NAME -> filtered.sortedBy { it.name.lowercase() }
            PlaylistSortType.LAST_PLAYED -> filtered.sortedByDescending { it.lastPlayed ?: 0L }
            PlaylistSortType.CHANGE_DATE -> filtered.sortedByDescending { it.changed ?: it.created ?: 0L }
            // 对齐 iOS durationFetchRequest：duration 降序，次级 songCount 降序
            PlaylistSortType.DURATION -> filtered.sortedWith(
                compareByDescending<Playlist> { it.duration }.thenByDescending { it.songCount }
            )
        }
        // 排序/过滤放到 Default 线程：订阅瞬间（页面 push 转场期间）的全量排序不占主线程，
        // 防转场掉帧（与 SongsViewModel/ArtistsViewModel/AlbumsViewModel 同款）
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 入场自动同步走静默路径：iOS refreshControl 只在用户手动下拉时出现，
        // 程序化同步（viewIsAppearing → syncDownPlaylistsWithoutSongs）无任何视觉指示
        syncFromServer(showIndicator = false)
    }

    /**
     * 滑动动作配置与结果协调（删除缓存确认、播放列表选择器）
     * 对应 iOS: PlaylistsVC.swipeCallback -> SwipeActionContext(containable: playlist)
     */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val isOfflineMode = appDelegate.settings.isOfflineMode

    /**
     * 含缓存歌曲的播放列表 id 集合：长按菜单据此判定 Play/Shuffle 是否可用（离线时）
     * 与 Delete Cache 是否显示（对应 iOS entityContainer.playables.hasCachedItems）
     */
    /** 全部歌曲已缓存的播放列表 id 集合（对应 iOS isCachedCompletely）：菜单隐藏 Download */
    val fullyCachedPlaylistIds: StateFlow<Set<String>> =
        appDelegate.playlists.getFullyCachedPlaylistIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val cachedPlaylistIds: StateFlow<Set<String>> = appDelegate.playlists.getCachedPlaylistIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * 处理整个播放列表的滑动动作
     * 对应 iOS: BasicTableViewController.createSwipeAction
     */
    fun handleSwipeAction(playlist: Playlist, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(playlist.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Handle swipe action failed", e)
            }
        }
    }

    fun onSearchTextChanged(text: String) {
        _searchText.value = text
    }

    fun setSortType(type: PlaylistSortType) {
        _sortType.value = type
        appDelegate.settings.setPlaylistsSortSetting(type.name)
    }

    /**
     * 从服务器同步播放列表（仅元数据，不含歌曲）
     * 对应 iOS: PlaylistsVC.viewIsAppearing -> syncDownPlaylistsWithoutSongs
     *
     * @param showIndicator 是否驱动下拉刷新指示器。用户手动下拉传 true（默认）；
     *   入场自动同步传 false——iOS 的 refreshControl 只由手动下拉触发，程序化同步无视觉；
     *   Android 若在 init 置 isRefreshing，IOSPullToRefreshBox 会把内容整体下移再 spring 弹回，
     *   叠加在页面 push 转场上表现为「列表从底部弹上来」。
     */
    fun syncFromServer(showIndicator: Boolean = true) {
        viewModelScope.launch {
            if (showIndicator) _isRefreshing.value = true
            appDelegate.playlists.syncPlaylists()
                .onFailure { android.util.Log.e(TAG, "Sync playlists failed", it) }
            if (showIndicator) _isRefreshing.value = false
        }
    }

    /**
     * 同步所有播放列表（含每个列表的完整歌曲）
     * 对应 iOS: PlaylistsVC.createOptionsButtonMenu -> "Sync All Playlists"
     */
    fun syncAllPlaylists() {
        viewModelScope.launch {
            _isRefreshing.value = true
            appDelegate.playlists.syncPlaylists()
                .onFailure { android.util.Log.e(TAG, "Sync playlists failed", it) }
            // 逐个同步每个播放列表的歌曲详情。
            // 直接查询本地全量列表（对应 iOS library.getPlaylists()）：
            // playlists StateFlow 是搜索过滤后的视图且更新异步，用它会漏同步
            appDelegate.playlists.getAllPlaylists().first().forEach { playlist ->
                appDelegate.playlists.syncPlaylistDetails(playlist.id)
                    .onFailure {
                        android.util.Log.e(TAG, "Sync playlist details failed: ${playlist.id}", it)
                    }
            }
            _isRefreshing.value = false
        }
    }

    /**
     * 删除播放列表
     * 对应 iOS: PlaylistsVC editButtonItem -> commit editingStyle .delete
     */
    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            appDelegate.playlists.deletePlaylist(playlist.id)
                .onFailure { android.util.Log.e(TAG, "Delete playlist failed", it) }
        }
    }

    fun getCoverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        val credentials = appDelegate.credentials.getCredentials() ?: return null
        return appDelegate.mediaUrls.getCoverArtUrl(
            coverArtId = coverArtId,
            username = credentials.username,
            password = credentials.password,
            baseUrl = credentials.serverUrl
        )
    }

    companion object {
        private const val TAG = "PlaylistsViewModel"
    }
}
