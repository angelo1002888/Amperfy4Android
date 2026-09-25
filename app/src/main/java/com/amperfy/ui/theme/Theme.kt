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

package com.amperfy.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.amperfy.data.local.ThemePreference

// ============================================================
// Material 3 ColorScheme 专用颜色常量
// 注意：所有 iOS 系统色已统一定义在 ColorExtensions.kt 中
// 这里仅保留 Material 3 ColorScheme 构建所需的内部常量
// ============================================================

// MARK: - Material 3 专用常量 (不对外暴露)
// 这些颜色仅用于构建 Material 3 的 lightColorScheme 和 darkColorScheme
// 所有对外使用都应该通过 MaterialTheme.colorScheme.* 访问

// 品牌色常量 (对应 ColorExtensions 中的 systemBlue 和 systemTeal)
private val AmperfyBlue = Color(0xFF007AFF)         // iOS .systemBlue - 用作 primary
private val AmperfyDarkBlue = Color(0xFF0051D5)     // 深蓝变体 - 用作 primaryContainer (Dark)
private val AmperfyLightBlue = Color(0xFF5AC8FA)    // iOS .systemTeal - 用作 secondary

// MARK: - Material 3 ColorScheme 定义

private val LightColorScheme = lightColorScheme(
    // 主色调 - iOS .systemBlue
    primary = AmperfyBlue,                              // iOS .systemBlue (#007AFF)
    onPrimary = Color.White,
    primaryContainer = AmperfyLightBlue,                // iOS .systemTeal (#5AC8FA)
    onPrimaryContainer = Color(0xFF001D35),

    // 次要色
    secondary = AmperfyLightBlue,                       // iOS .systemTeal (#5AC8FA)
    onSecondary = Color.White,
    secondaryContainer = LightExtendedColors.gray6,     // ✅ 使用扩展色
    onSecondaryContainer = LightExtendedColors.label,   // ✅ 使用语义色: iOS .label (Light)

    // 第三色
    tertiary = AmperfyDarkBlue,                         // 深蓝变体
    onTertiary = Color.White,

    // 背景和表面 - iOS .systemBackground / .systemGroupedBackground
    background = LightExtendedColors.gray6,             // ✅ 使用扩展色: iOS .systemGray6
    onBackground = LightExtendedColors.label,           // ✅ 使用语义色: iOS .label (Light)
    surface = Color(0xFFFFFFFF),                        // iOS .systemBackground (Light)
    onSurface = LightExtendedColors.label,              // ✅ 使用语义色: iOS .label (Light)
    surfaceVariant = LightExtendedColors.gray5,         // ✅ 使用扩展色: iOS .systemGray5
    onSurfaceVariant = LightExtendedColors.systemGray,  // ✅ 使用扩展色: iOS .systemGray

    // 错误 - iOS .systemRed
    error = LightExtendedColors.systemRed,              // ✅ 使用扩展色: iOS .systemRed
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // 轮廓和边框 - iOS .separator
    outline = LightExtendedColors.separator,            // ✅ 使用语义色: iOS .separator (Light)
    outlineVariant = LightExtendedColors.opaqueSeparator, // ✅ 使用语义色: iOS .opaqueSeparator (Light)

    // 阴影和遮罩
    scrim = Color.Black.copy(alpha = 0.5f),             // 标准模态背景
)

private val DarkColorScheme = darkColorScheme(
    // 主色调
    primary = AmperfyBlue,                              // iOS .systemBlue (#007AFF)
    onPrimary = Color.White,
    primaryContainer = AmperfyDarkBlue,                 // 深蓝变体
    onPrimaryContainer = AmperfyLightBlue,              // iOS .systemTeal (#5AC8FA)

    // 次要色
    secondary = AmperfyLightBlue,                       // iOS .systemTeal (#5AC8FA)
    onSecondary = Color(0xFF003544),
    secondaryContainer = DarkExtendedColors.gray6,      // ✅ 使用扩展色
    onSecondaryContainer = DarkExtendedColors.label,    // ✅ 使用语义色: iOS .label (Dark)

    // 第三色
    tertiary = AmperfyDarkBlue,                         // 深蓝变体
    onTertiary = Color.White,

    // 背景和表面
    background = Color(0xFF000000),                     // iOS .systemBackground (Dark)
    onBackground = DarkExtendedColors.label,            // ✅ 使用语义色: iOS .label (Dark)
    surface = DarkExtendedColors.gray6,                 // ✅ 使用扩展色: iOS .systemGray6 (Dark)
    onSurface = DarkExtendedColors.label,               // ✅ 使用语义色: iOS .label (Dark)
    surfaceVariant = DarkExtendedColors.gray5,          // ✅ 使用扩展色: iOS .systemGray5 (Dark)
    onSurfaceVariant = DarkExtendedColors.systemGray,   // ✅ 使用扩展色: iOS .systemGray

    // 错误 - 深色模式更亮
    error = DarkExtendedColors.systemRed,               // ✅ 使用扩展色: iOS .systemRed (Dark)
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // 轮廓和边框
    outline = DarkExtendedColors.separator,             // ✅ 使用语义色: iOS .separator (Dark)
    outlineVariant = DarkExtendedColors.opaqueSeparator, // ✅ 使用语义色: iOS .opaqueSeparator (Dark)

    // 阴影和遮罩
    scrim = Color.Black.copy(alpha = 0.7f),
)

// MARK: - 账户级主题色（iOS ThemePreference）

/**
 * 主题色 → primary（对应 iOS ThemePreference.asColor，SettingEnumerations.swift:308-323）。
 *
 * iOS 六色取 UIColor.systemX 动态色，这里直接复用 ColorExtensions 中的同名 iOS 系统色
 * （light/dark 两套，与 iOS 动态色的两个 appearance 变体一一对应）。
 */
private fun ThemePreference.asPrimaryColor(darkTheme: Boolean): Color {
    val colors = if (darkTheme) DarkExtendedColors else LightExtendedColors
    return when (this) {
        ThemePreference.BLUE -> colors.systemBlue
        ThemePreference.GREEN -> colors.systemGreen
        ThemePreference.RED -> colors.systemRed
        ThemePreference.YELLOW -> colors.systemYellow
        ThemePreference.ORANGE -> colors.systemOrange
        ThemePreference.PURPLE -> colors.systemPurple
    }
}

/**
 * 主题色 → onPrimary（对应 iOS ThemePreference.contrastColor，SettingEnumerations.swift:325-340）。
 * 仅 yellow 用黑，其余五色（**含 orange**）均为白——严格按 iOS 源码，勿凭观感改。
 */
private val ThemePreference.asContrastColor: Color
    get() = if (this == ThemePreference.YELLOW) Color.Black else Color.White

/**
 * App 主题
 *
 * @param themeColor 账户级主题色（iOS 2.1.0 AccountSetting.themePreference）。iOS 的应用点是
 *   `UIView.appearance().tintColor = theme.asColor`（AppDelegate 全局 tint），Android 对应
 *   `colorScheme.primary`——故这里只覆盖 primary/onPrimary 两项，不动 primaryContainer/
 *   secondary/tertiary（那几项是 Android 自有的 teal/深蓝派生装饰色，iOS 侧无对应 tint 语义）。
 *
 * 附带效应：BLUE 主题在深色模式下 primary 由此前写死的 #007AFF 变为 #0A84FF
 * （iOS systemBlue 的 Dark 变体真值），属对齐修正而非回归。
 */
@Composable
fun AmperfyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: ThemePreference = ThemePreference.BLUE,
    content: @Composable () -> Unit
) {
    // AppearanceMode的处理已经在MainActivity中完成
    // 这里直接使用传入的darkTheme参数
    // 主题色只在 darkTheme/themeColor 变化时重建 scheme，避免每次重组做 copy()
    val colorScheme = remember(darkTheme, themeColor) {
        val base = if (darkTheme) DarkColorScheme else LightColorScheme
        base.copy(
            primary = themeColor.asPrimaryColor(darkTheme),
            onPrimary = themeColor.asContrastColor
        )
    }
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
