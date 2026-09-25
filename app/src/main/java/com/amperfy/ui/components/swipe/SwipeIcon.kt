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

package com.amperfy.ui.components.swipe

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Created By Kevin Zou On 2022/12/23
 *
 * Copied from compose-swipeBox project: https://github.com/KevinnZou/compose-swipeBox
 * Integrated as source code to avoid external dependency
 *
 * Wrap the Icon into the [SwipeContent] with required size so that the icon size will
 * not change with the outside container
 *
 */
@Composable
fun RowScope.SwipeIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color = LocalContentColor.current,
    background: Color = Color.White,
    iconSize: Dp = 16.dp,
    weight: Float = 1.0f,
    onClick: () -> Unit,
) {
    SwipeContent(
        background = background,
        weight = weight,
        onClick = onClick
    ) {
        Icon(
            imageVector,
            contentDescription,
            modifier = Modifier.requiredSize(iconSize),
            tint = tint
        )
    }
}

@Composable
fun RowScope.SwipeIcon(
    background: Color = Color.White,
    painter: Painter,
    contentDescription: String?,
    iconSize: Dp = 16.dp,
    tint: Color = LocalContentColor.current,
    weight: Float = 1.0f,
    onClick: () -> Unit,
) {
    SwipeContent(
        background = background,
        weight = weight,
        onClick = onClick
    ) {
        Icon(
            painter,
            contentDescription,
            modifier = Modifier.requiredSize(iconSize),
            tint = tint
        )
    }
}

@Composable
fun RowScope.SwipeIcon(
    background: Color = Color.White,
    bitmap: ImageBitmap,
    contentDescription: String?,
    iconSize: Dp = 16.dp,
    tint: Color = LocalContentColor.current,
    weight: Float = 1.0f,
    onClick: () -> Unit,
) {
    SwipeContent(
        background = background,
        weight = weight,
        onClick = onClick
    ) {
        Icon(
            bitmap,
            contentDescription,
            modifier = Modifier.requiredSize(iconSize),
            tint = tint
        )
    }
}
