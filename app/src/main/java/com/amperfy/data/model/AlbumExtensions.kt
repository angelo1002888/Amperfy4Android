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
 * Extension functions and wrapper for Album to support PlayableContainable
 *
 * This file provides iOS compatibility layer for Album entities.
 * Ported from iOS: AmperfyKit/Storage/EntityWrappers/Album.swift
 */

/**
 * AlbumContainer - Wrapper that makes Album conform to PlayableContainable
 *
 * Similar to iOS's Album class which wraps AlbumMO (Managed Object)
 */
data class AlbumContainer(
    val album: Album,
    private val songs: List<Song> = emptyList()
) : PlayableContainable {

    override val id: String
        get() = album.id

    override val name: String
        get() = album.name

    /**
     * Returns all songs in this album as Playable objects
     * Equivalent to iOS: var playables: [AbstractPlayable]
     */
    override val playables: List<Playable>
        get() = songs.map { it.toPlayable() }

    /**
     * Returns sorted songs by track number
     * Equivalent to iOS: var songs: [AbstractPlayable]
     */
    val sortedSongs: List<Song>
        get() = songs.sortedBy { it.track ?: 0 }

    /**
     * Artist name as subtitle
     * Equivalent to iOS: var subtitle: String?
     */
    override fun getSubtitle(): String = album.artist

    /**
     * Album information string
     * Equivalent to iOS: func info(for api: BackenApiType, details: DetailInfoType) -> String
     */
    override fun getInfo(): String {
        val parts = mutableListOf<String>()

        // Add year
        album.year?.let { parts.add("$it") }

        // Add song count
        val songCountText = "${songCount} Song${if (songCount == 1) "" else "s"}"
        parts.add(songCountText)

        // Add duration if > 0
        if (album.duration > 0) {
            parts.add(formatDuration(album.duration))
        }

        // Add genre
        album.genre?.let {
            if (it.isNotBlank()) parts.add(it)
        }

        return parts.joinToString(" · ")
    }

    /**
     * Number of songs in the album
     * Equivalent to iOS: var songCount: Int
     */
    val songCount: Int
        get() = songs.size

    /**
     * Whether the album is favorited/starred
     * Equivalent to iOS: var isFavorite: Bool
     */
    override val isFavorite: Boolean
        get() = album.starred != null

    /**
     * Album duration in seconds
     * Equivalent to iOS: var duration: Int
     */
    val duration: Int
        get() = album.duration

    /**
     * Remote album duration from server
     * Equivalent to iOS: var remoteDuration: Int
     */
    val remoteDuration: Int
        get() = album.duration

    /**
     * Album year
     * Equivalent to iOS: var year: Int
     */
    val year: Int?
        get() = album.year

    /**
     * Artist information
     * Equivalent to iOS: var artist: Artist?
     */
    val artist: String
        get() = album.artist

    val artistId: String?
        get() = album.artistId

    /**
     * Album artwork/cover art ID
     * Equivalent to iOS: artwork property from AbstractLibraryEntity
     */
    val coverArt: String?
        get() = album.coverArt

    /**
     * Genre information
     * Equivalent to iOS: var genre: Genre?
     */
    val genre: String?
        get() = album.genre

    /**
     * Whether album songs metadata is synced
     * Equivalent to iOS: var isSongsMetaDataSynced: Bool
     */
    val isSongsMetaDataSynced: Boolean
        get() = songs.isNotEmpty()

    /**
     * Format duration in seconds to readable string (mm:ss or h:mm:ss)
     */
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    /**
     * Create PlayContext for this album
     * Equivalent to iOS: PlayContext(containable: album)
     */
    fun createPlayContext(startIndex: Int = 0): PlayContext {
        return PlayContext(
            index = startIndex,
            name = name,
            playables = playables,
            type = PlayerMode.MUSIC
        )
    }

    /**
     * Fetch/sync album details from server
     * Equivalent to iOS: func fetch(storage:librarySyncer:playableDownloadManager:) async throws
     *
     * Note: In Android, this should be called from ViewModel/Repository
     */
    companion object {
        /**
         * Create AlbumContainer from Album and its songs
         */
        fun create(album: Album, songs: List<Song>): AlbumContainer {
            return AlbumContainer(album, songs)
        }
    }
}

/**
 * Extension function to convert Album to AlbumContainer
 */
fun Album.toContainer(songs: List<Song> = emptyList()): AlbumContainer {
    return AlbumContainer(this, songs)
}
