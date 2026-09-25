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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSPullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.Genre
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.EntityPreviewCard
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSLongPressPreviewMenu
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildGenreContextMenuItems
import com.amperfy.ui.components.contextmenu.genrePreviewInfo
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.DefaultArtworkType

/**
 * GenresScreen - 流派列表（Phase 6.1）
 * 对应 iOS: GenresVC
 *
 * - 行 = 名称 + "X Albums · Y Songs" 信息 + chevron，无图（iOS cell.entityImage.isHidden = true）
 * - 搜索（placeholder 对齐 iOS）+ All/Cached 作用域
 * - 右侧字母索引复用 AlphabetIndex 组件
 * - 下拉刷新重新同步 getGenres
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GenresScreen(
    onBackClick: () -> Unit,
    onGenreClick: (Genre) -> Unit,
    viewModel: GenresViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val genres by viewModel.filteredGenres.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    val cachedGenreNames by viewModel.cachedGenreNames.collectAsState()
    // 全缓存流派集合（对应 iOS isCachedCompletely）：长按菜单据此隐藏 Download
    val fullyCachedGenreNames by viewModel.fullyCachedGenreNames.collectAsState()

    val showCollapsedTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

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

    // 作用域行仅在搜索激活时显示（iOS scope buttons 默认行为）；隐藏时重置回 All
    val showScopeBar = searchState.isPinned || uiState.searchText.isNotEmpty()
    LaunchedEffect(showScopeBar) {
        if (!showScopeBar) viewModel.setCachedScope(false)
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
                    placeholder = "Search in \"Genres\"",
                    scopeContent = scopeRow
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) "Genres" else null
            )
        }
    ) { padding ->
        IOSPullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
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
                // 索引条可见性（与下方 AlphabetIndex 的渲染条件同一个布尔）与行内容避让宽度：
                // 索引条可见时行内容 end 内边距改为「(条宽+最宽字形宽)/2+间隙」替代 16dp，分割线/行容器仍全宽
                // （= iOS UITableView 在 sectionIndexTitles 非空时收窄 cell.contentView，
                //   iOS 实机观测 2026-08-13，见 LocalListRowTrailingInset）
                // 该页索引条无 end 偏移（贴屏幕右缘）
                val indexBarRowEndPadding =
                    com.amperfy.ui.components.rememberIndexBarRowEndPadding()
                val rowTrailingInset = if (genres.isNotEmpty()) indexBarRowEndPadding else 0.dp

                CompositionLocalProvider(
                    com.amperfy.ui.components.LocalListRowTrailingInset provides rowTrailingInset
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(searchState.nestedScrollConnection),
                    contentPadding = PaddingValues(bottom = miniPlayerHeight)
                ) {
                    // 前置 item 1/3：大标题（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                    // iOS 风格大标题；钉顶时收起内容但保留 item 占位（字母索引 index 约定）
                    item {
                        if (!searchState.isPinned) {
                            IOSLargeTitle(
                                text = "Genres",
                                modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                            )
                        }
                    }

                    // 前置 item 2/3：搜索栏 + 作用域（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                    // 搜索栏 + 作用域（对应 iOS configureSearchController，GenresVC.swift:54）
                    // 恰好一个 item，显隐/钉顶/焦点行为全在 LibrarySearchState 内
                    librarySearchBarItem(
                        state = searchState,
                        searchText = uiState.searchText,
                        onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                        placeholder = "Search in \"Genres\"",
                        scopeContent = scopeRow,
                        searchBarModifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )

                    // 前置 item 3/3：分隔线（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                    // 列表区与上方控件的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）
                    item {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator
                        )
                    }

                    itemsIndexed(genres, key = { _, genre -> "genre_${genre.name}" }) { index, genre ->
                        GenreListItem(
                            genre = genre,
                            onClick = { onGenreClick(genre) },
                            hasCachedSongs = genre.name in cachedGenreNames,
                            isFullyCached = genre.name in fullyCachedGenreNames,
                            onSwipeAction = { action ->
                                viewModel.handleGenreSwipeAction(genre, action)
                            }
                        )
                        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
                        // separatorInset = cell layoutMargins 左右值，
                        // CommonScreenOperations.swift:41-47）；字母索引悬浮于内容之上，右侧不让位。
                        // **字母组之间恒全宽**：iOS GenresVC 按首字母分 section，
                        // 组间那条线是 grouped 表的段边界线而非 cell separator
                        //（iOS 实机对照 2026-08-10；此规则仅本页，其余字母索引页未经对照）。
                        // 末行不画：由下方 section 底边界线承担
                        if (index < genres.lastIndex) {
                            val isLetterBoundary =
                                com.amperfy.utils.AlphabetIndexUtils.getIndexLetter(genre.name) !=
                                    com.amperfy.utils.AlphabetIndexUtils.getIndexLetter(genres[index + 1].name)
                            HairlineDivider(
                                modifier = if (isLetterBoundary) Modifier else Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }

                    // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                    //（iOS 实机对照 2026-08-10）
                    if (genres.isNotEmpty()) {
                        item {
                            HairlineDivider(
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }
                }
                }

                // 空状态
                if (genres.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.searchText.isNotEmpty()) "No results" else "No genres",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 右侧字母索引（复用既有组件）
                if (genres.isNotEmpty()) {
                    com.amperfy.ui.components.AlphabetIndex(
                        items = genres,
                        listState = listState,
                        getIndexLetter = { genre ->
                            com.amperfy.utils.AlphabetIndexUtils.getIndexLetter(genre.name)
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        // 前置 item：title / searchBar / divider = 3（须与上方 LazyColumn 结构同步）
                        leadingItemCount = 3
                    )
                }
            }
        }
    }
}

/**
 * 流派行 - 对应 iOS GenericTableCell（无图变体，rowHeightWithoutImage）
 *
 * 长按弹预览卡 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt，
 * BasicTableViewController 对全部行统一提供）；菜单动作复用 Genre.handleSwipeAction
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenreListItem(
    genre: Genre,
    onClick: () -> Unit,
    hasCachedSongs: Boolean,
    isFullyCached: Boolean,
    onSwipeAction: (SwipeActionType) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val settingsManager = LocalSettingsManager.current
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()

    // 索引条可见时内容 Row 的 end 内边距总值（**替代** 16dp 而非叠加），行容器与分割线保持全宽
    // （= iOS UITableView 收窄 cell.contentView，见 LocalListRowTrailingInset）
    val trailingInset = com.amperfy.ui.components.LocalListRowTrailingInset.current

    var showPreviewMenu by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }

    val menuItems = buildGenreContextMenuItems(
        env = MenuEnv(
            isOfflineMode = isOfflineMode,
            isShuffleActionEnabled = isShuffleActionEnabled,
            isShowDetailedInfo = isShowDetailedInfo
        ),
        hasCachedSongs = hasCachedSongs,
        isFullyCached = isFullyCached,
        onAction = onSwipeAction,
        onCopyId = {
            // Subsonic 流派无 id，Copy ID 复制 name（唯一标识，见 Genre.kt）
            if (genre.name.isNotEmpty()) {
                clipboardManager.setText(AnnotatedString(genre.name))
            }
        }
    )

    Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                // 屏幕坐标（Popup 窗口原点与 App 窗口可能不一致，窗口坐标会错位）
                val position = coordinates.positionOnScreen()
                rowBoundsOnScreen = Rect(
                    left = position.x,
                    top = position.y,
                    right = position.x + coordinates.size.width,
                    bottom = position.y + coordinates.size.height
                )
            }
            // 行底色：长按 morph 时行矩形据此着色（同 SongListItem）
            .background(MaterialTheme.colorScheme.background)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showPreviewMenu = true
                }
            )
            // 横向 16/16：与全部列表行同一 cell layoutMargins
            // （CommonScreenOperations.swift:41-47 defaultMarginCellX = 16）；
            // 索引条可见时 end 侧改用避让值替代 16
            .padding(start = 16.dp, end = maxOf(16.dp, trailingInset), top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = genre.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // 信息（"X Albums · Y Songs"，对应 iOS infoLabel）
        Text(
            text = genre.info,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.size(4.dp))
        // 对应 iOS accessoryType = .disclosureIndicator
        Icon(
            AmperfyIcons.chevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }

        // 长按弹出：预览卡片 + 上下文菜单
        IOSLongPressPreviewMenu(
            expanded = showPreviewMenu,
            onDismissRequest = { showPreviewMenu = false },
            anchorBoundsOnScreen = rowBoundsOnScreen,
            items = menuItems
        ) {
            EntityPreviewCard(
                // 流派无服务器封面（iOS GenresVC:140 亦 entityImage.isHidden = true），
                // 恒走主题化默认艺术图（iOS ArtworkType.genre，Genre.swift:137）
                coverArtModel = null,
                defaultArtworkType = DefaultArtworkType.GENRE,
                title = genre.name,
                // iOS Genre 无副标题
                subtitle = null,
                info = genrePreviewInfo(genre, isShowDetailedInfo),
                // 对应 iOS performPreviewTransition 的 genre 分支：点卡片进流派详情
                showChevron = true,
                onClick = {
                    showPreviewMenu = false
                    onClick()
                }
            )
        }
    }
}
