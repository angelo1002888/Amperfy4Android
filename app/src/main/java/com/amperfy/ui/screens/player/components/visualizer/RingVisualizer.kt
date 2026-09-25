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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 环形径向频谱可视化（对应 iOS AmplitudeSpectrumView shapeType == .ring）
 *
 * 几何复刻 iOS（AmplitudeSpectrumView.swift:40-74）：
 * - 取前 [RING_BIN_COUNT] 个 bin（iOS `range: 0 ..< 75`）绕圆周径向排布；
 * - 内圈半径 = 边长/4，外圈 = 0.95×边长/2，条长在内外圈之间按幅值插值；
 * - 起始相位 +π/2（顶部），线宽 = 1.6π×内圈半径/条数，圆头描边；
 * - 中心 RMS 方块内切圆（loudness 圆）。
 *
 * 配色遵循 W7 约定「只用 MaterialTheme.colorScheme.*」——用 onSurface（对齐 iOS Color.primary
 * 的自适应黑/白语义），不复刻 iOS 无色版之外的花活。
 */
@Composable
fun RingVisualizer(
    magnitudes: FloatArray,
    rms: Float,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier) {
        drawRing(magnitudes, rms, color, size)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRing(
    magnitudes: FloatArray,
    rms: Float,
    color: Color,
    size: Size,
) {
    val count = min(RING_BIN_COUNT, magnitudes.size)
    if (count <= 0) return

    val center = Offset(0.5f * size.width, 0.5f * size.height)
    val sideLength = min(size.width, size.height)
    val radiusInner = sideLength / 4f
    val radiusOuter = 0.95f * (sideLength / 2f)
    val availableBarLength = radiusOuter - radiusInner
    val strokeWidth = 1.6f * Math.PI.toFloat() * radiusInner / count.toFloat()

    for (index in 0 until count) {
        val phi = (2.0 * Math.PI * index / count).toFloat() + (Math.PI.toFloat() / 2f)
        val radius2 = radiusInner + availableBarLength * magnitudes[index]
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        drawLine(
            color = color,
            start = Offset(center.x + radiusInner * cosPhi, center.y + radiusInner * sinPhi),
            end = Offset(center.x + radius2 * cosPhi, center.y + radius2 * sinPhi),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }

    // 中心 RMS 圆（iOS loudnessRect 内切圆，Fill 默认）
    if (rms > 0f) {
        drawCircle(
            color = color,
            radius = radiusInner * rms / 2f,
            center = center,
        )
    }
}

/** 环形使用的 bin 数（对齐 iOS `range: 0 ..< 75`） */
private const val RING_BIN_COUNT = 75
