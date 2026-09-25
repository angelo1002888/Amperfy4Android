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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.download.DownloadManager
import com.amperfy.data.model.DownloadItem
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.appendContextQueue
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DownloadsViewModel - 下载管理页 ViewModel（Phase 5.2）
 *
 * 对应 iOS: DownloadsVC.swift（数据源 DownloadsFetchedResultsController / DownloadMO）
 * 单列表：全部持久化下载记录按 creationDate 升序，行 = PlayableTableCell（SongListItem）。
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /**
     * 下载记录列表（持久化，按请求时间升序）
     * 对应 iOS: DownloadMO creationDate 排序列表
     *
     * 专题 15 P1：直接消费领域 [DownloadItem]（DownloadLocalStore 内批量装配歌曲，
     * 原逐条 getSongById 的 N+1 已消除）。
     */
    val downloadItems: StateFlow<List<DownloadItem>> = appDelegate.downloader.downloadItems
        // 批量装配查询移出主线程——记录多时订阅瞬间在主线程执行会造成进入页面转场掉帧
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 下载进度映射（SongListItem 行内进度环显示） */
    val downloadProgressMap: StateFlow<Map<String, DownloadManager.DownloadProgress>> =
        appDelegate.downloader.downloadProgressMap

    val currentPlayingSong = appDelegate.player.currentSong
    val isPlaying = appDelegate.player.isPlaying

    // ===== 菜单操作（对齐 iOS DownloadsVC.swift:53-80 三个全局操作）=====

    /** Clear finished downloads：删除已完成/失败/已取消的记录 */
    fun clearFinishedDownloads() = appDelegate.downloader.clearFinishedDownloads()

    /** Retry failed downloads：失败（含已取消）记录重置后重新入队 */
    fun retryFailedDownloads() = appDelegate.downloader.retryFailedDownloads()

    /** Cancel all downloads：未完成记录标记取消（保留在列表中显示感叹号） */
    fun cancelAllDownloads() = appDelegate.downloader.cancelAllDownloads()

    /**
     * 点击行播放该歌曲
     * 对应 iOS: convertCellViewToPlayContext → PlayContext(containable: playable)（单曲上下文）
     */
    fun playSong(song: Song) {
        viewModelScope.launch {
            appDelegate.player.playSong(
                song.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
            )
        }
    }

    /**
     * 菜单 Shuffle：下载页行的播放上下文与单击一致，即该单曲自身
     * （对应 iOS convertCellViewToPlayContext → PlayContext(containable: playable)），
     * 故乱序播放的对象也是这一首
     */
    fun shuffleSong(song: Song) {
        viewModelScope.launch {
            appDelegate.player.playShuffled(
                songs = listOf(
                    song.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                )
            )
        }
    }

    // ===== SongListItem 菜单回调（与 SongsViewModel 同模式）=====

    fun toggleFavorite(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appDelegate.library.toggleSongFavorite(song.id)
            } catch (e: Exception) {
                Log.e(TAG, "Toggle favorite error", e)
            }
        }
    }

    fun setSongRating(song: Song, rating: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appDelegate.library.updateSongRating(song.id, rating)
            } catch (e: Exception) {
                Log.e(TAG, "Set song rating error", e)
            }
        }
    }

    fun addToQueueNext(song: Song) = song.addToQueueNext(appDelegate, viewModelScope)
    fun addToQueueLater(song: Song) = song.addToQueueLater(appDelegate, viewModelScope)
    fun insertContextQueue(song: Song) = song.insertContextQueue(appDelegate, viewModelScope)
    fun appendContextQueue(song: Song) = song.appendContextQueue(appDelegate, viewModelScope)

    fun downloadSong(song: Song) {
        viewModelScope.launch(Dispatchers.IO) { appDelegate.downloader.downloadSong(song) }
    }

    fun deleteSongCache(song: Song) {
        viewModelScope.launch(Dispatchers.IO) { appDelegate.downloader.deleteSongCache(song) }
    }

    // ===== 播客单集行（Batch 4：下载记录含单集条目） =====

    /**
     * 点击单集行播放（镜像 PodcastsViewModel.playEpisode：单集自身即播放上下文，
     * 对应 iOS convertCellViewToPlayContext → PlayContext(containable: playable)）
     */
    fun playEpisode(episode: PodcastEpisode) {
        viewModelScope.launch {
            try {
                val playable = episode.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                appDelegate.player.play(
                    listOf(playable), PlayContextType.PODCAST, episode.podcastId, episode.podcastTitle
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play episode", e)
            }
        }
    }

    /** 单集行菜单/滑动动作（与播客各页同一条 handleSwipeAction 执行路径） */
    fun handleEpisodeAction(episode: PodcastEpisode, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(episode.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                Log.e(TAG, "Error handling episode action", e)
            }
        }
    }

    /** 服务器删除单集（成功后重新同步该播客刷新状态，同 PodcastsViewModel） */
    fun deleteEpisodeOnServer(episode: PodcastEpisode) {
        viewModelScope.launch {
            appDelegate.podcasts.deletePodcastEpisodeOnServer(episode.id)
                .onSuccess { appDelegate.podcasts.syncPodcastDetails(episode.podcastId) }
                .onFailure { e -> Log.e(TAG, "Delete episode on server failed", e) }
        }
    }

    fun addToPlaylist(song: Song) {
        swipeCoordinator.requestPlaylistSelector(listOf(song.id))
    }

    companion object {
        private const val TAG = "DownloadsViewModel"
    }
}
