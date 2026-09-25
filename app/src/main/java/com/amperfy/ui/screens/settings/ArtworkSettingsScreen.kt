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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.systemRed

/**
 * ArtworkSettingsScreen - 封面设置页（Phase 5.3；2026-08-01 对齐 iOS 2.1.0 三级结构）
 *
 * 对应 iOS: ArtworkSettingsView.swift——两个 Section：
 * 1) 三条统计行（Artworks / Not checked Artworks / Cached Artworks）+ 两个操作按钮
 *    （仅在存在 active 账户时显示，对齐 ArtworkSettingsView.swift:94）
 * 2) 两个二级页导航行（Artwork Download Settings / Artwork Display Settings）
 *
 * Android 统计口径与「Not checked 恒 0」的原因见 [ArtworkSettingsViewModel] KDoc。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtworkSettingsScreen(
    onBackClick: () -> Unit = {},
    onNavigateToArtworkDownload: () -> Unit = {},
    onNavigateToArtworkDisplay: () -> Unit = {},
    viewModel: ArtworkSettingsViewModel = hiltViewModel()
) {
    val stats by viewModel.artworkStats.collectAsState()
    val hasActiveAccount by viewModel.hasActiveAccount.collectAsState()

    var showDownloadAllDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Settings",
                title = "Artwork",
                centered = true
            )
        }
    ) { paddingValues ->
        SettingsList(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // ===== 统计 + 操作（对应 iOS Section 1）=====
            SettingsSection {
                SettingsRow(title = "Artworks") { SecondaryText(stats.artworkCount.toString()) }
                SettingsDivider()
                SettingsRow(title = "Not checked Artworks") {
                    SecondaryText(stats.notCheckedCount.toString())
                }
                SettingsDivider()
                SettingsRow(title = "Cached Artworks") {
                    SecondaryText(stats.cachedArtworkCount.toString())
                }

                // 无 active 账户时两个操作无意义，整体隐藏（对齐 iOS 的 if let activeAccountInfo）
                if (hasActiveAccount) {
                    SettingsDivider()
                    SettingsRow(
                        title = "Download all artworks in library",
                        onClick = { showDownloadAllDialog = true }
                    )
                    SettingsDivider()
                    ArtworkDestructiveRow(
                        title = "Delete all downloaded artworks",
                        onClick = { showDeleteDialog = true }
                    )
                }
            }

            // ===== 二级页导航（对应 iOS Section 2，Download 在前）=====
            SettingsSection {
                SettingsNavigationRow(
                    title = "Artwork Download Settings",
                    onClick = onNavigateToArtworkDownload
                )
                SettingsDivider()
                SettingsNavigationRow(
                    title = "Artwork Display Settings",
                    onClick = onNavigateToArtworkDisplay
                )
            }
        }
    }

    // Download all artworks 确认（文案对齐 iOS ArtworkSettingsView.swift:100-103）
    if (showDownloadAllDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadAllDialog = false },
            title = { Text("Download all artworks in library") },
            text = {
                Text(
                    "This action will add all uncached artworks to the download queue. " +
                        "With this action a lot network traffic can be generated and " +
                        "device storage capacity will be taken. Continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.downloadAllArtworks()
                    showDownloadAllDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete all artworks 确认（文案逐字对齐 iOS ArtworkSettingsView.swift:120-123）
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete all downloaded artworks") },
            text = {
                Text(
                    "This action will delete downloaded artworks. " +
                        "Artworks embedded in song/podcast episode files will be kept. Continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteArtworkCache()
                    showDeleteDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.systemRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/** 红色破坏性操作行 */
@Composable
private fun ArtworkDestructiveRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.systemRed
        )
    }
}

/**
 * 单选行（选中项行尾对勾），两个封面二级页共用。
 * 对应 iOS: ArtworkDownloadSettingsView / ArtworkDisplaySettings 中的
 * `HStack { Text(option.description); Spacer(); if selected { check } }`
 */
@Composable
internal fun ArtworkChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    SettingsRow(
        title = title,
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    imageVector = AmperfyIcons.check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}
