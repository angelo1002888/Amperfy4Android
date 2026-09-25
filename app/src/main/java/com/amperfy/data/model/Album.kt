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
 * Domain model for Album
 */
data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val coverArt: String? = null,  // 存储coverArt ID
    val songCount: Int = 0,
    val duration: Int = 0, // in seconds
    val year: Int? = null,
    val genre: String? = null,
    val created: Long? = null,
    val starred: Long? = null,
    val playCount: Int = 0,
    val rating: Int = 0, // 0-5, 0表示未评分

    /**
     * 在服务器 Newest/Recent 列表中的序号（1 起，0 = 不在列表中）
     * 对应 iOS 的 newestIndex/recentIndex，由 getAlbumList2 同步时按返回顺序写入
     */
    val newestIndex: Int = 0,
    val recentIndex: Int = 0,

    /**
     * 是否已缓存（本地存储）
     */
    var isCached: Boolean = false
) {
    // 注：原先这里有一个散装手拼封面 URL 的 getCoverArtUrl(baseUrl, username, password)，
    // 无任何调用方（封面 URL 一律经 SubsonicUrlBuilder / MediaUrlRepository 构建），
    // 且随 MD5 token+salt 认证落地已不合规（硬编码明文 p 与 v=1.16.1），故删除。

    /**
     * 是否已收藏
     */
    val isFavorite: Boolean
        get() = starred != null

    /**
     * 获取副标题信息（艺术家名称）
     */
    fun getSubtitle(): String? {
        return if (artist.isNotBlank()) artist else null
    }
}
