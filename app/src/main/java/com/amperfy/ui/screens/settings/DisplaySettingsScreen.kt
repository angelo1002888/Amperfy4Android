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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.local.AppearanceMode
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.navigation.LocalMiniPlayerHeight

/**
 * Display & Interaction设置页面 (已重构为AppDelegate模式)
 * 对应iOS的DisplaySettingsView
 *
 * 包含（对齐 iOS 2.1.0 DisplaySettingsView.swift:36-155 行序）:
 * - 外观模式(深色/浅色)（Android 特有，iOS 用系统 UIUserInterfaceStyle）
 * - 触觉反馈
 * - 音乐播放器跳过按钮
 * - 歌词平滑滚动
 * - 详细信息显示
 * - 各种时长显示选项
 * - 播放器随机按钮禁用
 *
 * 注：iOS 1.2.3 设置页无「Player Display Style」选择器——Large/Compact 仅在播放器内切换
 * （iOS PlayerControlView.swift:556-587），Android 已同样实现于 PopupPlayer displayMode
 *
 * 重构说明:
 * - 使用hiltViewModel获取SettingsViewModel
 * - 所有设置项通过StateFlow响应式更新
 * - 所有修改通过ViewModel调用appDelegate.settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(
    onBackClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    // 从ViewModel获取所有设置的StateFlow
    val appearanceMode by viewModel.appearanceMode.collectAsState()
    val isHapticsEnabled by viewModel.isHapticsEnabled.collectAsState()
    val isShowMusicPlayerSkipButtons by viewModel.isShowMusicPlayerSkipButtons.collectAsState()
    val isLyricsSmoothScrolling by viewModel.isLyricsSmoothScrolling.collectAsState()
    val isShowDetailedInfo by viewModel.isShowDetailedInfo.collectAsState()
    val isShowSongDuration by viewModel.isShowSongDuration.collectAsState()
    val isShowAlbumDuration by viewModel.isShowAlbumDuration.collectAsState()
    val isShowArtistDuration by viewModel.isShowArtistDuration.collectAsState()
    val isPlayerShuffleButtonEnabled by viewModel.isPlayerShuffleButtonEnabled.collectAsState()
    val isPlayerRatingDisplayed by viewModel.isPlayerRatingDisplayed.collectAsState()

    // 获取MiniPlayer高度
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Settings",
                title = "Display", // iOS 页内标题为 "Display"（入口行为 "Display & Interaction"）
                centered = true
            )
        }
    ) { paddingValues ->
        SettingsList(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = miniPlayerHeight) // 添加底部padding避免被MiniPlayer遮挡
        ) {
            // 外观模式选择
            // 对应iOS代码第80-97行
            SettingsSection {
                SettingsMenuRow(
                    title = "Appearance",
                    selectedValue = appearanceMode.displayName,
                    options = listOf(
                        AppearanceMode.SYSTEM.displayName to {
                            viewModel.setAppearanceMode(AppearanceMode.SYSTEM)
                        },
                        AppearanceMode.LIGHT.displayName to {
                            viewModel.setAppearanceMode(AppearanceMode.LIGHT)
                        },
                        AppearanceMode.DARK.displayName to {
                            viewModel.setAppearanceMode(AppearanceMode.DARK)
                        }
                    )
                )
            }

            // 触觉反馈开关
            // 对应iOS代码第100-106行
            SettingsSection(
                footer = "Certain interactions provide haptic feedback. Long pressing to display the details menu will always trigger haptic feedback."
            ) {
                SettingsCheckBoxRow(
                    title = "Haptic Feedback",
                    checked = isHapticsEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setHapticsEnabled(enabled)
                    }
                )
            }

            // 音乐播放器跳过按钮
            // 对应iOS代码第100-110行（section 标题 "Music Player"）
            SettingsSection(
                title = "Music Player",
                footer = "Display skip forward button and skip backward button in music player in addition to previous/next buttons."
            ) {
                SettingsCheckBoxRow(
                    title = "Music Player Skip Buttons",
                    checked = isShowMusicPlayerSkipButtons,
                    onCheckedChange = { enabled ->
                        viewModel.setShowMusicPlayerSkipButtons(enabled)
                    }
                )
            }

            // 星级评分显示（iOS: settings.user.isShowRating，DisplaySettingsView.swift:130-136）
            // 语义覆盖播放器内评分控件 + 歌曲列表行尾星级（SongListItem，
            // 对应 iOS PlayableTableCell.swift:381-384）；Android 键名冻结沿用
            // isPlayerRatingDisplayed
            SettingsSection(
                footer = "Display star rating in song cells and the currently playing view."
            ) {
                SettingsCheckBoxRow(
                    title = "Show Star Rating",
                    checked = isPlayerRatingDisplayed,
                    onCheckedChange = { enabled ->
                        viewModel.setPlayerRatingDisplayed(enabled)
                    }
                )
            }

            // 歌词平滑滚动
            // 对应iOS代码第124-133行
            SettingsSection(
                footer = "Lyrics are smoothly scrolled to next line. Deactivating will result in jumping from line to line."
            ) {
                SettingsCheckBoxRow(
                    title = "Lyrics Smooth Scrolling",
                    checked = isLyricsSmoothScrolling,
                    onCheckedChange = { enabled ->
                        viewModel.setLyricsSmoothScrolling(enabled)
                    }
                )
            }

            // 详细信息显示
            // 对应iOS代码第136-146行（section 标题 "Information"）
            SettingsSection(
                title = "Information",
                footer = "Display detailed information (bitrate, ID) and button \"Copy ID to Clipboard\"."
            ) {
                SettingsCheckBoxRow(
                    title = "Detailed Information",
                    checked = isShowDetailedInfo,
                    onCheckedChange = { enabled ->
                        viewModel.setShowDetailedInfo(enabled)
                    }
                )
            }

            // 歌曲时长显示
            // 对应iOS代码第144-150行
            SettingsSection(
                footer = "Display song duration in table rows."
            ) {
                SettingsCheckBoxRow(
                    title = "Song Duration",
                    checked = isShowSongDuration,
                    onCheckedChange = { enabled ->
                        viewModel.setShowSongDuration(enabled)
                    }
                )
            }

            // 专辑时长显示
            // 对应iOS代码第152-158行
            SettingsSection(
                footer = "Display album duration in table rows."
            ) {
                SettingsCheckBoxRow(
                    title = "Album Duration",
                    checked = isShowAlbumDuration,
                    onCheckedChange = { enabled ->
                        viewModel.setShowAlbumDuration(enabled)
                    }
                )
            }

            // 艺术家时长显示
            // 对应iOS代码第160-166行
            SettingsSection(
                footer = "Display artist duration in table rows."
            ) {
                SettingsCheckBoxRow(
                    title = "Artist Duration",
                    checked = isShowArtistDuration,
                    onCheckedChange = { enabled ->
                        viewModel.setShowArtistDuration(enabled)
                    }
                )
            }

            // 禁用播放器随机按钮
            // 对应iOS代码第172-188行（section 标题 "Shuffle"）
            // 注意: iOS中是反向逻辑(Disable按钮,内部存储的是isEnabled)
            SettingsSection(
                title = "Shuffle",
                footer = "Player Shuffle Button is displayed but it can't be interacted with."
            ) {
                SettingsCheckBoxRow(
                    title = "Disable Player Shuffle Button",
                    checked = !isPlayerShuffleButtonEnabled,  // 反向逻辑
                    onCheckedChange = { disabled ->
                        val enabled = !disabled
                        viewModel.setPlayerShuffleButtonEnabled(enabled)
                    }
                )
            }
        }
    }
}
