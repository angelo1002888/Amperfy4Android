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

import com.amperfy.data.remote.ampache.AmpacheAuthHandshake
import com.amperfy.data.remote.ampache.AmpacheLibraryChangeDates

/**
 * handshake 响应解析器（对应 iOS `AuthParserDelegate.swift`）。
 *
 * 响应样例：app/src/test/resources/ampache/handshake.xml。
 * `<auth>` 为空视为握手失败 → [result] 返回 null（AuthParserDelegate.swift:69-70）。
 *
 * `reauthenticateTime = sessionExpire − 5 分钟`（safetyOffsetTimeBeforeSessionExpireInMinutes，
 * AuthParserDelegate.swift:30/45-48）——**提前重握手**，不是等 4701 再补救。
 */
class AmpacheAuthParser : AmpacheXmlParser<AmpacheAuthHandshake?>() {

    /** 服务器 `<api>` 版本串（如 "5.5.6"）；iOS 同样在握手失败时也保留该值 */
    var serverApiVersion: String? = null
        private set

    private val now = System.currentTimeMillis()

    private var token: String = ""
    private var sessionExpireMillis: Long = now
    private var reauthenticateTimeMillis: Long = now
    private var lastUpdateMillis: Long = now
    private var lastAddMillis: Long = now
    private var lastCleanMillis: Long = now
    private var songCount: Int = 0
    private var artistCount: Int = 0
    private var albumCount: Int = 0
    private var genreCount: Int = 0
    private var playlistCount: Int = 0
    private var podcastCount: Int = 0
    private var videoCount: Int = 0

    private var handshake: AmpacheAuthHandshake? = null

    override val result: AmpacheAuthHandshake?
        get() = handshake

    override fun onEndElement(name: String) {
        when (name) {
            "auth" -> token = text.trim()
            "api" -> serverApiVersion = AmpacheXmlValues.nullIfBlank(text)
            "session_expire" -> {
                // 解析失败时回退「当前时刻」——与 iOS `buffer.asIso8601Date ?? Date()` 一致
                sessionExpireMillis = AmpacheXmlValues.parseIso8601Millis(text) ?: now
                reauthenticateTimeMillis = sessionExpireMillis - REAUTH_SAFETY_OFFSET_MILLIS
            }

            "update" -> lastUpdateMillis = AmpacheXmlValues.parseIso8601Millis(text) ?: now
            "add" -> lastAddMillis = AmpacheXmlValues.parseIso8601Millis(text) ?: now
            "clean" -> lastCleanMillis = AmpacheXmlValues.parseIso8601Millis(text) ?: now
            "songs" -> songCount = AmpacheXmlValues.toIntOrZero(text)
            "artists" -> artistCount = AmpacheXmlValues.toIntOrZero(text)
            "albums" -> albumCount = AmpacheXmlValues.toIntOrZero(text)
            "genres" -> genreCount = AmpacheXmlValues.toIntOrZero(text)
            "playlists" -> playlistCount = AmpacheXmlValues.toIntOrZero(text)
            "podcasts" -> podcastCount = AmpacheXmlValues.toIntOrZero(text)
            "videos" -> videoCount = AmpacheXmlValues.toIntOrZero(text)
            "root" -> handshake = if (token.isEmpty()) {
                null
            } else {
                AmpacheAuthHandshake(
                    token = token,
                    sessionExpireMillis = sessionExpireMillis,
                    reauthenticateTimeMillis = reauthenticateTimeMillis,
                    libraryChangeDates = AmpacheLibraryChangeDates(
                        lastUpdateMillis = lastUpdateMillis,
                        lastAddMillis = lastAddMillis,
                        lastCleanMillis = lastCleanMillis,
                    ),
                    songCount = songCount,
                    artistCount = artistCount,
                    albumCount = albumCount,
                    genreCount = genreCount,
                    playlistCount = playlistCount,
                    podcastCount = podcastCount,
                    videoCount = videoCount,
                    serverApiVersion = serverApiVersion,
                )
            }
        }
    }

    private companion object {
        /** 5 分钟安全提前量（iOS safetyOffsetTimeBeforeSessionExpireInMinutes = -5 分钟） */
        const val REAUTH_SAFETY_OFFSET_MILLIS = 5L * 60L * 1000L
    }
}
