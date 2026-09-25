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

import androidx.compose.animation.core.*
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amperfy.data.download.DownloadManager
import com.amperfy.data.model.Song
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildSongContextMenuItems
import com.amperfy.ui.components.contextmenu.songPreviewInfo
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.imageOverlay
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.systemRed
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * 歌曲列表项样式 - 对应iOS: PlayableTableCellStyle
 *
 * iOS中有两种样式:
 * - trackNumber: 显示曲目编号，用于专辑详情页 (isDislayAlbumTrackNumberStyle = true)
 * - artwork: 显示专辑封面，用于其他列表 (isDislayAlbumTrackNumberStyle = false)
 */
enum class SongListItemStyle {
    TRACK_NUMBER,  // 显示曲目编号 - 用于专辑详情页
    ARTWORK        // 显示专辑封面 - 用于收藏歌曲、搜索结果等
}

/**
 * 歌曲列表项的回调接口
 */
data class SongListItemCallbacks(
    val onClick: () -> Unit,
    /**
     * Shuffle - 对应 iOS createPlayShuffledAction()：以该行所在列表为上下文乱序播放。
     * 此前误接 onClick（等同 Play），2026-08-01 修正为独立回调
     */
    val onShuffle: () -> Unit = {},
    val onToggleFavorite: () -> Unit = {},
    val onSetRating: (Int) -> Unit = {},
    val onInsertContextQueue: () -> Unit = {},  // Insert Context Queue
    val onAppendContextQueue: () -> Unit = {},  // Append Context Queue
    val onAddToQueueNext: () -> Unit = {},       // Insert User Queue
    val onAddToQueueLater: () -> Unit = {},      // Append User Queue
    val onShowAlbum: () -> Unit = {},
    val onShowArtist: () -> Unit = {},
    val onAddToPlaylist: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onDeleteCache: () -> Unit = {}
)

/**
 * 统一的歌曲列表项组件 - 对应iOS: PlayableTableCell
 *
 * iOS布局:
 * - 左侧: favoriteIconImage(红心) + trackNumberLabel/entityImage(序号或封面) + titleLabel/artistLabel
 * - 右侧: cacheIconImage(下载图标) + durationLabel(时长,可选) + optionsButton(更多菜单)
 *
 * iOS菜单项 (EntityPreviewActionBuilder for Song):
 * - Play
 * - Shuffle (可选)
 * - Music Queue (submenu: Play Next, Play Later)
 * - Show Album (可选,不在专辑详情页显示)
 * - Show Artist
 * - Favorite / Unmark favorite
 * - Rating (submenu)
 * - Add to Playlist
 * - Download / Delete Cache
 *
 * @param song 歌曲数据
 * @param style 显示样式 (TRACK_NUMBER 或 ARTWORK)
 * @param trackNumber 曲目编号 (仅在 TRACK_NUMBER 样式时使用)
 * @param isPlaying 是否正在播放
 * @param showShuffleInMenu 是否在菜单中显示 Shuffle 选项
 * @param showAlbumInMenu 是否在菜单中显示 Show Album 选项 (专辑详情页不显示)
 * @param downloadProgressMap 下载进度映射
 * @param trailingContent 右侧附件槽位（对应 iOS cell 的 accessoryView），画在 More（⋯）**右侧**、
 *   行最右端；与 More 的显隐彼此独立（见 [isLongPressMenuEnabled]）
 * @param backgroundColor 行底色。默认 `colorScheme.background`（主界面列表），
 *   弹层内的调用点须传入所在 sheet 的底色（iOS 的 cell backgroundColor 恒等于
 *   tableView.backgroundColor，模态里即 elevated 提升层色），否则行块会在弹层底色上凸显
 * @param isLongPressMenuEnabled 是否处于「普通浏览态」——同时决定**长按预览+上下文菜单**与
 *   **More（⋯）按钮及其星级浮层**的显隐（iOS 两者同源于
 *   `isDisplayOptionButton = (playContextCb != nil) && (playerIndexCb == nil)`，
 *   PlayableTableCell.swift:408-412）。默认 `trailingContent == null`：
 *   右侧附件通常意味着编辑/勾选态，而 iOS 在 displayMode != .normal 时把行手势整体关闭
 *   （PlayableTableCell.swift:264-271 单击/hover/双击 isEnabled = displayMode == .normal），
 *   且 PlaylistEditVC / PlaylistAddLibraryVC 均未设 containableAtIndexPathCallback → 无 contextMenu。
 *   **例外需显式传 true**：Downloads 页的状态附件行在 iOS 仍是 .normal 模式
 *   （accessoryView 走 download 分支 :356-372），DownloadsVC 经
 *   BasicTableViewController.containableAtIndexPathCallback 照常提供 contextMenu。
 *   单击回调（勾选/播放）任何情况下都不受本开关影响。
 * @param showDivider 是否画行内底部的行间分隔线（16dp inset）。默认 true；
 *   **仅所在 section 的最后一行传 false**——iOS grouped 表尾行的 separator 就是那条
 *   全宽的 section 底边界线（由页面在列表末尾发射），两条线不叠放
 * @param callbacks 回调函数集合
 *
 * 注意：CredentialsManager 和 MediaUrlRepository 通过 CompositionLocal 自动获取，无需手动传递
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    song: Song,
    style: SongListItemStyle,
    trackNumber: Int = 0,
    isPlaying: Boolean = false,
    showShuffleInMenu: Boolean = true,
    showAlbumInMenu: Boolean = true,
    showArtistInMenu: Boolean = true,
    downloadProgressMap: Map<String, DownloadManager.DownloadProgress> = emptyMap(),
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    isLongPressMenuEnabled: Boolean = trailingContent == null,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    showDivider: Boolean = true,
    callbacks: SongListItemCallbacks
) {
    // 从 CompositionLocal 获取依赖，避免参数层层传递
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val haptic = LocalHapticFeedback.current

    // 索引条可见时内容 Row 的 end 内边距总值（**替代** 16dp 而非叠加；无索引条的页面为 0 → 走 16dp）。
    // 行容器与分割线保持全宽（= iOS UITableView 收窄 cell.contentView，见 [LocalListRowTrailingInset]）
    val trailingInset = LocalListRowTrailingInset.current

    // 行内封面的默认艺术图：按主题色现画（iOS ArtworkType.song）。
    // **本实例只给行内这一个绘制目标用**——预览卡自己按类型建实例，不共享
    // （Painter 内含按尺寸缓存的绘制状态，跨目标共享会互相污染，见 EntityPreviewCard 注释）
    val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.SONG)

    // Detailed Information（Settings→Display）：预览信息追加 Bitrate/MIME/ID，
    // 菜单追加 Copy ID to Clipboard（对应 iOS EntityPreviewVC.swift:161-163、755-763）
    val isShowDetailedInfo by LocalSettingsManager.current.isShowDetailedInfo.collectAsState()
    // Disable Player Shuffle Button（Settings→Display→Shuffle）：Shuffle 菜单项显示但禁用
    // 对应 iOS EntityPreviewVC 各 shuffle action 的 .disabled attributes
    val isShuffleActionEnabled by LocalSettingsManager.current
        .isPlayerShuffleButtonEnabled.collectAsState()
    // 离线模式（Settings→Player→Offline Mode）：抹掉 Favorite/Rating/Add to Playlist/Download，
    // 且未缓存歌曲的 Play/Shuffle/Music Queue 整体消失（对应 iOS configureFor(song:) 门控）
    val isOfflineMode by LocalSettingsManager.current.isOfflineMode.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val onCopyId: () -> Unit = {
        if (song.id.isNotEmpty()) {
            clipboardManager.setText(AnnotatedString(song.id))
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    // 长按预览菜单（对应 iOS contextMenuConfigurationForRowAt）
    var showPreviewMenu by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }

    // 获取当前歌曲的下载进度
    val downloadProgress = downloadProgressMap[song.id]
    val isDownloading = downloadProgress?.isDownloading == true
    val isDownloadCompleted = downloadProgress?.isCompleted == true
    val progress = downloadProgress?.progress

    // 判断是否已缓存：数据库标记 或 下载刚完成
    val isCached = song.isCached || isDownloadCompleted

    // 菜单项单点构建：More 下拉与长按预览菜单共用同一份（对齐 iOS 同一 EntityPreviewActionBuilder）
    val menuItems = buildSongContextMenuItems(
        song = song,
        env = MenuEnv(
            isOfflineMode = isOfflineMode,
            isShuffleActionEnabled = isShuffleActionEnabled,
            isShowDetailedInfo = isShowDetailedInfo
        ),
        isCached = isCached,
        isDownloading = isDownloading,
        showShuffle = showShuffleInMenu,
        showAlbum = showAlbumInMenu,
        showArtist = showArtistInMenu,
        onCopyId = onCopyId,
        callbacks = callbacks
    )

    // 行容器全宽：横向 16dp 由行内 padding 承担（iOS cell 全宽高亮：layoutMargins
    // 在 cell 内部，BasicTableCell 统一覆盖为 (9,16,9,16)，CommonScreenOperations.swift:41-47）
    // ——点按水波纹、长按预览快照的采集矩形因此与 iOS cell 一样贯通屏幕两侧
    Column(modifier = modifier.fillMaxWidth()) {
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
                .background(backgroundColor)
                // 长按弹出预览+菜单（iOS 系统长按触觉由 UIKit 提供，此处手动触发）；
                // 关闭时（编辑/勾选态，见 isLongPressMenuEnabled）传 null——非 null 的
                // onLongClick 会 consumeUntilUp 吞掉后续事件，传 null 才能让长按彻底不参与
                // （也不会干扰行内拖拽手柄）
                .combinedClickable(
                    onClick = callbacks.onClick,
                    onLongClick = if (isLongPressMenuEnabled) {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showPreviewMenu = true
                        }
                    } else null
                )
                // 行高 = 内容 48 + 上下各 9，横向 16（对应 iOS PlayableTableCell.rowHeight
                // = 48 + margin.top + margin.bottom，margin 为 defaultMarginCell
                // 的 defaultMarginCellY = 9 / defaultMarginCellX = 16，
                // CommonScreenOperations.swift:41-47）；索引条可见时 end 侧改用避让值替代 16
                .padding(start = 16.dp, end = maxOf(16.dp, trailingInset), top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Favorite icon - 对应iOS: favoriteIconImage
            // 心形容器宽 16、图标 12 居中（PlayableTableCell.xib "Favorite Container View"
            // ZTB-B7-OZR width=16，内 heart 12×12 centerX/centerY 居中）；
            // 容器右缘紧接封面/曲目号，无额外间隙（xib 约束 trackNumber.left = favContainer.right）
            Box(
                modifier = Modifier.width(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (song.isFavorite) {
                    Icon(
                        AmperfyIcons.suitHeartFill,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.systemRed, // iOS .systemRed - 通用红色
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // Track number or artwork - 对应iOS: trackNumberLabel 或 entityImage
            when (style) {
                SongListItemStyle.TRACK_NUMBER -> {
                    // 曲目编号或播放指示器
                    Box(
                        modifier = Modifier.width(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPlaying) {
                            PlayingIndicator()
                        }
                        else {
                            Text(
                                text = "${song.track ?: trackNumber}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.secondaryLabel  // iOS .secondaryLabel - 次要文字
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                SongListItemStyle.ARTWORK -> {
                    // 专辑封面
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = buildCoverArtUrl(song.coverArt, credentialsManager, musicRepository),
                            contentDescription = song.title,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Crop,
                            // 对齐 iOS LibraryEntityImage.refresh()：先显示生成的默认图，真图加载完才替换
                            placeholder = defaultArtwork,
                            error = defaultArtwork,
                            fallback = defaultArtwork
                        )
                        // 播放指示器覆盖在封面上
                        if (isPlaying) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.imageOverlay, // iOS ImageOverlayBackground
                                        RoundedCornerShape(6.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                PlayingIndicator(barColor = MaterialTheme.colorScheme.surface) // iOS白色
                            }
                        }
                    }
                    // 封面右缘 → 文本起点 6dp：iOS 标题容器 x=82（封面 32+48=80 后留 2）
                    // 且容器内竖栈 leading 再内缩 4（xib mUX-sN-oa8 / 约束 jYK-RP-Dp9），
                    // 合计文本绝对起点 86 = 前导 16 + 心形 16 + 封面 48 + 6
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            // Song info - 对应iOS: titleLabel + artistLabel
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    // 17sp 对应 iOS titleLabel system 17pt（PlayableTableCell.xib hPr-VQ-rTt）
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 副标题恒为艺术家（对应 iOS artistLabel.text = playable.creatorName，
                // PlayableTableCell.swift:326——两种 style 都不拼接专辑名）
                val subtitle = song.artist ?: ""

                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Cache icon / Download progress - 对应iOS: cacheIconImage + downloadProgress
            // 使用 DownloadProgressIndicator 显示:
            // 1. 已下载: arrow.down.circle 图标
            // 2. 下载中: 圆形饼图进度 + 百分比
            // 3. 未下载: 不显示
            if (isCached || isDownloading) {
                DownloadProgressIndicator(
                    isDownloaded = isCached,
                    isDownloading = isDownloading,
                    downloadProgress = progress,
                    modifier = Modifier,
                    // 15dp 对应 iOS compact 尺寸类 cacheIconWidth = 15
                    // （PlayableTableCell.swift:403；xib Cache Icon 高宽约束 15）
                    size = 15.dp,
                    color = MaterialTheme.colorScheme.secondaryLabel  // iOS .secondaryLabel - 次要图标
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // 歌曲时长 - 对应iOS: durationLabel（Settings→Display→Song Duration，
            // PlayableTableCell.swift:326-330/386，asColonDurationString）
            val isShowSongDuration by LocalSettingsManager.current.isShowSongDuration.collectAsState()
            if (isShowSongDuration && song.duration > 0) {
                Text(
                    text = formatSongDuration(song.duration),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.secondaryLabel,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            // 右侧区域布局对齐 iOS：optionsButton（⋯）是 contentView 内的控件、
            // accessoryView（勾选/加号/三横线/下载状态）挂在 cell 最右，
            // 故 **⋯ 在左、附件在右**，二者可同时出现（Downloads 页即此形态）。
            //
            // ⋯ 的显隐**不由附件决定**——iOS 判据是
            // `isDisplayOptionButton = (playContextCb != nil) && (playerIndexCb == nil)`
            // （PlayableTableCell.swift:408/412），与 displayMode / accessoryView 无关：
            // - PlaylistEditVC 传 `playContextCb: nil`（PlaylistEditVC.swift:247-253）→ 隐藏
            // - DownloadsVC 传 playContextCb 且无 playerIndexCb（DownloadsVC.swift:122-127）→ **显示**
            // Android 侧该判据与 [isLongPressMenuEnabled] 同源（iOS 同一「普通浏览态」条件
            // 同时决定 optionsButton 与 contextMenu），故直接复用它当开关。
            // 编辑态既隐藏 ⋯ 也不画星级（星排是 optionsButton 的附属浮层）。
            if (isLongPressMenuEnabled) {
                // More options button - 对应iOS: optionsButton
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            AmperfyIcons.ellipsis,
                            contentDescription = "More options",
                            // 常态 tint = .label（PlayableTableCell.swift:589）；
                            // :575 那支主题色是 Mac/iPad 指针 hover 态，触摸端不适用
                            tint = MaterialTheme.colorScheme.label
                        )
                    }

                    // 星级评分浮层 - 对应iOS: ratingStackView（Settings→Display→Show Star Rating，
                    // 显隐入口 PlayableTableCell.swift:381-384）
                    // 布局对应 iOS 约束（:152-183 setupRatingStars）：星排叠在 optionsButton 下方，
                    // trailing = optionsButton.trailing - 4、top = optionsButton.bottom - 8。
                    //
                    // **横向移植的是「星排与省略号点阵右对齐」的视觉关系，而非 -4 这个字面量**：
                    // iOS 的 optionsButton 在 PlayableTableCell.xib 中宽仅 30pt（id SRF-Yt-kwz），
                    // ellipsis 字形宽约 19-20pt 居中 → 字形右缘距按钮右缘约 5pt，
                    // 故 -4 的净效果是星排右缘与点阵右缘几乎重合（±1pt，与 iOS 实机观察一致）。
                    // Android 侧度量不同：IconButton 40dp、MoreHoriz 视口 24dp 居中（视口右缘
                    // 内缩 8dp）、点阵右缘再内缩 4dp（半径 2、圆心 cx=6/12/18，点阵右缘在视口 x=20）
                    // → 可见点阵右缘 = 按钮右缘 - 12dp，故取 x = -12dp（8dp 视口内缩 + 4dp 点阵内缩）。
                    //
                    // 纵向沿用 iOS「星底 = 按钮底 + 2」规则（两端按钮高度差异对该规则无影响）：
                    // 星高 10dp，BottomEnd 对齐使星底 = 按钮底，再下移 2dp 即得
                    // top = 按钮底 - 8（与按钮底部重叠 8dp、向下伸出 2dp；
                    // Compose 默认不裁剪子项，溢出正常绘制）。
                    // 只画 rating 颗实心星（不画空星底）、右对齐，rating == 0 整栈隐藏
                    // ——对应 :186-196 updateRatingDisplay 隐藏前 5-N 颗（栈内隐藏项塌缩）
                    val isShowRating by LocalSettingsManager.current
                        .isPlayerRatingDisplayed.collectAsState()
                    val starCount = (song.rating ?: 0).coerceIn(0, 5)
                    if (isShowRating && starCount > 0) {
                        // matchParentSize：星排不参与本 Box 的尺寸测量，等价 iOS 中星排是
                        // contentView 的兄弟视图、只锚定 optionsButton 而不影响其位置
                        // （否则 5 星宽 5*10-4*2=42dp > 按钮 40dp，会把 More 按钮整体推左 2dp，
                        // 导致同一列表内不同评分行的 More 按钮错位）；
                        // wrapContentWidth(unbounded)：星排按内容真实宽度测量后右对齐，
                        // 允许超出按钮宽度向左溢出绘制
                        Box(
                            modifier = Modifier.matchParentSize(),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            Row(
                                modifier = Modifier
                                    .offset(x = (-12).dp, y = 2.dp)
                                    .wrapContentWidth(
                                        align = Alignment.End,
                                        unbounded = true
                                    ),
                                horizontalArrangement = Arrangement.spacedBy((-2).dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(starCount) {
                                    Icon(
                                        AmperfyIcons.starFill,
                                        contentDescription = null,
                                        // iOS 此处硬编码 #E1AF41（PlayableTableCell.swift:166
                                        // UIColor(red: 0.882, green: 0.686, blue: 0.255)），
                                        // **不是** Utilities 的 gold #F1C242，故不走 colorScheme.gold
                                        tint = Color(0xFFE1AF41),
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    // iOS风格的Context Menu - 对应iOS: EntityPreviewActionBuilder.createMenuActions()
                    SongContextMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        items = menuItems
                    )
                }
            }

            // accessoryView 位（cell 最右）：编辑态的勾选/加号/三横线，或 Downloads 页的
            // 下载状态附件（PlayableTableCell.swift:339-372）
            if (trailingContent != null) {
                trailingContent()
            }
        }
        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
        // separatorInset = cell layoutMargins 的左右值，iOS 全仓未改 separatorInset，
        // BasicTableCell 覆盖 layoutMargins 为 (9,16,9,16)，CommonScreenOperations.swift:41-47）
        // ——与行内容左缘无关，不跟随封面/文字。
        // section 尾行（showDivider=false）不画：那一条由页面在列表末尾发射的
        // 全宽 section 底边界线承担（iOS grouped 表尾行 separator 即段边界，不叠两条）
        if (showDivider) {
            HairlineDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.separator  // iOS .separator - 透明分割线
            )
        }

        // 长按弹出：预览卡片 + 上下文菜单
        // 对应 iOS contextMenuConfigurationForRowAt（previewProvider: EntityPreviewVC + actionProvider）
        // 编辑/选择态整体不挂载（isLongPressMenuEnabled=false 时 showPreviewMenu 永不置真，
        // 此处一并短路，避免多余 Popup 组合）
        IOSLongPressPreviewMenu(
            expanded = showPreviewMenu && isLongPressMenuEnabled,
            onDismissRequest = { showPreviewMenu = false },
            anchorBoundsOnScreen = rowBoundsOnScreen,
            items = menuItems
        ) {
            // 歌曲在专辑详情页中时不可导航（iOS isNavigationDisallowed：隐藏 chevron、点卡片无跳转）
            val canNavigate = showAlbumInMenu && song.albumId != null
            EntityPreviewCard(
                coverArtModel = buildCoverArtUrl(song.coverArt, credentialsManager, musicRepository),
                defaultArtworkType = DefaultArtworkType.SONG,
                title = song.title,
                subtitle = song.artist,
                info = songPreviewInfo(song, isShowDetailedInfo),
                showChevron = canNavigate,
                onClick = if (canNavigate) {
                    {
                        // 对应 iOS willPerformPreviewAction → performPreviewTransition（song → AlbumDetail）
                        showPreviewMenu = false
                        callbacks.onShowAlbum()
                    }
                } else {
                    null
                }
            )
        }
    }
}

// iOS asColonDurationString：超 1 小时显示 "1:02:33"
// （集中构建器 songPreviewInfo 亦复用，故非 private）
fun formatSongDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}

/**
 * 歌曲的上下文菜单 - 对应iOS: EntityPreviewActionBuilder for Song
 */
@Composable
private fun SongContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<IOSContextMenuItem>
) {
    IOSStyleContextMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        alignment = Alignment.TopEnd,
        offset = IntOffset(-72, 48),
        items = items
    )
}

/**
 * 播放指示器动画 - 对应iOS: PlayIndicator
 * 显示3个动态高度变化的柱状图
 */
@Composable
fun PlayingIndicator(
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "playing")

    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(18.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(bar1Height.dp)
                .background(barColor, RoundedCornerShape(1.dp))
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(bar2Height.dp)
                .background(barColor, RoundedCornerShape(1.dp))
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(bar3Height.dp)
                .background(barColor, RoundedCornerShape(1.dp))
        )
    }
}
