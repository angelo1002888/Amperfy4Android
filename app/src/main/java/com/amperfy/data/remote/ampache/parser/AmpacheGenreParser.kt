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

import com.amperfy.data.remote.ampache.AmpacheGenreDto
import org.xml.sax.Attributes

/**
 * genres 响应解析器（对应 iOS `GenreParserDelegate.swift`）。
 *
 * 响应样例：app/src/test/resources/ampache/genres.xml。
 * iOS 只取 `id` + `<name>`；此处**超集**解析 `<albums>` / `<artists>` / `<songs>` 三个计数——
 * Android `GenreEntity` 有 album_count / song_count 两列（Subsonic 侧由 getGenres 直接给），
 * 不在此解析就得让 Batch 2 额外扇出查询。
 */
class AmpacheGenreParser : AmpacheXmlParser<List<AmpacheGenreDto>>() {

    private val genres = mutableListOf<AmpacheGenreDto>()
    private var genreId: String? = null
    private var genreName: String = ""
    private var albumCount: Int = 0
    private var artistCount: Int = 0
    private var songCount: Int = 0

    override val result: List<AmpacheGenreDto>
        get() = genres.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        if (name == "genre") {
            genreId = attributes?.getValue("id")
            genreName = ""
            albumCount = 0
            artistCount = 0
            songCount = 0
        }
    }

    override fun onEndElement(name: String) {
        if (genreId == null) return
        when (name) {
            "name" -> genreName = text
            "albums" -> albumCount = AmpacheXmlValues.toIntOrZero(text)
            "artists" -> artistCount = AmpacheXmlValues.toIntOrZero(text)
            "songs" -> songCount = AmpacheXmlValues.toIntOrZero(text)
            "genre" -> {
                incrementParsedCount()
                genres.add(
                    AmpacheGenreDto(
                        id = genreId!!,
                        name = genreName,
                        albumCount = albumCount,
                        artistCount = artistCount,
                        songCount = songCount,
                    ),
                )
                genreId = null
            }
        }
    }
}
