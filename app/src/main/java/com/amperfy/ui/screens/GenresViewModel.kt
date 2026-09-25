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
import com.amperfy.data.model.Genre
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Genres UI 状态
 */
data class GenresUiState(
    val searchText: String = "",
    val isSearchActive: Boolean = false,
    /** false=All / true=Cached（iOS scopeButtonTitles: ["All", "Cached"]） */
    val isCachedScope: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * Genres ViewModel（Phase 6.1）
 * 对应 iOS: GenresVC + GenreFetchedResultsController
 *
 * - 列表按名称字母排序（iOS: GenreMO.alphabeticSortedFetchRequest）
 * - 搜索按名称匹配；Cached 作用域只显示含缓存歌曲的流派
 *   （iOS: LibraryStorage.getFetchPredicateForCachedGenres 系列 SUBQUERY）
 * - 首次进入同步流派列表（iOS 在 syncInitial 做；Android 兼容既有安装在此补同步）；
 *   下拉刷新亦重新同步 getGenres（iOS 下拉刷新为全局 newest 元素同步，已知差异）
 */
@HiltViewModel
class GenresViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    private val _uiState = MutableStateFlow(GenresUiState())
    val uiState: StateFlow<GenresUiState> = _uiState.asStateFlow()

    val filteredGenres: StateFlow<List<Genre>> = combine(
        appDelegate.library.getAllGenres(),
        appDelegate.library.getCachedGenreNames(),
        _uiState
    ) { genres, cachedNames, state ->
        var result = genres

        if (state.searchText.isNotEmpty()) {
            result = result.filter { it.name.contains(state.searchText, ignoreCase = true) }
        }
        if (state.isCachedScope) {
            result = result.filter { it.name in cachedNames }
        }

        // NAME：store 已按 sort_key（拼音分区 + 原文，# 严格最后）排序返回（P3 批次 1c 下沉），无须客户端重排
        result
    }
        // 拼音排序移出主线程（与 SongsViewModel 同模式），避免订阅瞬间在转场帧执行
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 含缓存歌曲的流派名集合（长按菜单 Delete Cache / 离线 Play 门控用；
     * 与 Cached 作用域过滤同源，见 LibraryRepository.getCachedGenreNames）
     */
    val cachedGenreNames: StateFlow<Set<String>> = appDelegate.library.getCachedGenreNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 全部歌曲已缓存的流派名集合（对应 iOS isCachedCompletely）：菜单隐藏 Download */
    val fullyCachedGenreNames: StateFlow<Set<String>> = appDelegate.library.getFullyCachedGenreNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 长按菜单动作结果协调（删除缓存确认 / 加入播放列表选择器） */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    init {
        syncGenres(showRefreshing = false)
    }

    /**
     * 长按菜单动作执行入口（与滑动手势共用 Genre.handleSwipeAction 单一真相源）
     * 对应 iOS: GenresVC 的 SwipeActionContext(containable: genre)
     */
    fun handleGenreSwipeAction(genre: Genre, action: SwipeActionType) {
        viewModelScope.launch {
            swipeCoordinator.onResult(genre.handleSwipeAction(action, appDelegate))
        }
    }

    fun onSearchTextChanged(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update {
            it.copy(isSearchActive = active, searchText = if (!active) "" else it.searchText)
        }
    }

    fun setCachedScope(cached: Boolean) {
        _uiState.update { it.copy(isCachedScope = cached) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 下拉刷新（离线模式直接结束，对应 iOS GenresVC.handleRefresh:121-124） */
    fun handleRefresh() {
        syncGenres(showRefreshing = true)
    }

    private fun syncGenres(showRefreshing: Boolean) {
        viewModelScope.launch {
            if (appDelegate.settings.isOfflineMode.value) {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
                return@launch
            }
            if (showRefreshing) _uiState.update { it.copy(isRefreshing = true) }
            try {
                appDelegate.library.syncGenres()
                    .onFailure { e ->
                        _uiState.update { it.copy(error = "Failed to sync genres: ${e.message}") }
                    }
            } finally {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    companion object {
        private const val TAG = "GenresViewModel"
    }
}
