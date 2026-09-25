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
import com.amperfy.data.remote.ampache.AmpacheIdsDto
import org.xml.sax.Attributes

/**
 * 通用 id 抓取解析器（对应 iOS `IDsParserDelegate.swift`，2.1.0 新增）。
 *
 * 只看元素的 `id` 属性与 `<art>` URL，把一份响应里出现过的所有实体 id 收成集合。
 * iOS 用它在正式解析前批量预取 CoreData 对象（避免逐条 fetch 的性能塌陷）；
 * Android 走 Room upsert，**没有预取需求**——保留本解析器是为结构对齐，并给 Batch 2 的
 * 「服务器已删实体 → 本地差集清理」提供现成的 id 快照。
 *
 * 注意 iOS 对 `<art>` 的处理在 didEndElement（用元素文本反提取 object_id/object_type），
 * 其余在 didStartElement（读 id 属性），此处同。
 */
class AmpacheIdsParser : AmpacheXmlParser<AmpacheIdsDto>() {

    private val genreIds = mutableSetOf<String>()
    private val catalogIds = mutableSetOf<String>()
    private val artistIds = mutableSetOf<String>()
    private val albumIds = mutableSetOf<String>()
    private val songIds = mutableSetOf<String>()
    private val podcastIds = mutableSetOf<String>()
    private val podcastEpisodeIds = mutableSetOf<String>()
    private val radioIds = mutableSetOf<String>()
    private val artworkInfos = mutableSetOf<AmpacheArtworkInfo>()

    override val result: AmpacheIdsDto
        get() = AmpacheIdsDto(
            genreIds = genreIds.toSet(),
            catalogIds = catalogIds.toSet(),
            artistIds = artistIds.toSet(),
            albumIds = albumIds.toSet(),
            songIds = songIds.toSet(),
            podcastIds = podcastIds.toSet(),
            podcastEpisodeIds = podcastEpisodeIds.toSet(),
            radioIds = radioIds.toSet(),
            artworkInfos = artworkInfos.toSet(),
        )

    override fun onStartElement(name: String, attributes: Attributes?) {
        val id = attributes?.getValue("id") ?: return
        when (name) {
            "genre" -> genreIds.add(id)
            "catalog" -> catalogIds.add(id)
            "artist" -> artistIds.add(id)
            "album" -> albumIds.add(id)
            "song" -> songIds.add(id)
            "podcast_episode" -> podcastEpisodeIds.add(id)
            "live_stream" -> radioIds.add(id)
            "podcast" -> podcastIds.add(id)
        }
    }

    override fun onEndElement(name: String) {
        if (name == "art") {
            AmpacheArtworkInfo.fromUrl(text)?.let { artworkInfos.add(it) }
        }
    }
}
