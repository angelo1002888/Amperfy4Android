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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.amperfy.ui.components.IOSPullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.SheetSystemBarsFix
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildPlaylistContextMenuItems
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.sheetBackground
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * 叠层弹层的顶边下沉量：第二层（Add Songs）顶边比第一层（Edit）低这么多，
 * 缝里露出下层的顶圆角与底色。
 *
 * 对应 iOS UISheetPresentationController 堆叠呈现时第二层的系统间距——该值无公开源码，
 * 按真机观感标定近似。
 */
private val STACKED_SHEET_TOP_GAP = 10.dp

/**
 * PlaylistDetailScreen - 播放列表详情页（iOS 风格）
 *
 * 对应 iOS: PlaylistDetailVC.swift
 *
 * 右上角与 iOS 一致：Edit 按钮（打开独立编辑页，仅在线模式可用）+ More（ellipsis）按钮，
 * More 菜单为标准上下文菜单（与其它 Detail 页复用 IOSStyleContextMenu 组件），
 * 内容对齐 iOS EntityPreviewActionBuilder(container: playlist)：
 * Play / Shuffle | Music Queue | Add to Playlist / Download / Delete Cache。
 * 整删播放列表入口在 Playlists 列表的 Edit 模式（与 iOS 一致，详情页不提供）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBackClick: () -> Unit,
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
    editViewModel: PlaylistEditViewModel = hiltViewModel()
) {
    val playlist by viewModel.playlist.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    // More 菜单条件显隐依据（对应 iOS EntityPreviewVC:315-331）
    val hasCachedSongs = remember(songs) { songs.any { it.isDownloaded } }
    // 全缓存判定（对应 iOS isCachedCompletely）：菜单据此隐藏 Download
    val isFullyCached by viewModel.isFullyCached.collectAsState()
    // 顶栏 More 菜单所需：Detailed Information（Copy ID 项） + 剪贴板
    val isShowDetailedInfo by com.amperfy.ui.navigation.LocalSettingsManager.current
        .isShowDetailedInfo.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    var showMoreMenu by remember { mutableStateOf(false) }

    // Shuffle 菜单项禁用状态（Disable Player Shuffle Button，对应 iOS EntityPreviewVC .disabled）
    val isShuffleMenuItemEnabled by com.amperfy.ui.navigation.LocalSettingsManager.current
        .isPlayerShuffleButtonEnabled.collectAsState()
    // Edit 模态（自下而上弹出，覆盖全屏）—— 对应 iOS: present(PlaylistEditVC)
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var editName by rememberSaveable { mutableStateOf("") }
    val editSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Add Songs 模态（自下而上弹出，覆盖全屏）—— 对应 iOS: present(PlaylistAddLibraryVC 导航栈)
    // 由 Edit 模态底部的「+」打开（iOS PlaylistEditVC.swift:210-220 直接 present，两层堆叠）
    var showAddSongsSheet by rememberSaveable { mutableStateOf(false) }

    // 首次显示时从服务器同步歌曲
    LaunchedEffect(Unit) { viewModel.fetch() }

    // 滑动动作配置
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val leadingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.leading, SwipeContentType.MUSIC, isOfflineMode)
    }
    val trailingSwipeActions = remember(swipeSettings, isOfflineMode) {
        SwipeDisplaySettings.filter(swipeSettings.trailing, SwipeContentType.MUSIC, isOfflineMode)
    }

    val swipeController = rememberSwipeController()
    val swipeScope = rememberCoroutineScope()
    val nestedScrollConnection = remember(swipeController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) swipeController.closeCurrentItem(swipeScope)
                return Offset.Zero
            }
        }
    }

    // 删除缓存确认对话框（More 菜单 Delete Cache / 滑动 REMOVE_FROM_CACHE 触发）
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { pending ->
        DeleteCacheConfirmDialog(
            songCount = pending.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteCache() }
        )
    }

    // 添加到播放列表选择器（More 菜单 Add to Playlist / 滑动 ADD_TO_PLAYLIST 触发）
    val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
    pendingPlaylistSongIds?.let { ids ->
        PlaylistSelectorDialog(
            songIds = ids,
            onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
        )
    }

    // Edit 模态：自下而上弹出、覆盖全屏（对应 iOS PlaylistEditVC）
    if (showEditSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                editViewModel.rename(editName)
                showEditSheet = false
            },
            sheetState = editSheetState,
            dragHandle = null,
            // 顶边止于状态栏下缘 + 顶圆角 10dp（对齐 iOS pageSheet）
            modifier = Modifier.statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            // 底色与 SongListItem 行自画的 background 同色，消除行块凸显
            // （对齐 iOS PlaylistEditVC.swift:264-274 的 pageSheet 模态 +
            //  Utilities.swift:366 tableView.backgroundColor = systemBackground，
            //  模态呈现故取 elevated 提升层值）
            containerColor = MaterialTheme.colorScheme.sheetBackground
        ) {
            // sheet 独立窗口的系统栏图标明暗归位（否则状态栏在弹出瞬间变黑）
            SheetSystemBarsFix()
            // 本层不做任何变换：AddSongs 叠上来时 Edit 原地不动，只靠 AddSongs 顶边下沉的
            // STACKED_SHEET_TOP_GAP 露出本层顶部一条（底层页不联动缩小）
            Box(modifier = Modifier.fillMaxSize()) {
                PlaylistEditSheet(
                    name = editName,
                    onNameChange = { editName = it },
                    onDone = {
                        editViewModel.rename(editName)
                        showEditSheet = false
                    },
                    onAddSongs = {
                        // 直接**叠开** Add Songs（对齐 iOS PlaylistEditVC.swift:210-220：
                        // 从编辑模态里 present 添加歌曲的导航栈，两层堆叠、关上层回到编辑层）。
                        // 此处刻意不 rename、不关闭 Edit：改名提交只在 Edit 自身的关闭出口
                        // （Done / 下滑关闭），对齐 iOS viewDidDisappear → endEditing 的时机
                        showAddSongsSheet = true
                    },
                    viewModel = editViewModel
                )
            }
        }
    }

    // Add Songs 模态：参数与 Settings/Add Account 覆盖层同款（skipPartiallyExpanded 完全展开 +
    // dragHandle=null + 顶边止于状态栏下缘 + 顶圆角 10dp），对齐 iOS 的 pageSheet 模态导航栈；
    // 关闭系统返回键的默认 dismiss，交由 PlaylistAddSongsScreen 内部 BackHandler 逐级回退
    if (showAddSongsSheet) {
        val addSongsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAddSongsSheet = false },
            sheetState = addSongsSheetState,
            dragHandle = null,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
            // 顶边止于状态栏下缘 + 顶圆角 10dp（对齐 iOS pageSheet）；
            // 状态栏避让改由 sheet modifier 承担，内容不再重复 statusBarsPadding。
            // 再下沉 STACKED_SHEET_TOP_GAP：本弹层只从 Edit 的「+」叠开、恒为第二层，
            // 缝里露出下层 Edit 的顶部（iOS 堆叠观感），故无需条件化
            modifier = Modifier.statusBarsPadding().padding(top = STACKED_SHEET_TOP_GAP),
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
            // iOS PlaylistAdd 全族 VC 一律 tableView.backgroundColor = .backgroundColor
            // = systemBackground（模态呈现取 elevated 提升层值）
            containerColor = MaterialTheme.colorScheme.sheetBackground
        ) {
            // sheet 独立窗口的系统栏图标明暗归位（否则状态栏在弹出瞬间变黑）
            SheetSystemBarsFix()
            Box(modifier = Modifier.fillMaxSize()) {
                PlaylistAddSongsScreen(onClose = { showAddSongsSheet = false })
            }
        }
    }

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Playlists",
                // 名称在正文 Header 展示，导航栏不显示居中标题
                // 对齐 iOS PlaylistDetailVC：largeTitleDisplayMode=.never 且不调用 setNavBarTitle
                title = null,
                actions = {
                    // Edit —— 对应 iOS: editButton（自下而上弹出编辑模态），仅在线模式可用
                    if (!isOfflineMode) {
                        TextButton(onClick = {
                            editName = playlist?.name.orEmpty()
                            showEditSheet = true
                        }) {
                            Text("Edit")
                        }
                    }
                    // More —— 对应 iOS: optionsButton（ellipsis），标准上下文菜单
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(AmperfyIcons.ellipsis, contentDescription = "More")
                        }
                        // 顶栏 More 与列表行长按同源 - 对应 iOS PlaylistDetailVC.swift:161
                        // `optionsButton.menu = EntityPreviewActionBuilder(container: playlist).createMenuActions()`
                        // 注：iOS 的 Edit 是菜单外的独立 bar button（PlaylistDetailVC.swift:153-158），
                        // Android 同样保留左侧独立 Edit 按钮，菜单只放实体动作
                        IOSStyleContextMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(-72, 48),
                            items = buildPlaylistContextMenuItems(
                                env = MenuEnv(
                                    isOfflineMode = isOfflineMode,
                                    isShuffleActionEnabled = isShuffleMenuItemEnabled,
                                    isShowDetailedInfo = isShowDetailedInfo
                                ),
                                hasCachedSongs = hasCachedSongs,
                                onAction = { action -> viewModel.handlePlaylistAction(action) },
                                onCopyId = {
                                    playlist?.id?.takeIf { it.isNotEmpty() }?.let {
                                        clipboardManager.setText(AnnotatedString(it))
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
        // Header 封面 URL 在组合期缓存，避免每次重组重建（与 Edit 模态一致）
        val coverUrls = remember(playlist?.coverArt, songs) {
            headerCoverUrls(playlist?.coverArt, songs, viewModel)
        }
        // 下拉刷新（对应 iOS PlaylistDetailVC refreshControl -> playlist.fetch）
        IOSPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.fetch() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .nestedScroll(nestedScrollConnection),
                contentPadding = PaddingValues(bottom = miniPlayerHeight)
            ) {
            // Header：封面 + 名称 + 信息 + Play/Shuffle
            item(key = "header") {
                PlaylistHeader(
                    name = playlist?.name ?: "",
                    info = viewModel.getPlaylistInfo(),
                    coverUrls = coverUrls,
                    enabled = songs.isNotEmpty(),
                    onPlay = { viewModel.playAll(shuffled = false) },
                    onShuffle = { viewModel.playAll(shuffled = true) }
                )
            }

            if (songs.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No songs in this playlist",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // 位置复合 key：播放列表允许重复歌曲
                itemsIndexed(songs, key = { i, s -> "${s.id}_$i" }) { index, song ->
                    SwipeableItem(
                        key = "${song.id}_$index",
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
                            downloadProgressMap = downloadProgressMap,
                            callbacks = SongListItemCallbacks(
                                onClick = { viewModel.playSong(song) },
                                // Shuffle = 以本播放列表为上下文乱序播放
                                onShuffle = { viewModel.playAll(shuffled = true) },
                                onToggleFavorite = { viewModel.toggleFavorite(song) },
                                onSetRating = { viewModel.setSongRating(song, it) },
                                onInsertContextQueue = { viewModel.insertContextQueue(song) },
                                onAppendContextQueue = { viewModel.appendContextQueue(song) },
                                onAddToQueueNext = { viewModel.addToQueueNext(song) },
                                onAddToQueueLater = { viewModel.addToQueueLater(song) },
                                onShowAlbum = { song.albumId?.let(onNavigateToAlbum) },
                                onShowArtist = { song.artistId?.let(onNavigateToArtist) },
                                onDownload = { viewModel.downloadSong(song) },
                                onDeleteCache = { viewModel.deleteCache(song) }
                            )
                        )
                    }
                    // 行间分隔线由 SongListItem 内置（16dp..屏幕右缘），此处不再重复画
                }
            }
            }
        }
    }
}

/**
 * 计算 Header 封面 URL 列表：优先用播放列表自身封面，否则用前 4 首歌曲封面拼贴
 */
private fun headerCoverUrls(
    playlistCoverArt: String?,
    songs: List<Song>,
    viewModel: PlaylistDetailViewModel
): List<String> {
    if (!playlistCoverArt.isNullOrBlank()) {
        viewModel.getCoverArtUrl(playlistCoverArt)?.let { return listOf(it) }
    }
    return songs.mapNotNull { it.coverArt }
        .distinct()
        .take(4)
        .mapNotNull { viewModel.getCoverArtUrl(it) }
}

/**
 * 播放列表详情 Header
 */
@Composable
private fun PlaylistHeader(
    name: String,
    info: String,
    coverUrls: List<String>,
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlaylistMosaicCover(
            coverUrls = coverUrls,
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = info,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondaryLabel
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Play / Shuffle：与全部详情页共用 iOS LibraryElementDetailTableHeaderView 的唯一实现
        //（灰底 .secondarySystemFill + 主题色内容 + 10dp 圆角；Disable Player Shuffle Button
        // 门控内置）。[enabled] 为整块 deactivate 语义（iOS :168-180），两键同吃
        PlayShuffleButtons(
            onPlay = onPlay,
            onShuffle = onShuffle,
            enabled = enabled
        )
    }
}

/**
 * 4 图拼贴封面（不足 4 张时退化为单图/占位）
 * 对应 iOS: `Playlist.getArtworkCollection`（Playlist.swift:534-552）——
 * artworkItems 为空 → 只有 defaultArtworkType（`.playlist`）；1 张 → 单图；≥2 张 → 取前 4 张四宫格。
 *
 * 各槽位的兜底一律是**主题化的 playlist 默认艺术图**（iOS ArtworkType.playlist =
 * 反色：背景主题色 + 灰阶图标，UIImageAssetsExtension.swift:458-466），
 * 与 iOS `LibraryEntityImage` 取不到图时回落 defaultArtworkType 的语义一致。
 */
@Composable
internal fun PlaylistMosaicCover(
    coverUrls: List<String>,
    modifier: Modifier = Modifier
) {
    // 兜底 Painter **每个绘制目标各建一个**（分支内各自 remember、四宫格每格各自建，
    // 见 [MosaicTile]）——Painter 内含按尺寸缓存的绘制状态，多个绘制目标共享会互相污染
    //（2026-08-09 真机报障的同类隐患，见 EntityPreviewCard 的 defaultArtworkType 参数注释）
    Box(modifier = modifier) {
        when {
            coverUrls.isEmpty() -> {
                Image(
                    painter = rememberDefaultArtworkPainter(DefaultArtworkType.PLAYLIST),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            coverUrls.size < 4 -> {
                val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.PLAYLIST)
                AsyncImage(
                    model = coverUrls.first(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = defaultArtwork,
                    error = defaultArtwork,
                    fallback = defaultArtwork
                )
            }
            else -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        MosaicTile(coverUrls[0], Modifier.weight(1f).fillMaxHeight())
                        MosaicTile(coverUrls[1], Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        MosaicTile(coverUrls[2], Modifier.weight(1f).fillMaxHeight())
                        MosaicTile(coverUrls[3], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/**
 * 四宫格单格：真实封面优先，加载中/失败回落主题化 playlist 默认图。
 *
 * 兜底 Painter **每格各建一个**（不从外部传入共享实例）：`DefaultArtworkPainter` 内包的
 * `VectorPainter` 按上次绘制尺寸缓存合成结果，同一实例被多个绘制目标驱动会互相污染
 *（2026-08-09 真机报障的同类隐患，见 EntityPreviewCard 的 defaultArtworkType 参数注释）
 */
@Composable
private fun MosaicTile(
    coverUrl: String,
    modifier: Modifier = Modifier
) {
    val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.PLAYLIST)
    AsyncImage(
        model = coverUrl,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = defaultArtwork,
        error = defaultArtwork,
        fallback = defaultArtwork
    )
}
