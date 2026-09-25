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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator

/**
 * DirectoryDetailScreen - 目录浏览第三层：子目录 + 歌曲（Phase 6.5，可递归下钻）
 * 对应 iOS: DirectoriesVC（MultiSourceTableViewController 双 section）
 *
 * - 标题为目录名（iOS setNavBarTitle(title: directory.name)，DirectoriesVC.swift:64）；
 *   **无大标题**——DirectoriesVC.swift:204 prefersLargeTitles = false，标题恒为导航栏居中小标题
 * - 搜索 placeholder "Directories and Songs" + All/Cached 作用域（Cached 仅过滤歌曲）
 * - **无段标题**：iOS DirectoriesVC.swift:67 sectionHeaderHeight = 0 且未覆写
 *   titleForHeaderInSection，子目录段与歌曲段之间只有普通行间分隔线
 * - 头部 Play/Shuffle：整页非空即显示，**无歌曲时禁用而非隐藏**
 *   （iOS refreshHeaderView 的 activate/deactivate，DirectoriesVC.swift:193-200）；
 *   仅子目录与歌曲皆空时随 contentUnavailable 整块隐藏（:170-183 headerView?.isHidden）
 * - 子目录点击递归进入本页；歌曲点击以目录为上下文播放
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DirectoryDetailScreen(
    // 返回按钮文字 = 上级页名（文件夹名或父目录名，对齐 iOS 自动返回按钮）
    backTitle: String = "Directories",
    onBackClick: () -> Unit,
    // (directoryId, parentName)：parentName 传本目录名，用作下级返回按钮文字
    onNavigateToDirectory: (String, String) -> Unit,
    // 歌曲行 More 菜单 Show Album / Show Artist 导航（iOS EntityPreviewActionBuilder）
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    viewModel: DirectoryDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val directory by viewModel.directory.collectAsState()
    val subdirectories by viewModel.filteredSubdirectories.collectAsState()
    val songs by viewModel.filteredSongs.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    // 作用域行仅在搜索激活时显示（iOS scope buttons 默认行为）；隐藏时重置回 All
    val showScopeBar = searchState.isPinned || uiState.searchText.isNotEmpty()
    LaunchedEffect(showScopeBar) {
        if (!showScopeBar) viewModel.setCachedScope(false)
    }

    // 添加到播放列表选择器（歌曲行 More 菜单 Add to Playlist 触发）
    // 删除缓存确认对话框（长按菜单/滑动 Delete Cache 的 NeedsConfirmation 承接点）
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { songs ->
        DeleteCacheConfirmDialog(
            songCount = songs.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteCache() }
        )
    }

    val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
    pendingPlaylistSongIds?.let { ids ->
        PlaylistSelectorDialog(
            songIds = ids,
            onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
        )
    }

    // All/Cached 作用域段（钉顶头与列表内共用同一渲染）
    val scopeRow: @Composable () -> Unit = {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            listOf(false, true).forEachIndexed { index, cached ->
                SegmentedButton(
                    selected = uiState.isCachedScope == cached,
                    onClick = { viewModel.setCachedScope(cached) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    label = { Text(if (cached) "Cached" else "All") }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            // 钉顶态：导航栏收起，钉顶搜索头贴页面最顶（列表经 innerPadding 落其下方）
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Directories and Songs",
                    scopeContent = scopeRow
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = backTitle,
                // iOS DirectoriesVC.swift:204 prefersLargeTitles = false → 无大标题，
                // 导航栏居中小标题恒显示目录名（setNavBarTitle，:64），不随滚动切换
                title = directory?.name
            )
        }
    ) { padding ->
        // iOS DirectoriesVC 未挂 refreshControl → 无下拉刷新，仅普通容器承载内容
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
                    // 无大标题前置 item：iOS DirectoriesVC.swift:204 prefersLargeTitles = false，
                    // 本页标题只在导航栏居中显示

                    // 搜索栏 + All/Cached 作用域（iOS configureSearchController，
                    // DirectoriesVC.swift:60，placeholder "Directories and Songs"）——
                    // 恰好一个 item，显隐/钉顶/焦点行为全在 LibrarySearchState 内
                    librarySearchBarItem(
                        state = searchState,
                        searchText = uiState.searchText,
                        onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                        placeholder = "Directories and Songs",
                        scopeContent = scopeRow,
                        searchBarModifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )

                    // 头部 Play/Shuffle：整页非空（有子目录或有歌曲）即显示；
                    // **无歌曲时禁用而非隐藏**——iOS refreshHeaderView 走 deactivate()
                    // （DirectoriesVC.swift:193-200），headerView 只在子目录与歌曲皆空时
                    // 随 contentUnavailable 整块隐藏（:170-183）
                    if (subdirectories.isNotEmpty() || songs.isNotEmpty()) {
                        item {
                            DirectoryPlayShuffleHeader(
                                onPlay = { viewModel.playAll() },
                                onShuffle = { viewModel.shuffleAll() },
                                enabled = songs.isNotEmpty()
                            )
                        }
                    }

                    // 列表区与上方控件（搜索栏/头部 Play·Shuffle）的交界线 = iOS .grouped 表的
                    // section 顶边界线，恒全宽（0..0），不吃 separatorInset
                    if (subdirectories.isNotEmpty() || songs.isNotEmpty()) {
                        item {
                            HairlineDivider(
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }

                    // section 0：子目录（iOS DirectorySubdirectoriesFetchedResultsController）
                    // 无段标题：iOS sectionHeaderHeight = 0（DirectoriesVC.swift:67）
                    if (subdirectories.isNotEmpty()) {
                        itemsIndexed(
                            subdirectories,
                            key = { _, subdirectory -> "dir_${subdirectory.id}" }
                        ) { index, subdirectory ->
                            DirectoryListRow(
                                name = subdirectory.name,
                                coverArt = subdirectory.coverArt,
                                onClick = { onNavigateToDirectory(subdirectory.id, directory?.name ?: "Directories") },
                                directory = subdirectory,
                                onSwipeAction = { action ->
                                    viewModel.handleDirectorySwipeAction(subdirectory, action)
                                }
                            )
                            // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘
                            // （UITableView 默认 separatorInset = cell layoutMargins 左右值，
                            // CommonScreenOperations.swift:41-47），不跟随封面左缘；
                            // 整页最后一行（无歌曲段时的末个子目录）不画，让位给全宽段底线
                            if (index < subdirectories.lastIndex || songs.isNotEmpty()) {
                                HairlineDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    color = MaterialTheme.colorScheme.separator
                                )
                            }
                        }
                    }

                    // section 1：歌曲（iOS DirectorySongsFetchedResultsController，按 track 排序）
                    // 同样无段标题，紧接子目录段（两段之间只有 SongListItem 内置的行间分隔线）
                    if (songs.isNotEmpty()) {
                        itemsIndexed(songs, key = { _, song -> "song_${song.id}" }) { index, song ->
                            SongListItem(
                                song = song,
                                style = SongListItemStyle.ARTWORK,
                                isPlaying = currentSong?.id == song.id && isPlaying,
                                // 末行的 16dp inset 线让位给下方全宽 section 底边界线
                                showDivider = index < songs.lastIndex,
                                callbacks = SongListItemCallbacks(
                                    onClick = { viewModel.playSong(song) },
                                    // Shuffle = 以本目录歌曲列表为上下文乱序播放
                                    onShuffle = { viewModel.shuffleAll() },
                                    onToggleFavorite = { viewModel.toggleSongFavorite(song) },
                                    onSetRating = { rating -> viewModel.setSongRating(song, rating) },
                                    onInsertContextQueue = { viewModel.insertContextQueue(song) },
                                    onAppendContextQueue = { viewModel.appendContextQueue(song) },
                                    onAddToQueueNext = { viewModel.addToQueueNext(song) },
                                    onAddToQueueLater = { viewModel.addToQueueLater(song) },
                                    onShowAlbum = { song.albumId?.let { onNavigateToAlbum(it) } },
                                    onShowArtist = { song.artistId?.let { onNavigateToArtist(it) } },
                                    onAddToPlaylist = { viewModel.addToPlaylist(song) },
                                    onDownload = { viewModel.downloadSong(song) },
                                    onDeleteCache = { viewModel.deleteSongCache(song) }
                                )
                            )
                        }
                    }

                    // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                    //（iOS 实机对照 2026-08-10；两段之间不另加线，只封整表底边）
                    if (subdirectories.isNotEmpty() || songs.isNotEmpty()) {
                        item {
                            HairlineDivider(
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }

                    // 空状态
                    if (subdirectories.isEmpty() && songs.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (uiState.searchText.isNotEmpty() || uiState.isCachedScope) {
                                        "No results"
                                    } else {
                                        "Empty directory"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
        }
    }
}

/**
 * 头部 Play/Shuffle + "N Song(s)"（iOS DirectoriesVC:59-72）
 *
 * @param enabled 对应 iOS refreshHeaderView 的 activate()/deactivate()
 *   （DirectoriesVC.swift:193-200）：目录内无歌曲时两个按钮禁用（**不隐藏**）
 */
@Composable
private fun DirectoryPlayShuffleHeader(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 计数信息行**不渲染**：iOS `LibraryElementDetailTableHeaderView` 的
        // `infoContainerView.isHidden = isInfoAlwaysHidden || horizontalSizeClass == .compact`
        //（LibraryElementDetailTableHeaderView.swift:119-120）——iPhone 竖屏恒为 compact，
        // 故该行在 iPhone 上永远看不到（只在 iPad/regular 宽度出现）。
        // 注意：各**详情页**（Album/Artist/Playlist/Podcast Detail）的 info 行是另一套
        // `GenericDetailTableHeader.infoLabel`（规则为 isHidden = infoText.isEmpty），
        // 在 iPhone 上照常显示，不在本清扫范围内
        PlayShuffleButtons(
            onPlay = onPlay,
            onShuffle = onShuffle,
            enabled = enabled
        )
    }
}
