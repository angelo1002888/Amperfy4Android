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

/**
 * 滑动动作显示内容类型
 *
 * 对应 iOS: SwipeDisplaySettings.playContextTypeOfElements (PlayerMode)
 */
enum class SwipeContentType {
    MUSIC,
    PODCAST
}

/**
 * 滑动动作显示过滤规则
 *
 * 对应 iOS: BasicTableViewController.swift -> SwipeDisplaySettings.isAllowedToDisplay (行 60-98)
 *
 * 根据内容类型、离线模式和是否可收藏决定某个滑动动作是否显示
 */
object SwipeDisplaySettings {

    /**
     * 判断动作是否允许显示
     *
     * @param actionType 动作类型
     * @param contentType 列表内容类型（音乐 / 播客）
     * @param isOfflineMode 是否处于离线模式
     * @param isFavoritable 内容是否支持收藏
     */
    fun isAllowedToDisplay(
        actionType: SwipeActionType,
        contentType: SwipeContentType = SwipeContentType.MUSIC,
        isOfflineMode: Boolean = false,
        isFavoritable: Boolean = true
    ): Boolean {
        when (contentType) {
            SwipeContentType.MUSIC -> {
                // 音乐内容不显示 Podcast 队列动作
                // （iOS 还会对单个 Radio 隐藏 addToPlaylist；Android 电台行无滑动手势入口，
                // 天然规避，无需过滤——见 Phase 6.3 已知简化记录）
                if (actionType == SwipeActionType.INSERT_PODCAST_QUEUE ||
                    actionType == SwipeActionType.APPEND_PODCAST_QUEUE
                ) {
                    return false
                }
            }
            SwipeContentType.PODCAST -> {
                // 播客内容不显示随机播放、播放列表和音乐队列动作
                if (actionType == SwipeActionType.PLAY_SHUFFLED ||
                    actionType == SwipeActionType.ADD_TO_PLAYLIST ||
                    actionType == SwipeActionType.INSERT_CONTEXT_QUEUE ||
                    actionType == SwipeActionType.APPEND_CONTEXT_QUEUE ||
                    actionType == SwipeActionType.INSERT_USER_QUEUE ||
                    actionType == SwipeActionType.APPEND_USER_QUEUE
                ) {
                    return false
                }
            }
        }
        // 离线模式下隐藏需要服务器交互的动作
        if (isOfflineMode &&
            (actionType == SwipeActionType.ADD_TO_PLAYLIST ||
                actionType == SwipeActionType.DOWNLOAD ||
                actionType == SwipeActionType.FAVORITE)
        ) {
            return false
        }
        if (!isFavoritable && actionType == SwipeActionType.FAVORITE) {
            return false
        }
        return true
    }

    /**
     * 过滤动作列表，仅保留允许显示的动作
     */
    fun filter(
        actions: List<SwipeActionType>,
        contentType: SwipeContentType = SwipeContentType.MUSIC,
        isOfflineMode: Boolean = false,
        isFavoritable: Boolean = true
    ): List<SwipeActionType> {
        return actions.filter {
            isAllowedToDisplay(it, contentType, isOfflineMode, isFavoritable)
        }
    }
}
