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

package com.amperfy.data.remote.ampache

import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.remote.ampache.parser.AmpacheAuthParser
import com.amperfy.data.remote.ampache.parser.AmpacheXmlParsing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.security.MessageDigest

/**
 * 库变更三时间戳（对应 iOS `LibraryChangeDates`，AuthentificationHandshake.swift:26-43）。
 * 均为 epoch 毫秒；同步策略据此判断服务器库是否有变化。
 */
data class AmpacheLibraryChangeDates(
    val lastUpdateMillis: Long,
    val lastAddMillis: Long,
    val lastCleanMillis: Long,
)

/**
 * 一次成功握手的会话数据（对应 iOS `AuthentificationHandshake`，
 * AuthentificationHandshake.swift:47-59）。
 *
 * [token] 只驻内存、不落盘（与 iOS 同：进程重启后首个请求自然触发新握手）。
 * 各 count 来自握手响应，分页请求的 offset 钳制要用（AmpacheXmlServerApi.swift:306/387）。
 */
data class AmpacheAuthHandshake(
    val token: String,
    /** 会话过期时刻（epoch 毫秒） */
    val sessionExpireMillis: Long,
    /** 提前重握手时刻 = [sessionExpireMillis] − 5 分钟（AuthParserDelegate.swift:30/45-48） */
    val reauthenticateTimeMillis: Long,
    val libraryChangeDates: AmpacheLibraryChangeDates,
    val songCount: Int = 0,
    val artistCount: Int = 0,
    val albumCount: Int = 0,
    val genreCount: Int = 0,
    val playlistCount: Int = 0,
    val podcastCount: Int = 0,
    val videoCount: Int = 0,
    /** 服务器 `<api>` 版本串（如 "5.5.6"） */
    val serverApiVersion: String? = null,
) {
    /** 当前时刻是否还在「无需重握手」的窗口内（对应 iOS isAuthenticated，:133-136） */
    fun isValidAt(nowMillis: Long): Boolean = reauthenticateTimeMillis - nowMillis >= 0
}

/**
 * Ampache 握手会话管理器（对应 iOS `AmpacheXmlServerApi` 里认证相关的那一半：
 * generatePassphrase / createAuthURL / requestAuth / authenticate / reauthenticate /
 * provideCredentials / requestServerPodcastSupport）。
 *
 * 与 Subsonic 的根本差异：Subsonic 是**无状态每请求签名**
 * （`SubsonicAuthInterceptor` 追加 t+s），Ampache 是**会话制**——先 handshake 换 token，
 * 之后每个请求只带 `auth=<token>`，过期前 5 分钟主动重握手。
 *
 * 并发：多个请求同时发现会话过期时，只应发生**一次** handshake ——由 [handshakeMutex]
 * 保证，后到者在锁内二次检查即复用先到者刚拿到的会话（iOS 用 Atomic 包装，语义等价）。
 *
 * 每账户一份实例（Batch 2 由 `AccountComponentsRegistry` 装配），[credentialsProvider]
 * 每次现取该账户凭证——服务器 URL 可能被 Manage Server URLs 改写。
 */
class AmpacheAuthSession(
    private val okHttpClient: OkHttpClient,
    private val credentialsProvider: () -> LoginCredentials?,
) {

    private val handshakeMutex = Mutex()

    @Volatile
    private var handshake: AmpacheAuthHandshake? = null

    /** 最近一次握手拿到的服务器 `<api>` 版本（握手失败时也保留，与 iOS 同，:252-254） */
    @Volatile
    var serverApiVersion: String? = null
        private set

    /** 当前会话（可能已过期/为空），只读快照，供调试与 Batch 2 读取 counts */
    val currentHandshake: AmpacheAuthHandshake?
        get() = handshake

    /**
     * 当前会话 token（**可能已过期，也可能为 null**），只读快照。
     *
     * 唯一用途：`AmpacheMediaUrlRepositoryImpl` 是同步接口（`getStreamUrl`/
     * `getCoverArtUrl` 非 suspend，签名为 C0 冻结合同），拿不到「先确保会话有效再拼 URL」
     * 的机会，只能用内存里现成的 token 同步拼；真正发请求时由
     * [com.amperfy.core.AmpacheUrlAuthRefresher] 在装载时刻把 `auth` 换成新鲜 token
     * （ExoPlayer 的 ResolvingDataSource / Coil 的 AmpacheArtworkAuthInterceptor）。
     *
     * 发请求的路径（[AmpacheApi]）与 suspend 的下载 URL 一律走 [reauthenticate]，不读本属性。
     */
    val currentToken: String?
        get() = handshake?.token

    /**
     * 取一个可用会话：未过 reauthenticateTime 直接复用，否则重新握手
     * （对应 iOS reauthenticate()，AmpacheXmlServerApi.swift:275-282）。
     */
    suspend fun reauthenticate(): AmpacheAuthHandshake {
        validHandshake()?.let { return it }
        return handshakeMutex.withLock {
            // 二次检查：等锁期间可能已由别的请求完成握手
            validHandshake() ?: performHandshake(requireCredentials())
        }
    }

    /**
     * 强制握手一次并作为当前会话（对应 iOS authenticate(credentials:)）。
     * 登录/改密流程用：拿指定凭证验证服务器是否接受（iOS isAuthenticationValid，:180-182）。
     */
    suspend fun authenticate(credentials: LoginCredentials): AmpacheAuthHandshake =
        handshakeMutex.withLock { performHandshake(credentials) }

    /**
     * 校验凭证是否可用；失败抛异常（[AmpacheApiException] / [AmpacheAuthenticationException]）。
     * 对应 iOS isAuthenticationValid(credentials:)——**登录验证就是发一次 handshake**，
     * 不需要 ping。
     */
    suspend fun isAuthenticationValid(credentials: LoginCredentials) {
        authenticate(credentials)
    }

    /**
     * 作废当前会话（对应 iOS provideCredentials(credentials:) 里的 `authHandshake = nil`，
     * AmpacheXmlServerApi.swift:163-166）。
     *
     * Android 的凭证由 [credentialsProvider] 每次现取，故「换凭证」只需清会话。
     *
     * **Batch 2 接线结论**：唯一调用点是登出（`AccountComponents.stop()`）。
     * 改密 / 切换服务器 URL **不需要**作废会话——凭证每次现取，旧 token 在服务器侧
     * 仍有效直至过期，下次 reauthenticate 自然用新凭证重握手；提前作废只会平白多一次握手。
     */
    fun invalidateSession() {
        handshake = null
    }

    /**
     * 服务器是否支持播客（对应 iOS requestServerPodcastSupport()，:105-112）：
     * 握手后取 `<api>` 版本，与 [PODCAST_SUPPORT_MIN_API_VERSION]（420000）比较。
     *
     * **与 iOS 2.1.0 的刻意差异**：
     * iOS 写的是 `Int(serverApiVersion)`，而 Ampache 5 的 `<api>` 是 "5.5.6" 这类语义版本串
     * ——`Int("5.5.6")` 得 nil，于是 iOS **恒判不支持播客**，属 iOS 自身缺陷。
     * Android 按协议实现：两种形态（旧整数 "420000" / 语义版本 "5.5.6"）统一经
     * [AmpacheApiVersion.parse] 折算后再比较。
     */
    suspend fun requestServerPodcastSupport(): Boolean {
        reauthenticate()
        val version = AmpacheApiVersion.parse(serverApiVersion) ?: return false
        return version >= PODCAST_SUPPORT_MIN_API_VERSION
    }

    private fun validHandshake(): AmpacheAuthHandshake? =
        handshake?.takeIf { it.isValidAt(System.currentTimeMillis()) }

    private fun requireCredentials(): LoginCredentials =
        credentialsProvider() ?: throw AmpacheAuthenticationException("Ampache: 无可用凭证")

    /** 发一次 handshake 并落成当前会话；失败时清空会话（对应 iOS authenticate 的 catch 分支） */
    private suspend fun performHandshake(credentials: LoginCredentials): AmpacheAuthHandshake {
        val timestampSeconds = System.currentTimeMillis() / 1000L
        val passphrase = generatePassphrase(passwordHashOf(credentials), timestampSeconds)

        // 参数顺序逐字对齐 iOS createAuthURL（AmpacheXmlServerApi.swift:203-207）
        val url = AmpacheUrl.apiUrlBuilder(credentials.serverUrl)
            .addQueryParameter("action", "handshake")
            .addQueryParameter("auth", passphrase)
            .addQueryParameter("timestamp", timestampSeconds.toString())
            .addQueryParameter("version", CLIENT_API_VERSION)
            .addQueryParameter("user", credentials.username)
            .build()
            .toString()

        val body = try {
            ampacheHttpGetString(okHttpClient, url)
        } catch (e: Exception) {
            handshake = null
            throw e
        }

        val parser = AmpacheAuthParser()
        val parsed = AmpacheXmlParsing.parse(body, parser)
        parser.serverApiVersion?.let { serverApiVersion = it }

        parser.error?.let { error ->
            handshake = null
            throw AmpacheApiException(error, AmpacheUrl.cleanse(url))
        }
        if (parsed == null) {
            handshake = null
            throw AmpacheAuthenticationException("Ampache: 握手未返回 auth token")
        }
        handshake = parsed
        return parsed
    }

    /**
     * 握手用的密码哈希：优先用凭证里已算好的 sha256（`CredentialsManager.hashPassword`，
     * 与 iOS `LoginCredentials.passwordHash` 同为小写十六进制 sha256）；
     * 为空（如登录探测时构造的临时凭证）则就地算一次。
     */
    private fun passwordHashOf(credentials: LoginCredentials): String =
        credentials.passwordHash.ifEmpty { sha256Hex(credentials.password) }

    companion object {
        /** 客户端 API 版本（iOS clientApiVersion，AmpacheXmlServerApi.swift:92） */
        const val CLIENT_API_VERSION: String = "500000"

        /** 播客支持的服务器 API 版本下限（iOS :109） */
        const val PODCAST_SUPPORT_MIN_API_VERSION: Int = 420000

        /**
         * Ampache passphrase = `sha256("<unixtime 秒><sha256(password)>")`，小写十六进制
         * （对应 iOS generatePassphrase，AmpacheXmlServerApi.swift:138-144）。
         *
         * 注意**内层是密码的 sha256 预哈希**（明文密码不参与外层拼接），
         * 外层把十进制秒级时间戳字符串直接接在其前面。
         */
        fun generatePassphrase(passwordHash: String, timestampSeconds: Long): String =
            sha256Hex("$timestampSeconds$passwordHash")

        /** UTF-8 → 64 位小写十六进制 sha256（对应 iOS `StringHasher.sha256`） */
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
