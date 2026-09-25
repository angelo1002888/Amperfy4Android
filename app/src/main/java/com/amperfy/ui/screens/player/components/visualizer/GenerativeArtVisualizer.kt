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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 生成艺术粒子可视化（对应 iOS GenerativeArtView）
 *
 * 几何复刻 iOS（GenerativeArtView.swift:34-124）：
 * - bass(0-10)/mid(10-50)/treble(50-100) bin 能量驱动 5 层有机噪声环 + 30 个环绕粒子；
 * - `withFrameNanos` 驱动时间（约 30fps，对齐 iOS TimelineView minimumInterval 1/30）。
 *
 * 配色遵循 W7 约定「只用 MaterialTheme.colorScheme.*」——不复刻 iOS 的 hue 时间旋转，
 * 改用 primary/secondary/tertiary 三色循环 + 随层/时间变化的透明度（保留流动与分层观感）。
 */
@Composable
fun GenerativeArtVisualizer(
    magnitudes: FloatArray,
    rms: Float,
    modifier: Modifier = Modifier,
) {
    // withFrameNanos 自驱动时间（30fps 节流，避免每帧都重绘超过所需）
    var timeNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            timeNanos = now - start
        }
    }

    val layerColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
    )

    Canvas(modifier = modifier) {
        val time = timeNanos / 1_000_000_000.0   // 秒
        val center = Offset(size.width / 2f, size.height / 2f)

        val bass = bandEnergy(magnitudes, 0, 10, 10)
        val mid = bandEnergy(magnitudes, 10, 50, 40)
        val treble = bandEnergy(magnitudes, 50, min(magnitudes.size, 100), null)
        val rmsVal = if (rms > 0f) rms else 0.3f

        // 5 层有机噪声环
        val layers = 5
        val minSide = min(size.width, size.height)
        for (layer in 0 until layers) {
            val layerOffset = layer * 0.5
            val baseRadius = minSide * 0.15f * (1f + layer * 0.3f)
            val path = Path()
            val points = 60
            for (i in 0..points) {
                val angle = i.toDouble() / points * 2 * Math.PI
                val noise1 = sin(angle * 3 + time * 2 + layerOffset) * bass * 30
                val noise2 = cos(angle * 5 + time * 1.5) * mid * 20
                val noise3 = sin(angle * 7 + time * 3) * treble * 15
                val radius = baseRadius + (noise1 + noise2 + noise3).toFloat() + rmsVal * 20f
                val x = center.x + radius * cos(angle).toFloat()
                val y = center.y + radius * sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            val color = layerColors[layer % layerColors.size]
            val opacity = (0.3f - layer * 0.04f).coerceAtLeast(0.05f)
            drawPath(path, color = color.copy(alpha = opacity))
            drawPath(
                path,
                color = color.copy(alpha = (opacity + 0.2f).coerceAtMost(1f)),
                style = Stroke(width = 1.5f),
            )
        }

        // 30 个环绕粒子
        val particleCount = 30
        for (i in 0 until particleCount) {
            val seed = i * 1.618
            val angle = seed + time * (0.2 + bass * 0.5)
            val distance = 40.0 + sin(seed * 3 + time) * 30 * mid + i * 3 * (1 + rmsVal)
            val x = center.x + (distance * cos(angle)).toFloat()
            val y = center.y + (distance * sin(angle)).toFloat()
            val particleSize = 2f + treble * 6f
            val color = layerColors[i % layerColors.size]
            drawCircle(
                color = color.copy(alpha = 0.9f),
                radius = particleSize / 2f,
                center = Offset(x, y),
            )
        }
    }
}

/**
 * 频段能量（对齐 iOS bass/mid/trebleEnergy）：区间 [start,end) 幅值平均。
 * bin 不足时回退 0.3（iOS guard else 0.3）。[fixedDivisor] 非空时用固定除数（对齐 iOS 硬编码 10/40）。
 */
private fun bandEnergy(magnitudes: FloatArray, start: Int, end: Int, fixedDivisor: Int?): Float {
    if (magnitudes.size <= end - 1 && fixedDivisor != null) return 0.3f
    if (start >= end || start >= magnitudes.size) return 0.3f
    val safeEnd = min(end, magnitudes.size)
    var sum = 0f
    for (i in start until safeEnd) sum += magnitudes[i]
    val divisor = fixedDivisor ?: (safeEnd - start)
    return if (divisor <= 0) 0.3f else sum / divisor
}
