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

import com.amperfy.data.remote.ampache.AmpacheArtistDto
import com.amperfy.data.remote.ampache.AmpacheArtworkInfo
import org.xml.sax.Attributes

/**
 * artist / artists / advanced_search(type=artist) 响应解析器
 * （对应 iOS `ArtistParserDelegate.swift`）。
 *
 * 响应样例：xml-responses/artists.xml、artist.xml、advanced_search-artist.xml。
 * iOS 消费面：`id` 属性、`<name>` `<rating>` `<flag>` `<albumcount>` `<time>` `<genre>` `<art>`
 * （ArtistParserDelegate.swift:57-92）。`<songcount>` 为 Android 侧超集。
 *
 * iOS 里「genre 元素带 id 但本地无此 Genre 时补建 Genre」的分支（:69-78）在 Android
 * 属映射层职责——解析器只把 genreId/genreName 原样带出。
 */
class AmpacheArtistParser : AmpacheXmlParser<List<AmpacheArtistDto>>() {

    private val artists = mutableListOf<AmpacheArtistDto>()
    private var artistBuffer: ArtistBuffer? = null

    override val result: List<AmpacheArtistDto>
        get() = artists.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        when (name) {
            "artist" -> {
                val id = attributes?.getValue("id")
                artistBuffer = if (id != null) ArtistBuffer(id) else null
            }

            "genre" -> artistBuffer?.genreId = attributes?.getValue("id")
        }
    }

    override fun onEndElement(name: String) {
        val current = artistBuffer
        when (name) {
            "name" -> current?.name = text
            "rating" -> current?.rating = AmpacheXmlValues.toIntOrZero(text)
            "flag" -> current?.isFavorite = AmpacheXmlValues.flagToBoolean(text)
            "albumcount" -> current?.albumCount = AmpacheXmlValues.toIntOrZero(text)
            "songcount" -> current?.songCount = AmpacheXmlValues.toIntOrZero(text)
            "time" -> current?.duration = AmpacheXmlValues.toIntOrZero(text)
            "genre" -> current?.genreName = AmpacheXmlValues.nullIfBlank(text)
            "art" -> current?.let {
                it.artworkUrl = AmpacheXmlValues.nullIfBlank(text)
                it.artworkInfo = AmpacheArtworkInfo.fromUrl(text)
            }

            "artist" -> {
                incrementParsedCount()
                current?.let { artists.add(it.build()) }
                artistBuffer = null
            }
        }
    }

    private class ArtistBuffer(val id: String) {
        var name: String = ""
        var albumCount: Int = 0
        var songCount: Int = 0
        var duration: Int = 0
        var rating: Int = 0
        var isFavorite: Boolean = false
        var genreId: String? = null
        var genreName: String? = null
        var artworkUrl: String? = null
        var artworkInfo: AmpacheArtworkInfo? = null

        fun build() = AmpacheArtistDto(
            id = id,
            name = name,
            albumCount = albumCount,
            songCount = songCount,
            duration = duration,
            rating = rating,
            isFavorite = isFavorite,
            genreId = genreId,
            genreName = genreName,
            artworkUrl = artworkUrl,
            artworkInfo = artworkInfo,
        )
    }
}
