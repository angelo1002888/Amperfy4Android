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

package com.amperfy.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.amperfy.data.model.BackendApiType
import com.amperfy.data.model.LoginCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

/**
 * 凭证存储管理器
 * 使用Android Keystore加密存储敏感信息
 * 对应iOS的UserDefaults + Keychain
 */
@Singleton
class CredentialsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val sharedPreferences: SharedPreferences by lazy {
        try {
            // 使用EncryptedSharedPreferences加密存储(对应iOS Keychain)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                "amperfy_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 如果加密失败,降级使用普通SharedPreferences
            context.getSharedPreferences("amperfy_credentials", Context.MODE_PRIVATE)
        }
    }
    
    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"  // 改为保存明文密码（已加密存储）
        private const val KEY_BACKEND_API = "backend_api"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"  // iOS: initialSyncCompletionStatus

        // ===== 多账户命名空间键（W1 迁移新增；键 schema 冻结，W5 切换读取源）=====
        private const val KEY_ACCOUNTS_INDEX = "accounts_index"   // JSON 数组 [ident...]
        private const val KEY_ACTIVE_ACCOUNT = "active_account"   // ident
    }
    
    /**
     * 保存登录凭证
     * 对应iOS: storage.loginCredentials = credentials
     * 注意：密码使用EncryptedSharedPreferences加密存储
     */
    fun saveCredentials(credentials: LoginCredentials) {
        sharedPreferences.edit().apply {
            putString(KEY_SERVER_URL, credentials.serverUrl)
            putString(KEY_USERNAME, credentials.username)
            putString(KEY_PASSWORD, credentials.password)  // 保存明文密码（已加密）
            putInt(KEY_BACKEND_API, credentials.backendApi.value)
            putBoolean(KEY_IS_LOGGED_IN, true)
            apply()
        }
    }
    
    /**
     * 获取保存的登录凭证（active 账户）
     * 对应iOS: storage.loginCredentials
     *
     * W5 多账户读取源切换：存在 active_account 且该 ident 有命名空间键时读命名空间凭证；
     * 否则回退旧单份键（未迁移兜底，行为与单账户时期一致）。方法签名不变。
     */
    fun getCredentials(): LoginCredentials? {
        val active = getActiveAccountIdent()
        if (!active.isNullOrEmpty() && hasNamespacedAccount(active)) {
            return getCredentials(active)
        }
        // 回退：旧单份键（迁移前 / 命名空间键缺失）
        if (!isLoggedIn()) return null

        val serverUrl = sharedPreferences.getString(KEY_SERVER_URL, null) ?: return null
        val username = sharedPreferences.getString(KEY_USERNAME, null) ?: return null
        val password = sharedPreferences.getString(KEY_PASSWORD, null) ?: return null
        val backendApiValue = sharedPreferences.getInt(KEY_BACKEND_API, 0)
        val backendApi = BackendApiType.fromValue(backendApiValue)

        return LoginCredentials(
            serverUrl = serverUrl,
            username = username,
            password = password,  // 明文密码（加密存储）
            passwordHash = hashPassword(password),  // 动态计算hash
            backendApi = backendApi
        )
    }

    /**
     * 获取指定账户（ident）的登录凭证（W5 多账户 per-ident 读取）。
     * `serverUrl` 取该账户的 active_server_url（当前选中的连接 URL，Manage Server URLs 切换即改写），
     * 使调用方（getStreamUrl/getCoverArtUrl 的 baseUrl 参数）按账户所选 URL 构建。
     * 无命名空间键时返回 null。
     */
    fun getCredentials(ident: String): LoginCredentials? {
        if (!hasNamespacedAccount(ident)) return null
        val activeUrl = sharedPreferences.getString("$ident.active_server_url", null)
            ?: sharedPreferences.getString("$ident.server_url", null)
            ?: return null
        val username = sharedPreferences.getString("$ident.username", null) ?: return null
        val password = sharedPreferences.getString("$ident.password", null) ?: return null
        val backendApi = BackendApiType.fromValue(sharedPreferences.getInt("$ident.backend_api", 0))
        return LoginCredentials(
            serverUrl = activeUrl,
            username = username,
            password = password,
            passwordHash = hashPassword(password),
            backendApi = backendApi
        )
    }

    /**
     * 检查是否已登录
     * W5：任一多账户命名空间账户存在也视为已登录（兼容迁移后旧单份键被移除的场景）。
     */
    fun isLoggedIn(): Boolean {
        if (sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false)) return true
        return getAccountsIndex().isNotEmpty()
    }
    
    /**
     * 清除登录凭证(退出登录)
     */
    fun clearCredentials() {
        sharedPreferences.edit { clear() }
    }
    
    /**
     * 计算密码的SHA-256哈希
     * 对应iOS: StringHasher.sha256(dataString: password)
     */
    fun hashPassword(password: String): String {
        val bytes = password.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    /**
     * 检查是否需要初始同步
     * 对应iOS: storage.initialSyncCompletionStatus
     */
    fun needsInitialSync(): Boolean {
        return isLoggedIn() && !sharedPreferences.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
    }

    /**
     * 标记初始同步完成
     * 对应iOS: storage.initialSyncCompletionStatus = .completed
     */
    fun markInitialSyncCompleted() {
        sharedPreferences.edit {
            putBoolean(KEY_INITIAL_SYNC_COMPLETED, true)
        }
    }

    /**
     * 重置初始同步状态（用于重新同步）
     */
    fun resetInitialSyncStatus() {
        sharedPreferences.edit {
            putBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
        }
    }

    // ==================== 多账户命名空间存储（W1 追加） ====================
    // 仅「新增」这些键；getCredentials()/saveCredentials() 等既有单账户 API 行为不变（保留全部旧键）。
    // AccountManager 登录时写入（账户索引 + 命名空间凭证），PlaybackStateManager 等读 index。

    /** 读账户索引；迁移前为空列表（视为无多账户信息） */
    fun getAccountsIndex(): List<String> {
        val raw = sharedPreferences.getString(KEY_ACCOUNTS_INDEX, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setAccountsIndex(idents: List<String>) {
        val arr = org.json.JSONArray()
        idents.forEach { arr.put(it) }
        sharedPreferences.edit { putString(KEY_ACCOUNTS_INDEX, arr.toString()) }
    }

    fun getActiveAccountIdent(): String? =
        sharedPreferences.getString(KEY_ACTIVE_ACCOUNT, null)

    fun setActiveAccountIdent(ident: String) {
        sharedPreferences.edit { putString(KEY_ACTIVE_ACCOUNT, ident) }
    }

    /** 命名空间凭证键是否已写入（AccountManager / AccountComponentsRegistry 据此判定该账户凭证存在，缺失时跳过对应操作） */
    fun hasNamespacedAccount(ident: String): Boolean =
        sharedPreferences.contains("$ident.server_url")

    /**
     * 写单个账户的命名空间凭证键（键名冻结）：server_url/active_server_url/username/
     * password/backend_api/initial_sync_completed/alternative_server_urls（JSON 数组）。
     * 只新增不删旧键。
     */
    fun writeNamespacedCredentials(
        ident: String,
        serverUrl: String,
        username: String,
        password: String,
        backendApi: BackendApiType,
        initialSyncCompleted: Boolean,
        alternativeServerUrls: List<String>
    ) {
        val altArr = org.json.JSONArray()
        alternativeServerUrls.forEach { altArr.put(it) }
        sharedPreferences.edit {
            putString("$ident.server_url", serverUrl)
            putString("$ident.active_server_url", serverUrl)
            putString("$ident.username", username)
            putString("$ident.password", password)
            putInt("$ident.backend_api", backendApi.value)
            putBoolean("$ident.initial_sync_completed", initialSyncCompleted)
            putString("$ident.alternative_server_urls", altArr.toString())
        }
    }

    // ==================== per-ident 凭证读写（W5，键 schema 冻结） ====================

    /** 账户主 URL（登录时填写的原始 URL；备用 URL 切换不改此键） */
    fun getServerUrl(ident: String): String? =
        sharedPreferences.getString("$ident.server_url", null)

    /** 账户当前选中的连接 URL（Manage Server URLs 切换即改写；账户级拦截器每请求读取） */
    fun getActiveServerUrl(ident: String): String? =
        sharedPreferences.getString("$ident.active_server_url", null)
            ?: sharedPreferences.getString("$ident.server_url", null)

    fun setActiveServerUrl(ident: String, url: String) {
        sharedPreferences.edit { putString("$ident.active_server_url", url) }
    }

    /** 账户备用 URL 列表（JSON 数组） */
    fun getAlternativeServerUrls(ident: String): List<String> {
        val raw = sharedPreferences.getString("$ident.alternative_server_urls", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setAlternativeServerUrls(ident: String, urls: List<String>) {
        val arr = org.json.JSONArray()
        urls.forEach { arr.put(it) }
        sharedPreferences.edit { putString("$ident.alternative_server_urls", arr.toString()) }
    }

    /** 更新账户密码（Update Password） */
    fun setPassword(ident: String, password: String) {
        sharedPreferences.edit { putString("$ident.password", password) }
    }

    /**
     * 该账户的后端 API 类型（= 认证方式：SUBSONIC → MD5 token+salt、SUBSONIC_LEGACY → 明文 p）。
     *
     * 缺键/存量账户为 NOT_DETECTED(0)——MD5 认证落地前登录时写入的是用户在登录页选的
     * "Auto-Detect"，并非检测结果；这类账户按 token 处理
     * （见 [com.amperfy.data.remote.SubsonicAuthParams.usesLegacyPlaintextAuth]）。
     */
    fun getBackendApi(ident: String): BackendApiType =
        BackendApiType.fromValue(sharedPreferences.getInt("$ident.backend_api", 0))

    /**
     * 更新账户的后端 API 类型（登录检测结果、Update Password 后重新检测的结果）。
     * 对应 iOS 把 `BackendProxy.login` 返回的 BackenApiType 写回凭证（LoginVC/SettingsView）。
     */
    fun setBackendApi(ident: String, apiType: BackendApiType) {
        sharedPreferences.edit { putInt("$ident.backend_api", apiType.value) }
    }

    /** 该账户是否需要初始同步 */
    fun needsInitialSync(ident: String): Boolean =
        hasNamespacedAccount(ident) &&
            !sharedPreferences.getBoolean("$ident.initial_sync_completed", false)

    fun markInitialSyncCompleted(ident: String) {
        sharedPreferences.edit { putBoolean("$ident.initial_sync_completed", true) }
    }

    fun resetInitialSyncStatus(ident: String) {
        sharedPreferences.edit { putBoolean("$ident.initial_sync_completed", false) }
    }

    /**
     * 删除某账户的全部命名空间凭证键 + 从 accounts_index 移除（登出时调用）。
     * 若删除的是 active_account，交由调用方（AccountManager）另行切换 active。
     */
    fun removeAccount(ident: String) {
        sharedPreferences.edit {
            remove("$ident.server_url")
            remove("$ident.active_server_url")
            remove("$ident.username")
            remove("$ident.password")
            remove("$ident.backend_api")
            remove("$ident.initial_sync_completed")
            remove("$ident.alternative_server_urls")
        }
        setAccountsIndex(getAccountsIndex().filter { it != ident })
    }
}
