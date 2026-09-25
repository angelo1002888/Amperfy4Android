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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amperfy.data.model.Artist
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.artistPreviewInfo
import com.amperfy.ui.components.contextmenu.buildArtistContextMenuItems
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.systemRed
import com.amperfy.ui.theme.tertiaryLabel
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * 艺术家列表行 —— 对应 iOS `GenericTableCell`（GenericTableCell.xib + .swift）
 *
 * 布局对齐 xib：
 * - **行首**收藏心形容器（"Favorite Container View"：宽 16、内 heart 12×12 居中、
 *   `.systemRed`），非收藏时图标隐藏但容器**仍占位**——这样有/无收藏的行左缘对齐一致；
 *   容器右缘紧接 48pt EntityImage，xib 约束无间距 constant，故两者之间**不加 Spacer**
 * - 行尾只有 disclosure 箭头，**没有第二个心形**（iOS 从不在行尾画收藏心）
 *
 * 共用页面（iOS 两处本就是同一个 GenericTableCell）：
 * - ArtistsVC → `ArtistsScreen`
 * - GenreDetailVC 的 Artists 段 → `GenreDetailScreen`
 *
 * @param onToggleFavorite 保留给调用方的收藏动作入口；行内**不画可点的心**
 *   （iOS GenericTableCell 的心形是纯展示，收藏切换走滑动手势与上下文菜单）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistListItem(
    artist: Artist,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    hasCachedSongs: Boolean = false,
    isFullyCached: Boolean = false,
    onSwipeAction: (SwipeActionType) -> Unit = {},
    onSetRating: ((Int) -> Unit)? = null
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val settingsManager = LocalSettingsManager.current
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()

    // 索引条可见时内容 Row 的 end 内边距总值（**替代** 16dp 而非叠加；无索引条的页面为 0 → 走 16dp）。
    // 行容器与分割线保持全宽（= iOS UITableView 收窄 cell.contentView，见 [LocalListRowTrailingInset]）
    val trailingInset = LocalListRowTrailingInset.current

    // 行内封面的默认艺术图：按主题色现画（iOS ArtworkType.artist）。
    // **本实例只给行内这一个绘制目标用**——预览卡自己按类型建实例，不共享
    // （Painter 内含按尺寸缓存的绘制状态，跨目标共享会互相污染，见 EntityPreviewCard 注释）
    val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.ARTIST)

    // 长按预览菜单（对应 iOS contextMenuConfigurationForRowAt）
    var showPreviewMenu by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }

    val menuItems = buildArtistContextMenuItems(
        artist = artist,
        env = MenuEnv(
            isOfflineMode = isOfflineMode,
            isShuffleActionEnabled = isShuffleActionEnabled,
            isShowDetailedInfo = isShowDetailedInfo
        ),
        hasCachedSongs = hasCachedSongs,
        isFullyCached = isFullyCached,
        onAction = onSwipeAction,
        onSetRating = onSetRating,
        onCopyId = {
            if (artist.id.isNotEmpty()) {
                clipboardManager.setText(AnnotatedString(artist.id))
            }
        }
    )

    // 行容器全宽：横向 16dp 落到行内 padding（iOS cell 全宽高亮：layoutMargins 在 cell
    // 内部，BasicTableCell 覆盖为 (9,16,9,16)，CommonScreenOperations.swift:41-47）
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
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
                // 行底色置于点击之前：点按水波纹仍绘于其上
                .background(MaterialTheme.colorScheme.background)
                // 长按弹出预览卡片 + 上下文菜单（iOS 长按触觉由 UIKit 提供，此处手动触发）
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showPreviewMenu = true
                    }
                )
                // 索引条可见时 end 侧改用避让值替代 16
                .padding(start = 16.dp, end = maxOf(16.dp, trailingInset), top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 收藏心形容器在**行最左**（GenericTableCell.xib "Favorite Container View"：
            // 宽 16、内 heart 12×12 居中、tint .systemRed）；非收藏时只留空位不画图标，
            // 与歌曲行（PlayableTableCell）同一规格
            Box(
                modifier = Modifier.width(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (artist.isFavorite) {
                    Icon(
                        AmperfyIcons.suitHeartFill,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.systemRed,  // iOS .systemRed
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            // 心形容器右缘紧接封面，xib 约束无间距 constant → 此处不插 Spacer

            // 艺术家头像 - 圆角正方形（对齐 iOS 2.1：GenericTableCell 头像与 music.mic 占位图均为圆角方形，非圆形；4dp 与专辑行一致）
            AsyncImage(
                model = buildCoverArtUrl(artist.coverArt, credentialsManager, musicRepository),
                contentDescription = artist.name,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
                // 对齐 iOS LibraryEntityImage.refresh()：先显示生成占位图，真图加载完成才替换，UI 不等网络；
                // placeholder 覆盖加载中（含滑动回来重新发起请求期间），error/fallback 兜底失败与空模型，均避免露出底色成「白图」
                placeholder = defaultArtwork,
                error = defaultArtwork,
                fallback = defaultArtwork
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 艺术家名称和信息 - 垂直布局
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // 艺术家名称
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Albums和Songs数量（+可选艺术家总时长）- 显示在名字下方
                // 对应iOS: Artist.infoDetails short（Artist.swift:154-156，
                // Settings→Display→Artist Duration 追加 asDurationShortString）
                val isShowArtistDuration by settingsManager.isShowArtistDuration.collectAsState()
                val subtitleWithDuration = buildList {
                    artist.getSubtitle()?.let { add(it) }
                    if (isShowArtistDuration && artist.duration > 0) {
                        add(formatDurationShortString(artist.duration))
                    }
                }.joinToString(" • ").takeIf { it.isNotBlank() }
                subtitleWithDuration?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.secondaryLabel
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右箭头 - 对应 iOS accessoryType = .disclosureIndicator
            // （行尾不再画收藏心：iOS GenericTableCell 的心形只在行首容器里）
            Icon(
                AmperfyIcons.chevronRight,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.tertiaryLabel,
                modifier = Modifier.size(20.dp)
            )
        }

        // 行间分隔线**不在本组件内画**：两个宿主页（ArtistsScreen / GenreDetailScreen）
        // 都在 item 层自行发射，画在这里会重线

        // 长按弹出：预览卡片 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt）
        IOSLongPressPreviewMenu(
            expanded = showPreviewMenu,
            onDismissRequest = { showPreviewMenu = false },
            anchorBoundsOnScreen = rowBoundsOnScreen,
            items = menuItems
        ) {
            EntityPreviewCard(
                coverArtModel = buildCoverArtUrl(artist.coverArt, credentialsManager, musicRepository),
                defaultArtworkType = DefaultArtworkType.ARTIST,
                title = artist.name,
                // iOS Artist 无副标题（EntityPreviewVC subtitle 取 creatorName，艺术家为空）
                subtitle = null,
                info = artistPreviewInfo(artist, isShowDetailedInfo),
                // 对应 iOS performPreviewTransition 的 artist 分支：点卡片进艺术家详情
                showChevron = true,
                onClick = {
                    showPreviewMenu = false
                    onClick()
                }
            )
        }
    }
}
