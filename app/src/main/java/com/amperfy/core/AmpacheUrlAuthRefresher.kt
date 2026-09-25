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

package com.amperfy.core

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.BackendApiType
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.remote.ampache.AmpacheUrl
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ampache URL 的会话 token 保鲜器（Android 特有配套件，Ampache 移植 Batch 2）。
 *
 * **为什么需要它**：Ampache 的 stream / download / image.php URL 都带会话 token，
 * 而 token 过期前 5 分钟就要重握手（会话制认证）；偏偏 Android 侧构建这些
 * URL 的两个入口都是**非 suspend** 的冻结签名——`MediaUrlRepository.getStreamUrl`
 * （PlayerManager 同步调用）与 Compose 里的封面 URL 计算。iOS 没有这个问题：
 * 它的 `generateUrl(...)` 三个方法都是 `async`，装载时刻现拼。
 *
 * **方案**：URL 里先带「当前内存 token」（可能为空/过期），真正发请求的两条链在
 * **装载时刻**把 `auth` 换成新鲜 token：
 * - 播放：ExoPlayer 的 `ResolvingDataSource`（loader 线程，走 [withFreshAuthBlocking]）；
 * - 封面：Coil 的 `AmpacheArtworkAuthInterceptor`（suspend，走 [withFreshAuth]）。
 * 下载路径本就在协程内，直接由 Ampache 的 MediaUrl 实现 suspend 取新鲜 token，不经本类。
 *
 * 账户匹配：按 URL 的 scheme/host/port（+ 服务器子路径前缀）在**全部 Ampache 账户**里找
 * 该 URL 属于谁——媒体 URL 里没有账户标识，且多账户可能同时在播/在刷封面。
 */
@Singleton
class AmpacheUrlAuthRefresher @Inject constructor(
    private val registry: AccountComponentsRegistry,
    private val credentialsManager: CredentialsManager,
) {

    /** 是否为带会话 token 的 Ampache URL（API 端点或 image.php）；非 Ampache URL 直接放行 */
    fun isAmpacheAuthUrl(url: String?): Boolean = AmpacheUrl.isApiOrArtworkUrl(url)

    /**
     * 找出该 URL 所属账户的握手会话；找不到（非 Ampache 账户 / host 不匹配 / 组件未建）返回 null。
     *
     * 多个账户命中同一 host（同服务器多用户）时：优先取「当前 token 与 URL 里的 auth 相同」
     * 的那个账户——URL 就是它拼出来的；都不匹配则取第一个（换谁的 token 都能播自己的库，
     * 且 token 是账户级凭证，取错只会导致一次 401 后由上层重试）。
     */
    fun sessionFor(url: HttpUrl): AmpacheAuthSession? {
        val urlAuth = url.queryParameter("auth")
        var fallback: AmpacheAuthSession? = null
        for (ident in credentialsManager.getAccountsIndex()) {
            if (credentialsManager.getBackendApi(ident) != BackendApiType.AMPACHE) continue
            val serverUrl = credentialsManager.getCredentials(ident)?.serverUrl ?: continue
            if (!matchesServer(url, serverUrl)) continue
            val session = registry.getByIdent(ident)?.ampacheAuthSession ?: continue
            if (urlAuth != null && session.currentToken == urlAuth) return session
            if (fallback == null) fallback = session
        }
        return fallback
    }

    /**
     * 把 URL 里的 `auth` 换成新鲜 token（必要时触发一次握手）。
     *
     * 非 Ampache URL / 找不到会话 / 握手失败 → **原样返回**（不抛）：
     * 让请求照常发出去，由服务器返回 4701 后走各自链路的常规失败处理，
     * 好过在播放/图片加载路径上抛异常。
     */
    suspend fun withFreshAuth(urlString: String): String {
        if (!isAmpacheAuthUrl(urlString)) return urlString
        val url = urlString.toHttpUrlOrNull() ?: return urlString
        val session = sessionFor(url) ?: return urlString
        return try {
            AmpacheUrl.withAuthToken(urlString, session.reauthenticate().token)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "withFreshAuth failed, using url as is", e)
            urlString
        }
    }

    /**
     * [withFreshAuth] 的阻塞版——**仅供 ExoPlayer 的 `ResolvingDataSource.Resolver`**：
     * 该回调运行在 ExoPlayer 的 loader 线程（非主线程）且签名不是 suspend，
     * 只能就地阻塞等握手。绝不可在主线程调用。
     */
    fun withFreshAuthBlocking(urlString: String): String =
        runBlocking { withFreshAuth(urlString) }

    /**
     * URL 是否指向该账户的服务器：scheme + host + 端口（按 scheme 归一化）相同，
     * 且服务器 URL 带子路径时（如 `https://host/ampache`）URL 路径须以该子路径打头。
     */
    private fun matchesServer(url: HttpUrl, serverUrl: String): Boolean {
        val base = serverUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
        if (!url.host.equals(base.host, ignoreCase = true)) return false
        if (url.port != base.port) return false
        if (!url.scheme.equals(base.scheme, ignoreCase = true)) return false
        val basePath = base.encodedPath.trimEnd('/')
        return basePath.isEmpty() || url.encodedPath.startsWith(basePath)
    }

    private companion object {
        const val TAG = "AmpacheUrlAuth"
    }
}
