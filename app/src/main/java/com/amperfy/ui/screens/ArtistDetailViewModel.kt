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
import com.amperfy.data.download.DownloadManager
import com.amperfy.data.model.*
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// Import queue extension functions
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.appendContextQueue

/**
 * Artist详情UI状态
 */
data class ArtistDetailUiState(
    val artist: Artist? = null,
    val searchText: String = "",
    val isSearchActive: Boolean = false,
    val showCachedOnly: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)
// 注：头部的专辑/歌曲计数不取艺术家实体的存储计数字段（口径窄、依赖同步时机），
// 改由 ViewModel 的 headerAlbumCount/headerSongCount 提供——与页面列表同一复合查询，
// 对齐 iOS Artist.infoDetails 的相关计数（头部与列表数量恒一致）

/**
 * Artist Detail ViewModel
 * 对应iOS: ArtistDetailVC + ArtistAlbumsItemsFetchedResultsController + ArtistSongsItemsFetchedResultsController
 */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ArtistDetailViewModel"
    }

    // 从导航参数获取artistId
    private val artistId: String = checkNotNull(savedStateHandle["artistId"])

    // 播放器状态 - 用于显示播放指示器
    val isPlaying = appDelegate.player.isPlaying
    val currentPlayingSong = appDelegate.player.currentSong

    // UI状态
    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    // 艺术家信息
    val artist: StateFlow<Artist?> = appDelegate.library.observeArtistById(artistId)
        .onEach { artist ->
            _uiState.update { it.copy(artist = artist) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // 艺术家的专辑列表
    private val baseAlbums: Flow<List<Album>> = appDelegate.library.getArtistAlbums(artistId)

    // 过滤后的专辑列表（支持搜索和缓存过滤）
    val filteredAlbums: StateFlow<List<Album>> = combine(
        baseAlbums,
        _uiState
    ) { albums, state ->
        var result = albums

        // 缓存过滤
        if (state.showCachedOnly) {
            result = result.filter { it.isCached }
        }

        // 搜索过滤
        if (state.searchText.isNotEmpty()) {
            result = result.filter {
                it.name.contains(state.searchText, ignoreCase = true)
            }
        }

        result
    }
        // 过滤（含上游实体→领域模型逐项映射）移出主线程，避免订阅瞬间在转场帧执行
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 艺术家的歌曲列表
    private val baseSongs: Flow<List<Song>> = appDelegate.library.getArtistSongs(artistId)

    // 头部「N Albums · M Songs」计数——与专辑/歌曲列表同一复合查询（未过滤），
    // 对齐 iOS Artist.infoDetails 相关计数口径（LibraryStorage.getAlbums/
    // getSongs(whichContainsSongsWithArtist:)，artistsFilterSetting 默认 .albumArtists）
    val headerAlbumCount: StateFlow<Int> = baseAlbums.map { it.size }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val headerSongCount: StateFlow<Int> = baseSongs.map { it.size }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 过滤后的歌曲列表（支持搜索和缓存过滤）
    val filteredSongs: StateFlow<List<Song>> = combine(
        baseSongs,
        _uiState
    ) { songs, state ->
        var result = songs

        // 缓存过滤
        if (state.showCachedOnly) {
            result = result.filter { it.isCached }
        }

        // 搜索过滤
        if (state.searchText.isNotEmpty()) {
            result = result.filter {
                it.title.contains(state.searchText, ignoreCase = true)
            }
        }

        result
    }
        // 过滤（含上游实体→领域模型逐项映射）移出主线程，避免订阅瞬间在转场帧执行
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // 数据通过Flow自动从数据库加载
    }

    /**
     * 从服务器同步艺术家详情
     * 对应iOS: viewIsAppearing() -> artist.fetch(storage:librarySyncer:playableDownloadManager:)
     */
    fun fetch() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                appDelegate.library.syncArtistDetails(artistId = artistId)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to sync artist details", e)
                _uiState.update { it.copy(error = "Failed to sync: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // .toPlayable() method removed - now inherited from BasePlayableViewModel

    /**
     * 播放所有歌曲
     * 对应iOS: Play button (注意: iOS版本按钮文字是"Play"而非"Play All")
     */
    fun playAll() {
        viewModelScope.launch {
            try {
                val songs = filteredSongs.value
                if (songs.isNotEmpty()) {
                    val playables = songs.map { it.toPlayable() }
                    appDelegate.player.playPlaylist(
                        songs = playables,
                        startIndex = 0,
                        contextType = PlayContextType.ARTIST,
                        contextId = artistId,
                        contextName = artist.value?.name ?: "Artist"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play all", e)
                _uiState.update { it.copy(error = "Failed to play: ${e.message}") }
            }
        }
    }

    /**
     * 随机播放所有歌曲
     * 对应iOS: Shuffle button
     */
    fun shuffleAll() {
        viewModelScope.launch {
            try {
                val songs = filteredSongs.value
                if (songs.isNotEmpty()) {
                    val playables = songs.map { it.toPlayable() }.shuffled()
                    appDelegate.player.playPlaylist(
                        songs = playables,
                        startIndex = 0,
                        contextType = PlayContextType.ARTIST,
                        contextId = artistId,
                        contextName = artist.value?.name ?: "Artist"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to shuffle", e)
                _uiState.update { it.copy(error = "Failed to shuffle: ${e.message}") }
            }
        }
    }

    /**
     * 播放指定歌曲（从该歌曲开始播放整个列表）
     * 对应iOS: didSelectRow -> convertIndexPathToPlayContext
     */
    fun playSong(song: Song) {
        viewModelScope.launch {
            try {
                val songs = filteredSongs.value
                val index = songs.indexOf(song)
                if (index >= 0) {
                    val playables = songs.map { it.toPlayable() }
                    appDelegate.player.playPlaylist(
                        songs = playables,
                        startIndex = index,
                        contextType = PlayContextType.ARTIST,
                        contextId = artistId,
                        contextName = artist.value?.name ?: "Artist"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play song", e)
                _uiState.update { it.copy(error = "Failed to play: ${e.message}") }
            }
        }
    }

    /**
     * 播放专辑
     * 对应iOS: 点击album item -> 导航到AlbumDetailVC
     */
    fun playAlbum(album: Album) {
        viewModelScope.launch {
            try {
                val songs = appDelegate.library.getAlbumSongs(album.id).first()
                if (songs.isNotEmpty()) {
                    val playables = songs.map { it.toPlayable() }
                    appDelegate.player.playPlaylist(
                        songs = playables,
                        startIndex = 0,
                        contextType = PlayContextType.ALBUM,
                        contextId = album.id,
                        contextName = album.name
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play album", e)
                _uiState.update { it.copy(error = "Failed to play album: ${e.message}") }
            }
        }
    }

    /**
     * 切换艺术家收藏状态
     * 对应iOS: EntityPreviewActionBuilder - Favorite/Unfavorite
     */
    fun toggleFavorite() {
        viewModelScope.launch {
            try {
                appDelegate.library.toggleArtistFavorite(artistId)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to toggle favorite", e)
                _uiState.update { it.copy(error = "Failed to toggle favorite: ${e.message}") }
            }
        }
    }

    /**
     * 切换专辑收藏状态
     */
    fun toggleAlbumFavorite(album: Album) {
        viewModelScope.launch {
            try {
                appDelegate.library.toggleAlbumFavorite(album.id)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to toggle album favorite", e)
                _uiState.update { it.copy(error = "Failed to toggle favorite: ${e.message}") }
            }
        }
    }

    /**
     * 切换歌曲收藏状态
     */
    fun toggleSongFavorite(song: Song) {
        viewModelScope.launch {
            try {
                appDelegate.library.toggleSongFavorite(song.id)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to toggle song favorite", e)
                _uiState.update { it.copy(error = "Failed to toggle favorite: ${e.message}") }
            }
        }
    }

    /**
     * 设置艺术家评分
     * 对应iOS: EntityPreviewVC.setRating(rating:) for Artist
     */
    fun setArtistRating(rating: Int) {
        viewModelScope.launch {
            try {
                appDelegate.library.updateArtistRating(artistId, rating)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to set artist rating", e)
                _uiState.update { it.copy(error = "Failed to set rating: ${e.message}") }
            }
        }
    }

    /**
     * 设置专辑评分
     * 对应iOS: EntityPreviewVC.setRating(rating:) for Album
     * （专辑行长按上下文菜单 Rating 调色板；滑动动作无对应项，故单列一个方法）
     */
    fun setAlbumRating(album: Album, rating: Int) {
        viewModelScope.launch {
            try {
                appDelegate.library.updateAlbumRating(album.id, rating)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to set album rating", e)
                _uiState.update { it.copy(error = "Failed to set rating: ${e.message}") }
            }
        }
    }

    /**
     * 设置歌曲评分
     * 对应iOS: EntityPreviewVC.setRating(rating:) for Song
     */
    fun setSongRating(song: Song, rating: Int) {
        viewModelScope.launch {
            try {
                appDelegate.library.updateSongRating(song.id, rating)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to set song rating", e)
                _uiState.update { it.copy(error = "Failed to set rating: ${e.message}") }
            }
        }
    }

    /**
     * 将歌曲添加到队列
     * 对应iOS: SwipeAction - Add to Queue
     */
    fun addSongToQueue(song: Song) {
        viewModelScope.launch {
            try {
                appDelegate.player.addToQueue(song.toPlayable())
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to add to queue", e)
                _uiState.update { it.copy(error = "Failed to add to queue: ${e.message}") }
            }
        }
    }

    /**
     * 将专辑添加到队列
     */
    fun addAlbumToQueue(album: Album) {
        viewModelScope.launch {
            try {
                val songs = appDelegate.library.getAlbumSongs(album.id).first()
                songs.forEach { song ->
                    appDelegate.player.addToQueue(song.toPlayable())
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to add album to queue", e)
                _uiState.update { it.copy(error = "Failed to add to queue: ${e.message}") }
            }
        }
    }

    /**
     * 下载歌曲
     * 对应iOS: DownloadManager - download playable
     */
    fun downloadSong(song: Song) {
        viewModelScope.launch {
            try {
                appDelegate.downloader.downloadSong(song)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to download song", e)
                _uiState.update { it.copy(error = "Failed to download: ${e.message}") }
            }
        }
    }

    /**
     * 下载专辑
     */
    fun downloadAlbum(album: Album) {
        viewModelScope.launch {
            try {
                // 获取专辑的所有歌曲并下载
                val songs = appDelegate.library.getSongsByAlbum(album.id).first()
                if (songs.isNotEmpty()) {
                    appDelegate.downloader.downloadSongs(songs)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to download album", e)
                _uiState.update { it.copy(error = "Failed to download: ${e.message}") }
            }
        }
    }

    /**
     * 下载艺术家的所有歌曲
     * 对应iOS: EntityPreviewActionBuilder.createDownloadAction for Artist
     */
    fun downloadAllSongs() {
        viewModelScope.launch {
            try {
                val songs = filteredSongs.value
                if (songs.isNotEmpty()) {
                    appDelegate.downloader.downloadSongs(songs)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to download all songs", e)
                _uiState.update { it.copy(error = "Failed to download: ${e.message}") }
            }
        }
    }

    /**
     * 添加歌曲到播放列表
     * 对应iOS: Add to Playlist action
     */
    fun addSongToPlaylist(song: Song) {
        swipeCoordinator.requestPlaylistSelector(listOf(song.id))
    }

    /**
     * 添加艺术家的所有歌曲到播放列表
     * 对应iOS: EntityPreviewActionBuilder - Add to Playlist for Artist
     */
    fun addArtistToPlaylist() {
        viewModelScope.launch {
            try {
                val songIds = appDelegate.library.getArtistSongs(artistId).first().map { it.id }
                if (songIds.isNotEmpty()) {
                    swipeCoordinator.requestPlaylistSelector(songIds)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to add artist to playlist", e)
                _uiState.update { it.copy(error = "Failed to add to playlist: ${e.message}") }
            }
        }
    }

    /**
     * 删除缓存
     * 对应iOS: EntityPreviewActionBuilder - Delete Cache
     */
    fun deleteCache() {
        viewModelScope.launch {
            try {
                val songs = filteredSongs.value.filter { it.isCached }
                if (songs.isNotEmpty()) {
                    appDelegate.downloader.deleteSongsCache(songs)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to delete cache", e)
                _uiState.update { it.copy(error = "Failed to delete cache: ${e.message}") }
            }
        }
    }

    /**
     * 删除单首歌曲的缓存
     * 对应iOS: EntityPreviewActionBuilder.createDeleteCacheAction for Song
     */
    fun deleteSongCache(song: Song) {
        viewModelScope.launch {
            try {
                appDelegate.downloader.deleteSongCache(song)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to delete song cache", e)
                _uiState.update { it.copy(error = "Failed to delete cache: ${e.message}") }
            }
        }
    }

    /**
     * 更新搜索文本
     * 对应iOS: updateSearchResults(for: searchController)
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
     * 切换仅显示缓存
     * 对应iOS: Search scope buttons - ["All", "Cached"]
     */
    fun toggleShowCachedOnly() {
        _uiState.update { it.copy(showCachedOnly = !it.showCachedOnly) }
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ==================== Music Queue 操作方法 ====================

    /**
     * 获取艺术家的所有歌曲（用于队列操作）
     */
    private suspend fun getArtistSongs(): List<Song> {
        return appDelegate.library.getSongsByArtist(artistId).first()
    }

    /**
     * Insert User Queue (Play Next)
     * iOS: appDelegate.player.insertUserQueue(playables: playables)
     */
    fun insertUserQueue() {
        viewModelScope.launch {
            val songs = getArtistSongs()
            if (songs.isNotEmpty()) {
                val playables = songs.map { it.toPlayable() }
                appDelegate.player.insertUserQueue(playables)
            }
        }
    }

    /**
     * Append User Queue (Play Later)
     * iOS: appDelegate.player.appendUserQueue(playables: playables)
     */
    fun appendUserQueue() {
        viewModelScope.launch {
            val songs = getArtistSongs()
            if (songs.isNotEmpty()) {
                val playables = songs.map { it.toPlayable() }
                appDelegate.player.appendUserQueue(playables)
            }
        }
    }

    /**
     * Insert Context Queue
     * iOS: appDelegate.player.insertContextQueue(playables: playables)
     */
    fun insertContextQueue() {
        viewModelScope.launch {
            val songs = getArtistSongs()
            if (songs.isNotEmpty()) {
                val playables = songs.map { it.toPlayable() }
                appDelegate.player.insertContextQueue(playables)
            }
        }
    }

    /**
     * Append Context Queue
     * iOS: appDelegate.player.appendContextQueue(playables: playables)
     */
    fun appendContextQueue() {
        viewModelScope.launch {
            val songs = getArtistSongs()
            if (songs.isNotEmpty()) {
                val playables = songs.map { it.toPlayable() }
                appDelegate.player.appendContextQueue(playables)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Queue Operations - Using extension functions from SongQueueExtensions.kt
    // Replaces duplicate methods previously copied from BasePlayableViewModel
    // ═══════════════════════════════════════════════════════════

    private fun Song.toPlayable() = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)

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

    /**
     * 滑动动作配置与结果协调（删除缓存确认、播放列表选择器）
     */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val isOfflineMode = appDelegate.settings.isOfflineMode

    /**
     * 处理歌曲滑动动作 - 对应iOS: BasicTableViewController.createSwipeAction
     *
     * PLAY / PLAY_SHUFFLED 带上整个艺术家的播放上下文（见 [buildSongPlayContext]）。
     */
    fun handleSwipeAction(song: Song, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(
                    song.handleSwipeAction(action, appDelegate, buildSongPlayContext(song))
                )
            } catch (e: Exception) {
                android.util.Log.e("ArtistDetailVM", "Error handling swipe action", e)
            }
        }
    }

    /**
     * 构造歌曲行滑动/菜单播放上下文——对应 iOS ArtistDetailVC.swift:216-223
     * `PlayContext(containable: artist, index: playContextIndex, playables: songs)`：
     * 上下文 = 本艺术家全部歌曲（离线模式只含已缓存曲，iOS
     * `getContextSongs(onlyCachedSongs: isOfflineMode)`），起始索引 = 被滑动曲的位置。
     * 被滑动曲不在该列表中（离线且未缓存）时返回 null，退化为单曲上下文。
     */
    private fun buildSongPlayContext(song: Song): SwipePlayContext? {
        var context = filteredSongs.value
        if (appDelegate.settings.isOfflineMode.value) {
            context = context.filter { it.isDownloaded }
        }
        val index = context.indexOfFirst { it.id == song.id }
        if (index < 0) return null
        return SwipePlayContext(
            contextType = PlayContextType.ARTIST,
            contextId = artistId,
            contextName = artist.value?.name ?: "",
            songs = context,
            startIndex = index
        )
    }

    /**
     * 处理专辑滑动动作
     */
    /**
     * 容器缓存态（对应 iOS entityContainer.playables.hasCachedItems / isCachedCompletely）：
     * 直接由本页歌曲列表推导，与 iOS 取 container.playables 同口径
     */
    val hasCachedSongs: StateFlow<Boolean> = filteredSongs
        .map { list -> list.any { it.isCached } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isFullyCached: StateFlow<Boolean> = filteredSongs
        .map { list -> list.isNotEmpty() && list.all { it.isCached } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * 顶栏 More 菜单动作（整个艺术家）- 对应 iOS ArtistDetailVC.swift:98
     * `optionsButton.menu = EntityPreviewActionBuilder(container: artist).createMenuActions()`
     */
    fun handleArtistAction(action: SwipeActionType) {
        val current = artist.value ?: return
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(current.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling artist action", e)
            }
        }
    }

    fun handleSwipeAction(album: Album, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(album.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e("ArtistDetailVM", "Error handling swipe action", e)
            }
        }
    }
}
