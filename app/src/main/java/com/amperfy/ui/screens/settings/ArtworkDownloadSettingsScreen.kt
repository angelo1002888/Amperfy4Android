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

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.local.ArtworkDownloadSetting
import com.amperfy.ui.components.IOSNavTopBar

/**
 * ArtworkDownloadSettingsScreen - 封面下载策略页（Settings→Artwork→Artwork Download Settings）
 *
 * 对应 iOS: ArtworkDownloadSettingsView.swift——单个无标题 Section，
 * 列出 [ArtworkDownloadSetting] 全部选项，选中项行尾打勾。
 * 设置为账户级（AccountSettingsStore.artworkDownloadSetting），消费方为 ArtworkPolicyInterceptor。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtworkDownloadSettingsScreen(
    onBackClick: () -> Unit = {},
    viewModel: ArtworkSettingsViewModel = hiltViewModel()
) {
    val downloadSetting by viewModel.artworkDownloadSetting.collectAsState()

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Artwork",
                title = "Artwork Download",
                centered = true
            )
        }
    ) { paddingValues ->
        SettingsList(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection {
                ArtworkDownloadSetting.entries.forEachIndexed { index, option ->
                    if (index > 0) SettingsDivider()
                    ArtworkChoiceRow(
                        title = option.displayName,
                        selected = downloadSetting == option,
                        onClick = { viewModel.setArtworkDownloadSetting(option) }
                    )
                }
            }
        }
    }
}
