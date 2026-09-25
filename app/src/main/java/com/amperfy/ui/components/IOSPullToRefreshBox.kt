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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.secondaryLabel
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * iOS `UIRefreshControl` 风格的下拉刷新容器。
 *
 * 对齐依据：iOS Amperfy 2.1 全部列表页均使用系统默认 `UIRefreshControl()`，无任何自定义样式。
 * 因此本组件用经典 iOS「菊花」指示器整体替换 Material3 默认下拉刷新观感，交互复刻 iOS。
 * 关键数值取自社区对 UIRefreshControl 的实测（非源码，故提为常量便于真机微调）：
 *
 * 1. 拉动阶段：列表内容跟随手指整体下移（[graphicsLayer] translationY = distanceFraction ×
 *    [TRIGGER_THRESHOLD]，触发行程 ≈130pt），菊花在露出空隙中「逐根显影」——12 根辐条从约
 *    [SPINNER_APPEAR_FRACTION]（20%）行程起依次点亮，恰在 100% 行程（触发点）全亮，整体不旋转。
 * 2. 刷新中：内容停留在 [REFRESHING_GAP]（≈64pt，独立于触发行程）空隙，菊花原位持续旋转，
 *    旋转以「辐条透明度梯度」呈现拖尾（最亮辐条绕环步进，后随渐暗尾巴），非匀质自转。
 * 3. 结束：位移动画将内容回弹到 0，菊花随空隙收合而淡出。
 *
 * 实现：`Box` 自挂 `Modifier.pullToRefresh(threshold = TRIGGER_THRESHOLD, ...)`（M3 1.3 API），
 * 内容位移与刷新空隙解耦——拖动阶段用 snap 保持跟手，刷新/回弹用 spring 平滑过渡；spring 可能
 * 过冲为负，消费处（translationY / 指示器 offset / 空隙计算）一律 coerceAtLeast 0（前车之鉴：
 * IOSStyleContextMenu 曾因 spring 过冲负 padding 崩溃）。对外签名不变，各调用页零改动。
 *
 * 说明：内容 [graphicsLayer] 位移不改变布局测量（尺寸不变），字母索引等叠层若在 content 内会
 * 一并下移——与 iOS 整体位移一致，属预期。各页 LazyColumn 已挂的 SearchBarReveal / BottomBarState
 * nestedScroll 监听与本容器的 pullToRefresh 经嵌套滚动语义串行/并存，互不干扰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IOSPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    val daisyColor = MaterialTheme.colorScheme.secondaryLabel

    // 内容位移目标：拖动中跟手（fraction × 触发行程），刷新稳定后停留在刷新空隙（64dp，独立于触发行程）
    val targetOffset = if (isRefreshing) REFRESHING_GAP else TRIGGER_THRESHOLD * state.distanceFraction
    // 拖动中用 snap 保持跟手，刷新/回弹用 spring 平滑过渡
    val animatedOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = if (isRefreshing) spring() else snap(),
        label = "ptr-offset"
    )
    // spring 回弹可能过冲为负 → 一律钳制不为负，避免负位移/负 padding
    val safeOffset = animatedOffset.coerceAtLeast(0.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = state,
                threshold = TRIGGER_THRESHOLD,
                onRefresh = onRefresh
            )
    ) {
        // 内容整体跟随手指下移；刷新中停在 REFRESHING_GAP 空隙下方
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = safeOffset.toPx() }
        ) {
            content()
        }
        // 菊花指示器叠放在露出的空隙中，竖直居中
        IOSDaisyIndicator(
            isRefreshing = isRefreshing,
            distanceFraction = state.distanceFraction,
            thresholdOffset = {
                // 指示器竖直居中于露出的空隙：(空隙高度 - 指示器高度) / 2，钳制不为负
                val gapPx = safeOffset.toPx()
                val y = ((gapPx - INDICATOR_SIZE.toPx()) / 2f).coerceAtLeast(0f)
                IntOffset(0, y.toInt())
            },
            color = daisyColor,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/** 触发行程（UIRefreshControl 实测 ≈130pt）：拖动到此距离触发刷新，菊花恰全亮 */
private val TRIGGER_THRESHOLD = 130.dp

/** 刷新中内容停留空隙（实测 ≈64pt，独立于触发行程） */
private val REFRESHING_GAP = 64.dp

/** 菊花指示器整体尺寸（辐条外接方框） */
private val INDICATOR_SIZE = 28.dp

/** 辐条根数（12 根，对齐 iOS 系统菊花） */
private const val SPOKE_COUNT = 12

/** 拉动阶段：菊花从此行程比例起逐根显影（实测约 20% 行程开始出现） */
private const val SPINNER_APPEAR_FRACTION = 0.2f

/** 刷新旋转一圈的时长（毫秒），12 档离散步进以贴近 iOS 机械观感 */
private const val ROTATION_PERIOD_MS = 1000

/**
 * 经典 iOS「菊花」指示器：12 根圆头短辐条环形排布，单色系统灰，无背景无阴影。
 *
 * @param thresholdOffset 由外层根据下拉进度计算的竖直像素偏移（居中于空隙）。
 */
@Composable
private fun IOSDaisyIndicator(
    isRefreshing: Boolean,
    distanceFraction: Float,
    thresholdOffset: androidx.compose.ui.unit.Density.() -> IntOffset,
    color: Color,
    modifier: Modifier = Modifier
) {
    // 刷新中：驱动一个 0..SPOKE_COUNT 的连续量，取整得到「当前最亮辐条」位置，每圈约 1s
    val infinite = rememberInfiniteTransition(label = "daisy")
    val head by infinite.animateFloat(
        initialValue = 0f,
        targetValue = SPOKE_COUNT.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ROTATION_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "daisy-head"
    )

    Box(
        modifier = modifier
            .offset(thresholdOffset)
            .size(INDICATOR_SIZE)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = size.minDimension
            val innerR = s * 0.28f
            val outerR = s * 0.44f
            val strokeW = s * 0.075f
            val cx = size.width / 2f
            val cy = size.height / 2f

            // 拉动阶段小于显影起点时完全不绘制
            if (!isRefreshing && distanceFraction < SPINNER_APPEAR_FRACTION) return@Canvas

            // 拉动阶段：已点亮辐条数 = 进度 × 12，边界辐条按小数渐显（逐根显影）
            val litCount = (distanceFraction * SPOKE_COUNT).coerceIn(0f, SPOKE_COUNT.toFloat())
            val headIndex = floor(head).toInt() % SPOKE_COUNT

            for (i in 0 until SPOKE_COUNT) {
                val alpha = if (isRefreshing) {
                    // 环形距离「最亮位置」越远越暗：head=1.0 → 尾部=0.25 的线性拖尾
                    val d = ((headIndex - i) % SPOKE_COUNT + SPOKE_COUNT) % SPOKE_COUNT
                    0.25f + 0.75f * (1f - d.toFloat() / (SPOKE_COUNT - 1))
                } else {
                    // 从 12 点方向顺时针依次点亮
                    (litCount - i).coerceIn(0f, 1f)
                }
                if (alpha <= 0f) continue

                // 辐条 i：12 点方向为起点，顺时针每 30° 一根
                val angle = Math.toRadians((i * (360.0 / SPOKE_COUNT)) - 90.0)
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()
                drawLine(
                    color = color.copy(alpha = color.alpha * alpha),
                    start = Offset(cx + innerR * cosA, cy + innerR * sinA),
                    end = Offset(cx + outerR * cosA, cy + outerR * sinA),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
