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
 * 歌词模型 - 对应 iOS: LyricsList / StructuredLyrics / LyricsLine
 * （AmperfyKit/Api/BackendApi.swift:94-133）
 */
data class LyricsList(
    val lyrics: List<StructuredLyrics> = emptyList()
) {
    /**
     * 优先取第一个 synced 歌词块，否则取第一个（unsynced 兜底）
     * 对应 iOS: getFirstSyncedLyricsOrUnsyncedAsDefault()
     */
    fun getFirstSyncedLyricsOrUnsyncedAsDefault(): StructuredLyrics? =
        lyrics.firstOrNull { it.synced } ?: lyrics.firstOrNull()
}

data class StructuredLyrics(
    val lang: String = "",
    val synced: Boolean = false,
    val line: List<LyricsLine> = emptyList(),
    val displayArtist: String? = null,
    val displayTitle: String? = null,
    val offset: Int = 0   // 毫秒；iOS 解析存储但滚动逻辑未应用，Android 同
)

data class LyricsLine(
    val start: Long? = null,   // 毫秒（相对曲目开头）；unsynced 时为 null
    val value: String = ""
) {
    /** 对应 iOS LyricsLine.startTime（start == nil 时视为 0） */
    val startTimeMs: Long
        get() = start ?: 0L
}
