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
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.systemRed

/**
 * 红色破坏性操作行（对应 iOS SettingsButtonRow actionType: .destructive）
 */
@Composable
private fun SettingsDestructiveRow(title: String, onClick: () -> Unit) {
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
 * LibrarySettingsScreen - 资料库设置页（Phase 5.1）
 *
 * 对应 iOS 2.1.0: LibrarySettingsView.swift:140-270
 * Section 顺序对齐 iOS：统计 / Background song sync / Cache。
 * 注：iOS 2.1.0 本页无 Auto Cache 段（已归 Account 页）、无 Resync（Account 页 "Resync Library"）。
 * 差异：iOS 的 Podcast Episodes 统计与播客缓存行——Android 无播客单集缓存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    onBackClick: () -> Unit = {},
    viewModel: LibrarySettingsViewModel = hiltViewModel()
) {
    val playlistCount by viewModel.playlistCount.collectAsState()
    val artistCount by viewModel.artistCount.collectAsState()
    val albumCount by viewModel.albumCount.collectAsState()
    val songCount by viewModel.songCount.collectAsState()
    val podcastCount by viewModel.podcastCount.collectAsState()
    val cachedSongCount by viewModel.cachedSongCount.collectAsState()
    val syncProgressText by viewModel.syncProgressText.collectAsState()
    val cacheSizeText by viewModel.cacheSizeText.collectAsState()
    val cacheSizeLimitMB by viewModel.cacheSizeLimitMB.collectAsState()

    var showDownloadAllDialog by remember { mutableStateOf(false) }
    var showDeleteCacheDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Settings",
                title = "Library",
                centered = true
            )
        }
    ) { paddingValues ->
        val miniPlayerHeight = LocalMiniPlayerHeight.current
        SettingsList(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                // 底部避让 MiniPlayer，防止最后一行被遮挡
                .padding(bottom = miniPlayerHeight)
        ) {
            // ===== 统计（无标题，对应 iOS 第一个 Section）=====
            SettingsSection {
                SettingsRow(title = "Playlists") { SecondaryText("$playlistCount") }
                SettingsDivider()
                SettingsRow(title = "Artists") { SecondaryText("$artistCount") }
                SettingsDivider()
                SettingsRow(title = "Albums") { SecondaryText("$albumCount") }
                SettingsDivider()
                SettingsRow(title = "Songs") { SecondaryText("$songCount") }
                SettingsDivider()
                // Podcasts 统计（Phase 6.4 补充，对应 iOS LibrarySettingsView 统计行）
                SettingsRow(title = "Podcasts") { SecondaryText("$podcastCount") }
                SettingsDivider()
                SettingsRow(title = "Initial Sync") { SecondaryText(viewModel.initialSyncStatus) }
            }

            // ===== 后台歌曲同步进度 =====
            SettingsSection(title = "Background song sync") {
                SettingsRow(title = "Progress") { SecondaryText(syncProgressText) }
            }

            // ===== 缓存 =====
            SettingsSection(
                title = "Cache",
                footer = "Exceeding the cache size limit blocks further downloads. Already downloaded songs are not deleted automatically."
            ) {
                SettingsRow(title = "Cached Songs") { SecondaryText("$cachedSongCount") }
                SettingsDivider()
                SettingsRow(title = "Complete Cache Size") { SecondaryText(cacheSizeText) }
                SettingsDivider()
                SettingsMenuRow(
                    title = "Cache Size Limit",
                    selectedValue = LibrarySettingsViewModel.formatCacheLimit(cacheSizeLimitMB),
                    options = LibrarySettingsViewModel.CACHE_LIMIT_OPTIONS_MB.map { mb ->
                        LibrarySettingsViewModel.formatCacheLimit(mb) to { viewModel.setCacheSizeLimit(mb) }
                    }
                )
                SettingsDivider()
                SettingsRow(
                    title = "Download all songs in library",
                    onClick = { showDownloadAllDialog = true }
                )
                SettingsDivider()
                SettingsDestructiveRow(
                    title = "Delete downloaded Songs",
                    onClick = { showDeleteCacheDialog = true }
                )
            }
        }
    }

    // Download all songs 确认（文案对齐 iOS）
    if (showDownloadAllDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadAllDialog = false },
            title = { Text("Download all songs in library") },
            text = {
                Text(
                    "This action will add all uncached songs in \"Library -> Songs\" to the download queue. " +
                        "High network traffic can be generated and device storage capacity will be taken. Continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.downloadAllSongs()
                    showDownloadAllDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete Cache 确认（文案对齐 iOS，Android 无播客故仅提歌曲）
    if (showDeleteCacheDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteCacheDialog = false },
            title = { Text("Delete Cache") },
            text = { Text("Are you sure to delete all downloaded Songs?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCompleteCache()
                    showDeleteCacheDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.systemRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCacheDialog = false }) { Text("Cancel") }
            }
        )
    }

}
