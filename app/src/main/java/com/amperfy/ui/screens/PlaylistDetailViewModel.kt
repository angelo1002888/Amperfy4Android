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
import com.amperfy.data.model.*
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.appendContextQueue

/**
 * PlaylistDetailViewModel - 播放列表详情页 ViewModel
 *
 * 对应 iOS: PlaylistDetailVC.swift
 *
 * 负责：观察播放列表元数据与有序歌曲、从服务器同步歌曲、播放/随机播放、
 * 编辑（重排/删除歌曲，自动上传服务器）、重命名、删除播放列表。
 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    /** 播放列表元数据，响应式（名称等变化自动更新） */
    val playlist: StateFlow<Playlist?> = appDelegate.playlists.observePlaylistById(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 有序歌曲列表（列表顺序即播放列表顺序） */
    val songs: StateFlow<List<Song>> = appDelegate.playlists.getPlaylistSongs(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val isPlaying = appDelegate.player.isPlaying
    val currentPlayingSong = appDelegate.player.currentSong
    val downloadProgressMap = appDelegate.downloader.downloadProgressMap

    /**
     * 从服务器同步歌曲详情
     * 对应 iOS: PlaylistDetailVC.viewIsAppearing -> playlist.fetch
     */
    fun fetch() {
        viewModelScope.launch {
            _isRefreshing.value = true
            appDelegate.playlists.syncPlaylistDetails(playlistId)
                .onFailure { android.util.Log.e(TAG, "Sync playlist details failed", it) }
            _isRefreshing.value = false
        }
    }

    /**
     * 播放上下文歌曲：离线模式只含已缓存歌曲
     * 对应 iOS: playlist.getContextSongs(onlyCachedSongs: isOfflineMode)（PlaylistDetailVC:150-153）
     */
    private fun contextSongs(): List<Song> {
        val all = songs.value
        return if (appDelegate.settings.isOfflineMode.value) all.filter { it.isDownloaded } else all
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            val context = contextSongs()
            val index = context.indexOfFirst { it.id == song.id }
            // 离线且点击的歌曲未缓存：不播放（该歌不在播放上下文中）
            if (index < 0) return@launch
            appDelegate.player.playPlaylist(
                songs = context.map { it.toPlayable() },
                startIndex = index,
                contextType = PlayContextType.PLAYLIST,
                contextId = playlistId,
                contextName = playlist.value?.name ?: ""
            )
            appDelegate.playlists.updatePlaylistLastPlayed(playlistId)
        }
    }

    fun playAll(shuffled: Boolean = false) {
        viewModelScope.launch {
            val playables = contextSongs().map { it.toPlayable() }
            if (playables.isEmpty()) return@launch
            appDelegate.player.playPlaylist(
                songs = if (shuffled) playables.shuffled() else playables,
                startIndex = 0,
                contextType = PlayContextType.PLAYLIST,
                contextId = playlistId,
                contextName = playlist.value?.name ?: ""
            )
            appDelegate.playlists.updatePlaylistLastPlayed(playlistId)
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            appDelegate.library.toggleSongFavorite(song.id)
        }
    }

    fun addToQueue(song: Song) {
        viewModelScope.launch {
            appDelegate.player.addToQueue(song.toPlayable())
        }
    }

    /**
     * Header 信息：详情未同步（songs 为空）时回退到播放列表元数据的 songCount/duration
     * 对应 iOS: Playlist.info 使用实体自身统计（Playlist.swift:478-497）
     */
    fun getPlaylistInfo(): String {
        val loaded = songs.value
        val count = if (loaded.isNotEmpty()) loaded.size else playlist.value?.songCount ?: 0
        val totalSeconds = if (loaded.isNotEmpty()) loaded.sumOf { it.duration } else playlist.value?.duration ?: 0
        return "$count Song${if (count != 1) "s" else ""} • ${formatTotalDuration(totalSeconds)}"
    }

    /**
     * 时长格式化：不足 1 小时显示 "Xm"，否则显示 "Xh Xm"（超 24 小时累计小时数，如 "30h 5m"）。
     * 对应 iOS: asDurationShortString（Utilities.swift:118-123，仅 hour+minute 无「天」）
     */
    private fun formatTotalDuration(totalSeconds: Int): String {
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
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

    private fun Song.toPlayable() = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)

    // ─── 队列操作（复用扩展函数） ───
    fun addToQueueNext(song: Song) = song.addToQueueNext(appDelegate, viewModelScope)
    fun addToQueueLater(song: Song) = song.addToQueueLater(appDelegate, viewModelScope)
    fun insertContextQueue(song: Song) = song.insertContextQueue(appDelegate, viewModelScope)
    fun appendContextQueue(song: Song) = song.appendContextQueue(appDelegate, viewModelScope)

    fun setSongRating(song: Song, rating: Int) {
        viewModelScope.launch { appDelegate.library.updateSongRating(song.id, rating) }
    }

    fun downloadSong(song: Song) {
        viewModelScope.launch { appDelegate.downloader.downloadSong(song) }
    }

    fun deleteCache(song: Song) {
        viewModelScope.launch { appDelegate.downloader.deleteSongCache(song) }
    }

    // ─── 滑动动作 ───
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val isOfflineMode = appDelegate.settings.isOfflineMode

    /**
     * 歌曲行滑动 / 长按菜单动作。
     *
     * PLAY / PLAY_SHUFFLED 带上整个播放列表的播放上下文（见 [buildSongPlayContext]），
     * 并在动作执行后刷新本列表的 lastPlayed——对应 iOS
     * `PlayContext(containable:index:playables:)` 构造时即调 `containable.playedViaContext()`
     * （PlayerFacade.swift:128-133 → Playlist.swift:554 写 lastPlayedDate），
     * 与本页行点击 [playSong]、容器行 `Playlist.handleSwipeAction` 同源。
     * 上下文为 null 时走单曲退化路径（iOS 对应 `PlayContext(containable: song)`，
     * containable 是歌曲不是列表），故**不**刷 lastPlayed。
     */
    fun handleSwipeAction(song: Song, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                val playContext = buildSongPlayContext(song)
                swipeCoordinator.onResult(
                    song.handleSwipeAction(action, appDelegate, playContext)
                )
                val isPlayAction =
                    action == SwipeActionType.PLAY || action == SwipeActionType.PLAY_SHUFFLED
                if (isPlayAction && playContext != null) {
                    appDelegate.playlists.updatePlaylistLastPlayed(playlistId)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling swipe action", e)
            }
        }
    }

    /**
     * 构造歌曲行滑动/菜单播放上下文——对应 iOS PlaylistDetailVC.swift:249-254
     * `PlayContext(containable: playlist, index: songIndexPath.row, playables: songs)`：
     * 上下文 = [contextSongs]（离线模式只含已缓存曲），起始索引 = 被滑动曲的位置。
     * 被滑动曲不在该列表中（离线且未缓存）时返回 null，退化为单曲上下文。
     */
    private fun buildSongPlayContext(song: Song): SwipePlayContext? {
        val context = contextSongs()
        val index = context.indexOfFirst { it.id == song.id }
        if (index < 0) return null
        return SwipePlayContext(
            contextType = PlayContextType.PLAYLIST,
            contextId = playlistId,
            contextName = playlist.value?.name ?: "",
            songs = context,
            startIndex = index
        )
    }

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
     * 处理整个播放列表的上下文动作（More 菜单），复用与列表页相同的 Playlist 扩展处理。
     * 对应 iOS: PlaylistDetailVC.optionsButton -> EntityPreviewActionBuilder(container: playlist).createMenu()
     */
    fun handlePlaylistAction(action: SwipeActionType) {
        val pl = playlist.value ?: return
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(pl.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling playlist action", e)
            }
        }
    }

    companion object {
        private const val TAG = "PlaylistDetailVM"
    }
}
