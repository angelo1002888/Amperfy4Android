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

import com.amperfy.data.model.LoginCredentials
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Subsonic 认证拦截器（每请求追加 u/v/c + t/s 或明文 p）
 *
 * 对应 iOS `SubsonicServerApi.createAuthApiUrlComponent`（SubsonicServerApi.swift:213-240）——
 * iOS 每次构造请求 URL 时现拼认证参数，Android 形态为 OkHttp 拦截器：**所有** `/rest/` 请求
 * 的认证参数只在此一处生成（[SubsonicApi] 的端点声明里不再有 u/p/v/c 形参），
 * 认证方式随账户的 [LoginCredentials.backendApi] 走（token / legacy 明文）。
 *
 * 一账户一实例（对应 iOS BackendProxy 的 subsonicApi / subsonicLegacyApi 双实例——
 * iOS 用「两个实例各带一个 authType」表达，Android 用「一个拦截器读该账户已存的 apiType」表达）：
 * - 账户级：[AccountComponentsRegistry] 建 Retrofit 时挂 `{ credentialsManager.getCredentials(ident) }`，
 *   凭证与 apiType 每请求实时读取（与 [AccountBaseUrlInterceptor] 读 active_server_url 同口径，
 *   支持改密/类型重检测后立即生效）；
 * - 登录检测：[LoginApiHelper] 用固定凭证 + 待验类型建临时链。
 *
 * 已带认证参数的请求（URL 里已有 u）不再追加，避免重复参数。
 */
class SubsonicAuthInterceptor(
    private val credentialsProvider: () -> LoginCredentials?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // 只处理 Subsonic REST 端点；其它请求（如外部电台流）原样放行
        if (!url.encodedPath.contains("/rest/")) return chain.proceed(request)
        // 已显式带认证参数 → 不覆盖
        if (url.queryParameterNames.contains("u")) return chain.proceed(request)

        val credentials = credentialsProvider() ?: run {
            android.util.Log.w("SubsonicAuth", "No credentials for ${url.encodedPath}")
            return chain.proceed(request)
        }

        val newUrl = url.newBuilder().apply {
            SubsonicAuthParams.authQueryParams(
                username = credentials.username,
                password = credentials.password,
                apiType = credentials.backendApi
            ).forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
