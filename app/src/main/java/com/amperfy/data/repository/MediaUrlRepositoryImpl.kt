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

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.*
import com.amperfy.data.remote.SubsonicApi
import com.amperfy.data.remote.SubsonicUrlBuilder

/**
 * 媒体 URL 构建域实现：生成带认证的流媒体 URL 与封面 URL。
 * 方法体自 MusicRepositoryImpl 逐字搬移。
 *
 * 注：本域两个方法显式接收 username/password/baseUrl（刻意设计，见 MediaUrlRepository
 * 接口注释），故不使用基类的 currentAccountId；仍继承
 * [BaseSubsonicRepository] 以与其余五域保持一致的构造形态。
 *
 * 认证方式（token / legacy 明文）不进方法签名，由 [currentApiType] 从**本实例所属账户**解析
 * ——对齐 iOS：authType 是 SubsonicServerApi 实例自带的属性，generateUrl 直接用实例的
 * authType（SubsonicServerApi.swift:135-138 + :233-238），并非逐调用传参。
 * 本类每账户一份（AccountComponentsRegistry 构造时绑定），语义与 iOS 一一对应。
 *
 * 出处：Repository 拆分批次 2。
 */
internal class MediaUrlRepositoryImpl(
    subsonicApi: SubsonicApi,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    private val settingsManager: com.amperfy.data.local.SettingsManager,
    private val networkMonitor: com.amperfy.core.NetworkMonitor,
    boundAccountInfo: AccountInfo?,
) : BaseSubsonicRepository(
    subsonicApi, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    MediaUrlRepository {

    /**
     * 本实例所属账户的认证方式；凭证读不到时兜底 token（Subsonic 1.13.0 起的服务器现状，
     * 见 SubsonicAuthParams.usesLegacyPlaintextAuth）。
     */
    private fun currentApiType(): BackendApiType =
        currentCredentials()?.backendApi ?: BackendApiType.SUBSONIC

    override fun getStreamUrl(songId: String, username: String, password: String, baseUrl: String): String {
        // 流媒体格式 + 比特率上限（Settings→Player, Stream & Scrobble）
        // 对应 iOS SubsonicServerApi.generateUrl(forStreamingPlayableId:maxBitrate:)：
        // format 与 maxBitRate 均按当前网络在 WiFi/蜂窝两档偏好中取值
        // （iOS 2.0.0 起格式分网络；serverConfig 不传 format。
        // 缓存下载格式 Cache Format 为独立逻辑，不受此影响）
        val formatPreference = if (networkMonitor.isWifiOrEthernet) {
            settingsManager.streamingFormatWifiPreference.value
        } else {
            settingsManager.streamingFormatCellularPreference.value
        }
        val format = when (formatPreference) {
            com.amperfy.data.local.StreamingFormatPreference.MP3 -> "mp3"
            com.amperfy.data.local.StreamingFormatPreference.RAW -> "raw"
            com.amperfy.data.local.StreamingFormatPreference.SERVER_CONFIG -> null
        }
        val maxBitrate = if (networkMonitor.isWifiOrEthernet) {
            settingsManager.streamingMaxBitrateWifiPreference.value.kbps
        } else {
            settingsManager.streamingMaxBitrateCellularPreference.value.kbps
        }
        return SubsonicUrlBuilder.generateUrlForStreamingPlayable(
            id = songId,
            username = username,
            password = password,
            baseUrl = baseUrl,
            apiType = currentApiType(),
            maxBitrate = maxBitrate,
            format = format
        )
    }

    /**
     * 播客单集流 URL：Subsonic 与歌曲**同端点**，故实现体与 [getStreamUrl] 相同
     * （调用方传的 [episodeApiId] 已是 `streamId ?: id`，iOS SubsonicApi.swift:79）。
     * 本方法为 Ampache 移植 Batch 2 追加——Ampache 侧必须区分 type，接口遂分立两方法。
     */
    override fun getPodcastEpisodeStreamUrl(
        episodeApiId: String,
        username: String,
        password: String,
        baseUrl: String
    ): String = getStreamUrl(episodeApiId, username, password, baseUrl)

    override fun getCoverArtUrl(coverArtId: String, username: String, password: String, baseUrl: String): String {
        return SubsonicUrlBuilder.generateUrlForArtwork(coverArtId, username, password, baseUrl, currentApiType())
    }

    /**
     * 下载 URL（自 [com.amperfy.data.download.DownloadManager] 原样搬入，Ampache 移植 Batch 2
     * 开 API 无关缝所致；三分支与注释逐字保留，行为不变）。
     *
     * 缓存转码格式（Settings→Player→Cache Format (Transcoding)）
     * 对应 iOS SubsonicServerApi.generateUrl(forDownloadingPlayableId:)（:318-343）：
     * raw → 'download' 端点（跳过转码）；mp3 → 'stream' + format=mp3；
     * serverConfig → 'stream' 不带 format（服务器决定）
     * mp3/serverConfig 两个走 stream 端点的分支必须 estimateContentLength = false：
     * 下载读到流末尾，估算长度与转码实际字节数不符会被 OkHttp 判定流提前终止致首次下载必败
     * （详见 SubsonicUrlBuilder.generateUrlForStreamingPlayable 注释）；raw 走 download 端点不涉及。
     * 关闭 estimateContentLength 后转码响应可能为 chunked（无 Content-Length），此时下载侧进度循环
     * totalBytes = body.contentLength() = -1，if (totalBytes > 0) 跳过进度更新——DownloadProgress.progress
     * 保持 null（未知长度），UI 显示不确定态转圈而非停在 0%；下载仍正常完成。
     * 请求 id 一律取 apiId（单集为 streamId ?? id，iOS SubsonicApi.swift:73-76）。
     *
     * Subsonic 侧不区分歌曲/单集（同端点），故 [isPodcastEpisode] 未使用。
     */
    override suspend fun getDownloadUrl(
        apiId: String,
        isPodcastEpisode: Boolean,
        transcoding: com.amperfy.data.local.CacheTranscodingFormatPreference,
        username: String,
        password: String,
        baseUrl: String
    ): String = when (transcoding) {
        com.amperfy.data.local.CacheTranscodingFormatPreference.RAW ->
            SubsonicUrlBuilder.generateUrlForDownloadingPlayable(
                id = apiId,
                username = username,
                password = password,
                baseUrl = baseUrl,
                apiType = currentApiType()
            )
        com.amperfy.data.local.CacheTranscodingFormatPreference.MP3 ->
            SubsonicUrlBuilder.generateUrlForStreamingPlayable(
                id = apiId,
                username = username,
                password = password,
                baseUrl = baseUrl,
                apiType = currentApiType(),
                format = "mp3",
                estimateContentLength = false
            )
        com.amperfy.data.local.CacheTranscodingFormatPreference.SERVER_CONFIG ->
            SubsonicUrlBuilder.generateUrlForStreamingPlayable(
                id = apiId,
                username = username,
                password = password,
                baseUrl = baseUrl,
                apiType = currentApiType(),
                estimateContentLength = false
            )
    }

}
