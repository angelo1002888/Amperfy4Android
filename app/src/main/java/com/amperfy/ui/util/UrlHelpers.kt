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

package com.amperfy.ui.util

import androidx.compose.runtime.Composable
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.Album
import com.amperfy.data.model.Song
import com.amperfy.data.model.Artist
import com.amperfy.data.repository.MediaUrlRepository
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMediaUrlRepository

/**
 * UI Helper functions for generating URLs
 *
 * Ported from iOS: Similar to iOS's artwork URL generation
 *
 * 注意：提供了两套 API：
 * 1. 传统的函数（需要显式传递 credentialsManager 和 musicRepository）- 用于非 Composable 场景
 * 2. Composable 扩展函数（自动从 CompositionLocal 获取依赖）- 推荐在 Composable 中使用
 */

/**
 * 统一的 coverArt ID 转 URL 函数
 * 将 coverArt ID 转换为完整的 URL
 *
 * @param coverArtId coverArt ID 或已经是完整的 URL
 * @param credentialsManager 凭证管理器
 * @param musicRepository URL 构建仓库
 * @param size 图片尺寸（默认500）
 * @return 完整的 coverArt URL，如果无法生成则返回 null
 */
fun buildCoverArtUrl(
    coverArtId: String?,
    credentialsManager: CredentialsManager,
    musicRepository: MediaUrlRepository,
    size: Int = 500
): String? {
    if (coverArtId.isNullOrBlank()) {
        return null
    }

    // 如果已经是完整URL，直接返回
    if (coverArtId.startsWith("http://") || coverArtId.startsWith("https://")) {
        return coverArtId
    }

    // 从ID构建URL
    val credentials = credentialsManager.getCredentials() ?: return null

    return musicRepository.getCoverArtUrl(
        coverArtId = coverArtId,
        username = credentials.username,
        password = credentials.password,
        baseUrl = credentials.serverUrl
    )
}

// ============================================================================
// Composable 版本 - 推荐在 Composable 函数中使用
// 自动从 CompositionLocal 获取依赖，避免参数层层传递
// ============================================================================

/**
 * Composable 版本: 生成专辑封面 URL
 * 自动从 CompositionLocal 获取依赖
 *
 * 使用示例：
 * ```
 * @Composable
 * fun MyComponent(album: Album) {
 *     val coverUrl = album.getCoverArtUrl()
 *     AsyncImage(model = coverUrl, ...)
 * }
 * ```
 */
@Composable
fun Album.getCoverArtUrl(size: Int = 500): String? {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    return buildCoverArtUrl(this.coverArt, credentialsManager, musicRepository, size)
}

/**
 * Composable 版本: 生成歌曲封面 URL
 * 自动从 CompositionLocal 获取依赖
 */
@Composable
fun Song.getCoverArtUrl(size: Int = 500): String? {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    return buildCoverArtUrl(this.coverArt, credentialsManager, musicRepository, size)
}

/**
 * Composable 版本: 生成艺术家封面 URL
 * 自动从 CompositionLocal 获取依赖
 */
@Composable
fun Artist.getCoverArtUrl(size: Int = 500): String? {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    return buildCoverArtUrl(this.coverArt, credentialsManager, musicRepository, size)
}

/**
 * Composable 版本: 生成歌曲流媒体 URL
 * 自动从 CompositionLocal 获取依赖
 *
 * Equivalent to iOS: BackendAudioPlayer.generateUrl(forStreamingPlayable:)
 */
@Composable
fun Song.getStreamUrl(): String? {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val credentials = credentialsManager.getCredentials() ?: return null

    return musicRepository.getStreamUrl(
        songId = this.id,
        username = credentials.username,
        password = credentials.password,
        baseUrl = credentials.serverUrl
    )
}

// ============================================================================
// 传统版本 - 保留用于非 Composable 场景（如 ViewModel）
// ============================================================================

/**
 * Generate full cover art URL for an Album
 * Equivalent to iOS: album.artwork.url
 *
 * 注意：这是传统版本，需要显式传递参数
 * 在 Composable 中推荐使用 album.getCoverArtUrl() 代替
 */
fun Album.getFullCoverArtUrl(
    credentialsManager: CredentialsManager,
    musicRepository: MediaUrlRepository
): String? {
    return buildCoverArtUrl(this.coverArt, credentialsManager, musicRepository)
}

/**
 * Generate full cover art URL for a Song
 * Equivalent to iOS: song.artwork.url
 *
 * 注意：这是传统版本，需要显式传递参数
 * 在 Composable 中推荐使用 song.getCoverArtUrl() 代替
 */
fun Song.getFullCoverArtUrl(
    credentialsManager: CredentialsManager,
    musicRepository: MediaUrlRepository
): String? {
    return buildCoverArtUrl(this.coverArt, credentialsManager, musicRepository)
}

/**
 * Generate full cover art URL for an Artist
 * Equivalent to iOS: artist.artwork.url
 *
 * 注意：这是传统版本，需要显式传递参数
 * 在 Composable 中推荐使用 artist.getCoverArtUrl() 代替
 */
fun Artist.getFullCoverArtUrl(
    credentialsManager: CredentialsManager,
    musicRepository: MediaUrlRepository
): String? {
    return buildCoverArtUrl(this.coverArt, credentialsManager, musicRepository)
}

/**
 * Generate stream URL for a Song
 * Equivalent to iOS: BackendAudioPlayer.generateUrl(forStreamingPlayable:)
 *
 * 注意：这是传统版本，需要显式传递参数
 * 在 Composable 中推荐使用 song.getStreamUrl() 代替
 */
fun Song.getStreamUrl(
    credentialsManager: CredentialsManager,
    musicRepository: MediaUrlRepository
): String? {
    val credentials = credentialsManager.getCredentials() ?: return null

    return musicRepository.getStreamUrl(
        songId = this.id,
        username = credentials.username,
        password = credentials.password,
        baseUrl = credentials.serverUrl
    )
}
