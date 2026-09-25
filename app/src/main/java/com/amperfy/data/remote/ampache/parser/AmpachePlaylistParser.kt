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

import com.amperfy.data.remote.ampache.AmpacheArtworkInfo
import com.amperfy.data.remote.ampache.AmpachePlaylistDto
import org.xml.sax.Attributes

/**
 * playlists / playlist / playlist_create 响应解析器
 * （对应 iOS `PlaylistParserDelegate.swift`）。
 *
 * 响应样例：xml-responses/playlists.xml、playlist.xml、playlist_create.xml
 * （三者结构相同——playlist_create 直接回吐新建的 playlist，其 `id` 就是服务器分配的 id，
 * iOS 正是靠它回填本地播放列表 id，AmpacheLibrarySyncer.swift:1164-1177）。
 *
 * iOS 消费面（PlaylistParserDelegate.swift:92-96）：`id` 属性、`<name>`、`<items>`（歌曲数）。
 * Android 超集：`<owner>` `<type>` `<flag>` `<rating>` `<art>`——PlaylistEntity 有
 * owner / is_public / cover_art 列。
 *
 * iOS 在 `</root>` 做「服务器已删播放列表 → 本地删除」的差集清理，属 Batch 2 Repository 职责。
 */
class AmpachePlaylistParser : AmpacheXmlParser<List<AmpachePlaylistDto>>() {

    private val playlists = mutableListOf<AmpachePlaylistDto>()
    private var playlistBuffer: PlaylistBuffer? = null

    override val result: List<AmpachePlaylistDto>
        get() = playlists.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        if (name == "playlist") {
            // id 为空串的 playlist 与缺 id 等价丢弃（iOS 同样只在 id 非空时建对象）
            val id = attributes?.getValue("id")?.takeIf { it.isNotEmpty() }
            playlistBuffer = if (id != null) PlaylistBuffer(id) else null
        }
    }

    override fun onEndElement(name: String) {
        val current = playlistBuffer
        when (name) {
            "name" -> current?.name = text
            "owner" -> current?.owner = AmpacheXmlValues.nullIfBlank(text)
            "items" -> current?.songCount = AmpacheXmlValues.toIntOrZero(text)
            "type" -> current?.type = AmpacheXmlValues.nullIfBlank(text)
            "rating" -> current?.rating = AmpacheXmlValues.toIntOrZero(text)
            "flag" -> current?.isFavorite = AmpacheXmlValues.flagToBoolean(text)
            "art" -> current?.let {
                it.artworkUrl = AmpacheXmlValues.nullIfBlank(text)
                it.artworkInfo = AmpacheArtworkInfo.fromUrl(text)
            }

            "playlist" -> {
                incrementParsedCount()
                current?.let { playlists.add(it.build()) }
                playlistBuffer = null
            }
        }
    }

    private class PlaylistBuffer(val id: String) {
        var name: String = ""
        var owner: String? = null
        var songCount: Int = 0
        var type: String? = null
        var rating: Int = 0
        var isFavorite: Boolean = false
        var artworkUrl: String? = null
        var artworkInfo: AmpacheArtworkInfo? = null

        fun build() = AmpachePlaylistDto(
            id = id,
            name = name,
            owner = owner,
            songCount = songCount,
            type = type,
            rating = rating,
            isFavorite = isFavorite,
            artworkUrl = artworkUrl,
            artworkInfo = artworkInfo,
        )
    }
}
