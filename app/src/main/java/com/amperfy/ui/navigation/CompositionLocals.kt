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

package com.amperfy.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.repository.MediaUrlRepository

/**
 * CompositionLocal for CredentialsManager
 *
 * 通过 CompositionLocal 在整个 Compose 树中共享 CredentialsManager 实例
 * 避免了参数层层传递的问题，同时保持单例模式
 *
 * 使用方式：
 * ```
 * @Composable
 * fun SomeComponent() {
 *     val credentialsManager = LocalCredentialsManager.current
 *     // 使用 credentialsManager...
 * }
 * ```
 */
val LocalCredentialsManager = compositionLocalOf<CredentialsManager> {
    error("CredentialsManager not provided. Make sure to provide it at the top level using CompositionLocalProvider.")
}

/**
 * CompositionLocal for MediaUrlRepository
 *
 * 通过 CompositionLocal 在整个 Compose 树中共享 URL 构建能力
 * Composable 只用它构建流媒体/封面 URL——类型已收窄至 MediaUrlRepository
 * （Repository 拆分批次 1；数据查询一律走 ViewModel）
 *
 * 使用方式：
 * ```
 * @Composable
 * fun SomeComponent() {
 *     val musicRepository = LocalMediaUrlRepository.current
 *     // 使用 musicRepository...
 * }
 * ```
 */
val LocalMediaUrlRepository = compositionLocalOf<MediaUrlRepository> {
    error("MediaUrlRepository not provided. Make sure to provide it at the top level using CompositionLocalProvider.")
}

/**
 * CompositionLocal for SettingsManager
 *
 * 通过 CompositionLocal 在整个 Compose 树中共享 SettingsManager 实例
 * 用于在 UI 组件中访问应用设置，特别是主题、外观等影响UI的设置
 *
 * 使用方式：
 * ```
 * @Composable
 * fun SomeComponent() {
 *     val settingsManager = LocalSettingsManager.current
 *     val appearanceMode by settingsManager.appearanceMode.collectAsState()
 *     // 使用 settings...
 * }
 * ```
 */
val LocalSettingsManager = compositionLocalOf<SettingsManager> {
    error("SettingsManager not provided. Make sure to provide it at the top level using CompositionLocalProvider.")
}
