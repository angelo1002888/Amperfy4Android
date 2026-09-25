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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.separator

/**
 * IndexesScreen - 目录浏览第二层：音乐文件夹的顶层目录（Phase 6.5）
 * 对应 iOS: IndexesVC
 *
 * - 标题为音乐文件夹名（iOS setNavBarTitle(title: musicFolder.name)，IndexesVC.swift:53）；
 *   **无大标题**——IndexesVC.swift:89 prefersLargeTitles = false，标题恒为导航栏居中小标题
 * - 搜索 `Search in "Directories"`；下拉刷新同步 getIndexes
 * - 点击进入 DirectoryDetailScreen（iOS segue toDirectories → DirectoriesVC）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexesScreen(
    onBackClick: () -> Unit,
    // (directoryId, parentName)：parentName 传本页文件夹名，用作下级返回按钮文字
    onNavigateToDirectory: (String, String) -> Unit,
    viewModel: IndexesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val musicFolder by viewModel.musicFolder.collectAsState()
    val directories by viewModel.filteredDirectories.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    // 删除缓存确认对话框（长按菜单 Delete Cache 走滑动执行路径，结果在此承接）
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { songs ->
        DeleteCacheConfirmDialog(
            songCount = songs.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteCache() }
        )
    }

    // 添加到播放列表选择器（长按菜单 Add to Playlist）
    val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
    pendingPlaylistSongIds?.let { ids ->
        PlaylistSelectorDialog(
            songIds = ids,
            onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
        )
    }

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    Scaffold(
        topBar = {
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Directories\""
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Directories",
                // iOS IndexesVC.swift:89 prefersLargeTitles = false → 无大标题，
                // 导航栏居中小标题恒显示文件夹名（setNavBarTitle，:53），不随滚动切换
                title = musicFolder?.name
            )
        }
    ) { padding ->
        // iOS IndexesVC 未挂 refreshControl → 无下拉刷新，仅普通容器承载内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(searchState.nestedScrollConnection),
                    contentPadding = PaddingValues(bottom = miniPlayerHeight)
                ) {
                    // 无大标题前置 item：iOS IndexesVC.swift:89 prefersLargeTitles = false，
                    // 本页标题只在导航栏居中显示

                    // 搜索栏（恰好一个 item，行为在 LibrarySearchState 内）
                    librarySearchBarItem(
                        state = searchState,
                        searchText = uiState.searchText,
                        onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                        placeholder = "Search in \"Directories\"",
                        searchBarModifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )

                    // 列表区与上方搜索栏的交界线 = iOS .grouped 表的 section 顶边界线，
                    // 恒全宽（0..0），不吃 separatorInset
                    if (directories.isNotEmpty()) {
                        item {
                            HairlineDivider(
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }

                    itemsIndexed(
                        directories,
                        key = { _, directory -> "dir_${directory.id}" }
                    ) { index, directory ->
                        DirectoryListRow(
                            name = directory.name,
                            coverArt = directory.coverArt,
                            onClick = { onNavigateToDirectory(directory.id, musicFolder?.name ?: "Directories") },
                            directory = directory,
                            onSwipeAction = { action ->
                                viewModel.handleDirectorySwipeAction(directory, action)
                            }
                        )
                        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘
                        // （UITableView 默认 separatorInset = cell layoutMargins 左右值，
                        // CommonScreenOperations.swift:41-47），不跟随封面左缘；
                        // 末行不画，让位给下方全宽 section 底边界线
                        if (index < directories.lastIndex) {
                            HairlineDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }

                    // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                    //（iOS 实机对照 2026-08-10）
                    if (directories.isNotEmpty()) {
                        item {
                            HairlineDivider(
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }
                }

                if (directories.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.searchText.isNotEmpty()) "No results" else "No directories",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
        }
    }
}
