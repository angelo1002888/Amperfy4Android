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

package com.amperfy.data.repository.ampache

import com.amperfy.data.local.CacheTranscodingFormatPreference
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.local.StreamingFormatPreference
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheArtworkInfo
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.remote.ampache.AmpacheUrl
import com.amperfy.data.repository.MediaUrlRepository

/**
 * 媒体 URL 域的 Ampache 实现（对应 iOS `AmpacheXmlServerApi` 的三个 generateUrl，
 * AmpacheXmlServerApi.swift:753-818）。
 *
 * **token 时效性的分工**（Batch 2 定案）——Ampache 的 URL 都带会话 token，而
 * [getStreamUrl]/[getPodcastEpisodeStreamUrl]/[getCoverArtUrl] 三者是**非 suspend** 的
 * C0 冻结签名（PlayerManager/Compose 层同步调用），拿不到重握手的机会：
 * - 这里用 [AmpacheAuthSession.currentToken]（内存中当前 token，**可能为空或已过期**）拼；
 * - 真正发请求的两条链在装载时刻换新 token：ExoPlayer 经 `ResolvingDataSource`、
 *   Coil 经 `AmpacheArtworkAuthInterceptor`，二者共用
 *   [com.amperfy.core.AmpacheUrlAuthRefresher]；
 * - [getDownloadUrl] 是 suspend（下载本就在协程内），故直接 `reauthenticate()` 拿新鲜 token
 *   ——与 iOS 三个 `async` generateUrl 的行为完全一致。
 *
 * 出处：Ampache API 移植 Batch 2。
 */
internal class AmpacheMediaUrlRepositoryImpl(
    ampacheApi: AmpacheApi,
    authSession: AmpacheAuthSession,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    private val settingsManager: SettingsManager,
    private val networkMonitor: com.amperfy.core.NetworkMonitor,
    boundAccountInfo: AccountInfo?,
) : BaseAmpacheRepository(
    ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    MediaUrlRepository {

    override fun getStreamUrl(
        songId: String,
        username: String,
        password: String,
        baseUrl: String,
    ): String = buildStreamUrl(songId, isSong = true, baseUrl = baseUrl)

    override fun getPodcastEpisodeStreamUrl(
        episodeApiId: String,
        username: String,
        password: String,
        baseUrl: String,
    ): String = buildStreamUrl(episodeApiId, isSong = false, baseUrl = baseUrl)

    /**
     * `action=stream&type=song|podcast_episode&id=…[&format][&bitrate]&length=1`
     * （iOS generateUrlForStreamingPlayable，:769-800）。
     *
     * format / bitrate 的取值口径与 Subsonic 侧
     * [com.amperfy.data.repository.MediaUrlRepositoryImpl.getStreamUrl] 完全一致
     * ——按当前网络在 WiFi/蜂窝两档偏好中取；serverConfig 档不传 format，noLimit 档不传 bitrate。
     * `length=1` 恒传（让服务器按完整时长回传 content-length，iOS :797）。
     */
    private fun buildStreamUrl(id: String, isSong: Boolean, baseUrl: String): String = try {
        val builder = AmpacheUrl.authApiUrlBuilder(baseUrl, authSession.currentToken.orEmpty())
        builder.addQueryParameter("action", "stream")
        builder.addQueryParameter("type", if (isSong) AmpacheApi.TYPE_SONG else AmpacheApi.TYPE_PODCAST_EPISODE)
        builder.addQueryParameter("id", id)
        streamFormat()?.let { builder.addQueryParameter("format", it) }
        streamMaxBitrateKbps()?.let { builder.addQueryParameter("bitrate", it.toString()) }
        builder.addQueryParameter("length", "1")
        builder.build().toString()
    } catch (e: Exception) {
        // 服务器 URL 非法（AmpacheUrl.rootUrlBuilder 抛）——返回空串，播放侧按无效 URL 处理
        android.util.Log.e(AMPACHE_LOG_TAG, "buildStreamUrl error", e)
        ""
    }

    private fun streamFormat(): String? {
        val preference = if (networkMonitor.isWifiOrEthernet) {
            settingsManager.streamingFormatWifiPreference.value
        } else {
            settingsManager.streamingFormatCellularPreference.value
        }
        return when (preference) {
            StreamingFormatPreference.MP3 -> AmpacheApi.FORMAT_MP3
            StreamingFormatPreference.RAW -> AmpacheApi.FORMAT_RAW
            StreamingFormatPreference.SERVER_CONFIG -> null
        }
    }

    /** null = No Limit 档（iOS `case .noLimit: break`，不传 bitrate 参数） */
    private fun streamMaxBitrateKbps(): Int? = if (networkMonitor.isWifiOrEthernet) {
        settingsManager.streamingMaxBitrateWifiPreference.value.kbps
    } else {
        settingsManager.streamingMaxBitrateCellularPreference.value.kbps
    }

    /**
     * `<server>/image.php?auth=<token>&object_id=…&object_type=…`
     * （iOS generateUrlForArtwork，:802-818）。
     *
     * [coverArtId] 是落库的封面标识 = 去掉 auth/ssid 的 `<art>` URL（见 [ampacheCoverArtKey]），
     * 从中反提取 object_id/object_type 后按当前 token 现拼。反提取不出（非 image.php 形态）
     * 时原样返回——调用方当普通图片 URL 用，至少不会崩。
     */
    override fun getCoverArtUrl(
        coverArtId: String,
        username: String,
        password: String,
        baseUrl: String,
    ): String {
        val info = AmpacheArtworkInfo.fromUrl(coverArtId) ?: return coverArtId
        return try {
            AmpacheUrl.rootUrlBuilder(baseUrl, AmpacheUrl.ARTWORK_PATH)
                .addQueryParameter("auth", authSession.currentToken.orEmpty())
                .addQueryParameter("object_id", info.objectId)
                .addQueryParameter("object_type", info.objectType)
                .build()
                .toString()
        } catch (e: Exception) {
            android.util.Log.e(AMPACHE_LOG_TAG, "getCoverArtUrl error", e)
            coverArtId
        }
    }

    /**
     * `action=download&type=…&id=…&format=mp3|raw`（iOS generateUrlForDownloadingPlayable，:753-767）。
     *
     * 与流媒体不同，**format 恒传**：iOS 只分 mp3 与 default 两支，serverConfig 也落到
     * default → raw（**没有**「不传 format」的形态）。
     * 本方法是 suspend，故先 [AmpacheAuthSession.reauthenticate] 拿新鲜 token（与 iOS 同）。
     */
    override suspend fun getDownloadUrl(
        apiId: String,
        isPodcastEpisode: Boolean,
        transcoding: CacheTranscodingFormatPreference,
        username: String,
        password: String,
        baseUrl: String,
    ): String {
        val auth = authSession.reauthenticate()
        val format = when (transcoding) {
            CacheTranscodingFormatPreference.MP3 -> AmpacheApi.FORMAT_MP3
            else -> AmpacheApi.FORMAT_RAW
        }
        return AmpacheUrl.authApiUrlBuilder(baseUrl, auth.token)
            .addQueryParameter("action", "download")
            .addQueryParameter(
                "type",
                if (isPodcastEpisode) AmpacheApi.TYPE_PODCAST_EPISODE else AmpacheApi.TYPE_SONG,
            )
            .addQueryParameter("id", apiId)
            .addQueryParameter("format", format)
            .build()
            .toString()
    }
}
