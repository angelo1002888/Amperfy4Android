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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import kotlin.math.hypot

@Composable
fun IOSMusicControls(
    isPlaying: Boolean,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    // Music Player Skip Buttons（Settings→Display，对应 iOS PlayerControlView
    // skipBackward/skipForward，按钮序 [±10s后退][上一首][播放][下一首][±10s前进]）
    showSkipButtons: Boolean = false,
    isSkipEnabled: Boolean = true,
    onSkipBackward: () -> Unit = {},
    onSkipForward: () -> Unit = {}
) {
    // ========== 基准值配置 ==========
    // 修改此值可同步调整所有图标大小
    // iOS 实测比例: Play 100×112px, 双箭头 120×70px
    val playButtonHeight = 36.dp  // 👈 调整此基准值

    // 根据 iOS 比例自动计算其他尺寸
    val playButtonWidth = playButtonHeight * 0.89f      // Play 宽高比 0.89:1
    val arrowHeight = playButtonHeight * 0.625f         // 箭头高度 = Play高度 × 0.625
    val arrowWidth = arrowHeight * 1.71f                // 箭头宽高比 1.71:1
    // ================================

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val labelColor = MaterialTheme.colorScheme.label

        // Skip backward button: gobackward.10（电台时禁用，iOS isSkipAvailable）
        if (showSkipButtons) {
            IconButtonPressable(
                onClick = { if (isSkipEnabled) onSkipBackward() },
                color = if (isSkipEnabled) labelColor else labelColor.copy(alpha = 0.35f),
                modifier = Modifier.size(50.dp, 50.dp),
            ) { color ->
                Icon(
                    // iOS: AmperfyImage.skipBackward10 ("gobackward.10")
                    AmperfyIcons.skipBackward10,
                    contentDescription = "Skip Backward 10s",
                    tint = color,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        // Previous button: 双左箭头 (backward.fill)
        IconButtonPressable(
            onClick = onPreviousClick,
            color = labelColor,
            modifier = Modifier.size(66.dp, 50.dp),
        ) { color ->
            PreviousIcon(
                color = color,
                modifier = Modifier.size(arrowWidth, arrowHeight),
                cornerRadius = 5f
            )
        }

        // Play/Pause button
        IconButtonPressable(
            onClick = onPlayPauseClick,
            color = labelColor,
            modifier = Modifier.size(66.dp, 50.dp),
        ) { color ->
            if (isPlaying)
                PauseIcon(
                    color = color,
                    modifier = Modifier.size(playButtonWidth, playButtonHeight),
                    cornerRadius = 6f
                )
            else
                PlayIcon(
                    color = color,
                    modifier = Modifier.size(playButtonWidth, playButtonHeight),
                    cornerRadius = 6f
                )
        }

        // Next button: 双右箭头 (forward.fill)
        IconButtonPressable(
            onClick = onNextClick,
            color = labelColor,
            modifier = Modifier.size(66.dp, 50.dp),
        ) { color ->
            NextIcon(
                color = color,
                modifier = Modifier.size(arrowWidth, arrowHeight),
                cornerRadius = 5f
            )
        }

        // Skip forward button: goforward.10
        if (showSkipButtons) {
            IconButtonPressable(
                onClick = { if (isSkipEnabled) onSkipForward() },
                color = if (isSkipEnabled) labelColor else labelColor.copy(alpha = 0.35f),
                modifier = Modifier.size(50.dp, 50.dp),
            ) { color ->
                Icon(
                    // iOS: AmperfyImage.skipForward10 ("goforward.10")
                    AmperfyIcons.skipForward10,
                    contentDescription = "Skip Forward 10s",
                    tint = color,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

@Composable
fun IconButtonPressable(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        label = "pressScale"
    )

    val iconColor = if (pressed)
        color.copy(alpha = 0.6f)
    else
        color

    Box(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content(iconColor)
    }
}

fun roundedTriangle(
    p1: Offset,
    p2: Offset,
    p3: Offset,
    radius: Float
): Path {

    fun shrink(a: Offset, b: Offset): Pair<Offset, Offset> {
        val v = b - a
        val len = hypot(v.x, v.y).coerceAtLeast(0.001f)
        val r = radius.coerceAtMost(len / 2f)

        val nv = v / len
        return Pair(
            a + nv * r,
            b - nv * r
        )
    }

    val (p1A, p2A) = shrink(p1, p2)
    val (p2B, p3A) = shrink(p2, p3)
    val (p3B, p1B) = shrink(p3, p1)

    return Path().apply {
        moveTo(p1A.x, p1A.y)
        lineTo(p2A.x, p2A.y)
        quadraticTo(p2.x, p2.y, p2B.x, p2B.y)
        lineTo(p3A.x, p3A.y)
        quadraticTo(p3.x, p3.y, p3B.x, p3B.y)
        lineTo(p1B.x, p1B.y)
        quadraticTo(p1.x, p1.y, p1A.x, p1A.y)
        close()
    }
}

@Composable
fun PreviousIcon(
    color: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Float = 6f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val left = roundedTriangle(
            p1 = Offset(w * 0.5f, 0f),
            p2 = Offset(0f, h * 0.5f),
            p3 = Offset(w * 0.5f, h),
            radius = cornerRadius
        )

        val offset = 4f // 微调，使两个三角形紧挨着
        val right = roundedTriangle(
            p1 = Offset(w, 0f),
            p2 = Offset(w * 0.5f - offset, h * 0.5f),
            p3 = Offset(w, h),
            radius = cornerRadius
        )

        drawPath(left, color)
        drawPath(right, color)
    }
}

@Composable
fun PlayIcon(
    color: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Float = 6f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val p1 = Offset(0f, 0f)
        val p2 = Offset(w, h / 2)
        val p3 = Offset(0f, h)

        val path = roundedTriangle(
            p1, p2, p3,
            radius = cornerRadius
        )

        drawPath(path, color)
    }
}

@Composable
fun PauseIcon(
    color: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Float = 6f,
    barWidthRatio: Float = 0.28f,   // 每根竖条宽度占比
    gapRatio: Float = 0.18f        // 中间间距占比
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val barWidth = w * barWidthRatio
        val gap = w * gapRatio

        val leftX = (w - 2 * barWidth - gap) / 2f
        val rightX = leftX + barWidth + gap

        val radius = cornerRadius.coerceAtMost(barWidth / 2)

        // ✅ 左竖条
        drawRoundRect(
            color = color,
            topLeft = Offset(leftX, 0f),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(radius, radius)
        )

        // ✅ 右竖条
        drawRoundRect(
            color = color,
            topLeft = Offset(rightX, 0f),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

@Composable
fun NextIcon(
    color: Color,
    modifier: Modifier = Modifier,
    cornerRadius: Float = 6f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val offset = 4f // 微调，使两个三角形紧挨着
        val left = roundedTriangle(
            p1 = Offset(0f, 0f),
            p2 = Offset(w * 0.5f + offset, h * 0.5f),
            p3 = Offset(0f, h),
            radius = cornerRadius
        )

        val right = roundedTriangle(
            p1 = Offset(w * 0.5f, 0f),
            p2 = Offset(w, h * 0.5f),
            p3 = Offset(w * 0.5f, h),
            radius = cornerRadius
        )

        drawPath(left, color)
        drawPath(right, color)
    }
}
