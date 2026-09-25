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
import com.amperfy.utils.FileLogger
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 详细日志拦截器
 *
 * 记录每次网络请求的完整信息到Logcat和文件：
 * - 完整的请求URL（包括所有查询参数）
 * - 请求方法和Headers
 * - 请求Body（如果有）
 * - 完整的响应内容
 * - 响应Headers
 * - 响应状态码和耗时
 */
@Singleton
class DetailedLoggingInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "NetworkRequest"
        private const val MAX_LOGCAT_CONTENT = 1000 // Logcat最多显示1000字符预览
        private const val MAX_BODY_LOG_SIZE = 1024 * 100 // 最大记录100KB的响应体

        // 不应该记录响应体的Content-Type（二进制/流媒体）
        private val SKIP_BODY_CONTENT_TYPES = listOf(
            "audio/",
            "video/",
            "application/octet-stream",
            "image/"
        )

        // 不应该记录响应体的URL路径
        private val SKIP_BODY_URL_PATHS = listOf(
            "/rest/stream",
            "/rest/download",
            "/rest/getCoverArt"
        )

        /**
         * Query parameters whose values are credentials or session secrets:
         * Subsonic `p` (password) / `t` (token) / `s` (salt), Ampache `auth` / `ssid`
         * (session token) and a plain `password` parameter.
         */
        private val SENSITIVE_QUERY_PARAMS = listOf("p", "t", "s", "auth", "ssid", "password")

        /** Matches an Ampache `<auth>` element, including its CDATA form. */
        private val AUTH_ELEMENT_REGEX = Regex("<auth>.*?</auth>", RegexOption.DOT_MATCHES_ALL)

        /**
         * Returns [url] as a string with the value of every sensitive query parameter
         * replaced by `***`, so credentials never reach logcat or the log file.
         */
        private fun redactUrl(url: HttpUrl): String {
            val present = SENSITIVE_QUERY_PARAMS.filter { url.queryParameter(it) != null }
            if (present.isEmpty()) return url.toString()
            val builder = url.newBuilder()
            present.forEach { builder.setQueryParameter(it, "***") }
            return builder.build().toString()
        }

        /**
         * Masks the session token that an Ampache handshake returns inside `<auth>`
         * before the response body is logged.
         */
        private fun redactBody(body: String): String =
            if (body.contains("<auth>")) body.replace(AUTH_ELEMENT_REGEX, "<auth>***</auth>") else body
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // ==================== 记录请求信息 ====================
        // Redacted form only: every log line below must use this, never request.url directly
        val requestUrl = redactUrl(request.url)
        val requestLog = buildString {
            appendLine("========================================")
            appendLine("REQUEST")
            appendLine("URL: $requestUrl")
            appendLine("Method: ${request.method}")

            // 记录请求Headers
            if (request.headers.size > 0) {
                appendLine("Headers:")
                request.headers.forEach { (name, value) ->
                    // 隐藏敏感信息
                    val displayValue = if (name.lowercase().contains("password") ||
                                          name.lowercase().contains("token") ||
                                          name.lowercase().contains("authorization")) {
                        "***HIDDEN***"
                    } else {
                        value
                    }
                    appendLine("  $name: $displayValue")
                }
            }

            // 记录请求Body（如果有）
            request.body?.let { body ->
                try {
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    val contentType = body.contentType()
                    val charset = contentType?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8

                    val bodyContent = buffer.readString(charset)
                    appendLine("Request Body:")
                    appendLine(bodyContent)
                } catch (e: Exception) {
                    appendLine("Request Body: [Error reading: ${e.message}]")
                }
            }
        }

        // 输出到Logcat（简化版）
        Log.d(TAG, "REQUEST: $requestUrl")

        // 完整信息写入文件
        FileLogger.logMultiLine(TAG, requestLog)

        // ==================== 执行请求 ====================
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val errorLog = buildString {
                appendLine("========================================")
                appendLine("REQUEST FAILED")
                appendLine("URL: $requestUrl")
                appendLine("Duration: ${duration}ms")
                appendLine("Error: ${e.message}")
                appendLine("Stack Trace:")
                e.stackTrace.take(10).forEach { element ->
                    appendLine("  $element")
                }
            }

            Log.e(TAG, "REQUEST FAILED: $requestUrl - ${e.message}")
            FileLogger.logMultiLine(TAG, errorLog)
            throw e
        }

        val duration = System.currentTimeMillis() - startTime

        // ==================== 记录响应信息 ====================
        val responseBody = response.body
        var bodyContent: String? = null
        var skipBodyReason: String? = null

        if (responseBody != null) {
            val contentType = responseBody.contentType()
            val contentLength = responseBody.contentLength()
            val urlPath = request.url.encodedPath

            // 检查是否应该跳过记录响应体
            val shouldSkipBody = when {
                // 检查Content-Type是否为二进制/流媒体
                contentType != null && SKIP_BODY_CONTENT_TYPES.any {
                    contentType.toString().startsWith(it)
                } -> {
                    skipBodyReason = "Binary content type: $contentType"
                    true
                }
                // 检查URL路径是否为流媒体/下载
                SKIP_BODY_URL_PATHS.any { urlPath.contains(it) } -> {
                    skipBodyReason = "Streaming/download URL: $urlPath"
                    true
                }
                // 检查内容长度是否超过限制
                contentLength > MAX_BODY_LOG_SIZE -> {
                    skipBodyReason = "Content too large: ${contentLength / 1024}KB"
                    true
                }
                else -> false
            }

            if (!shouldSkipBody) {
                try {
                    val source = responseBody.source()
                    // 只请求有限的数据量
                    source.request(MAX_BODY_LOG_SIZE.toLong())
                    val buffer = source.buffer

                    val charset: Charset = contentType?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8

                    // 只克隆已缓冲的部分
                    val clonedBuffer = buffer.clone()
                    val rawBody = if (clonedBuffer.size > MAX_BODY_LOG_SIZE) {
                        clonedBuffer.readString(MAX_BODY_LOG_SIZE.toLong(), charset) + "... [TRUNCATED]"
                    } else {
                        clonedBuffer.readString(charset)
                    }
                    bodyContent = redactBody(rawBody)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading response body: ${e.message}")
                }
            }
        }

        val responseLog = buildString {
            appendLine("========================================")
            appendLine("RESPONSE")
            appendLine("URL: $requestUrl")
            appendLine("Status: ${response.code} ${response.message}")
            appendLine("Duration: ${duration}ms")

            // 记录响应Headers
            if (response.headers.size > 0) {
                appendLine("Headers:")
                response.headers.forEach { (name, value) ->
                    appendLine("  $name: $value")
                }
            }

            // 记录响应Body
            if (bodyContent != null) {
                appendLine("Response Body:")
                appendLine(bodyContent)
            } else if (skipBodyReason != null) {
                appendLine("Response Body: [SKIPPED - $skipBodyReason]")
            } else {
                appendLine("Response Body: [EMPTY or NULL]")
            }
            appendLine("========================================")
        }

        // Logcat输出简化版（只显示状态码和预览）
        Log.d(TAG, "RESPONSE: $requestUrl - ${response.code} (${duration}ms)")
        if (bodyContent != null && bodyContent.length > MAX_LOGCAT_CONTENT) {
            Log.d(TAG, "Body preview: ${bodyContent.take(MAX_LOGCAT_CONTENT)}... (see file for full content)")
        } else if (bodyContent != null) {
            Log.d(TAG, "Body: $bodyContent")
        }

        // 完整信息写入文件
        FileLogger.logMultiLine(TAG, responseLog)

        return response
    }
}
