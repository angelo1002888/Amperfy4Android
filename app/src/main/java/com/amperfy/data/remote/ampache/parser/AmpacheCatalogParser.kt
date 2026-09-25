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

import com.amperfy.data.remote.ampache.AmpacheCatalogDto
import org.xml.sax.Attributes

/**
 * catalogs 响应解析器（对应 iOS `CatalogParserDelegate.swift` → MusicFolder）。
 *
 * 响应样例：app/src/test/resources/ampache/catalogs.xml。
 * iOS 只消费 `id` 属性与 `<name>`（其余 type/path/last_* 字段一概不解析），此处同。
 *
 * iOS 在 `</root>` 里做「服务器已删目录 → 本地删除」的差集清理；Android 的差集清理属
 * Repository 层职责（Batch 2），解析器只出数据。
 */
class AmpacheCatalogParser : AmpacheXmlParser<List<AmpacheCatalogDto>>() {

    private val catalogs = mutableListOf<AmpacheCatalogDto>()
    private var catalogId: String? = null
    private var catalogName: String = ""

    override val result: List<AmpacheCatalogDto>
        get() = catalogs.toList()

    override fun onStartElement(name: String, attributes: Attributes?) {
        if (name == "catalog") {
            // id 缺失的 catalog 直接丢弃（iOS 记一条 error 日志后 return）
            catalogId = attributes?.getValue("id")
            catalogName = ""
        }
    }

    override fun onEndElement(name: String) {
        when (name) {
            "name" -> if (catalogId != null) catalogName = text
            "catalog" -> {
                incrementParsedCount()
                catalogId?.let { catalogs.add(AmpacheCatalogDto(id = it, name = catalogName)) }
                catalogId = null
                catalogName = ""
            }
        }
    }
}
