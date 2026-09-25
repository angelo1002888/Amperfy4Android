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

package com.amperfy.data.remote.ampache.parser

/**
 * playlist_songs 响应解析器（对应 iOS `PlaylistSongsParserDelegate.swift`）。
 *
 * 响应体就是一串 `<song>`（样例 xml-responses/playlist_songs.xml 为空列表形态），
 * **出现顺序即播放列表内的曲目顺序**——iOS 用 parsedCount 当下标逐条比对/落位
 * （PlaylistSongsParserDelegate.swift:36-52），Android 侧「按顺序整表替换」由 Batch 2 的
 * `replacePlaylistSongs` 事务承担，故解析器只需保持顺序，不必自己算下标。
 *
 * [collectionDurationSeconds] 对应 iOS `collectionDuration`（写进 playlist.remoteDuration）。
 * iOS 另有 `isCollectionCached`（全部歌曲都已本地缓存）——那是本地缓存状态、不来自 XML，
 * Android 侧由 DAO 的 `HAVING COUNT(*)=COUNT(cache_path)` 推导，此处不移植。
 */
class AmpachePlaylistSongsParser : AmpacheSongParser() {

    /** 曲目时长合计（秒）；无歌曲时为 0，与 iOS `parsedCount > 0 ? duration : 0` 等价 */
    val collectionDurationSeconds: Int
        get() = result.sumOf { it.duration }
}
