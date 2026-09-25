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

package com.amperfy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import coil.compose.AsyncImage
import com.amperfy.data.model.Playable
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.defaultArtworkTypeFor
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * iOS-style MiniPlayer component
 *
 * Ported from iOS: Amperfy/Screens/Player/MiniPlayerView.swift (configureForiOS)
 *
 * Key features matching iOS:
 * - Height: 48dp (iOS: 48pt)
 * - Max width: 600dp on tablets (iOS: 600pt)
 * - Rounded corners: 24dp（48dp 高全圆角胶囊，与悬浮 tab bar 同款玻璃观感）
 * - Glass effect background: surface 0.92 alpha + 8dp shadow
 * - Progress slider at bottom (3dp height, 6dp side padding)
 * - Title/artist 居中对齐（对齐 iOS MarqueeLabel textAlignment .center）
 * - Displays "No music playing" when song is null (iOS behavior)
 * - Play/Pause + Next 各 30dp（对齐 iOS 30pt）
 * - [showNextButton] = false 时 Next 按钮收起不占位
 *   （对齐 iOS refreshForTabAccessoryTraitChange：inline/最小化态 nextButton 隐藏、play 贴 trailing）
 *
 * Layout (iOS compact mode):
 * [Artwork] [Title/Artist(center)] [PlayButton] [NextButton]
 *           [Progress Slider - 3dp height at bottom]
 *
 * @param startPadding 内容左侧留白（默认 20dp，替换旧的 screenWidth-20 逻辑）
 * @param endPadding 内容右侧留白（默认 20dp；最小化态由外部加大以让出小圆钮空间）
 * @param showNextButton 是否显示 Next 按钮（inline/最小化态传 false 收起）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IOSMiniPlayer(
    song: Playable?,  // Nullable to support "No music playing" state
    isPlaying: Boolean,
    currentPosition: Long = 0L,
    duration: Long = 0L,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onClick: () -> Unit,
    startPadding: Dp = 20.dp,
    endPadding: Dp = 20.dp,
    showNextButton: Boolean = true,
    modifier: Modifier = Modifier
) {
    // 使用CompositionLocal获取依赖
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    // 按钮颜色
    val labelColor = MaterialTheme.colorScheme.label
    val disabledColor = labelColor.copy(alpha = 0.38f)

    // MiniPlayer作为独立浮动组件，外部Box完全透明；内部胶囊按 start/end padding 定位
    // 宽屏（>600dp）限 600dp 居中，手机端 fillMaxWidth 并按 start/end 留白
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp) // 明确设置高度
            .background(Color.Transparent), // 透明背景，不遮挡下层内容
        contentAlignment = Alignment.Center
    ) {
        // iOS-style glass effect（48dp 全圆角胶囊 + 8dp 阴影 + surface 0.92 玻璃背景）
        Box(
            modifier = (
                if (screenWidth > 600.dp) Modifier.width(600.dp)
                else Modifier.fillMaxWidth().padding(start = startPadding, end = endPadding)
                )
                .height(48.dp) // iOS: 48pt height
                .shadow(8.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .clickable(onClick = onClick)
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Main content row：在完整 48dp 内垂直居中（进度条改为底部叠放，不再挤占内容行高度）
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Album artwork - iOS: square, adaptive size
                    Card(
                        modifier = Modifier
                            .size(32.dp), // iOS: 固定尺寸
                        shape = RoundedCornerShape(4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        // 默认图按播放项类型分派（电台/播客单集/歌曲），空态取歌曲图；
                        // 按主题色现画（iOS UIImage.generateArtwork）
                        val defaultArtwork = rememberDefaultArtworkPainter(
                            song?.let { defaultArtworkTypeFor(it) } ?: DefaultArtworkType.SONG
                        )
                        AsyncImage(
                            model = song?.coverArt?.let { buildCoverArtUrl(it, credentialsManager, musicRepository) },
                            contentDescription = song?.title ?: "No music playing",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = defaultArtwork,
                            error = defaultArtwork,
                            fallback = defaultArtwork
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp)) // iOS: 8pt spacing

                    // Song info (title and artist) or "No music playing"
                    // 居中对齐（对齐 iOS MarqueeLabel textAlignment .center）
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (song != null) {
                            // Title - iOS: 11pt font on compact devices
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Artist - iOS: 13pt font
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp
                                ),
                                color = MaterialTheme.colorScheme.secondaryLabel,  // iOS .secondaryLabel - 次要文字
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // iOS: Display "No music playing" when currentlyPlaying is null
                            Text(
                                text = "No music playing",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Play/Pause button - iOS: 30x30pt, using IconButtonPressable
                    // 对应 iOS: MiniPlayerView.playButton
                    if (song != null) {
                        IconButtonPressable(
                            onClick = onPlayPauseClick,
                            color = labelColor,
                            modifier = Modifier.size(30.dp)
                        ) { color ->
                            if (isPlaying) {
                                PauseIcon(
                                    color = color,
                                    modifier = Modifier.size(18.dp),
                                    cornerRadius = 4f
                                )
                            } else {
                                PlayIcon(
                                    color = color,
                                    modifier = Modifier.size(18.dp),
                                    cornerRadius = 4f
                                )
                            }
                        }
                    } else {
                        // Disabled state
                        Box(
                            modifier = Modifier.size(30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            PlayIcon(
                                color = disabledColor,
                                modifier = Modifier.size(18.dp),
                                cornerRadius = 4f
                            )
                        }
                    }

                    // Next button - iOS: 30x30pt, using IconButtonPressable with NextIcon
                    // 对应 iOS: MiniPlayerView.nextButton (forward.fill)
                    // showNextButton=false 时横向收起（对齐 iOS refreshForTabAccessoryTraitChange：
                    // inline/最小化态 nextButton.isHidden = true、playButton 贴 trailing）
                    AnimatedVisibility(
                        visible = showNextButton,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        if (song != null) {
                            IconButtonPressable(
                                onClick = onNextClick,
                                color = labelColor,
                                modifier = Modifier.size(30.dp)
                            ) { color ->
                                NextIcon(
                                    color = color,
                                    modifier = Modifier.size(18.dp),  // Same size as PlayIcon
                                    cornerRadius = 4f
                                )
                            }
                        } else {
                            // Disabled state
                            Box(
                                modifier = Modifier.size(30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                NextIcon(
                                    color = disabledColor,
                                    modifier = Modifier.size(18.dp),  // Same size as PlayIcon
                                    cornerRadius = 4f
                                )
                            }
                        }
                    }
                }

                // Progress bar - iOS: 3pt height, 6pt padding on sides（叠放于胶囊底边）
                // iOS MiniPlayer: timeSlider.isEnabled = false (display-only, no seeking)
                // 对应 iOS: MiniPlayerView line 376: style != .miniPlayeriOS
                // Only show when song exists and has duration
                if (song != null && duration > 0) {
                    val progress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp)
                            .height(3.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(1.5.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = progress)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    shape = RoundedCornerShape(1.5.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Repeat mode enum
 * Equivalent to iOS: RepeatMode
 */
enum class RepeatMode {
    OFF,    // No repeat
    ALL,    // Repeat all
    ONE     // Repeat one
}
