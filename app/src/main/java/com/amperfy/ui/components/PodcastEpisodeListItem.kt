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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.rememberDefaultArtworkPainter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播客单集行 - 对应 iOS: PodcastEpisodeTableCell（高行：标题/日期/描述/时长+状态）
 *
 * - info 行 = 发布日期（iOS publishDate.asShortDayMonthString）
 * - 描述（depiction）最多两行
 * - 时长行：总时长；不可用时追加 " · Deleted on server"/" · Server syncing"
 *   （iOS :100-102 仅在 !isAvailableToUser 时追加 userStatus 描述）
 * - 不可用单集整行降透明度并显示禁止图标（iOS playEpisodeButton .ban 禁用）
 * - 缓存态/下载进度（Batch 4）：与 SongListItem 同源的 [DownloadProgressIndicator]
 *   （已缓存 = arrow.down.circle，下载中 = 饼图进度），对应 iOS PlayableTableCell cacheIconImage
 * - Android 无单集剩余时长显示（iOS remainingTimeInSec/playProgressPercent），已知简化
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PodcastEpisodeListItem(
    episode: PodcastEpisode,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMenuClick: (() -> Unit)? = null,
    showPodcastTitle: Boolean = false,
    /** 长按弹预览卡 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt）；null = 不响应长按 */
    onLongClick: (() -> Unit)? = null,
    /** 行的屏幕矩形回调（长按弹层 morph 起点）；null = 调用方不需要 */
    onRowBoundsChanged: ((Rect) -> Unit)? = null,
    /** 下载进度映射（Batch 4，行内进度环；键为单集 id） */
    downloadProgressMap: Map<String, com.amperfy.data.download.DownloadManager.DownloadProgress> = emptyMap(),
    /**
     * 右侧附件（对应 iOS cell 的 accessoryView，如 Downloads 页的下载状态）：
     * 画在 More（⋯）**右侧**、行最右端；与 More 的显隐彼此独立，同 SongListItem.trailingContent
     */
    trailingContent: (@Composable () -> Unit)? = null
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val haptic = LocalHapticFeedback.current
    val contentAlpha = if (episode.isAvailableToUser) 1f else 0.5f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                // 屏幕坐标（Popup 窗口原点与 App 窗口可能不一致，窗口坐标会错位）
                val position = coordinates.positionOnScreen()
                onRowBoundsChanged?.invoke(
                    Rect(
                        left = position.x,
                        top = position.y,
                        right = position.x + coordinates.size.width,
                        bottom = position.y + coordinates.size.height
                    )
                )
            }
            // 行底色：长按 morph 时行矩形据此着色（同 SongListItem）
            .background(MaterialTheme.colorScheme.background)
            // 不可用单集点击禁用，但长按菜单仍可用（Delete on Server / 描述等）
            .combinedClickable(
                onClick = { if (episode.isAvailableToUser) onClick() },
                onLongClick = if (onLongClick == null) null else ({
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                })
            )
            // 行内边距 9dp 与歌曲行同一套度量（iOS 单集同由 PlayableTableCell 渲染：
            // rowHeight = 48 + margin.top + margin.bottom，defaultMarginCellY = 9，
            // PlayableTableCell.swift:86 + CommonScreenOperations.swift:41-48）
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 封面（iOS entityImage）——默认图按主题色现画（见 ui/util/DefaultArtwork.kt），
        // 类型 = iOS ArtworkType.podcastEpisode（AbstractPlayable.swift:395-396）
        val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.PODCAST_EPISODE)
        AsyncImage(
            model = buildCoverArtUrl(episode.coverArt, credentialsManager, musicRepository),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
            alpha = contentAlpha,
            placeholder = defaultArtwork,
            error = defaultArtwork,
            fallback = defaultArtwork
        )

        // 封面右缘 → 文本起点 6dp（同 SongListItem：iOS 标题容器留 2 + 栈内缩 4，
        // PlayableTableCell.xib mUX-sN-oa8 / 约束 jYK-RP-Dp9）
        Spacer(modifier = Modifier.width(6.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title,
                // 17sp 对应 iOS titleLabel system 17pt（PlayableTableCell.xib hPr-VQ-rTt）
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            // info 行：发布日期（Episodes 模式下前置播客名）
            val dateText = formatPublishDate(episode.publishDate)
            val infoText = when {
                showPodcastTitle && dateText != null -> "${episode.podcastTitle} · $dateText"
                showPodcastTitle -> episode.podcastTitle
                else -> dateText
            }
            if (!infoText.isNullOrEmpty()) {
                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 描述（iOS depiction，可空）
            if (!episode.depiction.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = episode.depiction,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 时长 + 不可用状态（iOS playProgressLabel）
            Spacer(modifier = Modifier.height(2.dp))
            val durationText = formatEpisodeDuration(episode.duration)
            val statusSuffix = episode.unavailableDescription?.let { " · $it" } ?: ""
            Text(
                text = durationText + statusSuffix,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 缓存图标 / 下载进度（Batch 4，与 SongListItem 同源；iOS cacheIconImage + downloadProgress）
        val downloadProgress = downloadProgressMap[episode.id]
        val isDownloading = downloadProgress?.isDownloading == true
        val isCached = episode.isDownloaded || downloadProgress?.isCompleted == true
        if (isCached || isDownloading) {
            Spacer(modifier = Modifier.width(8.dp))
            DownloadProgressIndicator(
                isDownloaded = isCached,
                isDownloading = isDownloading,
                downloadProgress = downloadProgress?.progress,
                modifier = Modifier.align(Alignment.CenterVertically),
                // 15dp 对应 iOS compact 尺寸类 cacheIconWidth = 15（PlayableTableCell.swift:403）
                size = 15.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 播放中指示 / 不可用禁止图标（iOS playEpisodeButton 状态）
        if (isPlaying) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                PlayingIndicator()
            }
        } else if (!episode.isAvailableToUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                AmperfyIcons.ban,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.CenterVertically)
            )
        }

        // 右侧区域对齐 iOS：optionsButton（⋯）在 contentView 内、accessoryView 挂 cell 最右，
        // 故 **⋯ 在左、附件在右**，二者可同时出现（Downloads 页即此形态；
        // PlayableTableCell.swift:339-372 accessoryView 与 :408-412 optionsButton 互不影响）
        if (onMenuClick != null) {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Icon(
                    AmperfyIcons.ellipsis,
                    contentDescription = "More options",
                    // tint = .label（PodcastEpisodeTableCell.xib:64 optionsButton
                    // tintColor systemColor="labelColor"）
                    tint = MaterialTheme.colorScheme.label
                )
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                trailingContent()
            }
        }
    }
}

/** iOS publishDate.asShortDayMonthString（如 "5 Jun 2026"）；集中构建器预览卡亦复用 */
fun formatPublishDate(epochMillis: Long): String? {
    if (epochMillis <= 0) return null
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
}

/** 单集时长："Xm" / "Xh Ym"（对齐 PlaylistDetail 的 asDurationShortString 风格）；集中构建器复用 */
fun formatEpisodeDuration(durationSeconds: Int): String {
    if (durationSeconds <= 0) return "0m"
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
