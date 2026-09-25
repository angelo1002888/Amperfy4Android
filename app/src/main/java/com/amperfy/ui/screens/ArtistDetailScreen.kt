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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.AlbumListItem
import com.amperfy.ui.components.AlbumListItemCallbacks
import com.amperfy.ui.components.GroupedSectionHeader
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildArtistContextMenuItems
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * Artist详情屏幕 - iOS风格
 * 对应iOS: ArtistDetailVC.swift
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistName: String = "Artists", // 上一级页面名称,用于返回按钮
    onBackClick: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onNavigateToAlbum: (String) -> Unit = {}, // 歌曲行菜单 Show Album（按 albumId 导航）
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val artist by viewModel.artist.collectAsState()
    val albums by viewModel.filteredAlbums.collectAsState()
    val songs by viewModel.filteredSongs.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

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

    // 顶栏 More 菜单所需（与列表行长按同源）：容器缓存态
    val hasCachedSongs by viewModel.hasCachedSongs.collectAsState()
    val isFullyCached by viewModel.isFullyCached.collectAsState()

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

    // 对应iOS的viewIsAppearing() -> artist.fetch()
    LaunchedEffect(Unit) {
        viewModel.fetch()
    }

    Scaffold(
        topBar = {
            // 钉顶态：导航栏整体收起，钉顶搜索头贴页面最顶（对齐 iOS
            // hidesNavigationBarDuringPresentation = true）；列表经 innerPadding 落其下方
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Albums and Songs"
                )
            } else
            // iOS风格的统一导航栏 - 详情页不显示标题
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = artistName,
                title = null,  // 详情页不显示标题
                actions = {
                    // iOS: 更多选项(横向三个点) - UIBarButtonItem.createOptionsBarButton()
                    OptionsMenu(
                        artist = artist,
                        hasCachedSongs = hasCachedSongs,
                        isFullyCached = isFullyCached,
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(-72, 48),
                        onSetRating = { rating -> viewModel.setArtistRating(rating) },
                        onAction = { action -> viewModel.handleArtistAction(action) }
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .nestedScroll(searchState.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = miniPlayerHeight)
            ) {
                // 搜索栏 - 对应iOS: configureSearchController(placeholder: "Albums and Songs")
                // （ArtistDetailVC:58）；恰好一个 item，行为在 LibrarySearchState 内
                librarySearchBarItem(
                    state = searchState,
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Albums and Songs"
                )

                // 艺术家头部信息 - 对应iOS: GenericDetailTableHeader
                // 计数与下方专辑/歌曲列表同源（复合查询，对齐 iOS infoDetails 相关计数）
                item {
                    val headerAlbumCount by viewModel.headerAlbumCount.collectAsState()
                    val headerSongCount by viewModel.headerSongCount.collectAsState()
                    ArtistDetailHeader(
                        artist = artist,
                        albumCount = headerAlbumCount,
                        songCount = headerSongCount,
                        onPlayAll = { viewModel.playAll() },
                        onShuffle = { viewModel.shuffleAll() }
                    )
                }

                // Albums部分 - 对应iOS: LibraryElement.Album section
                // 段头用普通 item：iOS .grouped 表的段头**不吸顶**，随内容滚走
                if (albums.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Albums")
                    }

                    items(albums, key = { album -> "album_${album.id}" }) { album ->
                        SwipeableItem(
                            key = "album_${album.id}",
                            swipeController = swipeController,
                            leadingActions = leadingSwipeActions,
                            trailingActions = trailingSwipeActions,
                            onSwipeAction = { action -> viewModel.handleSwipeAction(album, action) },
                            isFavorite = album.isFavorite
                        ) {
                            AlbumListItem(
                                album = album,
                                callbacks = AlbumListItemCallbacks(
                                    onClick = { onAlbumClick(album) }
                                ),
                                showDivider = true,
                                // 长按上下文菜单：动作走与滑动同一条 Album.handleSwipeAction 路径
                                onSwipeAction = { action -> viewModel.handleSwipeAction(album, action) },
                                onSetRating = { rating -> viewModel.setAlbumRating(album, rating) }
                                // Show Artist 省略：已在该艺术家详情页内
                                // （对应 iOS isShowArtist = !(rootView is ArtistDetailVC)）
                            )
                        }
                    }
                }

                // Songs部分 - 对应iOS: LibraryElement.Song section（段头同样不吸顶）
                if (songs.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Songs")
                    }

                    items(songs, key = { song -> "song_${song.id}" }) { song ->
                        SwipeableItem(
                            key = "song_${song.id}",
                            swipeController = swipeController,
                            leadingActions = leadingSwipeActions,
                            trailingActions = trailingSwipeActions,
                            onSwipeAction = { action -> viewModel.handleSwipeAction(song, action) },
                            isFavorite = song.isFavorite
                        ) {
                        SongListItem(
                            song = song,
                            style = SongListItemStyle.ARTWORK,
                            isPlaying = currentSong?.id == song.id && isPlaying,
                            showShuffleInMenu = true,
                            showAlbumInMenu = true,
                            // 对应 iOS isShowArtist = !(rootView is ArtistDetailVC)：本页内整项省略
                            showArtistInMenu = false,
                            callbacks = SongListItemCallbacks(
                                onClick = { viewModel.playSong(song) },
                                // Shuffle = 以本艺术家歌曲列表为上下文乱序播放
                                onShuffle = { viewModel.shuffleAll() },
                                onToggleFavorite = { viewModel.toggleSongFavorite(song) },
                                onSetRating = { rating -> viewModel.setSongRating(song, rating) },
                                onAddToQueueNext = { viewModel.addSongToQueue(song) },
                                onAddToQueueLater = { viewModel.addSongToQueue(song) },
                                onShowAlbum = { song.albumId?.let(onNavigateToAlbum) },
                                onAddToPlaylist = { viewModel.addSongToPlaylist(song) },
                                onDownload = { viewModel.downloadSong(song) },
                                onDeleteCache = { viewModel.deleteSongCache(song) }
                            )
                        )
                        }
                    }
                }

                // 空状态
                if (albums.isEmpty() && songs.isEmpty() && !uiState.isLoading) {
                    item {
                        EmptyState(
                            isSearching = uiState.searchText.isNotEmpty(),
                            showCachedOnly = uiState.showCachedOnly
                        )
                    }
                }
            }

            // 加载指示器
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
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

/**
 * 艺术家详情头部
 * 对应iOS: GenericDetailTableHeader + LibraryElementDetailTableHeaderView
 */
@Composable
private fun ArtistDetailHeader(
    artist: Artist?,
    albumCount: Int,
    songCount: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    val credentialsManager = com.amperfy.ui.navigation.LocalCredentialsManager.current
    val musicRepository = com.amperfy.ui.navigation.LocalMediaUrlRepository.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 24.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 艺术家头像 - 圆角正方形（对齐 iOS 2.1：music.mic 占位图与真实头像均为圆角方形，非圆形；12dp 与专辑详情头图一致）
        // 默认图按主题色现画（iOS ArtworkType.artist）
        val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.ARTIST)
        AsyncImage(
            model = buildCoverArtUrl(artist?.coverArt, credentialsManager, musicRepository),
            contentDescription = artist?.name,
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
            // 对齐 iOS LibraryEntityImage.refresh()：先显示生成占位图，真图加载完成才替换，UI 不等网络；
            // placeholder 覆盖加载中（含滑动回来重新发起请求期间），error/fallback 兜底失败与空模型，均避免露出底色成「白图」
            placeholder = defaultArtwork,
            error = defaultArtwork,
            fallback = defaultArtwork
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 艺术家名称
        Text(
            text = artist?.name ?: "",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 信息行 - 对应iOS: "\(albumCount) Album\(albumCount == 1 ? "" : "s") · \(songCount) Song\(songCount == 1 ? "" : "s")"
        Text(
            text = "$albumCount Album${if (albumCount != 1) "s" else ""} · $songCount Song${if (songCount != 1) "s" else ""}",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 操作按钮行 - 对应 iOS: PlayShuffleInfoConfiguration / LibraryElementDetailTableHeaderView
        //（Play 文案为 "Play" 而非 "Play All"；Disable Player Shuffle Button 门控内置于共享组件）
        PlayShuffleButtons(
            onPlay = onPlayAll,
            onShuffle = onShuffle
        )
    }
}

/**
 * Section Header
 * 对应iOS: tableView titleForHeaderInSection（heightForHeaderInSection =
 * tableSectionHeightLarge，ArtistDetailVC.swift:300-303）
 *
 * 段头随内容滚走（普通 item，非 stickyHeader）→ 无需 Surface 撑不透明底色，
 * 直接 Column 承载「文本 + 段顶边界线」即可
 */
@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 段头文本走共享组件（与 GenreDetailScreen 同源，iOS 同一套 grouped 段头）
        GroupedSectionHeader(title = title)
        // section 头与其下列表的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）；
        // 刻意留在宿主层——GroupedSectionHeader 不带线，避免与各页边界线画成双线
        HairlineDivider(
            color = MaterialTheme.colorScheme.separator
        )
    }
}



/**
 * 选项菜单（横向三个点）- 对应 iOS ArtistDetailVC.swift:98
 * `optionsButton.menu = EntityPreviewActionBuilder(container: artist).createMenuActions()`
 * ——与艺术家列表行长按同一构建器、同一 Artist.handleSwipeAction 执行路径
 */
@Composable
private fun OptionsMenu(
    artist: Artist?,
    hasCachedSongs: Boolean,
    isFullyCached: Boolean,
    onSetRating: (Int) -> Unit,
    onAction: (SwipeActionType) -> Unit,
    alignment: Alignment = Alignment.TopEnd,
    offset: IntOffset = IntOffset(0, 0)
) {
    var expanded by remember { mutableStateOf(false) }

    val settingsManager = com.amperfy.ui.navigation.LocalSettingsManager.current
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    Box {
        // iOS: 横向三个点
        IconButton(onClick = { expanded = true }) {
            Icon(
                AmperfyIcons.ellipsis,
                tint = MaterialTheme.colorScheme.secondaryLabel,
                contentDescription = "Options"
            )
        }

        IOSStyleContextMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            alignment = alignment,
            offset = offset,
            items = artist?.let { current ->
                buildArtistContextMenuItems(
                    artist = current,
                    env = MenuEnv(
                        isOfflineMode = isOfflineMode,
                        isShuffleActionEnabled = isShuffleActionEnabled,
                        isShowDetailedInfo = isShowDetailedInfo
                    ),
                    hasCachedSongs = hasCachedSongs,
                    onAction = onAction,
                    onSetRating = onSetRating,
                    onCopyId = {
                        if (current.id.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(current.id))
                        }
                    },
                    isFullyCached = isFullyCached
                )
            } ?: emptyList()
        )
    }
}

/**
 * 空状态显示
 */
@Composable
private fun EmptyState(
    isSearching: Boolean,
    showCachedOnly: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                AmperfyIcons.musicalNotes,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    isSearching -> "No results found"
                    showCachedOnly -> "No cached content"
                    else -> "No content available"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    isSearching -> "Try a different search term"
                    showCachedOnly -> "Download some content first"
                    else -> "This artist has no albums or songs"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

