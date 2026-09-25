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
 * Domain model for Song
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String,
    val albumId: String? = null,
    val duration: Int, // in seconds
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val path: String,
    val isVideo: Boolean = false,
    val playCount: Int = 0,
    val discNumber: Int? = null,
    val created: Long? = null,
    val starred: Long? = null,
    val albumArtistId: String? = null,
    val type: String? = null,
    val bitRate: Int? = null,
    val streamUrl: String? = null,
    val isDownloaded: Boolean = false,
    val downloadPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val rating: Int? = null,  // 0-5, null表示未评分
    /** 所属账户 ident（C0 合同透传，见 Playable.accountId；C0 阶段恒为当前单账户 ident） */
    val accountId: String = "",
    /** ReplayGain（C0 合同透传，服务器 <song replayGain> 下发；null = 无标签） */
    val replayGainTrackGain: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumGain: Float? = null,
    val replayGainAlbumPeak: Float? = null
) {
    /**
     * 是否已收藏
     */
    val isFavorite: Boolean
        get() = starred != null

    /**
     * 是否已缓存（本地存储）
     */
    val isCached: Boolean
        get() = isDownloaded
}

fun Song.toPlayable(): Playable {
    return Playable(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        coverArt = coverArt,
        streamUrl = streamUrl ?: path,
        isDownloaded = isDownloaded,
        downloadPath = downloadPath,
        accountId = accountId,
        replayGainTrackGain = replayGainTrackGain,
        replayGainTrackPeak = replayGainTrackPeak,
        replayGainAlbumGain = replayGainAlbumGain,
        replayGainAlbumPeak = replayGainAlbumPeak
    )
}

/**
 * Convert Song to Playable with properly generated URLs
 * Equivalent to iOS: BackendAudioPlayer.insertStreamPlayable()
 *
 * Generates:
 * - Stream URL using Subsonic stream API
 * - Cover art URL using Subsonic getCoverArt API
 */
fun Song.toPlayableWithUrls(
    getStreamUrl: (String) -> String,
    getCoverArtUrl: (String) -> String?
): Playable {
    // Generate stream URL
    val generatedStreamUrl = getStreamUrl(id)

    // Generate cover art URL
    val generatedCoverArtUrl = coverArt?.let { getCoverArtUrl(it) }

    return Playable(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        coverArt = generatedCoverArtUrl,
        streamUrl = generatedStreamUrl,
        isDownloaded = isDownloaded,
        downloadPath = downloadPath,
        accountId = accountId,
        replayGainTrackGain = replayGainTrackGain,
        replayGainTrackPeak = replayGainTrackPeak,
        replayGainAlbumGain = replayGainAlbumGain,
        replayGainAlbumPeak = replayGainAlbumPeak
    )
}
