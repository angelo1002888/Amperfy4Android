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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSPullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.EntityPreviewCard
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSLongPressPreviewMenu
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.formatDurationShortString
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildPlaylistContextMenuItems
import com.amperfy.ui.components.contextmenu.playlistPreviewInfo
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.swipe.SwipeController
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.systemRed
import com.amperfy.ui.theme.tertiaryLabel
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * Playlists屏幕 - iOS风格
 *
 * 对应iOS: PlaylistsVC.swift
 *
 * 显示所有播放列表：搜索、排序、同步。
 * 单个播放列表的操作（播放/随机播放/加入队列/添加到播放列表/下载等）通过左右滑动手势完成，
 * 与 iOS 一致（列表项右侧没有 More 按钮，列表也没有新建入口——iOS 仅在 PlaylistSelectorVC 中创建）。
 * 删除通过右上角 Edit/Done 编辑模式完成（对应 iOS PlaylistsVC 的 editButtonItem + commit editingStyle .delete）。
 * 右上角 More 菜单包含 Sort（Name/Last time played/Change date/Duration）与 Sync All Playlists，与 iOS 对齐。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(
    onBackClick: () -> Unit,
    onNavigateToDetail: (String) -> Unit = {},
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val sortType by viewModel.sortType.collectAsState()
    val searchScope by viewModel.searchScope.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    // 含缓存歌曲的播放列表 id（长按菜单 Play/Shuffle 离线门控 + Delete Cache 显隐）
    val cachedPlaylistIds by viewModel.cachedPlaylistIds.collectAsState()
    // 全缓存播放列表集合（对应 iOS isCachedCompletely）：长按菜单据此隐藏 Download
    val fullyCachedPlaylistIds by viewModel.fullyCachedPlaylistIds.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    // 大标题向上滚动后收缩为导航栏居中标题（对齐 iOS PlaylistsVC largeTitle）
    val showCollapsedTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    var showOptionsMenu by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    // 作用域行仅在搜索激活时显示（iOS scope buttons 默认行为）；隐藏时重置回 All
    val showScopeBar = searchState.isPinned || searchText.isNotEmpty()
    LaunchedEffect(showScopeBar) {
        if (!showScopeBar) viewModel.setSearchScope(PlaylistsSearchScope.ALL)
    }

    // All/Cached 作用域段（钉顶头与列表内共用同一渲染）
    val scopeRow: @Composable () -> Unit = {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            PlaylistsSearchScope.entries.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = searchScope == value,
                    onClick = { viewModel.setSearchScope(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, PlaylistsSearchScope.entries.size),
                    label = { Text(if (value == PlaylistsSearchScope.ALL) "All" else "Cached") }
                )
            }
        }
    }

    val swipeController = rememberSwipeController()

    // 滑动动作配置（来自设置，经 SwipeDisplaySettings 过滤；播放列表不可收藏）
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val leadingSwipeActions = remember(swipeSettings.leading, isOfflineMode) {
        SwipeDisplaySettings.filter(
            swipeSettings.leading, SwipeContentType.MUSIC, isOfflineMode, isFavoritable = false
        )
    }
    val trailingSwipeActions = remember(swipeSettings.trailing, isOfflineMode) {
        SwipeDisplaySettings.filter(
            swipeSettings.trailing, SwipeContentType.MUSIC, isOfflineMode, isFavoritable = false
        )
    }

    // 离线模式退出时关闭编辑模式（对应 iOS viewIsAppearing: isOfflineMode -> isEditing = false）
    LaunchedEffect(isOfflineMode) {
        if (isOfflineMode) isEditMode = false
    }

    Scaffold(
        topBar = {
            // 钉顶态：导航栏收起，钉顶搜索头贴页面最顶（列表经 innerPadding 落其下方）
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Playlists\"",
                    scopeContent = scopeRow
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                // title 由下方 large title 收缩驱动（见 showCollapsedTitle）
                title = if (showCollapsedTitle) "Playlists" else null,
                actions = {
                    // 编辑模式切换（删除播放列表）—— 仅在线模式可用
                    // 对应 iOS: editButtonItem（位于 More 按钮左侧）
                    if (!isOfflineMode) {
                        // 编辑中渲染为对号图标（iOS editButtonItem 在编辑态即系统 .done 对号），
                        // 非编辑态为 "Edit" 文字按钮
                        if (isEditMode) {
                            // iOS 用系统 `editButtonItem`（PlaylistsVC.swift:251），Edit/Done
                            // 两态都是**纯文字**按钮，无图标
                            TextButton(onClick = { isEditMode = false }) {
                                Text("Done")
                            }
                        } else {
                            TextButton(onClick = { isEditMode = true }) {
                                Text("Edit")
                            }
                        }
                    }
                    // More 菜单（Sort 子菜单 + Options）—— 对应 iOS: SortBarButton
                    // 复用 AlbumsScreen 的 iOS 风格两级菜单组件
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(AmperfyIcons.ellipsis, contentDescription = "More")
                        }
                        IOSStyleContextMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(-72, 48),
                            items = listOf(
                                // Sort 子菜单
                                IOSContextMenuItem.Submenu(
                                    text = "Sort",
                                    icon = AmperfyIcons.sort,
                                    items = PlaylistSortType.entries.map { type ->
                                        IOSContextMenuItem.Action(
                                            text = type.displayName,
                                            icon = if (type == sortType) AmperfyIcons.check else null,
                                            onClick = { viewModel.setSortType(type) }
                                        )
                                    }
                                ),
                                IOSContextMenuItem.Divider,
                                // Options：同步所有播放列表
                                IOSContextMenuItem.Action(
                                    text = "Sync All Playlists",
                                    icon = AmperfyIcons.refresh,
                                    onClick = { viewModel.syncAllPlaylists() }
                                )
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        // 下拉刷新（对应 iOS PlaylistsVC refreshControl -> syncDownPlaylistsWithoutSongs）
        IOSPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.syncFromServer() },
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
                // iOS 风格大标题（向上滚动后收缩为导航栏居中标题；样式与 Genres/Downloads 统一）；
                // 钉顶时收起内容但保留 item 占位
                item {
                    if (!searchState.isPinned) {
                        IOSLargeTitle(
                            text = "Playlists",
                            modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                        )
                    }
                }

                // 搜索栏 + 作用域（对应 iOS configureSearchController，PlaylistsVC.swift:131；
                // 作用域段 = scopeButtonTitles，仅搜索激活时显示）——恰好一个 item，
                // 显隐/钉顶/焦点行为全在 LibrarySearchState 内
                librarySearchBarItem(
                    state = searchState,
                    searchText = searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Playlists\"",
                    scopeContent = scopeRow
                )

                // 计数行不渲染：iOS PlaylistsVC **没有**这类头部计数行
                // （全仓同源清扫，判据见 LibraryElementDetailTableHeaderView.swift:119-120——
                //  该类计数行在 iPhone 竖屏 compact 下恒隐藏）

                if (playlists.isEmpty()) {
                    // 空态为内容高度条目（fillParentMaxWidth + 固定顶距，与 FavoriteSongs 一致），
                    // 不用 fillParentMaxSize——后者会使页面可滚出一整屏空白、文案偏离视觉中心
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(top = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    AmperfyIcons.playlist,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (searchText.isNotEmpty()) "No results found" else "No Playlists",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // 列表区与上方控件（标题/搜索栏）的交界线 = iOS .grouped 表的
                    // section 顶边界线，恒全宽（0..0），不吃 separatorInset
                    item {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator  // iOS .separator
                        )
                    }

                    itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            coverArtUrl = viewModel.getCoverArtUrl(playlist.coverArt),
                            isEditMode = isEditMode,
                            swipeController = swipeController,
                            leadingActions = leadingSwipeActions,
                            trailingActions = trailingSwipeActions,
                            onClick = { onNavigateToDetail(playlist.id) },
                            onSwipeAction = { action -> viewModel.handleSwipeAction(playlist, action) },
                            onDelete = { playlistToDelete = playlist },
                            hasCachedSongs = playlist.id in cachedPlaylistIds,
                            isFullyCached = playlist.id in fullyCachedPlaylistIds,
                            // 末行的 16dp inset 线让位给下方全宽 section 底边界线
                            showDivider = index < playlists.lastIndex
                        )
                    }

                    // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                    //（iOS 实机对照 2026-08-10）
                    item {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator  // iOS .separator
                        )
                    }
                }
            }
        }
    }

    // 删除播放列表确认
    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete Playlist") },
            text = { Text("Are you sure you want to delete \"${playlist.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(playlist)
                    playlistToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // 删除缓存确认（滑动 REMOVE_FROM_CACHE 触发）
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { songs ->
        DeleteCacheConfirmDialog(
            songCount = songs.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteCache() }
        )
    }

    // 添加到播放列表选择器（滑动 ADD_TO_PLAYLIST 触发）
    val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
    pendingPlaylistSongIds?.let { ids ->
        PlaylistSelectorDialog(
            songIds = ids,
            onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
        )
    }
}

/**
 * 单个播放列表行 - 对应 iOS: PlaylistTableCell
 *
 * 普通模式下包裹在 [SwipeableItem] 中支持左右滑动手势；
 * 编辑模式下左侧显示红色删除按钮（对应 iOS commit editingStyle .delete）。
 */
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    coverArtUrl: String?,
    isEditMode: Boolean,
    swipeController: SwipeController,
    leadingActions: List<SwipeActionType>,
    trailingActions: List<SwipeActionType>,
    onClick: () -> Unit,
    onSwipeAction: (SwipeActionType) -> Unit,
    onDelete: () -> Unit,
    hasCachedSongs: Boolean = false,
    // 该播放列表全部歌曲已缓存（对应 iOS isCachedCompletely）：菜单隐藏 Download
    isFullyCached: Boolean = false,
    // 行内 16dp inset 分隔线；仅列表末行传 false（那条线由页面末尾的全宽段底线承担）
    showDivider: Boolean = true
) {
    if (isEditMode) {
        // 编辑模式：左侧红色删除按钮，禁用滑动与导航
        PlaylistRowContent(
            playlist = playlist,
            coverArtUrl = coverArtUrl,
            onClick = {},
            showDivider = showDivider,
            leading = {
                IconButton(onClick = onDelete) {
                    Icon(
                        AmperfyIcons.minusCircleFill,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.systemRed
                    )
                }
            },
            showArrow = false
        )
    } else {
        SwipeableItem(
            key = playlist.id,
            swipeController = swipeController,
            leadingActions = leadingActions,
            trailingActions = trailingActions,
            onSwipeAction = onSwipeAction,
            isFavorite = false
        ) {
            PlaylistRowContent(
                playlist = playlist,
                coverArtUrl = coverArtUrl,
                onClick = onClick,
                showDivider = showDivider,
                onSwipeAction = onSwipeAction,
                hasCachedSongs = hasCachedSongs,
                isFullyCached = isFullyCached
            )
        }
    }
}

/**
 * 播放列表行布局（封面 + 名称 + 歌曲数 + 箭头），含底部分隔线
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRowContent(
    playlist: Playlist,
    coverArtUrl: String?,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    showArrow: Boolean = true,
    // 行内 16dp inset 分隔线；section 末行传 false（让位给全宽段底线）
    showDivider: Boolean = true,
    onSwipeAction: ((SwipeActionType) -> Unit)? = null,
    hasCachedSongs: Boolean = false,
    isFullyCached: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val settingsManager = LocalSettingsManager.current
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()

    // 行内封面的默认图：按主题色现画（iOS ArtworkType.playlist，Playlist.swift:482-484——
    // playlist 是九类里唯一 switchColors 反色的）。
    // **本实例只给行内这一个绘制目标用**，预览卡自己按类型建实例（见 EntityPreviewCard 注释）
    val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.PLAYLIST)

    // 长按预览菜单（对应 iOS contextMenuConfigurationForRowAt）；编辑模式不传
    // onSwipeAction，此时不响应长按（对齐 iOS 编辑态无 context menu）
    var showPreviewMenu by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }
    val menuItems = onSwipeAction?.let { action ->
        buildPlaylistContextMenuItems(
            env = MenuEnv(
                isOfflineMode = isOfflineMode,
                isShuffleActionEnabled = isShuffleActionEnabled,
                isShowDetailedInfo = isShowDetailedInfo
            ),
            hasCachedSongs = hasCachedSongs,
            isFullyCached = isFullyCached,
            onAction = action,
            onCopyId = {
                if (playlist.id.isNotEmpty()) {
                    clipboardManager.setText(AnnotatedString(playlist.id))
                }
            }
        )
    } ?: emptyList()

    // 行容器全宽：横向 16dp 落到行内 padding（iOS cell 全宽高亮：layoutMargins 在 cell
    // 内部，BasicTableCell 覆盖为 (9,16,9,16)，CommonScreenOperations.swift:41-47）；
    // 外层底色铺满全宽，滑动手势露出层才被行底色完整遮盖
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                // 行底色置于点击之前：点按水波纹仍绘于其上
                .background(MaterialTheme.colorScheme.background)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (onSwipeAction != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showPreviewMenu = true
                        }
                    }
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(4.dp))
            }

            AsyncImage(
                model = coverArtUrl,
                contentDescription = playlist.name,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
                placeholder = defaultArtwork,
                error = defaultArtwork,
                fallback = defaultArtwork
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${playlist.songCount} Song${if (playlist.songCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondaryLabel
                    ),
                    maxLines = 1
                )
            }

            if (showArrow) {
                Icon(
                    AmperfyIcons.chevronRight,
                    contentDescription = "Navigate",
                    tint = MaterialTheme.colorScheme.tertiaryLabel,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
        // separatorInset = cell layoutMargins 左右值，CommonScreenOperations.swift:41-47）；
        // section 末行不画，让位给页面末尾的全宽段底线
        if (showDivider) {
            HairlineDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.separator  // iOS .separator
            )
        }

        // 长按弹出：预览卡片 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt）
        IOSLongPressPreviewMenu(
            expanded = showPreviewMenu,
            onDismissRequest = { showPreviewMenu = false },
            anchorBoundsOnScreen = rowBoundsOnScreen,
            items = menuItems
        ) {
            EntityPreviewCard(
                coverArtModel = coverArtUrl,
                defaultArtworkType = DefaultArtworkType.PLAYLIST,
                title = playlist.name,
                // iOS Playlist 预览无副标题
                subtitle = null,
                info = playlistPreviewInfo(playlist, isShowDetailedInfo),
                // 对应 iOS performPreviewTransition 的 playlist 分支：点卡片进播放列表详情
                showChevron = true,
                onClick = {
                    showPreviewMenu = false
                    onClick()
                }
            )
        }
    }
}

// 注：原 PlaylistNameDialog（创建播放列表弹窗）已随 PlaylistSelectorDialog 对齐
// iOS NewPlaylistTableHeader（内联输入框 + Create）而移除，无其他调用方
