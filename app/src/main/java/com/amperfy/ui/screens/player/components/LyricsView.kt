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

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amperfy.data.model.StructuredLyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * LyricsView - 播放器歌词视图（Phase 6.2）
 * 对应 iOS: Amperfy/Screens/View/Lyrics/LyricsView.swift + LyricTableCell.swift
 *
 * - 行渲染：20sp，非活动行灰色常规、活动行 label 色加粗，居中、最多 5 行
 *   （LyricTableCell.swift:55-74,126-129）
 * - 仅 synced 歌词滚动/高亮（LyricsView.swift:168）；unsynced 静态展示
 * - 活动行 = start >= 当前时间的下一行的前一行（LyricsView.swift:171,191）；
 *   播放过最后一行后滚到最后一行并清除高亮
 * - 上下边缘渐隐遮罩（iOS CAGradientLayer mask，透明区 0-0.2 / 0.8-1.0）
 * - 上下 contentPadding = 高度一半，使活动行居中（LyricsView.swift:152-163）
 * - 平滑/跳跃滚动由 isLyricsSmoothScrolling 设置驱动（display(scrollAnimation:)）
 * - 行不可点击（iOS shouldHighlightRowAt = false，无点行跳转）
 * - iOS 用 10Hz AVPlayer 时间观察器驱动；Android 以 250ms 轮询 getPositionMs
 * - highlightAll：对应 iOS highlightAllLyrics()（"No Lyrics" 状态全行高亮）
 */
@Composable
fun LyricsView(
    lyrics: StructuredLyrics,
    isPlaying: Boolean,
    isLyricsSmoothScrolling: Boolean,
    getPositionMs: () -> Long,
    modifier: Modifier = Modifier,
    highlightAll: Boolean = false
) {
    val listState = rememberLazyListState()
    var activeIndex by remember(lyrics) { mutableIntStateOf(-1) }
    var lastLineDisplayedOnce by remember(lyrics) { mutableStateOf(false) }

    // 同步歌词的活动行驱动（对应 iOS scroll(toTime:)）
    LaunchedEffect(lyrics, isPlaying, isLyricsSmoothScrolling) {
        if (!lyrics.synced || lyrics.line.isEmpty()) return@LaunchedEffect
        while (isActive) {
            val posMs = getPositionMs()
            val nextIndex = lyrics.line.indexOfFirst { it.startTimeMs >= posMs }
            when {
                // 已播过全部行：滚到最后一行一次并清除高亮（LyricsView.swift:171-187）
                nextIndex == -1 -> {
                    if (!lastLineDisplayedOnce) {
                        lastLineDisplayedOnce = true
                        activeIndex = -1
                        if (isLyricsSmoothScrolling) {
                            listState.animateScrollToItem(lyrics.line.lastIndex)
                        } else {
                            listState.scrollToItem(lyrics.line.lastIndex)
                        }
                    }
                }
                else -> {
                    lastLineDisplayedOnce = false
                    val current = nextIndex - 1
                    if (current != activeIndex) {
                        activeIndex = current
                        if (current >= 0) {
                            if (isLyricsSmoothScrolling) {
                                listState.animateScrollToItem(current)
                            } else {
                                listState.scrollToItem(current)
                            }
                        }
                    }
                }
            }
            delay(250)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val halfHeight = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // 上下边缘渐隐（对应 iOS CAGradientLayer mask，LyricsView.swift:61-76）
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.2f to Color.Black,
                            0.8f to Color.Black,
                            1.0f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            // 活动行居中（对应 iOS contentInset top/bottom = height/2）
            contentPadding = PaddingValues(top = halfHeight, bottom = halfHeight)
        ) {
            itemsIndexed(lyrics.line) { index, line ->
                val isActiveLine = highlightAll || (lyrics.synced && index == activeIndex)
                Text(
                    text = line.value,
                    fontSize = 20.sp,
                    fontWeight = if (isActiveLine) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActiveLine) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}
