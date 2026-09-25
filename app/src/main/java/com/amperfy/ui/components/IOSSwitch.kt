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

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.gray3
import com.amperfy.ui.theme.gray5
import com.amperfy.ui.theme.switchThumb
import com.amperfy.ui.theme.systemGreen

/**
 * iOS风格的Switch控件
 *
 * 特点：
 * - 圆形滑块，带有阴影
 * - 开启时：绿色背景（iOS标准绿色），白色滑块在右侧
 * - 关闭时：浅灰色背景，白色滑块在左侧
 * - 平滑的动画过渡
 *
 * @param checked 是否选中
 * @param onCheckedChange 状态改变回调
 * @param modifier 修饰符
 * @param enabled 是否启用
 */
@Composable
fun IOSSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // iOS Switch的标准颜色 - 使用扩展颜色,确保深色模式正确
    // 对应 iOS: .systemGreen (checked) / .systemGray5 (unchecked)
    // Light: #34C759 / #E5E5EA
    // Dark:  #32D74B / #2C2C2E
    val checkedTrackColor = MaterialTheme.colorScheme.systemGreen // iOS .systemGreen (响应式)
    val uncheckedTrackColor = MaterialTheme.colorScheme.gray3 // iOS .systemGray3 (响应式)
    val thumbColor = MaterialTheme.colorScheme.switchThumb // iOS Toggle thumb - 纯白色 (Light & Dark 一致)

    // Switch尺寸（接近iOS标准）
    val switchWidth = 51.dp
    val switchHeight = 31.dp
    val thumbSize = 27.dp
    val thumbPadding = 2.dp

    // 动画
    val animationDuration = 200
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) switchWidth - thumbSize - thumbPadding else thumbPadding,
        animationSpec = tween(durationMillis = animationDuration),
        label = "thumbOffset"
    )

    val trackAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        animationSpec = tween(durationMillis = animationDuration),
        label = "trackAlpha"
    )

    Box(
        modifier = modifier
            .width(switchWidth)
            .height(switchHeight)
            .clip(RoundedCornerShape(switchHeight / 2))
            .background(
                if (checked) checkedTrackColor.copy(alpha = trackAlpha)
                else uncheckedTrackColor.copy(alpha = trackAlpha)
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onCheckedChange?.invoke(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 滑块（Thumb）
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .shadow(
                    elevation = 3.dp,
                    shape = CircleShape,
                    clip = false
                )
                .background(
                    color = thumbColor.copy(alpha = if (enabled) 1f else 0.8f),
                    shape = CircleShape
                )
        )
    }
}
