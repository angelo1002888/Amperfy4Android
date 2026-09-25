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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Genre
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.AlbumListItem
import com.amperfy.ui.components.AlbumListItemCallbacks
// 艺术家行与 ArtistsScreen 共用同一组件（iOS 两处本就是同一个 GenericTableCell）
import com.amperfy.ui.components.ArtistListItem
import com.amperfy.ui.components.GroupedSectionHeader
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildGenreContextMenuItems
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * GenreDetailScreen - 流派详情（Phase 6.1）
 * 对应 iOS: GenreDetailVC（MultiSourceTableViewController，三固定段）
 *
 * - 三段：Artists / Albums / Songs（有内容才显示段头，iOS 行 303-321）；
 *   段头用普通 item **不吸顶**（iOS .grouped 段头随内容滚走）
 * - 头部：GenericDetailTableHeader 形态——大封面（流派恒为占位图）+ 居中流派名
 *   + 居中 info 行（Genre.swift:90-115 infoDetails）+ Play/Shuffle
 * - 艺术家行 = 与 ArtistsScreen 同源的 `ArtistListItem`（iOS 同一个 GenericTableCell），
 *   带滑动手势（iOS GenreDetailVC swipeCallback 的 artist 分支）
 * - 搜索（placeholder 对齐 iOS "Artists, Albums and Songs"）+ All/Cached 作用域
 * - 进入时同步流派内容（iOS viewIsAppearing → sync(genre:)）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreDetailScreen(
    onBackClick: () -> Unit,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    // 歌曲行 More 菜单 Show Album / Show Artist 导航（iOS EntityPreviewActionBuilder）
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    viewModel: GenreDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // 头部 info 行的计数来源（对应 iOS Genre.albumCount / songCount）
    val genre by viewModel.genre.collectAsState()
    val artists by viewModel.filteredArtists.collectAsState()
    val albums by viewModel.filteredAlbums.collectAsState()
    val songs by viewModel.filteredSongs.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    // 艺术家行的滑动手势（对应 iOS GenreDetailVC swipeCallback 的 artist 分支）
    val swipeController = rememberSwipeController()
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val isSwipeOfflineMode by viewModel.isOfflineMode.collectAsState()
    val leadingSwipeActions = remember(swipeSettings, isSwipeOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.leading, SwipeContentType.MUSIC, isSwipeOfflineMode)
    }
    val trailingSwipeActions = remember(swipeSettings, isSwipeOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.trailing, SwipeContentType.MUSIC, isSwipeOfflineMode)
    }
    // 艺术家行长按菜单的缓存门控（Play/Shuffle 离线可用性、Download / Delete Cache 显隐）
    val cachedArtistIds by viewModel.cachedArtistIds.collectAsState()
    val fullyCachedArtistIds by viewModel.fullyCachedArtistIds.collectAsState()

    // 对应 iOS viewIsAppearing → genre.fetch()
    LaunchedEffect(Unit) {
        viewModel.fetch()
    }

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    // 作用域行仅在搜索激活时显示（iOS scope buttons 默认行为）；隐藏时重置回 All
    val showScopeBar = searchState.isPinned || uiState.searchText.isNotEmpty()
    LaunchedEffect(showScopeBar) {
        if (!showScopeBar) viewModel.setCachedScope(false)
    }

    // 顶栏 More 菜单（与流派列表行长按同源）所需状态
    var showMoreMenu by remember { mutableStateOf(false) }
    val hasCachedSongs by viewModel.hasCachedSongs.collectAsState()
    val isFullyCached by viewModel.isFullyCached.collectAsState()
    val genreMenuSettings = com.amperfy.ui.navigation.LocalSettingsManager.current
    val isOfflineMode by genreMenuSettings.isOfflineMode.collectAsState()
    val isShowDetailedInfo by genreMenuSettings.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by genreMenuSettings.isPlayerShuffleButtonEnabled.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    // 删除缓存确认对话框（专辑行长按菜单 Delete Cache 触发，走 SwipeActionCoordinator）
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { pendingSongs ->
        DeleteCacheConfirmDialog(
            songCount = pendingSongs.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteCache() }
        )
    }

    // 添加到播放列表选择器（歌曲行 More 菜单 Add to Playlist、专辑行长按菜单触发）
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
                    placeholder = "Artists, Albums and Songs",
                    scopeContent = scopeRow
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Genres",
                title = viewModel.genreName,
                centered = true,
                actions = {
                    // 顶栏 More 与流派列表行长按同源 - 对应 iOS GenreDetailVC.swift:110
                    // `optionsButton.menu = EntityPreviewActionBuilder(container: genre).createMenuActions()`
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(AmperfyIcons.ellipsis, contentDescription = "More options")
                        }
                        IOSStyleContextMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(-72, 48),
                            items = buildGenreContextMenuItems(
                                env = MenuEnv(
                                    isOfflineMode = isOfflineMode,
                                    isShuffleActionEnabled = isShuffleActionEnabled,
                                    isShowDetailedInfo = isShowDetailedInfo
                                ),
                                hasCachedSongs = hasCachedSongs,
                                onAction = { action -> viewModel.handleGenreAction(action) },
                                // Subsonic 流派无 id，Copy ID 复制 name（唯一标识）
                                onCopyId = {
                                    if (viewModel.genreName.isNotEmpty()) {
                                        clipboardManager.setText(AnnotatedString(viewModel.genreName))
                                    }
                                },
                                isFullyCached = isFullyCached
                            )
                        )
                    }
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
                    .nestedScroll(searchState.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = miniPlayerHeight)
            ) {
                // 搜索栏 + 作用域（iOS configureSearchController，GenreDetailVC.swift:80——
                // "Artists, Albums and Songs" + scopeButtonTitles ["All","Cached"]）；
                // 恰好一个 item，显隐/钉顶/焦点行为全在 LibrarySearchState 内
                librarySearchBarItem(
                    state = searchState,
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Artists, Albums and Songs",
                    scopeContent = scopeRow
                )

                // 头部（对应 iOS GenericDetailTableHeader：大封面 + 居中标题 + 居中 info 行
                // + LibraryElementDetailTableHeaderView 的 Play/Shuffle）
                item {
                    GenreDetailHeader(
                        genreName = viewModel.genreName,
                        info = genreInfoDetails(genre, isShowDetailedInfo),
                        onPlayAll = { viewModel.playAll() },
                        onShuffle = { viewModel.shuffleAll() }
                    )
                }

                // Artists 段（对应 iOS LibraryElement.Artist section）
                // 段头用普通 item：iOS .grouped 表的段头**不吸顶**
                if (artists.isNotEmpty()) {
                    item { GenreSectionHeader(title = "Artists") }
                    items(artists, key = { "artist_${it.id}" }) { artist ->
                        // 行组件与 ArtistsScreen 同源（iOS 两处本就是同一个 GenericTableCell）
                        SwipeableItem(
                            key = "genre_artist_${artist.id}",
                            swipeController = swipeController,
                            leadingActions = leadingSwipeActions,
                            trailingActions = trailingSwipeActions,
                            onSwipeAction = { action ->
                                viewModel.handleArtistSwipeAction(artist, action)
                            },
                            isFavorite = artist.isFavorite
                        ) {
                            ArtistListItem(
                                artist = artist,
                                onClick = { onArtistClick(artist) },
                                onToggleFavorite = {
                                    viewModel.handleArtistSwipeAction(artist, SwipeActionType.FAVORITE)
                                },
                                hasCachedSongs = artist.id in cachedArtistIds,
                                isFullyCached = artist.id in fullyCachedArtistIds,
                                onSwipeAction = { action ->
                                    viewModel.handleArtistSwipeAction(artist, action)
                                },
                                onSetRating = { rating -> viewModel.setArtistRating(artist, rating) }
                            )
                        }
                        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
                        // separatorInset = cell layoutMargins 左右值，
                        // CommonScreenOperations.swift:41-47）
                        HairlineDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.separator
                        )
                    }
                }

                // Albums 段（对应 iOS LibraryElement.Album section）
                if (albums.isNotEmpty()) {
                    item { GenreSectionHeader(title = "Albums") }
                    items(albums, key = { "album_${it.id}" }) { album ->
                        // 滑动手势（iOS GenreDetailVC.swift:179-196 swipeCallback 的 album 分支）
                        SwipeableItem(
                            key = "genre_album_${album.id}",
                            swipeController = swipeController,
                            leadingActions = leadingSwipeActions,
                            trailingActions = trailingSwipeActions,
                            onSwipeAction = { action ->
                                viewModel.handleAlbumSwipeAction(album, action)
                            },
                            isFavorite = album.isFavorite
                        ) {
                            AlbumListItem(
                                album = album,
                                callbacks = AlbumListItemCallbacks(
                                    onClick = { onAlbumClick(album) }
                                ),
                                showDivider = true,
                                // 长按上下文菜单：动作走 Album.handleSwipeAction（与滑动同源）
                                onSwipeAction = { action -> viewModel.handleAlbumSwipeAction(album, action) },
                                onSetRating = { rating -> viewModel.setAlbumRating(album, rating) },
                                onShowArtist = album.artistId?.let { artistId ->
                                    { onNavigateToArtist(artistId) }
                                }
                            )
                        }
                    }
                }

                // Songs 段（对应 iOS LibraryElement.Song section，行点击播放不导航）
                if (songs.isNotEmpty()) {
                    item { GenreSectionHeader(title = "Songs") }
                    items(songs, key = { "song_${it.id}" }) { song ->
                        // 滑动手势（iOS GenreDetailVC.swift:197-201 swipeCallback 的 song 分支）
                        SwipeableItem(
                            key = "genre_song_${song.id}",
                            swipeController = swipeController,
                            leadingActions = leadingSwipeActions,
                            trailingActions = trailingSwipeActions,
                            onSwipeAction = { action ->
                                viewModel.handleSongSwipeAction(song, action)
                            },
                            isFavorite = song.isFavorite
                        ) {
                            SongListItem(
                                song = song,
                                style = SongListItemStyle.ARTWORK,
                                isPlaying = currentSong?.id == song.id && isPlaying,
                                callbacks = SongListItemCallbacks(
                                    onClick = { viewModel.playSong(song) },
                                    // Shuffle = 以本流派歌曲列表为上下文乱序播放
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
                }

                // 空状态
                if (artists.isEmpty() && albums.isEmpty() && songs.isEmpty() && !uiState.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.searchText.isNotEmpty()) "No results"
                                else "No content for this genre",
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
 * 流派 info 行文本 - 对应 iOS `Genre.infoDetails(for:details:)`
 * （AmperfyKit/Storage/EntityWrappers/Genre.swift:90-115）：
 * - Artists 计数段仅 Ampache 有（:92-98），Subsonic 不出现
 * - `X Album(s)` / `Y Song(s)`：计数为 0 的段**整段省略**，为 1 时用单数
 * - details.type == .long 且开了 Detailed Information 时追加 `ID: <id>`（:109-113）；
 *   Subsonic 流派无服务器 id，本项目以 name 作唯一标识（见 Genre.kt）
 * - 分隔符 " · "（CommonString.oneMiddleDot）
 *
 * 注：不能直接复用 `Genre.info`——那是列表行用的 short 形态，0 计数不省略、也无 ID 段。
 */
private fun genreInfoDetails(genre: Genre?, isShowDetailedInfo: Boolean): String {
    val parts = buildList {
        val albumCount = genre?.albumCount ?: 0
        val songCount = genre?.songCount ?: 0
        if (albumCount == 1) add("1 Album") else if (albumCount > 1) add("$albumCount Albums")
        if (songCount == 1) add("1 Song") else if (songCount > 1) add("$songCount Songs")
        if (isShowDetailedInfo) {
            val id = genre?.name.orEmpty()
            add("ID: ${id.ifEmpty { "-" }}")
        }
    }
    return parts.joinToString(" · ")
}

/**
 * 流派详情头部 - 对应 iOS `GenericDetailTableHeader`（compact 总高 400pt）：
 * 大封面（居中）+ 居中流派名 + 居中 info 行 + `LibraryElementDetailTableHeaderView` 的 Play/Shuffle。
 *
 * 尺寸与观感对齐本项目既有详情页头部（AlbumDetailScreen.AlbumDetailHeader）：
 * 宽度 70% 的方形 Card、12dp 圆角、居中排布——两页同源，避免各自一套。
 *
 * 封面：Subsonic 流派无服务器封面，恒走主题化默认艺术图，对应 iOS
 * `getGeneratedArtwork(theme:artworkType: .genre)`（Genre.swift:137 → ArtworkType.genre）；
 * 与流派长按预览卡（EntityPreviewCard 的 `defaultArtworkType = GENRE`）**同一实现**，
 * 见 ui/util/DefaultArtwork.kt；两处各自持有 Painter 实例（禁止共享，见该文件使用纪律）。
 */
@Composable
private fun GenreDetailHeader(
    genreName: String,
    info: String,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 大封面占位（同 AlbumDetailHeader 的 0.7f 方形 + 12dp 圆角）
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(1f),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                // 灰底 + 主题色图标 + 0.8 内缩比例，全部由 painter 承担
                //（iOS createArtwork 的 big 档，UIImageAssetsExtension.swift:28-33、481-508）
                Image(
                    painter = rememberDefaultArtworkPainter(DefaultArtworkType.GENRE),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 流派名 - 对应 iOS GenericDetailTableHeader.titleLabel（居中）
            Text(
                text = genreName,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // info 行 - 对应 iOS GenericDetailTableHeader.infoLabel
            //（规则 isHidden = infoText.isEmpty，故为空时整行不占位）
            if (info.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            PlayShuffleButtons(
                onPlay = onPlayAll,
                onShuffle = onShuffle
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // 头部与列表的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）
        HairlineDivider(
            color = MaterialTheme.colorScheme.separator
        )
    }
}

/**
 * 段头 - 对应 iOS titleForHeaderInSection（"Artists"/"Albums"/"Songs"，
 * heightForHeaderInSection = tableSectionHeightLarge，GenreDetailVC.swift:338-344）
 */
@Composable
private fun GenreSectionHeader(title: String) {
    // 段头随内容滚走（普通 item，非 stickyHeader）→ 无需 Surface 撑不透明底色
    Column(modifier = Modifier.fillMaxWidth()) {
        // 段头文本走共享组件（与 ArtistDetailScreen 同源，iOS 同一套 grouped 段头）
        GroupedSectionHeader(title = title)
        // section 头与其下列表的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）；
        // 刻意留在宿主层——GroupedSectionHeader 不带线，避免与各页边界线画成双线
        HairlineDivider(
            color = MaterialTheme.colorScheme.separator
        )
    }
}

// 艺术家行已改用共享组件 ui/components/ArtistListItem.kt
// （iOS GenreDetailVC 的 Artists 段与 ArtistsVC 本就是同一个 GenericTableCell）
