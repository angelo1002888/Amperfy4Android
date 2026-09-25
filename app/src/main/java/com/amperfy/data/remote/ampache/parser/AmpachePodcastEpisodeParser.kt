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

import com.amperfy.data.remote.ampache.AmpachePodcastEpisodeDto
import com.amperfy.data.remote.ampache.AmpachePodcastEpisodeRemoteStatus
import org.xml.sax.Attributes

/**
 * podcast_episodes 响应解析器
 * （对应 iOS `PodcastEpisodeParserDelegate.swift` + 其基类 `PlayableParserDelegate`）。
 *
 * 响应样例：xml-responses/podcast_episodes.xml。
 * iOS 消费面（PodcastEpisodeParserDelegate.swift:70-117）：`id` 属性、`<description>`
 * `<pubdate>` `<state>` `<filelength>` `<filesize>` `<art>`，其余（title/time/size/mime/url/
 * rating/flag/bitrate）走可播放基类。
 *
 * 时长/大小写两遍：`<filelength>`（"00:12:25"）与 `<filesize>`（"17.33 MB"）在前、
 * `<time>`（745）与 `<size>`（18170164）在后，**后者覆盖前者**——与 iOS 的回调顺序一致，
 * 实际生效的是精确值（见 AmpachePodcastEpisodeDto 注释）。
 *
 * 所属播客 id 不在单集元素里（iOS 由 delegate 的构造参数带入），Android 由调用方
 * （请求 podcast_episodes 时已知 filter=<podcastId>）在 Batch 2 映射时补。
 */
class AmpachePodcastEpisodeParser :
    AmpachePlayableParser<List<AmpachePodcastEpisodeDto>>() {

    private val episodes = mutableListOf<AmpachePodcastEpisodeDto>()
    private var episodeBuffer: EpisodeBuffer? = null

    override val result: List<AmpachePodcastEpisodeDto>
        get() = episodes.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        if (name == "podcast_episode") {
            val id = attributes?.getValue("id")
            val created = if (id != null) EpisodeBuffer(id) else null
            episodeBuffer = created
            playableBuffer = created
        }
    }

    override fun onEndElement(name: String) {
        super.onEndElement(name)
        val current = episodeBuffer
        when (name) {
            "description" -> current?.description = text
            "pubdate" -> AmpacheXmlValues.parsePodcastPubDateMillis(text)?.let {
                current?.publishDateMillis = it
            }

            "state" -> current?.status = AmpachePodcastEpisodeRemoteStatus.from(text.trim())
            "filelength" -> AmpacheXmlValues.parseDurationInSeconds(text)?.let {
                current?.duration = it
            }

            "filesize" -> AmpacheXmlValues.parseByteCount(text)?.let { current?.size = it }
            "podcast_episode" -> {
                incrementParsedCount()
                current?.let { episodes.add(it.build()) }
                episodeBuffer = null
                playableBuffer = null
            }
        }
    }

    private class EpisodeBuffer(val id: String) : AmpachePlayableBuffer() {
        var description: String? = null
        var publishDateMillis: Long = 0
        var status: AmpachePodcastEpisodeRemoteStatus =
            AmpachePodcastEpisodeRemoteStatus.UNDEFINED

        fun build() = AmpachePodcastEpisodeDto(
            id = id,
            title = title,
            description = description,
            publishDateMillis = publishDateMillis,
            status = status,
            duration = duration,
            size = size,
            bitrate = bitrate,
            contentType = contentType,
            url = url,
            rating = rating,
            isFavorite = isFavorite,
            artworkUrl = artworkUrl,
            artworkInfo = artworkInfo,
        )
    }
}
