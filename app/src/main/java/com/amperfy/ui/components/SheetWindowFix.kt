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

package com.amperfy.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/*
 * ModalBottomSheet 独立窗口的系统栏归位。
 *
 * 现象：sheet 弹出的瞬间状态栏变黑；iOS 上呈现 pageSheet 时系统状态栏无任何变化。
 *
 * 根因（material3 1.3.1 源码实证）：ModalBottomSheet 内容跑在自己的 Dialog 窗口里，
 * 该窗口在 `ModalBottomSheetDialogWrapper.init` 中一次性写死了系统栏图标明暗——
 *   val darkThemeEnabled = isSystemInDarkTheme()            // ModalBottomSheet.android.kt:281
 *   WindowCompat.getInsetsController(window, decorView).apply {
 *       isAppearanceLightStatusBars = !darkThemeEnabled     // 同文件 :541-542
 *       isAppearanceLightNavigationBars = !darkThemeEnabled
 *   }
 * 取的是**系统**深色模式，而本 App 的明暗来自用户设置 AppearanceMode（LIGHT/DARK/SYSTEM，
 * MainActivity:89-93）。二者不一致时（典型：App 设为 DARK 而系统为浅色），sheet 窗口作为最上层
 * 窗口把状态栏图标改成深色，深色图标落在 App 的深色背景上即「整条状态栏糊成黑的」。
 * 且该赋值只在窗口创建时执行一次，`updateParameters` 不再触碰，故会一直保持到 sheet 关闭。
 *
 * 反过来说，**状态栏底色不是黑的**：material3 的 EdgeToEdgeFloatingDialogTheme 已把
 * `android:statusBarColor` / `android:navigationBarColor` 设为 transparent、windowBackground
 * 透明，且 init 里已调用 `WindowCompat.setDecorFitsSystemWindows(window, false)`
 * （ModalBottomSheet.android.kt:490）——edge-to-edge 与透明底色这一层 material3 自己做全了，
 * 无需也不该再补。本辅助件只纠正它做错的那一项：图标明暗。
 */

/**
 * 把 sheet 的 Dialog 窗口的系统栏图标明暗**镜像回主窗口当前值**，使状态栏在 sheet 弹出前后
 * 观感一致（对齐 iOS：呈现模态不改变系统状态栏）。
 *
 * 用法：在 `ModalBottomSheet { ... }` 的 content 首行调用一次。
 * 置于 [SideEffect] 中，故每次重组都会重新贴合——App 明暗设置在 sheet 打开期间被改动时同样跟随。
 *
 * 兜底：拿不到 Dialog 窗口或宿主 Activity 时静默跳过（例如该 Composable 被复用到非 sheet 场景），
 * 不做任何副作用。
 */
@Composable
fun SheetSystemBarsFix() {
    val view = LocalView.current
    val activityWindow = LocalContext.current.findActivity()?.window

    SideEffect {
        val sheetWindow = view.findDialogWindow() ?: return@SideEffect
        val hostWindow = activityWindow ?: return@SideEffect

        val hostController = WindowCompat.getInsetsController(hostWindow, hostWindow.decorView)
        val sheetController = WindowCompat.getInsetsController(sheetWindow, sheetWindow.decorView)
        sheetController.isAppearanceLightStatusBars = hostController.isAppearanceLightStatusBars
        sheetController.isAppearanceLightNavigationBars =
            hostController.isAppearanceLightNavigationBars

        // 透明底色本由 EdgeToEdgeFloatingDialogTheme 提供（见文件头注释），这里再显式写一次仅作
        // 保险——某些 OEM ROM 会给 Dialog 窗口塞回不透明的系统栏底色。
        // statusBarColor/navigationBarColor 在 API 35 起弃用且无效，但 minSdk 26 的低版本设备仍需要
        @Suppress("DEPRECATION")
        sheetWindow.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        sheetWindow.navigationBarColor = Color.TRANSPARENT
    }
}

/**
 * 自 Compose 宿主 View 向上找承载 Dialog 的窗口。
 *
 * material3 的 `ModalBottomSheetDialogLayout` 实现了 [DialogWindowProvider]
 * （ModalBottomSheet.android.kt:327-334），正常是当前 View 的直接父级；这里仍逐级上溯，
 * 以免将来版本在中间插入包装层。
 */
private fun View.findDialogWindow(): Window? {
    var current: ViewParent? = parent
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return null
}

/** 自 Composable 的 Context 沿 [ContextWrapper] 链取宿主 Activity（Dialog 内的 Context 是包装过的） */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
