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

import com.amperfy.data.remote.ampache.AmpacheAlbumDto
import com.amperfy.data.remote.ampache.AmpacheArtworkInfo
import org.xml.sax.Attributes

/**
 * album / albums / artist_albums / stats(type=album) / advanced_search(type=album) 响应解析器
 * （对应 iOS `AlbumParserDelegate.swift`）。
 *
 * 响应样例：xml-responses/album.xml、albums.xml、artist_albums.xml、stats-album.xml、
 * advanced_search-album.xml。
 *
 * iOS 消费面（AlbumParserDelegate.swift:73-120）：`id` 属性、`<name>` `<artist id>`
 * `<rating>` `<flag>` `<year>` `<time>` `<songcount>` `<art>` `<genre id>`。
 * 一个 album 下可能出现**多个 `<genre>`**（见 artist_albums.xml），与 iOS 一致取最后一个。
 */
class AmpacheAlbumParser : AmpacheXmlParser<List<AmpacheAlbumDto>>() {

    private val albums = mutableListOf<AmpacheAlbumDto>()
    private var albumBuffer: AlbumBuffer? = null

    override val result: List<AmpacheAlbumDto>
        get() = albums.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        when (name) {
            "album" -> {
                val id = attributes?.getValue("id")
                albumBuffer = if (id != null) AlbumBuffer(id) else null
            }

            "artist" -> albumBuffer?.artistId = attributes?.getValue("id")
            "genre" -> albumBuffer?.genreId = attributes?.getValue("id")
        }
    }

    override fun onEndElement(name: String) {
        val current = albumBuffer
        when (name) {
            "name" -> current?.name = text
            "artist" -> current?.artistName = AmpacheXmlValues.nullIfBlank(text)
            "genre" -> current?.genreName = AmpacheXmlValues.nullIfBlank(text)
            "rating" -> current?.rating = AmpacheXmlValues.toIntOrZero(text)
            "flag" -> current?.isFavorite = AmpacheXmlValues.flagToBoolean(text)
            "year" -> current?.year = AmpacheXmlValues.toIntOrZero(text)
            "time" -> current?.duration = AmpacheXmlValues.toIntOrZero(text)
            "songcount" -> current?.songCount = AmpacheXmlValues.toIntOrZero(text)
            "art" -> current?.let {
                it.artworkUrl = AmpacheXmlValues.nullIfBlank(text)
                it.artworkInfo = AmpacheArtworkInfo.fromUrl(text)
            }

            "album" -> {
                incrementParsedCount()
                current?.let { albums.add(it.build()) }
                albumBuffer = null
            }
        }
    }

    private class AlbumBuffer(val id: String) {
        var name: String = ""
        var artistId: String? = null
        var artistName: String? = null
        var year: Int = 0
        var duration: Int = 0
        var songCount: Int = 0
        var rating: Int = 0
        var isFavorite: Boolean = false
        var genreId: String? = null
        var genreName: String? = null
        var artworkUrl: String? = null
        var artworkInfo: AmpacheArtworkInfo? = null

        fun build() = AmpacheAlbumDto(
            id = id,
            name = name,
            artistId = artistId,
            artistName = artistName,
            year = year,
            duration = duration,
            songCount = songCount,
            rating = rating,
            isFavorite = isFavorite,
            genreId = genreId,
            genreName = genreName,
            artworkUrl = artworkUrl,
            artworkInfo = artworkInfo,
        )
    }
}
