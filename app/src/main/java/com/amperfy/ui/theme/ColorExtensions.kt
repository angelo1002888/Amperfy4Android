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

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * iOS 扩展颜色
 *
 * 对应 iOS UtilitiesExtensions.swift 和 Utilities.swift 中的自定义颜色
 * Material 3 ColorScheme 无法完全表达 iOS 颜色系统，通过扩展属性补充
 *
 * iOS 源码参考（上游 BLeeEZ/amperfy 2.1.0）:
 * - Amperfy/UtilitiesExtensions.swift
 * - AmperfyKit/Common/Utilities.swift
 * - AmperfyKit/Assets/Assets.xcassets/
 */
data class IOSExtendedColors(
    // MARK: - iOS 系统色 (UIColor.system* 通用颜色)
    // 这些是 iOS 标准系统颜色，无特定语义，可用于任何场景
    // 参考: https://developer.apple.com/design/human-interface-guidelines/color

    val systemRed: Color,       // iOS .systemRed - 通用红色
    val systemGreen: Color,     // iOS .systemGreen - 通用绿色
    val systemBlue: Color,      // iOS .systemBlue - 通用蓝色
    val systemOrange: Color,    // iOS .systemOrange - 通用橙色
    val systemYellow: Color,    // iOS .systemYellow - 通用黄色
    val systemPink: Color,      // iOS .systemPink - 通用粉色
    val systemPurple: Color,    // iOS .systemPurple - 通用紫色
    val systemTeal: Color,      // iOS .systemTeal - 通用青色
    val systemIndigo: Color,    // iOS .systemIndigo - 通用靛蓝
    val systemGray: Color,      // iOS .systemGray - 通用灰色 (Light & Dark 一致)

    // MARK: - 语义颜色 (状态颜色)
    // 这些颜色有特定语义，应该在对应场景使用

    // Success - iOS .systemGreen (成功状态)
    val success: Color,
    val onSuccess: Color,

    // Warning - iOS .systemOrange (警告状态)
    val warning: Color,
    val onWarning: Color,

    // MARK: - Amperfy 自定义颜色

    // Rating/Theme - iOS Utilities.gold (#F1C242)
    // iOS 源码: Utilities.swift:334
    val gold: Color,

    // Favorite Heart - iOS Utilities.redHeart
    // iOS 源码: Utilities.swift:346
    val redHeart: Color,

    // Image Overlay - iOS ImageOverlayBackground.colorset
    // iOS 源码: PlayableTableCell.swift:140, PlayIndicator.swift:40
    val imageOverlay: Color,

    // MARK: - iOS 灰度系统 (systemGray2 ~ systemGray6)

    val gray2: Color,       // iOS .systemGray2
    val gray3: Color,       // iOS .systemGray3
    val gray4: Color,       // iOS .systemGray4 (按压高亮)
    val gray5: Color,       // iOS .systemGray5 (分割线)
    val gray6: Color,       // iOS .systemGray6 (背景)

    // MARK: - iOS 模态弹层背景 (elevated 提升层)
    // iOS 以 pageSheet / formSheet 呈现的 VC 走 elevated trait（UIUserInterfaceLevel.elevated），
    // 动态色取「提升层」值而非 base 值——深色下整体比主界面亮一级：
    // - systemBackground：base #000000 → elevated #1C1C1E
    // - systemGroupedBackground：base #000000 → elevated #1C1C1E
    // - secondarySystemGroupedBackground：base #1C1C1E → elevated #2C2C2E
    // 浅色下 elevated 与 base 同值（白 / #F2F2F7 / 白）。
    // 用途：全部 BottomSheet 弹层的宿主底色与其内部分组卡片底色，主界面列表不使用。

    // iOS .systemBackground @elevated —— 普通模态页底（PlaylistEdit/AddSongs/Selector/Add Account）
    val sheetBackground: Color,

    // iOS .systemGroupedBackground @elevated —— insetGrouped 模态页底（Settings / Home Preferences）
    val sheetGroupedBackground: Color,

    // iOS .secondarySystemGroupedBackground @elevated —— insetGrouped 模态内的分组卡片底
    val sheetSecondaryGroupedBackground: Color,

    // MARK: - 交互效果颜色

    // 按压时的高亮色 (iOS .systemGray4)
    // iOS 源码: UtilitiesExtensions.swift:344
    val pressHighlight: Color,

    // 悬停背景 (iOS .hoveredBackgroundColor)
    // iOS 源码: UtilitiesExtensions.swift:138
    val hoverBackground: Color,

    // MARK: - iOS 语义色系统 (UIColor Label/Fill/Separator)
    // 参考: https://developer.apple.com/design/human-interface-guidelines/color

    // Label 文字层级颜色 (主要用于文字显示)
    val label: Color,               // iOS .label - 主要文字 (Light: 黑色100%, Dark: 白色100%)
    val secondaryLabel: Color,      // iOS .secondaryLabel - 次要文字 (60% opacity)
    val tertiaryLabel: Color,       // iOS .tertiaryLabel - 第三级文字 (30% opacity)
    val quaternaryLabel: Color,     // iOS .quaternaryLabel - 第四级文字 (18% opacity)
    val placeholderText: Color,     // iOS .placeholderText - 占位符文字 (30% opacity)

    // Separator 分割线颜色
    val separator: Color,           // iOS .separator - 透明分割线 (带透明度)
    val opaqueSeparator: Color,     // iOS .opaqueSeparator - 不透明分割线

    // Fill 填充层级颜色 (主要用于背景填充)
    val systemFill: Color,          // iOS .systemFill - 主要填充
    val secondarySystemFill: Color, // iOS .secondarySystemFill - 次要填充
    val tertiarySystemFill: Color,  // iOS .tertiarySystemFill - 第三级填充
    val quaternarySystemFill: Color,// iOS .quaternarySystemFill - 第四级填充

    // Link 链接颜色
    val link: Color,                // iOS .link - 链接文字

    // MARK: - Switch 控件颜色

    // Switch 滑块颜色 - iOS Toggle 在 Light/Dark 模式下都是白色
    val switchThumb: Color,         // iOS Toggle thumb - 纯白色 (Light & Dark 一致)
)

/**
 * 浅色模式扩展颜色
 */
internal val LightExtendedColors = IOSExtendedColors(
    // iOS 系统色 (Light Mode)
    systemRed = Color(0xFFFF3B30),                      // iOS .systemRed (Light)
    systemGreen = Color(0xFF34C759),                    // iOS .systemGreen (Light)
    systemBlue = Color(0xFF007AFF),                     // iOS .systemBlue (Light)
    systemOrange = Color(0xFFFF9500),                   // iOS .systemOrange (Light)
    systemYellow = Color(0xFFFFCC00),                   // iOS .systemYellow (Light)
    systemPink = Color(0xFFFF2D55),                     // iOS .systemPink (Light)
    systemPurple = Color(0xFFAF52DE),                   // iOS .systemPurple (Light)
    systemTeal = Color(0xFF5AC8FA),                     // iOS .systemTeal (Light)
    systemIndigo = Color(0xFF5856D6),                   // iOS .systemIndigo (Light)
    systemGray = Color(0xFF8E8E93),                     // iOS .systemGray (Light & Dark 一致)

    // 语义颜色 (状态颜色)
    success = Color(0xFF34C759),                        // iOS .systemGreen (Light)
    onSuccess = Color.White,
    warning = Color(0xFFFF9500),                        // iOS .systemOrange (Light)
    onWarning = Color.White,

    // Amperfy 自定义
    gold = Color(0xFFF1C242),                           // iOS Utilities.gold
    redHeart = Color(0xFFFF0000).copy(alpha = 0.8f),   // iOS .redHeart
    imageOverlay = Color(0xFF292929).copy(alpha = 0.6f), // iOS ImageOverlayBackground

    // 灰度系统
    gray2 = Color(0xFFAEAEB2),                          // iOS .systemGray2 (Light)
    gray3 = Color(0xFFC7C7CC),                          // iOS .systemGray3 (Light)
    gray4 = Color(0xFFD1D1D6),                          // iOS .systemGray4 (Light)
    gray5 = Color(0xFFE5E5EA),                          // iOS .systemGray5 (Light)
    gray6 = Color(0xFFF2F2F7),                          // iOS .systemGray6 (Light)

    // 模态弹层背景（Light 下 elevated 与 base 同值）
    sheetBackground = Color(0xFFFFFFFF),                     // iOS .systemBackground (Light)
    sheetGroupedBackground = Color(0xFFF2F2F7),              // iOS .systemGroupedBackground (Light)
    sheetSecondaryGroupedBackground = Color(0xFFFFFFFF),     // iOS .secondarySystemGroupedBackground (Light)

    // 交互效果
    pressHighlight = Color(0xFFD1D1D6).copy(alpha = 0.3f),  // iOS TableCell.markAsFocused
    hoverBackground = Color(0xFFAEAEB2).copy(alpha = 0.2f), // iOS .hoveredBackgroundColor

    // iOS 语义色系统 (Light Mode)
    label = Color(0xFF000000),                              // iOS .label (Light) - 黑色 100%
    secondaryLabel = Color(0xFF3C3C43).copy(alpha = 0.6f),  // iOS .secondaryLabel (Light) - 60% opacity
    tertiaryLabel = Color(0xFF3C3C43).copy(alpha = 0.3f),   // iOS .tertiaryLabel (Light) - 30% opacity
    quaternaryLabel = Color(0xFF3C3C43).copy(alpha = 0.18f),// iOS .quaternaryLabel (Light) - 18% opacity
    placeholderText = Color(0xFF3C3C43).copy(alpha = 0.3f), // iOS .placeholderText (Light) - 30% opacity

    separator = Color(0xFF3C3C43).copy(alpha = 0.29f),      // iOS .separator (Light) - 29% opacity
    opaqueSeparator = Color(0xFFC6C6C8),                    // iOS .opaqueSeparator (Light)

    systemFill = Color(0xFF787880).copy(alpha = 0.20f),     // iOS .systemFill (Light)
    secondarySystemFill = Color(0xFF787880).copy(alpha = 0.16f), // iOS .secondarySystemFill (Light)
    tertiarySystemFill = Color(0xFF767680).copy(alpha = 0.12f),  // iOS .tertiarySystemFill (Light)
    quaternarySystemFill = Color(0xFF747480).copy(alpha = 0.08f),// iOS .quaternarySystemFill (Light)

    link = Color(0xFF007AFF),                               // iOS .link (Light) - 与 .systemBlue 相同

    // Switch 控件
    switchThumb = Color(0xFFFFFFFF),                        // iOS Toggle thumb - 纯白色
)

/**
 * 深色模式扩展颜色
 */
internal val DarkExtendedColors = IOSExtendedColors(
    // iOS 系统色 (Dark Mode) - 深色模式下更亮，提高对比度
    systemRed = Color(0xFFFF453A),                      // iOS .systemRed (Dark)
    systemGreen = Color(0xFF32D74B),                    // iOS .systemGreen (Dark)
    systemBlue = Color(0xFF0A84FF),                     // iOS .systemBlue (Dark)
    systemOrange = Color(0xFFFF9F0A),                   // iOS .systemOrange (Dark)
    systemYellow = Color(0xFFFFD60A),                   // iOS .systemYellow (Dark)
    systemPink = Color(0xFFFF375F),                     // iOS .systemPink (Dark)
    systemPurple = Color(0xFFBF5AF2),                   // iOS .systemPurple (Dark)
    systemTeal = Color(0xFF64D2FF),                     // iOS .systemTeal (Dark)
    systemIndigo = Color(0xFF5E5CE6),                   // iOS .systemIndigo (Dark)
    systemGray = Color(0xFF8E8E93),                     // iOS .systemGray (Light & Dark 一致)

    // 语义颜色 (状态颜色) - 深色模式更亮
    success = Color(0xFF32D74B),                        // iOS .systemGreen (Dark)
    onSuccess = Color(0xFF003A0F),
    warning = Color(0xFFFF9F0A),                        // iOS .systemOrange (Dark)
    onWarning = Color(0xFF4D2800),

    // Amperfy 自定义
    gold = Color(0xFFF1C242),                           // 金色保持一致
    redHeart = Color(0xFFFF0000).copy(alpha = 0.8f),   // 红心保持一致
    imageOverlay = Color(0xFF292929).copy(alpha = 0.6f), // 遮罩保持一致

    // 灰度系统 - 深色模式更暗
    gray2 = Color(0xFF636366),                          // iOS .systemGray2 (Dark)
    gray3 = Color(0xFF48484A),                          // iOS .systemGray3 (Dark)
    gray4 = Color(0xFF3A3A3C),                          // iOS .systemGray4 (Dark)
    gray5 = Color(0xFF2C2C2E),                          // iOS .systemGray5 (Dark)
    gray6 = Color(0xFF1C1C1E),                          // iOS .systemGray6 (Dark)

    // 模态弹层背景（Dark 走 elevated 提升层，整体比主界面亮一级）
    sheetBackground = Color(0xFF1C1C1E),                     // iOS .systemBackground @elevated (Dark)
    sheetGroupedBackground = Color(0xFF1C1C1E),              // iOS .systemGroupedBackground @elevated (Dark)
    sheetSecondaryGroupedBackground = Color(0xFF2C2C2E),     // iOS .secondarySystemGroupedBackground @elevated (Dark)

    // 交互效果
    pressHighlight = Color.White.copy(alpha = 0.1f),    // 深色模式用白色高亮
    hoverBackground = Color(0xFF636366).copy(alpha = 0.2f),

    // iOS 语义色系统 (Dark Mode)
    label = Color(0xFFFFFFFF),                              // iOS .label (Dark) - 白色 100%
    secondaryLabel = Color(0xFFEBEBF5).copy(alpha = 0.6f),  // iOS .secondaryLabel (Dark) - 60% opacity
    tertiaryLabel = Color(0xFFEBEBF5).copy(alpha = 0.3f),   // iOS .tertiaryLabel (Dark) - 30% opacity
    quaternaryLabel = Color(0xFFEBEBF5).copy(alpha = 0.18f),// iOS .quaternaryLabel (Dark) - 18% opacity
    placeholderText = Color(0xFFEBEBF5).copy(alpha = 0.3f), // iOS .placeholderText (Dark) - 30% opacity

    separator = Color(0xFF545458).copy(alpha = 0.6f),       // iOS .separator (Dark) - 60% opacity
    opaqueSeparator = Color(0xFF38383A),                    // iOS .opaqueSeparator (Dark)

    systemFill = Color(0xFF787880).copy(alpha = 0.36f),     // iOS .systemFill (Dark)
    secondarySystemFill = Color(0xFF787880).copy(alpha = 0.32f), // iOS .secondarySystemFill (Dark)
    tertiarySystemFill = Color(0xFF767680).copy(alpha = 0.24f),  // iOS .tertiarySystemFill (Dark)
    quaternarySystemFill = Color(0xFF747480).copy(alpha = 0.18f),// iOS .quaternarySystemFill (Dark)

    link = Color(0xFF0A84FF),                               // iOS .link (Dark) - 与 .systemBlue (Dark) 相同

    // Switch 控件
    switchThumb = Color(0xFFFFFFFF),                        // iOS Toggle thumb - 纯白色
)

/**
 * CompositionLocal for extended colors
 */
val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

// MARK: - ColorScheme 扩展属性

// ============================================================
// iOS 系统色扩展属性 (通用颜色，无特定语义)
// ============================================================

val ColorScheme.systemRed: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemRed

val ColorScheme.systemGreen: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemGreen

val ColorScheme.systemBlue: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemBlue

val ColorScheme.systemOrange: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemOrange

val ColorScheme.systemYellow: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemYellow

val ColorScheme.systemPink: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemPink

val ColorScheme.systemPurple: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemPurple

val ColorScheme.systemTeal: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemTeal

val ColorScheme.systemIndigo: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemIndigo

val ColorScheme.systemGray: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemGray

// ============================================================
// 语义颜色扩展属性 (状态颜色，有特定语义)
// ============================================================

val ColorScheme.success: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.success

val ColorScheme.onSuccess: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.onSuccess

val ColorScheme.warning: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.warning

val ColorScheme.onWarning: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.onWarning

val ColorScheme.gold: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.gold

val ColorScheme.redHeart: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.redHeart

val ColorScheme.imageOverlay: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.imageOverlay

val ColorScheme.gray2: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.gray2

val ColorScheme.gray3: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.gray3

val ColorScheme.gray4: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.gray4

val ColorScheme.gray5: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.gray5

val ColorScheme.gray6: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.gray6

// ============================================================
// iOS 模态弹层背景扩展属性（elevated 提升层，仅供 BottomSheet 弹层使用）
// ============================================================

val ColorScheme.sheetBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.sheetBackground

val ColorScheme.sheetGroupedBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.sheetGroupedBackground

val ColorScheme.sheetSecondaryGroupedBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.sheetSecondaryGroupedBackground

val ColorScheme.pressHighlight: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.pressHighlight

val ColorScheme.hoverBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.hoverBackground

// ============================================================
// iOS 语义色扩展属性 (Label/Fill/Separator 系统)
// ============================================================

// Label 文字层级颜色
val ColorScheme.label: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.label

val ColorScheme.secondaryLabel: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.secondaryLabel

val ColorScheme.tertiaryLabel: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.tertiaryLabel

val ColorScheme.quaternaryLabel: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.quaternaryLabel

val ColorScheme.placeholderText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.placeholderText

// Separator 分割线颜色
val ColorScheme.separator: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.separator

val ColorScheme.opaqueSeparator: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.opaqueSeparator

// Fill 填充层级颜色
val ColorScheme.systemFill: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.systemFill

val ColorScheme.secondarySystemFill: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.secondarySystemFill

val ColorScheme.tertiarySystemFill: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.tertiarySystemFill

val ColorScheme.quaternarySystemFill: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.quaternarySystemFill

// Link 链接颜色
val ColorScheme.link: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.link

// ============================================================
// Switch 控件颜色扩展属性
// ============================================================

// Switch 滑块颜色 - iOS Toggle 在 Light/Dark 下都是白色
val ColorScheme.switchThumb: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current.switchThumb
