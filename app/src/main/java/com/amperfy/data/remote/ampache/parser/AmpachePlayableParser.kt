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

/**
 * 可播放实体（歌曲 / 播客单集）的公共字段缓冲
 * ——对应 iOS `AbstractPlayable` 上被 `PlayableParserDelegate` 写入的那一组属性。
 */
open class AmpachePlayableBuffer {
    var title: String = ""
    var rating: Int = 0
    var isFavorite: Boolean = false
    var track: Int = 0
    var url: String? = null
    var year: Int = 0
    var duration: Int = 0
    var size: Long = 0
    var bitrate: Int = 0
    var contentType: String? = null
    var disk: String? = null
    var artworkUrl: String? = null
    var artworkInfo: AmpacheArtworkInfo? = null
    var replayGainTrackGain: Float? = null
    var replayGainTrackPeak: Float? = null
    var replayGainAlbumGain: Float? = null
    var replayGainAlbumPeak: Float? = null
}

/**
 * 可播放实体解析器基类（对应 iOS `PlayableParserDelegate.swift`）。
 *
 * iOS 用「delegate 继承 + playableBuffer 指向当前实体」的方式在 Song / PodcastEpisode 之间
 * 复用同一组元素处理（PlayableParserDelegate.swift:43-83）；此处结构 1:1 照搬：
 * 子类在实体开始时把自己的缓冲对象赋给 [playableBuffer]，公共元素由本类落字段。
 *
 * 注意 iOS 的 `<rating>` 语义：它落在 delegate 的临时变量里、在实体闭合时才写进实体并清零。
 * 这里把 rating 直接放进缓冲对象，效果等价（缓冲对象本身按实体创建/丢弃）。
 */
abstract class AmpachePlayableParser<T> : AmpacheXmlParser<T>() {

    /** 当前正在解析的可播放实体缓冲（子类在实体 start 元素里赋值、end 元素里置空） */
    protected var playableBuffer: AmpachePlayableBuffer? = null

    override fun onEndElement(name: String) {
        val playable = playableBuffer ?: return
        when (name) {
            "title" -> playable.title = text
            "rating" -> playable.rating = AmpacheXmlValues.toIntOrZero(text)
            "flag" -> playable.isFavorite = AmpacheXmlValues.flagToBoolean(text)
            "track" -> playable.track = AmpacheXmlValues.toIntOrZero(text)
            "url" -> playable.url = AmpacheXmlValues.nullIfBlank(text)
            "year" -> playable.year = AmpacheXmlValues.toIntOrZero(text)
            "time" -> playable.duration = AmpacheXmlValues.toIntOrZero(text)
            "size" -> playable.size = AmpacheXmlValues.toLongOrZero(text)
            "bitrate" -> playable.bitrate = AmpacheXmlValues.toIntOrZero(text)
            "mime" -> playable.contentType = AmpacheXmlValues.nullIfBlank(text)
            "disk" -> playable.disk = AmpacheXmlValues.nullIfBlank(text)
            "art" -> {
                playable.artworkUrl = AmpacheXmlValues.nullIfBlank(text)
                playable.artworkInfo = AmpacheArtworkInfo.fromUrl(text)
            }

            "replaygain_album_gain" -> playable.replayGainAlbumGain =
                AmpacheXmlValues.toFloatOrNull(text)

            "replaygain_album_peak" -> playable.replayGainAlbumPeak =
                AmpacheXmlValues.toFloatOrNull(text)

            "replaygain_track_gain" -> playable.replayGainTrackGain =
                AmpacheXmlValues.toFloatOrNull(text)

            "replaygain_track_peak" -> playable.replayGainTrackPeak =
                AmpacheXmlValues.toFloatOrNull(text)
        }
    }
}
