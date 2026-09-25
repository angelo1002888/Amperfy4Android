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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Created By Kevin Zou On 2022/12/24
 *
 * Copied from compose-swipeBox project: https://github.com/KevinnZou/compose-swipeBox
 * Integrated as source code to avoid external dependency
 *
 * Wrap the real content into a box with weight set and align the content at center
 *
 * @param background the background color of the box
 * @param weight the weight of the box
 * @param onClick the action to be executed when the box is clicked
 * @param content the real content of the box
 */
@Composable
fun RowScope.SwipeContent(
    background: Color = Color.White,
    weight: Float = 1f,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    require(weight > 0.0) { "invalid weight $weight; It must be greater than zero" }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .weight(weight)
            .background(background)
            .clickable {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
