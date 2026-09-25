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

package com.amperfy.ui.util

import android.util.Log
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 滑动动作结果协调器
 *
 * 对应 iOS: BasicTableViewController.createSwipeAction 中 removeFromCache 的
 * UIAlertController 确认流程和 addToPlaylist 的 PlaylistSelectorVC 弹出流程
 *
 * 各 ViewModel 持有一个实例，将 handleSwipeAction 返回的 SwipeActionResult
 * 交给 onResult 处理；Screen 收集状态流来显示确认对话框 / 播放列表选择器。
 */
class SwipeActionCoordinator(
    private val appDelegate: AppDelegate,
    private val scope: CoroutineScope
) {
    private val _pendingDeleteCacheSongs = MutableStateFlow<List<Song>?>(null)

    /** 待确认删除缓存的歌曲，非空时显示确认对话框 */
    val pendingDeleteCacheSongs: StateFlow<List<Song>?> = _pendingDeleteCacheSongs.asStateFlow()

    private val _pendingDeleteCacheEpisodes = MutableStateFlow<List<PodcastEpisode>?>(null)

    /** 待确认删除缓存的播客单集（Batch 4），非空时显示确认对话框 */
    val pendingDeleteCacheEpisodes: StateFlow<List<PodcastEpisode>?> = _pendingDeleteCacheEpisodes.asStateFlow()

    private val _pendingPlaylistSongIds = MutableStateFlow<List<String>?>(null)

    /** 待添加到播放列表的歌曲 ID，非空时显示播放列表选择器（Phase 2 接入 PlaylistSelectorDialog） */
    val pendingPlaylistSongIds: StateFlow<List<String>?> = _pendingPlaylistSongIds.asStateFlow()

    /**
     * 处理滑动动作执行结果
     */
    fun onResult(result: SwipeActionResult) {
        when (result) {
            is SwipeActionResult.Success -> { /* 无需后续处理 */ }
            is SwipeActionResult.NeedsConfirmation -> {
                _pendingDeleteCacheSongs.value = result.songs
            }
            is SwipeActionResult.NeedsEpisodeCacheConfirmation -> {
                _pendingDeleteCacheEpisodes.value = result.episodes
            }
            is SwipeActionResult.ShowPlaylistSelector -> {
                _pendingPlaylistSongIds.value = result.songIds
            }
            is SwipeActionResult.NotSupported -> {
                Log.d(TAG, "Swipe action not supported")
            }
        }
    }

    /**
     * 用户确认删除缓存
     *
     * 对应 iOS: alert Delete action -> removeFinishedDownload + library.deleteCache
     */
    fun confirmDeleteCache() {
        val songs = _pendingDeleteCacheSongs.value ?: return
        _pendingDeleteCacheSongs.value = null
        scope.launch {
            appDelegate.downloader.deleteSongsCache(songs)
                .onFailure { Log.e(TAG, "Delete cache failed", it) }
        }
    }

    fun dismissDeleteCache() {
        _pendingDeleteCacheSongs.value = null
    }

    /**
     * 用户确认删除播客单集缓存（Batch 4，镜像 [confirmDeleteCache]）
     * 对应 iOS: 同一个 createDeleteCacheAction 的 Delete 分支（对象为 AbstractPlayable）
     */
    fun confirmDeleteEpisodeCache() {
        val episodes = _pendingDeleteCacheEpisodes.value ?: return
        _pendingDeleteCacheEpisodes.value = null
        scope.launch {
            episodes.forEach { episode ->
                appDelegate.downloader.deleteEpisodeCache(episode)
                    .onFailure { Log.e(TAG, "Delete episode cache failed", it) }
            }
        }
    }

    fun dismissDeleteEpisodeCache() {
        _pendingDeleteCacheEpisodes.value = null
    }

    fun dismissPlaylistSelector() {
        _pendingPlaylistSongIds.value = null
    }

    /**
     * 主动请求弹出播放列表选择器（用于 More 菜单 "Add to Playlist" 等非滑动入口）
     */
    fun requestPlaylistSelector(songIds: List<String>) {
        _pendingPlaylistSongIds.value = songIds
    }

    companion object {
        private const val TAG = "SwipeActionCoordinator"
    }
}
