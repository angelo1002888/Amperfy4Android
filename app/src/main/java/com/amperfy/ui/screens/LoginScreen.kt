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

import com.amperfy.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.BackendApiType
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.sheetBackground

/**
 * 登录/服务器配置屏幕 - iOS风格
 * 对应iOS的LoginVC（amperfy-2.1.0：LoginVC.swift）。
 * 同时用于首次登录与账户菜单「Add Account」（iOS 两处即同一 VC）。
 */
/** 登录页模式（W5）：首次登录 vs 从账户菜单添加账户 */
enum class LoginMode { LOGIN, ADD_ACCOUNT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    mode: LoginMode = LoginMode.LOGIN,
    // 非 null 时页面右上角显示关闭按钮（对齐 iOS 模态时的 closeButton；首启登录传 null 不显示）
    onClose: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val isAdd = mode == LoginMode.ADD_ACCOUNT
    // ADD_ACCOUNT 模式预填当前 active 账户的 serverUrl/username（密码留空），对齐 iOS LoginVC.viewIsAppearing 预填
    val credentialsManager = LocalCredentialsManager.current
    val prefill = if (isAdd) credentialsManager.getCredentials() else null
    var serverUrl by remember { mutableStateOf(if (isAdd) (prefill?.serverUrl ?: "") else BuildConfig.DEBUG_SERVER_URL) }
    var username by remember { mutableStateOf(if (isAdd) (prefill?.username ?: "") else BuildConfig.DEBUG_USERNAME) }
    var password by remember { mutableStateOf(if (isAdd) "" else BuildConfig.DEBUG_PASSWORD) }
    var passwordVisible by remember { mutableStateOf(false) }
    var selectedApiType by remember { mutableStateOf(BackendApiType.NOT_DETECTED) }
    var showApiSelector by remember { mutableStateOf(false) }

    val loginState by viewModel.loginState.collectAsState()

    // 监听登录状态（Success 为一次性事件：消费后立即复位为 Idle——LoginViewModel 是
    // Activity 作用域（首登页在导航顶层条件组合、Add Account 弹层复用同一实例），
    // 不复位则残留的 Success 会让下次组合的 Add Account 弹层在首帧自我关闭）
    LaunchedEffect(loginState) {
        if (loginState is LoginState.Success) {
            onLoginSuccess()
            viewModel.resetLoginState()
        }
    }

    // 销毁兜底复位：首登成功时 activeAccount 流驱动的分支切换可能抢在上面的
    // LaunchedEffect 协程启动之前销毁本页（协程未跑即被取消，Success 残留），
    // 故在离开组合时无条件复位——与协程调度时序无关，双保险。
    DisposableEffect(Unit) {
        onDispose { viewModel.resetLoginState() }
    }

    // 错误信息
    val errorMessage = when (loginState) {
        is LoginState.Error -> (loginState as LoginState.Error).message
        else -> ""
    }

    val isLoading = loginState is LoginState.Loading

    Box(
        modifier = Modifier
            .fillMaxSize()
            // ADD_ACCOUNT 为 formSheet 模态呈现（iOS CommonScreenOperations.swift:95），
            // 页底取 systemBackground @elevated；首启全屏登录仍用主界面 background
            .background(
                if (mode == LoginMode.ADD_ACCOUNT) MaterialTheme.colorScheme.sheetBackground
                else MaterialTheme.colorScheme.background
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Amperfy 标题（iOS amperfyLabel：50pt bold，tintColor）
            Text(
                text = "Amperfy",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.primary
            )

            // 标题 → 卡片 间距 30dp（iOS amperfyLabel.bottomAnchor 到 formGlassContainer -30）
            Spacer(modifier = Modifier.height(30.dp))

            // iOS formGlassContainer：圆角 20 + 主题色淡染玻璃效果 + 内边距 20（compact outerInset）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 先铺 surface 再叠主题色 0.08 淡染，模拟 iOS UIGlassEffect tint（alpha 0.1）
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(20.dp),
                // 行间距 15dp（iOS spaceInBetween）
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                // Server URL（iOS placeholder "https://localhost/ampache"）
                LoginTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        viewModel.resetLoginState()
                    },
                    placeholder = "https://localhost/ampache",
                    leadingIcon = AmperfyIcons.serverUrl,
                    keyboardType = KeyboardType.Uri,
                    enabled = !isLoading
                )

                // Username
                LoginTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        viewModel.resetLoginState()
                    },
                    placeholder = "Username",
                    leadingIcon = AmperfyIcons.userPerson,
                    keyboardType = KeyboardType.Text,
                    enabled = !isLoading
                )

                // Password（保留可见性切换 trailingIcon——Android 惯例，iOS 无此项但无伤）
                LoginTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        viewModel.resetLoginState()
                    },
                    placeholder = "Password",
                    leadingIcon = AmperfyIcons.password,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (passwordVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    enabled = !isLoading,
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (passwordVisible)
                                    AmperfyIcons.eyeFill
                                else
                                    AmperfyIcons.eyeSlashFill,
                                contentDescription = if (passwordVisible)
                                    "Hide password"
                                else
                                    "Show password",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )

                // API 行（iOS apiLabel + apiSelectorButton，密码行之下、卡片内）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "API:",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // iOS apiSelectorButton 用 UIMenu 上下文菜单（LoginVC.swift:454-472，
                    // showsMenuAsPrimaryAction），此处用项目现成 IOSStyleContextMenu 对齐；
                    // 选项纯文字无图标、无选中态标记（对齐 iOS UIAction）
                    Box {
                        OutlinedButton(
                            onClick = { showApiSelector = true },
                            enabled = !isLoading,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(selectedApiType.getDisplayName())
                            Icon(AmperfyIcons.chevronDown, contentDescription = "Select API")
                        }
                        // 菜单选项取 BackenApiType.selectorDescription（Auto-Detect/Ampache/
                        // Subsonic/Subsonic (legacy login)）；Action 点击后组件自动 dismiss
                        IOSStyleContextMenu(
                            expanded = showApiSelector,
                            onDismissRequest = { showApiSelector = false },
                            items = BackendApiType.entries.map { apiType ->
                                IOSContextMenuItem.Action(
                                    text = apiType.getDisplayName(),
                                    onClick = { selectedApiType = apiType }
                                )
                            }
                        )
                    }
                }
            }

            // 卡片 → Login 按钮 间距 30dp（iOS loginGlassContainer.topAnchor +30）
            Spacer(modifier = Modifier.height(30.dp))

            // Login 按钮（iOS：宽 140、高 40、居中、image .login + title "Login"）
            Button(
                onClick = {
                    viewModel.login(
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                        apiType = selectedApiType
                    )
                },
                modifier = Modifier
                    .width(140.dp)
                    .height(40.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        AmperfyIcons.login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Login", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 关闭按钮（仅模态显示，对齐 iOS closeButton 右上角 padding 16）
        if (onClose != null) {
            FilledTonalIconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(AmperfyIcons.xmark, contentDescription = "Close")
            }
        }
    }

    // 错误提示（iOS showErrorMsg：UIAlertController 标题 "Login failed" + 消息 + OK）
    if (errorMessage.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.resetLoginState() },
            title = { Text("Login failed") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetLoginState() }) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * 登录页紧凑输入行（对齐 iOS UITextField.configuteForLogin）：
 * 单行、圆角 5、0.5 细边框、左侧模板图标、仅 placeholder 无浮动 label，行高 40dp。
 */
@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    // iOS borderColor 取 .label（半透明前景色）、borderWidth 0.5
    val borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .border(0.5.dp, borderColor, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    innerTextField()
                }
                if (trailingIcon != null) {
                    trailingIcon()
                }
            }
        }
    )
}
