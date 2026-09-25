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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.AmperfyIcons

/**
 * iOS 风格的统一导航栏组件
 *
 * 对应 iOS Amperfy 的导航栏设计模式
 *
 * 主要特性:
 * - 左侧: 返回箭头 + 上级页面标题文字 (iOS 风格)
 * - 中间: 可选标题 (支持滚动时显示/隐藏)
 * - 右侧: 自定义操作按钮
 * - 使用 iOS 系统颜色 (primary 蓝色用于导航元素)
 *
 * 使用场景:
 * - 列表页面 (Artists, Albums, Playlists, Songs 等)
 * - 详情页面 (ArtistDetail, AlbumDetail 等)
 * - 设置页面 (Settings 及其子页面)
 *
 * @param onBackClick 返回按钮点击回调
 * @param backTitle 返回按钮旁显示的上级页面标题 (如 "Library", "Albums", "Settings")
 * @param showBackText 是否显示返回按钮旁的文字 (默认 true，iOS 风格总是显示)
 * @param title 中间标题文字 (可选，null 则不显示)
 * @param showTitle 是否显示中间标题 (默认 true，可用于滚动时动态控制)
 * @param centered 是否使用居中布局 (默认 true，使用 CenterAlignedTopAppBar)
 * @param actions 右侧操作按钮区域的内容
 * @param backgroundColor 导航栏背景色 (默认使用主题 background 色)
 * @param contentColor 导航栏内容颜色 (默认使用主题 primary 色 - iOS 蓝色)
 *
 * @example 基本用法 - 列表页面带折叠标题
 * ```kotlin
 * IOSNavTopBar(
 *     onBackClick = { navController.popBackStack() },
 *     backTitle = "Library",
 *     title = if (showCollapsedTitle) "Artists" else null,
 *     actions = {
 *         IconButton(onClick = { /* More menu */ }) {
 *             Icon(AmperfyIcons.ellipsis, contentDescription = "More")
 *         }
 *     }
 * )
 * ```
 *
 * @example 详情页面 - 不显示中间标题
 * ```kotlin
 * IOSNavTopBar(
 *     onBackClick = onBackClick,
 *     backTitle = "Albums",
 *     title = null,  // 详情页通常不显示标题
 *     actions = {
 *         IconButton(onClick = { showMoreMenu = true }) {
 *             Icon(AmperfyIcons.ellipsis, contentDescription = "More")
 *         }
 *     }
 * )
 * ```
 *
 * @example 设置页面 - 固定标题（iOS 设置子页为 .inline 居中标题，使用默认 centered = true）
 * ```kotlin
 * IOSNavTopBar(
 *     onBackClick = onBackClick,
 *     backTitle = "Settings",
 *     title = "Server",  // 居中显示（对齐 iOS navigationBarTitleDisplayMode(.inline)）
 *     backgroundColor = MaterialTheme.colorScheme.surface
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IOSNavTopBar(
    onBackClick: () -> Unit,
    backTitle: String = "Back",
    showBackText: Boolean = true,
    title: String? = null,
    showTitle: Boolean = true,
    centered: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    // iOS 风格的返回按钮组件
    val navigationIcon: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onBackClick)
        ) {
            // iOS 返回按钮为 chevron.backward（细箭头 "<"），非 Material 默认的 "←"
            Icon(
                imageVector = AmperfyIcons.chevronLeft,
                contentDescription = "Back",
                tint = contentColor,
                modifier = Modifier.padding(start = 4.dp)
            )
            if (showBackText) {
                // 长上级页名（如目录浏览的父目录名）单行截断并限宽，
                // 近似 iOS 返回按钮空间不足时的自动降级，避免挤压居中标题/actions
                Text(
                    text = backTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .widthIn(max = 160.dp)
                )
            }
        }
    }

    // 中间标题组件（单行截断，对齐 iOS 导航标题 ellipsis；长目录名/流派名不换行撑高）
    val titleContent: @Composable () -> Unit = {
        if (title != null && showTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    // 根据 centered 参数选择使用居中或左对齐的 TopAppBar
    if (centered) {
        CenterAlignedTopAppBar(
            title = titleContent,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = backgroundColor,
                navigationIconContentColor = contentColor,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = contentColor
            )
        )
    } else {
        TopAppBar(
            title = titleContent,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = backgroundColor,
                navigationIconContentColor = contentColor,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = contentColor
            )
        )
    }
}
