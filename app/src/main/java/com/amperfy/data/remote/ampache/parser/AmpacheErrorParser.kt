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

import com.amperfy.data.remote.ampache.AmpacheResponseError

/**
 * 只解析 `<error>` 节点的解析器（对应 iOS `AmpacheXmlParser` 被直接当 delegate 用的场景：
 * `AmpacheXmlServerApi.checkForErrorResponse`，AmpacheXmlServerApi.swift:820-831；
 * 以及 `AmpacheLibrarySyncer.parseForError`）。
 *
 * 错误分支的实现在基类 [AmpacheXmlParser]（每个响应都可能是错误体），本类只把它暴露为 result。
 * 写类 action（flag/rate/record_play/playlist_*）在 iOS 侧就只跑这一遍，不看成功体。
 */
class AmpacheErrorParser : AmpacheXmlParser<AmpacheResponseError?>() {
    override val result: AmpacheResponseError?
        get() = error
}
