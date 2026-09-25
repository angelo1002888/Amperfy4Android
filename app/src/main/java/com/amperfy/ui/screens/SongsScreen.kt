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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.SearchBarSlot
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.ui.components.IOSPullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.AlphabetIndex
import com.amperfy.ui.components.LocalListRowTrailingInset
import com.amperfy.ui.components.rememberIndexBarRowEndPadding
import com.amperfy.ui.components.GroupedSectionHeader
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.SortSectionMode
import com.amperfy.ui.util.SortSectionUtils

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongsScreen(
    onBackClick: () -> Unit,
    onSongClick: (Song) -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    viewModel: SongsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val songs by viewModel.filteredSongs.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    // 滑动动作配置（来自设置，经 SwipeDisplaySettings 过滤）
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    // 「Many Songs」确认弹窗的待确认数量（null = 不显示），对应 iOS SongsVC.swift:477-492
    var manySongsCount by remember { mutableStateOf<Int?>(null) }
    val leadingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.leading, SwipeContentType.MUSIC, isOfflineMode)
    }
    val trailingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.trailing, SwipeContentType.MUSIC, isOfflineMode)
    }

    // 滑动控制器 - 确保同时只有一个 item 处于滑出状态
    val swipeController = rememberSwipeController()
    val swipeScope = rememberCoroutineScope()
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

    // 「Many Songs」确认（对应 iOS SongsVC.swift:477-492 的 UIAlertController：
    // title "Many Songs" + OK/Cancel；文案逐字对齐）
    manySongsCount?.let { count ->
        AlertDialog(
            onDismissRequest = { manySongsCount = null },
            title = { Text("Many Songs") },
            text = { Text("Are you sure to add $count songs from \"Songs\" to download queue?") },
            confirmButton = {
                TextButton(onClick = {
                    manySongsCount = null
                    viewModel.downloadAllSongs()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { manySongsCount = null }) { Text("Cancel") }
            }
        )
    }

    var showSortMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 逐行段头标题：**仅 Rating 档**有段头（iOS SongsVC.swift:233-252 唯 rating 返回
    // tableSectionHeightLarge=40，name/addedDate/duration/starredDate 一律高 0 = 平铺无段头）。
    // 段头在段首行的 item 内联发射，故 lazy item 数仍等于 songs.size，
    // 字母索引沿用 items 一一对应的定位方式，无须额外映射表
    val sectionTitles: List<String>? = remember(songs, uiState.sortType) {
        if (uiState.sortType.sectionMode == SortSectionMode.RATING) {
            songs.map { songSectionTitle(it, SortSectionMode.RATING) }
        } else {
            null
        }
    }

    // 逐行段边界键（**行间线形态判定**：与下一行键不同 → 该行下方的线画全宽 = grouped 段边界线；
    // 同段内仍 16dp inset）。null = 该档全部 16dp inset。
    // 按 Albums 页 iOS 实机观测（2026-08-10）同构推广至 rating/duration 两档
    val rowSectionKeys: List<String>? = remember(songs, uiState.sortType) {
        if (uiState.sortType.hasFullWidthSectionDividers) {
            songs.map { songRowSectionKey(it, uiState.sortType.sectionMode) }
        } else {
            null
        }
    }

    // 索引条标签集：duration 档由当前数据现算（值域无穷），name/rating 用固定表
    val songIndexBarLabels = remember(songs, uiState.sortType) {
        SortSectionUtils.indexLabels(uiState.sortType.sectionMode, songs) {
            songIndexLabel(it, uiState.sortType.sectionMode)
        }
    }

    // 索引条可见性（与下方 AlphabetIndex 的渲染条件同一个布尔）与行内容避让宽度：
    // 索引条可见时行内容 end 内边距改为「条偏移+(条宽+最宽字形宽)/2+间隙」替代 16dp，分割线/行容器仍全宽
    // （= iOS UITableView 在 sectionIndexTitles 非空时收窄 cell.contentView，
    //   iOS 实机观测 2026-08-13，见 LocalListRowTrailingInset）
    val isIndexBarVisible = songs.isNotEmpty() && uiState.sortType.sectionMode != SortSectionMode.NONE
    // 该页索引条自身相对屏幕右缘偏移 4dp（见下方 AlphabetIndex 的 padding(end = 4.dp)）
    val indexBarRowEndPadding =
        rememberIndexBarRowEndPadding(songIndexBarLabels, indexBarEndPadding = 4.dp)
    val rowTrailingInset = if (isIndexBarVisible) indexBarRowEndPadding else 0.dp

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    val showCollapsedTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    Scaffold(
        topBar = {
            // 钉顶态：导航栏整体收起，钉顶搜索头贴页面最顶（statusBarsPadding 避让状态栏，
            // 背景铺满含状态栏区）；列表经 Scaffold innerPadding 落于其下方滚动。对齐 iOS
            // UISearchController hidesNavigationBarDuringPresentation = true。
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = searchQuery,
                    onSearchTextChanged = { searchQuery = it; viewModel.updateSearchText(it) },
                    placeholder = "Search in \"Songs\""
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) "Songs" else null,
                actions = {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(AmperfyIcons.ellipsis, "More")
                    }
                    // 右上角选项菜单（对应 iOS SongsVC.updateRightBarButtonItems，
                    // SongsVC.swift:191-198：Sort 子菜单 + Download Songs 两块）
                    IOSStyleContextMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        items = buildList {
                            // Sort 子菜单（iOS createSortButtonMenu，:368-460；父行 title "Sort"
                            // + image .sort，当前项打勾）。iOS 本页四档 Name/Rating/Duration/
                            // Date Added；Android 另有 Artist/Album 两档（Android 独有，保留）
                            add(IOSContextMenuItem.Submenu(
                                text = "Sort",
                                icon = AmperfyIcons.sort,
                                items = SongSortType.entries.map { sortType ->
                                    IOSContextMenuItem.Action(
                                        text = sortType.displayName,
                                        icon = if (uiState.sortType == sortType) {
                                            AmperfyIcons.check
                                        } else {
                                            null
                                        },
                                        onClick = { viewModel.changeSortType(sortType) }
                                    )
                                }
                            ))
                            // Download Songs（iOS createActionButtonMenu，:463-496；仅在线模式）
                            if (!isOfflineMode) {
                                add(IOSContextMenuItem.Divider)
                                add(IOSContextMenuItem.Action(
                                    text = "Download Songs",
                                    icon = AmperfyIcons.download,
                                    onClick = {
                                        // 超过阈值先确认（iOS 同段的 "Many Songs" UIAlertController）
                                        val count = viewModel.allSongsCount()
                                        if (count > MANY_SONGS_WARNING_THRESHOLD) {
                                            manySongsCount = count
                                        } else {
                                            viewModel.downloadAllSongs()
                                        }
                                    }
                                ))
                            }
                        }
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            IOSPullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.handleRefresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                CompositionLocalProvider(LocalListRowTrailingInset provides rowTrailingInset) {
                LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .nestedScroll(searchState.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = miniPlayerHeight + 16.dp)
            ) {
                // 前置 item 1/3：大标题（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                item(key = "title") {
                    if (!searchState.isPinned) {
                        IOSLargeTitle(
                            text = "Songs",
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                        )
                    }
                }

                // 前置 item 2/3：搜索栏 + 播放控制（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                // 普通 item（不钉住，对齐 iOS 表头随滚动移动）；
                // 搜索栏包 AnimatedVisibility 参与显隐（hidesSearchBarWhenScrolling），控制区始终可见
                item(key = "searchAndControls") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(start = 16.dp, end = 20.dp, top = 2.dp, bottom = 4.dp)
                    ) {
                        // 搜索栏与下方 Play/Shuffle 同处一个 item（前置 item 数不变）：
                        // 故不用列表版发射器，直接调其内核 SearchBarSlot
                        searchState.SearchBarSlot(
                            searchText = searchQuery,
                            onSearchTextChanged = { searchQuery = it; viewModel.updateSearchText(it) },
                            placeholder = "Search in \"Songs\"",
                            horizontalPadding = 0.dp,
                            verticalPadding = 0.dp,
                            bottomSpacing = 8.dp
                        )
                        SongPlayControls(
                            onPlayClick = { viewModel.playAll() },
                            onShuffleClick = { viewModel.shuffleAll() }
                        )
                    }
                }

                // 前置 item 3/3：分隔线（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                // 列表区与上方控件（标题/搜索栏/播放控制）的交界线 = iOS .grouped 表的
                // section 顶边界线，恒全宽（0..0），不吃 separatorInset
                item(key = "sectionTopDivider") {
                    HairlineDivider(
                        color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                    )
                }

                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    // derivedStateOf: 只在当前歌曲的进度值变化时才触发重组
                    val songProgress by remember(song.id) {
                        derivedStateOf {
                            val p = downloadProgressMap[song.id]
                            if (p != null) mapOf(song.id to p) else emptyMap()
                        }
                    }
                    Column {
                        // Rating 档：段首行上方内联 40dp grouped 段头
                        //（iOS SongsVC.swift:253-275 供标题 "N Star(s)"/"Not rated"、:233-252 供高度）
                        if (sectionTitles != null &&
                            (index == 0 || sectionTitles[index - 1] != sectionTitles[index])
                        ) {
                            GroupedSectionHeader(title = sectionTitles[index])
                        }
                        SwipeableItem(
                            key = song.id,
                            swipeController = swipeController,
                            leadingActions = leadingSwipeActions,
                            trailingActions = trailingSwipeActions,
                            onSwipeAction = { viewModel.handleSwipeAction(song, it) },
                            isFavorite = song.isFavorite
                        ) {
                            SongListItem(
                                song = song,
                                style = SongListItemStyle.ARTWORK,
                                isPlaying = currentSong?.id == song.id && isPlaying,
                                downloadProgressMap = songProgress,
                                // 需要区分段间/段内两种线形态时，行内不画、改由本 item 统一发射；
                                // 否则沿用行内 16dp inset 线（末行不画，让位给下方全宽段底线）
                                showDivider = rowSectionKeys == null && index < songs.lastIndex,
                                callbacks = SongListItemCallbacks(
                                    onClick = { viewModel.playSong(song) },
                                    // Shuffle = 以本页列表为上下文乱序播放（对齐 iOS 头部 Shuffle 语义）
                                    onShuffle = { viewModel.shuffleAll() },
                                    onToggleFavorite = { viewModel.toggleFavorite(song) },
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

                        // 段间全宽 / 段内 16dp inset（形态判定见 rowSectionKeys 注释）；
                        // 末行不画，由列表末尾的全宽段底线承担
                        if (rowSectionKeys != null && index < songs.lastIndex) {
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
                if (songs.isNotEmpty()) {
                    item(key = "sectionBottomDivider") {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                        )
                    }
                }
            }
            }
        }

            // 索引条（iOS风格）：Name=A-Z+#、Rating="5".."1"/"#"、Duration=时长桶；
            // Date Added 无索引（iOS asSectionIndexType = .newestOrRecent → sectionIndexTitle nil，
            // BasicFetchedResultsController.swift:182-200）
            if (isIndexBarVisible) {
                val sectionMode = uiState.sortType.sectionMode
                AlphabetIndex(
                    items = songs,
                    listState = listState,
                    getIndexLetter = { song -> songIndexLabel(song, sectionMode) },
                    letters = songIndexBarLabels,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    // 前置 item：title / searchAndControls / divider = 3（须与上方 LazyColumn 结构同步）
                    leadingItemCount = 3
                )
            }
        }
    }
}

@Composable
private fun SongPlayControls(
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        PlayShuffleButtons(
            onPlay = onPlayClick,
            onShuffle = onShuffleClick
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
