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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 跑马灯单行文本 - 对应 iOS MarqueeLabel.applyAmperfyStyle()
 * （UtilitiesExtensions.swift:29-48）
 *
 * iOS 参数复刻：
 * - type = .continuous（连续循环滚动）
 * - animationDelay = 2.0s（首次与每轮循环前延迟）
 * - speed = 30pt/s
 * - trailingBuffer = 30pt（循环首尾间隔）
 * - fadeLength = 10pt（两端渐变淡出，仅文字溢出滚动时）
 *
 * 文字未溢出时表现为普通单行文本（对应 MarqueeLabel 不滚动时 tailTruncation）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fadeLength: Dp = 10.dp
) {
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var textWidthPx by remember { mutableIntStateOf(0) }
    val isOverflowing = containerWidthPx > 0 && textWidthPx > containerWidthPx

    Box(
        modifier = modifier
            .onSizeChanged { containerWidthPx = it.width }
            .then(
                if (isOverflowing) {
                    Modifier
                        // 离屏合成，DstIn 渐变只作用于本节点内容
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fadePx = fadeLength.toPx().coerceAtMost(size.width / 2f)
                            // 左端渐入
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black,
                                    startX = 0f,
                                    endX = fadePx
                                ),
                                size = Size(fadePx, size.height),
                                blendMode = BlendMode.DstIn
                            )
                            // 右端渐出
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Black,
                                    1f to Color.Transparent,
                                    startX = size.width - fadePx,
                                    endX = size.width
                                ),
                                topLeft = Offset(size.width - fadePx, 0f),
                                size = Size(fadePx, size.height),
                                blendMode = BlendMode.DstIn
                            )
                        }
                } else {
                    Modifier
                }
            )
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { textWidthPx = it.size.width },
            modifier = Modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                animationMode = MarqueeAnimationMode.Immediately,
                repeatDelayMillis = 2000,
                initialDelayMillis = 2000,
                spacing = MarqueeSpacing(30.dp),
                velocity = 30.dp
            )
        )
    }
}
