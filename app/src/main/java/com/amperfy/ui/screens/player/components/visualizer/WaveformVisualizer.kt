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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max

/**
 * 示波器风格波形可视化（对应 iOS WaveformView）
 *
 * 几何复刻 iOS（WaveformView.swift:34-70）：
 * - 幅值逐 bin 沿 X 轴均分，偶数 bin 向上、奇数 bin 向下（±amplitude），
 *   amplitude = mag×height×0.4；相邻点用三次贝塞尔平滑连接；
 * - 线宽随 RMS：max(2, rms×6)。
 *
 * 配色遵循 W7 约定，用 onSurface（对齐 iOS Color.primary）。
 */
@Composable
fun WaveformVisualizer(
    magnitudes: FloatArray,
    rms: Float,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier) {
        if (magnitudes.size <= 1) return@Canvas

        val lineWidth = max(2f, rms * 6f)
        val midY = size.height / 2f
        val step = size.width / (magnitudes.size - 1).toFloat()
        val ampScale = size.height * 0.4f

        fun yOf(index: Int): Float {
            val amplitude = magnitudes[index] * ampScale
            return midY + if (index % 2 == 0) amplitude else -amplitude
        }

        val path = Path()
        path.moveTo(0f, yOf(0))
        for (index in 1 until magnitudes.size) {
            val x = index * step
            val y = yOf(index)
            val prevX = (index - 1) * step
            val prevY = yOf(index - 1)
            // iOS addCurve：控制点在两点间水平居中，保持各自的 y
            path.cubicTo(prevX + step * 0.5f, prevY, x - step * 0.5f, y, x, y)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
