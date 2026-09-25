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
 * Artist数据模型
 * 对应iOS: ArtistMO (Core Data Entity)
 */
data class Artist(
    override val id: String,
    override val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val songCount: Int = 0,
    val duration: Int = 0,  // 总时长（秒）
    val starred: Long? = null,  // 收藏时间戳
    val rating: Int = 0,  // 评分 (0-5)
    val addedAt: Long = System.currentTimeMillis()  // 添加时间
) : PlayableContainable {
    override val playables: List<Playable>
        get() = emptyList() // 将在Repository层通过关联查询获取

    override val isFavorite: Boolean
        get() = starred != null

    override fun getSubtitle(): String? {
        return when {
            albumCount > 0 && songCount > 0 -> "$albumCount ${if (albumCount == 1) "Album" else "Albums"} • $songCount ${if (songCount == 1) "Song" else "Songs"}"
            albumCount > 0 -> "$albumCount ${if (albumCount == 1) "Album" else "Albums"}"
            songCount > 0 -> "$songCount ${if (songCount == 1) "Song" else "Songs"}"
            else -> null
        }
    }

    override fun getInfo(): String {
        val parts = mutableListOf<String>()
        if (albumCount > 0) {
            parts.add("$albumCount ${if (albumCount == 1) "album" else "albums"}")
        }
        if (songCount > 0) {
            parts.add("$songCount ${if (songCount == 1) "song" else "songs"}")
        }
        if (duration > 0) {
            parts.add(formatDuration(duration))
        }
        return parts.joinToString(" • ")
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    /**
     * 获取字母索引
     * 对应iOS: alphabeticSectionInitial
     */
    fun getAlphabeticSection(): String {
        val firstChar = name.firstOrNull()?.uppercaseChar() ?: '#'
        return if (firstChar.isLetter()) firstChar.toString() else "#"
    }
}

/**
 * Artist完整信息（包含关联的专辑和歌曲）
 * 用于详情页面显示
 */
data class ArtistWithDetails(
    val artist: Artist,
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList()
) {
    val totalDuration: Int
        get() = songs.sumOf { it.duration }

    val totalSongCount: Int
        get() = songs.size

    val totalAlbumCount: Int
        get() = albums.size
}
