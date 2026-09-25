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

package com.amperfy.data.remote

import com.amperfy.data.model.BackendApiType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Subsonic 认证参数单一真相源（MD5 token+salt）
 *
 * 对应 iOS 两处的合体：
 * - `AmperfyKit/Common/StringHasher.swift` md5Hex（UTF-8 → 32 位小写十六进制）
 * - `AmperfyKit/Api/Subsonic/SubsonicServerApi.swift:213-240` createAuthApiUrlComponent
 *   （每请求追加 u/v/c，再按客户端 API 版本二选一：< 1.13.0 发明文 p，否则发 t + s）
 *
 * 版本与认证方式的绑定关系（iOS SubsonicServerApi.swift:103-104 + :135-138）：
 * 客户端 API 版本**不由服务器版本决定**，而由 authType 决定——
 * 非 legacy → 1.13.0（defaultClientApiVersionWithToken）恒发 token；
 * legacy → 1.11.0（defaultClientApiVersionPreToken）恒发明文。
 * 阈值 1.13.0 = `SubsonicVersion.authenticationTokenRequiredServerApi`（SubsonicVersion.swift:27）。
 *
 * 已验证例证（Subsonic 官方文档示例）：password="sesame"、salt="c19b2d" →
 * [md5Hex] 结果为 "26719a1196d2a940705a59634eb18eab"。
 */
object SubsonicAuthParams {

    /**
     * Subsonic client identifier (`c=` parameter); distinct from the iOS app's "Amperfy"
     * so servers can tell the two clients apart.
     */
    const val CLIENT_NAME = "Amperfy4Android"

    /** token 认证的客户端 API 版本（iOS defaultClientApiVersionWithToken） */
    const val CLIENT_API_VERSION_WITH_TOKEN = "1.13.0"

    /** legacy（明文）认证的客户端 API 版本（iOS defaultClientApiVersionPreToken） */
    const val CLIENT_API_VERSION_PRE_TOKEN = "1.11.0"

    /** 盐长度（iOS `String.generateRandomString(ofLength: 16)`，SubsonicServerApi.swift:234） */
    private const val SALT_LENGTH = 16

    /** 盐字母表（逐字对齐 iOS Common/Utilities.swift:217-220） */
    private const val SALT_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** 认证与公共查询参数键（缓存键规范化时剔除，见 [stripAuthQueryParams]） */
    private val AUTH_QUERY_KEYS = setOf("u", "p", "t", "s", "v", "c")

    private val secureRandom = SecureRandom()

    /**
     * token = md5(password + salt)（UTF-8、32 位小写十六进制）
     * 对应 iOS StringHasher.md5Hex + generateAuthenticationToken（SubsonicServerApi.swift:148-156）
     */
    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 16 位随机盐（每请求现生成一枚，对齐 iOS 在 createAuthApiUrlComponent 内每次调用生成） */
    fun generateSalt(): String {
        val sb = StringBuilder(SALT_LENGTH)
        repeat(SALT_LENGTH) {
            sb.append(SALT_ALPHABET[secureRandom.nextInt(SALT_ALPHABET.length)])
        }
        return sb.toString()
    }

    /**
     * 该账户类型是否走 legacy 明文认证。
     *
     * 只有显式检测/选择为 [BackendApiType.SUBSONIC_LEGACY] 才发明文；其余取值
     * （SUBSONIC / NOT_DETECTED / AMPACHE）一律按 token 处理——
     * NOT_DETECTED 是存量账户（MD5 落地前登录时未做类型检测，backend_api 键写的是用户在
     * 登录页选的 "Auto-Detect"=0）的常见值，默认走 token 与 Subsonic 1.13.0（2016 年）以来的
     * 服务器现状一致；极老 legacy 服务器的存量账户重新登录一次即被检测为 legacy 并恢复。
     */
    fun usesLegacyPlaintextAuth(apiType: BackendApiType): Boolean =
        apiType == BackendApiType.SUBSONIC_LEGACY

    /**
     * 生成一次请求的认证查询参数（对应 iOS createAuthApiUrlComponent，
     * SubsonicServerApi.swift:213-240；参数顺序亦按 iOS 的追加顺序 u → v → c → p/t+s）。
     *
     * 每次调用现生成新盐，故同一资源两次调用得到的 URL 不同（与 iOS 一致）；
     * Android 侧由此带来的 Coil URL 缓存键漂移由 [stripAuthQueryParams] 配套化解。
     */
    fun authQueryParams(
        username: String,
        password: String,
        apiType: BackendApiType,
    ): Map<String, String> {
        val params = LinkedHashMap<String, String>(6)
        params["u"] = username
        if (usesLegacyPlaintextAuth(apiType)) {
            params["v"] = CLIENT_API_VERSION_PRE_TOKEN
            params["c"] = CLIENT_NAME
            // iOS：version < 1.13.0 时发明文 p（不做 "enc:" 十六进制变体）
            params["p"] = password
        } else {
            params["v"] = CLIENT_API_VERSION_WITH_TOKEN
            params["c"] = CLIENT_NAME
            val salt = generateSalt()
            params["t"] = md5Hex(password + salt)
            params["s"] = salt
        }
        return params
    }

    /**
     * 剔除认证与公共参数（u/p/t/s/v/c）后的 URL——**仅用作缓存键**，不可用于发请求。
     *
     * Android 特有配套：随机盐使同一封面每次生成的 URL 都不同，而 Coil 默认以 URL 串作
     * memory/disk 缓存键，不规范化则缓存全废（iOS 以 artwork id 自建缓存，无此问题）。
     * 保留 host/path 与业务参数（id/size 等），故不同封面、不同尺寸仍是不同键。
     * 解析失败时原样返回（宁可缓存不命中，也不产生错键）。
     */
    fun stripAuthQueryParams(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val builder = parsed.newBuilder()
        AUTH_QUERY_KEYS.forEach { builder.removeAllQueryParameters(it) }
        return builder.build().toString()
    }
}
