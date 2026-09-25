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

import coil.intercept.Interceptor
import coil.request.ImageResult
import com.amperfy.core.AmpacheUrlAuthRefresher

/**
 * Coil 拦截器：把 Ampache 封面 URL 里的会话 token 换成新鲜的（Ampache 移植 Batch 2）。
 *
 * 根因与播放侧同源（见 [AmpacheUrlAuthRefresher]）：封面 URL 由非 suspend 的
 * `MediaUrlRepository.getCoverArtUrl` 在 Compose 里同步拼出，只能带「内存中当前 token」；
 * 会话过期后这条 URL 会拿到 4701。本拦截器在**真正发请求前**（Coil 的 suspend 链路里）
 * 用 [AmpacheUrlAuthRefresher.withFreshAuth] 换新，必要时触发一次握手。
 *
 * 注册顺序（见 `AmperfyApplication.newImageLoader`）必须在
 * [ArtworkCacheKeyInterceptor] **之后**：缓存键要用「剔掉 auth 后」的规范化串，
 * 而规范化对换 token 前后的 URL 结果相同，故先定键再换 token，缓存命中不受影响。
 *
 * 非 Ampache 请求（Subsonic URL / 本地文件 / 占位资源）原样放行。
 */
class AmpacheArtworkAuthInterceptor(
    private val refresher: AmpacheUrlAuthRefresher,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data
        val url = when (data) {
            is String -> data
            is android.net.Uri -> data.toString()
            else -> null
        }
        if (url == null || !refresher.isAmpacheAuthUrl(url)) return chain.proceed(request)
        val fresh = refresher.withFreshAuth(url)
        if (fresh == url) return chain.proceed(request)
        return chain.proceed(request.newBuilder().data(fresh).build())
    }
}
