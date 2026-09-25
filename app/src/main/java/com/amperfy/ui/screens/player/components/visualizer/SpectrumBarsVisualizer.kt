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

package com.amperfy.ui.screens.player.components.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * 经典均衡器风格频谱柱可视化（对应 iOS SpectrumBarsView）
 *
 * 几何复刻 iOS（SpectrumBarsView.swift:34-90）：
 * - 128 bin 按顺序分组平均为 [BAR_COUNT] 柱（groupSize = bin 数 / 柱数）；
 * - 柱宽按可用宽度均分（柱间距 2dp），柱高 = 幅值×高度×0.9（下限 4dp）；
 * - 圆角 = 柱宽×0.3，从下往上生长。
 *
 * 配色遵循 W7 约定「只用 MaterialTheme.colorScheme.*」——不复刻 iOS 的 hue 彩虹，
 * 改用 primary→primary(0.6) 的纵向渐变（保留自上而下渐隐的观感）。
 */
@Composable
fun SpectrumBarsVisualizer(
    magnitudes: FloatArray,
    modifier: Modifier = Modifier,
    barCount: Int = BAR_COUNT,
) {
    val topColor = MaterialTheme.colorScheme.primary
    val bottomColor = topColor.copy(alpha = 0.6f)
    Canvas(modifier = modifier) {
        val bars = groupMagnitudes(magnitudes, barCount)
        drawBars(bars, topColor, bottomColor, size)
    }
}

/** 128 bin → barCount 柱分组平均（对齐 iOS groupedMagnitudes） */
private fun groupMagnitudes(magnitudes: FloatArray, barCount: Int): FloatArray {
    if (magnitudes.isEmpty()) return FloatArray(barCount)
    val groupSize = max(1, magnitudes.size / barCount)
    return FloatArray(barCount) { i ->
        val start = i * groupSize
        if (start >= magnitudes.size) {
            0f
        } else {
            val end = min(start + groupSize, magnitudes.size)
            var sum = 0f
            for (j in start until end) sum += magnitudes[j]
            sum / max(1, end - start)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(
    bars: FloatArray,
    topColor: Color,
    bottomColor: Color,
    size: Size,
) {
    if (bars.isEmpty()) return
    val spacing = 2.dp.toPx()
    val totalSpacing = spacing * (bars.size - 1)
    val barWidth = (size.width - totalSpacing) / bars.size
    if (barWidth <= 0f) return
    val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.3f)
    val minHeight = 4.dp.toPx()

    bars.forEachIndexed { index, value ->
        val x = index * (barWidth + spacing)
        val height = max(minHeight, value * size.height * 0.9f)
        val y = size.height - height
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(topColor, bottomColor),
                startY = y,
                endY = size.height,
            ),
            topLeft = Offset(x, y),
            size = Size(barWidth, height),
            cornerRadius = cornerRadius,
        )
    }
}

/** 分组柱数（对齐 iOS AudioAnalyzerView barCount = 32） */
private const val BAR_COUNT = 32
