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
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Genre
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipePlayContext
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.appendContextQueue
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * GenreDetail UI 状态
 */
data class GenreDetailUiState(
    val searchText: String = "",
    /** false=All / true=Cached（iOS scopeButtonTitles: ["All", "Cached"]） */
    val isCachedScope: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * GenreDetail ViewModel（Phase 6.1）
 * 对应 iOS: GenreDetailVC + Genre{Artists,Albums,Songs}FetchedResultsController
 *
 * 三段数据均来自本地 genre 字段匹配（iOS 经 Core Data genre 关系，语义一致）；
 * fetch() 对应 iOS viewIsAppearing → sync(genre:)：对流派已知专辑逐个专辑同步扇出
 */
@HiltViewModel
class GenreDetailViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** 流派名即标识（Subsonic 无流派 id） */
    val genreName: String = savedStateHandle["name"] ?: ""

    private val _uiState = MutableStateFlow(GenreDetailUiState())
    val uiState: StateFlow<GenreDetailUiState> = _uiState.asStateFlow()

    val genre: StateFlow<Genre?> = appDelegate.library.observeGenreByName(genreName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val filteredArtists: StateFlow<List<Artist>> = combine(
        appDelegate.library.getGenreArtists(genreName), _uiState
    ) { artists, state ->
        var result = artists
        if (state.searchText.isNotEmpty()) {
            result = result.filter { it.name.contains(state.searchText, ignoreCase = true) }
        }
        result
    }
        // 过滤（含上游实体→领域模型逐项映射）移出主线程，避免订阅瞬间在转场帧执行
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredAlbums: StateFlow<List<Album>> = combine(
        appDelegate.library.getGenreAlbums(genreName), _uiState
    ) { albums, state ->
        var result = albums
        if (state.isCachedScope) result = result.filter { it.isCached }
        if (state.searchText.isNotEmpty()) {
            result = result.filter { it.name.contains(state.searchText, ignoreCase = true) }
        }
        result
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredSongs: StateFlow<List<Song>> = combine(
        appDelegate.library.getGenreSongs(genreName), _uiState
    ) { songs, state ->
        var result = songs
        if (state.isCachedScope) result = result.filter { it.isCached }
        if (state.searchText.isNotEmpty()) {
            result = result.filter { it.title.contains(state.searchText, ignoreCase = true) }
        }
        result
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 播放状态（行播放指示器用）
    val currentSong = appDelegate.player.currentSong
    val isPlaying = appDelegate.player.isPlaying

    /** 播放列表选择器协调（More 菜单 Add to Playlist，与 SongsViewModel 同模式） */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /**
     * 同步流派内容 - 对应 iOS viewIsAppearing → genre.fetch → sync(genre:)
     * （对已知专辑逐个 getAlbum 扇出，无 by-genre 端点）
     */
    fun fetch() {
        viewModelScope.launch {
            if (appDelegate.settings.isOfflineMode.value) return@launch
            _uiState.update { it.copy(isLoading = true) }
            try {
                appDelegate.library.syncGenreDetails(genreName)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Genre Sync error", e)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onSearchTextChanged(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    fun setCachedScope(cached: Boolean) {
        _uiState.update { it.copy(isCachedScope = cached) }
    }

    /**
     * Play - 全部歌曲顺序播放（离线只播缓存，对应 iOS playShuffleInfoConfig.playContextCb）
     */
    fun playAll() {
        playSongs(shuffled = false)
    }

    /** Shuffle - 全部歌曲乱序播放 */
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
                    contextType = PlayContextType.SONGS,
                    contextId = genreName,
                    contextName = genreName
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play genre", e)
            }
        }
    }

    /** 单曲播放（以流派歌曲列表为上下文） */
    fun playSong(song: Song) {
        viewModelScope.launch {
            try {
                val songs = filteredSongs.value
                val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                appDelegate.player.playPlaylist(
                    songs = songs.map { it.toPlayable() },
                    startIndex = index,
                    contextType = PlayContextType.SONGS,
                    contextId = genreName,
                    contextName = genreName
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
     * 顶栏 More 菜单动作（整个流派）- 对应 iOS GenreDetailVC.swift:110
     * `optionsButton.menu = EntityPreviewActionBuilder(container: genre).createMenuActions()`
     */
    fun handleGenreAction(action: SwipeActionType) {
        val current = genre.value ?: return
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(current.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling genre action", e)
            }
        }
    }

    /**
     * 专辑行长按上下文菜单动作 - 对应 iOS contextMenuConfigurationForRowAt
     * 与滑动动作同源（Album.handleSwipeAction），结果交 swipeCoordinator
     * （删除缓存确认 / 播放列表选择器由 Screen 承接）
     */
    fun handleAlbumSwipeAction(album: Album, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(album.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling album swipe action", e)
            }
        }
    }

    /**
     * 设置专辑评分 - 对应 iOS EntityPreviewVC.setRating(rating:) for Album
     * （长按上下文菜单 Rating 调色板；滑动动作无对应项，故单列一个方法）
     */
    fun setAlbumRating(album: Album, rating: Int) {
        viewModelScope.launch {
            appDelegate.library.updateAlbumRating(album.id, rating)
        }
    }

    /** 滑动动作配置（Screen 侧再经 SwipeDisplaySettings.filter 过滤），与 ArtistsVC 同源 */
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val isOfflineMode = appDelegate.settings.isOfflineMode

    /**
     * 艺术家行滑动/长按菜单动作 - 对应 iOS GenreDetailVC.swift 的 swipeCallback
     * artist 分支（与 ArtistsVC 同一 Artist.handleSwipeAction 路径）
     */
    fun handleArtistSwipeAction(artist: Artist, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(artist.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling artist swipe action", e)
            }
        }
    }

    /**
     * 歌曲行滑动动作 - 对应 iOS GenreDetailVC.swift:197-201 swipeCallback 的 song 分支
     * （与 FavoriteSongsVC / AlbumDetailVC 同一 Song.handleSwipeAction 路径，
     * 结果交 swipeCoordinator，删除缓存确认 / 播放列表选择器由 Screen 承接）
     *
     * PLAY / PLAY_SHUFFLED 带上整个流派的播放上下文（见 [buildSongPlayContext]）。
     */
    fun handleSongSwipeAction(song: Song, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(
                    song.handleSwipeAction(action, appDelegate, buildSongPlayContext(song))
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling song swipe action", e)
            }
        }
    }

    /**
     * 构造歌曲行滑动/菜单播放上下文——对应 iOS GenreDetailVC.swift:242-248
     * `PlayContext(containable: genre, index: playContextIndex, playables: songs)`：
     * 上下文 = 本流派全部歌曲（离线模式只含已缓存曲，iOS
     * `getContextSongs(onlyCachedSongs: isOfflineMode)`），起始索引 = 被滑动曲的位置。
     * 流派以 name 为标识（Subsonic 无流派 id）。
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
            contextType = PlayContextType.GENRE,
            contextId = genreName,
            contextName = genreName,
            songs = context,
            startIndex = index
        )
    }

    /** 设置艺术家评分 - 对应 iOS EntityPreviewActionBuilder.createRatingMenu() for Artist */
    fun setArtistRating(artist: Artist, rating: Int) {
        viewModelScope.launch {
            try {
                appDelegate.library.updateArtistRating(artist.id, rating)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error updating artist rating", e)
            }
        }
    }

    /**
     * 含缓存歌曲 / 全缓存的艺术家 id 集合（对应 iOS
     * entityContainer.playables.hasCachedItems / isCachedCompletely）：
     * 艺术家行长按菜单据此做离线门控与 Download / Delete Cache 显隐，与 ArtistsVC 同源
     */
    val cachedArtistIds: StateFlow<Set<String>> = appDelegate.library.getCachedArtistIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val fullyCachedArtistIds: StateFlow<Set<String>> = appDelegate.library.getFullyCachedArtistIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

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
        private const val TAG = "GenreDetailViewModel"
    }
}
