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
 * 资料库导航项类型 - 对应 iOS: LibraryDisplayType
 * （AmperfyKit/Storage/LibraryDisplaySettings.swift:29-145）
 *
 * rawValue 与 iOS 完全对齐（11 = recentSongs 已在 iOS 废弃，故跳号）；
 * 持久化只存 inUse 的有序 rawValue 数组（iOS PersistentStorage.swift:740-742 语义）。
 * 图标逐项对齐 iOS `LibraryDisplayType.image`（LibraryDisplaySettings.swift:86-117），
 * 经 [AmperfyIcons] 取 Cupertino 字形；newestAlbums / recentAlbums 在 iOS 是自定义资源位图
 * （album_newest / album_recent），Android 侧暂为 Material 保留位（专题11 B6 自绘后合流）。
 */
enum class LibraryDisplayType(
    val rawValue: Int,
    val displayName: String,
    val icon: ImageVector
) {
    ARTISTS(0, "Artists", AmperfyIcons.artist),
    ALBUMS(1, "Albums", AmperfyIcons.album),
    SONGS(2, "Songs", AmperfyIcons.musicalNotes),
    GENRES(3, "Genres", AmperfyIcons.genre),
    DIRECTORIES(4, "Directories", AmperfyIcons.folder),
    PLAYLISTS(5, "Playlists", AmperfyIcons.playlist),
    PODCASTS(6, "Podcasts", AmperfyIcons.podcast),
    DOWNLOADS(7, "Downloads", AmperfyIcons.download),
    FAVORITE_SONGS(8, "Favorite Songs", AmperfyIcons.heartFill),
    FAVORITE_ALBUMS(9, "Favorite Albums", AmperfyIcons.heartFill),
    FAVORITE_ARTISTS(10, "Favorite Artists", AmperfyIcons.heartFill),
    NEWEST_ALBUMS(12, "Newest Albums", AmperfyIcons.albumNewest),
    RECENT_ALBUMS(13, "Recently Played Albums", AmperfyIcons.albumRecent),
    RADIOS(14, "Radios", AmperfyIcons.radio);

    companion object {
        fun fromRawValue(rawValue: Int): LibraryDisplayType? =
            entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * 资料库导航项显隐/排序设置 - 对应 iOS: LibraryDisplaySettings
 * （LibraryDisplaySettings.swift:149-203）
 *
 * 只持久化 inUse（含顺序）；notUsed 由 allCases 差集推导并按 rawValue 升序排列
 * （iOS init(inUse:) :164-167 的关键设计）。
 */
data class LibraryDisplaySettings(
    val inUse: List<LibraryDisplayType>
) {
    /** 隐藏项：全集差集，按 rawValue 升序（iOS notUsed 排列规则） */
    val notUsed: List<LibraryDisplayType>
        get() = LibraryDisplayType.entries.filter { it !in inUse }.sortedBy { it.rawValue }

    fun isVisible(type: LibraryDisplayType): Boolean = type in inUse

    companion object {
        /**
         * 默认设置 - 对应 iOS defaultSettings.inUse（LibraryDisplaySettings.swift:169-184）：
         * 10 项；Genres/Downloads/FavoriteAlbums/FavoriteArtists 默认隐藏
         */
        val DEFAULT = LibraryDisplaySettings(
            inUse = listOf(
                LibraryDisplayType.ARTISTS,
                LibraryDisplayType.ALBUMS,
                LibraryDisplayType.NEWEST_ALBUMS,
                LibraryDisplayType.RECENT_ALBUMS,
                LibraryDisplayType.SONGS,
                LibraryDisplayType.FAVORITE_SONGS,
                LibraryDisplayType.DIRECTORIES,
                LibraryDisplayType.PLAYLISTS,
                LibraryDisplayType.PODCASTS,
                LibraryDisplayType.RADIOS
            )
        )
    }
}
