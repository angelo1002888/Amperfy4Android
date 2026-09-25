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

package com.amperfy.utils

import android.net.Uri
import coil.intercept.Interceptor
import coil.key.Keyer
import coil.request.ImageResult
import coil.request.Options
import com.amperfy.data.remote.SubsonicAuthParams
import com.amperfy.data.remote.ampache.AmpacheUrl

/**
 * 封面缓存键规范化（Android 特有配套件，iOS 无对应物）
 *
 * 根因：MD5 token+salt 认证**每请求现生成新盐**（对齐 iOS
 * SubsonicServerApi.createAuthApiUrlComponent，SubsonicServerApi.swift:233-238），
 * 于是同一封面每次生成的 URL 都不同；而 Coil 默认拿 URL 串当 memory/disk 缓存键，
 * 不规范化则封面缓存整体失效（每次进列表都重新下载）。iOS 侧封面由 ArtworkDownloadManager
 * 按 artwork id 自建缓存，URL 变化不影响，故没有这个问题。
 *
 * 规范化 = 剔除 u/p/t/s/v/c 六个认证与公共参数（[SubsonicAuthParams.stripAuthQueryParams]），
 * 保留 host/path/id/size，故不同封面、不同尺寸仍是不同键。真正发出的请求 URL 不变
 * （仍带完整认证参数），只有「键」被规范化。
 *
 * 两件套缺一不可（Coil 2 的 memory / disk 键取自不同来源）：
 * - [ArtworkCacheKeyer]：内存缓存**基础键**（尺寸等仍由 Coil 作为 extras 附加，
 *   不会出现「小图复用给大图」）；
 * - [ArtworkCacheKeyInterceptor]：磁盘缓存键（Coil 2 的 HttpUriFetcher 用
 *   `options.diskCacheKey ?: 请求 URL`，Keyer 管不到）。
 */
object ArtworkCacheKey {

    /**
     * 只对两类「带易变认证参数」的 URL 规范化，其它 model（本地文件/资源）原样放行：
     * - Subsonic REST（`/rest/`）：剔 u/p/t/s/v/c（每请求换盐）；
     * - Ampache `image.php`（Ampache 移植 Batch 2）：剔 auth/ssid（会话 token 每次握手都变，
     *   不剔则每轮会话后封面缓存整体作废）。
     */
    fun normalizedOrNull(data: Any?): String? {
        val raw = when (data) {
            is String -> data
            is Uri -> data.toString()
            else -> return null
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
        if (raw.contains("/rest/")) return SubsonicAuthParams.stripAuthQueryParams(raw)
        if (AmpacheUrl.isApiOrArtworkUrl(raw)) return AmpacheUrl.stripAuthQueryParams(raw)
        return null
    }
}

/** 内存缓存基础键（见 [ArtworkCacheKey]）；返回 null 表示交回 Coil 默认键 */
class ArtworkCacheKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? =
        ArtworkCacheKey.normalizedOrNull(data)
}

/** 磁盘缓存键（见 [ArtworkCacheKey]）；请求已显式指定 diskCacheKey 时不覆盖 */
class ArtworkCacheKeyInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.diskCacheKey != null) return chain.proceed(request)
        val normalized = ArtworkCacheKey.normalizedOrNull(request.data)
            ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().diskCacheKey(normalized).build())
    }
}
