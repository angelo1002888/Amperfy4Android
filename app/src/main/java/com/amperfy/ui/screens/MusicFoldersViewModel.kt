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
import com.amperfy.data.model.MusicFolder
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
 * MusicFolders UI 状态
 */
data class MusicFoldersUiState(
    val searchText: String = "",
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * MusicFolders ViewModel（Phase 6.5，目录浏览第一层）
 * 对应 iOS: MusicFoldersVC + MusicFolderFetchedResultsController
 *
 * - 标题固定 "Directories"（iOS sceneTitle）
 * - 列表按 id 排序（iOS MusicFolderMO.idSortedFetchRequest）
 * - 进入与下拉刷新同步 getMusicFolders（iOS viewIsAppearing，仅在线）
 */
@HiltViewModel
class MusicFoldersViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    private val _uiState = MutableStateFlow(MusicFoldersUiState())
    val uiState: StateFlow<MusicFoldersUiState> = _uiState.asStateFlow()

    val filteredFolders: StateFlow<List<MusicFolder>> = combine(
        appDelegate.directories.getMusicFolders(),
        _uiState
    ) { folders, state ->
        if (state.searchText.isNotEmpty()) {
            folders.filter { it.name.contains(state.searchText, ignoreCase = true) }
        } else {
            folders
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                appDelegate.directories.syncMusicFolders()
                    .onFailure { e ->
                        // iOS 错误主题 "Music Folders Sync"
                        _uiState.update { it.copy(error = "Music Folders Sync failed: ${e.message}") }
                    }
            } finally {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }
}
