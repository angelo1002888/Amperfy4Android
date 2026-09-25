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

import com.amperfy.data.remote.ampache.AmpacheSongDto
import org.xml.sax.Attributes

/**
 * song / album_songs / artist_songs / search_songs / playlist_generate / advanced_search(type=song)
 * 响应解析器（对应 iOS `SongParserDelegate.swift` + 其基类 `PlayableParserDelegate`）。
 *
 * 响应样例：xml-responses/song.xml、album_songs.xml、artist_songs.xml、search_songs.xml、
 * playlist_generate-song.xml、advanced_search-song.xml。
 *
 * iOS 消费面（SongParserDelegate.swift:34-136 + PlayableParserDelegate.swift:43-83）：
 * `id` 属性、`<artist id>` `<album id>` `<genre id>` 三个「带 id 的引用元素」，
 * 加上 title/track/url/year/time/size/bitrate/mime/disk/rating/flag/art/replaygain_×4。
 *
 * Android 超集：`<albumartist id>`（SongEntity.album_artist_id 列）与 `<filename>`
 * （SongEntity.path 列）——iOS 的 Song 实体没有这两列，Android 有。
 */
open class AmpacheSongParser : AmpachePlayableParser<List<AmpacheSongDto>>() {

    private val songs = mutableListOf<AmpacheSongDto>()
    private var songBuffer: SongBuffer? = null

    override val result: List<AmpacheSongDto>
        get() = songs.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        when (name) {
            "song" -> {
                val id = attributes?.getValue("id")
                val created = if (id != null) SongBuffer(id) else null
                songBuffer = created
                playableBuffer = created
            }

            "artist" -> songBuffer?.artistId = attributes?.getValue("id")
            "album" -> songBuffer?.albumId = attributes?.getValue("id")
            "albumartist" -> songBuffer?.albumArtistId = attributes?.getValue("id")
            "genre" -> songBuffer?.genreId = attributes?.getValue("id")
        }
    }

    override fun onEndElement(name: String) {
        // 公共可播放字段先由基类落定（元素名与本类处理的集合不相交）
        super.onEndElement(name)
        val current = songBuffer
        when (name) {
            "artist" -> current?.artistName = AmpacheXmlValues.nullIfBlank(text)
            "album" -> current?.albumName = AmpacheXmlValues.nullIfBlank(text)
            "albumartist" -> current?.albumArtistName = AmpacheXmlValues.nullIfBlank(text)
            "genre" -> current?.genreName = AmpacheXmlValues.nullIfBlank(text)
            "filename" -> current?.filename = AmpacheXmlValues.nullIfBlank(text)
            "song" -> {
                incrementParsedCount()
                current?.let { songs.add(it.build()) }
                songBuffer = null
                playableBuffer = null
            }
        }
    }

    private class SongBuffer(val id: String) : AmpachePlayableBuffer() {
        var artistId: String? = null
        var artistName: String? = null
        var albumId: String? = null
        var albumName: String? = null
        var albumArtistId: String? = null
        var albumArtistName: String? = null
        var genreId: String? = null
        var genreName: String? = null
        var filename: String? = null

        fun build() = AmpacheSongDto(
            id = id,
            title = title,
            artistId = artistId,
            artistName = artistName,
            albumId = albumId,
            albumName = albumName,
            albumArtistId = albumArtistId,
            albumArtistName = albumArtistName,
            genreId = genreId,
            genreName = genreName,
            track = track,
            disk = disk,
            year = year,
            duration = duration,
            size = size,
            bitrate = bitrate,
            contentType = contentType,
            url = url,
            filename = filename,
            rating = rating,
            isFavorite = isFavorite,
            artworkUrl = artworkUrl,
            artworkInfo = artworkInfo,
            replayGainTrackGain = replayGainTrackGain,
            replayGainTrackPeak = replayGainTrackPeak,
            replayGainAlbumGain = replayGainAlbumGain,
            replayGainAlbumPeak = replayGainAlbumPeak,
        )
    }
}
