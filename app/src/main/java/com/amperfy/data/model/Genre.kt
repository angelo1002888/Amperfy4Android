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
 * 流派领域模型 - 对应 iOS: Genre（AmperfyKit/Storage/EntityWrappers/Genre.swift）
 *
 * Subsonic 流派以 name 为标识（无服务器 id）
 */
data class Genre(
    val name: String,
    val albumCount: Int = 0,
    val songCount: Int = 0
) {
    /**
     * 行信息文本 - 对应 iOS Genre.infoDetails（Subsonic 无 Artist 计数段）：
     * "X Album(s) · Y Song(s)"，单复数与 iOS 逐字一致
     */
    val info: String
        get() {
            val albums = if (albumCount == 1) "1 Album" else "$albumCount Albums"
            val songs = if (songCount == 1) "1 Song" else "$songCount Songs"
            return "$albums · $songs"
        }
}
