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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSPullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.Artist
// 艺术家行与 GenreDetailScreen 的 Artists 段共用同一组件
// （iOS 两处本就是同一个 GenericTableCell）
import com.amperfy.ui.components.ArtistListItem
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator

/**
 * Artists屏幕 - iOS风格
 * 对应iOS: ArtistsVC
 *
 * 主要特性:
 * - 永久显示的搜索栏
 * - 可滚动折叠的大标题
 * - 右上角More菜单(横向三点)整合Sort/Filter/Download
 * - 无字母分组的列表（右侧有字母索引）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistsScreen(
    onBackClick: () -> Unit,
    onArtistClick: (Artist) -> Unit,
    viewModel: ArtistsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val artists by viewModel.filteredArtists.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()
    val isRefreshing by remember { derivedStateOf { uiState.isRefreshing } }

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    // 滑动动作配置（来自设置，经 SwipeDisplaySettings 过滤）
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    // 含缓存歌曲的艺术家 id（长按菜单 Play/Shuffle 离线门控 + Delete Cache 显隐）
    val cachedArtistIds by viewModel.cachedArtistIds.collectAsState()
    // 全缓存艺术家集合（对应 iOS isCachedCompletely）：长按菜单据此隐藏 Download
    val fullyCachedArtistIds by viewModel.fullyCachedArtistIds.collectAsState()
    val leadingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.leading, SwipeContentType.MUSIC, isOfflineMode)
    }
    val trailingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.trailing, SwipeContentType.MUSIC, isOfflineMode)
    }

    // 滑动控制器 - 确保同时只有一个 item 处于滑出状态
    val swipeController = rememberSwipeController()
    val swipeScope = rememberCoroutineScope()
    val scope = rememberCoroutineScope()
    // 「Many Songs」确认（对应 iOS ArtistsVC.swift:486-499 的 UIAlertController）
    var manySongs by remember { mutableStateOf<List<com.amperfy.data.model.Song>?>(null) }
    manySongs?.let { songs ->
        AlertDialog(
            onDismissRequest = { manySongs = null },
            title = { Text("Many Songs") },
            text = {
                Text("Are you sure to add ${songs.size} songs from \"${uiState.filterTitle}\" to download queue?")
            },
            confirmButton = {
                TextButton(onClick = {
                    manySongs = null
                    viewModel.downloadSongs(songs)
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { manySongs = null }) { Text("Cancel") }
            }
        )
    }
    val nestedScrollConnection = remember(swipeController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y != 0f) {
                    swipeController.closeCurrentItem(swipeScope)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    // 删除缓存确认对话框
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { pendingSongs ->
        DeleteCacheConfirmDialog(
            songCount = pendingSongs.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteCache() }
        )
    }

    // 添加到播放列表选择器（滑动 ADD_TO_PLAYLIST / More 菜单触发）
    val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
    pendingPlaylistSongIds?.let { ids ->
        PlaylistSelectorDialog(
            songIds = ids,
            onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
        )
    }

    // 监听滚动位置
    // 当滚动到第一个item（大标题）完全不可见时，显示导航栏中的折叠标题
    val showCollapsedTitle by remember {
        derivedStateOf {
            // 如果滚动超过第一个item（大标题），则显示折叠标题
            listState.firstVisibleItemIndex > 0
        }
    }

    // 计算搜索框是否应该固定
    // 搜索框在第二个item位置，当它滚动到顶部时固定
    val isSearchBarPinned by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex >= 1
        }
    }

    Scaffold(
        topBar = {
            // 钉顶态：导航栏整体收起，钉顶搜索头贴页面最顶（对齐 iOS
            // hidesNavigationBarDuringPresentation = true）；列表经 innerPadding 落其下方滚动
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"${uiState.filterTitle}\""
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) uiState.sceneTitle else null,
                actions = {
                    // More 菜单 - 横向三点图标
                    MoreMenu(
                        currentSortType = uiState.sortType,
                        currentFilter = uiState.displayFilter,
                        filterTitle = uiState.filterTitle,
                        alignment = Alignment.TopEnd,  // 在按钮左下方展开
                        offset = IntOffset(-72, 48),
                        onSortTypeSelected = { viewModel.setSortType(it) },
                        onFilterSelected = { viewModel.setDisplayFilter(it) },
                        onDownloadClick = {
                            scope.launch {
                                val songs = viewModel.collectFilteredArtistSongs()
                                if (songs.size > MANY_SONGS_WARNING_THRESHOLD) {
                                    manySongs = songs
                                } else {
                                    viewModel.downloadSongs(songs)
                                }
                            }
                        },
                        isOfflineMode = isOfflineMode
                    )
                }
            )
        }
    ) { padding ->
        IOSPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.handleRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when {
                    uiState.isLoading && artists.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    artists.isEmpty() && !uiState.isLoading -> {
                        EmptyState(
                            displayFilter = uiState.displayFilter,
                            isSearching = uiState.searchText.isNotEmpty()
                        )
                    }
                    else -> {
                        // 主列表 - iOS风格：大标题在上，搜索栏在��并可固定
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 索引条可见性（与下方 AlphabetIndex 的渲染条件同一个布尔）与行内容避让宽度：
                            // 索引条可见时行内容 end 内边距改为「(条宽+最宽字形宽)/2+间隙」替代 16dp，分割线/行容器仍全宽
                            // （= iOS UITableView 在 sectionIndexTitles 非空时收窄 cell.contentView，
                            //   iOS 实机观测 2026-08-13，见 LocalListRowTrailingInset）
                            val isIndexBarVisible =
                                uiState.sortType == ArtistSortType.NAME && artists.isNotEmpty()
                            // 该页索引条无 end 偏移（贴屏幕右缘）
                            val indexBarRowEndPadding =
                                com.amperfy.ui.components.rememberIndexBarRowEndPadding()
                            val rowTrailingInset =
                                if (isIndexBarVisible) indexBarRowEndPadding else 0.dp

                            CompositionLocalProvider(
                                com.amperfy.ui.components.LocalListRowTrailingInset provides rowTrailingInset
                            ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(nestedScrollConnection)
                                    .nestedScroll(searchState.nestedScrollConnection),
                                contentPadding = PaddingValues(bottom = miniPlayerHeight)
                            ) {
                                // 前置 item 1/3：大标题（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                                // iOS风格的大标题 - 随滚动消失；钉顶时收起内容但保留 item 占位（字母索引 index 约定）
                                item {
                                    if (!searchState.isPinned) {
                                        IOSLargeTitle(
                                            text = uiState.sceneTitle,
                                            modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                                        )
                                    }
                                }

                                // 前置 item 2/3：搜索栏（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                                // 搜索栏 - 恰好一个 item（对齐 iOS configureSearchController，
                                // ArtistsVC.swift:164）；显隐/钉顶/焦点行为全在 LibrarySearchState 内
                                librarySearchBarItem(
                                    state = searchState,
                                    searchText = uiState.searchText,
                                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                                    placeholder = "Search in \"${uiState.filterTitle}\"",
                                    searchBarModifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )

                                // 前置 item 3/3：分隔线（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                                // 列表区与上方控件（标题/搜索栏）的交界线 = iOS .grouped 表的
                                // section 顶边界线，恒全宽（0..0），不吃 separatorInset
                                item {
                                    HairlineDivider(
                                        color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                                    )
                                }
                                // 艺术家列表
                                itemsIndexed(
                                    artists,
                                    key = { _, artist -> "artist_${artist.id}" }
                                ) { index, artist ->
                                    SwipeableItem(
                                        key = "artist_${artist.id}",
                                        swipeController = swipeController,
                                        leadingActions = leadingSwipeActions,
                                        trailingActions = trailingSwipeActions,
                                        onSwipeAction = { action -> viewModel.handleSwipeAction(artist, action) },
                                        isFavorite = artist.isFavorite
                                    ) {
                                        ArtistListItem(
                                            artist = artist,
                                            onClick = { onArtistClick(artist) },
                                            onToggleFavorite = { viewModel.toggleFavorite(artist) },
                                            hasCachedSongs = artist.id in cachedArtistIds,
                                            isFullyCached = artist.id in fullyCachedArtistIds,
                                            onSwipeAction = { action ->
                                                viewModel.handleSwipeAction(artist, action)
                                            },
                                            onSetRating = { rating ->
                                                viewModel.setRating(artist, rating)
                                            }
                                        )
                                    }
                                    // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘
                                    // （UITableView 默认 separatorInset = cell layoutMargins 左右值，
                                    // CommonScreenOperations.swift:41-47）；
                                    // 字母索引悬浮于内容之上，右侧不再让位。
                                    // 末行不画：那一条由下方 section 底边界线（全宽）承担
                                    if (index < artists.lastIndex) {
                                        HairlineDivider(
                                            modifier = Modifier.padding(start = 16.dp),
                                            color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                                        )
                                    }
                                }

                                // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0），
                                // 不吃 separatorInset（iOS 实机对照 2026-08-10）
                                item {
                                    HairlineDivider(
                                        color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                                    )
                                }
                            }
                            }

                            // 右侧字母索引
                            if (isIndexBarVisible) {
                                com.amperfy.ui.components.AlphabetIndex(
                                    items = artists,
                                    listState = listState,
                                    getIndexLetter = { artist -> com.amperfy.utils.AlphabetIndexUtils.getIndexLetter(artist.name) },
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    // 前置 item：title / searchBar / divider = 3（须与下方 LazyColumn 结构同步）
                                    leadingItemCount = 3
                                )
                            }
                        }
                    }
                }

                // 错误提示
                uiState.error?.let { error ->
                    Snackbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        action = {
                            TextButton(onClick = { viewModel.clearError() }) {
                                Text("Dismiss")
                            }
                        }
                    ) {
                        Text(error)
                    }
                }
            }
        }
    }
}

/**
 * More 菜单 - iOS风格
 * 使用横向三点图标（ellipsis）
 * 对应iOS: updateRightBarButtonItems() -> ellipsis菜单
 */
@Composable
private fun MoreMenu(
    currentSortType: ArtistSortType,
    currentFilter: ArtistDisplayFilter,
    filterTitle: String,
    onSortTypeSelected: (ArtistSortType) -> Unit,
    onFilterSelected: (ArtistDisplayFilter) -> Unit,
    onDownloadClick: () -> Unit,
    isOfflineMode: Boolean,
    alignment: Alignment = Alignment.TopEnd,
    offset: IntOffset = IntOffset(0, 0)
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        // More 按钮 - 横向三点 (iOS: ellipsis)
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = AmperfyIcons.ellipsis,
                contentDescription = "More"
            )
        }

        // 主菜单 - iOS风格
        IOSStyleContextMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            alignment = alignment,
            offset = offset,
            items = buildList {
                // Sort 子菜单
                add(IOSContextMenuItem.Submenu(
                    text = "Sort",
                    // iOS: UIMenu(title: "Sort", image: .sort)（ArtistsVC.swift:439-440）
                    icon = AmperfyIcons.sort,
                    items = listOf(
                        IOSContextMenuItem.Action(
                            text = "Name",
                            icon = if (currentSortType == ArtistSortType.NAME) AmperfyIcons.check else null,
                            onClick = { onSortTypeSelected(ArtistSortType.NAME) }
                        ),
                        IOSContextMenuItem.Action(
                            text = "Rating",
                            icon = if (currentSortType == ArtistSortType.RATING) AmperfyIcons.check else null,
                            onClick = { onSortTypeSelected(ArtistSortType.RATING) }
                        ),
                        IOSContextMenuItem.Action(
                            text = "Duration",
                            icon = if (currentSortType == ArtistSortType.DURATION) AmperfyIcons.check else null,
                            onClick = { onSortTypeSelected(ArtistSortType.DURATION) }
                        )
                    )
                ))
                // Filter 子菜单：Favorites 模式隐藏（菜单无 Favorites 项，切走后无法切回）
                // 对应 iOS: favorites 模式隐藏 Filter（ArtistsVC.swift:137-139）
                if (currentFilter != ArtistDisplayFilter.FAVORITES) {
                    add(IOSContextMenuItem.Submenu(
                        text = "Filter",
                        // iOS: UIMenu(title: "Filter", image: .filter)（ArtistsVC.swift:462-463）
                        icon = AmperfyIcons.filter,
                        items = listOf(
                            IOSContextMenuItem.Action(
                                text = "All",
                                icon = if (currentFilter == ArtistDisplayFilter.ALL) AmperfyIcons.check else null,
                                onClick = { onFilterSelected(ArtistDisplayFilter.ALL) }
                            ),
                            IOSContextMenuItem.Action(
                                text = "Album Artists",
                                icon = if (currentFilter == ArtistDisplayFilter.ALBUM_ARTISTS) AmperfyIcons.check else null,
                                onClick = { onFilterSelected(ArtistDisplayFilter.ALBUM_ARTISTS) }
                            )
                        )
                    ))
                }
                // Download <filterTitle>——**仅在线模式**（iOS ArtistsVC.swift:196-198
                // `if isOnlineMode { actions.append(createActionButtonMenu()) }`）
                if (!isOfflineMode) {
                    add(IOSContextMenuItem.Divider)
                    add(IOSContextMenuItem.Action(
                        text = "Download $filterTitle",
                        // iOS: UIAction(title: "Download …", image: .startDownload)（ArtistsVC.swift:472）
                        icon = AmperfyIcons.download,
                        onClick = onDownloadClick
                    ))
                }
            }
        )
    }
}

/**
 * 空状态显示
 */
@Composable
private fun EmptyState(
    displayFilter: ArtistDisplayFilter,
    isSearching: Boolean
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                AmperfyIcons.artist,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    isSearching -> "No artists found"
                    displayFilter == ArtistDisplayFilter.FAVORITES -> "No favorite artists"
                    else -> "No artists available"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    isSearching -> "Try a different search term"
                    displayFilter == ArtistDisplayFilter.FAVORITES -> "Mark some artists as favorites"
                    else -> "Sync your library to see artists"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
