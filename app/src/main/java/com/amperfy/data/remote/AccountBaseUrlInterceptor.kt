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

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.AccountInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 账户级 base URL 拦截器（W5，多账户网络栈定案）
 *
 * 每个账户的 AccountComponents 各建一份 Retrofit + SubsonicApi，配一个绑定该账户的本拦截器
 * （全局共享 OkHttpClient，经 newBuilder().addInterceptor() 挂本拦截器复用连接池）。
 *
 * 与退役的全局 DynamicBaseUrlInterceptor 的区别：
 * - 绑定构造时的 [accountInfo]，只按该账户的 active_server_url 重写 scheme/host/port；
 * - active_server_url 每请求实时读取，支持 Manage Server URLs 运行时切换。
 */
class AccountBaseUrlInterceptor(
    private val accountInfo: AccountInfo,
    private val credentialsManager: CredentialsManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val baseUrl = credentialsManager.getActiveServerUrl(accountInfo.ident)
        if (baseUrl.isNullOrEmpty()) {
            android.util.Log.w("AccountBaseUrl", "No server URL for account ${accountInfo.ident}")
            return chain.proceed(originalRequest)
        }
        val newBaseUrl = baseUrl.toHttpUrlOrNull()
        if (newBaseUrl == null) {
            android.util.Log.e("AccountBaseUrl", "Invalid server URL: $baseUrl")
            return chain.proceed(originalRequest)
        }
        val newUrl = originalRequest.url.newBuilder()
            .scheme(newBaseUrl.scheme)
            .host(newBaseUrl.host)
            .port(newBaseUrl.port)
            .build()
        return chain.proceed(originalRequest.newBuilder().url(newUrl).build())
    }
}
