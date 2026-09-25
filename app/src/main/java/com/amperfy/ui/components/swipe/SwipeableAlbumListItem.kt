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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amperfy.data.model.Album
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.AlbumLongPressPreviewMenu
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.theme.*
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * iOS风格的滑动专辑列表项
 *
 * 对应 iOS: AlbumsVC + BasicTableViewController swipe actions
 *
 * 滑动机制委托给通用的 [SwipeableItem]，本组件仅负责专辑行布局。
 * 动作列表由调用方传入（来自 SettingsManager.swipeActionSettings 并经
 * SwipeDisplaySettings 过滤）。
 */
@Composable
fun SwipeableAlbumListItem(
    album: Album,
    onClick: () -> Unit,
    swipeController: SwipeController,
    leadingActions: List<SwipeActionType>,
    trailingActions: List<SwipeActionType>,
    onSwipeAction: (SwipeActionType) -> Unit,
    showDivider: Boolean = true,
    // 长按上下文菜单的评分回调（对应 iOS createRatingMenu）；null = 菜单不含 Rating 项
    onSetRating: ((Int) -> Unit)? = null,
    // 长按上下文菜单 Show Artist（对应 iOS configureFor(album:) isShowArtist）；null = 整项省略
    onShowArtist: (() -> Unit)? = null,
    // 该专辑全部歌曲已缓存（对应 iOS isCachedCompletely）：菜单隐藏 Download
    isFullyCached: Boolean = false,
    modifier: Modifier = Modifier
) {
    SwipeableItem(
        key = album.id,
        swipeController = swipeController,
        leadingActions = leadingActions,
        trailingActions = trailingActions,
        onSwipeAction = onSwipeAction,
        isFavorite = album.isFavorite,
        modifier = modifier
    ) {
        AlbumRowContent(
            album = album,
            onClick = onClick,
            showDivider = showDivider,
            onSwipeAction = onSwipeAction,
            isFullyCached = isFullyCached,
            onSetRating = onSetRating,
            onShowArtist = onShowArtist
        )
    }
}

/**
 * 专辑行布局（收藏图标 + 封面 + 信息 + 箭头）
 *
 * 对齐 iOS GenericTableCell：容器行不显示缓存图标（cacheIconImage 仅歌曲/单集行有）
 *
 * 长按弹预览卡 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt，
 * BasicTableViewController 对全部行统一提供）；菜单项与卡片经共用的
 * AlbumLongPressPreviewMenu 单点构建（与 AlbumListItem、Albums 网格单元同源），
 * 动作复用滑动执行路径 onSwipeAction，无第二份实现
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumRowContent(
    album: Album,
    onClick: () -> Unit,
    showDivider: Boolean,
    onSwipeAction: (SwipeActionType) -> Unit = {},
    isFullyCached: Boolean = false,
    onSetRating: ((Int) -> Unit)? = null,
    onShowArtist: (() -> Unit)? = null
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val colorScheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    // 长按预览菜单（对应 iOS contextMenuConfigurationForRowAt）
    var showPreviewMenu by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }

    // 索引条可见时内容 Row 的 end 内边距总值（**替代** 16dp 而非叠加；无索引条的页面为 0 → 走 16dp）。
    // 行容器与分割线保持全宽（= iOS UITableView 收窄 cell.contentView，
    // 见 com.amperfy.ui.components.LocalListRowTrailingInset）
    val trailingInset = com.amperfy.ui.components.LocalListRowTrailingInset.current

    val itemHeight = 68.dp
    val horizontalPadding = 16.dp
    // 心形容器 16 / 心 12：GenericTableCell.xib "Favorite Container View" 规格，
    // 与 AlbumListItem / SongListItem / ArtistListItem 同一套；
    // 容器右缘紧接 48pt EntityImage，xib 约束无间距 constant → **不设 favoriteSpacing**
    val favoriteWidth = 16.dp
    val favoriteIconSize = 12.dp
    val coverSize = 48.dp
    val coverSpacing = 12.dp
    val arrowWidth = 20.dp

    // 行容器全宽：横向 16dp 落到行内 padding（iOS cell 全宽高亮：layoutMargins 在 cell
    // 内部，BasicTableCell 覆盖为 (9,16,9,16)，CommonScreenOperations.swift:41-47）；
    // 外层底色必须铺满全宽，滑动手势露出层才被行底色完整遮盖
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = itemHeight)
                .onGloballyPositioned { coordinates ->
                    // 屏幕坐标（Popup 窗口原点与 App 窗口可能不一致，窗口坐标会错位）
                    val position = coordinates.positionOnScreen()
                    rowBoundsOnScreen = Rect(
                        left = position.x,
                        top = position.y,
                        right = position.x + coordinates.size.width,
                        bottom = position.y + coordinates.size.height
                    )
                }
                // 行底色置于点击之前：点按水波纹仍绘于其上
                .background(MaterialTheme.colorScheme.background)
                // 长按弹出预览卡片 + 上下文菜单（iOS 系统长按触觉由 UIKit 提供，此处手动触发）
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showPreviewMenu = true
                    }
                )
                // 索引条可见时 end 侧改用避让值替代 16
                .padding(
                    start = horizontalPadding,
                    end = maxOf(horizontalPadding, trailingInset),
                    top = 10.dp,
                    bottom = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 收藏心形容器（行最左，非收藏时只留空位不画图标）
            Box(
                modifier = Modifier.width(favoriteWidth),
                contentAlignment = Alignment.Center
            ) {
                if (album.isFavorite) {
                    Icon(
                        AmperfyIcons.suitHeartFill,
                        contentDescription = "Favorite",
                        tint = colorScheme.systemRed,  // iOS .systemRed
                        modifier = Modifier.size(favoriteIconSize)
                    )
                }
            }
            // 心形容器右缘紧接封面，xib 约束无间距 constant → 此处不插 Spacer

            // 封面图（默认图按主题色现画，iOS ArtworkType.album）
            val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.ALBUM)
            AsyncImage(
                model = buildCoverArtUrl(album.coverArt, credentialsManager, musicRepository),
                contentDescription = album.name,
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
                placeholder = defaultArtwork,
                error = defaultArtwork,
                fallback = defaultArtwork
            )

            Spacer(modifier = Modifier.width(coverSpacing))

            // 专辑信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!album.artist.isNullOrBlank()) {
                    Text(
                        text = album.artist ?: "",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            color = colorScheme.secondaryLabel
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (album.songCount > 0) {
                    Text(
                        text = "${album.songCount} Song${if (album.songCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            color = colorScheme.secondaryLabel
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右箭头
            Icon(
                AmperfyIcons.chevronRight,
                contentDescription = "Navigate",
                tint = colorScheme.tertiaryLabel,
                modifier = Modifier.size(arrowWidth)
            )
        }

        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
        // separatorInset = cell layoutMargins 左右值，CommonScreenOperations.swift:41-47）
        if (showDivider) {
            HairlineDivider(
                modifier = Modifier.padding(start = horizontalPadding),
                color = colorScheme.separator
            )
        }

        // 长按弹出：预览卡片 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt）；
        // 菜单项与卡片由共用的 AlbumLongPressPreviewMenu 单点构建
        AlbumLongPressPreviewMenu(
            album = album,
            expanded = showPreviewMenu,
            onDismissRequest = { showPreviewMenu = false },
            anchorBoundsOnScreen = rowBoundsOnScreen,
            onAction = onSwipeAction,
            onSetRating = onSetRating,
            onShowArtist = onShowArtist,
            // 对应 iOS performPreviewTransition 的 album 分支：点卡片进专辑详情
            onOpenDetail = onClick,
            isFullyCached = isFullyCached
        )
    }
}
