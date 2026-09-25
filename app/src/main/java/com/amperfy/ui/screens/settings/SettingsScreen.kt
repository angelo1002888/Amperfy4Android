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

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.local.ScreenLockPreventionPreference
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.sheetGroupedBackground
import com.amperfy.utils.FileLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings主屏幕
 * 完全对应iOS的SettingsView
 *
 * 包含:
 * - 版本信息
 * - 离线模式开关
 * - 屏幕锁定防止选项
 * - 各个子设置页面的导航链接
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToDisplayAndInteraction: () -> Unit = {},
    // 账户页（路由字符串仍为 settings/server，历史命名保留）
    onNavigateToAccount: () -> Unit = {},
    onNavigateToLibrary: () -> Unit = {},
    onNavigateToPlayer: () -> Unit = {},
    onNavigateToEqualizer: () -> Unit = {},
    onNavigateToSwipe: () -> Unit = {},
    onNavigateToArtwork: () -> Unit = {},
    onNavigateToSupport: () -> Unit = {},
    onNavigateToLicense: () -> Unit = {},
    onNavigateToXCallback: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = hiltViewModel()

    val appVersion by viewModel.appVersion.collectAsState()
    val buildNumber by viewModel.buildNumber.collectAsState()

    // 获取当前设置值 - 使用collectAsState从StateFlow获取
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val screenLockPreventionPreference by viewModel.screenLockPreventionPreference.collectAsState()

    // 隐藏功能：连续三次点击Version启用/禁用文件日志
    var versionClickCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 获取MiniPlayer高度
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    // 大标题收起（对齐 iOS NavigationView 大标题的系统收起行为，SettingsView.swift:124-126）：
    // 内容上滑到大标题完全滚出视口后，顶部渐显居中 inline 小标题栏。
    // 本页是 verticalScroll 而非 LazyColumn，故阈值直接比对 scrollState.value 与实测大标题高度
    //（各列表页的 showCollapsedTitle 用 firstVisibleItemIndex，机制同源、判据不同）。
    val scrollState = rememberScrollState()
    var largeTitleHeightPx by remember { mutableIntStateOf(0) }
    val showCollapsedTitle by remember {
        derivedStateOf { largeTitleHeightPx > 0 && scrollState.value >= largeTitleHeightPx }
    }

    // 此前 Scaffold 带 Modifier.padding(top = 16.dp) 的空留白已删：标题自带上下 padding，
    // 那 16dp 在 Settings 作为底部弹层覆盖层时会在顶部露出一条底色条带
    Scaffold { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            SettingsList(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(bottom = miniPlayerHeight) // 添加底部padding避免被MiniPlayer遮挡
            ) {
                // 大标题 "Settings"（对应 iOS SettingsView.swift:125-127
                // NavigationView { list.navigationTitle("Settings") }）：
                // 置于滚动内容首部，随列表一起滚出视口；滚出后由上方覆盖的收起态小标题栏接管。
                // 实测高度（含上下 padding）即收起阈值
                IOSLargeTitle(
                    text = "Settings",
                    modifier = Modifier
                        // onSizeChanged 排在 padding **之前**，量到的才是含上下留白的整块高度
                        .onSizeChanged { if (it.height > 0) largeTitleHeightPx = it.height }
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                )

                // 版本信息Section
                // 对应iOS代码第58-65行
                SettingsSection {
                    // Version行 - 将点击检测放在版本号值上（隐藏功能：连续三次点击启用/禁用文件日志）
                    SettingsRow(title = "Version") {
                        Text(
                            text = appVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null // 无任何点击效果，完全隐秘
                            ) {
                                versionClickCount++

                                // 启动协程来重置计数器
                                scope.launch {
                                    delay(1000) // 1秒内未达到3次点击则重置
                                    if (versionClickCount < 3) {
                                        versionClickCount = 0
                                    }
                                }

                                // 检查是否连续三次点击
                                if (versionClickCount >= 3) {
                                    versionClickCount = 0 // 重置计数

                                    // 切换文件日志状态
                                    if (FileLogger.isEnabled()) {
                                        FileLogger.disable()
                                        Toast.makeText(
                                            context,
                                            "File logging disabled",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        FileLogger.enable()
                                        Toast.makeText(
                                            context,
                                            "File logging enabled\nPath: ${FileLogger.getLogDirectoryPath()}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                    SettingsDivider()
                    SettingsRow(title = "Build Number") {
                        SecondaryText(buildNumber)
                    }
                }
            
                // 离线模式Section
                // 对应iOS代码第67-73行
                SettingsSection(
                    footer = "Songs, podcasts, and artworks won't download offline. Searches are limited to the device, and playlists won't sync with the server."
                ) {
                    SettingsCheckBoxRow(
                        title = "Offline Mode",
                        checked = isOfflineMode,
                        onCheckedChange = { enabled ->
                            viewModel.setOfflineMode(enabled)
                        }
                    )
                }
            
                // 屏幕锁定防止Section
                // 对应iOS代码第75-92行
                SettingsSection {
                    SettingsMenuRow(
                        title = "Prevent Screen Lock",
                        selectedValue = screenLockPreventionPreference.displayName,
                        options = listOf(
                            ScreenLockPreventionPreference.NEVER.displayName to {
                                viewModel.screenLockPreventionOffPressed()
                            },
                            ScreenLockPreventionPreference.ALWAYS.displayName to {
                                viewModel.screenLockPreventionOnPressed()
                            },
                            ScreenLockPreventionPreference.ONLY_IF_CHARGING.displayName to {
                                viewModel.screenLockPreventionChargingPressed()
                            }
                        )
                    )
                }
            
                // 主要设置导航Section
                // 对应 iOS 2.1.0 SettingsView.swift:95-103（行序 Account → Display & Interaction →
                // Library → Player → Equalizer → Swipe → Artwork）
                SettingsSection {
                    SettingsNavigationRow(
                        title = "Account",
                        onClick = onNavigateToAccount
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "Display & Interaction",
                        onClick = onNavigateToDisplayAndInteraction
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "Library",
                        onClick = onNavigateToLibrary
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "Player, Stream & Scrobble",
                        onClick = onNavigateToPlayer
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "Equalizer",
                        onClick = onNavigateToEqualizer
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "Swipe",
                        onClick = onNavigateToSwipe
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "Artwork",
                        onClick = onNavigateToArtwork
                    )
                }
            
                // 其他设置导航Section
                // 对应iOS代码第105-113行
                SettingsSection {
                    SettingsNavigationRow(
                        title = "Support",
                        onClick = onNavigateToSupport
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "License",
                        onClick = onNavigateToLicense
                    )
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = "X-Callback-URL Documentation",
                        onClick = onNavigateToXCallback
                    )
                }
            }

        // 收起态小标题栏（覆盖在滚动内容之上，渐显/渐隐）——对应 iOS 大标题上滑后
        // 收起为导航栏 inline 居中小标题（系统行为，SettingsView.swift:124-126 的
        // NavigationView 默认 .automatic 显示模式）。
        // 已知限制：iOS 该栏为系统毛玻璃材质，Compose 无跨版本可用的实时 blur，
        // 此处以不透明 elevated 分组底色近似。
        AnimatedVisibility(
            visible = showCollapsedTitle,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // iOS inline 导航栏高度 44pt
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.sheetGroupedBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Settings",
                        // iOS 导航栏 inline 标题：17pt semibold
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                // 栏底细线恒 1 物理像素（同长按菜单分隔线口径）
                HairlineDivider(
                    color = MaterialTheme.colorScheme.separator
                )
            }
        }
        }
    }
}
