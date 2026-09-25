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
 * Settings for swipe actions on list items
 *
 * 对应 iOS: AmperfyKit/Storage/SwipeActionSettings.swift -> SwipeActionSettings
 *
 * Manages which swipe actions are available on the leading (left) and trailing (right)
 * sides of list items.
 */
data class SwipeActionSettings(
    val leading: List<SwipeActionType> = emptyList(),
    val trailing: List<SwipeActionType> = emptyList()
) {
    /**
     * Get all actions that are not currently used (not in leading or trailing)
     */
    val notUsed: List<SwipeActionType>
        get() {
            val used = leading + trailing
            return SwipeActionType.configurableCases().filter { it !in used }
        }

    /**
     * Get combined list of all actions grouped by position
     * [0] = leading, [1] = trailing, [2] = not used
     *
     * 对应 iOS: SwipeActionSettings.combined
     */
    val combined: List<List<SwipeActionType>>
        get() = listOf(leading, trailing, notUsed)

    companion object {
        /**
         * Default swipe action configuration
         *
         * 对应 iOS: SwipeActionSettings.defaultSettings
         *
         * Leading (left swipe):
         * - Append to Context Queue
         * - Insert to Context Queue
         *
         * Trailing (right swipe):
         * - Append to User Queue
         * - Insert to User Queue
         * - Append Podcast Queue
         * - Insert Podcast Queue
         * - Download
         */
        val DEFAULT = SwipeActionSettings(
            leading = listOf(
                SwipeActionType.APPEND_CONTEXT_QUEUE,
                SwipeActionType.INSERT_CONTEXT_QUEUE
            ),
            trailing = listOf(
                SwipeActionType.APPEND_USER_QUEUE,
                SwipeActionType.INSERT_USER_QUEUE,
                SwipeActionType.APPEND_PODCAST_QUEUE,
                SwipeActionType.INSERT_PODCAST_QUEUE,
                SwipeActionType.DOWNLOAD
            )
        )

        /**
         * Empty settings (no swipe actions)
         */
        val EMPTY = SwipeActionSettings(
            leading = emptyList(),
            trailing = emptyList()
        )
    }

    /**
     * Create new settings with updated leading actions
     */
    fun withLeading(actions: List<SwipeActionType>): SwipeActionSettings {
        return copy(leading = actions)
    }

    /**
     * Create new settings with updated trailing actions
     */
    fun withTrailing(actions: List<SwipeActionType>): SwipeActionSettings {
        return copy(trailing = actions)
    }

    /**
     * Add an action to the leading side
     */
    fun addToLeading(action: SwipeActionType): SwipeActionSettings {
        if (action in leading) return this
        return copy(leading = leading + action)
    }

    /**
     * Add an action to the trailing side
     */
    fun addToTrailing(action: SwipeActionType): SwipeActionSettings {
        if (action in trailing) return this
        return copy(trailing = trailing + action)
    }

    /**
     * Remove an action from leading
     */
    fun removeFromLeading(action: SwipeActionType): SwipeActionSettings {
        return copy(leading = leading.filter { it != action })
    }

    /**
     * Remove an action from trailing
     */
    fun removeFromTrailing(action: SwipeActionType): SwipeActionSettings {
        return copy(trailing = trailing.filter { it != action })
    }

    /**
     * Reset to default settings
     */
    fun resetToDefault(): SwipeActionSettings {
        return DEFAULT
    }
}
