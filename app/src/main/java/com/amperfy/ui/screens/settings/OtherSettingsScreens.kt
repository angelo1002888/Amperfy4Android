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

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amperfy.ui.components.IOSNavTopBar

// LibrarySettingsScreen 已实现，移至 LibrarySettingsScreen.kt（Phase 5.1）

// EqualizerSettingsScreen 已实现（W7），移至 EqualizerSettingsScreen.kt

// ArtworkSettingsScreen 已实现，移至 ArtworkSettingsScreen.kt（Phase 5.3）

// SupportSettingsScreen 已实现，移至 SupportSettingsScreen.kt（Phase 5.5）

// LicenseSettingsScreen 已实现，移至 LicenseSettingsScreen.kt（Phase 5.6）

/**
 * X-Callback-URL设置页面
 * 对应iOS的XCallbackURLsSetttingsView
 * TODO: 实现完整功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XCallbackURLSettingsScreen(
    onBackClick: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Settings",
                title = "X-Callback-URL Documentation",
                centered = true
            )
        }
    ) { paddingValues ->
        PlaceholderContent(
            modifier = Modifier.padding(paddingValues),
            title = "X-Callback-URL Documentation"
        )
    }
}

/**
 * 占位符内容组件
 * 用于还未完全实现的设置页面
 */
@Composable
private fun PlaceholderContent(
    modifier: Modifier = Modifier,
    title: String
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Coming soon...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
