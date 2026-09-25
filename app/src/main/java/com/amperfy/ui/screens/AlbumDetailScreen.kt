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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.download.DownloadManager
import com.amperfy.data.model.Album
import com.amperfy.data.model.Song
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.LibrarySearchState
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildAlbumContextMenuItems
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.SwipeController
import com.amperfy.ui.components.swipe.rememberSwipeController
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * AlbumDetailScreen - Album Detail screen
 *
 * Ported from iOS: Amperfy/Screens/ViewController/AlbumDetailVC.swift
 *
 * This screen displays album details with songs list, following iOS's design pattern.
 *
 * iOS Components mapped to Compose:
 * - AlbumDetailVC → AlbumDetailScreen Composable
 * - GenericDetailTableHeader → AlbumDetailHeader Composable
 * - LibraryElementDetailTableHeaderView → PlayShuffleButtons Composable
 * - PlayableTableCell → SongItem Composable
 * - UITableView → LazyColumn
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    onBackClick: () -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}, // 头部菜单 Show Artist / 歌曲行菜单（按 artistId 导航）
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val album by viewModel.album.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()

    // More菜单状态
    var showMoreMenu by remember { mutableStateOf(false) }

    // Shuffle 菜单项禁用状态（Disable Player Shuffle Button，对应 iOS EntityPreviewVC .disabled）
    val isShuffleMenuItemEnabled by com.amperfy.ui.navigation.LocalSettingsManager.current
        .isPlayerShuffleButtonEnabled.collectAsState()

    // 滑动动作配置（来自设置，经 SwipeDisplaySettings 过滤）
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val leadingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.leading, SwipeContentType.MUSIC, isOfflineMode)
    }
    val trailingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.trailing, SwipeContentType.MUSIC, isOfflineMode)
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

    // 顶栏 More 菜单所需（与列表行长按同源）：容器缓存态 + Detailed Information + 剪贴板
    val hasCachedSongs by viewModel.hasCachedSongs.collectAsState()
    val isFullyCached by viewModel.isFullyCached.collectAsState()
    val isShowDetailedInfo by com.amperfy.ui.navigation.LocalSettingsManager.current
        .isShowDetailedInfo.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    // 根据来源页面确定返回标题
    val backTitle = when (viewModel.fromPage) {
        "artist" -> "Artist"
        "downloads" -> "Downloads"
        else -> "Albums"
    }

    // Trigger fetch on first appearance (equivalent to iOS viewIsAppearing)
    LaunchedEffect(Unit) {
        viewModel.fetch()
    }

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    // 列表状态、搜索状态与搜索词一并提升到外层：钉顶头走 topBar 槽（与其余页统一），
    // 列表内搜索栏与 LazyColumn 在内容区 AlbumDetailContent 内。
    val listState = rememberLazyListState()
    val searchState = rememberLibrarySearchState(listState)
    var searchText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            // 钉顶态：导航栏收起，钉顶搜索头贴页面最顶（内容经 innerPadding 落其下方）
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = searchText,
                    onSearchTextChanged = { searchText = it },
                    placeholder = "Search in \"Album\""
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = backTitle,
                title = null,  // 详情页不显示标题
                actions = {
                    // More菜单 - 对应iOS: optionsButton
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                AmperfyIcons.ellipsis,
                                contentDescription = "More options"
                            )
                        }

                        // 顶栏 More 与列表行长按同源 - 对应 iOS AlbumDetailVC.swift:124
                        // `optionsButton.menu = EntityPreviewActionBuilder(container: album).createMenuActions()`
                        IOSStyleContextMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(-72, 48),
                            items = album?.let { currentAlbum ->
                                buildAlbumContextMenuItems(
                                    album = currentAlbum,
                                    env = MenuEnv(
                                        isOfflineMode = isOfflineMode,
                                        isShuffleActionEnabled = isShuffleMenuItemEnabled,
                                        isShowDetailedInfo = isShowDetailedInfo
                                    ),
                                    hasCachedSongs = hasCachedSongs,
                                    onAction = { action -> viewModel.handleAlbumAction(action) },
                                    onSetRating = { rating -> viewModel.setAlbumRating(rating) },
                                    // 本页非艺术家详情页，Show Artist 显示（iOS isShowArtist 门控）
                                    onShowArtist = currentAlbum.artistId?.let { artistId ->
                                        { onNavigateToArtist(artistId) }
                                    },
                                    onCopyId = {
                                        if (currentAlbum.id.isNotEmpty()) {
                                            clipboardManager.setText(AnnotatedString(currentAlbum.id))
                                        }
                                    },
                                    isFullyCached = isFullyCached
                                )
                            } ?: emptyList()
                        )
                    }
                }
            )
        }
    ) { padding ->
        // iOS AlbumDetailVC 未挂 refreshControl → 无下拉刷新，仅普通容器承载内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading && album == null -> {
                    LoadingView()
                }
                album != null -> {
                    AlbumDetailContent(
                        album = album!!,
                        songs = songs,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        downloadProgressMap = downloadProgressMap,
                        onSongClick = { song -> viewModel.playSong(song) },
                        onPlayAllClick = { viewModel.playAllSongs() },
                        onShuffleClick = { viewModel.shuffleAllSongs() },
                        onToggleFavorite = { song -> viewModel.toggleFavorite(song) },
                        onSetSongRating = { song, rating -> viewModel.setSongRating(song, rating) },
                        onAddToQueue = { song -> viewModel.addToQueue(song) },
                        onInsertContextQueue = { song -> viewModel.insertContextQueue(song) },  // Insert Context Queue
                        onAppendContextQueue = { song -> viewModel.appendContextQueue(song) },  // Append Context Queue
                        onAddToQueueNext = { song -> viewModel.addToQueueNext(song) },  // Insert User Queue
                        onAddToQueueLater = { song -> viewModel.addToQueueLater(song) },  // Append User Queue
                        onDownload = { song -> viewModel.downloadSong(song) },
                        onAddToPlaylist = { song -> viewModel.addToPlaylist(song) },
                        onShowArtist = { song -> song.artistId?.let(onNavigateToArtist) },
                        onDeleteCache = { song -> viewModel.deleteCache(song) },
                        leadingSwipeActions = leadingSwipeActions,
                        trailingSwipeActions = trailingSwipeActions,
                        onSwipeAction = { song, action -> viewModel.handleSwipeAction(song, action) },
                        listState = listState,
                        searchState = searchState,
                        searchText = searchText,
                        onSearchTextChanged = { searchText = it }
                    )
                }
                else -> {
                    EmptyView()
                }
            }
        }
    }
}

/**
 * AlbumDetailContent - Main content for album details
 * Equivalent to iOS: AlbumDetailVC's tableView with header and cells
 */
@Composable
private fun AlbumDetailContent(
    album: Album,
    songs: List<Song>,
    currentSong: com.amperfy.data.model.Playable?,
    isPlaying: Boolean,
    downloadProgressMap: Map<String, DownloadManager.DownloadProgress>,
    onSongClick: (Song) -> Unit,
    onPlayAllClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onSetSongRating: (Song, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onInsertContextQueue: (Song) -> Unit,  // Insert Context Queue
    onAppendContextQueue: (Song) -> Unit,  // Append Context Queue
    onAddToQueueNext: (Song) -> Unit,       // Insert User Queue
    onAddToQueueLater: (Song) -> Unit,      // Append User Queue
    onDownload: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onShowArtist: (Song) -> Unit,
    onDeleteCache: (Song) -> Unit,
    leadingSwipeActions: List<SwipeActionType>,
    trailingSwipeActions: List<SwipeActionType>,
    onSwipeAction: (Song, SwipeActionType) -> Unit,
    // 列表状态由外层持有：LibrarySearchState 要观察它做「内容不足一屏 → 搜索栏常驻」
    listState: LazyListState,
    // 搜索栏状态与搜索词由外层持有（钉顶头在外层 topBar 槽），内容区只渲染列表内搜索栏
    searchState: LibrarySearchState,
    searchText: String,
    onSearchTextChanged: (String) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current

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

    // 过滤歌曲列表
    val filteredSongs = remember(songs, searchText) {
        if (searchText.isBlank()) {
            songs
        } else {
            songs.filter { song ->
                song.title.contains(searchText, ignoreCase = true) ||
                (song.artist?.contains(searchText, ignoreCase = true) == true)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .nestedScroll(searchState.nestedScrollConnection),
        contentPadding = PaddingValues(bottom = miniPlayerHeight + 16.dp)
    ) {
        // 搜索栏 - 对应 iOS configureSearchController（AlbumDetailVC.swift:88，placeholder
        // 为固定字面量 "Search in \"Album\""，非专辑名）；恰好一个 item，
        // 显隐/钉顶/焦点行为全在 LibrarySearchState 内
        librarySearchBarItem(
            state = searchState,
            searchText = searchText,
            onSearchTextChanged = onSearchTextChanged,
            placeholder = "Search in \"Album\""
        )

        // Album header - Equivalent to iOS GenericDetailTableHeader
        item {
            AlbumDetailHeader(
                album = album,
                songCount = songs.size,
                onPlayAllClick = onPlayAllClick,
                onShuffleClick = onShuffleClick
            )
        }

        // Songs list - Equivalent to iOS tableView cells
        if (filteredSongs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchText.isNotBlank()) "No results found" else "No songs in this album",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondaryLabel
                    )
                }
            }
        } else {
            itemsIndexed(filteredSongs, key = { _, song -> song.id }) { index, song ->
                // 使用统一的 SongListItem 组件 - TRACK_NUMBER 样式，不显示 Show Album
                SwipeableItem(
                    key = song.id,
                    swipeController = swipeController,
                    leadingActions = leadingSwipeActions,
                    trailingActions = trailingSwipeActions,
                    onSwipeAction = { action -> onSwipeAction(song, action) },
                    isFavorite = song.isFavorite
                ) {
                SongListItem(
                    song = song,
                    style = SongListItemStyle.TRACK_NUMBER,
                    trackNumber = song.track ?: (index + 1),
                    isPlaying = currentSong?.id == song.id && isPlaying,
                    showShuffleInMenu = true,
                    showAlbumInMenu = false,  // 专辑详情页不显示 Show Album
                    downloadProgressMap = downloadProgressMap,
                    callbacks = SongListItemCallbacks(
                        onClick = { onSongClick(song) },
                        // Shuffle = 以本专辑歌曲列表为上下文乱序播放（同头部 Shuffle 入口）
                        onShuffle = onShuffleClick,
                        onToggleFavorite = { onToggleFavorite(song) },
                        onSetRating = { rating -> onSetSongRating(song, rating) },
                        onInsertContextQueue = { onInsertContextQueue(song) },  // Insert Context Queue
                        onAppendContextQueue = { onAppendContextQueue(song) },  // Append Context Queue
                        onAddToQueueNext = { onAddToQueueNext(song) },  // Insert User Queue
                        onAddToQueueLater = { onAddToQueueLater(song) },  // Append User Queue
                        onShowArtist = { onShowArtist(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onDownload = { onDownload(song) },
                        onDeleteCache = { onDeleteCache(song) }
                    )
                )
                }
            }
        }
    }
}

/**
 * AlbumDetailHeader - Album header with artwork and play controls
 *
 * Ported from iOS: GenericDetailTableHeader
 *
 * Components:
 * - EntityImageView → AsyncImage (Album artwork)
 * - Title/Subtitle/Info labels → Text composables
 * - LibraryElementDetailTableHeaderView → PlayShuffleButtons
 */
@Composable
private fun AlbumDetailHeader(
    album: Album,
    songCount: Int,
    onPlayAllClick: () -> Unit,
    onShuffleClick: () -> Unit
) {
    val credentialsManager = com.amperfy.ui.navigation.LocalCredentialsManager.current
    val musicRepository = com.amperfy.ui.navigation.LocalMediaUrlRepository.current

    // 外层全宽（无横向 padding）：尾部的「头部与列表交界线」须画满屏幕两端，
    // 故把它抬出 padding(16) 的层级
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Album artwork - Equivalent to iOS EntityImageView
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
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

            Spacer(modifier = Modifier.height(24.dp))

            // Album name - Equivalent to iOS title label
            Text(
                text = album.name,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Artist name - Equivalent to iOS subtitle (clickable)
            Text(
                text = album.artist,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Album info - Equivalent to iOS info label
            AlbumInfoRow(album, songCount)

            Spacer(modifier = Modifier.height(24.dp))

            // Play/Shuffle buttons - Equivalent to iOS LibraryElementDetailTableHeaderView
            //（共享组件：灰底 .secondarySystemFill + 主题色内容 + 10dp 圆角，
            // Disable Player Shuffle Button 门控内置）
            PlayShuffleButtons(
                onPlay = onPlayAllClick,
                onShuffle = onShuffleClick
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // 头部与歌曲列表的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）
        HairlineDivider(
            color = MaterialTheme.colorScheme.separator  // iOS .separator
        )
    }
}

/**
 * AlbumInfoRow - Display album metadata (year, song count, duration, genre)
 * Equivalent to iOS info label in GenericDetailTableHeader
 */
@Composable
private fun AlbumInfoRow(album: Album, songCount: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Year
        if (album.year != null) {
            Text(
                text = "${album.year}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "·",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Song count
        Text(
            text = "$songCount Song${if (songCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Duration
        if (album.duration > 0) {
            Text(
                text = "·",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDuration(album.duration),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Genre
        album.genre?.let { genre ->
            if (genre.isNotBlank()) {
                Text(
                    text = "·",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = genre,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * LoadingView - Loading indicator
 * Equivalent to iOS loading state in viewDidLoad
 */
@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Loading Album...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * EmptyView - Empty state when album not found
 */
@Composable
private fun EmptyView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = AmperfyIcons.album,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                text = "Album not found",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

/**
 * Format duration in seconds to readable string (mm:ss or h:mm:ss)
 * Equivalent to iOS formatDuration helper
 */
private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}
