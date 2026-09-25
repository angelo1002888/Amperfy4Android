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
import com.amperfy.data.local.PodcastsShowType
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Podcasts UI 状态
 */
data class PodcastsUiState(
    val searchText: String = "",
    val isCachedScope: Boolean = false,
    val error: String? = null
)

/**
 * Podcasts ViewModel（Phase 6.4）
 * 对应 iOS: PodcastsVC（双数据源：PodcastFetchedResultsController +
 * PodcastEpisodesReleaseDateFetchedResultsController，PodcastsVC.swift:40-47）
 *
 * - showType 来自 settings.podcastsShowSetting（排序菜单切换并持久化，iOS :251-279）
 * - 进入页面即同步 syncNewestPodcastEpisodes（iOS viewIsAppearing → syncFromServer，
 *   仅在线；无下拉刷新，iOS PodcastsVC 无 UIRefreshControl）
 * - 搜索 All/Cached 作用域；Episodes 模式按单集缓存态过滤，Podcasts 模式 Cached 恒空（已知简化）
 */
@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    private val _uiState = MutableStateFlow(PodcastsUiState())
    val uiState: StateFlow<PodcastsUiState> = _uiState.asStateFlow()

    val showType: StateFlow<PodcastsShowType> = appDelegate.settings.podcastsShowSetting

    val isOfflineMode = appDelegate.settings.isOfflineMode
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /** 单集下载进度映射（Batch 4，行内缓存图标/进度环） */
    val downloadProgressMap = appDelegate.downloader.downloadProgressMap

    /** Podcasts 模式：按名称排序（iOS PodcastFetchedResultsController，不分字母段） */
    val filteredPodcasts: StateFlow<List<Podcast>> = combine(
        appDelegate.podcasts.getAllPodcasts(),
        _uiState
    ) { podcasts, state ->
        var result = podcasts
        if (state.searchText.isNotEmpty()) {
            result = result.filter { it.title.contains(state.searchText, ignoreCase = true) }
        }
        // Podcasts 模式的 Cached 作用域仍恒空：容器（播客）级聚合缓存态未接线
        // （Batch 4 只落地单集级缓存，已知简化）
        if (state.isCachedScope) result = emptyList()
        // store 已按 sort_key（拼音分区 + 原文）排序返回（P3 批次 3b 下沉），无须客户端重排；
        // 中文标题从 title.lowercase() 码点序改为拼音分区序，与列表页展示一致
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Episodes 模式：跨播客按发布日期降序（iOS PodcastEpisodesReleaseDateFetchedResultsController） */
    val filteredEpisodes: StateFlow<List<PodcastEpisode>> = combine(
        appDelegate.podcasts.getAllPodcastEpisodes(),
        _uiState
    ) { episodes, state ->
        var result = episodes
        if (state.searchText.isNotEmpty()) {
            result = result.filter {
                it.title.contains(state.searchText, ignoreCase = true) ||
                    it.podcastTitle.contains(state.searchText, ignoreCase = true)
            }
        }
        // Batch 4：Cached 作用域按单集缓存态过滤（对齐 iOS scope 语义；此前无缓存管线时恒空）
        if (state.isCachedScope) result = result.filter { it.isDownloaded }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 播放状态（行播放指示器用）
    val currentSong = appDelegate.player.currentSong
    val isPlaying = appDelegate.player.isPlaying

    init {
        // iOS: viewIsAppearing → syncFromServer()（AutoDownloadLibrarySyncer.syncNewestPodcastEpisodes）
        syncFromServer()
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

    /** 切换显示模式并重新同步（iOS 排序菜单 UIAction：写 settings + syncFromServer） */
    fun setShowType(type: PodcastsShowType) {
        appDelegate.settings.setPodcastsShowSetting(type)
        syncFromServer()
    }

    private fun syncFromServer() {
        viewModelScope.launch {
            if (appDelegate.settings.isOfflineMode.value) return@launch
            appDelegate.podcasts.syncNewestPodcastEpisodes()
                .onFailure { e ->
                    // iOS 错误主题 "Podcasts Newest Episodes Sync"
                    _uiState.update { it.copy(error = "Podcasts Sync failed: ${e.message}") }
                }
        }
    }

    /** 播放单集（单集上下文，iOS PlayContext(containable: episode)） */
    fun playEpisode(episode: PodcastEpisode) {
        if (!episode.isAvailableToUser) return
        viewModelScope.launch {
            try {
                val playable = episode.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                appDelegate.player.play(
                    listOf(playable), PlayContextType.PODCAST, episode.podcastId, episode.podcastTitle
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play episode", e)
            }
        }
    }

    fun handleEpisodeSwipeAction(episode: PodcastEpisode, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(episode.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling swipe action", e)
            }
        }
    }

    // ===== 播客（容器）级动作：对应 iOS EntityPreviewVC configureFor(podcast:) =====
    // 曲目集合 = 该播客全部「可用」单集（iOS entityPlayables 对 podcast 上下文不过滤缓存，
    // 但仍要求单集在服务器可用或已缓存——isAvailableToUser 自 Batch 4 起含 cached 优先）

    private suspend fun availableEpisodes(podcastId: String): List<PodcastEpisode> =
        appDelegate.podcasts.getPodcastEpisodes(podcastId).first()
            .filter { it.isAvailableToUser }

    /** 播客行 Play：从最新一集开始顺序播放该播客全部可用单集 */
    fun playPodcast(podcast: Podcast) {
        viewModelScope.launch {
            try {
                val playables = availableEpisodes(podcast.id).map {
                    it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                }
                if (playables.isEmpty()) return@launch
                appDelegate.player.play(
                    playables, PlayContextType.PODCAST, podcast.id, podcast.title
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play podcast", e)
            }
        }
    }

    /** 播客行 Insert Podcast Queue（Batch 2 起落独立播客队列，对齐 iOS insertPodcastQueue） */
    fun insertPodcastQueue(podcast: Podcast) {
        viewModelScope.launch {
            try {
                val playables = availableEpisodes(podcast.id).map {
                    it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                }
                if (playables.isEmpty()) return@launch
                appDelegate.player.insertPodcastQueue(playables)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to insert podcast queue", e)
            }
        }
    }

    /** 播客行 Append Podcast Queue（同上） */
    fun appendPodcastQueue(podcast: Podcast) {
        viewModelScope.launch {
            try {
                val playables = availableEpisodes(podcast.id).map {
                    it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                }
                if (playables.isEmpty()) return@launch
                appDelegate.player.appendPodcastQueue(playables)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to append podcast queue", e)
            }
        }
    }

    /**
     * 播客行长按菜单动作分派（菜单只会发出 PLAY / INSERT_PODCAST_QUEUE /
     * APPEND_PODCAST_QUEUE 三种，其余一律忽略）
     */
    fun handlePodcastSwipeAction(podcast: Podcast, action: SwipeActionType) {
        when (action) {
            SwipeActionType.PLAY -> playPodcast(podcast)
            SwipeActionType.INSERT_PODCAST_QUEUE -> insertPodcastQueue(podcast)
            SwipeActionType.APPEND_PODCAST_QUEUE -> appendPodcastQueue(podcast)
            else -> Unit
        }
    }

    /** 单集加入播客队列（Batch 2 起落独立播客队列） */
    fun insertPodcastQueue(episode: PodcastEpisode) {
        handleEpisodeSwipeAction(episode, SwipeActionType.INSERT_PODCAST_QUEUE)
    }

    fun appendPodcastQueue(episode: PodcastEpisode) {
        handleEpisodeSwipeAction(episode, SwipeActionType.APPEND_PODCAST_QUEUE)
    }

    /** 服务器删除单集（iOS EntityPreviewVC:687-695；成功后重新 sync 该播客刷新状态） */
    fun deleteEpisodeOnServer(episode: PodcastEpisode) {
        viewModelScope.launch {
            appDelegate.podcasts.deletePodcastEpisodeOnServer(episode.id)
                .onSuccess {
                    appDelegate.podcasts.syncPodcastDetails(episode.podcastId)
                }.onFailure { e ->
                    _uiState.update { it.copy(error = "Delete failed: ${e.message}") }
                }
        }
    }

    companion object {
        private const val TAG = "PodcastsViewModel"
    }
}
