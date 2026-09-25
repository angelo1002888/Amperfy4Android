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

package com.amperfy.data.repository

import com.amperfy.data.model.*
import com.amperfy.data.remote.dto.*

// DTO→domain 映射（原 MusicRepository.kt 文件末尾的顶层 private 函数）：
// 拆分后由六个域实现共用，故可见性由 private 放宽为 internal，函数体逐字不变。
// 出处：Repository 拆分批次 2。

// Extension functions to convert DTOs to domain models
internal fun ArtistDto.toArtist() = Artist(
    id = id,
    name = name,
    coverArt = coverArt,
    albumCount = albumCount,
    // 解析 starred（丢失该字段会导致全量同步时收藏标记被 UpdatePolicy.ALL 覆盖清零）
    starred = starred?.let { parseStarredDate(it) },
    rating = userRating ?: 0  // Subsonic userRating -> Artist rating
)

internal fun ArtistDetailDto.toArtist() = Artist(
    id = id,
    name = name,
    coverArt = coverArt,
    albumCount = albumCount,
    starred = starred?.let { parseStarredDate(it) },
    rating = userRating ?: 0  // Subsonic userRating -> Artist rating
)

internal fun AlbumDto.toAlbum() = Album(
    id = id,
    name = name,
    artist = artist ?: "",
    artistId = artistId,
    coverArt = coverArt,
    songCount = songCount,
    duration = duration,
    year = year,
    genre = genre,
    created = created?.let { parseStarredDate(it) },
    playCount = playCount ?: 0,
    starred = starred?.let { parseStarredDate(it) },
    rating = userRating ?: 0  // Subsonic userRating -> Album rating
)

internal fun AlbumDetailDto.toAlbum() = Album(
    id = id,
    name = name,
    artist = artist ?: "",
    artistId = artistId,
    coverArt = coverArt,
    songCount = songCount,
    duration = duration,
    year = year,
    genre = genre,
    created = created?.let { parseStarredDate(it) },
    playCount = playCount ?: 0,
    starred = starred?.let { parseStarredDate(it) },
    rating = userRating ?: 0  // Subsonic userRating -> Album rating
)

internal fun SongDto.toSong(accountId: String) = Song(
    id = id,
    title = title,
    artist = artist ?: "",
    artistId = artistId,
    album = album ?: "",
    albumId = albumId,
    duration = duration,
    track = track,
    year = year,
    genre = genre,
    coverArt = coverArt,
    size = size,
    contentType = contentType,
    suffix = suffix,
    path = path ?: "",
    isVideo = isVideo,
    playCount = playCount ?: 0,
    discNumber = discNumber,
    bitRate = bitRate,
    type = type,
    // 解析starred字段 - Subsonic返回的是ISO日期字符串，转换为时间戳
    starred = starred?.let { parseStarredDate(it) },
    rating = userRating ?: 0,  // Subsonic userRating -> Song rating
    accountId = accountId,
    replayGainTrackGain = replayGain?.trackGain,
    replayGainTrackPeak = replayGain?.trackPeak,
    replayGainAlbumGain = replayGain?.albumGain,
    replayGainAlbumPeak = replayGain?.albumPeak
)

/**
 * 解析Subsonic的starred日期字符串为时间戳
 * Subsonic返回格式: "2024-01-15T10:30:00.000Z"
 */
internal fun parseStarredDate(dateString: String): Long? {
    return try {
        java.time.Instant.parse(dateString).toEpochMilli()
    } catch (e: Exception) {
        // 如果解析失败，尝试简单的非空检查
        // 有starred字段意味着已收藏
        System.currentTimeMillis()
    }
}

internal fun PlaylistDto.toPlaylist() = Playlist(
    id = id,
    name = name,
    comment = comment,
    owner = owner,
    isPublic = isPublic,
    songCount = songCount,
    duration = duration,
    coverArt = coverArt,
    created = created?.let { parseStarredDate(it) },
    changed = changed?.let { parseStarredDate(it) }
)
