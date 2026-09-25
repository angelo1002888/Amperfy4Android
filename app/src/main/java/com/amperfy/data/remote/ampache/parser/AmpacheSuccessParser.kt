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

import com.amperfy.data.remote.ampache.AmpacheSuccessDto
import org.xml.sax.Attributes

/**
 * 写类 action 的成功响应解析器：`<success code="1"><![CDATA[…]]></success>`。
 *
 * 响应样例：xml-responses/flag.xml、rate.xml、record_play.xml、playlist_delete.xml、
 * playlist_add_song.xml、playlist_remove_song.xml、playlist_edit.xml、
 * podcast_episode_delete.xml。
 *
 * **iOS 没有对应的 delegate**——它对写类响应只跑一遍错误解析（`parseForError`），成功体丢弃。
 * Android 把 code/message 解出来仅供事件日志/调试，不参与任何判定
 * （判定仍是「无 `<error>` 即成功」，与 iOS 同）。
 *
 * 兼容 goodbye.xml 那种把文本包在 `<message>` 子元素里的形态：两种都落到 message。
 */
class AmpacheSuccessParser : AmpacheXmlParser<AmpacheSuccessDto?>() {

    private var code: Int? = null
    private var message: String = ""
    private var nestedMessage: String? = null

    override val result: AmpacheSuccessDto?
        get() = code?.let {
            AmpacheSuccessDto(code = it, message = nestedMessage ?: message)
        }

    override fun onStartElement(name: String, attributes: Attributes?) {
        if (name == "success") {
            code = attributes?.getValue("code")?.trim()?.toIntOrNull() ?: 0
        }
    }

    override fun onEndElement(name: String) {
        when (name) {
            "message" -> if (code != null) nestedMessage = AmpacheXmlValues.nullIfBlank(text)
            "success" -> message = text.trim()
        }
    }
}
