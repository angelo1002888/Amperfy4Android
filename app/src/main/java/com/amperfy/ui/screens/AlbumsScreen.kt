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
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.ui.components.IOSPullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.model.Album
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.AlbumGridItemSkeleton
import com.amperfy.ui.components.AlbumListItem
import com.amperfy.ui.components.AlbumListItemCallbacks
import com.amperfy.ui.components.AlbumListItemSkeleton
import com.amperfy.ui.components.AlbumLongPressPreviewMenu
import com.amperfy.ui.components.GroupedSectionHeader
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.LibrarySearchState
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.SearchBarSlot
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.swipe.DragAnchors
import com.amperfy.ui.components.swipe.SwipeController
import com.amperfy.ui.components.swipe.SwipeableAlbumListItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.SortSectionMode
import com.amperfy.ui.util.SortSectionUtils
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter
import kotlinx.coroutines.launch

/**
 * Albums Screen - iOS风格完整实现
 *
 * 功能特性：
 * - iOS风格大标题导航栏（滚动折叠）
 * - 返回按钮显示上级页面名称
 * - 搜索框固定在导航栏底部
 * - Table/Grid 视图切换
 * - 多种排序方式（Name, Artist, Year, Duration, Rating等）
 * - 显示过滤器（All, Newest, Recent, Favorites）
 * - 搜索功能
 * - 下拉刷新
 * - 播放/随机播放按钮
 * - 字母索引（Table视图）
 * - 可调节的Grid大小（2-5列）
 * - 下载所有专辑
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(
    onBackClick: () -> Unit = {},
    onAlbumClick: (Album) -> Unit = {},
    // 行/网格单元长按菜单 Show Artist 导航（对应 iOS configureFor(album:) isShowArtist）
    onNavigateToArtist: (String) -> Unit = {},
    viewModel: AlbumsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val albums by viewModel.filteredAlbums.collectAsState()
    val isLoadingData by viewModel.isLoadingData.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()
    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    // Table/Grid 两个变体共用同一 listState，故状态在外层建一份、整体下传。
    val searchState = rememberLibrarySearchState(listState)

    // 滑动动作配置（来自设置，经 SwipeDisplaySettings 过滤）
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val scope = rememberCoroutineScope()
    // 「Many Songs」确认（对应 iOS AlbumsCommonVCInteractions.swift:478-491 的 UIAlertController）
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
    // 全缓存专辑集合（对应 iOS isCachedCompletely）：长按菜单据此隐藏 Download
    val fullyCachedAlbumIds by viewModel.fullyCachedAlbumIds.collectAsState()
    val leadingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.leading, SwipeContentType.MUSIC, isOfflineMode)
    }
    val trailingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.trailing, SwipeContentType.MUSIC, isOfflineMode)
    }

    // 删除缓存确认对话框
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { songs ->
        DeleteCacheConfirmDialog(
            songCount = songs.size,
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

    var showGridSizeDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 监听滚动位置 - iOS风格折叠标题
    val showCollapsedTitle by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0
        }
    }

    Scaffold(
        topBar = {
            // 钉顶态：导航栏整体收起，钉顶搜索头贴页面最顶（对齐 iOS
            // hidesNavigationBarDuringPresentation = true）；列表/网格经 innerPadding 落其下方
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = searchQuery,
                    onSearchTextChanged = { searchQuery = it; viewModel.updateSearchText(it) },
                    placeholder = "Search in \"${uiState.sceneTitle}\""
                )
            } else
            // iOS风格的统一导航栏
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) uiState.sceneTitle else null,
                actions = {
                    // More菜单 - 横向三个点（iOS风格两级菜单）
                    Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(AmperfyIcons.ellipsis, "More")
                    }

                    // 主菜单 - iOS风格
                    IOSStyleContextMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        alignment = Alignment.TopEnd,  // 在按钮左下方展开
                        offset = IntOffset(-72, 48),
                        items = buildList {
                            // Sort 子菜单：Newest/Recent 模式不显示（服务器顺序即展示顺序）；
                            // 菜单只含 Name/Rating/Artist/Duration/Year
                            // 对应 iOS: AlbumsCommonVCInteractions.swift:227-231、:321
                            if (uiState.displayFilter != DisplayCategoryFilter.NEWEST &&
                                uiState.displayFilter != DisplayCategoryFilter.RECENT
                            ) {
                                add(IOSContextMenuItem.Submenu(
                                    text = "Sort",
                                    icon = AmperfyIcons.sort,
                                    items = AlbumSortType.entries
                                        .filter { it != AlbumSortType.NEWEST && it != AlbumSortType.RECENT }
                                        .map { sortType ->
                                            IOSContextMenuItem.Action(
                                                text = sortType.getDisplayName(),
                                                icon = if (uiState.sortType == sortType) AmperfyIcons.check else null,
                                                onClick = { viewModel.changeSortType(sortType) }
                                            )
                                        }
                                ))
                            }
                            // Style 子菜单
                            add(IOSContextMenuItem.Submenu(
                                text = "Style",
                                icon = if (uiState.styleType == AlbumStyleType.GRID)
                                    AmperfyIcons.grid
                                else
                                    AmperfyIcons.listBullet,
                                items = listOf(
                                    IOSContextMenuItem.Action(
                                        text = "Table",
                                        icon = if (uiState.styleType == AlbumStyleType.TABLE) AmperfyIcons.check else AmperfyIcons.listBullet,
                                        onClick = { viewModel.changeStyleType(AlbumStyleType.TABLE) }
                                    ),
                                    IOSContextMenuItem.Action(
                                        text = "Grid",
                                        icon = if (uiState.styleType == AlbumStyleType.GRID) AmperfyIcons.check else AmperfyIcons.grid,
                                        onClick = { viewModel.changeStyleType(AlbumStyleType.GRID) }
                                    ),
                                    IOSContextMenuItem.Divider,
                                    IOSContextMenuItem.Action(
                                        text = "Change Grid Size",
                                        icon = AmperfyIcons.resize,
                                        onClick = {
                                            if (uiState.styleType == AlbumStyleType.GRID) {
                                                showGridSizeDialog = true
                                            }
                                        }
                                    )
                                )
                            ))
                            // Download <filterTitle>——**全部 filter 变体都有**，仅在线模式
                            // （iOS AlbumsCommonVCInteractions.swift:457-495 的
                            //  createActionButtonMenu，各 displayFilter 分支均取该过滤集的专辑歌曲）
                            if (!isOfflineMode) {
                                add(IOSContextMenuItem.Divider)
                                add(IOSContextMenuItem.Action(
                                    text = "Download ${uiState.filterTitle}",
                                    icon = AmperfyIcons.download,
                                    onClick = {
                                        scope.launch {
                                            val songs = viewModel.collectFilteredAlbumSongs()
                                            if (songs.size > MANY_SONGS_WARNING_THRESHOLD) {
                                                manySongs = songs
                                            } else {
                                                viewModel.downloadSongs(songs)
                                            }
                                        }
                                    }
                                ))
                            }
                        }
                    )
                    }
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
                // 根据视图类型使用不同的布局策略
                // 始终显示相应的视图，内部根据 isLoadingData 和 albums.isEmpty() 处理状态
                if (uiState.styleType == AlbumStyleType.TABLE) {
                    // Table视图：使用带有大标题和搜索栏的LazyColumn
                    AlbumsTableViewWithHeader(
                        sceneTitle = uiState.sceneTitle,
                        albums = albums,
                        searchQuery = searchQuery,
                        onSearchQueryChange = {
                            searchQuery = it
                            viewModel.updateSearchText(it)
                        },
                        sortType = uiState.sortType,
                        onAlbumClick = onAlbumClick,
                        onPlayClick = { viewModel.handleHeaderPlay() },
                        onShuffleClick = { viewModel.handleHeaderShuffle() },
                        onItemVisible = { index ->
                            viewModel.listViewWillDisplayCell(index, searchQuery.ifEmpty { null })
                        },
                        onSwipeAction = { album, action ->
                            viewModel.handleSwipeAction(album, action)
                        },
                        onSetRating = { album, rating ->
                            viewModel.setRating(album, rating)
                        },
                        onNavigateToArtist = onNavigateToArtist,
                        fullyCachedAlbumIds = fullyCachedAlbumIds,
                        leadingSwipeActions = leadingSwipeActions,
                        trailingSwipeActions = trailingSwipeActions,
                        listState = listState,
                        miniPlayerHeight = miniPlayerHeight,
                        isLoadingData = isLoadingData,
                        displayFilter = uiState.displayFilter,
                        isSearching = searchQuery.isNotEmpty(),
                        searchState = searchState
                    )
                } else {
                    // Grid视图：使用LazyColumn包装以支持sticky headers
                    AlbumsGridViewWithHeader(
                        sceneTitle = uiState.sceneTitle,
                        albums = albums,
                        searchQuery = searchQuery,
                        onSearchQueryChange = {
                            searchQuery = it
                            viewModel.updateSearchText(it)
                        },
                        gridSize = uiState.gridSize,
                        sortType = uiState.sortType,
                        onAlbumClick = onAlbumClick,
                        onPlayClick = { viewModel.handleHeaderPlay() },
                        onShuffleClick = { viewModel.handleHeaderShuffle() },
                        onItemVisible = { index ->
                            viewModel.listViewWillDisplayCell(index, searchQuery.ifEmpty { null })
                        },
                        // 网格单元长按菜单与表格行同源（iOS BasicCollectionViewController
                        // 与 BasicTableViewController 提供同一份 album context menu）
                        onSwipeAction = { album, action ->
                            viewModel.handleSwipeAction(album, action)
                        },
                        onSetRating = { album, rating ->
                            viewModel.setRating(album, rating)
                        },
                        onNavigateToArtist = onNavigateToArtist,
                        fullyCachedAlbumIds = fullyCachedAlbumIds,
                        listState = listState,
                        miniPlayerHeight = miniPlayerHeight,
                        isLoadingData = isLoadingData,
                        displayFilter = uiState.displayFilter,
                        isSearching = searchQuery.isNotEmpty(),
                        searchState = searchState
                    )
                }
            }
        }

        // Grid大小对话框
        if (showGridSizeDialog) {
            GridSizeDialog(
                currentSize = uiState.gridSize,
                onSizeChange = { viewModel.changeGridSize(it) },
                onDismiss = { showGridSizeDialog = false }
            )
        }
    }
}

/**
 * 播放控制区域 - iOS风格
 */
@Composable
private fun AlbumPlayControls(
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPlayButtons: Boolean = true
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp) // 上下行间距
    ) {
        // modifier 同时作用于外层 Column 与本行（沿用原实现，未改）
        if (showPlayButtons) PlayShuffleButtons(
            onPlay = onPlayClick,
            onShuffle = onShuffleClick,
            modifier = modifier,
            // iOS AlbumsCommonVCInteractions.swift:560 传 false → 文案 "Random"
            isShuffleOnContextNecessary = false
        )

        // 计数信息行**不渲染**：iOS `LibraryElementDetailTableHeaderView` 的
        // `infoContainerView.isHidden = isInfoAlwaysHidden || horizontalSizeClass == .compact`
        //（LibraryElementDetailTableHeaderView.swift:119-120）——iPhone 竖屏恒为 compact，
        // 故该行在 iPhone 上永远看不到（只在 iPad/regular 宽度出现）。
        // 注意：各**详情页**（Album/Artist/Playlist/Podcast Detail）的 info 行是另一套
        // `GenericDetailTableHeader.infoLabel`（规则为 isHidden = infoText.isEmpty），
        // 在 iPhone 上照常显示，不在本清扫范围内
    }
}

/**
 * GRID 网格行的**基础** end 内边距。
 *
 * 20dp 是 A-Z 索引条档的 iOS 真机对照参照值（2026-08-13 第三轮实机对照：该现状与 iOS 实机
 * 间距几乎一致），同时也是 LIST 侧 `rememberIndexBarRowEndPadding` 字形间隙公式的标定基准。
 * year/duration 宽标签档由 `maxOf` 抬到公式值（第四轮判据：这两档原先仍与索引条重叠）。
 */
private val GRID_ROW_END_PADDING = 20.dp

/**
 * 按当前排序档把专辑切成段（键 = [albumGridSectionKey]，**GRID 专用**）。
 *
 * 段序：rating/year/duration 三档**沿列表既有顺序**（ViewModel 已按 iOS FRC 方向排好——
 * rating/year `ascending: false`、duration `ascending: true`，
 * AlbumMO+CoreDataClass.swift:83-98/:169-177/:187-205），`groupBy` 返回的 LinkedHashMap
 * 保持首现序，故**不得再排**；字母档沿用既有的「# 排最后」显式排序。
 * 不分段档（newest/recent）返回单个空键段，调用方据此不画段头。
 */
private fun albumSections(
    albums: List<Album>,
    sortType: AlbumSortType
): Map<String, List<Album>> = when (sortType.sectionMode) {
    SortSectionMode.NONE -> linkedMapOf("" to albums)
    SortSectionMode.ALPHABET -> albums
        .groupBy { album -> albumGridSectionKey(album, sortType) }
        .toSortedMap(compareBy { letter -> if (letter == "#") "ZZZ" else letter })
    else -> albums.groupBy { album -> albumGridSectionKey(album, sortType) }
}

/**
 * GRID 段头是否画标题 —— iOS `AlbumsCollectionVC.swift:79-87`：
 * name/artist（首字母）、rating（"N Stars"）、year（年份）画；
 * duration/newest/recent 为 `display(title: nil)` 不画（duration 分段只服务索引与段边界）。
 */
private fun AlbumSortType.hasGridSectionHeader(): Boolean = when (sectionMode) {
    SortSectionMode.ALPHABET, SortSectionMode.RATING, SortSectionMode.YEAR -> true
    SortSectionMode.DURATION_ALBUM, SortSectionMode.DURATION_SONG, SortSectionMode.NONE -> false
}

/**
 * Grid视图模式（带大标题和搜索栏）- iOS风格
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumsGridViewWithHeader(
    sceneTitle: String,
    albums: List<Album>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    gridSize: Int,
    // 当前排序档：段头分组模式与索引条标签全由它派生（iOS AlbumsCollectionVC.swift:79-87/:95-131）
    sortType: AlbumSortType,
    onAlbumClick: (Album) -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onItemVisible: (Int) -> Unit,
    // 网格单元长按上下文菜单：动作走与表格行同一条 Album.handleSwipeAction 路径
    onSwipeAction: (Album, SwipeActionType) -> Unit,
    onSetRating: (Album, Int) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    // 全缓存专辑集合（对应 iOS isCachedCompletely）：菜单隐藏 Download
    fullyCachedAlbumIds: Set<String>,
    listState: LazyListState,
    miniPlayerHeight: Dp,
    isLoadingData: Boolean,
    displayFilter: DisplayCategoryFilter,
    isSearching: Boolean,
    // 搜索栏状态（外层建一份、两变体共用）：显隐/钉顶/焦点行为全在其内部
    searchState: LibrarySearchState
) {
    val showIndexTitles = !sortType.isIndexTitlesHidden
    val showGridSectionHeader = sortType.hasGridSectionHeader()

    // 段分组随排序档切换（对应 iOS AlbumsCollectionVC.swift:79-87：
    // name/artist → 首字母、rating → "N Stars"/"Not rated"、year → 年份/"#"、
    // duration → 时长桶但 display(title: nil) 不画标题、newest/recent → 不分段）
    val groupedAlbums: Map<String, List<Album>> = remember(albums, sortType) {
        albumSections(albums, sortType)
    }

    // 索引条标签集：year/duration 档由当前数据现算（值域无穷），其余用固定表
    val albumIndexBarLabels = remember(albums, sortType) {
        SortSectionUtils.indexLabels(sortType.sectionMode, albums) { albumIndexLabel(it, sortType) }
    }

    // 网格行的 end 内边距：**A-Z 档恒为既有的 20dp**（iOS 实机观测 2026-08-13 第三轮：
    // 该现状与 iOS 间距几乎一致，且是 LIST 侧字形间隙公式的标定参照），year/duration 宽标签档
    // 经 maxOf 走与 LIST 同一公式让位（第四轮真机判据：GRID 这两档原先仍与索引条重叠）。
    // 条件与下方 AlphabetIndex 的渲染条件同一个布尔；4.dp = 该页索引条自身的 padding(end = 4.dp)
    val indexBarRowEndPadding = com.amperfy.ui.components.rememberIndexBarRowEndPadding(
        albumIndexBarLabels,
        indexBarEndPadding = 4.dp
    )
    val gridRowEndPadding = if (showIndexTitles && albums.isNotEmpty()) {
        maxOf(GRID_ROW_END_PADDING, indexBarRowEndPadding)
    } else {
        GRID_ROW_END_PADDING
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(searchState.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = miniPlayerHeight + 16.dp)
        ) {
            // 前置 item 1/3：大标题（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
            // iOS风格的大标题 - 随滚动消失；钉顶时收起内容但保留 item 占位（字母索引 index 约定）
            item(key = "title") {
                if (!searchState.isPinned) {
                    IOSLargeTitle(
                        text = sceneTitle,
                        modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                    )
                }
            }

            // 前置 item 2/3：搜索栏 + 播放控制（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
            // 普通 item（不钉住，对齐 iOS 表头随滚动移动）；搜索栏与控制区同处一个 item，
            // 故不用列表版发射器，直接调其内核 SearchBarSlot（控制区始终可见）
            item(key = "searchAndControls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding( start = 16.dp, end = 20.dp, top = 2.dp, bottom = 4.dp )
                ) {
                    searchState.SearchBarSlot(
                        searchText = searchQuery,
                        onSearchTextChanged = onSearchQueryChange,
                        placeholder = "Search in \"$sceneTitle\"",  // iOS: Search in "<filterTitle>"（随过滤器）
                        horizontalPadding = 0.dp,
                        verticalPadding = 0.dp,
                        bottomSpacing = 8.dp
                    )

                    // 播放控制区域 - 始终可见随内容滚动
                    AlbumPlayControls(
                        onPlayClick = onPlayClick,
                        onShuffleClick = onShuffleClick,
                        // 播放逻辑已接通（handleHeaderPlay/Shuffle 基于 filteredAlbums，
                        // 各 filter 模式通用），对齐 iOS 各模式均显示 Play/Shuffle
                        showPlayButtons = true
                    )
                }
            }

            // 前置 item 3/3：分隔线（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount，
            // 并同步下方 letterToIndexMap 的内容起始 index）
            // 列表区与上方控件的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）
            item(key = "sectionTopDivider") {
                HairlineDivider(
                    color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                )
            }

            // Grid内容 或 骨架屏/空状态
            when {
                isLoadingData -> {
                    // 显示骨架屏
                    items(4) { rowIndex ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (i in 0 until gridSize) {
                                Box(modifier = Modifier.weight(1f)) {
                                    AlbumGridItemSkeleton()
                                }
                            }
                        }
                    }
                }
                albums.isEmpty() -> {
                    // 显示空状态占位符
                    item { EmptyAlbumsContent(displayFilter = displayFilter, isSearching = isSearching) }
                }
                else -> {
                    // 显示实际的Grid内容
                    groupedAlbums.forEach { (sectionKey, albumsInSection) ->
                        // Section Header（标题随排序档：首字母 / "N Stars" / 年份；
                        // duration 与不分段档不画，见 hasGridSectionHeader）
                        if (showGridSectionHeader && sectionKey.isNotEmpty()) {
                            item(key = "header_$sectionKey") {
                                AlbumSectionHeader(title = sectionKey)
                            }
                        }

                        // Grid items for this section - 按行组织
                        val rowCount = (albumsInSection.size + gridSize - 1) / gridSize
                        items(
                            count = rowCount,
                            key = { rowIndex -> "grid_row_${sectionKey}_$rowIndex" }
                        ) { rowIndex ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // end 见 [gridRowEndPadding]：A-Z 档恒 20dp（iOS 真机参照值，
                                    // 也是 LIST 字形间隙公式的标定基准），year/duration 宽标签档
                                    // 按同一公式让位（第四轮真机判据：原先仍重叠）
                                    .padding(
                                        start = 16.dp,
                                        end = gridRowEndPadding,
                                        top = 8.dp,
                                        bottom = 8.dp
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                for (i in 0 until gridSize) {
                                    val itemIndex = rowIndex * gridSize + i
                                    if (itemIndex < albumsInSection.size) {
                                        val album = albumsInSection[itemIndex]
                                        val globalIndex = albums.indexOf(album)

                                        Box(modifier = Modifier.weight(1f)) {
                                            LaunchedEffect(Unit) {
                                                onItemVisible(globalIndex)
                                            }

                                            AlbumGridItem(
                                                album = album,
                                                onClick = { onAlbumClick(album) },
                                                onSwipeAction = { action -> onSwipeAction(album, action) },
                                                onSetRating = { rating -> onSetRating(album, rating) },
                                                onShowArtist = album.artistId?.let { artistId ->
                                                    { onNavigateToArtist(artistId) }
                                                },
                                                isFullyCached = album.id in fullyCachedAlbumIds
                                            )
                                        }
                                    } else {
                                        // 空白占位
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                    //（iOS 实机对照 2026-08-10；GRID 行之间无行间线，故只补这一条）
                    item(key = "sectionBottomDivider") {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                        )
                    }
                }
            }
        }

        // 索引条（iOS风格）- Grid 视图同样显示（标签随排序档；仅不分段的 Newest/Recent 无索引）
        if (showIndexTitles && albums.isNotEmpty()) {
            // 创建「索引标签 → 绝对 lazy index」映射（AlphabetIndex 按此定位段首）。
            // 键用**完整标签**：rating 段头是 "5 Stars" 而标签是 "5"，duration 桶标签
            // "1:00:00"/"1:40:00" 首字符还会撞车，故不能取首字
            val letterToIndexMap = remember(groupedAlbums, gridSize, sortType, showGridSectionHeader) {
                val map = mutableMapOf<String, Int>()
                // 前置 item：0=title、1=searchAndControls、2=section 顶边界线，共 3
                //（须与下方 AlphabetIndex 的 leadingItemCount 一致）；内容从 index 3 起
                var currentIndex = 3
                groupedAlbums.forEach { (_, albumsInSection) ->
                    val first = albumsInSection.firstOrNull() ?: return@forEach
                    // 同一索引标签可能对应多段（duration 桶按 GRID 分组已合并，此处取首段）
                    map.putIfAbsent(albumIndexLabel(first, sortType), currentIndex)
                    // 每个 section 含：0/1 个 header（duration 档不画）+ n 个 grid 行
                    currentIndex += (if (showGridSectionHeader) 1 else 0) +
                        ((albumsInSection.size + gridSize - 1) / gridSize)
                }
                map
            }

            com.amperfy.ui.components.AlphabetIndex(
                items = albums,
                listState = listState,
                getIndexLetter = { album -> albumIndexLabel(album, sortType) },
                isGridView = true,
                letterToIndexMap = letterToIndexMap,
                // rating 档为固定 "5".."1"/"#"；year/duration 档由数据现算；字母档 null = A-Z + #
                letters = albumIndexBarLabels,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                // 前置 item：title / searchAndControls / divider = 3（须与上方 LazyColumn 结构同步）
                leadingItemCount = 3
            )
        }
    }
}

/**
 * Table视图模式（带大标题和搜索栏）- iOS风格
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumsTableViewWithHeader(
    sceneTitle: String,
    albums: List<Album>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    // 当前排序档：段头（仅 rating/year 两档有）与索引条标签全由它派生
    //（iOS AlbumsVC.swift:285-296 的 heightForHeaderInSection、:76-106 的 sectionIndexTitles）
    sortType: AlbumSortType,
    onAlbumClick: (Album) -> Unit,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onItemVisible: (Int) -> Unit,
    onSwipeAction: (Album, SwipeActionType) -> Unit,
    // 长按上下文菜单 Rating 调色板（对应 iOS createRatingMenu）
    onSetRating: (Album, Int) -> Unit,
    // 长按上下文菜单 Show Artist 导航（对应 iOS createShowArtistAction）
    onNavigateToArtist: (String) -> Unit,
    // 全缓存专辑集合（对应 iOS isCachedCompletely）：菜单隐藏 Download
    fullyCachedAlbumIds: Set<String>,
    leadingSwipeActions: List<SwipeActionType>,
    trailingSwipeActions: List<SwipeActionType>,
    listState: LazyListState,
    miniPlayerHeight: Dp,
    isLoadingData: Boolean,
    displayFilter: DisplayCategoryFilter,
    isSearching: Boolean,
    // 搜索栏状态（外层建一份、两变体共用）：显隐/钉顶/焦点行为全在其内部
    searchState: LibrarySearchState
) {
    val showIndexTitles = !sortType.isIndexTitlesHidden

    // 逐行段头标题：**仅 rating/year 两档**有段头（iOS AlbumsVC.swift:285-296 该两档返回
    // tableSectionHeightLarge=40，artist/duration/name/newest/recent 一律高 0 = 平铺无段头）。
    // 段头在段首行的 item 内联发射（见下方 items 块），故 lazy item 数仍等于 albums.size，
    // 索引条沿用 items 一一对应的定位方式，无须 letterToIndexMap
    val sectionTitles: List<String>? = remember(albums, sortType) {
        when (sortType.sectionMode) {
            SortSectionMode.RATING, SortSectionMode.YEAR ->
                albums.map { albumSectionTitle(it, sortType) }
            else -> null
        }
    }

    // 逐行段边界键（**行间线形态判定**：与下一行键不同 → 该行下方的线画全宽 = grouped 段边界线；
    // 同段内仍为 16dp inset）。null = 该档全部 16dp inset
    //（iOS 实机观测 2026-08-10：artist/rating/year/duration 段间全宽，name 例外；
    //  实现与 GenresScreen 字母组间全宽同构——比对相邻两行的段键）
    val rowSectionKeys: List<String>? = remember(albums, sortType) {
        if (sortType.hasFullWidthSectionDividers) {
            albums.map { albumRowSectionKey(it, sortType) }
        } else {
            null
        }
    }

    // 索引条标签集：year/duration 档由当前数据现算（值域无穷），其余用固定表
    val albumIndexBarLabels = remember(albums, sortType) {
        SortSectionUtils.indexLabels(sortType.sectionMode, albums) { albumIndexLabel(it, sortType) }
    }

    // 索引条可见性（与下方 AlphabetIndex 的渲染条件同一个布尔）与行内容避让宽度：
    // 索引条可见时行内容 end 内边距改为「条偏移+(条宽+最宽字形宽)/2+间隙」替代 16dp，
    // 分割线/行容器仍全宽（= iOS UITableView 在 sectionIndexTitles 非空时收窄 cell.contentView，
    // iOS 实机观测 2026-08-13，见 LocalListRowTrailingInset）；
    // A-Z 档算得 ≈20dp，与 GRID 网格行 end = 20dp 的 iOS 参照值一致（GRID 的 A-Z 档保持
    // 该现状；其 year/duration 宽标签档经 maxOf 走同一公式让位，见 [gridRowEndPadding]）
    val isIndexBarVisible = showIndexTitles && albums.isNotEmpty()
    // 该页索引条自身相对屏幕右缘偏移 4dp（见下方 AlphabetIndex 的 padding(end = 4.dp)）
    val indexBarRowEndPadding = com.amperfy.ui.components.rememberIndexBarRowEndPadding(
        albumIndexBarLabels,
        indexBarEndPadding = 4.dp
    )
    val rowTrailingInset = if (isIndexBarVisible) indexBarRowEndPadding else 0.dp

    // 记录Table视图的item数量到日志
    LaunchedEffect(albums.size) {
        android.util.Log.d("AlbumsScreen", "Table view items count: ${albums.size}")
    }

    // 滑动控制器 - 确保同时只有一个 item 处于滑出状态
    val swipeController = rememberSwipeController()
    val coroutineScope = rememberCoroutineScope()

    // 处理滚动时关闭打开的滑动项
    val nestedScrollConnection = remember(swipeController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // 当用户开始滚动时，关闭当前打开的滑动项
                if (available.y != 0f) {
                    swipeController.closeCurrentItem(coroutineScope)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
            // iOS风格的大标题 - 随滚动消失；钉顶时收起内容但保留 item 占位
            item {
                if (!searchState.isPinned) {
                    IOSLargeTitle(
                        text = sceneTitle,
                        modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                    )
                }
            }

            // 前置 item 2/3：搜索栏 + 播放控制（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
            // 普通 item（不钉住，对齐 iOS 表头随滚动移动）；搜索栏与控制区同处一个 item，
            // 故不用列表版发射器，直接调其内核 SearchBarSlot（控制区始终可见）
            item(key = "searchAndControls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(
                            start = 16.dp,
                            end = if (showIndexTitles) 20.dp else 16.dp,  // 为字母索引留出空间
                            top = 2.dp,
                            bottom = 4.dp
                        )
                ) {
                    searchState.SearchBarSlot(
                        searchText = searchQuery,
                        onSearchTextChanged = onSearchQueryChange,
                        placeholder = "Search in \"$sceneTitle\"",  // iOS: Search in "<filterTitle>"（随过滤器）
                        horizontalPadding = 0.dp,
                        verticalPadding = 0.dp,
                        bottomSpacing = 8.dp
                    )

                    // 播放控制区域 - 始终可见随内容滚动
                    AlbumPlayControls(
                        onPlayClick = onPlayClick,
                        onShuffleClick = onShuffleClick,
                        // 播放逻辑已接通（handleHeaderPlay/Shuffle 基于 filteredAlbums，
                        // 各 filter 模式通用），对齐 iOS 各模式均显示 Play/Shuffle
                        showPlayButtons = true
                    )
                }
            }

            // 前置 item 3/3：分隔线（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
            // 列表区与上方控件的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）
            item(key = "sectionTopDivider") {
                HairlineDivider(
                    color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                )
            }

            // Album列表items 或 骨架屏/空状态
            when {
                isLoadingData -> {
                    // 显示骨架屏
                    items(10) { index ->
                        AlbumListItemSkeleton(showDivider = index < 9)
                    }
                }
                albums.isEmpty() -> { // 显示空状态占位符
                    item { EmptyAlbumsContent(displayFilter = displayFilter, isSearching = isSearching) }
                }
                else -> {
                    // 显示实际的专辑列表 - 使用滑动组件
                    items(
                        count = albums.size,
                        key = { index -> "album_${albums[index].id}" }
                    ) { index ->
                        val album = albums[index]

                        // 通知item可见
                        LaunchedEffect(Unit) {
                            onItemVisible(index)
                        }

                        Column {
                            // rating/year 档：段首行上方内联 40dp grouped 段头
                            //（iOS AlbumsVC.swift:47-74 供标题、:285-296 供高度）
                            if (sectionTitles != null &&
                                (index == 0 || sectionTitles[index - 1] != sectionTitles[index])
                            ) {
                                GroupedSectionHeader(title = sectionTitles[index])
                            }

                            // 使用 SwipeableAlbumListItem 替代 AlbumListItem
                            SwipeableAlbumListItem(
                                album = album,
                                onClick = { onAlbumClick(album) },
                                swipeController = swipeController,
                                leadingActions = leadingSwipeActions,
                                trailingActions = trailingSwipeActions,
                                onSwipeAction = { action -> onSwipeAction(album, action) },
                                onSetRating = { rating -> onSetRating(album, rating) },
                                // 专辑无 artistId 时无处可跳，整项省略（对应 iOS isShowArtist 门控）
                                onShowArtist = album.artistId?.let { artistId ->
                                    { onNavigateToArtist(artistId) }
                                },
                                isFullyCached = album.id in fullyCachedAlbumIds,
                                // 需要区分段间/段内两种线形态时，行内不画、改由本 item 统一发射；
                                // 否则沿用行内 16dp inset 线（末行不画，让位给下方全宽段底线）
                                showDivider = rowSectionKeys == null && index < albums.lastIndex
                            )

                            // 段间全宽 / 段内 16dp inset（形态判定见 rowSectionKeys 注释）；
                            // 末行不画，由列表末尾的全宽段底线承担
                            if (rowSectionKeys != null && index < albums.lastIndex) {
                                val isSectionBoundary =
                                    rowSectionKeys[index] != rowSectionKeys[index + 1]
                                HairlineDivider(
                                    modifier = if (isSectionBoundary) Modifier
                                    else Modifier.padding(start = 16.dp),
                                    color = MaterialTheme.colorScheme.separator
                                )
                            }
                        }
                    }

                    // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                    //（iOS 实机对照 2026-08-10）
                    item(key = "sectionBottomDivider") {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                        )
                    }
                }
            }
        }
        }

        // 索引条（iOS风格）：标签随排序档（name/artist=首字母、rating="5".."1"/"#"、
        // year=年份、duration=时长桶）；仅 newest/recent 无索引（iOS AlbumsVC.swift:76-106）
        if (isIndexBarVisible) {
            com.amperfy.ui.components.AlphabetIndex(
                items = albums,
                listState = listState,
                getIndexLetter = { album -> albumIndexLabel(album, sortType) },
                letters = albumIndexBarLabels,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                // 前置 item：title / searchAndControls / divider = 3（须与上方 LazyColumn 结构同步）
                leadingItemCount = 3
            )
        }
    }
}

/**
 * Section Header（**仅 GRID 样式使用**）
 *
 * 对应 iOS `CommonCollectionSectionHeader`（AlbumsCollectionVC.swift:79-87）——标题**随排序档**：
 * name/artist 为首字母（经 `sectionTitleToIndexTitle`）、rating 为 "N Stars"/"Not rated"、
 * year 为年份/"#"、duration/newest/recent 为 `display(title: nil)` 无标题。
 * Android 侧的标题即 GRID 分组键 [albumGridSectionKey]（除 duration 外与段头标题同值），
 * duration 与不分段档整段不发射本组件
 *（iOS duration 档仍占 30pt 空段头，Android 不复刻）。
 * 规格逐项照 CommonCollectionSectionHeader.swift:26 与同名 .xib：
 * - 段头总高 30pt（`frameHeight = 30.0`）
 * - 字体 boldSystem 17pt（xib fontDescription type="boldSystem" pointSize="17"）
 * - 颜色为默认 UILabel 色 = `.label`（xib 未设 textColor）
 * - 文本左缘 28pt（xib label frame x=28，= leftMargin + constant 8）、贴段头底（bottom 4）
 *
 * 注意：LIST 样式**没有**字母段头——iOS AlbumsVC.swift:285-296 对
 * artist/duration/name/newest/recent 一律返回高 0，字母只出现在右侧索引条；
 * 该两档之外的 rating/year 在 LIST 下有 40pt 段头，用共享的
 * [com.amperfy.ui.components.GroupedSectionHeader]（本组件是 collection 专用的 30pt 规格，两者不通用）。
 */
@Composable
private fun AlbumSectionHeader(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // 段头总高 30dp（iOS CommonCollectionSectionHeader.frameHeight）
            .height(30.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.label, // iOS 默认 UILabel 色 = .label
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 左缘 28dp、底部 4dp（xib 约束）；右侧为字母索引条留白
            modifier = Modifier.padding(start = 28.dp, end = 40.dp, bottom = 4.dp)
        )
    }
}

/**
 * Grid模式专辑项 - iOS Music风格
 *
 * 长按弹预览卡 + 上下文菜单：对应 iOS BasicCollectionViewController
 * contextMenuConfigurationForItemAt——网格与表格提供同一份 album context menu，
 * 故此处复用 AlbumLongPressPreviewMenu（与表格行同源）。
 * morph 起始矩形取网格单元格自身 bounds（预览卡仍为全宽，iOS 网格同语义）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridItem(
    album: Album,
    onClick: () -> Unit,
    onSwipeAction: (SwipeActionType) -> Unit,
    onSetRating: (Int) -> Unit,
    onShowArtist: (() -> Unit)? = null,
    isFullyCached: Boolean = false
) {
    val credentialsManager = com.amperfy.ui.navigation.LocalCredentialsManager.current
    val musicRepository = com.amperfy.ui.navigation.LocalMediaUrlRepository.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // 长按预览菜单（对应 iOS contextMenuConfigurationForItemAt）
    var showPreviewMenu by remember { mutableStateOf(false) }
    var cellBoundsOnScreen by remember {
        mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                // 屏幕坐标（Popup 窗口原点与 App 窗口可能不一致，窗口坐标会错位）
                val position = coordinates.positionOnScreen()
                cellBoundsOnScreen = androidx.compose.ui.geometry.Rect(
                    left = position.x,
                    top = position.y,
                    right = position.x + coordinates.size.width,
                    bottom = position.y + coordinates.size.height
                )
            }
            // 长按弹出预览卡片 + 上下文菜单（iOS 系统长按触觉由 UIKit 提供，此处手动触发）
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                    )
                    showPreviewMenu = true
                }
            )
    ) {
        // 专辑封面 - iOS风格阴影和圆角，使用完整URL
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            // 默认图按主题色现画（iOS ArtworkType.album）
            val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.ALBUM)
            AsyncImage(
                model = buildCoverArtUrl(album.coverArt, credentialsManager, musicRepository),
                contentDescription = album.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = defaultArtwork,
                error = defaultArtwork,
                fallback = defaultArtwork
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 专辑名称
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        
        Spacer(modifier = Modifier.height(2.dp))
        
        // 艺术家名称
        Text(
            text = album.artist,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // 长按弹出：预览卡片 + 上下文菜单（与表格行同源构建）
        AlbumLongPressPreviewMenu(
            album = album,
            expanded = showPreviewMenu,
            onDismissRequest = { showPreviewMenu = false },
            anchorBoundsOnScreen = cellBoundsOnScreen,
            onAction = onSwipeAction,
            onSetRating = onSetRating,
            onShowArtist = onShowArtist,
            // 对应 iOS performPreviewTransition 的 album 分支：点卡片进专辑详情
            onOpenDetail = onClick,
            isFullyCached = isFullyCached
        )
    }
}

/**
 * Grid大小调节对话框 - iOS风格滑块
 */
@Composable
private fun GridSizeDialog(
    currentSize: Int,
    onSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentSize.toFloat()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grid Size") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${sliderValue.toInt()} Columns",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 2f..5f,
                    steps = 2,
                    onValueChangeFinished = {
                        onSizeChange(sliderValue.toInt())
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("2", style = MaterialTheme.typography.bodySmall)
                    Text("5", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

// 替换原来内联的空状态占位符处，使用如下调用：
@Composable
private fun EmptyAlbumsContent(
    displayFilter: DisplayCategoryFilter,
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = AmperfyIcons.album,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                text = when {
                    isSearching -> "No Results"
                    displayFilter == DisplayCategoryFilter.FAVORITES -> "No Favorite Albums"
                    displayFilter == DisplayCategoryFilter.RECENT -> "No Recently Played Albums"
                    displayFilter == DisplayCategoryFilter.NEWEST -> "No New Albums"
                    else -> "No Albums"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
