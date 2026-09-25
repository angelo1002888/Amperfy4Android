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

package com.amperfy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.amperfy.core.AppDelegate
import com.amperfy.player.PlaybackService
import com.amperfy.data.local.AccountSetting
import com.amperfy.data.local.AppearanceMode
import com.amperfy.data.local.ScreenLockPreventionPreference
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.ui.navigation.AmperfyNavigation
import com.amperfy.ui.screens.LaunchScreenOverlay
import com.amperfy.ui.theme.AmperfyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Amperfy主Activity
 *
 * 完全模仿iOS版本的UI结构:
 * - 底部标签栏包含三个主标签: Library, Search, Settings
 * - Library标签展开后显示多个子分类(Artists, Albums, Songs等)
 * - 底部固定显示Mini Player(当有歌曲播放时)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var credentialsManager: CredentialsManager

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var appDelegate: AppDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系统 splash 与 Compose 复刻层（LaunchScreenOverlay）的交接：接管退场动画做交叉淡入。
        // 默认退场是**瞬时移除**，文字层会硬切浮现，观感突兀。
        // 此处让系统 splash 视图整体（背景 + Logo）在复刻层之上淡出——两层背景同色、Logo 同尺寸
        // 同位置（splash_icon.xml 的 44dp inset 使系统位图 = 复刻层 LOGO_SIZE 200dp），
        // 故可见效果就是「"Amperfy" 大标题与版权行柔和浮现」。
        // 注意：一旦设置本监听器，系统就不再自行移除 splash，**必须**自己调 provider.remove()
        installSplashScreen().setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .setDuration(SPLASH_CROSSFADE_MS)
                .withEndAction { provider.remove() }
                .start()
        }
        super.onCreate(savedInstanceState)

        // 暂停/播放 Quick Action：冷启动经快捷方式进入时处理 toggle
        handleTogglePlaybackIntent(intent)

        // 启用 Edge-to-Edge 模式 (Android 15+ / API 35+)
        // 内容可以延伸到状态栏和导航栏区域,实现 iOS 风格的沉浸式体验
        enableEdgeToEdge()

        // 对于低版本 Android,手动配置窗口 Insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            // 根据用户设置的 AppearanceMode 决定使用深色还是浅色主题
            // 使用 StateFlow 确保响应式更新
            val appearanceMode by settingsManager.appearanceMode.collectAsState()
            val isDarkTheme = when (appearanceMode) {
                AppearanceMode.LIGHT -> false  // 强制浅色
                AppearanceMode.DARK -> true    // 强制深色
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()  // 跟随系统
            }

            // 账户级主题色（iOS 2.1.0 账户级 themePreference → 全局 tintColor；
            // Android 对应 colorScheme.primary）。
            // 必须经 activeAccount 流取 ident 再 settings(ident)——activeSettings() 是快照式取流
            // （构造时读一次 activeIdent），不随账户切换更新（W5 已知约束）。
            val activeAccount by appDelegate.accounts.activeAccount.collectAsState()
            val activeIdent = activeAccount?.info?.ident

            // LocalMediaUrlRepository 的来源（Ampache 移植 Batch 2）：必须按 active 账户取
            // appDelegate.music（该账户后端类型对应的实现），不能用 Hilt 注入的 compat 单例
            // ——后者恒为 Subsonic 栈，Ampache 账户下会拼出 /rest/ URL 拿不到封面。
            // 未登录时 appDelegate.music 自身回退 compat 实例（AppDelegate:58-60）。
            val activeMusicRepository = remember(activeIdent) { appDelegate.music }
            val accountSetting by remember(activeIdent) {
                if (activeIdent != null) {
                    appDelegate.accountSettings.settings(activeIdent)
                } else {
                    // 未登录/无 active 账户：缺省 BLUE（对齐 iOS getSetting 回退 defaultSettings）
                    MutableStateFlow(AccountSetting())
                }
            }.collectAsState()

            // 屏幕常亮防锁屏（对应 iOS AppDelegate.configureLockScreenPrevention:170-183，
            // isIdleTimerDisabled → FLAG_KEEP_SCREEN_ON）
            val screenLockPrevention by settingsManager.screenLockPreventionPreference.collectAsState()
            KeepScreenOnEffect(screenLockPrevention)

            // 控制状态栏和导航栏的外观
            SideEffect {
                // 设置状态栏和导航栏颜色为透明
                // 使用 @Suppress 抑制弃用警告,因为低版本 Android 需要这些 API
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = Color.Transparent.toArgb()

                // 根据主题设置状态栏图标颜色
                // iOS 风格: 浅色背景用深色图标,深色背景用浅色图标
                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.isAppearanceLightStatusBars = !isDarkTheme  // 浅色主题用深色图标
                windowInsetsController.isAppearanceLightNavigationBars = !isDarkTheme  // 导航栏同步
            }

            AmperfyTheme(darkTheme = isDarkTheme, themeColor = accountSetting.themePreference) {
                Surface( modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 使用iOS风格的导航结构，并传入通过 Hilt 注入的单例实例
                        AmperfyNavigation(
                            credentialsManager = credentialsManager,
                            musicRepository = activeMusicRepository,
                            settingsManager = settingsManager
                        )

                        // 启动屏文字层（对齐 iOS LaunchScreen.storyboard 的 "Amperfy" 大标题与版权行，
                        // 系统 splash 画不了文本故接力复刻，见 LaunchScreenOverlay）。
                        // **rememberSaveable 是关键**：Activity 重建（旋转 / 深浅色切换 / 后台被回收后重建）
                        // 不得重现启动屏——保存态使重建后恒为 false，只有进程冷启动才初始 true
                        var showLaunchOverlay by rememberSaveable { mutableStateOf(true) }
                        LaunchedEffect(Unit) {
                            delay(LAUNCH_OVERLAY_HOLD_MS)
                            showLaunchOverlay = false
                        }
                        AnimatedVisibility(
                            visible = showLaunchOverlay,
                            // 首帧必须立即全显（本层是系统 splash 的接力，淡入会露出下方 UI）
                            enter = EnterTransition.None,
                            exit = fadeOut(tween(LAUNCH_OVERLAY_FADE_MS))
                        ) {
                            LaunchScreenOverlay()
                        }
                    }
                }
            }
        }
    }

    /**
     * 应用运行中经快捷方式再次进入时处理 toggle（配合 manifest singleTop）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTogglePlaybackIntent(intent)
    }

    /**
     * 消费暂停/播放 Quick Action：经 PlayerManager 现有 playPause 入口切换。
     * 不直启 Service，避免后台启动限制；PlayerManager 为 @Singleton，冷启动时也已就绪。
     */
    private fun handleTogglePlaybackIntent(intent: Intent?) {
        if (intent?.action == PlaybackService.ACTION_TOGGLE_PLAYBACK) {
            appDelegate.player.playPause()
        }
    }

    /**
     * 按设置维持屏幕常亮（对应 iOS ScreenLockPreventionPreference 三档语义）：
     * Never/Always 直接清/置 FLAG_KEEP_SCREEN_ON；Only When Charging 监听
     * ACTION_BATTERY_CHANGED 粘性广播随充电状态切换（对应 iOS batteryStateDidChange 通知，
     * iOS 判定 batteryState != .unplugged，即充电中或已充满）
     */
    @Composable
    private fun KeepScreenOnEffect(preference: ScreenLockPreventionPreference) {
        var isCharging by remember { mutableStateOf(false) }
        if (preference == ScreenLockPreventionPreference.ONLY_IF_CHARGING) {
            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        intent?.let { isCharging = it.isChargingStatus() }
                    }
                }
                // 粘性广播：注册即返回当前电池状态
                val sticky = registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                sticky?.let { isCharging = it.isChargingStatus() }
                onDispose { unregisterReceiver(receiver) }
            }
        }
        val keepScreenOn = when (preference) {
            ScreenLockPreventionPreference.ALWAYS -> true
            ScreenLockPreventionPreference.NEVER -> false
            ScreenLockPreventionPreference.ONLY_IF_CHARGING -> isCharging
        }
        DisposableEffect(keepScreenOn) {
            if (keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { }
        }
    }
}

/**
 * 系统 splash 退场（→ Compose 复刻层）的交叉淡入时长。
 *
 * iOS 为单屏启动屏、无此交接，故无真值可抄；本值只为消除「文字硬切浮现」的突兀感，
 * 属标定值、待真机微调。
 */
private const val SPLASH_CROSSFADE_MS = 300L

/**
 * 启动屏文字层（[LaunchScreenOverlay]）的停留时长。
 *
 * iOS LaunchScreen 没有「固定时长」这一概念——它的显示时长就是进程启动耗时，故本值无 iOS 真值可抄，
 * 属 Android 两段式启动（系统 splash → Compose 复刻层）的**标定值，待真机微调**。
 */
private const val LAUNCH_OVERLAY_HOLD_MS = 700L

/**
 * 启动屏文字层的淡出时长（250 → 400，为缓解进入真实 UI 时的突兀感拉长，2026-08-13）。
 *
 * iOS 无此过渡（单屏启动屏直接被首个 VC 顶掉）；此处淡出**仅为衔接 Android 两段式的观感**，
 * 缓动沿用 tween 默认的 FastOutSlowIn；同为标定值、待真机微调。
 */
private const val LAUNCH_OVERLAY_FADE_MS = 400

private fun Intent.isChargingStatus(): Boolean {
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
}
