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
import com.amperfy.data.model.MusicFolder
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
 * Indexes UI 状态
 */
data class IndexesUiState(
    val searchText: String = "",
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * Indexes ViewModel（Phase 6.5，目录浏览第二层：音乐文件夹的顶层目录）
 * 对应 iOS: IndexesVC + MusicFolderDirectoriesFetchedResultsController
 *
 * - 标题为音乐文件夹名（iOS sceneTitle { musicFolder.name }）
 * - 进入与下拉刷新同步 getIndexes（iOS viewIsAppearing → syncIndexes(musicFolder:)）
 */
@HiltViewModel
class IndexesViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val folderId: String = savedStateHandle.get<String>("folderId") ?: ""

    private val _uiState = MutableStateFlow(IndexesUiState())
    val uiState: StateFlow<IndexesUiState> = _uiState.asStateFlow()

    val musicFolder: StateFlow<MusicFolder?> =
        appDelegate.directories.observeMusicFolderById(folderId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val filteredDirectories: StateFlow<List<Directory>> = combine(
        appDelegate.directories.getMusicFolderDirectories(folderId),
        _uiState
    ) { directories, state ->
        if (state.searchText.isNotEmpty()) {
            directories.filter { it.name.contains(state.searchText, ignoreCase = true) }
        } else {
            directories
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 长按菜单动作结果协调（删除缓存确认 / 加入播放列表选择器） */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /**
     * 目录行长按菜单动作入口（与滑动手势共用 Directory.handleSwipeAction 单一真相源）
     * 对应 iOS: IndexesVC 的 SwipeActionContext(containable: directory)
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
                appDelegate.directories.syncIndexes(folderId)
                    .onFailure { e ->
                        // iOS 错误主题 "Indexes Sync"
                        _uiState.update { it.copy(error = "Indexes Sync failed: ${e.message}") }
                    }
            } finally {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }
}
