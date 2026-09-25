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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.gold
import androidx.compose.runtime.collectAsState

/**
 * 五星评分组件（对应 iOS Screens/View/RatingView.swift）
 *
 * 交互（对齐 iOS RatingView）：
 * - 点第 n 星 → 设为 n；再点当前评分对应的星 → 清零；长按任意星 → 清零。
 * - 触发触感反馈（点击 light、长按 medium，遵循 isHapticsEnabled 设置）。
 * - 星色用 [gold]（项目既有约定，红心/星色统一）。
 * - 禁用态（[enabled] = false，如离线）：不响应交互，填充星透明度 0.3、空星 0.05（对齐 iOS）。
 */
@Composable
fun RatingView(
    rating: Int,
    onRatingChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    starSize: Int = 25,
    starSpacing: Int = 4,
) {
    val haptic = LocalHapticFeedback.current
    val isHapticsEnabled by LocalSettingsManager.current.isHapticsEnabled.collectAsState()
    val starColor = MaterialTheme.colorScheme.gold

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(starSpacing.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (star in 1..STAR_COUNT) {
            val isFilled = star <= rating
            // 禁用态透明度对齐 iOS（填充 0.3 / 空 0.05）；启用态全不透明
            val starAlpha = if (enabled) 1f else if (isFilled) 0.3f else 0.05f
            Icon(
                imageVector = if (isFilled) AmperfyIcons.starFill else AmperfyIcons.starEmpty,
                contentDescription = "Rating $star",
                tint = starColor,
                modifier = Modifier
                    .size(starSize.dp)
                    .alpha(starAlpha)
                    .pointerInput(enabled, rating) {
                        if (!enabled) return@pointerInput
                        detectTapGestures(
                            onTap = {
                                // 点当前评分星 = 清零，否则设为该星（对齐 iOS starTapped）
                                val newRating = if (star == rating) 0 else star
                                if (isHapticsEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                onRatingChanged(newRating)
                            },
                            onLongPress = {
                                // 长按任意星 = 清零（对齐 iOS starLongPressed）
                                if (isHapticsEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                onRatingChanged(0)
                            },
                        )
                    },
            )
        }
    }
}

private const val STAR_COUNT = 5
