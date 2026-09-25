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

import android.util.Log
import com.amperfy.data.model.BackendApiType
import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 登录验证辅助类
 * 用于动态创建Retrofit实例验证不同服务器
 *
 * 认证类型检测对应 iOS `BackendProxy.login(apiType:credentials:)`（BackendProxy.swift:281-311）：
 * iOS 持 ampacheApi、subsonicApi（authType=autoDetect，恒发 token）与 subsonicLegacyApi
 * （authType=legacy，恒发明文）三个实例，登录时按用户所选类型逐个 `isAuthenticationValid` 试，
 * 成功者作为 [BackendApiType] 随凭证持久化。Android 这里用「同一临时 Retrofit + 各挂一个固定
 * 类型的 [SubsonicAuthInterceptor]」表达 Subsonic 两档；Ampache 档走一次真实 handshake
 * （会话制认证没有 ping，登录验证就是握手本身）。
 */
object LoginApiHelper {

    private const val TAG = "LoginApiHelper"

    /**
     * 检测服务器认证类型（对齐 iOS BackendProxy.login 的顺序语义）。
     *
     * @param selected 用户在登录页选的 API 类型（登录页 API: 菜单，默认 Auto-Detect）
     * @return 检测成功的类型（[BackendApiType.SUBSONIC] = token / [BackendApiType.SUBSONIC_LEGACY]
     *   = 明文），全部失败返回 null（凭证错误或服务器不可达）
     *
     * 候选顺序：Auto-Detect → **① Ampache handshake → ② Subsonic token → ③ Subsonic legacy**
     * （逐字对齐 iOS BackendProxy.swift:283-311 的先后）；显式选定则只试所选那一种。
     */
    suspend fun detectApiType(
        serverUrl: String,
        username: String,
        password: String,
        selected: BackendApiType = BackendApiType.NOT_DETECTED,
    ): BackendApiType? {
        val candidates = when (selected) {
            BackendApiType.AMPACHE -> listOf(BackendApiType.AMPACHE)
            BackendApiType.SUBSONIC -> listOf(BackendApiType.SUBSONIC)
            BackendApiType.SUBSONIC_LEGACY -> listOf(BackendApiType.SUBSONIC_LEGACY)
            // NOT_DETECTED（Auto-Detect）：Ampache 在前，与 iOS 同序
            else -> listOf(
                BackendApiType.AMPACHE,
                BackendApiType.SUBSONIC,
                BackendApiType.SUBSONIC_LEGACY,
            )
        }
        for (candidate in candidates) {
            val ok = if (candidate == BackendApiType.AMPACHE) {
                ampacheHandshakeSucceeds(serverUrl, username, password)
            } else {
                ping(serverUrl, username, password, candidate)
            }
            if (ok) {
                Log.d(TAG, "Detected api type: $candidate")
                return candidate
            }
        }
        Log.w(TAG, "No api type matched for server: $serverUrl")
        return null
    }

    /**
     * 获取服务器 API 版本（ping 响应的 version 属性）
     * 对应 iOS: SubsonicServerApi.serverApiVersion（从响应解析缓存）；失败返回 null
     *
     * 注：iOS 登录后另有一次 `determineApiVersionToUse` 的 ping——它只把服务器版本写日志，
     * **不参与**客户端版本/认证方式决策（SubsonicServerApi.swift:168-190），Android 不复刻
     * 那次额外请求；本方法是 Account 设置页「API 信息」行的显示用途，与认证决策无关。
     */
    suspend fun fetchServerApiVersion(
        serverUrl: String,
        username: String,
        password: String,
        apiType: BackendApiType,
    ): String? {
        // Ampache：服务器版本来自握手响应的 <api>，没有 ping 端点
        if (apiType == BackendApiType.AMPACHE) {
            return try {
                val credentials = ampacheCredentials(serverUrl, username, password)
                val session = AmpacheAuthSession(createTemporaryHttpClient()) { credentials }
                session.authenticate(credentials)
                session.serverApiVersion
            } catch (e: Exception) {
                Log.e(TAG, "Fetch Ampache server version failed: ${e.message}", e)
                null
            }
        }
        return try {
            val response = createTemporaryApi(serverUrl, username, password, apiType).ping()
            response.body()?.subsonicResponse
                ?.takeIf { it.status == "ok" }
                ?.version
        } catch (e: Exception) {
            Log.e(TAG, "Fetch server version failed: ${e.message}", e)
            null
        }
    }

    /**
     * Ampache 登录验证 = **发一次 handshake**（对应 iOS AmpacheXmlServerApi.isAuthenticationValid，
     * AmpacheXmlServerApi.swift:180-182）——会话制认证没有 ping。
     *
     * 任何异常（网络不可达 / 4701 凭证错误 / 该服务器根本不是 Ampache）→ false，
     * 对应 iOS BackendProxy.login 逐个 try 的 catch-忽略语义（BackendProxy.swift:283-311）。
     */
    private suspend fun ampacheHandshakeSucceeds(
        serverUrl: String,
        username: String,
        password: String,
    ): Boolean = try {
        val credentials = ampacheCredentials(serverUrl, username, password)
        AmpacheAuthSession(createTemporaryHttpClient()) { credentials }
            .isAuthenticationValid(credentials)
        true
    } catch (e: Exception) {
        Log.d(TAG, "Ampache handshake failed: ${e.message}")
        false
    }

    /** 探测/取版本用的临时凭证（passwordHash 留空，会话管理器就地算 sha256） */
    private fun ampacheCredentials(serverUrl: String, username: String, password: String) =
        LoginCredentials(
            serverUrl = serverUrl,
            username = username,
            password = password,
            passwordHash = "",
            backendApi = BackendApiType.AMPACHE,
        )

    /**
     * 探测用的临时 OkHttpClient：与 [createTemporaryApi] 同规格（10s 超时 + 详细日志），
     * 但**不挂** [SubsonicAuthInterceptor]——Ampache 的认证参数在 URL 里由会话管理器自己拼。
     */
    private fun createTemporaryHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(DetailedLoggingInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 用指定认证类型 ping 一次（对应 iOS SubsonicApi.isAuthenticationValid） */
    private suspend fun ping(
        serverUrl: String,
        username: String,
        password: String,
        apiType: BackendApiType,
    ): Boolean {
        return try {
            Log.d(TAG, "Ping server: $serverUrl, apiType: $apiType")
            val response = createTemporaryApi(serverUrl, username, password, apiType).ping()
            val isSuccess = response.isSuccessful &&
                response.body()?.subsonicResponse?.status == "ok"
            Log.d(TAG, "Ping result: $isSuccess, response code: ${response.code()}")
            isSuccess
        } catch (e: Exception) {
            // 网络错误、超时、或其他异常
            Log.e(TAG, "Ping failed with exception: ${e.message}", e)
            false
        }
    }

    /**
     * 创建临时API实例用于验证
     *
     * 认证参数由固定凭证 + 固定类型的 [SubsonicAuthInterceptor] 追加；本链**不挂**
     * [AccountBaseUrlInterceptor]（账户尚未入册，URL 直接用 baseUrl 拼），
     * 故 serverUrl 必须在此规范化到以 '/' 结尾。
     */
    private fun createTemporaryApi(
        baseUrl: String,
        username: String,
        password: String,
        apiType: BackendApiType,
    ): SubsonicApi {
        // 使用详细日志拦截器
        val detailedLoggingInterceptor = DetailedLoggingInterceptor()

        val credentials = LoginCredentials(
            serverUrl = baseUrl,
            username = username,
            password = password,
            passwordHash = "",
            backendApi = apiType
        )

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(SubsonicAuthInterceptor { credentials })
            .addInterceptor(detailedLoggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        val gson = GsonBuilder()
            .setLenient()
            .create()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(SubsonicApi::class.java)
    }

    /**
     * 规范化baseUrl,确保以/结尾
     */
    private fun normalizeBaseUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.endsWith("/")) {
            normalized += "/"
        }
        return normalized
    }
}
