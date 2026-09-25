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

package com.amperfy.data.repository.ampache

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheApiException
import com.amperfy.data.remote.ampache.AmpacheAuthSession

/**
 * Ampache 侧统一日志 tag（与 Subsonic 侧的 "MusicRepository" 区分，便于按后端过滤 logcat）。
 */
internal const val AMPACHE_LOG_TAG: String = "AmpacheRepository"

/**
 * Ampache 六域实现共享基类——与 Subsonic 侧的
 * [com.amperfy.data.repository.BaseSubsonicRepository] 同构（账户上下文解析 + 统一错误处置），
 * 三件账户设施（isSyncAllowed / currentAccountId / currentCredentials / currentAccountInfo）
 * 自该类逐字复制，语义一字不改；差别只在「响应校验」——
 * Subsonic 是 `requireOk(retrofit2.Response)`，Ampache 的错误在解析层已转成
 * [AmpacheApiException]，故本类给的是 [runAmpache] 包装器。
 *
 * 出处：Ampache API 移植 Batch 2。
 */
internal abstract class BaseAmpacheRepository(
    protected val ampacheApi: AmpacheApi,
    protected val authSession: AmpacheAuthSession,
    protected val credentialsManager: CredentialsManager,
    protected val eventLogger: com.amperfy.core.EventLogger,
    private val networkMonitor: com.amperfy.core.NetworkMonitor,
    /**
     * 绑定账户（每账户组件由 AccountComponentsRegistry 构造时传入）。
     * 为 null 时为「active 动态」实例，accountId/凭证均跟随当前 active 账户。
     */
    private val boundAccountInfo: AccountInfo?,
) {

    /**
     * 是否允许发起远程同步 —— 对应 iOS CommonLibrarySyncer.isSyncAllowed
     * （CommonLibrarySyncer.swift:37）：Ampache/Subsonic 两侧 syncer 共用同一基类同一守卫，
     * 故 Ampache 各 sync 方法的早退口径与 Subsonic 侧逐字一致（断网静默跳过、不报错）。
     */
    protected val isSyncAllowed: Boolean
        get() = networkMonitor.isConnectedToNetwork

    /** 写入/主键构造用绑定账户 ident；未绑定（active 动态实例）时取当前 active 账户 */
    protected val currentAccountId: String
        get() = boundAccountInfo?.ident
            ?: currentCredentials()
                ?.let { AccountInfo.create(it.serverUrl, it.username).ident }
            ?: ""

    /**
     * 本 Repository 上下文的登录凭证：绑定账户实例取该账户命名空间凭证，
     * active 动态实例取 active 凭证。
     */
    protected fun currentCredentials(): LoginCredentials? =
        boundAccountInfo?.let { credentialsManager.getCredentials(it.ident) }
            ?: credentialsManager.getCredentials()

    /**
     * 本 Repository 上下文的账户身份（缓存目录分层用）；均无则 null（未登录）。
     */
    protected fun currentAccountInfo(): AccountInfo? =
        boundAccountInfo
            ?: currentCredentials()
                ?.let { AccountInfo.create(it.serverUrl, it.username) }

    /**
     * 统一执行 + 错误处置（对应 Subsonic 侧「try/catch + requireOk 里的 EventLogger 钩子」两件事）。
     *
     * - [kotlinx.coroutines.CancellationException] 原样透传（不得吞——BackgroundLibrarySyncer
     *   stop() 时若被当普通失败，专辑会被误标 isSongsSynced 而永久跳过，见 Subsonic 侧
     *   syncAlbumDetails 注释）；
     * - [AmpacheApiException]（服务器 `<error>` 节点）记 EventLogger 后转 Result.failure
     *   ——与 Subsonic requireOk 的 `eventLogger.apiError` 同一钩子位；
     * - 其余异常（IO/解析）记日志后转 Result.failure。
     *
     * 刻意不用 `inline`（派工稿的写法）：inline + suspend lambda 在本类里没有性能收益，
     * 且 protected inline 对成员可见性有额外约束，改为普通 suspend 形参更稳。
     */
    protected suspend fun <T> runAmpache(action: String, block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AmpacheApiException) {
            // statusCode 记 Ampache 错误码（4701/4704/4710…，对应 iOS ResponseError.statusCode）
            eventLogger.apiError(
                action,
                e.statusCode,
                buildString {
                    append(e.error.message.ifEmpty { e.error.errorType ?: "Ampache error" })
                    e.cleansedUrl?.takeIf { it.isNotEmpty() }?.let { append(" <").append(it).append(">") }
                },
            )
            Result.failure(e)
        } catch (e: Exception) {
            android.util.Log.e(AMPACHE_LOG_TAG, "$action error", e)
            Result.failure(e)
        }
}
