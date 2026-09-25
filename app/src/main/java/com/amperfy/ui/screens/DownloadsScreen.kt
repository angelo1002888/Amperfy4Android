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

package com.amperfy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.DownloadItem
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator

/**
 * DownloadsScreen - 下载管理页（Phase 5.2）
 *
 * 对应 iOS: DownloadsVC.swift
 * - 单列表：全部持久化下载记录（iOS DownloadMO；Android 领域模型 DownloadItem）按请求时间升序
 * - 行 = 标准歌曲行（PlayableTableCell/SongListItem：点击播放、长按预览菜单、More 菜单）
 *   或播客单集行（Batch 4：PodcastEpisodeRow，与播客各页同源）
 *   + 下载状态附件（失败/取消=感叹号、完成=对勾、下载中=进度环[iOS 为 spinner，已知差异]）
 * - 右上角菜单三个全局操作对齐 iOS：Clear finished / Retry failed / Cancel all
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBackClick: () -> Unit,
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloadItems by viewModel.downloadItems.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()
    val currentPlayingSong by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    // 大标题向上滚动后收缩为导航栏居中标题（对齐 iOS DownloadsVC largeTitle）
    val showCollapsedTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    var showOptionsMenu by remember { mutableStateOf(false) }

    // 删除缓存确认对话框（More 菜单 Delete Cache）
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { pendingSongs ->
        DeleteCacheConfirmDialog(
            songCount = pendingSongs.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteCache() }
        )
    }

    // 删除单集缓存确认（Batch 4：单集行菜单 Delete Cache 的承接点）
    val pendingDeleteCacheEpisodes by viewModel.swipeCoordinator.pendingDeleteCacheEpisodes.collectAsState()
    pendingDeleteCacheEpisodes?.let { pendingEpisodes ->
        DeleteCacheConfirmDialog(
            songCount = pendingEpisodes.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteEpisodeCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteEpisodeCache() }
        )
    }

    // 添加到播放列表选择器（More 菜单 Add to Playlist）
    val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
    pendingPlaylistSongIds?.let { ids ->
        PlaylistSelectorDialog(
            songIds = ids,
            onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
        )
    }

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) "Downloads" else null,
                actions = {
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(AmperfyIcons.ellipsis, contentDescription = "Options")
                        }
                        // 菜单项对齐 iOS DownloadsVC.swift:53-80
                        IOSStyleContextMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(-72, 48),
                            items = listOf(
                                IOSContextMenuItem.Action(
                                    text = "Clear finished downloads",
                                    // iOS: .clear（SF "clear"，DownloadsVC.swift:67-69），
                                    // 与播放器 Clear Player 同一符号；B6 自绘后不再借用 xmark
                                    icon = AmperfyIcons.clear,
                                    onClick = { viewModel.clearFinishedDownloads() }
                                ),
                                IOSContextMenuItem.Action(
                                    text = "Retry failed downloads",
                                    icon = AmperfyIcons.redo,
                                    onClick = { viewModel.retryFailedDownloads() }
                                ),
                                IOSContextMenuItem.Action(
                                    text = "Cancel all downloads",
                                    icon = AmperfyIcons.cloudX,
                                    onClick = { viewModel.cancelAllDownloads() }
                                )
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = miniPlayerHeight + 16.dp)
        ) {
            // iOS 风格大标题（向上滚动后收缩为导航栏居中标题，对齐 iOS DownloadsVC largeTitle）
            item {
                IOSLargeTitle(
                    text = "Downloads",
                    modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                )
            }

            // 空态提示（iOS DownloadsVC 无空态文案，此为 Android 侧补充，样式与 FavoriteSongs 一致）
            if (downloadItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .padding(top = 120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                AmperfyIcons.cloudX,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Downloads",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 列表区与上方大标题的交界线 = iOS .grouped 表的 section 顶边界线，
            // 恒全宽（0..0），不吃 separatorInset
            if (downloadItems.isNotEmpty()) {
                item {
                    HairlineDivider(
                        color = MaterialTheme.colorScheme.separator  // iOS .separator
                    )
                }
            }

            itemsIndexed(
                downloadItems,
                // 歌曲与单集 id 分属两个命名空间，key 前缀按类型区分（Batch 4）
                key = { _, item -> "dl_${item.entityType.name}_${item.id}" }
            ) { index, item ->
                val song = item.song
                val episode = item.episode
                // 末行的 16dp inset 线让位给下方全宽 section 底边界线
                val isLastRow = index == downloadItems.lastIndex
                if (song != null) {
                    SongListItem(
                        song = song,
                        style = SongListItemStyle.ARTWORK,
                        isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                        downloadProgressMap = downloadProgressMap,
                        // 下载状态附件替代 More 按钮（iOS PlayableTableCell.swift:291-306 accessoryView）
                        trailingContent = {
                            DownloadStatusAccessory(item, downloadProgressMap[song.id]?.progress)
                        },
                        // 本页附件是「下载状态」而非编辑态附件：iOS 此处 displayMode 仍是 .normal
                        // （accessoryView 走 download 分支 PlayableTableCell.swift:356-372），
                        // 行手势不禁且 DownloadsVC 经 containableAtIndexPathCallback 提供 contextMenu
                        // → 显式覆盖 SongListItem 的「有附件即禁长按」默认值
                        isLongPressMenuEnabled = true,
                        showDivider = !isLastRow,
                        callbacks = SongListItemCallbacks(
                            onClick = { viewModel.playSong(song) },
                            onShuffle = { viewModel.shuffleSong(song) },
                            onToggleFavorite = { viewModel.toggleFavorite(song) },
                            onSetRating = { rating -> viewModel.setSongRating(song, rating) },
                            onInsertContextQueue = { viewModel.insertContextQueue(song) },
                            onAppendContextQueue = { viewModel.appendContextQueue(song) },
                            onAddToQueueNext = { viewModel.addToQueueNext(song) },
                            onAddToQueueLater = { viewModel.addToQueueLater(song) },
                            onShowAlbum = { song.albumId?.let(onNavigateToAlbum) },
                            onShowArtist = { song.artistId?.let(onNavigateToArtist) },
                            onAddToPlaylist = { viewModel.addToPlaylist(song) },
                            onDownload = { viewModel.downloadSong(song) },
                            onDeleteCache = { viewModel.deleteSongCache(song) }
                        )
                    )
                } else if (episode != null) {
                    // 单集行复用播客各页同一行组件（含长按预览卡 + 集中构建器菜单）
                    PodcastEpisodeRow(
                        episode = episode,
                        isPlaying = currentPlayingSong?.id == episode.id && isPlaying,
                        // 下载页跨播客混排，行内显示所属播客名（同 Episodes 显示模式）
                        showPodcastTitle = true,
                        // 本页非播客详情页，但无播客详情导航入口 → Show Podcast 省略（已知简化）
                        showPodcastInMenu = false,
                        onClick = { viewModel.playEpisode(episode) },
                        onAction = { action -> viewModel.handleEpisodeAction(episode, action) },
                        onShowPodcast = {},
                        onDeleteOnServer = { viewModel.deleteEpisodeOnServer(episode) },
                        downloadProgressMap = downloadProgressMap,
                        trailingContent = {
                            DownloadStatusAccessory(item, downloadProgressMap[episode.id]?.progress)
                        }
                    )
                    // 歌曲行的分隔线由 SongListItem 内置；单集行（PodcastEpisodeRow）无内置线，
                    // 故仅此分支补画。左端距屏幕左缘 16dp、右端画到屏幕右缘
                    // （UITableView 默认 separatorInset = cell layoutMargins 左右值，
                    // CommonScreenOperations.swift:41-47）；末行不画，让位给全宽段底线
                    if (!isLastRow) {
                        HairlineDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.separator  // iOS .separator
                        )
                    }
                }
            }

            // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
            //（iOS 实机对照 2026-08-10）
            if (downloadItems.isNotEmpty()) {
                item {
                    HairlineDivider(
                        color = MaterialTheme.colorScheme.separator  // iOS .separator
                    )
                }
            }
        }
    }
}

/**
 * 下载状态附件（歌曲行与单集行同源）
 * 对应 iOS PlayableTableCell.swift:291-306 accessoryView：
 * 失败/取消 = 感叹号、完成 = 对勾、下载中 = 进度环（iOS 为不定 spinner，已知差异）；
 * 感叹号/对勾均为 .labelColor 单色（:292-300）
 */
@Composable
private fun DownloadStatusAccessory(item: DownloadItem, progress: Float?) {
    when {
        item.isError -> Icon(
            AmperfyIcons.exclamation,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        item.isFinished -> Icon(
            AmperfyIcons.check,
            contentDescription = "Finished",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        item.isDownloading -> {
            if (progress != null && progress > 0f) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp
                )
            } else {
                // 等待中/无进度数据：不定进度环（iOS spinner）
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp
                )
            }
        }
    }
}
