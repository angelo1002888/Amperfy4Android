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

package com.amperfy.ui.screens.player.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.amperfy.data.model.Playable
import com.amperfy.data.model.RepeatMode
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.player.PlayerIndex
import com.amperfy.player.PlayerQueueType
import com.amperfy.ui.components.EntityPreviewCard
import com.amperfy.ui.components.FavoriteButton
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSLongPressPreviewMenu
import com.amperfy.ui.components.swipe.SwipeController
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.screens.player.PlayerDisplayMode
import com.amperfy.ui.screens.player.PopupPlayerViewModel
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.defaultArtworkTypeFor
import com.amperfy.ui.util.rememberDefaultArtworkPainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Queue list view with CurrentlyPlayingTableCell header
 * iOS: TableView in PopupPlayerVC with CurrentlyPlayingTableCell as section header
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueListView(
    viewModel: PopupPlayerViewModel,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSwitchToLarge: () -> Unit,
    listState: LazyListState,
    // Menu actions
    rating: Int = 0,
    isCached: Boolean = false,
    isOnlineMode: Boolean = true,
    hasLyrics: Boolean = false,
    onShowAlbum: () -> Unit = {},
    onShowArtist: () -> Unit = {},
    onShowLyrics: () -> Unit = {},
    onSetRating: (Int) -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onDownload: () -> Unit = {},
    onDeleteCache: () -> Unit = {},
    // 队列行长按菜单的跳转（按 id 导航并收起播放器，对应 iOS performPreviewTransition）
    onShowAlbumById: (String) -> Unit = {},
    onShowArtistById: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current

    val playlist by viewModel.playlist.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()

    // 三层队列数据（对应iOS: PopupPlayerVC - TableView sections）
    val prevQueue by viewModel.prevQueue.collectAsState()
    val userQueue by viewModel.userQueue.collectAsState()
    val contextNextQueue by viewModel.contextNextQueue.collectAsState()  // iOS: contextNext - "Next From"
    val contextName by viewModel.contextName.collectAsState()  // iOS: player.contextName - for "Next From" header

    // Batch 2："Next From" 段头的 shuffle / repeat 按钮状态（iOS: ContextQueueNextSectionHeader）
    val isShuffle by viewModel.isShuffle.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val isShuffleButtonEnabled by viewModel.isShuffleButtonEnabled.collectAsState()
    val playerMode by viewModel.playerMode.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    // 拖拽重排（Batch 3，2026-08-03 起支持跨 section 拖动，对齐 iOS
    // PopupPlayer+TableViewExtension.moveRowAt → PlayQueueHandler.movePlayable）：
    // Previous / Next in Queue / Next From 三段合用一份拖拽状态，任意段的行可拖到任意其他段；
    // 触觉反馈为 Android 增强（iOS 拖拽无震动）
    val haptics = LocalHapticFeedback.current
    val settingsManager = LocalSettingsManager.current
    val isHapticsEnabled by settingsManager.isHapticsEnabled.collectAsState()
    val hapticsCallback = remember(isHapticsEnabled) {
        { if (isHapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    val dragState = remember { QueueDragState() }
    // 拖拽几何兜底初值（真实高度由各段头 / Currently Playing 行 / 队列行的 onSizeChanged 覆盖）
    val density = LocalDensity.current
    remember(density) {
        with(density) {
            dragState.rowHeightPx = QUEUE_ROW_HEIGHT.roundToPx()
            dragState.prevHeaderPx = QUEUE_PREV_HEADER_HEIGHT.roundToPx()
            dragState.userHeaderPx = QUEUE_USER_HEADER_HEIGHT.roundToPx()
            dragState.nextHeaderPx = QUEUE_NEXT_HEADER_HEIGHT.roundToPx()
            dragState.currentlyPlayingPx = QUEUE_CURRENTLY_PLAYING_HEIGHT.roundToPx()
        }
        density
    }
    // 拖拽提交 / 取消回滚：均为稳定 lambda——拖拽手势挂在 pointerInput(Unit) 上不重启，
    // 捕获的 lambda 不会随重组更新，故内部一律现读 StateFlow.value，不捕获快照值
    val commitQueueMove: (PlayerIndex, PlayerIndex) -> Unit = remember(viewModel) {
        { from, to -> viewModel.movePlayable(from, to) }
    }
    val resyncQueueCopies: () -> Unit = remember(viewModel, dragState) {
        {
            dragState.prevItems.clear()
            dragState.prevItems.addAll(
                buildQueueEntries(QueueKeyPrefix.PREV, viewModel.prevQueue.value)
            )
            dragState.userItems.clear()
            dragState.userItems.addAll(
                buildQueueEntries(QueueKeyPrefix.USER, viewModel.userQueue.value)
            )
            dragState.nextItems.clear()
            dragState.nextItems.addAll(
                buildQueueEntries(QueueKeyPrefix.NEXT, viewModel.contextNextQueue.value)
            )
        }
    }
    // 非拖拽期按队列 Flow 刷新工作副本；拖拽中冻结，避免打断进行中的手势。
    // **三段必须一次性整体重建**：条目跨段搬移后 key 仍带原段前缀，若分三个 LaunchedEffect
    // 各自刷新，源段先重建、目标段暂未重建的那一帧里，源段可能重新生成与在途条目相同的 key
    // （同一首歌在同段出现多次时），LazyColumn 撞 key 直接抛异常。整体重建则任一时刻
    // 三段副本都出自同一次构建，key 全局唯一。
    LaunchedEffect(prevQueue, userQueue, contextNextQueue) {
        if (dragState.draggingSection == null) resyncQueueCopies()
    }

    // 滑动控制器 - 队列行滑动移出（对应 iOS: UITableView delete editingStyle）
    val swipeController = rememberSwipeController()
    val nestedScrollConnection = remember(swipeController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y != 0f) {
                    swipeController.closeCurrentItem(coroutineScope)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val queueSwipeActions = remember { listOf(SwipeActionType.REMOVE_FROM_QUEUE) }

    // 歌曲切换时动画滚动到 Currently Playing
    // 注：打开播放器时的初始定位已由 listState 的 initialFirstVisibleItemIndex 完成
    // （PopupPlayerScreen 创建时经 initialQueueScrollIndex 计算，首帧即在正确位置，
    // 修复此前先渲染 Previous 顶部再延迟跳转的视觉跳动）
    var isInitialComposition by remember { mutableStateOf(true) }
    LaunchedEffect(currentSong?.id) {
        if (isInitialComposition) {
            isInitialComposition = false
            return@LaunchedEffect
        }
        delay(300) // 等待队列 Flow 更新与列表稳定后再滚动
        val currentlyPlayingIndex = if (prevQueue.isNotEmpty()) {
            1 + prevQueue.size // Previous header + prev 行数
        } else {
            0
        }
        listState.animateScrollToItem(currentlyPlayingIndex)
    }

    // 滚动到当前播放行（对应 iOS scrollToCurrentlyPlayingRow）。
    // 必须在子协程（launch）中执行且先等用户滚动结束：程序滚动是 Default 优先级，
    // 用户触摸/惯性滚动（UserInput）尚未结束时发起会被滚动互斥直接拒绝、
    // 动画中被触摸打断同样抛 CancellationException——若直接在 collect 内挂起，
    // 该异常会连带取消整个 LaunchedEffect(Unit) 且永不重启，导致本次打开播放器内
    // "恢复播放滚回当前行/菜单滚动到当前"永久失效（惯性未停时点播放即可触发）
    val scrollToCurrentlyPlayingRow: suspend () -> Unit = {
        snapshotFlow { listState.isScrollInProgress }.first { !it }
        listState.animateScrollToItem(viewModel.initialQueueScrollIndex())
    }

    // 切换到 COMPACT（本视图进入组合）时先瞬时定位到当前播放行——
    // 对应 iOS changeDisplayStyleVisually(.compact) 动画前先 scrollToCurrentlyPlayingRow()，
    // 同时保证共享元素动画的落点行（sticky 当前播放行）在视口内。
    // 首次打开播放器时 listState 初始索引已在该位置，此处为 no-op
    LaunchedEffect(Unit) {
        listState.scrollToItem(viewModel.initialQueueScrollIndex())
    }

    // 菜单 "Scroll to currently playing" 事件
    LaunchedEffect(Unit) {
        viewModel.scrollToCurrentlyPlayingEvent.collect {
            launch { scrollToCurrentlyPlayingRow() }
        }
    }

    // 暂停状态下点击播放（恢复播放）：当前播放歌曲行滚动到第一行
    // （iOS: didStartPlaying → reloadData → scrollToCurrentlyPlayingRow）
    LaunchedEffect(Unit) {
        var wasPlaying = viewModel.isPlaying.value
        viewModel.isPlaying.collect { playing ->
            if (playing && !wasPlaying) {
                launch { scrollToCurrentlyPlayingRow() }
            }
            wasPlaying = playing
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Queue list (iOS: TableView with sections)
        // All sections in one LazyColumn: Previous, Currently Playing, Next in Queue, Next From
        // 底部 padding 按差额补足：默认仅 16dp 最小留白（滚到底不再出现大片空白，
        // 此前固定加了约一屏的 padding）；仅当"当前播放行及以下内容"不足一屏时，
        // 补足缺口，保证打开播放器时当前播放歌曲行能定位在列表第一行
        var listHeightPx by remember { mutableStateOf(0) }
        val bottomPadding = with(androidx.compose.ui.platform.LocalDensity.current) {
            val rowHeight = QUEUE_ROW_HEIGHT            // QueueItem 固定行高
            val userHeaderHeight = QUEUE_USER_HEADER_HEIGHT
            val nextHeaderHeight = QUEUE_NEXT_HEADER_HEIGHT
            val belowCurrentHeight = QUEUE_CURRENTLY_PLAYING_HEIGHT + // Currently Playing 行
                (if (userQueue.isNotEmpty()) {
                    userHeaderHeight + rowHeight * userQueue.size
                } else 0.dp) +
                (if (contextNextQueue.isNotEmpty()) {
                    nextHeaderHeight + rowHeight * contextNextQueue.size
                } else 0.dp)
            (listHeightPx.toDp() - belowCurrentHeight).coerceAtLeast(16.dp)
        }
        // 打开后的初始定位校正：三段队列行由 Flow 经拖拽工作副本（LaunchedEffect）
        // 异步填充，首次组合时行数不全，initialFirstVisibleItemIndex 会被钳制；
        // 且差额 padding 依赖列表实测高度、首帧尚未就位。
        // 在短暂窗口内逐帧把列表锚定到当前播放行（行数填充/padding 就位后自然停住），
        // 用户开始滚动立即停止；该窗口处于播放器滑入动画期间，校正过程不可见
        LaunchedEffect(Unit) {
            val targetIndex = viewModel.initialQueueScrollIndex()
            if (targetIndex <= 0) return@LaunchedEffect
            repeat(15) { // ~15 帧 ≈ 250ms
                if (listState.isScrollInProgress) return@LaunchedEffect
                if (listState.firstVisibleItemIndex != targetIndex ||
                    listState.firstVisibleItemScrollOffset != 0
                ) {
                    listState.scrollToItem(targetIndex)
                }
                withFrameNanos { }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { listHeightPx = it.height }
                .nestedScroll(nestedScrollConnection),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            // Section 1: Previous Queue (contextPrevQueue)
            // iOS: ContextQueuePrevSectionHeader (height: 20.5 + 16 = 36.5pt)
            // 显示已播放的歌曲历史（iOS行为：显示所有已经播放过的歌曲）
            // 段的显隐一律看**工作副本**而非 Flow：跨段拖动过程中源段可能被拖空、
            // 目标段可能变长，段头随之增删，拖拽几何（QueueDragState.rowOffsetY）才与实际布局一致
            if (dragState.prevItems.isNotEmpty()) {
                stickyHeader(key = "header_previous") {
                    SectionHeader(
                        title = "Previous",
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { if (it.height > 0) dragState.prevHeaderPx = it.height }
                            .background(MaterialTheme.colorScheme.background) // iOS: Sticky header with background
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                // Previous queue songs（可拖拽重排，段内 + 跨段，对齐 iOS movePlayable）
                reorderableQueueItems(
                    section = PlayerQueueType.PREV,
                    drag = dragState,
                    swipeController = swipeController,
                    queueSwipeActions = queueSwipeActions,
                    viewModel = viewModel,
                    isOnlineMode = isOnlineMode,
                    listState = listState,
                    onShowAlbumById = onShowAlbumById,
                    onShowArtistById = onShowArtistById,
                    onHaptic = hapticsCallback,
                    onRemove = { viewModel.removeFromQueue(it) },
                    onPlay = { viewModel.playFromQueue(it) },
                    onCommitMove = commitQueueMove,
                    onResync = resyncQueueCopies
                )
            }

            // Section 2: Currently Playing (sticky)
            // iOS: CurrentlyPlayingTableCell
            currentSong?.let { song ->
                stickyHeader(key = "currently_playing") {
                    // Column 包一层承载行底分割线（iOS .plain 表 separatorStyle="default"，
                    // PopupPlayerVC.xib:32：当前播放行同样有底线）；
                    // 拖拽几何量测上提到本层——它表达的是「手指要越过的整块高度」，含分割线才准确
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 拖拽几何：prev↔user/next 跨段时手指要越过整行当前播放（恒不可落）
                            .onSizeChanged {
                                if (it.height > 0) dragState.currentlyPlayingPx = it.height
                            }
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(QUEUE_CURRENTLY_PLAYING_HEIGHT)
                                .clickable(onClick = onSwitchToLarge),
                            color = MaterialTheme.colorScheme.surfaceVariant // iOS .secondarySystemGroupedBackground
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: Small artwork (iOS: 72x72px)
                                // LARGE/COMPACT 切换共享元素：当前播放行缩略图 ↔ 大封面
                                Card(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .playerSharedElement(SHARED_KEY_PLAYER_ARTWORK),
                                    shape = RoundedCornerShape(4.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                ) {
                                    // 默认图按播放项类型现画（随账户主题色，见 ui/util/DefaultArtwork.kt）
                                    val defaultArtwork =
                                        rememberDefaultArtworkPainter(defaultArtworkTypeFor(song))
                                    AsyncImage(
                                        model = buildCoverArtUrl(song.coverArt, credentialsManager, musicRepository),
                                        contentDescription = song.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        placeholder = defaultArtwork,
                                        error = defaultArtwork,
                                        fallback = defaultArtwork
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Center: Song info
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface, // iOS .label
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = song.artist,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 16.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, // iOS .secondaryLabel
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // 菜单状态提到 favorite/info 与 options 两枚按钮之上——播客模式下 info 按钮
                                // 复用同一个当前曲 More 菜单（与 LargePlayerView 同款处理）
                                var showSongOptionsMenu by remember { mutableStateOf(false) }

                                // Right: favoriteButton (iOS: 30x30px)
                                // 对应iOS: PopupPlayerVC.favoritePressed()；播客模式换 info 图标
                                // （iOS refreshFavoriteButton 服务于 LARGE/COMPACT 两形态共用的收藏键，
                                //   PopupPlayer+Visuals.swift:97-106 的 podcast 分支——`config.image = .info`）
                                if (playerMode == com.amperfy.data.model.PlayerMode.PODCAST) {
                                    IconButton(
                                        onClick = { showSongOptionsMenu = true },
                                        modifier = Modifier
                                            .size(30.dp)
                                            .playerSharedElement(SHARED_KEY_PLAYER_FAVORITE)
                                    ) {
                                        Icon(
                                            AmperfyIcons.info, // iOS: AmperfyImage.info
                                            contentDescription = "Episode Info",
                                            tint = MaterialTheme.colorScheme.onSurface, // iOS: baseForegroundColor = .label
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                } else {
                                    FavoriteButton(
                                        isFavorite = isFavorite,
                                        onClick = onToggleFavorite,
                                        modifier = Modifier.playerSharedElement(SHARED_KEY_PLAYER_FAVORITE),
                                        // 离线时禁用并转中性色（对应 iOS PopupPlayer+Visuals.swift:82-85 的 isOnlineMode）
                                        enabled = isOnlineMode
                                    )
                                }

                                // Right: optionsButton (iOS: 30x30px)
                                Box(modifier = Modifier.playerSharedElement(SHARED_KEY_PLAYER_OPTIONS)) {
                                    IconButton(
                                        onClick = { showSongOptionsMenu = true },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            AmperfyIcons.ellipsis, // iOS: AmperfyImage.ellipsis
                                            contentDescription = "Song Options",
                                            // tint = .label（PlayerControlView.swift:112
                                            // optionsButton.imageView?.tintColor = .label）
                                            tint = MaterialTheme.colorScheme.label,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    CurrentlyPlayingSongMenu(
                                        expanded = showSongOptionsMenu,
                                        onDismiss = { showSongOptionsMenu = false },
                                        alignment = Alignment.TopEnd,
                                        offset = IntOffset(0, -8),
                                        displayMode = PlayerDisplayMode.COMPACT,
                                        // Song details
                                        isFavorite = isFavorite,
                                        rating = rating,
                                        isCached = isCached,
                                        isOnlineMode = isOnlineMode,
                                        hasLyrics = hasLyrics,
                                        // Actions
                                        onShowAlbum = onShowAlbum,
                                        onShowArtist = onShowArtist,
                                        onShowLyrics = onShowLyrics,
                                        onToggleFavorite = onToggleFavorite,
                                        onSetRating = onSetRating,
                                        onAddToPlaylist = onAddToPlaylist,
                                        onDownload = onDownload,
                                        onDeleteCache = onDeleteCache
                                    )
                                }
                            }
                        }

                        // 行底分割线：左端距屏幕左缘 16dp、右端画到屏幕右缘
                        // （iOS .plain 表 separatorStyle="default" 的默认 separatorInset
                        // = cell layoutMargins，CommonScreenOperations.swift:41-47）
                        HairlineDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.separator  // iOS .separator
                        )
                    }
                }
            } ?: run {
                // 清空播放器后的空态占位行：占位封面 + "No music playing"
                // （对齐 iOS：CurrentlyPlayingTableCell 由 refreshCurrentlyPlayingInfo
                //   驱动，清空后同一行显示 "No music playing" 而非整行消失）
                stickyHeader(key = "currently_playing_empty") {
                    // 同当前播放行：Column 承载行底分割线，拖拽几何量测上提到本层
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged {
                                if (it.height > 0) dragState.currentlyPlayingPx = it.height
                            }
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(QUEUE_CURRENTLY_PLAYING_HEIGHT),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Card(
                                    modifier = Modifier.size(72.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                ) {
                                    // 空播放态占位（iOS ArtworkType.song），按主题色现画
                                    Image(
                                        painter = rememberDefaultArtworkPainter(DefaultArtworkType.SONG),
                                        contentDescription = "No music playing",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "No music playing",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // 行底分割线（同上，iOS .plain 表默认 separator）
                        HairlineDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.separator  // iOS .separator
                        )
                    }
                }
            }

            // Section 3: User Queue (userQueue)
            // iOS: UserQueueSectionHeader (height: 28 + 16 = 44pt)
            if (dragState.userItems.isNotEmpty()) {
                stickyHeader(key = "header_user_queue") {
                    SectionHeader(
                        title = "Next in Queue",
                        showClearButton = true,
                        onClearClick = { viewModel.clearUserQueue() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { if (it.height > 0) dragState.userHeaderPx = it.height }
                            .background(MaterialTheme.colorScheme.background) // iOS: Sticky header with background
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                reorderableQueueItems(
                    section = PlayerQueueType.USER,
                    drag = dragState,
                    swipeController = swipeController,
                    queueSwipeActions = queueSwipeActions,
                    viewModel = viewModel,
                    isOnlineMode = isOnlineMode,
                    listState = listState,
                    onShowAlbumById = onShowAlbumById,
                    onShowArtistById = onShowArtistById,
                    onHaptic = hapticsCallback,
                    onRemove = { viewModel.removeFromQueue(it) },
                    onPlay = { viewModel.playFromQueue(it) },
                    onCommitMove = commitQueueMove,
                    onResync = resyncQueueCopies
                )
            }

            // Section 4: Context Next (contextNext)
            // iOS: ContextQueueNextSectionHeader (height: 40 + 16 = 56pt)
            // 显示 contextQueue 的剩余部分，不包括 userQueue
            // 对应iOS: player.nextQueueCount - 只包含 contextQueue[currentIndex+1...]
            if (dragState.nextItems.isNotEmpty()) {
                stickyHeader(key = "header_next_from") {
                    SectionHeader(
                        title = "Next From",
                        subtitle = contextName, // iOS: player.contextName - 显示播放上下文名称（专辑、播放列表等）
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { if (it.height > 0) dragState.nextHeaderPx = it.height }
                            .background(MaterialTheme.colorScheme.background) // iOS: Sticky header with background
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        // 音乐模式才有 shuffle/repeat 两枚按钮
                        // （iOS ContextQueueNextSectionHeader.refreshCurrentlyPlayingInfo:72-81
                        //  在 podcast 分支把两枚按钮 isHidden = true）
                        trailingContent = {
                            if (playerMode != com.amperfy.data.model.PlayerMode.PODCAST) {
                                QueueSectionRepeatShuffleButtons(
                                    isShuffle = isShuffle,
                                    isShuffleEnabled = isShuffleButtonEnabled,
                                    repeatMode = repeatMode,
                                    onToggleShuffle = { viewModel.toggleShuffle() },
                                    onToggleRepeat = { viewModel.toggleRepeatMode() }
                                )
                            }
                        }
                    )
                }

                // Context next queue songs (iOS: contextNextQueue)
                reorderableQueueItems(
                    section = PlayerQueueType.NEXT,
                    drag = dragState,
                    swipeController = swipeController,
                    queueSwipeActions = queueSwipeActions,
                    viewModel = viewModel,
                    isOnlineMode = isOnlineMode,
                    listState = listState,
                    onShowAlbumById = onShowAlbumById,
                    onShowArtistById = onShowArtistById,
                    onHaptic = hapticsCallback,
                    onRemove = { viewModel.removeFromQueue(it) },
                    onPlay = { viewModel.playFromQueue(it) },
                    onCommitMove = commitQueueMove,
                    onResync = resyncQueueCopies
                )
            }
        }
    }
}

// ========== 队列拖拽几何常量（与本文件实际渲染值一致，仅作实测前的兜底初值） ==========

/** QueueItem 固定行高 */
private val QUEUE_ROW_HEIGHT = 66.dp

/** Currently Playing 行（iOS CurrentlyPlayingTableCell） */
private val QUEUE_CURRENTLY_PLAYING_HEIGHT = 94.dp

/** "Previous" 段头（iOS ContextQueuePrevSectionHeader 20.5+16） */
private val QUEUE_PREV_HEADER_HEIGHT = 40.dp

/** "Next in Queue" 段头（iOS UserQueueSectionHeader 28+16） */
private val QUEUE_USER_HEADER_HEIGHT = 44.dp

/** "Next From" 段头（iOS ContextQueueNextSectionHeader 40+16，含副标题） */
private val QUEUE_NEXT_HEADER_HEIGHT = 56.dp

/**
 * 队列行长按抬起（lift）后，手指静止多久才弹出预览菜单（ms）。
 *
 * 对应 iOS UITableView「drag & drop + context menu 并存」的系统行为：整行长按先 lift，
 * 继续按住不动才浮出预览菜单（UIKit 未公开该间隔，350ms 为观感近似值，真机可调）。
 */
private const val QUEUE_MENU_AFTER_LIFT_DELAY_MS = 350L

/** 工作副本 key 的段前缀（见 [buildQueueEntries]） */
private object QueueKeyPrefix {
    const val PREV = "prev"
    const val USER = "user"
    const val NEXT = "next"
}

/**
 * 生成拖拽工作副本条目：key = **初始段前缀 + 歌曲 id + 段内出现序号**。
 *
 * 前缀在建副本时一次性写死、跨段搬移后**不再变化**——LazyColumn 的行 key 直接用它，
 * 条目从一段拖进另一段时 key 不变，挂在拖动柄上的手势节点得以存活
 * （key 变化会让 Compose 销毁重建节点、手势协程静默死亡，根因见 ReorderEntry.kt）。
 * 同一首歌可能同时出现在 user 与 next 段（两个不同条目），段前缀保证 key 不撞。
 */
private fun buildQueueEntries(
    prefix: String,
    list: List<Playable>
): List<com.amperfy.ui.util.ReorderEntry<Playable>> =
    com.amperfy.ui.util.buildReorderEntries(list) { "${prefix}_${it.id}" }

/**
 * 播放器队列三段共用的拖拽状态（工作副本 + 拖拽位置 + 布局几何）
 *
 * 对应 iOS UITableView 的整表 reorder：拖动落点是「段 + 段内索引」（PlayerIndex），
 * 三段共用一份状态才能跨段搬移。工作副本为 ReorderEntry 包装（key 稳定，见上）。
 */
private class QueueDragState {
    val prevItems = mutableStateListOf<com.amperfy.ui.util.ReorderEntry<Playable>>()
    val userItems = mutableStateListOf<com.amperfy.ui.util.ReorderEntry<Playable>>()
    val nextItems = mutableStateListOf<com.amperfy.ui.util.ReorderEntry<Playable>>()

    /** 被拖行当前所在段；null = 无拖拽进行中 */
    var draggingSection by mutableStateOf<PlayerQueueType?>(null)

    /** 被拖行在所在段工作副本中的当前索引 */
    var draggingIndex by mutableStateOf(0)

    /** 拖动起点（提交 movePlayable 的 from，取拖动**前**的队列坐标） */
    var dragStartSection = PlayerQueueType.PREV
    var dragStartIndex = 0

    /** 相对**当前槽位**的手指累计位移（px），每次换位后按实际位移扣减 */
    var dragOffsetY by mutableStateOf(0f)

    // 布局几何（px）：初值按 dp 常量换算，随后由各处 onSizeChanged 实测覆盖
    var rowHeightPx = 0
    var prevHeaderPx = 0
    var userHeaderPx = 0
    var nextHeaderPx = 0
    var currentlyPlayingPx = 0

    fun itemsOf(section: PlayerQueueType) = when (section) {
        PlayerQueueType.PREV -> prevItems
        PlayerQueueType.USER -> userItems
        PlayerQueueType.NEXT -> nextItems
    }
}

/** 三段在列表中的先后次序 */
private val QUEUE_SECTION_ORDER = listOf(
    PlayerQueueType.PREV,
    PlayerQueueType.USER,
    PlayerQueueType.NEXT
)

/**
 * 某行在整段队列内容中的纵向偏移（px），按给定的三段行数计算。
 * 计入段头与 Currently Playing 行占用的净空——跨段拖动的位移阈值全靠它。
 */
private fun QueueDragState.rowOffsetY(
    section: PlayerQueueType,
    index: Int,
    prevCount: Int,
    userCount: Int,
    nextCount: Int
): Float {
    var y = 0f
    if (prevCount > 0) y += prevHeaderPx
    if (section == PlayerQueueType.PREV) return y + index * rowHeightPx
    y += prevCount * rowHeightPx + currentlyPlayingPx
    if (userCount > 0) y += userHeaderPx
    if (section == PlayerQueueType.USER) return y + index * rowHeightPx
    y += userCount * rowHeightPx
    if (nextCount > 0) y += nextHeaderPx
    return y + index * rowHeightPx
}

/**
 * 向下一格的落点：同段下一行；已在段末则进入**后面第一个非空段**的首行。
 *
 * 已知简化：空段不渲染段头也就没有可视落区，因而不可作为落点
 * （iOS 空 section 的 header 高度亦 ≈0，体验等价）。
 */
private fun QueueDragState.targetBelow(
    section: PlayerQueueType,
    index: Int
): Pair<PlayerQueueType, Int>? {
    if (index < itemsOf(section).size - 1) return section to (index + 1)
    for (i in (QUEUE_SECTION_ORDER.indexOf(section) + 1) until QUEUE_SECTION_ORDER.size) {
        val candidate = QUEUE_SECTION_ORDER[i]
        if (itemsOf(candidate).isNotEmpty()) return candidate to 0
    }
    return null
}

/** 向上一格的落点：同段上一行；已在段首则追加到**前面第一个非空段**的末尾 */
private fun QueueDragState.targetAbove(
    section: PlayerQueueType,
    index: Int
): Pair<PlayerQueueType, Int>? {
    if (index > 0) return section to (index - 1)
    for (i in (QUEUE_SECTION_ORDER.indexOf(section) - 1) downTo 0) {
        val candidate = QUEUE_SECTION_ORDER[i]
        if (itemsOf(candidate).isNotEmpty()) return candidate to itemsOf(candidate).size
    }
    return null
}

/**
 * 被拖行从 (fs, fi) 换位到 (ts, ti) 后的**实际视觉位移**（px）。
 * 跨段时目标段的行数已 +1、源段 -1（段头可能随之增删），故两侧用各自的行数快照计算。
 */
private fun QueueDragState.displacement(
    fs: PlayerQueueType,
    fi: Int,
    ts: PlayerQueueType,
    ti: Int
): Float {
    var prevCount = prevItems.size
    var userCount = userItems.size
    var nextCount = nextItems.size
    val before = rowOffsetY(fs, fi, prevCount, userCount, nextCount)
    if (fs != ts) {
        when (fs) {
            PlayerQueueType.PREV -> prevCount--
            PlayerQueueType.USER -> userCount--
            PlayerQueueType.NEXT -> nextCount--
        }
        when (ts) {
            PlayerQueueType.PREV -> prevCount++
            PlayerQueueType.USER -> userCount++
            PlayerQueueType.NEXT -> nextCount++
        }
    }
    return rowOffsetY(ts, ti, prevCount, userCount, nextCount) - before
}

/** 在工作副本间搬移被拖条目（段内 / 跨段同一路径） */
private fun QueueDragState.moveDragged(
    fs: PlayerQueueType,
    fi: Int,
    ts: PlayerQueueType,
    ti: Int
) {
    val entry = itemsOf(fs).removeAt(fi)
    itemsOf(ts).add(ti, entry)
    draggingSection = ts
    draggingIndex = ti
}

/**
 * 按累计位移解析落点并搬移工作副本（一次手势事件可能跨多格，故循环处理）。
 *
 * 阈值取「到相邻落点的实际位移的一半」——段内即半行高，跨段则自动含上
 * Currently Playing 行 + 目标段头的高度，因此**当前播放行恒不可落**：
 * 从 prev 末行向下越过它直接进入 user 首（user 空则 next 首），反向同理
 * （对齐 iOS PopupPlayer+TableViewExtension.targetIndexPathForMove:243-256）。
 */
private fun QueueDragState.updateDragTarget(onHaptic: () -> Unit, listState: LazyListState) {
    if (rowHeightPx <= 0) return
    var moved = false
    var guard = 0
    while (guard++ < 64) {
        val section = draggingSection ?: break
        val index = draggingIndex

        val below = targetBelow(section, index)
        if (below != null) {
            val delta = displacement(section, index, below.first, below.second)
            if (delta > 0f && dragOffsetY > delta / 2f) {
                moveDragged(section, index, below.first, below.second)
                dragOffsetY -= delta
                moved = true
                onHaptic()
                continue
            }
        }

        val above = targetAbove(section, index)
        if (above != null) {
            val delta = displacement(section, index, above.first, above.second)
            if (delta < 0f && dragOffsetY < delta / 2f) {
                moveDragged(section, index, above.first, above.second)
                dragOffsetY -= delta
                moved = true
                onHaptic()
                continue
            }
        }
        break
    }
    if (moved) {
        // 换位后以 index 重新锚定视口，防被拖条目出视口被懒回收、手势遭取消致重排不提交
        // （根因同 PlaylistEditScreen）；未涉及首行时等价 no-op。
        // 已知局限：跨段导致源段被拖空、段头消失时列表项索引整体前移一格，
        // 该帧的锚定会落在相邻项上（仅"拖走某段最后一行"时出现，视觉为一次轻微跳动）
        listState.requestScrollToItem(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset
        )
    }
}

/**
 * 可重排的队列 section（Batch 3：段内 + 跨段）
 * 对应 iOS: PopupPlayer+TableViewExtension.moveRowAt -> PlayQueueHandler.movePlayable；
 * 任意段的行可拖到任意其他段，拖拽结束一次性提交 onCommitMove(from, to)——
 * from 为拖动前坐标、to 为落点段内最终索引。
 *
 * 拖拽触发方式为**整行长按抬起后拖动**，对齐 iOS PopupPlayerVC 的 UITableView drag & drop
 * （`dragDelegate` + `dragInteractionEnabled = true`，PopupPlayerVC.swift:76-78）：
 * 行尾三横线只是 accessoryView 装饰、不是拖拽把手（PlayableTableCell.swift:352-355）。
 * 此前 Android 误把播放列表编辑态（PlaylistEditVC / PlaylistEditSheet）的「手柄即时拖拽」
 * 搬到了播放器队列——编辑态用手柄是对的，队列不是。
 */
private fun LazyListScope.reorderableQueueItems(
    section: PlayerQueueType,
    drag: QueueDragState,
    swipeController: SwipeController,
    queueSwipeActions: List<SwipeActionType>,
    viewModel: PopupPlayerViewModel,
    isOnlineMode: Boolean,
    // 拖拽交换后需以 index 重新锚定视口（根因同 PlaylistEditScreen），故沿参数链传入外层 listState
    listState: LazyListState,
    onShowAlbumById: (String) -> Unit,
    onShowArtistById: (String) -> Unit,
    onHaptic: () -> Unit,
    onRemove: (Playable) -> Unit,
    onPlay: (Playable) -> Unit,
    onCommitMove: (PlayerIndex, PlayerIndex) -> Unit,
    onResync: () -> Unit
) {
    // key 用条目自带的稳定唯一 key（段前缀_id#出现序号）：拖拽换位、乃至跨段搬移都不改变 key，
    // 手势节点得以存活（含索引/当前段的复合 key 会在换位时杀死进行中的手势，
    // 导致行悬停留空隙、重排不提交——见 ReorderEntry.kt）
    itemsIndexed(drag.itemsOf(section), key = { _, entry -> entry.key }) { index, entry ->
        val song = entry.item
        // pointerInput 的手势块不随重组更新捕获值，用 rememberUpdatedState 取最新的段与行索引
        // （跨段搬移后本行归属的 section 也会变，故两者都要跟新）
        val currentSection by rememberUpdatedState(section)
        val currentIndex by rememberUpdatedState(index)
        val isDragging = drag.draggingSection == section && drag.draggingIndex == index

        // 长按预览菜单（对应 iOS PopupPlayer+TableViewExtension contextMenuConfigurationForRowAt）
        var showPreviewMenu by remember { mutableStateOf(false) }
        var rowBoundsOnScreen by remember {
            mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        }
        // 整行长按抬起（armed / lift）：对应 iOS UITableView drag 的系统 lift 动画
        // （轻微放大 + 投影，松手落回）；lift 即已就位拖拽坐标，移动手指便直接重排
        var isLifted by remember { mutableStateOf(false) }
        // 本次长按内手指已越过 touchSlop = 真正进入拖拽重排（用于停掉菜单定时器）
        var isReordering by remember { mutableStateOf(false) }

        // lift 后**静止**一段时间才浮出预览菜单（对应 UIKit 长按抬起 → 按住不动出菜单）；
        // 一旦开始拖动（isReordering 置真）或手指抬起（isLifted 置假），本效果连同
        // 未到点的延时一起被取消 = 菜单定时器取消
        LaunchedEffect(isLifted, isReordering) {
            if (isLifted && !isReordering) {
                delay(QUEUE_MENU_AFTER_LIFT_DELAY_MS)
                showPreviewMenu = true
            }
        }

        val liftProgress by animateFloatAsState(
            targetValue = if (isLifted || isDragging) 1f else 0f,
            animationSpec = tween(200),
            label = "reorderLift"
        )

        // 行 + 行底分割线：iOS 播放器队列表为 .plain 且 separatorStyle="default"
        // （PopupPlayerVC.xib:32），即**每行底部都有默认分割线**（左端 16pt = cell
        // layoutMargins、右端到屏幕右缘），与全仓列表行同一约定。
        //
        // 分割线放在**变换层之外**（graphicsLayer 挂在内层 Box 上）：lift 时只有行块
        // 缩放投影、分割线不跟着一起放大，对齐 iOS 拖拽 lift 快照不含 separator。
        // 两个 modifier 随之上提到本 Column：
        // - zIndex 决定**跨 item** 的绘制顺序（lift 放大时本行要盖住相邻行），必须挂在
        //   LazyColumn item 的根节点上；留在内层 Box 只会在 Column 内部排序、失去跨行效果
        // - onSizeChanged 采的是「行间距」（拖拽换位阈值 drag.rowHeightPx），
        //   含分割线才与实际 item 间距一致，避免长距离拖动逐行累积偏差
        Column(
            modifier = Modifier
                .zIndex(if (isDragging || liftProgress > 0f) 1f else 0f)
                .onSizeChanged { if (it.height > 0) drag.rowHeightPx = it.height }
        ) {
            Box(
                modifier = Modifier
                    // 整行长按拖拽（对齐 iOS dragInteractionEnabled 的 lift → 拖动）。
                    // 三个刻意选择：
                    // 1) 手势节点排在 graphicsLayer **之前**——排在其后会落入本行的变换层，
                    //    指针局部坐标被 translationY 反向抵消，位移测量与实际手指位移不符；
                    // 2) 手势不能以 section 为 key——跨段搬移会重启手势并杀死本次拖动，
                    //    故固定 Unit，段与索引一律经 rememberUpdatedState / 状态对象现读；
                    // 3) 长按前的移动不消费任何事件，列表纵向滚动与 SwipeableItem 横滑照常抢占
                    //    （它们一旦消费事件，detectDragGesturesAfterLongPress 的长按判定即取消）
                    .pointerInput(Unit) {
                        // 越过 touchSlop 前的累计位移（px）：lift 后手指微颤不应立刻触发重排
                        var pendingX = 0f
                        var pendingY = 0f
                        var reordering = false
                        val slop = viewConfiguration.touchSlop
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                pendingX = 0f
                                pendingY = 0f
                                reordering = false
                                isLifted = true
                                isReordering = false
                                onHaptic()
                                drag.draggingSection = currentSection
                                drag.draggingIndex = currentIndex
                                drag.dragStartSection = currentSection
                                drag.dragStartIndex = currentIndex
                                drag.dragOffsetY = 0f
                            },
                            onDragEnd = {
                                // from = 拖动前坐标；to = 工作副本里的最终段与索引
                                // （跨段时可等于目标段原长度，即拖到段末追加，
                                //  movePlayable 的 guard 允许 to.index == count）
                                val fromSection = drag.dragStartSection
                                val fromIndex = drag.dragStartIndex
                                val toSection = drag.draggingSection
                                val toIndex = drag.draggingIndex
                                drag.draggingSection = null
                                drag.dragOffsetY = 0f
                                isLifted = false
                                // 只 lift 未拖动（reordering == false）时不提交任何改动：
                                // 菜单已弹出则保持打开、未弹出则整行落回，对齐 iOS lift 松手
                                if (reordering &&
                                    toSection != null &&
                                    (toSection != fromSection || toIndex != fromIndex)
                                ) {
                                    onCommitMove(
                                        PlayerIndex(fromSection, fromIndex),
                                        PlayerIndex(toSection, toIndex)
                                    )
                                }
                                reordering = false
                                isReordering = false
                            },
                            onDragCancel = {
                                drag.draggingSection = null
                                drag.dragOffsetY = 0f
                                isLifted = false
                                reordering = false
                                isReordering = false
                                // 三段工作副本全部回退到队列现值
                                onResync()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (!reordering) {
                                    pendingX += dragAmount.x
                                    pendingY += dragAmount.y
                                    if (hypot(pendingX, pendingY) > slop) {
                                        reordering = true
                                        // 菜单弹出后继续移动 = 菜单消失、转入拖拽（UIKit 标准行为）。
                                        // 手指仍按在本行上：指针已在按下时锁定到当时的命中链，
                                        // 新弹出的 Popup 只拦截**新**指针，故后续 onDrag 照常派发
                                        showPreviewMenu = false
                                        isReordering = true
                                        drag.dragOffsetY += pendingY
                                        drag.updateDragTarget(onHaptic, listState)
                                    }
                                } else {
                                    drag.dragOffsetY += dragAmount.y
                                    drag.updateDragTarget(onHaptic, listState)
                                }
                            }
                        )
                    }
                    .graphicsLayer {
                        translationY = if (isDragging) drag.dragOffsetY else 0f
                        val liftScale = 1f + 0.03f * liftProgress
                        scaleX = liftScale
                        scaleY = liftScale
                        shadowElevation = 8.dp.toPx() * liftProgress
                    }
                    .onGloballyPositioned { coordinates ->
                        // 屏幕坐标（Popup 窗口原点与 App 窗口可能不一致，窗口坐标会错位）
                        val position = coordinates.positionOnScreen()
                        rowBoundsOnScreen = androidx.compose.ui.geometry.Rect(
                            left = position.x,
                            top = position.y,
                            right = position.x + coordinates.size.width,
                            bottom = position.y + coordinates.size.height
                        )
                    }
            ) {
                SwipeableItem(
                    key = entry.key,
                    swipeController = swipeController,
                    trailingActions = queueSwipeActions,
                    onSwipeAction = { onRemove(song) }
                ) {
                    QueueItem(
                        song = song,
                        isPlaying = false,
                        onClick = {
                            // lift 之后松手不得误触发播放（iOS lift 松手仅回落 / 菜单驻留）。
                            // 这里刻意**不给 combinedClickable 传 onLongClick**：非 null 的
                            // onLongClick 触发后会 consumeUntilUp() 吞掉后续全部事件，而行级
                            // 长按计时器与它同源同长（同一个 down、同为 longPressTimeout），
                            // 子节点先派发先触发，整行拖拽会在第一次移动时被判取消——
                            // 故改用 isLifted 标志抑制误播放，长按一律交给行级手势处理
                            if (!isLifted) onPlay(song)
                        },
                        trailingContent = {
                            // 行尾三横线为纯装饰附件，对应 iOS PlayableTableCell 的 `.bars`
                            // accessoryView（PlayableTableCell.swift:352-355）——**不是拖拽触发器**，
                            // 拖拽一律由整行长按抬起发起（见上方 Box 的 pointerInput）
                            Icon(
                                AmperfyIcons.bars, // iOS: AmperfyImage.bars ("line.3.horizontal")
                                contentDescription = "Reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }

                // 长按弹出：预览卡片 + 菜单
                // 对应 iOS PopupPlayer 队列行长按（EntityPreviewVC + EntityPreviewActionBuilder），
                // 菜单与当前播放歌曲 More 菜单同源，另含 Play/Music Queue（iOS 传 playContextCb）
                if (showPreviewMenu) {
                    val credentialsManager = LocalCredentialsManager.current
                    val musicRepository = LocalMediaUrlRepository.current
                    // 按需订阅该行歌曲的库内实体（favorite/rating/缓存实时状态与
                    // albumId/artistId 来源；电台等非库内条目为 null，回退精简菜单）
                    val rowSong by remember(song.id) { viewModel.observeSong(song.id) }
                        .collectAsState(initial = null)
                    val dismissPreviewMenu = { showPreviewMenu = false }
                    IOSLongPressPreviewMenu(
                        expanded = true,
                        onDismissRequest = dismissPreviewMenu,
                        anchorBoundsOnScreen = rowBoundsOnScreen,
                        // 全屏播放器内菜单水平居中（iOS 真机表现；普通列表为对齐卡片左侧）
                        menuAlignment = Alignment.CenterHorizontally,
                        items = buildQueueItemMenuItems(
                            rowSong = rowSong,
                            isOnlineMode = isOnlineMode,
                            viewModel = viewModel,
                            onShowAlbumById = onShowAlbumById,
                            onShowArtistById = onShowArtistById
                        )
                    ) {
                        EntityPreviewCard(
                            coverArtModel = buildCoverArtUrl(
                                song.coverArt, credentialsManager, musicRepository
                            ),
                            // 默认图按播放项类型（随账户主题色）；Painter 由预览卡自持
                            defaultArtworkType = defaultArtworkTypeFor(song),
                            title = song.title,
                            subtitle = song.artist,
                            info = queuePreviewInfo(rowSong, song),
                            showChevron = rowSong?.albumId != null,
                            onClick = rowSong?.albumId?.let { albumId ->
                                {
                                    // 对应 iOS willPerformPreviewAction → performPreviewTransition
                                    dismissPreviewMenu()
                                    onShowAlbumById(albumId)
                                }
                            }
                        )
                    }
                }
            }

            HairlineDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.separator  // iOS .separator（半透明，深浅底皆可见）
            )
        }
    }
}

/**
 * 队列行长按菜单 - 对应 iOS PopupPlayer 队列行 contextMenuConfigurationForRowAt →
 * EntityPreviewActionBuilder.createMenu()，与当前播放歌曲 More 菜单同源同结构：
 * PopupPlayer 内两处构造均未传 playContextCb（PopupPlayer+TableViewExtension.swift:324、
 * PopupPlayer+Visuals.swift:67），而 isPlay/isShuffle/isMusicQueue 都要求
 * playContextCb != nil（EntityPreviewVC.swift:249-264）→ 播放器内菜单无
 * Play/Shuffle/Music Queue（普通列表传了 playIndexCB，故列表行长按有这些项）
 *
 * rowSong 为该行的库内 Song 实体；电台等非库内条目查询为 null 时返回空菜单
 * （仅显示预览卡片；iOS 电台此处仅有 Go to Site，已知简化）
 */
private fun buildQueueItemMenuItems(
    rowSong: Song?,
    isOnlineMode: Boolean,
    viewModel: PopupPlayerViewModel,
    onShowAlbumById: (String) -> Unit,
    onShowArtistById: (String) -> Unit
): List<IOSContextMenuItem> = buildList {
    val songEntity = rowSong ?: return@buildList

    // 跳转组（对应 createShowAlbumAction / createShowArtistAction）
    songEntity.albumId?.let { albumId ->
        add(IOSContextMenuItem.Action(
            text = "Show Album",
            icon = AmperfyIcons.album, // iOS: AmperfyImage.album ("square.stack")
            onClick = { onShowAlbumById(albumId) }
        ))
    }
    songEntity.artistId?.let { artistId ->
        add(IOSContextMenuItem.Action(
            text = "Show Artist",
            icon = AmperfyIcons.artist, // iOS: AmperfyImage.artist ("music.mic")
            onClick = { onShowArtistById(artistId) }
        ))
    }

    // Favorite / Rating（对应 createFavoriteMenu / createRatingMenu，iOS 仅在线时显示）
    if (isOnlineMode) {
        add(IOSContextMenuItem.Divider)
        add(IOSContextMenuItem.Action(
            text = if (songEntity.isFavorite) "Unmark favorite" else "Favorite",
            // iOS: heart / heart.slash（与 EntityPreviewActionBuilder 同源）
            icon = if (songEntity.isFavorite) AmperfyIcons.heartSlash else AmperfyIcons.heartEmpty,
            onClick = { viewModel.toggleFavoriteFor(songEntity) }
        ))
        val rating = songEntity.rating ?: 0
        val ratingText = if (rating == 0) "Not rated" else "$rating Star${if (rating > 1) "s" else ""}"
        add(IOSContextMenuItem.RatingPalette(
            text = "Rating: $ratingText",
            icon = if (rating > 0) AmperfyIcons.starFill else AmperfyIcons.starEmpty,
            currentRating = rating,
            onRatingSelected = { viewModel.setRatingFor(songEntity, it) }
        ))
        add(IOSContextMenuItem.Divider)
        // Add to Playlist（对应 createAddToPlaylistAction，弹 PlaylistSelectorDialog）
        add(IOSContextMenuItem.Action(
            text = "Add to Playlist",
            icon = AmperfyIcons.playlistPlus, // iOS: AmperfyImage.playlistPlus ("text.badge.plus")
            onClick = { viewModel.addSongToPlaylist(songEntity.id) }
        ))
    }

    // Download / Delete Cache（对应 createDownloadAction / createDeleteCacheAction）
    if (songEntity.isCached) {
        add(IOSContextMenuItem.Action(
            text = "Delete Cache",
            icon = AmperfyIcons.trash, // iOS: AmperfyImage.trash
            destructive = true,
            onClick = { viewModel.deleteSongCacheFor(songEntity) }
        ))
    } else if (isOnlineMode) {
        add(IOSContextMenuItem.Action(
            text = "Download",
            icon = AmperfyIcons.download, // iOS: AmperfyImage.download ("arrow.down.circle")
            onClick = { viewModel.downloadSongFor(songEntity) }
        ))
    }
}

/**
 * 队列行预览卡片信息行 - 对应 iOS Song.infoDetails(type: .long)
 * 库内歌曲显示 "Track N · 时长 · Year N · Genre: x"；非库内条目仅时长
 */
private fun queuePreviewInfo(rowSong: Song?, playable: Playable): String {
    if (rowSong == null) return formatQueueItemDuration(playable.duration)
    return buildList {
        rowSong.track?.takeIf { it > 0 }?.let { add("Track $it") }
        if (rowSong.duration > 0) add(formatQueueItemDuration(rowSong.duration))
        rowSong.year?.takeIf { it > 0 }?.let { add("Year $it") }
        rowSong.genre?.takeIf { it.isNotBlank() }?.let { add("Genre: $it") }
    }.joinToString(" · ")
}

/**
 * 时长格式化 "M:SS"
 */
private fun formatQueueItemDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Queue item cell
 * iOS: PlayableTableCell (height 66pt)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueItem(
    song: Playable,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current

    // 注：此前这里按 isPlaying 画一层硬编码 Color.LightGray 高亮底色，已删除——该分支恒不可达：
    // 当前播放曲由独立的 Currently Playing sticky 行渲染（iOS CurrentlyPlayingTableCell），
    // 三段队列（prev/user/next）本就不含当前曲，本文件唯一调用点固定传 isPlaying = false；
    // iOS PlayableTableCell 亦无「当前播放行换底色」的表现
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // 行内容横向 16dp：对齐 iOS cell layoutMargins（CommonScreenOperations.swift:41-47），
            // 与行底分割线 16dp 起点同一左缘
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Small artwork
        Card(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            // 默认图按播放项类型现画（随账户主题色，见 ui/util/DefaultArtwork.kt）
            val defaultArtwork = rememberDefaultArtworkPainter(defaultArtworkTypeFor(song))
            AsyncImage(
                model = buildCoverArtUrl(song.coverArt, credentialsManager, musicRepository),
                contentDescription = song.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = defaultArtwork,
                error = defaultArtwork,
                fallback = defaultArtwork
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Song info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, // iOS .label
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, // iOS .secondaryLabel
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 尾部附件（拖拽手柄等）
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}

/**
 * Section Header Component for Queue List
 * iOS: ContextQueuePrevSectionHeader, UserQueueSectionHeader, ContextQueueNextSectionHeader
 *
 * @param title 主标题 (e.g., "Previous", "Next from Queue", "Next From")
 * @param subtitle 副标题 (可选，用于"Next From"显示专辑/播放列表名称)
 * @param showClearButton 是否显示清除按钮 (用于"Next from Queue")
 * @param onClearClick 清除按钮点击回调
 * @param trailingContent 右侧附加内容槽位（"Next From" 段的 shuffle/repeat 两枚按钮，
 *   对应 iOS ContextQueueNextSectionHeader 的 shuffleButton/repeatButton）
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showClearButton: Boolean = false,
    onClearClick: () -> Unit = {},
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Title (and subtitle if provided)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant // iOS .secondaryLabel
            )

            // Subtitle (for "Next From" section)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface, // iOS .label
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Right: Clear button (for "Next from Queue" section)
        if (showClearButton) {
            TextButton(onClick = onClearClick) {
                Text(
                    text = "Clear",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Right: 附加按钮槽位（"Next From" 段的 shuffle / repeat）
        trailingContent?.invoke()
    }
}

/**
 * "Next From" 段头右侧的 Shuffle + Repeat 两枚按钮
 * 对应 iOS: ContextQueueNextSectionHeader.shuffleButton / repeatButton（:47-94）
 * + PlayerUIHandler.refreshShuffleButton / refreshRepeatButton（:177-217）
 *
 * - 激活态用主色、非激活态 onSurfaceVariant（对应 iOS `UIButton.Configuration.player(isSelected:)`）；
 * - Shuffle 在「Disable Player Shuffle Button」设置下**禁用置灰**——对应 iOS
 *   `shuffleButton.isEnabled = isPlayerShuffleButtonEnabled`（与上下文菜单里整项隐藏的策略不同）；
 * - Repeat 图标：off/all 同为 Repeat（仅颜色区分），single 用 RepeatOne。
 */
@Composable
private fun QueueSectionRepeatShuffleButtons(
    isShuffle: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onToggleShuffle,
            enabled = isShuffleEnabled,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = AmperfyIcons.shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffle) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(
            onClick = onToggleRepeat,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                // iOS: single = AmperfyImage.repeatOne ("repeat.1")；off/all 同为
                // repeatMenu/repeatAll ("repeat")，只靠选中态配色区分
                // （PlayerUIHandler.swift:177-201）
                imageVector = if (repeatMode == RepeatMode.SINGLE) {
                    AmperfyIcons.repeatOne
                } else {
                    AmperfyIcons.repeatAll
                },
                contentDescription = "Repeat: $repeatMode",
                tint = if (repeatMode == RepeatMode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
