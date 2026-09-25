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
 * Home 首页 section 枚举（C0 合同，冻结；iOS: AmperfyKit/Storage/HomeSection.swift）
 *
 * rawValue 已对照 iOS HomeSection.swift 声明序核定（2026-07-12）——持久化只存 Int
 * rawValue，**只追加不复用**（对齐 iOS 约定，保证与 iOS 端设置语义一致）。
 */
enum class HomeSection(val rawValue: Int, val displayName: String) {
    RECENTLY_PLAYED_PLAYLISTS(0, "Recently Played Playlists"),   // iOS: lastTimePlayedPlaylists
    RECENTLY_PLAYED_ALBUMS(1, "Recently Played Albums"),
    NEWEST_ALBUMS(2, "Newest Albums"),
    RANDOM_ALBUMS(3, "Random Albums"),
    NEWEST_PODCAST_EPISODES(4, "Newest Podcast Episodes"),
    PODCASTS(5, "Podcasts"),
    RADIOS(6, "Radios"),
    RANDOM_ARTISTS(7, "Random Artists"),
    RANDOM_GENRES(8, "Random Genres"),
    RANDOM_SONGS(9, "Random Songs");

    /** 随机类 section：不落库、进入页面/点击刷新时内存重抽 */
    val isRandom: Boolean
        get() = this == RANDOM_ALBUMS || this == RANDOM_ARTISTS ||
            this == RANDOM_GENRES || this == RANDOM_SONGS

    companion object {
        /** 对齐 iOS defaultValue: [randomAlbums, recentlyPlayedAlbums, lastTimePlayedPlaylists, newestAlbums] */
        val DEFAULT = listOf(RANDOM_ALBUMS, RECENTLY_PLAYED_ALBUMS, RECENTLY_PLAYED_PLAYLISTS, NEWEST_ALBUMS)

        /** 每 section 最多展示条数（对齐 iOS） */
        const val MAX_ITEMS = 20

        fun fromRawValue(v: Int): HomeSection? = entries.firstOrNull { it.rawValue == v }
    }
}
