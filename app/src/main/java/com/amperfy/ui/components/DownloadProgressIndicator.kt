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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.success
import com.amperfy.ui.theme.warning

/**
 * 下载进度指示器 - 圆形饼图进度显示
 *
 * 对应iOS: 缓存图标 arrow.down.circle + 下载进度
 *
 * 四种状态:
 * 1. 已下载完成: 显示 arrow.down.circle 图标 (iOS风格: 圆圈内向下箭头)
 * 2. 正在下载(已知大小): 显示圆形进度饼图 + 百分比数字
 * 3. 正在下载(大小未知，如转码 chunked 无 Content-Length): 显示不确定态转圈
 * 4. 未下载: 不显示任何内容
 */
@Composable
fun DownloadProgressIndicator(
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,  // 0.0 ~ 1.0
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    when {
        // 已下载完成 - 显示iOS风格的arrow.down.circle图标
        isDownloaded -> {
            IOSArrowDownCircle(
                modifier = modifier.size(size),
                color = color
            )
        }
        // 正在下载(已知大小) - 显示饼图进度
        isDownloading && downloadProgress != null -> {
            CircularProgressPie(
                progress = downloadProgress,
                modifier = modifier.size(size),
                color = color
            )
        }
        // 正在下载(大小未知) - 不确定态转圈（无 progress 参数即不确定态）
        isDownloading -> {
            CircularProgressIndicator(
                modifier = modifier.size(size),
                strokeWidth = 2.dp,
                color = color
            )
        }
        // 未下载 - 不显示任何内容
        else -> {
            // 空白占位
        }
    }
}

/**
 * iOS风格的arrow.down.circle图标
 * 圆圈内有一个向下的箭头
 */
@Composable
private fun IOSArrowDownCircle(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Canvas(modifier = modifier) {
        val canvasSize = size.minDimension
        val strokeWidth = canvasSize * 0.08f
        val radius = (canvasSize - strokeWidth) / 2
        val center = Offset(size.width / 2, size.height / 2)

        // 绘制外圆
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        // 绘制向下箭头
        val arrowWidth = canvasSize * 0.35f
        val arrowHeight = canvasSize * 0.4f
        val arrowTop = center.y - arrowHeight / 2
        val arrowBottom = center.y + arrowHeight / 2
        val arrowHeadSize = canvasSize * 0.2f

        // 箭头主干（垂直线）
        drawLine(
            color = color,
            start = Offset(center.x, arrowTop),
            end = Offset(center.x, arrowBottom),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // 箭头头部（V形）
        val arrowPath = Path().apply {
            moveTo(center.x - arrowHeadSize, arrowBottom - arrowHeadSize)
            lineTo(center.x, arrowBottom)
            lineTo(center.x + arrowHeadSize, arrowBottom - arrowHeadSize)
        }
        drawPath(
            path = arrowPath,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

/**
 * 圆形饼图进度指示器
 * 显示下载进度的饼图，中心显示百分比数字
 * 圆环颜色从警告色(0%)渐变到成功色(100%)
 */
@Composable
private fun CircularProgressPie(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
) {
    val percentage = (progress * 100).toInt()

    // 进度颜色：从警告色(0%)渐变到成功色(100%)
    val progressStartColor = MaterialTheme.colorScheme.warning
    val progressEndColor = MaterialTheme.colorScheme.success
    val progressColor = lerp(progressStartColor, progressEndColor, progress)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = size.minDimension
            val strokeWidth = canvasSize * 0.12f
            val radius = (canvasSize - strokeWidth) / 2
            val centerX = size.width / 2
            val centerY = size.height / 2

            // 背景圆环
            drawCircle(
                color = backgroundColor,
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = strokeWidth)
            )

            // 进度圆弧 - 使用渐变颜色
            val sweepAngle = 360f * progress
            drawArc(
                color = progressColor,
                startAngle = -90f,  // 从顶部开始
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(
                    centerX - radius,
                    centerY - radius
                ),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 使用 Canvas 原生绘制文字，确保居中
            drawIntoCanvas { canvas ->
                val text = "$percentage%"
                val textPaint = android.graphics.Paint().apply {
                    this.color = progressColor.toArgb()
                    textSize = canvasSize * 0.32f  // 文字大小相对于圆的大小
                    textAlign = android.graphics.Paint.Align.CENTER
                    isFakeBoldText = true
                    isAntiAlias = true
                }

                // 计算文字基线位置使其垂直居中
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)
                val textY = centerY + textBounds.height() / 2f

                canvas.nativeCanvas.drawText(text, centerX, textY, textPaint)
            }
        }
    }
}
