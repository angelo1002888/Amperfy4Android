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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.amperfy.ui.components.IOSMusicControls
import com.amperfy.ui.components.IOSStylePreciseSeekSlider
import com.amperfy.ui.screens.player.PlayerDisplayMode
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.gray3
import com.amperfy.ui.theme.label
import kotlinx.coroutines.launch

/**
 * Player controls section - FIXED at bottom, NEVER MOVES
 * iOS: PlayerControlView (175pt height + 20pt safety margin)
 *
 * Contains:
 * - Time slider
 * - Play/pause, previous, next, skip buttons
 * - Bottom options row: airplay, displayPlaylist, playerMode, optionsButton
 *
 * The optionsButton in this section shows PLAYER-LEVEL options (different from song options)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControlsSection(
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onSwitchView: () -> Unit,
    displayMode: PlayerDisplayMode,
    // Music Player Skip Buttons（Settings→Display，对应 iOS PlayerControlView skip 按钮）
    showSkipButtons: Boolean = false,
    isSkipEnabled: Boolean = true,
    onSkipBackward: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    // Batch 2：playerMode 切换按钮（iOS PlayerControlView.playerModeButton）
    showPlayerModeButton: Boolean = false,
    isPodcastMode: Boolean = false,
    onTogglePlayerMode: () -> Unit = {},
    // Player options menu state
    hasQueue: Boolean = false,
    hasUserQueue: Boolean = false,
    isOnlineMode: Boolean = true,
    isLyricsDisplayed: Boolean = false,
    isLyricsButtonAllowedToDisplay: Boolean = false,
    // Audio Visualizer 菜单状态
    isVisualizerDisplayed: Boolean = false,
    isVisualizerButtonAllowedToDisplay: Boolean = false,
    selectedVisualizerType: com.amperfy.data.model.VisualizerType = com.amperfy.data.model.VisualizerType.RING,
    onShowVisualizer: () -> Unit = {},
    onHideVisualizer: () -> Unit = {},
    onSetVisualizerType: (com.amperfy.data.model.VisualizerType) -> Unit = {},
    // Playback Rate / Sleep Timer 状态（Phase 4.1/4.2）
    currentPlaybackRate: Float = 1.0f,
    onSetPlaybackRate: (Float) -> Unit = {},
    sleepTimerRemainingSeconds: Int = 0,
    isPauseAfterCurrentSong: Boolean = false,
    onStartSleepTimer: (Int) -> Unit = {},
    onPauseAfterCurrentSong: () -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    // Player options menu actions
    onClearPlayer: () -> Unit = {},
    onClearUserQueue: () -> Unit = {},
    onPlayerInfo: () -> Unit = {},
    onShowLyrics: () -> Unit = {},
    onHideLyrics: () -> Unit = {},
    onAddContextQueueToPlaylist: () -> Unit = {},
    onScrollToCurrentlyPlaying: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 将状态提升到Column外部，避免重组时丢失状态
    var showPlayerOptionsMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Progress slider (iOS: timeSlider, 30pt height)
        // iOS styling: Label color thumb (黑色Light/白色Dark), blue active track, light gray inactive track
        IOSStylePreciseSeekSlider(
            currentPosition = currentPosition,
            duration = duration,
            onSeek = onSeek,
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
        )


        // Time labels (iOS: elapsedTimeLabel & remainingTimeLabel)
        // iOS: Left shows elapsed time (2:56), Right shows remaining time with minus sign (-2:13)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentPosition),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, // iOS .secondaryLabel
                fontSize = 13.sp
            )
            Text(
                // iOS: Remaining time with minus sign；duration 未知（切歌未就绪/电台）时
                // 显示 "--:--"（PlayerControlView.remainingTime == nil 分支），
                // 并对差值钳非负，防止切歌窗口出现负数垃圾值
                text = if (duration > 0) {
                    formatRemainingTime((duration - currentPosition).coerceAtLeast(0L))
                } else {
                    "--:--"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, // iOS .secondaryLabel
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Control buttons (iOS: 50pt height)
        // iOS: Previous (◀◀), Play/Pause (▶), Next (▶▶)，Skip Buttons 设置开启时两侧加 ±10s
        IOSMusicControls(
            isPlaying = isPlaying,
            onPreviousClick = onPreviousClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick,
            showSkipButtons = showSkipButtons,
            isSkipEnabled = isSkipEnabled,
            onSkipBackward = onSkipBackward,
            onSkipForward = onSkipForward
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Options row (iOS: optionsStackView, 28pt height)
        // iOS: Only 3 buttons - AirPlay (左), Display mode toggle (中), More (右) with .label color icons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // AirPlay (iOS: airplayButton)
            IconButton(onClick = { /* AirPlay */ }, modifier = Modifier.size(28.dp)) {
                Icon(
                    // iOS: AmperfyImage.airplayaudio；Android 该位本就是 Cast 语义，
                    // 永久保留 Material
                    AmperfyIcons.airplayaudio,
                    contentDescription = "AirPlay",
                    tint = MaterialTheme.colorScheme.label, // iOS: .label (Light: 黑色, Dark: 白色)
                    modifier = Modifier.size(20.dp)
                )
            }

            // Display playlist / Switch view (iOS: displayPlaylistButton)
            // iOS: UIButton.Configuration.player(isSelected: displayMode == .compact)
            // Icon: list.bullet (SF Symbol)
            val isQueueButtonSelected = displayMode == PlayerDisplayMode.COMPACT
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp)) // iOS: .medium cornerStyle
                    .background(
                        if (isQueueButtonSelected) {
                            MaterialTheme.colorScheme.label // iOS: .label background when selected
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent // iOS: .clear when unselected
                        }
                    )
                    .then(
                        if (isQueueButtonSelected) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.label, // iOS: 1px border
                                shape = RoundedCornerShape(6.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable(onClick = onSwitchView),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AmperfyIcons.listBullet, // iOS: AmperfyImage.playlistDisplayStyle ("list.bullet")
                    contentDescription = "Queue",
                    tint = if (isQueueButtonSelected) {
                        MaterialTheme.colorScheme.background // iOS: .systemBackground when selected (反色)
                    } else {
                        MaterialTheme.colorScheme.label // iOS: .label when unselected
                    },
                    modifier = Modifier.size(18.dp) // iOS: .medium scale
                )
            }

            // playerMode 切换按钮（iOS: PlayerControlView.playerModeButton +
            // playerModeChangePressed:237-246 / refreshPlayerModeChangeButton:438-448）。
            // 图标为「目标模式」：音乐态显示播客图标（点击去播客）、播客态显示音符（点击回音乐）；
            // 播客队列为空且当前非播客模式时整枚隐藏。
            // 图标画的是「**当前模式**」而非切换目标，对齐 iOS refreshPlayerModeChangeButton
            // （PlayerControlView.swift:441-446：music → .musicalNotes("music.note")、
            //  podcast → 自定义 asset `podcast`）。
            // 注意：B7 曾误按「目标模式」实现，2026-08-08 用户重裁后改回对齐 iOS
            if (showPlayerModeButton) {
                IconButton(onClick = onTogglePlayerMode, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (isPodcastMode) {
                            AmperfyIcons.podcast
                        } else {
                            AmperfyIcons.musicalNotes
                        },
                        contentDescription = if (isPodcastMode) "Podcast Mode" else "Music Mode",
                        tint = MaterialTheme.colorScheme.label,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 音量按钮（iOS: PlayerControlView.volumeButton，xib 28×28、SF Symbol volume.3.fill）
            // iOS 底排顺序 airplay → displayPlaylist → playerMode → volume → options；
            // 点击弹出 popover 气泡滑杆（showVolumeSliderMenu → SliderMenuPopover，箭头朝下浮在按钮上方）。
            // 音量按钮在 LARGE/COMPACT 均显示。
            VolumeSliderPopup()

            // PLAYER-LEVEL Options (iOS: optionsButton from PlayerControlView)
            // This shows createPlayerOptionsMenu() - different from song options!
            Box {
                IconButton(
                    onClick = { showPlayerOptionsMenu = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        AmperfyIcons.ellipsis, // iOS: AmperfyImage.ellipsis
                        contentDescription = "Player Options",
                        tint = MaterialTheme.colorScheme.label, // iOS: .label (Light: 黑色, Dark: 白色)
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Player-level options menu (iOS: createPlayerOptionsMenu from PlayerControlView)
                PlayerOptionsMenu(
                    expanded = showPlayerOptionsMenu,
                    onDismiss = { showPlayerOptionsMenu = false },
                    // 右缘对齐 More 按钮右缘、底边贴按钮顶边（PlayerOptionsMenu 内部
                    // placeAboveAnchor=true 实现）；此前的 IntOffset(-32,-64) 魔法偏移已删
                    alignment = Alignment.BottomEnd,
                    displayMode = displayMode,
                    // Queue 状态
                    hasQueue = hasQueue,
                    hasUserQueue = hasUserQueue,
                    isOnlineMode = isOnlineMode,
                    isPodcastMode = isPodcastMode,
                    // LARGE 模式状态
                    isLyricsDisplayed = isLyricsDisplayed,
                    isLyricsButtonAllowedToDisplay = isLyricsButtonAllowedToDisplay,
                    // Audio Visualizer 状态
                    isVisualizerDisplayed = isVisualizerDisplayed,
                    isVisualizerButtonAllowedToDisplay = isVisualizerButtonAllowedToDisplay,
                    selectedVisualizerType = selectedVisualizerType,
                    onShowVisualizer = onShowVisualizer,
                    onHideVisualizer = onHideVisualizer,
                    onSetVisualizerType = onSetVisualizerType,
                    // Playback Rate / Sleep Timer 状态
                    currentPlaybackRate = currentPlaybackRate,
                    onSetPlaybackRate = onSetPlaybackRate,
                    sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                    isPauseAfterCurrentSong = isPauseAfterCurrentSong,
                    onStartSleepTimer = onStartSleepTimer,
                    onPauseAfterCurrentSong = onPauseAfterCurrentSong,
                    onCancelSleepTimer = onCancelSleepTimer,
                    // Actions
                    onClearPlayer = onClearPlayer,
                    onClearUserQueue = onClearUserQueue,
                    onPlayerInfo = onPlayerInfo,
                    // LARGE 模式 Actions
                    onShowLyrics = onShowLyrics,
                    onHideLyrics = onHideLyrics,
                    onAddContextQueueToPlaylist = onAddContextQueueToPlaylist,
                    // COMPACT 模式 Actions
                    onScrollToCurrentlyPlaying = onScrollToCurrentlyPlaying
                )
            }
        }
    }
}

/**
 * 音量按钮 + popover 气泡滑杆（iOS 2.1.0: PlayerControlView.volumeButton +
 * showVolumeSliderMenu → SliderMenuPopover）
 *
 * 点击喇叭按钮在其正上方弹出气泡（250×50、箭头朝下，对齐 iOS popover permittedArrowDirections=.down、
 * preferredContentSize 250×50），气泡内为单根滑杆（对齐 SliderMenuView：左右 10pt 边距、垂直居中、
 * 无最小/最大图标无文字）。外点或再点按钮关闭。
 *
 * 音量读写系统媒体流 [android.media.AudioManager.STREAM_MUSIC]；外部音量变化（音量键/系统面板）经
 * [android.database.ContentObserver] 监听 [android.provider.Settings.System] 并 200ms 去抖回读同步。
 * 已知取舍：蓝牙绝对音量设备上系统音量分级粗（步进大），滑块随之跳变，不额外插值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSliderPopup() {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    // 当前音量（0..1）。用户拖动时以本地值为准，外部变化经 ContentObserver 回读同步
    var volume by remember {
        mutableStateOf(
            audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        )
    }

    // 气泡显隐状态：用 MutableTransitionState 让 Popup 存活到退场动画结束
    // （对齐 iOS popover animated:true 的进/出场动画，见下方 AnimatedVisibility）
    val popupVisible = remember { MutableTransitionState(false) }

    // 监听系统音量外部变化（音量键/系统面板），200ms 去抖回读
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pending: kotlinx.coroutines.Job? = null
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                pending?.cancel()
                pending = scope.launch {
                    kotlinx.coroutines.delay(200)
                    val v = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        .toFloat() / maxVolume
                    volume = v.coerceIn(0f, 1f)
                }
            }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    // 气泡尺寸/箭头（对齐 iOS SliderMenuPopover：250×50 + 朝下箭头）
    val bubbleColor = MaterialTheme.colorScheme.surface
    val arrowWidth = 14.dp
    val arrowHeight = 7.dp

    // 按钮外套 Box 作为 Popup 锚点（iOS popover sourceView=volumeButton）
    Box {
        IconButton(
            onClick = { popupVisible.targetState = !popupVisible.targetState },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                // iOS: xib "volume.3.fill" = AmperfyImage.volumeMax ("speaker.wave.3.fill")
                AmperfyIcons.volumeMax,
                contentDescription = "Volume",
                tint = MaterialTheme.colorScheme.label, // iOS: tint labelColor
                modifier = Modifier.size(20.dp)
            )
        }

        // 进场时立即组合、退场时保留到动画结束（currentState 与 targetState 任一为 true 即组合）
        if (popupVisible.currentState || popupVisible.targetState) {
            // 气泡定位：水平居中于按钮、垂直置于按钮正上方（含箭头间隙 2dp）
            val density = LocalDensity.current
            val gapPx = with(density) { 2.dp.roundToPx() }
            val positionProvider = remember(gapPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        val x = anchorBounds.left + anchorBounds.width / 2 - popupContentSize.width / 2
                        val y = anchorBounds.top - popupContentSize.height - gapPx
                        val clampedX = x.coerceIn(
                            0,
                            (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        )
                        return IntOffset(clampedX, y)
                    }
                }
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { popupVisible.targetState = false },
                // focusable = true 让弹窗消费外部点击：外点仅触发 dismiss、不再穿透给喇叭按钮，
                // 修复「气泡打开时再点喇叭，dismiss 与按钮 onClick 竞态重开导致关不掉」；返回键亦可关闭
                properties = PopupProperties(focusable = true)
            ) {
                // 进/出场动画对齐 iOS popover（UIPopoverPresentationController animated:true）：
                // 从底边中点（箭头指向按钮处，transformOrigin 0.5,1）缩放生长 + 淡入约 0.2s 出现，
                // 反向收缩淡出约 0.18s 消失
                AnimatedVisibility(
                    visibleState = popupVisible,
                    enter = fadeIn(tween(200)) + scaleIn(
                        animationSpec = tween(220),
                        initialScale = 0.85f,
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    ),
                    exit = fadeOut(tween(180)) + scaleOut(
                        animationSpec = tween(180),
                        targetScale = 0.85f,
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 气泡本体（250×50，圆角 14dp + 阴影，背景 surface 深浅自适应）
                        Box(
                            modifier = Modifier
                                .size(width = 250.dp, height = 50.dp)
                                .shadow(8.dp, RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .background(bubbleColor),
                            contentAlignment = Alignment.Center
                        ) {
                            VolumeSlider(
                                value = volume,
                                onValueChange = { newValue ->
                                    volume = newValue
                                    val level = (newValue * maxVolume).toInt().coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(
                                        android.media.AudioManager.STREAM_MUSIC, level, 0
                                    )
                                }
                            )
                        }
                        // 朝下的小三角箭头（iOS popover .down 箭头），气泡底边中点指向按钮
                        Canvas(modifier = Modifier.size(width = arrowWidth, height = arrowHeight)) {
                            val path = Path().apply {
                                moveTo(0f, 0f)
                                lineTo(size.width, 0f)
                                lineTo(size.width / 2f, size.height)
                                close()
                            }
                            drawPath(path, color = bubbleColor)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 气泡内单根音量滑杆（对齐 iOS SliderMenuView：左右 10pt 边距、垂直居中、无两侧图标无文字）。
 * 视觉对齐 iOS UISlider：轨道 4dp 圆头（未填充 gray3、已填充 primary），拇指白色圆形 22dp + 轻阴影。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val trackHeight = 4.dp
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp), // iOS SliderMenuView 左右 10pt 边距
        thumb = {
            // iOS UISlider 拇指：白色圆形 + 轻微阴影
            Box(
                Modifier
                    .size(22.dp)
                    .shadow(2.dp, CircleShape)
                    .background(Color.White, CircleShape)
            )
        },
        track = { sliderPositions ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                contentAlignment = Alignment.Center
            ) {
                // 未填充底轨（灰）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(trackHeight)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.gray3)
                )
                // 已填充轨（iOS minimumTrack = tint/primary 色）
                Box(
                    Modifier
                        .fillMaxWidth(sliderPositions.value)
                        .height(trackHeight)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .align(Alignment.CenterStart)
                )
            }
        }
    )
}

// Time formatting function
private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).toInt()
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%d:%02d", minutes, remainingSeconds)
}

/**
 * 格式化剩余时间（带负号）
 * iOS: remainingTimeLabel 显示为 "-2:13" 格式
 */
private fun formatRemainingTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).toInt()
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("-%d:%02d", minutes, remainingSeconds)
}
