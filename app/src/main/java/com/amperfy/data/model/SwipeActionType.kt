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

package com.amperfy.data.model

import androidx.compose.ui.graphics.vector.ImageVector
import com.amperfy.ui.theme.AmperfyIcons

/**
 * Swipe action types for list items
 *
 * 对应 iOS: AmperfyKit/Storage/SwipeActionSettings.swift -> SwipeActionType
 *
 * Defines all available swipe actions that can be performed on playable items
 * (songs, albums, artists, playlists, etc.) in list views
 */
enum class SwipeActionType(
    val rawValue: Int,
    val displayName: String
) {
    // displayName 对齐 iOS SwipeActionSettings.swift:41-68（滑动按钮标题用 displayName）
    INSERT_USER_QUEUE(0, "Insert User Queue"),
    APPEND_USER_QUEUE(1, "Append User Queue"),
    INSERT_CONTEXT_QUEUE(2, "Insert Context Queue"),
    APPEND_CONTEXT_QUEUE(3, "Append Context Queue"),
    DOWNLOAD(4, "Download"),
    REMOVE_FROM_CACHE(5, "Remove from Cache"),
    ADD_TO_PLAYLIST(6, "Add to Playlist"),
    PLAY(7, "Play"),
    PLAY_SHUFFLED(8, "Play shuffled"),
    INSERT_PODCAST_QUEUE(9, "Insert Podcast Queue"),
    APPEND_PODCAST_QUEUE(10, "Append Podcast Queue"),
    FAVORITE(11, "Favorite"),

    // Android 专用：播放器队列行的移出操作
    // 对应 iOS: PopupPlayer+TableViewExtension 中 UITableView 原生 delete editingStyle
    // 不可在 SwipeSettingsScreen 中配置（iOS 中它不属于 SwipeActionType）
    REMOVE_FROM_QUEUE(12, "Remove");

    /**
     * 是否可在 SwipeSettingsScreen 中配置到 leading/trailing
     */
    val isConfigurable: Boolean
        get() = this != REMOVE_FROM_QUEUE

    /**
     * 设置页面中显示的完整名称
     *
     * 对应 iOS: SwipeActionSettings.swift -> settingsName
     */
    val settingsName: String
        get() = when (this) {
            INSERT_USER_QUEUE -> "Insert in User Queue"
            APPEND_USER_QUEUE -> "Append to User Queue"
            INSERT_CONTEXT_QUEUE -> "Insert in Context Queue"
            APPEND_CONTEXT_QUEUE -> "Append to Context Queue"
            DOWNLOAD -> "Download"
            REMOVE_FROM_CACHE -> "Remove from Cache"
            ADD_TO_PLAYLIST -> "Add to Playlist"
            PLAY -> "Play"
            PLAY_SHUFFLED -> "Play shuffled"
            INSERT_PODCAST_QUEUE -> "Insert in Podcast Queue"
            APPEND_PODCAST_QUEUE -> "Append to Podcast Queue"
            FAVORITE -> "Mark as Favorite"
            REMOVE_FROM_QUEUE -> "Remove from Queue"
        }

    /**
     * Get the icon for this swipe action
     *
     * 对应 iOS: SwipeActionSettings.swift:100-127
     *
     * Icon mapping to iOS:
     * - insertUserQueue -> user_queue_insert (custom SF Symbol)
     * - appendUserQueue -> user_queue_append (custom SF Symbol)
     * - insertContextQueue -> context_queue_insert (custom SF Symbol)
     * - appendContextQueue -> context_queue_append (custom SF Symbol)
     * - download -> arrow.down.circle (SF Symbol)
     * - removeFromCache -> trash (SF Symbol)
     * - addToPlaylist -> music.note.list (SF Symbol)
     * - play -> play.fill (SF Symbol)
     * - playShuffled -> shuffle (SF Symbol)
     * - insertPodcastQueue -> context_queue_insert (SF Symbol)
     * - appendPodcastQueue -> context_queue_append (SF Symbol)
     * - favorite -> heart.fill (SF Symbol)
     *
     * 图标一律走 AmperfyIcons 集中映射：Cupertino 字形直供，
     * iOS 侧为自定义 asset 的四个队列图标暂由 AmperfyIcons 的 Material 保留位承接
     * （queue* 六属性，B6 自绘后只改那一处，本处不动）。
     */
    fun getIcon(isFavorite: Boolean = false): ImageVector {
        return when (this) {
            // User Queue - iOS: user_queue_insert / user_queue_append（自定义 asset）
            INSERT_USER_QUEUE -> AmperfyIcons.userQueueInsert
            APPEND_USER_QUEUE -> AmperfyIcons.userQueueAppend

            // Context Queue - iOS: context_queue_insert / context_queue_append（自定义 asset）
            INSERT_CONTEXT_QUEUE -> AmperfyIcons.contextQueueInsert
            APPEND_CONTEXT_QUEUE -> AmperfyIcons.contextQueueAppend

            // Download - 对应 iOS: arrow.down.circle
            DOWNLOAD -> AmperfyIcons.download

            // Remove Cache - 对应 iOS: trash
            REMOVE_FROM_CACHE -> AmperfyIcons.trash

            // Add to Playlist - 对应 iOS: music.note.list
            ADD_TO_PLAYLIST -> AmperfyIcons.playlist

            // Play - 对应 iOS: play.fill
            PLAY -> AmperfyIcons.play

            // Shuffle - 对应 iOS: shuffle
            PLAY_SHUFFLED -> AmperfyIcons.shuffle

            // Podcast Queue - 使用与 Context Queue 相同的图标 (对应 iOS 的设计)
            // iOS: podcastQueueInsert = contextQueueInsert
            // iOS: podcastQueueAppend = contextQueueAppend
            INSERT_PODCAST_QUEUE -> AmperfyIcons.podcastQueueInsert
            APPEND_PODCAST_QUEUE -> AmperfyIcons.podcastQueueAppend

            // Favorite - 对应 iOS: preCbContainable.isFavorite ? heartFill : heartEmpty
            FAVORITE -> if (isFavorite) AmperfyIcons.heartFill else AmperfyIcons.heartEmpty

            // Remove from Queue - 对应 iOS: UITableView delete editingStyle（trash 形）
            REMOVE_FROM_QUEUE -> AmperfyIcons.trash
        }
    }

    companion object {
        /**
         * Get SwipeActionType from raw value
         */
        fun fromRawValue(value: Int): SwipeActionType? {
            return entries.find { it.rawValue == value }
        }

        /**
         * All available action types
         */
        fun allCases(): List<SwipeActionType> {
            return entries
        }

        /**
         * 可在 SwipeSettingsScreen 中配置的动作类型
         *
         * 对应 iOS: SwipeActionType.allCases（iOS 枚举不含 REMOVE_FROM_QUEUE）
         */
        fun configurableCases(): List<SwipeActionType> {
            return entries.filter { it.isConfigurable }
        }
    }
}
