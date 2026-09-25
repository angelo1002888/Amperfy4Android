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

package com.amperfy.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.amperfy.core.AppDelegate
import com.amperfy.data.local.ThemePreference
import com.amperfy.data.model.BackendApiType
import com.amperfy.data.remote.LoginApiHelper
import com.amperfy.data.remote.SubsonicAuthParams
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.systemRed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 账户设置 ViewModel（W5）——仅注入 AppDelegate。
 *
 * 账户级设置（Theme Color / Auto Cache 两项 / Scrobble streamed Songs）读写均走
 * AccountSettingsStore 的 active 账户命名空间（对齐 iOS 2.1.0：这些项在
 * AccountSettingsView 内，属账户维度）。账户切换时 UI 整体重建，VM 重构造即
 * 读到新 active 账户设置，故按当前 ident 一次性绑定。
 */
@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    val activeAccount = appDelegate.accounts.activeAccount
    val allAccounts = appDelegate.accounts.allAccounts

    /**
     * Client API Version 信息行（iOS AccountSettingsView.swift:206 取该后端 api 的
     * clientApiVersion）：客户端声明的 API 版本由**后端与认证方式**决定
     * ——Ampache 500000（AmpacheXmlServerApi.swift:92）；
     * Subsonic token 1.13.0 / legacy 1.11.0（SubsonicServerApi.swift:103-104、:135-138），
     * 不是服务器版本，也不再是过去写死的 1.16.1。
     */
    val clientApiVersion: String
        get() {
            val apiType = appDelegate.credentials.getCredentials()?.backendApi
                ?: BackendApiType.SUBSONIC
            if (apiType == BackendApiType.AMPACHE) return AmpacheAuthSession.CLIENT_API_VERSION
            return if (SubsonicAuthParams.usesLegacyPlaintextAuth(apiType)) {
                SubsonicAuthParams.CLIENT_API_VERSION_PRE_TOKEN
            } else {
                SubsonicAuthParams.CLIENT_API_VERSION_WITH_TOKEN
            }
        }
    private val _serverApiVersion = MutableStateFlow("-")
    val serverApiVersion: StateFlow<String> = _serverApiVersion

    /** ident 为空（未登录，正常不可达设置页）时：settings("") 回退缺省、update("") 空操作 */
    private val accountIdent: String get() = appDelegate.accounts.activeAccountId ?: ""

    // ===== 账户级设置（iOS AccountSettingsView.swift:139-183）=====

    val themePreference: StateFlow<ThemePreference> =
        appDelegate.accountSettings.settings(accountIdent)
            .map { it.themePreference }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                appDelegate.accountSettings.settings(accountIdent).value.themePreference
            )

    val isAutoCacheLatestSongs: StateFlow<Boolean> =
        appDelegate.accountSettings.settings(accountIdent)
            .map { it.isAutoCacheLatestSongs }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                appDelegate.accountSettings.settings(accountIdent).value.isAutoCacheLatestSongs
            )

    val isAutoCacheLatestPodcastEpisodes: StateFlow<Boolean> =
        appDelegate.accountSettings.settings(accountIdent)
            .map { it.isAutoCacheLatestPodcastEpisodes }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                appDelegate.accountSettings.settings(accountIdent).value.isAutoCacheLatestPodcastEpisodes
            )

    val isScrobbleStreamedItems: StateFlow<Boolean> =
        appDelegate.accountSettings.settings(accountIdent)
            .map { it.isScrobbleStreamedItems }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                appDelegate.accountSettings.settings(accountIdent).value.isScrobbleStreamedItems
            )

    /**
     * 设置主题色（对应 iOS setThemePreference）
     * 写入 AccountSettingsStore 后经 settings(ident) 流 → MainActivity → AmperfyTheme
     * （colorScheme.primary）即时生效
     */
    fun setThemePreference(preference: ThemePreference) =
        appDelegate.accountSettings.update(accountIdent) { it.copy(themePreference = preference) }

    fun setAutoCacheLatestSongs(enabled: Boolean) =
        appDelegate.accountSettings.update(accountIdent) { it.copy(isAutoCacheLatestSongs = enabled) }

    fun setAutoCacheLatestPodcastEpisodes(enabled: Boolean) =
        appDelegate.accountSettings.update(accountIdent) {
            it.copy(isAutoCacheLatestPodcastEpisodes = enabled)
        }

    fun setScrobbleStreamedItems(enabled: Boolean) =
        appDelegate.accountSettings.update(accountIdent) { it.copy(isScrobbleStreamedItems = enabled) }

    init {
        val creds = appDelegate.credentials.getCredentials()
        if (creds != null) {
            viewModelScope.launch {
                // 认证方式随该账户已存的类型（token / legacy），见 SubsonicAuthParams
                LoginApiHelper.fetchServerApiVersion(
                    creds.serverUrl, creds.username, creds.password, creds.backendApi
                )?.let { _serverApiVersion.value = it }
            }
        }
    }

    /**
     * 更新 active 账户密码：新密码校验成功才落盘（对齐 iOS updatePassword）。
     * 校验走**重新检测**认证类型（服务器可能已升级到支持 token），检测结果与新密码一并落盘。
     *
     * 后端类型若发生切换（Ampache ↔ Subsonic*，Ampache 移植 Batch 2），该账户的整套服务栈
     * 必须重建——Repository/会话管理器都是按类型在 AccountComponentsRegistry.build 里定死的，
     * 不重建会继续用旧后端的实现发请求。
     */
    fun updatePassword(newPassword: String, onResult: (Boolean) -> Unit) {
        val creds = appDelegate.credentials.getCredentials() ?: return onResult(false)
        val ident = appDelegate.accounts.activeAccountId ?: return onResult(false)
        viewModelScope.launch {
            val previous = appDelegate.credentials.getBackendApi(ident)
            val detected = LoginApiHelper.detectApiType(
                creds.serverUrl, creds.username, newPassword
            )
            if (detected != null) {
                appDelegate.credentials.setPassword(ident, newPassword)
                appDelegate.credentials.setBackendApi(ident, detected)
                if (detected != previous) {
                    appDelegate.accounts.rebuildComponents(ident)
                }
            }
            onResult(detected != null)
        }
    }

    /** Resync：停 BLS + 关离线 + 重置 active 账户初始同步标记，调用方随后重启 App */
    fun prepareResync() {
        val ident = appDelegate.accounts.activeAccountId ?: return
        runCatching { appDelegate.backgroundLibrarySyncer.stop() }
        appDelegate.settings.setOfflineMode(false)
        appDelegate.credentials.resetInitialSyncStatus(ident)
    }

    /**
     * 登出账户（顺序由 AccountManager.logout 保证）。
     * 登出后若无剩余账户 → 回调 onNoAccountsLeft（调用方重启回登录页）；否则 onDone。
     */
    fun logout(ident: String, onDone: () -> Unit, onNoAccountsLeft: () -> Unit) {
        viewModelScope.launch {
            appDelegate.accounts.logout(ident)
            if (allAccounts.value.isEmpty()) onNoAccountsLeft() else onDone()
        }
    }
}

/**
 * 账户设置页（对齐 iOS 2.1.0 AccountSettingsView.swift:116-254）
 *
 * Section 顺序：URL/Username 只读 → Theme Color → Auto Cache（Newest Songs /
 * Newest Podcast Episodes）→ Scrobble streamed Songs → Backend API/Server API
 * Version/Client API Version 只读 → Manage Server URLs → Update Password +
 * Resync Library → Logout。
 *
 * 注：iOS 2.1.0 本页无多账户列表与 Add Account 入口（账户切换/新增在主界面账户菜单），
 * Android 同步移除页内 "Accounts" 段；Add Account 走 MainScreen 的独立模态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    navController: NavHostController,
    viewModel: AccountSettingsViewModel = hiltViewModel()
) {
    val active by viewModel.activeAccount.collectAsState()
    val serverApiVersion by viewModel.serverApiVersion.collectAsState()
    val themePreference by viewModel.themePreference.collectAsState()
    val isAutoCacheLatestSongs by viewModel.isAutoCacheLatestSongs.collectAsState()
    val isAutoCacheLatestPodcastEpisodes by viewModel.isAutoCacheLatestPodcastEpisodes.collectAsState()
    val isScrobbleStreamedItems by viewModel.isScrobbleStreamedItems.collectAsState()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showResyncDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val miniPlayerHeight = LocalMiniPlayerHeight.current

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = { navController.popBackStack() },
                backTitle = "Settings",
                title = "Account",
                centered = true
            )
        }
    ) { padding ->
        SettingsList(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = miniPlayerHeight) // 底部避让 MiniPlayer
        ) {
            // 登录信息（iOS :120-137）
            SettingsSection {
                SettingsRow(title = "URL", trailing = {
                    Text(active?.serverUrl ?: "-", color = MaterialTheme.colorScheme.secondaryLabel)
                })
                SettingsDivider()
                SettingsRow(title = "Username", trailing = {
                    Text(active?.userName ?: "-", color = MaterialTheme.colorScheme.secondaryLabel)
                })
            }

            // 主题色（iOS :139-162，6 色）
            SettingsSection {
                SettingsMenuRow(
                    title = "Theme Color",
                    selectedValue = themePreference.displayName,
                    options = ThemePreference.entries.map { theme ->
                        theme.displayName to { viewModel.setThemePreference(theme) }
                    }
                )
            }

            // 自动缓存（iOS :164-173，header "Auto Cache"）
            SettingsSection(title = "Auto Cache") {
                SettingsCheckBoxRow(
                    title = "Newest Songs",
                    checked = isAutoCacheLatestSongs,
                    onCheckedChange = { viewModel.setAutoCacheLatestSongs(it) }
                )
                SettingsDivider()
                // 注：Android 暂无播客单集下载缓存（已知简化），
                // 该开关持久化但暂无消费方
                SettingsCheckBoxRow(
                    title = "Newest Podcast Episodes",
                    checked = isAutoCacheLatestPodcastEpisodes,
                    onCheckedChange = { viewModel.setAutoCacheLatestPodcastEpisodes(it) }
                )
            }

            // Scrobble（iOS :175-183）
            SettingsSection(
                footer = "Enable to scrobble all streamed songs, even if the server already marks them as played."
            ) {
                SettingsCheckBoxRow(
                    title = "Scrobble streamed Songs",
                    checked = isScrobbleStreamedItems,
                    onCheckedChange = { viewModel.setScrobbleStreamedItems(it) }
                )
            }

            // API 信息（iOS :185-210）
            SettingsSection {
                SettingsRow(title = "Backend API", trailing = {
                    Text(
                        active?.apiType?.getDisplayName() ?: "-",
                        color = MaterialTheme.colorScheme.secondaryLabel
                    )
                })
                SettingsDivider()
                SettingsRow(title = "Server API Version", trailing = {
                    Text(serverApiVersion, color = MaterialTheme.colorScheme.secondaryLabel)
                })
                SettingsDivider()
                SettingsRow(title = "Client API Version", trailing = {
                    Text(viewModel.clientApiVersion, color = MaterialTheme.colorScheme.secondaryLabel)
                })
            }

            // 服务器 URL 管理（iOS :212-216）
            SettingsSection {
                SettingsNavigationRow(
                    title = "Manage Server URLs",
                    onClick = { navController.navigate("settings/server/manage-urls") }
                )
            }

            // 密码与重新同步（iOS :218-236）
            SettingsSection {
                SettingsRow(title = "Update Password", onClick = { showPasswordDialog = true })
                SettingsDivider()
                SettingsRow(title = "Resync Library", onClick = { showResyncDialog = true })
            }

            // 登出（iOS :238-254，红色破坏性）
            SettingsSection {
                AccountDestructiveRow(title = "Logout", onClick = { showLogoutDialog = true })
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // Update Password 对话框
    if (showPasswordDialog) {
        UpdatePasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onConfirm = { newPw ->
                viewModel.updatePassword(newPw) { showPasswordDialog = false }
            }
        )
    }

    // Resync Library 确认（文案对齐 iOS :225-234；Android 无法原地重开同步流程，
    // 确认后结束进程由用户重启，重启后经初始同步门进入 InitialSyncScreen）
    if (showResyncDialog) {
        AlertDialog(
            onDismissRequest = { showResyncDialog = false },
            title = { Text("Resync Library") },
            text = {
                Text(
                    "This will reset your local library and start syncing again from the server. " +
                        "Your downloaded files will remain on this device.\n\n" +
                        "Do you want to resync your library?"
                )
            },
            confirmButton = {
                val ctx = LocalContextForRestart()
                TextButton(onClick = {
                    showResyncDialog = false
                    viewModel.prepareResync()
                    restartApp(ctx)
                }) { Text("Resync", color = MaterialTheme.colorScheme.systemRed) }
            },
            dismissButton = { TextButton(onClick = { showResyncDialog = false }) { Text("Cancel") } }
        )
    }

    // Logout 确认（文案对齐 iOS :244-252）
    if (showLogoutDialog) {
        val ctx = LocalContextForRestart()
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout") },
            text = {
                Text(
                    "Logging out will sign you out of the current account. Your login credentials " +
                        "will be removed, and all downloaded files for this account will be deleted.\n\n" +
                        "Do you want to log out?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ident = active?.info?.ident
                    showLogoutDialog = false
                    if (ident != null) {
                        viewModel.logout(
                            ident,
                            onDone = { navController.popBackStack() },
                            onNoAccountsLeft = { restartApp(ctx) }
                        )
                    }
                }) { Text("Logout", color = MaterialTheme.colorScheme.systemRed) }
            },
            dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") } }
        )
    }
}

/** 红色破坏性操作行（对应 iOS SettingsButtonRow actionType: .destructive） */
@Composable
private fun AccountDestructiveRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.systemRed
        )
    }
}

@Composable
private fun UpdatePasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("New Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        },
        confirmButton = {
            TextButton(onClick = { if (password.isNotEmpty()) onConfirm(password) }) { Text("Update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** 取 Application Context（进程重启用） */
@Composable
private fun LocalContextForRestart(): android.content.Context =
    androidx.compose.ui.platform.LocalContext.current.applicationContext

/** 进程重启（对齐 iOS restartByUser：结束进程，由用户重新启动） */
private fun restartApp(context: android.content.Context) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }, 150)
}
