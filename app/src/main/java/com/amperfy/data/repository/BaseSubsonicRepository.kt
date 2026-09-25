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

package com.amperfy.data.repository

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.*
import com.amperfy.data.remote.SubsonicApi
import com.amperfy.data.remote.dto.*

/**
 * 域实现共享基类：账户上下文解析（绑定账户/active 动态）与 Subsonic API 响应校验。
 *
 * 六个域实现（Library/Playlist/Podcast/Directory/Search/MediaUrl）共用同一套账户与
 * 校验设施，故上提为基类；三件设施（currentAccountId / currentCredentials / requireOk）
 * 自 MusicRepositoryImpl 原样搬移，行为不变。
 *
 * 出处：Repository 拆分批次 2——MusicRepositoryImpl 的物理拆分。
 */
internal abstract class BaseSubsonicRepository(
    protected val subsonicApi: SubsonicApi,
    protected val credentialsManager: CredentialsManager,
    protected val eventLogger: com.amperfy.core.EventLogger,
    private val networkMonitor: com.amperfy.core.NetworkMonitor,
    /**
     * 绑定账户（W5 每账户组件由 AccountComponentsRegistry 构造时传入）。
     * 为 null 时为「active 动态」实例（AppModule 提供，供 CompositionLocal 的 URL 构建等场景），
     * accountId/凭证均跟随当前 active 账户。
     */
    private val boundAccountInfo: AccountInfo?,
) {

    /**
     * 是否允许发起远程同步 —— 对应 iOS CommonLibrarySyncer.isSyncAllowed
     * （CommonLibrarySyncer.swift:37 `networkMonitor.isConnectedToNetwork`）。
     *
     * iOS 每个 LibrarySyncer 方法首行都是 `guard isSyncAllowed else { return }`：断网/飞行模式
     * 时**静默跳过**远程同步（不发请求、不写库、不报错），UI 照常展示本地数据。Android 各域
     * 实现的 sync/上行方法同口径在方法开头早退，避免离线时把请求失败顶成 "Sync failed" 错误。
     *
     * 与用户「离线模式开关」（`SettingsManager.isOfflineMode`，各 ViewModel 自行门控）互补：
     * 本守卫看的是**真实连通性**，开关没开时同样生效。
     *
     * 唯一例外为初始同步（iOS syncInitial 刻意无此守卫，SubsonicLibrarySyncer.swift:50）：
     * 离线时应报错走 "Sync Failed" + Retry，故 [com.amperfy.ui.screens.InitialSyncViewModel]
     * 在调用各 sync 前自行做连通性预检。
     */
    protected val isSyncAllowed: Boolean
        get() = networkMonitor.isConnectedToNetwork

    // 写入/主键构造用绑定账户 ident；未绑定（active 动态实例）时取当前 active 账户
    protected val currentAccountId: String
        get() = boundAccountInfo?.ident
            ?: currentCredentials()
                ?.let { AccountInfo.create(it.serverUrl, it.username).ident }
            ?: ""

    /**
     * 本 Repository 上下文的登录凭证（W5）：绑定账户实例取该账户命名空间凭证，
     * active 动态实例取 active 凭证。sync/API 方法一律经本方法解析凭证。
     */
    protected fun currentCredentials(): com.amperfy.data.model.LoginCredentials? =
        boundAccountInfo?.let { credentialsManager.getCredentials(it.ident) }
            ?: credentialsManager.getCredentials()

    /**
     * 本 Repository 上下文的账户身份（缓存目录分层用）：绑定账户实例取绑定值，
     * active 动态实例由 active 凭证推导；均无则 null（未登录，调用方跳过落盘）。
     * 语义与 [com.amperfy.data.download.DownloadManager] 的同名私有方法一致。
     */
    protected fun currentAccountInfo(): AccountInfo? =
        boundAccountInfo
            ?: currentCredentials()
                ?.let { AccountInfo.create(it.serverUrl, it.username) }

    /**
     * 校验 HTTP 与 Subsonic 业务状态并返回响应体。
     * Subsonic 出错时返回 HTTP 200 + status="failed" + error code（如 50 无权限、70 不存在），
     * 只判 isSuccessful 会把失败当成功写库（对应 iOS 每次调用后的 parseForError）。
     * 校验失败抛异常，由调用方的 try/catch 转为 Result.failure。
     */
    protected fun <T : SubsonicStatusResponse> requireOk(
        response: retrofit2.Response<SubsonicResponse<T>>,
        action: String
    ): T {
        if (!response.isSuccessful) {
            eventLogger.apiError(action, response.code(), "HTTP ${response.code()}")
            throw Exception("$action failed: HTTP ${response.code()}")
        }
        val body = response.body()?.subsonicResponse
            ?: run {
                eventLogger.apiError(action, response.code(), "empty response body")
                throw Exception("$action failed: empty response body")
            }
        if (body.status != "ok") {
            val detail = body.error?.let { " (error ${it.code}: ${it.message})" } ?: ""
            // statusCode 记录 Subsonic error code（对应 iOS ResponseError 的 statusCode）
            eventLogger.apiError(action, body.error?.code ?: 0, "status=${body.status}$detail")
            throw Exception("$action failed: status=${body.status}$detail")
        }
        return body
    }

}
