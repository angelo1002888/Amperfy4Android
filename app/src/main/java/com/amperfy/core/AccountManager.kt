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

import android.content.Context
import com.amperfy.data.local.AccountSettingsStore
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.ThemePreference
import com.amperfy.data.local.store.AccountLocalStore
import com.amperfy.data.model.Account
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.remote.LoginApiHelper
import com.amperfy.player.PlayerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** 账户事件（W5 完整接线） */
sealed class AccountEvent {
    data class Added(val account: Account) : AccountEvent()
    data class Removed(val ident: String) : AccountEvent()
    data class ActiveChanged(val account: Account?) : AccountEvent()
}

/** 登录去重：同 server+user 已存在 */
class AccountAlreadyExistsException(val account: Account) :
    Exception("Account already exists: ${account.userName}@${account.serverUrl}")

/**
 * 账户管理器（W5 真实现）
 *
 * - 多凭证存储经 [credentialsManager] 命名空间键；账户服务栈经 [registry]（登出才销毁）。
 * - 切换只换 UI 上下文，不停任何账户服务；登出走服务销毁 + 缓存/凭证清理（顺序冻结）。
 * - 与 [registry]/[playerProvider] 存在依赖环，用 [Provider] 惰性打断（PlayerManager → registry
 *   → …；AccountManager → PlayerManager 用 Provider）。
 */
@Singleton
class AccountManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialsManager: CredentialsManager,
    private val accountLocalStore: AccountLocalStore,
    private val registry: AccountComponentsRegistry,
    private val accountSettingsStore: AccountSettingsStore,
    private val playerProvider: Provider<PlayerManager>,
    private val eventLogger: EventLogger,
) {

    private val _activeAccount = MutableStateFlow(loadActiveAccount())
    val activeAccount: StateFlow<Account?> = _activeAccount.asStateFlow()

    private val _allAccounts = MutableStateFlow(loadAllAccounts())
    val allAccounts: StateFlow<List<Account>> = _allAccounts.asStateFlow()

    private val _accountEvents = MutableSharedFlow<AccountEvent>(extraBufferCapacity = 8)
    val accountEvents: SharedFlow<AccountEvent> = _accountEvents.asSharedFlow()

    /** active 账户的统一账户键；未登录时为 null */
    val activeAccountId: String?
        get() = _activeAccount.value?.info?.ident

    // ==================== 登录 ====================

    /**
     * 登录/添加账户：
     * ping 校验（含认证类型检测） → AccountInfo 相等性去重（同 server+user 已存在则失败） →
     * 写命名空间凭证 + upsert 账户（AccountLocalStore 建库侧租户根）+ 加入 accounts_index →
     * 自动 switchAccount。
     *
     * 认证类型：ping 用 [LoginApiHelper.detectApiType] 按「先 token 后 legacy」试
     * （对齐 iOS BackendProxy.login，BackendProxy.swift:281-311），**检测结果**（而非用户在
     * 登录页选的 Auto-Detect）随凭证持久化，此后该账户的每次请求据此选认证方式。
     */
    suspend fun login(credentials: LoginCredentials): Result<Account> {
        val detectedApiType = LoginApiHelper.detectApiType(
            credentials.serverUrl, credentials.username, credentials.password,
            selected = credentials.backendApi
        ) ?: return Result.failure(
            Exception("Authentication failed. Please check your credentials.")
        )
        val info = AccountInfo.create(credentials.serverUrl, credentials.username)
        val ident = info.ident

        // 相等性去重（AccountInfo 只看 serverHash+userHash）
        _allAccounts.value.firstOrNull { it.info == info }?.let {
            return Result.failure(AccountAlreadyExistsException(it))
        }

        // 走到这里即确定是新账户：先算好它的主题色（对齐 iOS Settings.swift:385-386 login 时赋值）
        val newTheme = themeColorForNextNewAccount()

        // 命名空间凭证（新账户尚未同步）
        credentialsManager.writeNamespacedCredentials(
            ident = ident,
            serverUrl = credentials.serverUrl,
            username = credentials.username,
            password = credentials.password,
            backendApi = detectedApiType,
            initialSyncCompleted = false,
            alternativeServerUrls = emptyList()
        )
        credentialsManager.setAccountsIndex((credentialsManager.getAccountsIndex() + ident).distinct())

        // 新账户自动分配未占用主题色（iOS 在 AccountSettings.login 内与凭证同批写入）
        accountSettingsStore.update(ident) { it.copy(themePreference = newTheme) }

        // 账户 upsert（收口 AccountLocalStore，专题 15 P1 批次 3；P4 起 = 建库侧 account_scope 租户根）
        val account = Account(info, credentials.serverUrl, credentials.username, detectedApiType)
        accountLocalStore.upsertAccount(account)

        _allAccounts.value = loadAllAccounts()
        _accountEvents.tryEmit(AccountEvent.Added(account))
        eventLogger.info("Account", message = "login: $ident")

        // 自动切为 active（AddAccount 成功即切）
        switchAccount(ident)
        return Result.success(account)
    }

    // ==================== 切换 ====================

    /**
     * 切换 active 账户：只换 UI 上下文，不停任何账户服务。
     * setActive → activeAccount 发射 → ActiveChanged 事件（UI 层重置 NavController）。
     */
    suspend fun switchAccount(ident: String) {
        if (!credentialsManager.hasNamespacedAccount(ident)) return
        credentialsManager.setActiveAccountIdent(ident)
        val account = accountFor(ident)
        _activeAccount.value = account
        _accountEvents.tryEmit(AccountEvent.ActiveChanged(account))
        eventLogger.info("Account", message = "switch active: $ident")
    }

    // ==================== 登出（顺序冻结） ====================

    /**
     * 登出账户（顺序冻结）：
     * 1. player.logoutAccount（队列含该账户曲目 → 清空停止）
     * 2. components.stop() + 3. registry.reset()（销毁组件）
     * 4. 删缓存目录 → 删账户设置/凭证键与账户实体（AccountLocalStore）/accounts_index
     * 若登出的是 active，切到剩余首个账户或进入未登录态。
     * 库实体由 AccountLocalStore.deleteAccount 删 account_scope 租户根后 FK CASCADE 即时级联清理
     * （专题 15 P4 批次 1）。
     */
    suspend fun logout(ident: String) {
        val info = parseInfo(ident) ?: return
        val wasActive = credentialsManager.getActiveAccountIdent() == ident

        // 1. 播放侧：队列含该账户曲目则清空停止（否则不动）
        playerProvider.get().logoutAccount(info)

        // 2+3. 停止并销毁该账户服务栈（若已懒建）
        registry.reset(info)

        // 4. 删缓存目录（不重建组件，直接删目录，与 DownloadManager 分层路径一致）
        deleteAccountCacheDir(info)

        // 删账户设置 + 凭证键 + 账户实体（account_scope 租户根，级联清库数据）+ accounts_index
        accountSettingsStore.removeAccount(ident)
        accountLocalStore.deleteAccount(ident)
        credentialsManager.removeAccount(ident)   // 删 <ident>.* 键 + 从 index 移除

        // active 切换
        val remaining = credentialsManager.getAccountsIndex()
        if (wasActive) {
            val next = remaining.firstOrNull()
            if (next != null) {
                switchAccount(next)
            } else {
                // 无剩余账户：进入未登录态（清全部凭证，UI 回登录页）
                credentialsManager.clearCredentials()
                _activeAccount.value = null
                _accountEvents.tryEmit(AccountEvent.ActiveChanged(null))
            }
        }

        _allAccounts.value = loadAllAccounts()
        _accountEvents.tryEmit(AccountEvent.Removed(ident))
        eventLogger.info("Account", message = "logout: $ident")
    }

    /**
     * 重建该账户的服务栈（Ampache 移植 Batch 2）——**后端类型变化后必须调用**。
     *
     * 场景：Update Password 时重新检测出的 [com.amperfy.data.model.BackendApiType] 与原值不同
     * （Ampache ↔ Subsonic*）。整套组件（Repository 实现族 / 握手会话 / 下载 / Scrobble）
     * 是在 [AccountComponentsRegistry.build] 里按类型定死的，不重建会继续按旧后端发请求。
     *
     * 实现即 `registry.reset`：先 stop（含作废 Ampache 会话）再从表里移除，
     * 下次 `get`/`getByIdent` 懒建出新类型的栈。**不动**凭证、库数据与播放队列
     * ——与 [logout] 的语义严格区分。
     */
    fun rebuildComponents(ident: String) {
        val info = parseInfo(ident) ?: return
        registry.reset(info)
        eventLogger.info("Account", message = "rebuild components: $ident")
    }

    /** 该账户是否需要初始同步 */
    fun needsInitialSync(ident: String): Boolean = credentialsManager.needsInitialSync(ident)

    // ==================== 内部 ====================

    /**
     * 新账户的主题色（对齐 iOS AccountSettings.themeColorForNextNewAccount，Settings.swift:449-462）：
     * 按枚举声明序（= iOS rawValue 升序 blue<green<red<yellow<orange<purple）取第一个未被现有账户
     * 占用的颜色；六色全被占用时取第一个不同于 active 账户当前色的；兜底 blue。
     *
     * 调用时机在新账户写入 `_allAccounts` 之前，故 usedThemes 天然不含自己；首个账户 usedThemes
     * 为空 → 恒为 BLUE（与既有行为一致）。
     */
    private fun themeColorForNextNewAccount(): ThemePreference {
        val usedThemes = _allAccounts.value
            .map { accountSettingsStore.settings(it.info.ident).value.themePreference }
            .toSet()
        ThemePreference.entries.firstOrNull { it !in usedThemes }?.let { return it }

        // 六色全占用：避开 active 账户当前色
        val activeIdent = _activeAccount.value?.info?.ident ?: return ThemePreference.BLUE
        val activeTheme = accountSettingsStore.settings(activeIdent).value.themePreference
        return ThemePreference.entries.firstOrNull { it != activeTheme } ?: ThemePreference.BLUE
    }

    private fun deleteAccountCacheDir(info: AccountInfo) {
        File(context.filesDir, "accounts/${info.serverHash}/${info.userHash}").deleteRecursively()
    }

    private fun loadActiveAccount(): Account? {
        val active = credentialsManager.getActiveAccountIdent()
        if (!active.isNullOrEmpty()) accountFor(active)?.let { return it }
        // 回退：旧单份凭证（迁移前）
        val creds = credentialsManager.getCredentials() ?: return null
        return Account(
            AccountInfo.create(creds.serverUrl, creds.username),
            creds.serverUrl, creds.username, creds.backendApi
        )
    }

    private fun loadAllAccounts(): List<Account> {
        val idents = credentialsManager.getAccountsIndex()
        if (idents.isNotEmpty()) return idents.mapNotNull { accountFor(it) }
        // 回退：单账户（迁移前）
        return listOfNotNull(loadActiveAccount())
    }

    /** 由 ident 构建 Account（凭证键取 server_url/username/backend_api；缺失返回 null） */
    private fun accountFor(ident: String): Account? {
        val info = parseInfo(ident) ?: return null
        val serverUrl = credentialsManager.getServerUrl(ident) ?: return null
        val creds = credentialsManager.getCredentials(ident) ?: return null
        return Account(info, serverUrl, creds.username, credentialsManager.getBackendApi(ident))
    }

    /** ident = "$serverHash-$userHash"（两段 hex），按首个 '-' 拆回 AccountInfo */
    private fun parseInfo(ident: String): AccountInfo? {
        val idx = ident.indexOf('-')
        if (idx <= 0 || idx >= ident.length - 1) return null
        return AccountInfo(ident.substring(0, idx), ident.substring(idx + 1))
    }
}
