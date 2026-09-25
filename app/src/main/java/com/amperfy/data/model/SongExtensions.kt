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

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.repository.MediaUrlRepository

/**
 * Extension functions for Song domain model
 * Provides convenient conversions to Playable with proper URL building
 */

/**
 * Convert Song to Playable with streaming and artwork URLs built from credentials
 *
 * This extension eliminates duplicate code across ViewModels that need to convert
 * Song objects to Playable format with proper authentication URLs.
 *
 * @param credentialsManager Provides server credentials for URL building
 * @param musicRepository Repository for building stream and cover art URLs
 * @return Playable object with URLs, or basic Playable if credentials unavailable
 */
fun Song.toPlayableWithCredentials(
    credentialsManager: CredentialsManager,
    musicRepository: MediaUrlRepository
): Playable {
    // Try to get server credentials to build proper streaming and artwork URLs
    val credentials = credentialsManager.getCredentials()
    return if (credentials != null) {
        toPlayableWithUrls(
            getStreamUrl = { songId ->
                musicRepository.getStreamUrl(
                    songId,
                    credentials.username,
                    credentials.password,
                    credentials.serverUrl
                )
            },
            getCoverArtUrl = { coverArtId ->
                musicRepository.getCoverArtUrl(
                    coverArtId,
                    credentials.username,
                    credentials.password,
                    credentials.serverUrl
                )
            }
        )
    } else {
        // Fallback: Return basic Playable without URLs if credentials not available
        toPlayable()
    }
}
