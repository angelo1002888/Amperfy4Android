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

package com.amperfy.ui.screens.player.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.screens.player.PlayerDisplayMode
import com.amperfy.ui.screens.player.PopupPlayerViewModel
import com.amperfy.ui.theme.AmperfyIcons

/**
 * Currently Playing 歌曲的 More 按钮菜单
 *
 * 对应iOS: LargeCurrentlyPlayingPlayerView.optionsButton 和 CurrentlyPlayingTableCell.optionsButton
 * iOS 实现: EntityPreviewActionBuilder(container: currentlyPlaying, on: rootView) (不传 playContextCb)
 *
 * 使用位置:
 * - LargePlayerView 右侧的 More (︙) 按钮
 * - QueueListView 的 Currently Playing 部分右侧的 More (︙) 按钮
 *
 * 菜单项根据 displayMode 变化:
 * - LARGE 模式: Download/Add to Playlist/分隔线/Rating/Favorite/分隔线/Show Artist/Show Album
 * - COMPACT 模式: Show Album/Show Artist/分隔线/Favorite/Rating/分隔线/Add to Playlist/Download
 */
@Composable
fun CurrentlyPlayingSongMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.TopEnd,
    offset: IntOffset = IntOffset(0, 0),
    displayMode: PlayerDisplayMode,
    // Song details
    isFavorite: Boolean,
    rating: Int, // 0-5
    isCached: Boolean,
    isOnlineMode: Boolean,
    hasLyrics: Boolean,
    // Actions
    onShowAlbum: () -> Unit,
    onShowArtist: () -> Unit,
    onShowLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSetRating: (Int) -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onDeleteCache: () -> Unit
) {
    IOSStyleContextMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        alignment = alignment,
        offset = offset,
        items = buildList {
            when (displayMode) {
                PlayerDisplayMode.LARGE -> {
                    // LARGE 模式: Download/Add to Playlist/分隔线/Rating/Favorite/分隔线/Show Artist/Show Album

                    // Section 1: Download / Delete Cache
                    if (!isCached && isOnlineMode) {
                        add(IOSContextMenuItem.Action(
                            text = "Download",
                            icon = AmperfyIcons.download, // iOS: AmperfyImage.download ("arrow.down.circle")
                            onClick = {
                                onDismiss()
                                onDownload()
                            }
                        ))
                    }
                    if (isCached) {
                        add(IOSContextMenuItem.Action(
                            text = "Delete Cache",
                            icon = AmperfyIcons.trash, // iOS: AmperfyImage.trash
                            onClick = {
                                onDismiss()
                                onDeleteCache()
                            }
                        ))
                    }

                    // Add to Playlist
                    if (isOnlineMode) {
                        add(IOSContextMenuItem.Action(
                            text = "Add to Playlist",
                            icon = AmperfyIcons.playlistPlus, // iOS: AmperfyImage.playlistPlus ("text.badge.plus")
                            onClick = {
                                onDismiss()
                                onAddToPlaylist()
                            }
                        ))
                    }

                    add(IOSContextMenuItem.Divider)

                    // Section 2: Rating/Favorite (在线模式)
                    if (isOnlineMode) {
                        // Rating - 使用 RatingPalette (对应iOS: createRatingMenu with displayAsPalette)
                        val ratingText = if (rating == 0) "Not rated" else "$rating Star${if (rating > 1) "s" else ""}"
                        add(IOSContextMenuItem.RatingPalette(
                            text = "Rating: $ratingText",
                            icon = if (rating > 0) AmperfyIcons.starFill else AmperfyIcons.starEmpty,
                            currentRating = rating,
                            onRatingSelected = onSetRating
                        ))

                        // Favorite - 对应iOS: createFavoriteMenu()
                        add(IOSContextMenuItem.Action(
                            text = if (isFavorite) "Unmark favorite" else "Favorite",
                            // iOS: heart / heart.slash（与 EntityPreviewActionBuilder 同源）
                            icon = if (isFavorite) AmperfyIcons.heartSlash else AmperfyIcons.heartEmpty,
                            onClick = {
                                onDismiss()
                                onToggleFavorite()
                            }
                        ))
                    }

                    add(IOSContextMenuItem.Divider)

                    // Section 3: Goto Actions
                    add(IOSContextMenuItem.Action(
                        text = "Show Artist",
                        icon = AmperfyIcons.artist, // iOS: AmperfyImage.artist ("music.mic")
                        onClick = {
                            onDismiss()
                            onShowArtist()
                        }
                    ))
                    add(IOSContextMenuItem.Action(
                        text = "Show Album",
                        icon = AmperfyIcons.album, // iOS: AmperfyImage.album ("square.stack")
                        onClick = {
                            onDismiss()
                            onShowAlbum()
                        }
                    ))
                    // Show Lyrics（iOS: EntityPreviewVC.createShowLyricsAction:738-752，
                    // 歌曲有歌词才显示；iOS 依据落盘 lyricsRelFilePath，Android 按需拉取判定）
                    if (hasLyrics) {
                        add(IOSContextMenuItem.Action(
                            text = "Show Lyrics",
                            icon = AmperfyIcons.lyrics, // iOS: AmperfyImage.lyrics ("quote.bubble")
                            onClick = {
                                onDismiss()
                                onShowLyrics()
                            }
                        ))
                    }
                }

                PlayerDisplayMode.COMPACT -> {
                    // COMPACT 模式: Show Album/Show Artist/分隔线/Favorite/Rating/分隔线/Add to Playlist/Download

                    // Section 1: Goto Actions
                    add(IOSContextMenuItem.Action(
                        text = "Show Album",
                        icon = AmperfyIcons.album, // iOS: AmperfyImage.album ("square.stack")
                        onClick = {
                            onDismiss()
                            onShowAlbum()
                        }
                    ))
                    add(IOSContextMenuItem.Action(
                        text = "Show Artist",
                        icon = AmperfyIcons.artist, // iOS: AmperfyImage.artist ("music.mic")
                        onClick = {
                            onDismiss()
                            onShowArtist()
                        }
                    ))
                    // Show Lyrics（iOS: EntityPreviewVC.createShowLyricsAction:738-752）
                    if (hasLyrics) {
                        add(IOSContextMenuItem.Action(
                            text = "Show Lyrics",
                            icon = AmperfyIcons.lyrics, // iOS: AmperfyImage.lyrics ("quote.bubble")
                            onClick = {
                                onDismiss()
                                onShowLyrics()
                            }
                        ))
                    }

                    add(IOSContextMenuItem.Divider)

                    // Section 2: Favorite/Rating (在线模式)
                    if (isOnlineMode) {
                        // Favorite - 对应iOS: createFavoriteMenu()
                        add(IOSContextMenuItem.Action(
                            text = if (isFavorite) "Unmark favorite" else "Favorite",
                            // iOS: heart / heart.slash（与 EntityPreviewActionBuilder 同源）
                            icon = if (isFavorite) AmperfyIcons.heartSlash else AmperfyIcons.heartEmpty,
                            onClick = {
                                onDismiss()
                                onToggleFavorite()
                            }
                        ))

                        // Rating - 使用 RatingPalette (对应iOS: createRatingMenu with displayAsPalette)
                        val ratingText = if (rating == 0) "Not rated" else "$rating Star${if (rating > 1) "s" else ""}"
                        add(IOSContextMenuItem.RatingPalette(
                            text = "Rating: $ratingText",
                            icon = if (rating > 0) AmperfyIcons.starFill else AmperfyIcons.starEmpty,
                            currentRating = rating,
                            onRatingSelected = onSetRating
                        ))
                    }

                    add(IOSContextMenuItem.Divider)

                    // Section 3: Add to Playlist
                    if (isOnlineMode) {
                        add(IOSContextMenuItem.Action(
                            text = "Add to Playlist",
                            icon = AmperfyIcons.playlistPlus, // iOS: AmperfyImage.playlistPlus ("text.badge.plus")
                            onClick = {
                                onDismiss()
                                onAddToPlaylist()
                            }
                        ))
                    }

                    // Download / Delete Cache
                    if (!isCached && isOnlineMode) {
                        add(IOSContextMenuItem.Action(
                            text = "Download",
                            icon = AmperfyIcons.download, // iOS: AmperfyImage.download ("arrow.down.circle")
                            onClick = {
                                onDismiss()
                                onDownload()
                            }
                        ))
                    }
                    if (isCached) {
                        add(IOSContextMenuItem.Action(
                            text = "Delete Cache",
                            icon = AmperfyIcons.trash, // iOS: AmperfyImage.trash
                            onClick = {
                                onDismiss()
                                onDeleteCache()
                            }
                        ))
                    }
                }
            }
        }
    )
}

/**
 * PopupPlayerScreen 底部控制栏的 More 按钮菜单
 *
 * 对应iOS: PlayerControlView.optionsButton
 * iOS 实现: PlayerControlView.createPlayerOptionsMenu()
 *
 * 使用位置:
 * - PopupPlayerScreen 底部控制栏右侧的 More (︙) 按钮
 *
 * 菜单带标题 "Player Options"（iOS: PlayerUIHandler.swift:249 `UIMenu.lazyMenu(title:)`）。
 *
 * **顺序说明（B6.1 真机修正）**：iOS 该菜单由右下角按钮**向上**弹出，UIKit 对上弹菜单
 * **倒序显示**——iOS 源码 createPlayerOptionsMenu 的 append 次序与用户实际看到的次序完全相反。
 * 本实现**按可视序（自上而下）写死**，改动时请对照真机而非 iOS 源码行序。
 *
 * 可视序（条件项在不满足时整项缺席，其余项顺次上移）:
 * 1. Player Info
 * 2. Scroll to currently playing（仅 COMPACT）
 * 3. Add Context Queue to Playlist（仅 LARGE + 在线 + 有队列）
 * 4. Visualizer Style ›（仅可视化正显示；subtitle = 当前样式）
 * 5. Show / Hide Audio Visualizer
 * 6. Show / Hide Lyrics
 * 7. Playback Rate ›（subtitle = 当前档位）
 * 8. Sleep Timer ›
 * 9. Clear User Queue（仅 userQueue 非空）
 * 10. Clear Player（仅播放器有内容）
 */
@Composable
fun PlayerOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.BottomEnd,
    offset: IntOffset = IntOffset(0, 0),
    displayMode: PlayerDisplayMode,
    // Queue状态
    hasQueue: Boolean,
    hasUserQueue: Boolean,
    isOnlineMode: Boolean,
    // 播客模式（iOS: switch player.playerMode { case .podcast: break }——播客模式无 Add Context）
    isPodcastMode: Boolean = false,
    // LARGE 模式状态
    isLyricsDisplayed: Boolean = false,
    // iOS 2.1.0: isLyricsButtonAllowedToDisplay = music 模式 && Subsonic（与歌曲是否有歌词无关）
    isLyricsButtonAllowedToDisplay: Boolean = false,
    // Audio Visualizer 状态（iOS: createVisualizerTypeMenu / show-hide 动作）
    isVisualizerDisplayed: Boolean = false,
    isVisualizerButtonAllowedToDisplay: Boolean = false,
    selectedVisualizerType: com.amperfy.data.model.VisualizerType = com.amperfy.data.model.VisualizerType.RING,
    onShowVisualizer: () -> Unit = {},
    onHideVisualizer: () -> Unit = {},
    onSetVisualizerType: (com.amperfy.data.model.VisualizerType) -> Unit = {},
    // Playback Rate 状态（Phase 4.1，iOS: createPlaybackRateMenu）
    currentPlaybackRate: Float = 1.0f,
    onSetPlaybackRate: (Float) -> Unit = {},
    // Sleep Timer 状态（Phase 4.2，iOS: createSleepTimerMenu）
    sleepTimerRemainingSeconds: Int = 0,
    isPauseAfterCurrentSong: Boolean = false,
    onStartSleepTimer: (Int) -> Unit = {},
    onPauseAfterCurrentSong: () -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    // Actions - 通用
    onClearPlayer: () -> Unit,
    onClearUserQueue: () -> Unit,
    onPlayerInfo: () -> Unit,
    // Actions - LARGE 模式
    onShowLyrics: () -> Unit = {},
    onHideLyrics: () -> Unit = {},
    onAddContextQueueToPlaylist: () -> Unit = {},
    // Actions - COMPACT 模式
    onScrollToCurrentlyPlaying: () -> Unit = {}
) {
    IOSStyleContextMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        alignment = alignment,
        offset = offset,
        // iOS: PlayerUIHandler.swift:249 `UIMenu.lazyMenu(title: "Player Options")`
        title = "Player Options",
        // iOS 该菜单由底部控制栏的 More 按钮上弹，菜单**右下角贴住按钮**；
        // 故走「贴锚点上方」定位（右缘随 BottomEnd 对齐按钮右缘，底边贴按钮顶边上方 8dp），
        // 不再用魔法 offset 硬凑
        placeAboveAnchor = true,
        items = buildList {
            // **按可视顺序（自上而下）写死**。注意 iOS 侧该菜单从右下角按钮向上弹出，
            // UIKit 对上弹菜单**倒序显示**——即 iOS 源码里的 append 次序与用户看到的次序相反
            // （已用真机逐项核对）。故本实现不照抄 createPlayerOptionsMenu 的 append 序，
            // 而是直接按真机可视序排列，改动时请对照真机而非 iOS 源码行序。

            // Player Info
            add(IOSContextMenuItem.Action(
                text = "Player Info",
                icon = AmperfyIcons.info, // iOS: AmperfyImage.info
                onClick = {
                    onDismiss()
                    onPlayerInfo()
                }
            ))

            // Scroll to currently playing (COMPACT 模式)
            if (displayMode == PlayerDisplayMode.COMPACT) {
                add(IOSContextMenuItem.Action(
                    text = "Scroll to currently playing",
                    icon = AmperfyIcons.squareArrow, // iOS: AmperfyImage.squareArrow ("arrow.forward.square")
                    onClick = {
                        onDismiss()
                        onScrollToCurrentlyPlaying()
                    }
                ))
            }

            // Add Context Queue to Playlist——iOS 条件（PlayerControlView.swift:387-390）：
            // music 模式 + 有内容 + 在线，**与显示样式无关**（LARGE/COMPACT 均显示；
            // 原 `displayMode == LARGE` 门为移植期误加，2026-08-08 移除）
            if (isOnlineMode && hasQueue && !isPodcastMode) {
                add(IOSContextMenuItem.Action(
                    text = "Add Context Queue to Playlist",
                    icon = AmperfyIcons.playlistPlus, // iOS: AmperfyImage.playlistPlus
                    onClick = {
                        onDismiss()
                        onAddContextQueueToPlaylist()
                    }
                ))
            }

            // 注：iOS 1.2.3 播放器菜单无 "Show Audio Visualizer" 项（iOS 无此功能），
            // 517bfed 误加，已移除；Audio Visualizer 为 Android Backlog 项，实现时再加

            // Visualizer Style 子菜单 + Show/Hide Audio Visualizer（iOS: PlayerControlView.swift:354-384）
            // 可视序：Style 在 Show/Hide **之上**（iOS append 序为 hide→style，上弹倒序所致）；
            // Style 仅在可视化正显示时出现
            if (isVisualizerButtonAllowedToDisplay) {
                val visualizerShown = isVisualizerDisplayed && displayMode == PlayerDisplayMode.LARGE
                if (visualizerShown) {
                    // 当前样式作 subtitle 第二行（iOS :304-306 subtitle = currentType.displayName）
                    add(IOSContextMenuItem.Submenu(
                        text = "Visualizer Style",
                        subtitle = selectedVisualizerType.displayName,
                        icon = AmperfyIcons.sparkles, // iOS: AmperfyImage.sparkles
                        items = com.amperfy.data.model.VisualizerType.entries.map { type ->
                            IOSContextMenuItem.Action(
                                text = type.displayName,
                                icon = if (type == selectedVisualizerType) AmperfyIcons.check else null,
                                onClick = {
                                    onDismiss()
                                    onSetVisualizerType(type)
                                }
                            )
                        }
                    ))
                }
                add(IOSContextMenuItem.Action(
                    text = if (visualizerShown) "Hide Audio Visualizer" else "Show Audio Visualizer",
                    // iOS: AmperfyImage.audioVisualizer ("circle.dashed")，B6 已自绘虚线圆
                    icon = AmperfyIcons.audioVisualizer,
                    onClick = {
                        onDismiss()
                        if (visualizerShown) onHideVisualizer() else onShowVisualizer()
                    }
                ))
            }

            // Show/Hide Lyrics（iOS: PlayerControlView.swift:509-529）
            // 显示条件见 isLyricsButtonAllowedToDisplay（music 模式，播客模式隐藏），
            // 与歌曲是否有歌词无关；
            // 文案：歌词未显示或非 LARGE 视图 → "Show Lyrics"（点击置位并切回 LARGE），
            //       已显示且 LARGE 视图 → "Hide Lyrics"
            if (isLyricsButtonAllowedToDisplay) {
                val showsHide = isLyricsDisplayed && displayMode == PlayerDisplayMode.LARGE
                add(IOSContextMenuItem.Action(
                    text = if (showsHide) "Hide Lyrics" else "Show Lyrics",
                    // iOS: AmperfyImage.lyrics ("quote.bubble")，Show/Hide 两态同图标
                    // （PlayerControlView.swift:334,346）
                    icon = AmperfyIcons.lyrics,
                    onClick = {
                        onDismiss()
                        if (showsHide) onHideLyrics() else onShowLyrics()
                    }
                ))
            }

            // Playback Rate 子菜单（iOS: createPlaybackRateMenu，PlayerControlView.swift:472-488）
            // 标题附当前档位（对应 iOS UIMenu subtitle），当前档打勾
            add(IOSContextMenuItem.Submenu(
                text = "Playback Rate",
                // 当前档位作 subtitle 第二行（iOS createPlaybackRateMenu subtitle 语义，:283-284）
                subtitle = PopupPlayerViewModel.formatPlaybackRate(currentPlaybackRate),
                icon = AmperfyIcons.playbackRate, // iOS: AmperfyImage.playbackRate（speedometer 近似）
                items = PopupPlayerViewModel.PLAYBACK_RATES.map { rate ->
                    IOSContextMenuItem.Action(
                        text = PopupPlayerViewModel.formatPlaybackRate(rate),
                        icon = if (rate == currentPlaybackRate) AmperfyIcons.check else null,
                        onClick = {
                            onDismiss()
                            onSetPlaybackRate(rate)
                        }
                    )
                }
            ))

            // Sleep Timer 子菜单（iOS: AppDelegate.createSleepTimerMenu，AppDelegate.swift:483-567）
            // 已激活 → 单项 Turn Off（附剩余时间/End of Song 状态）；未激活 → End of Song + 8 个定时档
            val sleepTimerActive = sleepTimerRemainingSeconds > 0 || isPauseAfterCurrentSong
            val sleepTimerTitle = when {
                sleepTimerRemainingSeconds > 0 ->
                    "Sleep Timer (${PopupPlayerViewModel.formatSleepRemaining(sleepTimerRemainingSeconds)})"
                isPauseAfterCurrentSong -> "Sleep Timer (End of Song)"
                else -> "Sleep Timer"
            }
            add(IOSContextMenuItem.Submenu(
                text = sleepTimerTitle,
                icon = AmperfyIcons.sleep, // iOS: AmperfyImage.sleep ("moon.zzz")
                items = if (sleepTimerActive) {
                    listOf(IOSContextMenuItem.Action(
                        text = "Turn Off",
                        // iOS 该项 `image: nil`——无图标（AppDelegateMainMenuExtension.swift:354-378
                        // 的 deactivate UIAction），与下方未激活分支的 End of Song / 各定时档一致
                        icon = null,
                        onClick = {
                            onDismiss()
                            onCancelSleepTimer()
                        }
                    ))
                } else {
                    buildList {
                        add(IOSContextMenuItem.Action(
                            text = "End of Song",
                            onClick = {
                                onDismiss()
                                onPauseAfterCurrentSong()
                            }
                        ))
                        // 定时档位对齐 iOS：5/10/15/30/45 分钟 + 1/2/4 小时
                        listOf(5, 10, 15, 30, 45, 60, 120, 240).forEach { minutes ->
                            val label = when {
                                minutes < 60 -> "$minutes Minutes"
                                minutes == 60 -> "1 Hour"
                                else -> "${minutes / 60} Hours"
                            }
                            add(IOSContextMenuItem.Action(
                                text = label,
                                onClick = {
                                    onDismiss()
                                    onStartSleepTimer(minutes)
                                }
                            ))
                        }
                    }
                }
            ))

            // Clear User Queue（iOS: PlayerControlView.swift:322-325，条件 userQueueCount > 0）
            if (hasUserQueue) {
                add(IOSContextMenuItem.Action(
                    text = "Clear User Queue",
                    icon = AmperfyIcons.playlistX, // iOS: AmperfyImage.playlistX ("text.badge.xmark")
                    onClick = {
                        onDismiss()
                        onClearUserQueue()
                    }
                ))
            }

            // Clear Player（最后显示）。iOS 条件 = currentlyPlaying != nil || 任一队列非空；
            // Android 的 hasQueue 已是「播放器有内容」的等价判据（见调用方 PopupPlayerViewModel.hasQueue）
            if (hasQueue) {
                add(IOSContextMenuItem.Action(
                    text = "Clear Player",
                    // iOS: AmperfyImage.clear ("clear"，PlayerControlView.swift:316)。
                    // B6 自绘出圆角方框 + 内嵌 X 的「清除键」形（cupertino 同名字形只是裸 ✕，
                    // 会丢外框且与关闭钮同形，故不取）
                    icon = AmperfyIcons.clear,
                    onClick = {
                        onDismiss()
                        onClearPlayer()
                    }
                ))
            }
        }
    )
}
