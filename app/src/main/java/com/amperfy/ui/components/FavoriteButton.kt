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

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.redHeart

/**
 * Reusable Favorite button component
 * Used in PopupPlayer (LargeView and QueueView)
 *
 * 对应iOS: PopupPlayerVC.favoritePressed() / PopupPlayer+Visuals.swift:82-85
 *
 * 离线态（[enabled] = false，对应 iOS `!isOnlineMode`——**用户「离线模式」开关取反，
 * 不是网络连通性**）：按钮禁用且着 label 色（中性前景色）；在线态一律红心色。
 * 两种状态下图标形状都如实反映 [isFavorite]（实心/空心），不隐藏（对齐 iOS）。
 *
 * @param isFavorite Current favorite state
 * @param onClick Click handler that triggers favorite toggle
 * @param modifier Optional modifier for customization
 * @param enabled 是否可操作（在线模式为 true）
 */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(30.dp),
        enabled = enabled
    ) {
        Icon(
            // iOS: AmperfyImage.heartFill / .heartEmpty（PopupPlayer+Visuals.swift:76-106）
            imageVector = if (isFavorite) AmperfyIcons.heartFill else AmperfyIcons.heartEmpty,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (enabled) {
                // 在线：收藏与否都用红心色（iOS .redHeart，空心心同样是红色）
                MaterialTheme.colorScheme.redHeart
            } else {
                // 离线：iOS .label（中性前景色）
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(24.dp)
        )
    }
}
