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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amperfy.data.model.Playable
import com.amperfy.ui.components.FavoriteButton
import com.amperfy.ui.components.RatingView
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.screens.player.LargeDisplayElement
import com.amperfy.ui.screens.player.PlayerDisplayMode
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.defaultArtworkTypeFor
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * Background artwork with blur and gradient effect
 * iOS: PopupPlayer+Visuals.swift - refreshBackgroundItemArtwork()
 * iOS使用白色毛玻璃效果
 */
@Composable
fun BackgroundArtwork(
    song: Playable
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Blurred background image - 更淡的专辑封面
        // 默认图按播放项类型现画（随账户主题色，见 ui/util/DefaultArtwork.kt）
        val defaultArtwork = rememberDefaultArtworkPainter(defaultArtworkTypeFor(song))
        AsyncImage(
            model = buildCoverArtUrl(song.coverArt, credentialsManager, musicRepository),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.2f, // 淡淡的封面背景
            placeholder = defaultArtwork,
            error = defaultArtwork,
            fallback = defaultArtwork
        )

        // 白色毛玻璃效果 (iOS风格) - 完全不透明
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface, // iOS .systemBackground - 顶部完全不透明
                            MaterialTheme.colorScheme.surface  // iOS .systemBackground - 底部完全不透明
                        )
                    )
                )
        )
    }
}

/**
 * Large player view - Shows album artwork and song details with favorite + options buttons
 * iOS: LargeCurrentlyPlayingPlayerView.swift
 *
 * Components:
 * - upperContainerView: Large artwork (300x300px)
 * - detailsContainer: Song info + favoriteButton + optionsButton
 */
@Composable
fun LargePlayerView(
    song: Playable,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onTitleClick: () -> Unit,
    onAlbumClick: () -> Unit,
    onArtistClick: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onSwitchToCompact: () -> Unit,
    // Menu actions
    rating: Int = 0,
    isCached: Boolean = false,
    isOnlineMode: Boolean = true,
    hasLyrics: Boolean = false,
    // 主区显示元素（三态：封面/歌词/可视化，iOS: LargeDisplayElement）
    displayElement: LargeDisplayElement = LargeDisplayElement.ARTWORK,
    // 可视化视图内容槽（由 Screen 提供，内部 collectAsState 频谱，隔离 30fps 重组）
    visualizerContent: (@Composable (Modifier) -> Unit)? = null,
    // 播放器内五星评分：仅设置开启且可评分曲目时显示，挂封面与信息之间
    showRating: Boolean = false,
    isRatingEnabled: Boolean = true,
    /**
     * 播客模式（Batch 2）：红心按钮换成 info 图标按钮
     * （iOS PopupPlayer+Visuals.refreshFavoriteButton:97-106 的 podcast 分支——
     * `config.image = .info`、`button.isEnabled = true` 恒可用）
     */
    isPodcastMode: Boolean = false,
    // 音频信息徽标：Detailed Information 开启时展示
    showAudioInfo: Boolean = false,
    bitRate: Int? = null,
    suffix: String? = null,
    // 歌词视图（Phase 6.2，iOS: LargeCurrentlyPlayingPlayerView lyricsView 叠放于封面之上）
    lyrics: com.amperfy.data.model.StructuredLyrics? = null,
    isLyricsSmoothScrolling: Boolean = true,
    getPositionMs: () -> Long = { 0L },
    onShowAlbum: () -> Unit = {},
    onShowArtist: () -> Unit = {},
    onShowLyrics: () -> Unit = {},
    onSetRating: (Int) -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onDownload: () -> Unit = {},
    onDeleteCache: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val hapticFeedback = LocalHapticFeedback.current

    // 滑动状态
    // 对应iOS: LargeCurrentlyPlayingPlayerView - Swipe手势检测
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 50.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.5f))

        // upperContainerView: Large artwork display (iOS: 300x300px)
        // 添加水平滑动手势：左滑下一曲，右滑上一曲
        // 对应iOS: LargeCurrentlyPlayingPlayerView.swift:160-197 - Swipe Gesture
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(horizontal = 20.dp)
                // LARGE/COMPACT 切换共享元素：大封面 ↔ 当前播放行缩略图
                // （iOS PopupPlayer+Animations.animateArtwork）
                .playerSharedElement(SHARED_KEY_PLAYER_ARTWORK)
                .clickable(onClick = onSwitchToCompact) // iOS: artworkPressed - switch to compact view
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            swipeOffset = 0f
                        },
                        onDragEnd = {
                            if (swipeOffset < -swipeThreshold) {
                                // 左滑 → 下一曲
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayNext()
                            } else if (swipeOffset > swipeThreshold) {
                                // 右滑 → 上一曲
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayPrevious()
                            }
                            swipeOffset = 0f
                        },
                        onDragCancel = {
                            swipeOffset = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            swipeOffset += dragAmount
                        }
                    )
                },
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 歌词/可视化叠放时封面调暗至 10%（iOS: almostHideArtwork → artworkImage.alpha = 0.1）
                val artworkDimmed = displayElement == LargeDisplayElement.LYRICS && lyrics != null ||
                    displayElement == LargeDisplayElement.VISUALIZER
                // 默认图按播放项类型现画（随账户主题色，见 ui/util/DefaultArtwork.kt）
                val defaultArtwork = rememberDefaultArtworkPainter(defaultArtworkTypeFor(song))
                AsyncImage(
                    model = buildCoverArtUrl(song.coverArt, credentialsManager, musicRepository),
                    contentDescription = song.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (artworkDimmed) 0.1f else 1f },
                    contentScale = ContentScale.Crop,
                    placeholder = defaultArtwork,
                    error = defaultArtwork,
                    fallback = defaultArtwork
                )
                // 歌词视图叠放（iOS: lyricsView.frame = upperContainerView.bounds）
                if (displayElement == LargeDisplayElement.LYRICS && lyrics != null) {
                    LyricsView(
                        lyrics = lyrics,
                        isPlaying = isPlaying,
                        isLyricsSmoothScrolling = isLyricsSmoothScrolling,
                        getPositionMs = getPositionMs,
                        modifier = Modifier.fillMaxSize(),
                        // "No Lyrics" 状态全行高亮（iOS highlightAllLyrics）
                        highlightAll = lyrics.line.size == 1 && lyrics.line.first().value == "No Lyrics"
                    )
                }
                // 可视化视图叠放（iOS: visualizerHostingView 于封面之上）
                if (displayElement == LargeDisplayElement.VISUALIZER && visualizerContent != null) {
                    visualizerContent(Modifier.fillMaxSize())
                }
            }
        }

        // 封面与曲目信息之间的间隔——评分开启即居中显示 RatingView（对齐 iOS 真机）
        // iOS 切换歌词走 display(element: .lyrics)（LargeCurrentlyPlayingPlayerView）：仅调暗封面
        // （almostHideArtwork）并把 lyricsView 叠放于封面区（lyricsView.frame = upperContainerView.bounds），
        // 不触碰评分可见性——评分行独立于封面区之下，歌词/可视化/封面三态均随 Show Star Rating 常显。
        // （iOS refreshRating 里 ratingView.isHidden = (element == .lyrics) 仅在整体 refresh() 即切歌时执行，
        //  "Show Lyrics" 的切换路径不经过它，故真机切歌词态评分仍显示，Android 取此真机一致行为。）
        val showRatingHere = showRating
        if (showRatingHere) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                contentAlignment = Alignment.Center
            ) {
                RatingView(
                    rating = rating,
                    onRatingChanged = onSetRating,
                    enabled = isRatingEnabled
                )
            }
        } else {
            Spacer(modifier = Modifier.height(32.dp))
        }

        // detailsContainer: Song info with favorite and options buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Song details (with clickable labels)
            // 对应iOS: PopupPlayerVC - 点击Label导航到AlbumDetail/ArtistDetail
            Column(modifier = Modifier.weight(1f)) {
                // Title Label - 点击导航到 AlbumDetailScreen
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface, // iOS .label
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onTitleClick)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Album Label - 点击导航到 AlbumDetailScreen
                Text(
                    text = song.album,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // iOS .secondaryLabel
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onAlbumClick)
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Artist Label - 点击导航到 ArtistDetailScreen
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // iOS .secondaryLabel
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onArtistClick)
                )

                // 音频信息徽标（Detailed Information 开启时）：
                // "{bitRate}kbps · {SUFFIX} · [缓存/流播]"，数据源自 currentSong，无网络请求
                val audioBadge = buildAudioInfoBadge(bitRate, suffix)
                if (showAudioInfo && audioBadge != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = audioBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isCached) {
                                // iOS: UIImage.cache = .download ("arrow.down.circle")
                                AmperfyIcons.download        // 已缓存
                            } else {
                                // iOS: AmperfyImage.antenna（PlayerUIHandler.swift:487）
                                AmperfyIcons.antenna         // 流播
                            },
                            contentDescription = if (isCached) "Cached" else "Streaming",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 菜单状态提到 favorite/info 与 options 两枚按钮之上——播客模式下 info 按钮
            // 复用同一个当前曲 More 菜单（iOS podcast 分支的 info 按钮同样展示条目详情）
            var showSongOptionsMenu by remember { mutableStateOf(false) }

            // Right: favoriteButton (iOS: 30x30px)
            // 对应iOS: PopupPlayerVC.favoritePressed()；播客模式换 info 图标（见 isPodcastMode 说明）
            if (isPodcastMode) {
                IconButton(
                    onClick = { showSongOptionsMenu = true },
                    modifier = Modifier
                        .size(30.dp)
                        .playerSharedElement(SHARED_KEY_PLAYER_FAVORITE)
                ) {
                    Icon(
                        AmperfyIcons.info, // iOS: AmperfyImage.info ("info.circle")
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

            // Right: optionsButton (iOS: 30x30px) - Shows song-level options menu
            Box(modifier = Modifier.playerSharedElement(SHARED_KEY_PLAYER_OPTIONS)) {
                IconButton(
                    onClick = { showSongOptionsMenu = true },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        AmperfyIcons.ellipsis, // iOS: AmperfyImage.ellipsis
                        contentDescription = "Song Options",
                        // tint = .label（LargeCurrentlyPlayingPlayerView.xib
                        // Options 按钮 tintColor systemColor="labelColor"）
                        tint = MaterialTheme.colorScheme.label,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Song-level options menu (iOS: refreshOptionButton from LargeCurrentlyPlayingPlayerView)
                CurrentlyPlayingSongMenu(
                    expanded = showSongOptionsMenu,
                    onDismiss = { showSongOptionsMenu = false },
                    alignment = Alignment.BottomEnd,  // 在按钮左上方展开
                    offset = IntOffset(-32, -64),  // 稍微向上偏移
                    displayMode = PlayerDisplayMode.LARGE,
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

        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * 构建音频信息徽标文本："{bitRate}kbps · {SUFFIX}"。
 * bitRate 与 suffix 均缺失时返回 null（不显示徽标）。
 */
private fun buildAudioInfoBadge(bitRate: Int?, suffix: String?): String? {
    val parts = buildList {
        bitRate?.takeIf { it > 0 }?.let { add("${it}kbps") }
        suffix?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
