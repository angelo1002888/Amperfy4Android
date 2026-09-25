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

package com.amperfy.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.BackendApiType
import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.remote.LoginApiHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 登录界面ViewModel (已重构为AppDelegate模式)
 * 处理登录验证逻辑
 *
 * 重构说明:
 * - 从1个依赖(credentialsManager) → 1个依赖(appDelegate)
 * - 直接使用appDelegate.credentials
 * - 保持架构一致性，即使只有单一依赖
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    /**
     * 执行登录验证
     * 对应iOS: backendApi.login(apiType:credentials:)
     */
    fun login(
        serverUrl: String,
        username: String,
        password: String,
        apiType: BackendApiType = BackendApiType.NOT_DETECTED
    ) {
        // 输入验证
        if (serverUrl.isEmpty()) {
            _loginState.value = LoginState.Error("Please enter server URL")
            return
        }

        if (!serverUrl.startsWith("https://") && !serverUrl.startsWith("http://")) {
            _loginState.value = LoginState.Error("Server URL must start with https:// or http://")
            return
        }

        if (username.isEmpty()) {
            _loginState.value = LoginState.Error("Please enter username")
            return
        }

        if (password.isEmpty()) {
            _loginState.value = LoginState.Error("Please enter password")
            return
        }

        _loginState.value = LoginState.Loading

        viewModelScope.launch {
            try {
                // 计算密码哈希
                val passwordHash = appDelegate.credentials.hashPassword(password)

                // 创建凭证对象
                val credentials = LoginCredentials(
                    serverUrl = serverUrl.trim(),
                    username = username.trim(),
                    password = password,
                    passwordHash = passwordHash,
                    backendApi = apiType
                )

                // W5：登录/添加账户统一经 AccountManager.login（ping 校验 + 去重 + 写命名空间凭证 +
                // 加入 index + 自动切为 active）。LOGIN 与 ADD_ACCOUNT 走同一路径，仅错误文案区分。
                val result = appDelegate.accounts.login(credentials)
                result.fold(
                    onSuccess = { _loginState.value = LoginState.Success },
                    onFailure = { e ->
                        val msg = when (e) {
                            is com.amperfy.core.AccountAlreadyExistsException ->
                                "This account is already added."
                            else -> e.message
                                ?: "Authentication failed. Please check your credentials."
                        }
                        _loginState.value = LoginState.Error(msg)
                    }
                )
            } catch (e: Exception) {
                _loginState.value = LoginState.Error(
                    e.message ?: "Unable to connect to server. Please check your connection."
                )
            }
        }
    }

    /**
     * 检查是否已有保存的登录状态
     */
    fun checkSavedLogin(): Boolean {
        return appDelegate.credentials.isLoggedIn()
    }

    /**
     * 重置登录状态
     */
    fun resetLoginState() {
        _loginState.value = LoginState.Idle
    }
}

/**
 * 登录状态
 */
sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}
