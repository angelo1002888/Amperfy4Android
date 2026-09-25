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
import com.amperfy.data.model.Directory
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Song
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.appendContextQueue
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DirectoryDetail UI 状态
 */
data class DirectoryDetailUiState(
    val searchText: String = "",
    /** false=All / true=Cached（iOS scopeButtonTitles ["All","Cached"]，仅过滤歌曲） */
    val isCachedScope: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * DirectoryDetail ViewModel（Phase 6.5，目录浏览第三层：子目录 + 歌曲，可递归）
 * 对应 iOS: DirectoriesVC（MultiSourceTableViewController 双 section）
 *
 * - section 0 子目录（DirectorySubdirectoriesFetchedResultsController，parent==）
 * - section 1 歌曲（DirectorySongsFetchedResultsController，按 track 排序）
 * - 进入与下拉刷新同步 getMusicDirectory（iOS viewIsAppearing → sync(directory:)）
 * - 头部 Play/Shuffle + "N Song(s)"，无歌曲时禁用（iOS refreshHeaderView deactivate）
 */
@HiltViewModel
class DirectoryDetailViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val directoryId: String = savedStateHandle.get<String>("directoryId") ?: ""

    private val _uiState = MutableStateFlow(DirectoryDetailUiState())
    val uiState: StateFlow<DirectoryDetailUiState> = _uiState.asStateFlow()

    val directory: StateFlow<Directory?> =
        appDelegate.directories.observeDirectoryById(directoryId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 子目录：文本过滤，Cached 作用域不影响（iOS updateSearchResults:299-312） */
    val filteredSubdirectories: StateFlow<List<Directory>> = combine(
        appDelegate.directories.getSubdirectories(directoryId),
        _uiState
    ) { directories, state ->
        var result = directories
        if (state.searchText.isNotEmpty()) {
            result = result.filter { it.name.contains(state.searchText, ignoreCase = true) }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredSongs: StateFlow<List<Song>> = combine(
        appDelegate.directories.getDirectorySongs(directoryId),
        _uiState
    ) { songs, state ->
        var result = songs
        if (state.isCachedScope) result = result.filter { it.isCached }
        if (state.searchText.isNotEmpty()) {
            result = result.filter { it.title.contains(state.searchText, ignoreCase = true) }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 播放状态（行播放指示器用）
    val currentSong = appDelegate.player.currentSong
    val isPlaying = appDelegate.player.isPlaying

    /** 播放列表选择器协调（More 菜单 Add to Playlist，与 SongsViewModel 同模式） */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /**
     * 子目录行长按菜单动作入口（与滑动手势共用 Directory.handleSwipeAction 单一真相源）
     * 对应 iOS: DirectoriesVC 的 SwipeActionContext(containable: directory)
     */
    fun handleDirectorySwipeAction(directory: Directory, action: SwipeActionType) {
        viewModelScope.launch {
            swipeCoordinator.onResult(directory.handleSwipeAction(action, appDelegate))
        }
    }

    init {
        sync(showRefreshing = false)
    }

    fun onSearchTextChanged(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    fun setCachedScope(cached: Boolean) {
        _uiState.update { it.copy(isCachedScope = cached) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun handleRefresh() {
        sync(showRefreshing = true)
    }

    private fun sync(showRefreshing: Boolean) {
        viewModelScope.launch {
            if (appDelegate.settings.isOfflineMode.value) {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
                return@launch
            }
            if (showRefreshing) _uiState.update { it.copy(isRefreshing = true) }
            try {
                appDelegate.directories.syncDirectory(directoryId)
                    .onFailure { e ->
                        // iOS 错误主题 "Directory Sync"
                        _uiState.update { it.copy(error = "Directory Sync failed: ${e.message}") }
                    }
            } finally {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /** 头部 Play：目录歌曲顺序播放（离线只播缓存，iOS getContextSongs(onlyCachedSongs:)） */
    fun playAll() {
        playSongs(shuffled = false)
    }

    /** 头部 Shuffle：目录歌曲乱序播放 */
    fun shuffleAll() {
        playSongs(shuffled = true)
    }

    private fun playSongs(shuffled: Boolean) {
        viewModelScope.launch {
            try {
                var songs = filteredSongs.value
                if (appDelegate.settings.isOfflineMode.value) {
                    songs = songs.filter { it.isCached }
                }
                if (songs.isEmpty()) return@launch
                val ordered = if (shuffled) songs.shuffled() else songs
                appDelegate.player.playPlaylist(
                    songs = ordered.map { it.toPlayable() },
                    startIndex = 0,
                    contextType = PlayContextType.FOLDER,
                    contextId = directoryId,
                    contextName = directory.value?.name ?: "Directory"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play directory", e)
            }
        }
    }

    /** 单曲播放（以目录歌曲列表为上下文，iOS convertIndexPathToPlayContext:179-186） */
    fun playSong(song: Song) {
        viewModelScope.launch {
            try {
                val songs = filteredSongs.value
                val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                appDelegate.player.playPlaylist(
                    songs = songs.map { it.toPlayable() },
                    startIndex = index,
                    contextType = PlayContextType.FOLDER,
                    contextId = directoryId,
                    contextName = directory.value?.name ?: "Directory"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play song", e)
            }
        }
    }

    fun toggleSongFavorite(song: Song) {
        viewModelScope.launch {
            appDelegate.library.toggleSongFavorite(song.id)
        }
    }

    fun setSongRating(song: Song, rating: Int) {
        viewModelScope.launch {
            appDelegate.library.updateSongRating(song.id, rating)
        }
    }

    fun downloadSong(song: Song) {
        viewModelScope.launch {
            appDelegate.downloader.downloadSong(song)
        }
    }

    fun deleteSongCache(song: Song) {
        viewModelScope.launch {
            appDelegate.downloader.deleteSongCache(song)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 队列操作 - 使用 SongQueueExtensions.kt（对应 iOS More 菜单 Music Queue 子菜单）
    // ═══════════════════════════════════════════════════════════

    fun addToQueueNext(song: Song) {
        song.addToQueueNext(appDelegate, viewModelScope)
    }

    fun addToQueueLater(song: Song) {
        song.addToQueueLater(appDelegate, viewModelScope)
    }

    fun insertContextQueue(song: Song) {
        song.insertContextQueue(appDelegate, viewModelScope)
    }

    fun appendContextQueue(song: Song) {
        song.appendContextQueue(appDelegate, viewModelScope)
    }

    /** More 菜单 Add to Playlist：交由 Screen 弹 PlaylistSelectorDialog */
    fun addToPlaylist(song: Song) {
        swipeCoordinator.requestPlaylistSelector(listOf(song.id))
    }

    /**
     * 生成带认证流 URL 的 Playable（与 AlbumDetail/FavoriteSongs VM 同模式）。
     * 不可用裸 data.model.toPlayable：其 streamUrl 回退 path（服务器相对路径），
     * 未缓存歌曲会被 ExoPlayer 当本地文件打开导致 FileNotFoundException
     */
    private fun Song.toPlayable() = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)

    companion object {
        private const val TAG = "DirectoryDetailViewModel"
    }
}
