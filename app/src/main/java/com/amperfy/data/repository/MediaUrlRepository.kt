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

package com.amperfy.data.repository

/**
 * 媒体 URL 构建域仓库：生成带认证的流媒体 URL、封面 URL 与下载 URL。
 * 对应 iOS: SubsonicServerApi / AmpacheXmlServerApi 的 generateUrl(forStreaming:) /
 * generateUrl(forArtwork:) / generateUrl(forDownloading:)。
 * 出处：Repository 拆分批次 1；下载与播客两方法为 Ampache 移植 Batch 2 追加。
 *
 * 注：这几个方法显式接收 username/password/baseUrl 是刻意设计——PlayerManager / DownloadManager
 * 按 playable.accountId 经 AccountComponentsRegistry fail-closed 取该账户凭证后构建 URL
 * （其余 sync/search 方法的凭证一律由 Repository 内部 currentCredentials() 解析）。
 *
 * **Ampache 的会话 token 时效性**：Ampache 实现拼 URL 时用的是内存中当前 token
 * （可能已过期/为空，因为这几个方法都不是 suspend）；真正发请求的两条链在装载时刻换新——
 * ExoPlayer 经 `ResolvingDataSource`、Coil 经 `AmpacheArtworkAuthInterceptor`，
 * 二者共用 [com.amperfy.core.AmpacheUrlAuthRefresher]。下载路径本就在协程内，
 * 故 [getDownloadUrl] 直接是 suspend，由 Ampache 实现内部保证会话有效。
 */
interface MediaUrlRepository {
    fun getStreamUrl(songId: String, username: String, password: String, baseUrl: String): String

    /**
     * 播客单集的流媒体 URL。
     *
     * Subsonic 与歌曲同端点（实现体与 [getStreamUrl] 相同，调用方传 `streamId ?: id`）；
     * Ampache 必须区分（`type=podcast_episode`），故单立一个方法。
     *
     * @param episodeApiId 请求用 id——Subsonic 侧为 `streamId ?: id`
     *   （iOS SubsonicApi.swift:79），Ampache 侧即单集 id
     */
    fun getPodcastEpisodeStreamUrl(
        episodeApiId: String,
        username: String,
        password: String,
        baseUrl: String
    ): String

    fun getCoverArtUrl(coverArtId: String, username: String, password: String, baseUrl: String): String

    /**
     * 下载（缓存）URL——按 Cache Format 偏好选端点/格式。
     *
     * 语义按后端各自的 iOS 对应物：
     * - Subsonic（iOS SubsonicServerApi.generateUrl(forDownloadingPlayableId:)）：
     *   raw → download 端点；mp3 → stream + format=mp3；serverConfig → stream 不带 format；
     * - Ampache（iOS AmpacheXmlServerApi.swift:753-767）：一律 download 端点，
     *   mp3 → format=mp3，其余 → format=raw。
     *
     * suspend：Ampache 实现需要在拼 URL 前确保会话有效（可能触发一次握手）。
     *
     * @param apiId 请求用 id（单集为 `streamId ?: id`，歌曲即 id）
     */
    suspend fun getDownloadUrl(
        apiId: String,
        isPodcastEpisode: Boolean,
        transcoding: com.amperfy.data.local.CacheTranscodingFormatPreference,
        username: String,
        password: String,
        baseUrl: String
    ): String
}
