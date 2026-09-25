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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// Import queue extension functions
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.appendContextQueue

/**
 * 重新导出 DownloadProgress 类型供 UI 层使用
 */
typealias DownloadProgress = DownloadManager.DownloadProgress

/**
 * AlbumDetailViewModel - ViewModel for Album Detail screen
 *
 * Ported from iOS: Amperfy/Screens/ViewController/AlbumDetailVC.swift
 *
 * This ViewModel manages the state and business logic for displaying album details,
 * including fetching songs, handling play/shuffle operations, and syncing from server.
 *
 * iOS Equivalent Properties:
 * - album: Album! → _albumContainer: AlbumContainer?
 * - fetchedResultsController → appDelegate.library.getSongsByAlbum()
 * - detailOperationsView → Handled in Compose UI
 */
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val appDelegate: AppDelegate,  // ✅ 唯一依赖
    savedStateHandle: SavedStateHandle
) : ViewModel() {  // ✅ 不再继承 BasePlayableViewModel

    private val albumId: String = checkNotNull(savedStateHandle["albumId"])

    /**
     * 来源页面标识（用于显示正确的返回标题）
     * "albums" -> "Albums"
     * "artist" -> "Artist"
     */
    val fromPage: String = savedStateHandle["from"] ?: "albums"

    /**
     * Album container with songs
     * Equivalent to iOS: var album: Album!
     */
    private val _albumContainer = MutableStateFlow<AlbumContainer?>(null)
    val albumContainer: StateFlow<AlbumContainer?> = _albumContainer.asStateFlow()

    /**
     * Raw album entity - 从数据库持续观察，响应式更新
     * 当album的favorite/rating等属性在数据库中变化时，UI会自动更新
     * Equivalent to iOS: NSFetchedResultsController
     */
    val album: StateFlow<Album?> = appDelegate.library.observeAlbumById(albumId)
        .onEach { album ->
            android.util.Log.d("RatingMenu", "[AlbumDetailViewModel] album Flow emitted: id=${album?.id}, rating=${album?.rating}")
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    /**
     * Songs fetched from database
     * Equivalent to iOS: fetchedResultsController.fetchedObjects
     */
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    /**
     * Search filter text
     * Equivalent to iOS: searchController.searchBar.text
     */
    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    /**
     * Only show cached songs filter
     * Equivalent to iOS: searchController.searchBar.selectedScopeButtonIndex == 1
     */
    private val _onlyCachedSongs = MutableStateFlow(false)
    val onlyCachedSongs: StateFlow<Boolean> = _onlyCachedSongs.asStateFlow()

    /**
     * Initial loading state
     */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Refreshing/syncing state
     * Equivalent to iOS: viewIsAppearing async fetch operation
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Player state
     * Equivalent to iOS: appDelegate.player
     */
    val isPlaying = appDelegate.player.isPlaying
    val currentPlayingSong = appDelegate.player.currentSong

    /**
     * Download progress map - 暴露给UI层显示下载进度
     * 对应iOS: downloadProgress in PlayableTableCell
     */
    val downloadProgressMap = appDelegate.downloader.downloadProgressMap

    init {
        loadAlbumDetails()
    }

    /**
     * Load album details from local database
     * Equivalent to iOS: viewDidLoad() -> fetchedResultsController.fetch()
     */
    private fun loadAlbumDetails() {
        viewModelScope.launch {
            _isLoading.value = true

            // Load and observe album songs
            // Equivalent to iOS: AlbumSongsFetchedResultsController
            // 使用 distinctUntilChanged 避免不必要的重复更新
            // 注意：album通过observeAlbumById自动观察，无需手动加载
            appDelegate.library.getSongsByAlbum(albumId)
                .collect { songList ->
                    val sortedSongs = songList.sortedBy { it.track ?: 0 }
                    _songs.value = sortedSongs

                    // Update album container with songs
                    // 使用当前album的值（从StateFlow获取）
                    album.value?.let {
                        _albumContainer.value = it.toContainer(sortedSongs)
                    }

                    _isLoading.value = false
                }
        }
    }

    /**
     * Fetch/sync album details from server
     * Equivalent to iOS: viewIsAppearing() -> album.fetch(storage:librarySyncer:playableDownloadManager:)
     *
     * iOS版本每次viewIsAppearing都会调用此方法从服务器同步详情
     * Android版本通过LaunchedEffect在Composable首次显示时调用
     */
    fun fetch() {
        viewModelScope.launch {
            _isRefreshing.value = true

            try {
                appDelegate.library.syncAlbumDetails(albumId = albumId)
                // Equivalent to iOS: detailOperationsView?.refresh()
                // Songs will automatically update via Flow
            } catch (e: Exception) {
                // Equivalent to iOS: appDelegate.eventLogger.report(topic: "Album Sync", error: error)
                android.util.Log.e("AlbumDetailViewModel", "Album sync error", e)
            }

            _isRefreshing.value = false
        }
    }

    /**
     * Sync album from server (legacy method name)
     * Calls fetch() internally
     */
    fun syncAlbumFromServer() {
        fetch()
    }

    /**
     * Search songs in the album
     * Equivalent to iOS: updateSearchResults(for: searchController)
     */
    fun search(searchText: String, onlyCachedSongs: Boolean = false) {
        _searchText.value = searchText
        _onlyCachedSongs.value = onlyCachedSongs

        // In iOS, this calls: fetchedResultsController.search(searchText:onlyCachedSongs:)
        // For Android, we filter in memory (or could use Room query)
        viewModelScope.launch {
            val allSongs = appDelegate.library.getSongsByAlbum(albumId).first()
            val filtered = allSongs
                .filter { song ->
                    // Search filter
                    if (searchText.isNotEmpty()) {
                        song.title.contains(searchText, ignoreCase = true) ||
                        song.artist.contains(searchText, ignoreCase = true)
                    } else {
                        true
                    }
                }
                .filter { song ->
                    // Cached filter
                    if (onlyCachedSongs) {
                        song.isDownloaded
                    } else {
                        true
                    }
                }
                .sortedBy { it.track ?: 0 }

            _songs.value = filtered
        }
    }

    /**
     * Get songs for play context, optionally filtering cached songs only
     * Equivalent to iOS: fetchedResultsController.getContextSongs(onlyCachedSongs:)
     */
    private fun getContextSongs(onlyCachedSongs: Boolean = false): List<Song> {
        val songs = _songs.value
        return if (onlyCachedSongs) {
            songs.filter { it.isDownloaded }
        } else {
            songs
        }
    }

    // .toPlayable() method removed - now inherited from BasePlayableViewModel

    /**
     * Convert song at specific index to PlayContext
     * Equivalent to iOS: convertIndexPathToPlayContext(songIndexPath:)
     */
    fun convertSongToPlayContext(song: Song): PlayContext? {
        val songs = getContextSongs(onlyCachedSongs = false)
        val playContextIndex = songs.indexOfFirst { it.id == song.id }
        if (playContextIndex == -1) return null

        val album = album.value ?: return null
        return PlayContext(
            index = playContextIndex,
            name = album.name,
            playables = songs.map { it.toPlayable() },
            type = PlayerMode.MUSIC
        )
    }

    /**
     * Play a specific song with album context
     * Equivalent to iOS: cell.display(playable:playContextCb:rootView:isDislayAlbumTrackNumberStyle:)
     */
    fun playSong(song: Song) {
        viewModelScope.launch {
            val playContext = convertSongToPlayContext(song)
            if (playContext != null) {
                appDelegate.player.playPlaylist(
                    songs = playContext.playables,
                    startIndex = playContext.index,
                    contextType = PlayContextType.ALBUM,
                    contextId = album.value?.id,
                    contextName = playContext.name  // 传递专辑名称
                )
            } else {
                // Fallback: play single song
                appDelegate.player.playSong(song.toPlayable())
            }
        }
    }

    /**
     * Play all songs in album from beginning
     * Equivalent to iOS: LibraryElementDetailTableHeaderView.play(isShuffled: false)
     */
    fun playAllSongs() {
        viewModelScope.launch {
            val album = album.value ?: return@launch
            val songs = getContextSongs(onlyCachedSongs = false)

            if (songs.isNotEmpty()) {
                // Equivalent to iOS: PlayContext(containable: album, playables: songs)
                val playContext = PlayContext(
                    index = 0,
                    name = album.name,
                    playables = songs.map { it.toPlayable() },
                    type = PlayerMode.MUSIC
                )
                appDelegate.player.playPlaylist(
                    songs = playContext.playables,
                    startIndex = playContext.index,
                    contextType = PlayContextType.ALBUM,
                    contextId = album.id,
                    contextName = playContext.name  // 传递专辑名称
                )
            }
        }
    }

    /**
     * Shuffle and play all songs in album
     * Equivalent to iOS: LibraryElementDetailTableHeaderView.play(isShuffled: true)
     */
    fun shuffleAllSongs() {
        viewModelScope.launch {
            val album = album.value ?: return@launch
            val songs = getContextSongs(onlyCachedSongs = false)

            if (songs.isNotEmpty()) {
                // Create play context and shuffle
                // Equivalent to iOS: PlayContext(...).getWithShuffledIndex()
                val playContext = PlayContext(
                    index = 0,
                    name = album.name,
                    playables = songs.map { it.toPlayable() },
                    type = PlayerMode.MUSIC
                ).getWithShuffledIndex()

                appDelegate.player.playPlaylist(
                    songs = playContext.playables.shuffled(),
                    startIndex = 0,  // Start from beginning after shuffling
                    contextType = PlayContextType.ALBUM,
                    contextId = album.id,
                    contextName = playContext.name  // 传递专辑名称
                )
            }
        }
    }

    /**
     * Toggle play/pause
     */
    fun playPause() {
        appDelegate.player.playPause()
    }

    /**
     * Get album info string
     * Equivalent to iOS: PlayShuffleInfoConfiguration.infoCB
     */
    fun getAlbumInfo(): String {
        val songCount = _songs.value.size
        return "$songCount Song${if (songCount == 1) "" else "s"}"
    }

    /**
     * Get full cover art URL for an album
     * Equivalent to iOS: album.artwork.url
     */
    fun getCoverArtUrl(album: Album): String? {
        val coverArtId = album.coverArt ?: return null
        val credentials = appDelegate.credentials.getCredentials() ?: return null

        return appDelegate.mediaUrls.getCoverArtUrl(
            coverArtId = coverArtId,
            username = credentials.username,
            password = credentials.password,
            baseUrl = credentials.serverUrl
        )
    }

    /**
     * Toggle favorite status for a song
     * Equivalent to iOS: EntityPreviewActionBuilder.createFavoriteMenu
     */
    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            try {
                appDelegate.library.toggleSongFavorite(song.id)
            } catch (e: Exception) {
                android.util.Log.e("AlbumDetailViewModel", "Toggle favorite error", e)
            }
        }
    }

    /**
     * Toggle favorite status for the album
     * Equivalent to iOS: EntityPreviewActionBuilder.createFavoriteMenu for Album
     *
     * 响应式更新：当服务器返回成功后，Repository会更新数据库，
     * observeAlbumById会自动发出新的Album对象，UI会自动刷新菜单文字
     */
    fun toggleAlbumFavorite() {
        viewModelScope.launch {
            try {
                album.value?.let { album ->
                    appDelegate.library.toggleAlbumFavorite(album.id)
                    // 不需要手动更新UI，observeAlbumById会自动监听数据库变化
                }
            } catch (e: Exception) {
                android.util.Log.e("AlbumDetailViewModel", "Toggle album favorite error", e)
            }
        }
    }

    /**
     * Set album rating
     * Equivalent to iOS: EntityPreviewVC.setRating(rating:) for Album
     *
     * 响应式更新：当服务器返回成功后，Repository会更新数据库，
     * observeAlbumById会自动发出新的Album对象，UI会自动刷新Rating显示
     */
    fun setAlbumRating(rating: Int) {
        android.util.Log.d("RatingMenu", "[AlbumDetailViewModel] setAlbumRating called with rating=$rating, current album.value?.rating=${album.value?.rating}")
        viewModelScope.launch {
            try {
                album.value?.let { album ->
                    android.util.Log.d("RatingMenu", "[AlbumDetailViewModel] Calling appDelegate.library.updateAlbumRating(${album.id}, $rating)")
                    appDelegate.library.updateAlbumRating(album.id, rating)
                    android.util.Log.d("RatingMenu", "[AlbumDetailViewModel] updateAlbumRating completed, album.value?.rating now = ${this@AlbumDetailViewModel.album.value?.rating}")
                    // 不需要手动更新UI，observeAlbumById会自动监听数据库变化
                }
            } catch (e: Exception) {
                android.util.Log.e("AlbumDetailViewModel", "Set album rating error", e)
            }
        }
    }

    /**
     * Set song rating
     * Equivalent to iOS: EntityPreviewVC.setRating(rating:) for Song
     */
    fun setSongRating(song: Song, rating: Int) {
        viewModelScope.launch {
            try {
                appDelegate.library.updateSongRating(song.id, rating)
            } catch (e: Exception) {
                android.util.Log.e("AlbumDetailViewModel", "Set song rating error", e)
            }
        }
    }

    /**
     * Add song to play queue (legacy method, uses appendUserQueue internally)
     * Equivalent to iOS: EntityPreviewActionBuilder.createMusicQueueAction
     */
    fun addToQueue(song: Song) {
        viewModelScope.launch {
            val playable = song.toPlayable()
            appDelegate.player.addToQueue(playable)
        }
    }

    // addToQueueNext(song) and addToQueueLater(song) methods removed - now inherited from BasePlayableViewModel

    /**
     * Add all album songs to User Queue (Play Next)
     * 对应iOS: Album级别的 insertUserQueue
     */
    fun addAllToQueueNext() {
        viewModelScope.launch {
            val songs = getContextSongs(onlyCachedSongs = false)
            if (songs.isNotEmpty()) {
                val playables = songs.map { it.toPlayable() }
                android.util.Log.d("AlbumDetailViewModel", "addAllToQueueNext: ${songs.size} songs")
                appDelegate.player.insertUserQueue(playables)
            }
        }
    }

    /**
     * Add all album songs to User Queue (Play Later)
     * 对应iOS: Album级别的 appendUserQueue
     */
    fun addAllToQueueLater() {
        viewModelScope.launch {
            val songs = getContextSongs(onlyCachedSongs = false)
            if (songs.isNotEmpty()) {
                val playables = songs.map { it.toPlayable() }
                android.util.Log.d("AlbumDetailViewModel", "addAllToQueueLater: ${songs.size} songs")
                appDelegate.player.appendUserQueue(playables)
            }
        }
    }

    // insertContextQueue(song) and appendContextQueue(song) methods removed - now inherited from BasePlayableViewModel

    /**
     * Insert all songs to Context Queue
     * iOS: appDelegate.player.insertContextQueue(playables: playables)
     */
    fun insertAllContextQueue() {
        viewModelScope.launch {
            val songs = getContextSongs(onlyCachedSongs = false)
            if (songs.isNotEmpty()) {
                val playables = songs.map { it.toPlayable() }
                android.util.Log.d("AlbumDetailViewModel", "insertAllContextQueue: ${songs.size} songs")
                appDelegate.player.insertContextQueue(playables)
            }
        }
    }

    /**
     * Append all songs to Context Queue
     * iOS: appDelegate.player.appendContextQueue(playables: playables)
     */
    fun appendAllContextQueue() {
        viewModelScope.launch {
            val songs = getContextSongs(onlyCachedSongs = false)
            if (songs.isNotEmpty()) {
                val playables = songs.map { it.toPlayable() }
                android.util.Log.d("AlbumDetailViewModel", "appendAllContextQueue: ${songs.size} songs")
                appDelegate.player.appendContextQueue(playables)
            }
        }
    }

    /**
     * Download a song
     * Equivalent to iOS: EntityPreviewActionBuilder.createDownloadAction
     */
    fun downloadSong(song: Song) {
        viewModelScope.launch {
            appDelegate.downloader.downloadSong(song)
        }
    }

    /**
     * Download all songs in the album
     * Equivalent to iOS: EntityPreviewActionBuilder.createDownloadAction for Album
     */
    fun downloadAllSongs() {
        viewModelScope.launch {
            val songs = _songs.value
            if (songs.isNotEmpty()) {
                appDelegate.downloader.downloadSongs(songs)
            }
        }
    }

    /**
     * Add song to playlist
     * Equivalent to iOS: EntityPreviewActionBuilder.createAddToPlaylistAction
     */
    fun addToPlaylist(song: Song) {
        swipeCoordinator.requestPlaylistSelector(listOf(song.id))
    }

    /**
     * Add all album songs to playlist (album header More menu)
     * Equivalent to iOS: EntityPreviewActionBuilder.createAddToPlaylistAction for Album
     */
    fun addAllToPlaylist() {
        val songs = _songs.value
        if (songs.isNotEmpty()) {
            swipeCoordinator.requestPlaylistSelector(songs.map { it.id })
        }
    }

    /**
     * Delete cached song
     * Equivalent to iOS: EntityPreviewActionBuilder.createDeleteCacheAction
     */
    fun deleteCache(song: Song) {
        viewModelScope.launch {
            appDelegate.downloader.deleteSongCache(song)
        }
    }

    /**
     * Delete cache for all songs in the album
     * Equivalent to iOS: EntityPreviewActionBuilder.createDeleteCacheAction for Album
     */
    fun deleteAllCache() {
        viewModelScope.launch {
            val songs = _songs.value.filter { it.isCached }
            if (songs.isNotEmpty()) {
                appDelegate.downloader.deleteSongsCache(songs)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Queue Operations - Using extension functions from SongQueueExtensions.kt
    // Replaces duplicate methods previously copied from BasePlayableViewModel
    // ═══════════════════════════════════════════════════════════

    /**
     * Convert Song to Playable with credentials
     * Helper extension function for this ViewModel
     */
    private fun Song.toPlayable() = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)

    /**
     * Add song to user queue (next to play after current song)
     * iOS: EntityPreviewActionBuilder.addToQueueNext()
     */
    fun addToQueueNext(song: Song) {
        song.addToQueueNext(appDelegate, viewModelScope)
    }

    /**
     * Add song to end of user queue
     * iOS: EntityPreviewActionBuilder.addToQueueLater()
     */
    fun addToQueueLater(song: Song) {
        song.addToQueueLater(appDelegate, viewModelScope)
    }

    /**
     * Insert song at front of context queue
     * iOS: insertContextQueue()
     */
    fun insertContextQueue(song: Song) {
        song.insertContextQueue(appDelegate, viewModelScope)
    }

    /**
     * Append song to end of context queue
     * iOS: appendContextQueue()
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
     * 容器缓存态（对应 iOS entityContainer.playables.hasCachedItems / isCachedCompletely）：
     * 直接由本页歌曲列表推导，与 iOS 取 container.playables 同口径
     */
    val hasCachedSongs: StateFlow<Boolean> = songs
        .map { list -> list.any { it.isCached } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isFullyCached: StateFlow<Boolean> = songs
        .map { list -> list.isNotEmpty() && list.all { it.isCached } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * 顶栏 More 菜单动作（整张专辑）- 对应 iOS AlbumDetailVC.swift:124
     * `optionsButton.menu = EntityPreviewActionBuilder(container: album).createMenuActions()`
     * ——与列表行长按同一构建器、同一 Album.handleSwipeAction 执行路径
     */
    fun handleAlbumAction(action: SwipeActionType) {
        val current = album.value ?: return
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(current.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e("AlbumDetailVM", "Error handling album action", e)
            }
        }
    }

    /**
     * 处理歌曲滑动动作 - 对应iOS: BasicTableViewController.createSwipeAction
     *
     * PLAY / PLAY_SHUFFLED 带上整张专辑的播放上下文（见 [buildSongPlayContext]）。
     */
    fun handleSwipeAction(song: Song, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(
                    song.handleSwipeAction(action, appDelegate, buildSongPlayContext(song))
                )
            } catch (e: Exception) {
                android.util.Log.e("AlbumDetailVM", "Error handling swipe action", e)
            }
        }
    }

    /**
     * 构造歌曲行滑动/菜单播放上下文——对应 iOS AlbumDetailVC.swift:173-180
     * `PlayContext(containable: album, index: playContextIndex, playables: songs)`：
     * 上下文 = 本专辑全部歌曲（离线模式只含已缓存曲，iOS
     * `getContextSongs(onlyCachedSongs: isOfflineMode)`），起始索引 = 被滑动曲的位置。
     * 被滑动曲不在该列表中（离线且未缓存）时返回 null，退化为单曲上下文。
     */
    private fun buildSongPlayContext(song: Song): SwipePlayContext? {
        val context = getContextSongs(
            onlyCachedSongs = appDelegate.settings.isOfflineMode.value
        )
        val index = context.indexOfFirst { it.id == song.id }
        if (index < 0) return null
        return SwipePlayContext(
            contextType = PlayContextType.ALBUM,
            contextId = albumId,
            contextName = album.value?.name ?: "",
            songs = context,
            startIndex = index
        )
    }
}
