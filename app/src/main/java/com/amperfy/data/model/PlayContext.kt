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
 * PlayContext - Represents the context for playing a collection of playables
 *
 * This is the Android equivalent of iOS's PlayContext struct.
 * It contains information about what is being played, the starting position,
 * and the list of playables in the context.
 *
 * Ported from iOS: AmperfyKit/Api/Wrapper/PlayContext.swift
 */
data class PlayContext(
    val index: Int = 0,                     // Starting play position
    val name: String,                       // Context name (e.g., "Album Name")
    val playables: List<Playable>,          // Array of songs to play
    val type: PlayerMode = PlayerMode.MUSIC, // Player mode
    val isKeepIndexDuringShuffle: Boolean = false  // Whether to keep index when shuffling
) {
    /**
     * Creates a PlayContext with a shuffled starting index
     * Equivalent to iOS: getWithShuffledIndex()
     */
    fun getWithShuffledIndex(): PlayContext {
        return if (playables.isNotEmpty()) {
            copy(index = playables.indices.random())
        } else {
            this
        }
    }

    /**
     * Creates a PlayContext with shuffled playables
     */
    fun withShuffledPlayables(): PlayContext {
        return copy(playables = playables.shuffled())
    }

    companion object {
        /**
         * Create PlayContext from a PlayableContainable
         * Equivalent to iOS: init(containable: PlayableContainable)
         */
        fun fromContainable(
            containable: PlayableContainable,
            index: Int = 0
        ): PlayContext {
            return PlayContext(
                index = index,
                name = containable.name,
                playables = containable.playables,
                type = PlayerMode.MUSIC
            )
        }
    }
}

/**
 * PlayerMode - Represents the type of content being played
 * Equivalent to iOS PlayerMode enum
 */
enum class PlayerMode {
    MUSIC,
    PODCAST
}

/**
 * PlayableContainable - Interface for entities that can contain playables
 *
 * This is the Android equivalent of iOS's PlayableContainable protocol.
 * Implemented by Album, Artist, Playlist, Genre, etc.
 *
 * Ported from iOS: AmperfyKit/Api/Wrapper/PlayableContainable.swift
 */
interface PlayableContainable {
    val id: String
    val name: String
    val playables: List<Playable>
    val isFavorite: Boolean
        get() = false

    /**
     * Returns subtitle information (e.g., artist for album)
     */
    fun getSubtitle(): String? = null

    /**
     * Returns info details about the container
     */
    fun getInfo(): String = ""
}
