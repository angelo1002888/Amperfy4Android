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

package com.amperfy.data.remote.ampache

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AmpacheApi] 里两个纯函数的合同（不发任何网络请求）：日志脱敏与 `<art>` URL 反提取。
 *
 * 脱敏出处：iOS `AmpacheXmlServerApi.cleanse`（AmpacheXmlServerApi.swift:220-244）——
 * host → SERVERURL、去端口、`auth`/`ssid`/`user` 三个参数打码。
 * 这条链路是「事件日志里不得出现会话 token / 用户名」的唯一保证，故单测锁死。
 */
class AmpacheApiUrlTest {

    private val api: AmpacheApi = AmpacheApi(
        okHttpClient = OkHttpClient(),
        credentialsProvider = { null },
        authSession = AmpacheAuthSession(OkHttpClient()) { null },
    )

    @Test
    fun cleanse_redactsHostPortAuthSsidAndUser() {
        val cleansed = api.cleanse(
            "https://music.example.com:4533/server/xml.server.php" +
                "?auth=5f8fdddd612635070f90f833b8bd5ff9&action=albums&user=joe&ssid=xyz&offset=0",
        )
        assertEquals(
            "https://SERVERURL/server/xml.server.php" +
                "?auth=AUTH&action=albums&user=USER&ssid=SSID&offset=0",
            cleansed,
        )
    }

    @Test
    fun cleanse_keepsSubPathAndBusinessParams() {
        val cleansed = api.cleanse(
            "http://host/ampache/image.php?auth=token&object_id=12&object_type=album",
        )
        assertEquals(
            "http://SERVERURL/ampache/image.php?auth=AUTH&object_id=12&object_type=album",
            cleansed,
        )
    }

    @Test
    fun cleanse_returnsEmptyForNullOrInvalidUrl() {
        assertEquals("", api.cleanse(null))
        assertEquals("", api.cleanse("not-a-url"))
    }

    @Test
    fun extractArtworkInfoFromUrl_readsObjectIdAndType() {
        val info = api.extractArtworkInfoFromUrl(
            "https://music.com.au/image.php?object_id=10&object_type=album&id=457&name=art.png",
        )
        assertEquals(AmpacheArtworkInfo("10", "album"), info)
        assertNull(api.extractArtworkInfoFromUrl("https://music.com.au/image.php?name=art.png"))
    }
}
