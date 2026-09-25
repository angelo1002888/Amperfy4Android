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

package com.amperfy.ui.screens.player

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.geometry.Offset
import android.widget.Toast
import com.amperfy.ui.screens.player.PopupPlayerViewModel
import com.amperfy.ui.screens.player.PlayerDisplayMode
import com.amperfy.ui.screens.player.components.*
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.rememberDefaultArtworkPainter
import kotlinx.coroutines.launch
import kotlin.math.tan

/**
 * 箭头配置类 - 封装所有箭头相关的参数
 * 修改arrowAngleDegrees即可自动计算其他所有参数
 */
private data class ArrowConfig(
    val arrowAngleDegrees: Float = 135f,  // 箭头夹角（度）
    val arrowWidth: Float = 42f,          // 箭头总宽度（dp）
    val strokeWidth: Float = 4f           // 线条粗细（dp）
) {
    // 自动计算的参数
    val halfWidth: Float = arrowWidth / 2f

    // 箭头高度 = 半宽 * tan(与垂直线的夹角)
    // 与垂直线的夹角 = (180° - 箭头夹角) / 2
    val verticalAngleDegrees: Float = (180f - arrowAngleDegrees) / 2f
    val angleInRadians: Double = Math.toRadians(verticalAngleDegrees.toDouble())
    val arrowHeight: Float = (halfWidth * tan(angleInRadians)).toFloat()

    /**
     * 说明：
     * - arrowAngleDegrees = 120° → verticalAngle = 30° → 较尖锐
     * - arrowAngleDegrees = 135° → verticalAngle = 22.5° → 适中
     * - arrowAngleDegrees = 150° → verticalAngle = 15° → 较扁平
     * - arrowAngleDegrees = 170° → verticalAngle = 5° → 非常扁平
     */
}

/**
 * iOS-style PopupPlayerVC - Full screen player
 *
 * Ported from iOS: Amperfy/Screens/ViewController/PopupPlayerVC.swift
 *
 * Layout structure (top to bottom):
 * 1. Background Image (blurred album art) - fills entire screen
 * 2. Close Button (top center) - 44dp height, always visible
 * 3. Content Area (Large Player View or Table View) - fills remaining space above controls
 * 4. Control View (bottom, fixed height 175dp + 20dp margin) - NEVER MOVES
 *
 * Two display modes:
 * - LARGE: Shows large artwork + song details (with favorite + options buttons)
 * - COMPACT: Shows queue table with header (CurrentlyPlayingTableCell with favorite + options buttons)
 *
 * Important: Control View and its optionsButton are SHARED between both modes and NEVER MOVE
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PopupPlayerScreen(
    onBackClick: () -> Unit,
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    viewModel: PopupPlayerViewModel = hiltViewModel(),
    miniPlayerBounds: androidx.compose.ui.geometry.Rect? = null // 接收MiniPlayer的位置信息
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val displayMode by viewModel.displayMode.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()

    // 当前歌曲详细信息（包含 favorite, rating 等）
    // 对应iOS: player.currentlyPlaying
    val currentSongDetails by viewModel.currentSongDetails.collectAsState()
    val isFavorite = currentSongDetails?.isFavorite ?: false
    val rating = currentSongDetails?.rating ?: 0
    val isCached = currentSongDetails?.isCached ?: false
    // SongDetailsMenu "Show Lyrics" 显隐。
    // iOS: EntityPreviewVC:771-781 条件为 `song.lyricsRelFilePath != nil`——即**已落盘才显示**，
    // 而 iOS 无歌词时根本不落盘，故语义等价于「该曲确有歌词」。
    // Android 无同款落盘时机（拉到即落盘），改判「确有真实歌词内容」——
    // 判据三层收口见 [PopupPlayerViewModel.hasRealLyrics]（数组空 / 条目行空 / 行值全空白）
    val currentSongLyrics by viewModel.currentSongLyrics.collectAsState()
    val hasLyrics = PopupPlayerViewModel.hasRealLyrics(currentSongLyrics)
    var showLyricsDialog by remember { mutableStateOf(false) }

    // 歌词菜单项状态（iOS: PlayerControlView.swift:509-529，显示条件只看设置，与歌曲无关）
    val isLyricsButtonAllowedToDisplay by viewModel.isLyricsButtonAllowedToDisplay.collectAsState()
    val isPlayerLyricsDisplayed by viewModel.isPlayerLyricsDisplayed.collectAsState()
    // 歌词内容与滚动设置（Phase 6.2）
    val currentLyrics by viewModel.currentLyrics.collectAsState()
    val isLyricsSmoothScrolling by viewModel.isLyricsSmoothScrolling.collectAsState()

    // W7 三态主区 + 可视化菜单状态
    val largeDisplayElement by viewModel.largeDisplayElement.collectAsState()
    val isPlayerVisualizerDisplayed by viewModel.isPlayerVisualizerDisplayed.collectAsState()
    val selectedVisualizerType by viewModel.selectedVisualizerType.collectAsState()
    val isVisualizerButtonAllowedToDisplay by viewModel.isVisualizerButtonAllowedToDisplay.collectAsState()
    // 播放器内评分 + 音频徽标
    val isPlayerRatingDisplayed by viewModel.isPlayerRatingDisplayed.collectAsState()
    val isShowDetailedInfo by viewModel.isShowDetailedInfo.collectAsState()
    // Batch 2：播放模式（LARGE 视图红心 ⇄ info 按钮切换依据；
    // COMPACT 当前播放行同款切换在 QueueListView 内部按 viewModel.playerMode 判定）
    val playerModeForVisuals by viewModel.playerMode.collectAsState()

    // Settings and queue state
    val isOnlineMode by viewModel.isOnlineMode.collectAsState()
    val hasQueue by viewModel.hasQueue.collectAsState()
    val hasUserQueue by viewModel.hasUserQueue.collectAsState()

    // Phase 4.1/4.2/4.3：播放速率、睡眠定时器、播放器信息
    val currentPlaybackRate by viewModel.playbackRate.collectAsState()
    val sleepTimerRemainingSeconds by viewModel.sleepTimerRemainingSeconds.collectAsState()
    val isPauseAfterCurrentSong by viewModel.isPauseAfterCurrentSong.collectAsState()
    val showPlayerInfoDialog by viewModel.showPlayerInfoDialog.collectAsState()

    // Player Info 弹窗（对应 iOS: PlainDetailsVC.display(player:)，模态展示 Play Time + Queue Items）
    if (showPlayerInfoDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPlayerInfo() },
            title = { Text("Player Info") },
            text = {
                Column {
                    viewModel.buildPlayerInfoLines().forEach { line ->
                        if (line.isEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                        } else {
                            Text(
                                text = line,
                                style = if (line == "Play Time" || line == "Queue Items")
                                    MaterialTheme.typography.titleSmall
                                else MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPlayerInfo() }) { Text("OK") }
            }
        )
    }

    // 歌词纯文本弹窗（对应 iOS: EntityPreviewVC.showLyrics → PlainDetailsVC 模态展示原文）
    if (showLyricsDialog) {
        AlertDialog(
            onDismissRequest = { showLyricsDialog = false },
            title = { Text("Lyrics") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = currentSongLyrics?.line?.joinToString("\n") { it.value } ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLyricsDialog = false }) { Text("OK") }
            }
        )
    }

    // 观察 Toast 消息事件流，显示一次性消息
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // AudioAnalyzer 门控（CPU 归零验收项）：
    // isActive = 可视化开启 && LARGE && isPlaying（PopupPlayer 可见即本 composable 在组合中）
    val analyzerShouldBeActive = isPlayerVisualizerDisplayed &&
        displayMode == PlayerDisplayMode.LARGE && isPlaying
    LaunchedEffect(analyzerShouldBeActive) {
        viewModel.setAudioAnalyzerActive(analyzerShouldBeActive)
    }
    // 退出播放器（本 composable 离开组合）时强制置 false，防止后台空转
    DisposableEffect(Unit) {
        onDispose { viewModel.setAudioAnalyzerActive(false) }
    }

    // 导航辅助函数
    // 对应iOS: PopupPlayerVC.displayAlbumDetail() / displayArtistDetail()
    val navigateAndClose = { action: () -> Unit ->
        onBackClick() // 先关闭播放器
        action() // 然后执行导航
    }

    // iOS-style drag-to-dismiss gesture state
    // 初始值即在屏幕之外（而非首帧渲染在展开位置后再 snap 下去）：
    // 修复打开时先闪现一帧全屏内容、再跳到底部滑入的卡顿观感
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val dragOffsetY = remember { Animatable(screenHeightPx * 1.1f) }
    val coroutineScope = rememberCoroutineScope()
    val dismissThreshold = 600f // 下滑600px后关闭（手指实际拖动距离）

    // 实际拖动距离（未应用阻尼）- 用于箭头动画和dismiss判断
    var actualDragDistance by remember { mutableStateOf(0f) }

    // LazyListState for Compact mode - 用于判断列表是否在顶部
    // 初始即定位到 Currently Playing 行（对齐 iOS：打开播放器顶部就是当前歌曲，
    // 不再先渲染 Previous 顶部再延迟跳转）
    val compactListState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember { viewModel.initialQueueScrollIndex() }
    )

    // 缩放和位置动画状态
    val scaleX = remember { Animatable(1f) }
    val scaleY = remember { Animatable(1f) }
    val translationY = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) } // 添加透明度动画

    // PopupPlayerScreen的bounds
    var popupBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // 执行dismiss动画的辅助函数：缩回 MiniPlayer 位置
    fun performDismissAnimation() {
        coroutineScope.launch {
            val miniBounds = miniPlayerBounds
            val popup = popupBounds
            if (miniBounds != null && popup != null && popup.width > 0f && popup.height > 0f) {
                // 计算缩放比例：MiniPlayer的大小 / PopupPlayer的大小
                // 分别计算X和Y方向的缩放，不保持宽高比
                val targetScaleX = miniBounds.width / popup.width
                val targetScaleY = miniBounds.height / popup.height
                // 需要移动的距离（中心对中心）
                val targetTranslationY =
                    (miniBounds.top + miniBounds.height / 2f) - (popup.top + popup.height / 2f)

                // 并行执行并等待全部动画真正完成后再关闭：
                // 此前用固定 delay(400) 收尾，弹簧未稳定就卸载会在结尾跳变；
                // 缩回改临界阻尼，消除收尾回弹
                kotlinx.coroutines.coroutineScope {
                    launch {
                        dragOffsetY.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 1f,
                                stiffness = 350f,
                                visibilityThreshold = 0.5f
                            )
                        )
                    }
                    launch {
                        scaleX.animateTo(
                            targetValue = targetScaleX,
                            animationSpec = spring(dampingRatio = 1f, stiffness = 350f)
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = targetScaleY,
                            animationSpec = spring(dampingRatio = 1f, stiffness = 350f)
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = targetTranslationY,
                            animationSpec = spring(
                                dampingRatio = 1f,
                                stiffness = 350f,
                                visibilityThreshold = 0.5f
                            )
                        )
                    }
                    launch {
                        // 透明度先行淡出（缩放到一半时已基本透明，露出下层 MiniPlayer）
                        alpha.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 1f, stiffness = 600f)
                        )
                    }
                }
                onBackClick()
            } else {
                onBackClick()
            }
        }
    }

    // 阻尼系数：模拟iOS UIScrollView的橡皮筋效果
    // iOS使用对数曲线阻尼，开始时阻力很大，越拉越容易
    fun applyResistance(offset: Float): Float {
        if (offset <= 0) return 0f

        // iOS橡皮筋公式 (reverse-engineered from UIKit):
        // rubberBand(x, coeff, dim) = (1.0 - (1.0 / ((x * coeff / dim) + 1.0))) * dim
        //
        // 这个公式的特点:
        // - 初期阻力非常大（前50-100px需要用力拉）
        // - 指数衰减（随着拖动距离增加，阻力逐渐减小）
        // - coefficient控制整体阻力强度，iOS默认约0.55
        //
        // 对比效果 (coefficient=0.55, dimension=300):
        // offset=100 -> result≈55  (阻尼率55%)
        // offset=300 -> result≈165 (阻尼率55%)
        // offset=600 -> result≈275 (阻尼率46%)


        val coefficient = 0.65f  // 调整为0.65，降低阻力，增加视觉位移
        val dimension = dismissThreshold

        // iOS公式：(1.0 - (1.0 / ((x * coeff / dim) + 1.0))) * dim
        //
        // coefficient对视觉位移的影响（当actualDragDistance = dismissThreshold时）：
        // coeff=0.55 -> 视觉位移 = dismissThreshold * 35.5%
        // coeff=0.65 -> 视觉位移 = dismissThreshold * 39.4%
        // coeff=0.75 -> 视觉位移 = dismissThreshold * 42.9%
        //
        // 当前配置：dismissThreshold=600, coeff=0.65 -> 视觉位移约236px
        val result = (1.0f - (1.0f / ((offset * coefficient / dimension) + 1.0f))) * dimension

        return result
    }

    // 首次进入时的滑入动画
    LaunchedEffect(Unit) {
        // 等首帧提交后再起动画：打开瞬间是全屏播放器重组/测量成本最高的一帧，
        // 此时播放器整体还在屏外（初始偏移），贵的一帧不可见也不吃动画帧预算
        withFrameNanos { }
        dragOffsetY.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = 1f,          // 临界阻尼：大位移滑入无过冲更顺滑
                stiffness = 300f,
                visibilityThreshold = 0.5f  // 半像素即收敛，避免亚像素长尾拖动画时长
            )
        )
        // 动画完成后再启动高频进度轮询，不与滑入动画抢帧
        viewModel.ensurePositionUpdatesStarted()
    }

    // 嵌套滚动连接: 当内部列表滚动到顶部时,拦截继续向下的滑动来关闭播放器
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 向上滑动时，如果已经有下拉偏移，优先消耗这个偏移
                if (dragOffsetY.value > 0 && available.y < 0) {
                    actualDragDistance += available.y
                    val newOffset = applyResistance(actualDragDistance.coerceAtLeast(0f))
                    coroutineScope.launch {
                        dragOffsetY.snapTo(newOffset)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 向下滑动时，检查顶部item是否完全可见
                // firstVisibleItemIndex = 0 且 firstVisibleItemScrollOffset = 0 表示第一个item完全可见
                val isTopItemFullyVisible = compactListState.firstVisibleItemIndex == 0 &&
                                            compactListState.firstVisibleItemScrollOffset == 0

                // 只有同时满足以下条件才触发下拉关闭：
                // 1. 顶部item完全可见
                // 2. 向下滑动 (available.y > 0)
                // 3. 是用户主动拖动 (source == UserInput)，而不是fling (SideEffect)
                if (available.y > 0 && isTopItemFullyVisible && source == NestedScrollSource.UserInput) {
                    // 主动拖动，触发下拉关闭手势
                    actualDragDistance += available.y
                    val newOffset = applyResistance(actualDragDistance.coerceAtLeast(0f))
                    coroutineScope.launch {
                        dragOffsetY.snapTo(newOffset)
                    }
                    return Offset(0f, available.y)
                }
                // 其他情况（包括fling产生的SideEffect）不拦截，让列表显示系统的overscroll效果
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 滑动结束后的处理
                if (actualDragDistance > dismissThreshold) {
                    // 超过阈值：执行缩小到 MiniPlayer 的动画
                    performDismissAnimation()
                    actualDragDistance = 0f
                } else if (actualDragDistance > 0) {
                    // 未达到阈值：回弹
                    val startDistance = actualDragDistance

                    coroutineScope.launch {
                        var lastValue = dragOffsetY.value
                        dragOffsetY.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 1.0f,  // iOS使用临界阻尼，无过冲
                                stiffness = 300f
                            )
                        ) { // updateListener
                            val progress = (this.value / lastValue.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                            actualDragDistance = (startDistance * progress).coerceAtLeast(0f)
                            lastValue = this.value
                        }
                        actualDragDistance = 0f
                    }
                } else {
                    actualDragDistance = 0f
                }
                return Velocity.Zero
            }
        }
    }

    // iOS-style: Hide status bar and navigation bar for immersive experience
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent) // 设置为透明，显示背后的内容
            .nestedScroll(nestedScrollConnection) // 支持COMPACT模式的嵌套滚动
    ) {
        // 整个播放器界面作为一个可滑动的层
        // iOS: Always show PopupPlayerScreen, display "No music playing" when currentSong is null
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    val size = coordinates.size
                    popupBounds = androidx.compose.ui.geometry.Rect(
                        left = position.x,
                        top = position.y,
                        right = position.x + size.width,
                        bottom = position.y + size.height
                    )
                }
                .graphicsLayer {
                    // 应用Y轴平移（下拉手势）
                    val dragTranslation = dragOffsetY.value

                    // 应用缩放 - 分别控制X和Y方向，不保持宽高比
                    val currentScaleX = scaleX.value
                    val currentScaleY = scaleY.value
                    this.scaleX = currentScaleX
                    this.scaleY = currentScaleY

                    // 应用透明度
                    this.alpha = alpha.value

                    // 应用Y轴位移（移动到MiniPlayer位置）
                    transformOrigin = TransformOrigin.Center
                    this.translationY = dragTranslation + translationY.value
                }
                .clickable(
                    enabled = true,
                    onClick = { /* 拦截点击事件，防止穿透 */ },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            // Background image with blur effect (iOS PopupPlayer+Visuals.swift)
            // 背景也跟随界面移动，并根据下滑进度调整透明度
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                currentSong?.let { song ->
                    // Show current song artwork
                    BackgroundArtwork(
                        song = song
                    )
                } ?: run {
                    // iOS: When no music playing, show generated artwork background
                    // PopupPlayer+Visuals.swift line 108-143: refreshBackgroundItemArtwork()
                    // Uses .getGeneratedArtwork(theme: themePreference, artworkType: .song)
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Show default album artwork as background (iOS generated artwork)
                        // 空播放态占位（iOS ArtworkType.song），按主题色现画
                        Image(
                            painter = rememberDefaultArtworkPainter(DefaultArtworkType.SONG),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.2f
                        )

                        // White frosted glass effect (iOS style) - fully opaque
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surface,
                                            MaterialTheme.colorScheme.surface
                                        )
                                    )
                                )
                        )
                    }
                }
            }

                // Main layout column
                Column(modifier = Modifier.fillMaxSize()) {
                    // 1. Close button at top center (iOS: closeButtonPlaceholderView with 44dp height)
                    // 下滑时箭头逐渐变扁平成横线
                    // 添加系统状态栏避让
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars) // 避开系统状态栏
                            .height(24.dp)
                            .clickable(onClick = {
                                // 点击关闭按钮时也使用缩小动画
                                performDismissAnimation()
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        // 计算箭头压扁进度 (0f = 原始箭头, 1f = 完全压扁成线)
                        // 使用actualDragDistance（原始拖动距离）而非dragOffsetY.value（阻尼后的位移）
                        // 这样当手指拖动达到dismissThreshold时，箭头正好变成横线，同时触发dismiss
                        val flattenProgress = (actualDragDistance / dismissThreshold).coerceIn(0f, 1f)

                        // 箭头的垂直缩放: 从1.0渐变到0.1 (压扁)
                        val scaleY = 1f - (flattenProgress * 0.9f)

                        // 使用Canvas绘制箭头，实现平滑的压扁动画
                        // flattenProgress: 0f = 向下箭头(∨), 1f = 横线(—)
                        val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant

                        // 使用封装的箭头配置
                        val arrowConfig = remember { ArrowConfig(arrowAngleDegrees = 135f) }

                        Canvas(
                            modifier = Modifier.size(44.dp)
                        ) {
                            val canvasWidth = size.width
                            val canvasHeight = size.height

                            // 从配置中获取所有参数（自动转换为px）
                            val arrowWidth = arrowConfig.arrowWidth.dp.toPx()
                            val strokeWidth = arrowConfig.strokeWidth.dp.toPx()
                            val halfWidth = arrowConfig.halfWidth.dp.toPx()
                            val baseArrowHeight = arrowConfig.arrowHeight.dp.toPx()

                            // 垂直方向的压扁：
                            // - flattenProgress = 0: 正常箭头高度
                            // - flattenProgress = 1: 完全压扁成横线
                            val arrowHeight = baseArrowHeight * (1f - flattenProgress)

                            // 中心点
                            val centerX = canvasWidth / 2f
                            val centerY = canvasHeight / 2f

                            // 左侧线段: 从左上到中心底部
                            // 起点: (centerX - halfWidth, centerY - arrowHeight)
                            // 终点: (centerX, centerY)
                            val leftStartX = centerX - halfWidth
                            val leftStartY = centerY - arrowHeight
                            val leftEndX = centerX
                            val leftEndY = centerY

                            // 右侧线段: 从中心底部到右上
                            // 起点: (centerX, centerY)
                            // 终点: (centerX + halfWidth, centerY - arrowHeight)
                            val rightStartX = centerX
                            val rightStartY = centerY
                            val rightEndX = centerX + halfWidth
                            val rightEndY = centerY - arrowHeight

                            // 绘制左侧线段
                            drawLine(
                                color = arrowColor,
                                start = androidx.compose.ui.geometry.Offset(leftStartX, leftStartY),
                                end = androidx.compose.ui.geometry.Offset(leftEndX, leftEndY),
                                strokeWidth = strokeWidth,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round  // 圆形端点
                            )

                            // 绘制右侧线段
                            drawLine(
                                color = arrowColor,
                                start = androidx.compose.ui.geometry.Offset(rightStartX, rightStartY),
                                end = androidx.compose.ui.geometry.Offset(rightEndX, rightEndY),
                                strokeWidth = strokeWidth,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round  // 圆形端点
                            )
                        }
                    }

                // 2. Content area (LARGE view or COMPACT view)
                // 使用nestedScroll处理与列表滚动的协调，LARGE模式使用直接手势
                // SharedTransitionLayout：LARGE/COMPACT 切换时封面/红心/More 按钮
                // 位移缩放 + 两视图交叉淡入淡出（iOS PopupPlayer+Animations.swift，0.2s）
                androidx.compose.animation.SharedTransitionLayout(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f) // Takes remaining space above controls
                        .then(
                            // LARGE模式: 添加直接的拖动手势
                            if (displayMode == PlayerDisplayMode.LARGE) {
                                Modifier.pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            actualDragDistance = 0f
                                        },
                                        onDragEnd = {
                                            coroutineScope.launch {
                                                if (actualDragDistance > dismissThreshold) {
                                                    // 超过阈值：平滑缩小到 MiniPlayer
                                                    performDismissAnimation()
                                                    actualDragDistance = 0f
                                                } else {
                                                    // 否则回弹到原位
                                                    // 保存当前的拖动距离，用于动画过程
                                                    val startDistance = actualDragDistance

                                                    // 计算动画进度并同步更新actualDragDistance
                                                    var lastValue = dragOffsetY.value
                                                    dragOffsetY.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(
                                                            dampingRatio = 1.0f,  // iOS使用临界阻尼，无过冲
                                                            stiffness = 300f
                                                        )
                                                    ) { // updateListener
                                                        // 根据dragOffsetY的变化同步更新actualDragDistance
                                                        val progress = (this.value / lastValue.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                                                        actualDragDistance = (startDistance * progress).coerceAtLeast(0f)
                                                        lastValue = this.value
                                                    }
                                                    // 动画完成后确保重置为0
                                                    actualDragDistance = 0f
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            coroutineScope.launch {
                                                // 取消时也回弹
                                                // 保存当前的拖动距离，用于动画过程
                                                val startDistance = actualDragDistance

                                                // 计算动画进度并同步更新actualDragDistance
                                                var lastValue = dragOffsetY.value
                                                dragOffsetY.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = 1.0f,  // iOS使用临界阻尼，无过冲
                                                        stiffness = 300f
                                                    )
                                                ) { // updateListener
                                                    // 根据dragOffsetY的变化同步更新actualDragDistance
                                                    val progress = (this.value / lastValue.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                                                    actualDragDistance = (startDistance * progress).coerceAtLeast(0f)
                                                    lastValue = this.value
                                                }
                                                // 动画完成后确保重置为0
                                                actualDragDistance = 0f
                                            }
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            // 只允许向下拖动
                                            if (dragAmount > 0 || actualDragDistance > 0) {
                                                actualDragDistance = (actualDragDistance + dragAmount).coerceAtLeast(0f)
                                                // 应用阻尼效果
                                                val offsetWithResistance = applyResistance(actualDragDistance)
                                                coroutineScope.launch {
                                                    dragOffsetY.snapTo(offsetWithResistance)
                                                }
                                            }
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.amperfy.ui.screens.player.components.LocalPlayerSharedTransitionScope provides this
                    ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = displayMode,
                        transitionSpec = {
                            // iOS 时序：消失视图 0→133ms 淡出，出现视图 67→200ms 淡入
                            // （displaStyleAnimationDuration=0.2s 的 2/3 时长、1/3 延迟）
                            androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(133, delayMillis = 67)
                            ) togetherWith androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(133)
                            )
                        },
                        label = "playerDisplayStyle"
                    ) { mode ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.amperfy.ui.screens.player.components.LocalPlayerAnimatedVisibilityScope provides this
                    ) {
                    when (mode) {
                        PlayerDisplayMode.COMPACT -> {
                            // iOS: TableView with CurrentlyPlayingTableCell header
                            QueueListView(
                                viewModel = viewModel,
                                isFavorite = isFavorite,
                                onToggleFavorite = { viewModel.toggleFavorite() },
                                onSwitchToLarge = { viewModel.switchDisplayMode() },
                                listState = compactListState,
                                // Menu actions
                                rating = rating,
                                isCached = isCached,
                                isOnlineMode = isOnlineMode,
                                hasLyrics = hasLyrics,
                                onShowAlbum = {
                                    currentSongDetails?.albumId?.let { albumId ->
                                        navigateAndClose { onNavigateToAlbum(albumId) }
                                    }
                                },
                                onShowArtist = {
                                    currentSongDetails?.artistId?.let { artistId ->
                                        navigateAndClose { onNavigateToArtist(artistId) }
                                    }
                                },
                                onShowLyrics = { showLyricsDialog = true },
                                onSetRating = { viewModel.setRating(it) },
                                onAddToPlaylist = { viewModel.addCurrentSongToPlaylist() },
                                onDownload = { viewModel.downloadSong() },
                                onDeleteCache = { viewModel.deleteSongCache() },
                                // 队列行长按菜单的跳转（按 id 导航并收起播放器）
                                onShowAlbumById = { albumId ->
                                    navigateAndClose { onNavigateToAlbum(albumId) }
                                },
                                onShowArtistById = { artistId ->
                                    navigateAndClose { onNavigateToArtist(artistId) }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        PlayerDisplayMode.LARGE -> {
                            // iOS: LargeCurrentlyPlayingPlayerView
                            currentSong?.let { song ->
                                // 导航辅助函数 - 消除重复代码
                                // Title和Album都导航到AlbumDetailScreen
                                val navigateToAlbum: () -> Unit = {
                                    currentSongDetails?.albumId?.let { albumId ->
                                        navigateAndClose { onNavigateToAlbum(albumId) }
                                    }
                                    Unit
                                }

                                val navigateToArtist: () -> Unit = {
                                    currentSongDetails?.artistId?.let { artistId ->
                                        navigateAndClose { onNavigateToArtist(artistId) }
                                    }
                                    Unit
                                }

                                LargePlayerView(
                                    song = song,
                                    isPlaying = isPlaying,
                                    isFavorite = isFavorite,
                                    onToggleFavorite = { viewModel.toggleFavorite() },
                                    onTitleClick = navigateToAlbum,  // Title点击 → AlbumDetailScreen
                                    onAlbumClick = navigateToAlbum,  // Album点击 → AlbumDetailScreen
                                    onArtistClick = navigateToArtist, // Artist点击 → ArtistDetailScreen
                                    onPlayNext = { viewModel.skipToNext() },
                                    onPlayPrevious = { viewModel.skipToPrevious() },
                                    onSwitchToCompact = { viewModel.switchDisplayMode() },
                                    // Menu actions
                                    rating = rating,
                                    isCached = isCached,
                                    isOnlineMode = isOnlineMode,
                                    hasLyrics = hasLyrics,
                                    // 三态主区（封面/歌词/可视化）
                                    displayElement = largeDisplayElement,
                                    visualizerContent = { m ->
                                        val spectrum by viewModel.spectrum.collectAsState()
                                        com.amperfy.ui.screens.player.components.visualizer.VisualizerView(
                                            type = selectedVisualizerType,
                                            spectrum = spectrum,
                                            modifier = m
                                        )
                                    },
                                    // Batch 2：播客模式红心换 info（iOS PopupPlayer+Visuals:97-106）
                                    isPodcastMode = playerModeForVisuals ==
                                        com.amperfy.data.model.PlayerMode.PODCAST,
                                    // 播放器内评分：设置开启且可评分曲目，离线时只读
                                    showRating = isPlayerRatingDisplayed &&
                                        !song.isRadio && !song.isPodcastEpisode,
                                    isRatingEnabled = isOnlineMode,
                                    // 音频徽标
                                    showAudioInfo = isShowDetailedInfo,
                                    bitRate = currentSongDetails?.bitRate,
                                    suffix = currentSongDetails?.suffix,
                                    // 歌词视图（Phase 6.2）
                                    lyrics = currentLyrics,
                                    isLyricsSmoothScrolling = isLyricsSmoothScrolling,
                                    getPositionMs = { viewModel.getCurrentPositionMs() },
                                    onShowAlbum = navigateToAlbum,
                                    onShowArtist = navigateToArtist,
                                    onShowLyrics = { showLyricsDialog = true },
                                    onSetRating = { viewModel.setRating(it) },
                                    onAddToPlaylist = { viewModel.addCurrentSongToPlaylist() },
                                    onDownload = { viewModel.downloadSong() },
                                    onDeleteCache = { viewModel.deleteSongCache() },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } ?: run {
                                // 清空播放器后的空态：保持与正常播放完全一致的布局骨架——
                                // 大封面位置显示生成占位图、标题行显示 "No music playing"、
                                // artist 行留空（对齐 iOS PopupPlayer+Visuals.swift
                                // refreshCurrentlyPlayingInfo else 分支 + refreshArtwork 占位封面；
                                // iOS 清空后控制区上方不是空白，布局结构原样保留）
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Spacer(modifier = Modifier.weight(0.5f))

                                    // 大封面占位（与 LargePlayerView 同尺寸/圆角/阴影）
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .padding(horizontal = 20.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
                                    ) {
                                        // 空播放态占位（iOS ArtworkType.song），按主题色现画
                                        Image(
                                            painter = rememberDefaultArtworkPainter(
                                                DefaultArtworkType.SONG
                                            ),
                                            contentDescription = "No music playing",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(32.dp))

                                    // 标题行（与 LargePlayerView detailsContainer 同位置左对齐）
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "No music playing",
                                                style = MaterialTheme.typography.headlineSmall.copy(
                                                    fontSize = 20.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    } // CompositionLocalProvider(LocalPlayerAnimatedVisibilityScope)
                    } // AnimatedContent
                    } // CompositionLocalProvider(LocalPlayerSharedTransitionScope)
                }

                // 3. Control View (bottom, fixed - 175dp + 20dp margin) - iOS: PlayerControlView
                // This NEVER moves when switching between LARGE and COMPACT modes
                // iOS: margin.bottom = 20pt + safetyMarginOnBottom = 20pt + Safe Area (Home Indicator)
                val areSkipButtonsVisible by viewModel.areSkipButtonsVisible.collectAsState()
                val isSkipAvailable by viewModel.isSkipAvailable.collectAsState()
                // Batch 2：playerMode 切换按钮（播客队列非空或当前处于播客模式时显示）
                val isPlayerModeButtonVisible by viewModel.isPlayerModeButtonVisible.collectAsState()
                val currentPlayerMode by viewModel.playerMode.collectAsState()
                PlayerControlsSection(
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPlayPauseClick = { viewModel.playPause() },
                    onPreviousClick = { viewModel.skipToPrevious() },
                    onNextClick = { viewModel.skipToNext() },
                    onSeek = { viewModel.seekTo(it) },
                    onSwitchView = { viewModel.switchDisplayMode() },
                    displayMode = displayMode,
                    // Skip Buttons（Settings→Display→Music Player Skip Buttons）
                    showSkipButtons = areSkipButtonsVisible,
                    isSkipEnabled = isSkipAvailable,
                    onSkipBackward = { viewModel.skipBackward() },
                    onSkipForward = { viewModel.skipForward() },
                    // playerMode 切换（音乐 ⇄ 播客）
                    showPlayerModeButton = isPlayerModeButtonVisible,
                    isPodcastMode = currentPlayerMode == com.amperfy.data.model.PlayerMode.PODCAST,
                    onTogglePlayerMode = { viewModel.togglePlayerMode() },
                    // Player options menu state
                    hasQueue = hasQueue,
                    hasUserQueue = hasUserQueue,
                    isOnlineMode = isOnlineMode,
                    isLyricsDisplayed = isPlayerLyricsDisplayed,
                    isLyricsButtonAllowedToDisplay = isLyricsButtonAllowedToDisplay,
                    // Audio Visualizer 菜单状态
                    isVisualizerDisplayed = isPlayerVisualizerDisplayed,
                    isVisualizerButtonAllowedToDisplay = isVisualizerButtonAllowedToDisplay,
                    selectedVisualizerType = selectedVisualizerType,
                    onShowVisualizer = { viewModel.showVisualizer() },
                    onHideVisualizer = { viewModel.hideVisualizer() },
                    onSetVisualizerType = { viewModel.setVisualizerType(it) },
                    // Playback Rate / Sleep Timer 状态（Phase 4.1/4.2）
                    currentPlaybackRate = currentPlaybackRate,
                    onSetPlaybackRate = { viewModel.setPlaybackRate(it) },
                    sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                    isPauseAfterCurrentSong = isPauseAfterCurrentSong,
                    onStartSleepTimer = { viewModel.startSleepTimer(it) },
                    onPauseAfterCurrentSong = { viewModel.activatePauseAfterCurrentSong() },
                    onCancelSleepTimer = { viewModel.cancelSleepTimer() },
                    // Player options menu actions
                    onClearPlayer = { viewModel.clearPlayer() },
                    onClearUserQueue = { viewModel.clearUserQueue() },
                    onPlayerInfo = { viewModel.showPlayerInfo() },
                    // 歌词视图本体随 Phase 6.2 实现，当前维护状态 + Show 时切回 LARGE（对齐 iOS）
                    onShowLyrics = { viewModel.showLyrics() },
                    onHideLyrics = { viewModel.hideLyrics() },
                    onAddContextQueueToPlaylist = { viewModel.addContextQueueToPlaylist() },
                    onScrollToCurrentlyPlaying = { viewModel.scrollToCurrentlyPlaying() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 20.dp)
                        .padding(bottom = 20.dp) // iOS: safetyMarginOnBottom = 20pt
                        .windowInsetsPadding(WindowInsets.navigationBars) // 自动适配底部导航栏/手势条（对应iOS Safe Area）
                )
                }
            }
        }

        // 添加到播放列表选择器（Add to Playlist / Add Context Queue to Playlist）
        val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
        pendingPlaylistSongIds?.let { ids ->
            com.amperfy.ui.components.PlaylistSelectorDialog(
                songIds = ids,
                onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
            )
        }
    }
