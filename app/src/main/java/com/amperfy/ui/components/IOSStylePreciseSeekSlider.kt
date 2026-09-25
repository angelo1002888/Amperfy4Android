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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.gray3
import com.amperfy.ui.theme.label

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun IOSStylePreciseSeekSlider(
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,

    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 6.dp,
    thumbRadiusDragging: Dp = 10.dp,

    // ✅ ✅ ✅ 终极关键参数：用来抵消 Material 内部的“隐藏上移”
    thumbCenterFineTune: Dp = 1.dp   // 👈 你现在这个情况用 1.dp 正好
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val safeDuration = duration.takeIf { it > 0 } ?: 1L

    val uiValue = if (isDragging) {
        dragValue
    } else if (duration <= 0) {
        // duration 未知（切歌未就绪 / 电台直播流）：圆点停在左端
        // 对应 iOS PlayerControlView.refreshTimeInfo：maximumValue = duration，
        // duration 为 0 时 UISlider 停在 minimum；此前 safeDuration=1ms 会把
        // fraction 钳到 1.0，导致小圆点跳到最右侧
        0f
    } else {
        (currentPosition.toFloat() / safeDuration).coerceIn(0f, 1f)
    }

    val thumbSize by animateDpAsState(
        targetValue = if (isDragging)
            thumbRadiusDragging * 2
        else
            thumbRadius * 2,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "thumbSize"
    )

    val thumbElevation by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 2.dp,
        animationSpec = tween(120),
        label = "thumbElevation"
    )

    val sliderHeight = thumbRadiusDragging * 2

    Slider(
        value = uiValue,
        onValueChange = { value ->
            isDragging = true
            dragValue = value
        },
        onValueChangeFinished = {
            val finalPosition = (dragValue * safeDuration).toLong()
            isDragging = false
            onSeek(finalPosition)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(sliderHeight),

        thumb = {
            Box(
                Modifier
                    .offset(y = thumbCenterFineTune)   // ✅ 终极像素补偿
                    .size(thumbSize)
                    .shadow(thumbElevation, CircleShape)
                    .background(MaterialTheme.colorScheme.label, CircleShape)
            )
        },

        track = { sliderPositions ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(sliderHeight),
                contentAlignment = Alignment.Center
            ) {
                // 灰色底轨
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(trackHeight)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.gray3)
                )

                // 蓝色进度轨
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