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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amperfy.data.model.Album
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.albumPreviewInfo
import com.amperfy.ui.components.contextmenu.buildAlbumContextMenuItems
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.systemRed
import com.amperfy.ui.theme.tertiaryLabel
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * 专辑长按弹层（预览卡 + 上下文菜单）- 对应 iOS contextMenuConfigurationForRowAt
 *
 * iOS 中 BasicTableViewController（表格行）与 BasicCollectionViewController（网格单元）
 * 提供同一份 album context menu，故 Android 三处调用方（AlbumListItem、
 * SwipeableAlbumListItem 的行、AlbumsScreen 的网格单元）共用本组件：
 * 菜单项与预览卡内容在此单点构建，调用方只负责捕获自身矩形与长按手势。
 *
 * @param anchorBoundsOnScreen 被长按的行/单元格屏幕矩形（morph 起点）
 * @param onAction 动作执行入口，一律回落调用方既有的滑动执行路径（Album.handleSwipeAction）
 * @param onSetRating 评分回调；null = 菜单不含 Rating 项
 * @param onShowArtist 跳转艺术家详情；null = 菜单不含 Show Artist（见 buildAlbumContextMenuItems）
 * @param onOpenDetail 点击预览卡进专辑详情（对应 iOS performPreviewTransition 的 album 分支）；
 *   null = 卡片不可点且不画箭头
 */
@Composable
fun AlbumLongPressPreviewMenu(
    album: Album,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBoundsOnScreen: Rect?,
    onAction: (SwipeActionType) -> Unit,
    onSetRating: ((Int) -> Unit)? = null,
    onShowArtist: (() -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
    /**
     * 该容器全部歌曲已缓存（对应 iOS isDownloadPossible 中的 isCachedCompletely）；
     * true 时菜单隐藏 Download
     */
    isFullyCached: Boolean = false
) {
    val credentialsManager = LocalCredentialsManager.current
    val mediaUrlRepository = LocalMediaUrlRepository.current
    val clipboardManager = LocalClipboardManager.current
    val settingsManager = LocalSettingsManager.current
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()

    // 缓存态取域模型 isCached（专辑既有字段；无逐曲缓存计数，已知近似）
    val hasCachedSongs = album.isCached
    val menuItems = buildAlbumContextMenuItems(
        album = album,
        env = MenuEnv(
            isOfflineMode = isOfflineMode,
            isShuffleActionEnabled = isShuffleActionEnabled,
            isShowDetailedInfo = isShowDetailedInfo
        ),
        hasCachedSongs = hasCachedSongs,
        onAction = onAction,
        onSetRating = onSetRating,
        onShowArtist = onShowArtist,
        onCopyId = {
            if (album.id.isNotEmpty()) {
                clipboardManager.setText(AnnotatedString(album.id))
            }
        },
        isFullyCached = isFullyCached
    )

    IOSLongPressPreviewMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        anchorBoundsOnScreen = anchorBoundsOnScreen,
        items = menuItems
    ) {
        EntityPreviewCard(
            coverArtModel = buildCoverArtUrl(album.coverArt, credentialsManager, mediaUrlRepository),
            // 默认艺术图按主题色现画（iOS ArtworkType.album）；只传类型，Painter 由预览卡自持
            defaultArtworkType = DefaultArtworkType.ALBUM,
            title = album.name,
            subtitle = album.artist.takeIf { it.isNotBlank() },
            info = albumPreviewInfo(album, hasCachedSongs, isShowDetailedInfo),
            showChevron = onOpenDetail != null,
            onClick = onOpenDetail?.let { open -> { onDismissRequest(); open() } }
        )
    }
}

/**
 * Album列表项的回调接口
 */
data class AlbumListItemCallbacks(
    val onClick: () -> Unit,
    val onPlay: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onInsertContextQueue: () -> Unit = {},  // Insert Context Queue
    val onAppendContextQueue: () -> Unit = {},  // Append Context Queue
    val onAddToQueueNext: () -> Unit = {},       // Insert User Queue
    val onAddToQueueLater: () -> Unit = {},      // Append User Queue
    val onToggleFavorite: () -> Unit = {},
    val onAddToPlaylist: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onDeleteCache: () -> Unit = {}
)

/**
 * 统一的专辑列表项组件 - 对应iOS: GenericTableCell
 *
 * iOS布局:
 * - 左侧: favoriteIconImage(红心) + entityImage(封面图)
 * - 中间: titleLabel(专辑名) + subtitleLabel(艺术家名) + infoLabel(歌曲数量)
 * - 右侧: disclosureIndicator(右箭头)
 *   注意：容器行(GenericTableCell)没有缓存图标——cacheIconImage 仅存在于
 *   PlayableTableCell(歌曲行)与 PodcastEpisodeTableCell(单集行)
 *
 * iOS菜单项 (EntityPreviewActionBuilder for Album):
 * - Play
 * - Shuffle
 * - Music Queue (submenu: Play Next, Play Later)
 * - Show Artist (不在艺术家详情页显示)
 * - Favorite / Unmark favorite
 * - Rating (submenu)
 * - Add to Playlist
 * - Download / Delete Cache
 *
 * 长按弹预览卡 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt，
 * BasicTableViewController 对全部行统一提供）：菜单/卡片经 AlbumLongPressPreviewMenu 单点构建；
 * onSwipeAction 为 null（未接线的调用方）时长按无响应，保底旧行为
 *
 * @param album 专辑数据
 * @param callbacks 回调函数集合
 * @param showDivider 是否显示底部分隔线
 * @param onSwipeAction 长按菜单动作执行入口（该页既有的 Album.handleSwipeAction 路径）；
 *   null = 不启用长按
 * @param onSetRating 长按菜单 Rating 调色板回调；null = 菜单不含 Rating 项
 * @param onShowArtist 长按菜单 Show Artist；null = 整项省略（艺术家详情页内的专辑行）
 *
 * 注意：CredentialsManager 和 MediaUrlRepository 通过 CompositionLocal 自动获取，无需手动传递
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumListItem(
    album: Album,
    callbacks: AlbumListItemCallbacks,
    showDivider: Boolean = true,
    onSwipeAction: ((SwipeActionType) -> Unit)? = null,
    onSetRating: ((Int) -> Unit)? = null,
    onShowArtist: (() -> Unit)? = null,
    isFullyCached: Boolean = false
) {
    // 从 CompositionLocal 获取依赖，避免参数层层传递
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val haptic = LocalHapticFeedback.current

    // 默认艺术图：按主题色现画（iOS ArtworkType.album）
    val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.ALBUM)

    // 索引条可见时内容 Row 的 end 内边距总值（**替代** 16dp 而非叠加；无索引条的页面为 0 → 走 16dp）。
    // 行容器与分割线保持全宽（= iOS UITableView 收窄 cell.contentView，见 [LocalListRowTrailingInset]）
    val trailingInset = LocalListRowTrailingInset.current

    // 长按预览菜单（对应 iOS contextMenuConfigurationForRowAt）
    var showPreviewMenu by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }
    // 未接线（onSwipeAction 为 null）时不挂长按回调：长按无响应且不宣告长按语义，保底旧行为
    val onLongPress: (() -> Unit)? = if (onSwipeAction == null) null else ({
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        showPreviewMenu = true
    })

    // 行容器全宽：横向 16dp 由行内 padding 承担（iOS cell 全宽高亮：layoutMargins
    // 在 cell 内部，BasicTableCell 覆盖为 (9,16,9,16)，CommonScreenOperations.swift:41-47）
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                // 行底色置于点击之前：点按水波纹仍绘于其上（既有观感不变）
                .background(MaterialTheme.colorScheme.background)
                // 长按弹出预览卡片 + 上下文菜单（iOS 系统长按触觉由 UIKit 提供，此处手动触发）
                .combinedClickable(
                    onClick = callbacks.onClick,
                    onLongClick = onLongPress
                )
                // 索引条可见时 end 侧改用避让值替代 16
                .padding(start = 16.dp, end = maxOf(16.dp, trailingInset), top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 收藏心形容器在行最左（GenericTableCell.xib "Favorite Container View"：
            // 宽 16、内 heart 12×12 居中、tint .systemRed），非收藏时只留空位不画图标；
            // 容器右缘紧接 48pt EntityImage，xib 约束无间距 constant → **其后不加 Spacer**
            Box(
                modifier = Modifier.width(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (album.isFavorite) {
                    Icon(
                        AmperfyIcons.suitHeartFill,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.systemRed, // iOS .systemRed - 通用红色
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // 封面图 - 对应iOS: entityImage
            AsyncImage(
                model = buildCoverArtUrl(album.coverArt, credentialsManager, musicRepository),
                contentDescription = album.name,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
                // 对齐 iOS LibraryEntityImage.refresh()：先显示生成的默认图，真图加载完才替换
                placeholder = defaultArtwork,
                error = defaultArtwork,
                fallback = defaultArtwork
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 专辑信息 - 对应iOS: GenericTableCell三行显示
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // 第一行: Album名称 - 对应iOS: titleLabel
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 第二行: Artist名称 - 对应iOS: subtitleLabel
                if (!album.artist.isNullOrBlank()) {
                    Text(
                        text = album.artist ?: "",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.secondaryLabel  // iOS .secondaryLabel - 次要文字
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                // 第三行: 歌曲数量（+可选专辑时长） - 对应iOS: infoLabel，Album.infoDetails
                // short 类型（Album.swift:184-193，Settings→Display→Album Duration 追加时长）
                val isShowAlbumDuration by LocalSettingsManager.current.isShowAlbumDuration.collectAsState()
                if (album.songCount > 0) {
                    Text(
                        text = buildList {
                            add("${album.songCount} Song${if (album.songCount != 1) "s" else ""}")
                            if (isShowAlbumDuration && album.duration > 0) {
                                add(formatDurationShortString(album.duration))
                            }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.secondaryLabel  // iOS .secondaryLabel - 次要文字
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右尖括号 - 对应iOS: accessoryType = .disclosureIndicator
            Icon(
                AmperfyIcons.chevronRight,
                contentDescription = "Navigate",
                tint = MaterialTheme.colorScheme.tertiaryLabel,  // iOS .tertiaryLabel - 第三级图标
                modifier = Modifier.size(20.dp)
            )
        }

        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
        // separatorInset = cell layoutMargins 左右值，CommonScreenOperations.swift:41-47）
        // ——与行内容左缘无关，不跟随封面
        if (showDivider) {
            HairlineDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
            )
        }

        // 长按弹出：预览卡片 + 上下文菜单（未接线时 onSwipeAction 为 null，长按不触发）
        if (onSwipeAction != null) {
            AlbumLongPressPreviewMenu(
                album = album,
                expanded = showPreviewMenu,
                onDismissRequest = { showPreviewMenu = false },
                anchorBoundsOnScreen = rowBoundsOnScreen,
                onAction = onSwipeAction,
                onSetRating = onSetRating,
                onShowArtist = onShowArtist,
                onOpenDetail = callbacks.onClick,
                isFullyCached = isFullyCached
            )
        }
    }
}
