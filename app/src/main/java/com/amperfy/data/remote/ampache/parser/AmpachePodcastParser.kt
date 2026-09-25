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
import com.amperfy.data.remote.ampache.AmpachePodcastDto
import org.xml.sax.Attributes

/**
 * podcasts 响应解析器（对应 iOS `PodcastParserDelegate.swift`）。
 *
 * 响应样例：xml-responses/podcasts.xml。
 * iOS 消费面（PodcastParserDelegate.swift:53-69）：`id` 属性、`<name>`（→ titleRawParsed）、
 * `<description>`（→ depictionRawParsed）、`<rating>`、`<art>`。
 *
 * `<name>` / `<description>` 是 HTML 转义原文（如 `&mdash;` `&#039;`）——iOS 在
 * performPostParseOperations 里用 `html2String` 反转义；Android 侧延后到 Batch 2 映射层
 * （反转义需 `android.text.Html`，属 framework 类，放解析层会让纯 JVM 单测失效）。
 */
class AmpachePodcastParser : AmpacheXmlParser<List<AmpachePodcastDto>>() {

    private val podcasts = mutableListOf<AmpachePodcastDto>()
    private var podcastBuffer: PodcastBuffer? = null

    override val result: List<AmpachePodcastDto>
        get() = podcasts.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        if (name == "podcast") {
            val id = attributes?.getValue("id")
            podcastBuffer = if (id != null) PodcastBuffer(id) else null
        }
    }

    override fun onEndElement(name: String) {
        val current = podcastBuffer
        when (name) {
            "name" -> current?.title = text
            "description" -> current?.description = text
            "rating" -> current?.rating = AmpacheXmlValues.toIntOrZero(text)
            "art" -> current?.let {
                it.artworkUrl = AmpacheXmlValues.nullIfBlank(text)
                it.artworkInfo = AmpacheArtworkInfo.fromUrl(text)
            }

            "podcast" -> {
                incrementParsedCount()
                current?.let { podcasts.add(it.build()) }
                podcastBuffer = null
            }
        }
    }

    private class PodcastBuffer(val id: String) {
        var title: String = ""
        var description: String = ""
        var rating: Int = 0
        var artworkUrl: String? = null
        var artworkInfo: AmpacheArtworkInfo? = null

        fun build() = AmpachePodcastDto(
            id = id,
            title = title,
            description = description,
            rating = rating,
            artworkUrl = artworkUrl,
            artworkInfo = artworkInfo,
        )
    }
}
