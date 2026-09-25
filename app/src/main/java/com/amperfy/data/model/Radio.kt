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

package com.amperfy.data.model

/**
 * 电台领域模型 - 对应 iOS: Radio（AmperfyKit/Storage/EntityWrappers/Radio.swift）
 *
 * - creatorName 为空串（iOS Radio.swift:44-46，列表行副标题留空）
 * - 不可评分/收藏/下载/加播放列表（iOS AbstractPlayable 对 radio 的各项排除）
 */
data class Radio(
    val id: String,
    val title: String,
    val streamUrl: String,
    val siteUrl: String? = null
)

/**
 * 转为可播放对象：原始 streamUrl 直接播放（iOS insertStreamPlayable 对 radio 用 radio.url，
 * 不经 generateUrl 服务器地址生成），isRadio=true 使 Scrobble/自动缓存短路
 */
fun Radio.toPlayable(): Playable = Playable(
    id = id,
    title = title,
    artist = "",          // iOS creatorName = ""
    album = "",
    duration = 0,         // 直播流无时长（iOS 单元格对 radio 隐藏时长）
    coverArt = null,
    streamUrl = streamUrl,
    isDownloaded = false,
    isRadio = true
)
