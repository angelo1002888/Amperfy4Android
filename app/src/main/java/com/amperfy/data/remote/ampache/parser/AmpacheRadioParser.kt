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

import com.amperfy.data.remote.ampache.AmpacheRadioDto
import org.xml.sax.Attributes

/**
 * live_streams 响应解析器（对应 iOS `RadioParserDelegate.swift`）。
 *
 * 响应样例：xml-responses/live_streams.xml。
 * iOS 消费面（RadioParserDelegate.swift:52-64）：`id` 属性、`<name>`（→ title）、
 * `<url>`（直连流地址）、`<site_url>`（→ siteURL，Android RadioEntity.home_page_url）。
 * `<codec>` `<catalog>` iOS 不消费，此处同。
 */
class AmpacheRadioParser : AmpacheXmlParser<List<AmpacheRadioDto>>() {

    private val radios = mutableListOf<AmpacheRadioDto>()
    private var radioBuffer: RadioBuffer? = null

    override val result: List<AmpacheRadioDto>
        get() = radios.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        if (name == "live_stream") {
            val id = attributes?.getValue("id")
            radioBuffer = if (id != null) RadioBuffer(id) else null
        }
    }

    override fun onEndElement(name: String) {
        val current = radioBuffer
        when (name) {
            "name" -> current?.name = text
            "url" -> current?.url = AmpacheXmlValues.nullIfBlank(text)
            "site_url" -> current?.siteUrl = AmpacheXmlValues.nullIfBlank(text)
            "live_stream" -> {
                incrementParsedCount()
                current?.let {
                    radios.add(
                        AmpacheRadioDto(
                            id = it.id,
                            name = it.name,
                            url = it.url,
                            siteUrl = it.siteUrl,
                        ),
                    )
                }
                radioBuffer = null
            }
        }
    }

    private class RadioBuffer(val id: String) {
        var name: String = ""
        var url: String? = null
        var siteUrl: String? = null
    }
}
