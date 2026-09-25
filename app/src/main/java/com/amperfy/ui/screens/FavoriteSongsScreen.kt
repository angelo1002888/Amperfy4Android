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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.SearchBarSlot
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.data.local.FavoriteSongSortType
import com.amperfy.ui.components.AlphabetIndex
import com.amperfy.ui.components.LocalListRowTrailingInset
import com.amperfy.ui.components.rememberIndexBarRowEndPadding
import com.amperfy.ui.components.GroupedSectionHeader
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSPullToRefreshBox
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
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
import com.amperfy.ui.util.SortSectionMode
import com.amperfy.ui.util.SortSectionUtils
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * Favorite Songs屏幕 - iOS风格
 * 对应iOS: FavoriteSongsVC (LibraryDisplayType.favoriteSongs)
 *
 * 显示用户收藏的歌曲列表
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FavoriteSongsScreen(
    onBackClick: () -> Unit,
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    viewModel: FavoriteSongsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val songs by viewModel.filteredSongs.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    // 滑动动作配置（来自设置，经 SwipeDisplaySettings 过滤）
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
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

    // 右上角选项菜单（对应 iOS SongsVC updateRightBarButtonItems：
    // OptionsBarButton = Sort 子菜单 + Download Favorite Songs）
    // 「Many Songs」确认（对应 iOS SongsVC.swift:477-492；容器名用本页 filterTitle）
    var manySongsCount by remember { mutableStateOf<Int?>(null) }
    manySongsCount?.let { count ->
        AlertDialog(
            onDismissRequest = { manySongsCount = null },
            title = { Text("Many Songs") },
            text = {
                Text("Are you sure to add $count songs from \"Favorite Songs\" to download queue?")
            },
            confirmButton = {
                TextButton(onClick = {
                    manySongsCount = null
                    viewModel.downloadAllFavorites()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { manySongsCount = null }) { Text("Cancel") }
            }
        )
    }

    var showOptionsMenu by remember { mutableStateOf(false) }

    // 逐行段头标题：**仅 Rating 档**有段头。iOS 的 Favorite Songs 就是同一个 SongsVC 挂
    // favorites 过滤（LibraryDisplayType.favoriteSongs），段头规则同构：
    // SongsVC.swift:233-252 唯 rating 返回 tableSectionHeightLarge=40、:253-275 供
    // "N Star(s)"/"Not rated" 标题，其余档高 0 = 平铺无段头。
    // 段头在段首行的 item 内联发射（不新增 lazy item），故 lazy item 数仍等于 songs.size，
    // 右侧索引条沿用 items 一一对应的定位方式，无须额外映射表
    val sectionTitles: List<String>? = remember(songs, uiState.sortType) {
        if (uiState.sortType == FavoriteSongSortType.RATING) {
            songs.map { songSectionTitle(it, SortSectionMode.RATING) }
        } else {
            null
        }
    }

    // 逐行段边界键（**行间线形态判定**：与下一行键不同 → 该行下方的线画全宽；同段内 16dp inset）。
    // null = 该档全部 16dp inset。按 Albums 页 iOS 实机观测（2026-08-10）同构推广
    val rowSectionKeys: List<String>? = remember(songs, uiState.sortType) {
        if (uiState.sortType.hasFullWidthSectionDividers) {
            songs.map { songRowSectionKey(it, uiState.sortType.sectionMode) }
        } else {
            null
        }
    }

    // 索引条标签集：duration 档由当前数据现算，name/rating 用固定表
    val songIndexBarLabels = remember(songs, uiState.sortType) {
        SortSectionUtils.indexLabels(uiState.sortType.sectionMode, songs) {
            songIndexLabel(it, uiState.sortType.sectionMode)
        }
    }

    // 大标题随列表滚动折叠：滚过标题后在导航栏中间浮现（与 AlbumsScreen 一致）
    val listState = rememberLazyListState()
    val showCollapsedTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    // 索引条可见性（与下方 AlphabetIndex 的渲染条件同一个布尔）与行内容避让宽度：
    // 索引条可见时行内容 end 内边距改为「条偏移+(条宽+最宽字形宽)/2+间隙」替代 16dp，分割线/行容器仍全宽
    // （= iOS UITableView 在 sectionIndexTitles 非空时收窄 cell.contentView，
    //   iOS 实机观测 2026-08-13，见 LocalListRowTrailingInset）
    val isIndexBarVisible = songs.isNotEmpty() && !uiState.isLoading &&
        uiState.sortType.sectionMode != SortSectionMode.NONE
    // 该页索引条自身相对屏幕右缘偏移 4dp（见下方 AlphabetIndex 的 padding(end = 4.dp)）
    val indexBarRowEndPadding =
        rememberIndexBarRowEndPadding(songIndexBarLabels, indexBarEndPadding = 4.dp)
    val rowTrailingInset = if (isIndexBarVisible) indexBarRowEndPadding else 0.dp

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    Scaffold(
        topBar = {
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Favorite Songs\""
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) "Favorite Songs" else null,
                actions = {
                    IconButton(onClick = { showOptionsMenu = true }) {
                        Icon(AmperfyIcons.ellipsis, contentDescription = "Options")
                    }
                    IOSStyleContextMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                        items = buildList {
                            // Sort 子菜单（对应 iOS createSortButtonMenu：
                            // Name/Rating/Duration/Starred date，当前项打勾）
                            add(IOSContextMenuItem.Submenu(
                                text = "Sort",
                                icon = AmperfyIcons.sort,
                                items = FavoriteSongSortType.entries.map { sortType ->
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
                            // Download（对应 iOS createActionButtonMenu，仅在线模式）
                            if (!isOfflineMode) {
                                add(IOSContextMenuItem.Divider)
                                add(IOSContextMenuItem.Action(
                                    text = "Download Favorite Songs",
                                    icon = AmperfyIcons.download,
                                    onClick = {
                                        // 超阈值先确认（iOS SongsVC.swift:477-492 的 "Many Songs"）
                                        val count = viewModel.favoriteSongsCount()
                                        if (count > MANY_SONGS_WARNING_THRESHOLD) {
                                            manySongsCount = count
                                        } else {
                                            viewModel.downloadAllFavorites()
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
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 下拉刷新（对应 iOS SongsVC 的 refreshControl，Favorite Songs 同款 handleRefresh）
            IOSPullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
            CompositionLocalProvider(LocalListRowTrailingInset provides rowTrailingInset) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .nestedScroll(searchState.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = miniPlayerHeight)
            ) {
                // 大标题 - 随列表滚动折叠（与 AlbumsScreen 一致）；钉顶时收起内容但保留 item 占位
                item {
                    if (!searchState.isPinned) {
                        IOSLargeTitle(
                            text = "Favorite Songs",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // 搜索栏 + Play/Shuffle 控制行 - 普通 item（不钉住，对齐 iOS 表头随滚动移动）；
                // 搜索栏包 AnimatedVisibility 参与显隐（hidesSearchBarWhenScrolling），控制区始终可见
                item(key = "searchAndControls") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // 搜索栏与下方 Play/Shuffle 同处一个 item（前置 item 数不变）：
                        // 故不用 librarySearchBarItem，直接调其内核 SearchBarSlot
                        searchState.SearchBarSlot(
                            searchText = uiState.searchText,
                            onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                            placeholder = "Search in \"Favorite Songs\""  // iOS: SongsVC filterTitle
                        )
                        // Play/Shuffle + 歌曲数量（对应 iOS LibraryElementDetailTableHeaderView）
                        if (songs.isNotEmpty()) {
                            FavoritePlayControls(
                                onPlayClick = { viewModel.playAll() },
                                onShuffleClick = { viewModel.shuffleAll() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // 加载/空状态
                if (uiState.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(top = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (songs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(top = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    AmperfyIcons.heartEmpty,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (uiState.searchText.isNotEmpty()) "No results found" else "No Favorite Songs",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (uiState.searchText.isEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Songs you mark as favorites will appear here",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 列表区与上方控件（标题/搜索栏/播放控制）的交界线 = iOS .grouped 表的
                    // section 顶边界线，恒全宽（0..0），不吃 separatorInset
                    item {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                        )
                    }

                    itemsIndexed(songs, key = { _, song -> "song_${song.id}" }) { index, song ->
                        Column {
                        // Rating 档：段首行上方内联 40dp grouped 段头（iOS SongsVC.swift:253-275）
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
                            showShuffleInMenu = true,
                            showAlbumInMenu = true,
                            // 需要区分段间/段内两种线形态时，行内不画、改由本 item 统一发射；
                            // 否则沿用行内 16dp inset 线（末行不画，让位给下方全宽段底线）
                            showDivider = rowSectionKeys == null && index < songs.lastIndex,
                            callbacks = SongListItemCallbacks(
                                onClick = { viewModel.playSong(song) },
                                // Shuffle = 以本页收藏歌曲列表为上下文乱序播放
                                onShuffle = { viewModel.shuffleAll() },
                                onToggleFavorite = { viewModel.toggleFavorite(song) },
                                onAddToQueueNext = { viewModel.addToQueue(song) },
                                onAddToQueueLater = { viewModel.addToQueue(song) },
                                onShowAlbum = { song.albumId?.let { onNavigateToAlbum(it) } },
                                onShowArtist = { song.artistId?.let { onNavigateToArtist(it) } },
                                onAddToPlaylist = { viewModel.addToPlaylist(song) },
                                onDownload = { viewModel.downloadSong(song) },
                                onDeleteCache = { viewModel.deleteSongCache(song) }
                            )
                        )
                        }

                        // 段间全宽 / 段内 16dp inset（形态判定见 rowSectionKeys 注释）；
                        // 末行不画，由列表末尾的全宽段底线承担。
                        // 其余排序档的行间线仍由 SongListItem 内置（16dp..屏幕右缘）
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
                    item {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
                        )
                    }
                }
            }
            }
            }

            // 索引条：iOS 的 Favorite Songs = 同一个 SongsVC，索引随 sortType 恒存在
            //（Name=A-Z+#、Rating="5".."1"/"#"、Duration=时长桶；
            //  Starred date/Date Added 无索引，asSectionIndexType = .none/.newestOrRecent）
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
                    // 前置 item：title / searchAndControls / section 顶边界线 = 3
                    //（须与上方 LazyColumn 结构同步）
                    leadingItemCount = 3
                )
            }
        }
    }
}

/**
 * Play/Shuffle 控制 + 歌曲数量
 * 对应 iOS: LibraryElementDetailTableHeaderView（PlayShuffleInfoConfiguration，
 * SongsVC.viewDidLoad 中创建的列表头，与 SongsScreen.SongPlayControls 同构）
 */
@Composable
private fun FavoritePlayControls(
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
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
