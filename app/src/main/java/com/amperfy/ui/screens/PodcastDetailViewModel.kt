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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PodcastDetail UI 状态
 */
data class PodcastDetailUiState(
    val searchText: String = "",
    val isCachedScope: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * PodcastDetail ViewModel（Phase 6.4）
 * 对应 iOS: PodcastDetailVC + PodcastEpisodesFetchedResultsController
 *
 * - 进入页面即 fetch（iOS viewIsAppearing → podcast.fetch → sync(podcast:)）
 * - 下拉刷新同步 sync(podcast:)（iOS handleRefresh）
 * - 头部播放按钮 "Newest Episode"：播放最新一集，无 Shuffle（iOS :59-76 isShuffleHidden）
 * - Delete on Server：deletePodcastEpisode 后重新 sync 刷新状态（iOS EntityPreviewVC:687-695）
 */
@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val podcastId: String = savedStateHandle.get<String>("podcastId") ?: ""

    private val _uiState = MutableStateFlow(PodcastDetailUiState())
    val uiState: StateFlow<PodcastDetailUiState> = _uiState.asStateFlow()

    val podcast: StateFlow<Podcast?> =
        appDelegate.podcasts.observePodcastById(podcastId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val filteredEpisodes: StateFlow<List<PodcastEpisode>> = combine(
        appDelegate.podcasts.getPodcastEpisodes(podcastId),
        _uiState
    ) { episodes, state ->
        var result = episodes
        if (state.searchText.isNotEmpty()) {
            result = result.filter { it.title.contains(state.searchText, ignoreCase = true) }
        }
        // Batch 4：Cached 作用域按单集缓存态过滤（对齐 iOS scope 语义；此前无缓存管线时恒空）
        if (state.isCachedScope) result = result.filter { it.isDownloaded }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isOfflineMode = appDelegate.settings.isOfflineMode
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /** 单集下载进度映射（Batch 4，行内缓存图标/进度环） */
    val downloadProgressMap = appDelegate.downloader.downloadProgressMap

    // 播放状态（行播放指示器用）
    val currentSong = appDelegate.player.currentSong
    val isPlaying = appDelegate.player.isPlaying

    /**
     * 顶栏 More 菜单动作（整个播客）- 对应 iOS PodcastDetailVC.swift:107
     * `optionsButton.menu = EntityPreviewActionBuilder(container: podcast).createMenuActions()`
     * 曲目集合 = 全部可用单集（isAvailableToUser 自 Batch 4 起含已缓存；Batch 2 起队列两项落独立播客队列）
     */
    fun handlePodcastAction(action: SwipeActionType) {
        viewModelScope.launch {
            val playables = filteredEpisodes.value
                .filter { it.isAvailableToUser }
                .map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            if (playables.isEmpty()) return@launch
            val title = podcast.value?.title ?: ""
            when (action) {
                SwipeActionType.PLAY ->
                    appDelegate.player.play(playables, PlayContextType.PODCAST, podcastId, title)
                SwipeActionType.INSERT_PODCAST_QUEUE ->
                    appDelegate.player.insertPodcastQueue(playables)
                SwipeActionType.APPEND_PODCAST_QUEUE ->
                    appDelegate.player.appendPodcastQueue(playables)
                else -> Unit
            }
        }
    }

    init {
        // iOS: viewIsAppearing → podcast.fetch(...)（即 sync(podcast:)）
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
                appDelegate.podcasts.syncPodcastDetails(podcastId)
                    .onFailure { e ->
                        // iOS 错误主题 "Podcast Sync"
                        _uiState.update { it.copy(error = "Podcast Sync failed: ${e.message}") }
                    }
            } finally {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
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
                    listOf(playable), PlayContextType.PODCAST, podcastId, episode.podcastTitle
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play episode", e)
            }
        }
    }

    /** 头部 "Newest Episode" 播放按钮：播放最新一集（iOS PodcastDetailVC:59-76，context?.first） */
    fun playNewestEpisode() {
        filteredEpisodes.value.firstOrNull { it.isAvailableToUser }?.let { playEpisode(it) }
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

    /** 单集加入播客队列（Android 单队列简化：映射上下文队列） */
    fun insertPodcastQueue(episode: PodcastEpisode) {
        handleEpisodeSwipeAction(episode, SwipeActionType.INSERT_PODCAST_QUEUE)
    }

    fun appendPodcastQueue(episode: PodcastEpisode) {
        handleEpisodeSwipeAction(episode, SwipeActionType.APPEND_PODCAST_QUEUE)
    }

    /**
     * 服务器删除单集（iOS EntityPreviewVC:687-695：确认后 requestPodcastEpisodeDelete
     * → sync(podcast:) 刷新，单集变 "Deleted on server" 状态）
     */
    fun deleteEpisodeOnServer(episode: PodcastEpisode) {
        viewModelScope.launch {
            appDelegate.podcasts.deletePodcastEpisodeOnServer(episode.id)
                .onSuccess {
                    appDelegate.podcasts.syncPodcastDetails(podcastId)
                }.onFailure { e ->
                    _uiState.update { it.copy(error = "Delete failed: ${e.message}") }
                }
        }
    }

    companion object {
        private const val TAG = "PodcastDetailViewModel"
    }
}
