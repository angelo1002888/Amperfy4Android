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
import androidx.lifecycle.ViewModel
import com.amperfy.core.AppDelegate
import com.amperfy.data.local.CacheTranscodingFormatPreference
import com.amperfy.data.local.StreamingFormatPreference
import com.amperfy.data.local.StreamingMaxBitratePreference
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * W4 新增设置项的轻量 ViewModel（流媒体格式分网络 + 单曲进度记忆）。
 * 放在本文件而非 SettingsViewModel：v2.1.0 并行开发期间 SettingsViewModel 不在
 * W4 边界内，整合期可并回 SettingsViewModel
 */
@HiltViewModel
class PlayerStreamSettingsViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    /** WiFi 流媒体格式（iOS 2.0.0 起分网络，生效于 MusicRepository.getStreamUrl） */
    val streamingFormatWifiPreference: StateFlow<StreamingFormatPreference>
        get() = appDelegate.settings.streamingFormatWifiPreference

    /** 蜂窝流媒体格式 */
    val streamingFormatCellularPreference: StateFlow<StreamingFormatPreference>
        get() = appDelegate.settings.streamingFormatCellularPreference

    /**
     * 记住单曲播放进度（iOS 2.1.0 isPlayerSongPlaybackResumeEnabled，页内行名
     * "Song Playback Resume"；生效于 PlayerManager 装载/离场钩子）
     */
    val isRememberSongPlaybackProgress: StateFlow<Boolean>
        get() = appDelegate.settings.isRememberSongPlaybackProgress

    fun setStreamingFormatWifiPreference(preference: StreamingFormatPreference) {
        appDelegate.settings.setStreamingFormatWifiPreference(preference)
    }

    fun setStreamingFormatCellularPreference(preference: StreamingFormatPreference) {
        appDelegate.settings.setStreamingFormatCellularPreference(preference)
    }

    fun setRememberSongPlaybackProgress(enabled: Boolean) {
        appDelegate.settings.setRememberSongPlaybackProgress(enabled)
    }
}

/**
 * Player, Stream & Scrobble设置页面 (已重构为AppDelegate模式)
 * 对应 iOS 2.1.0 PlayerSettingsView.swift:71-195（段序与文案逐段对齐）:
 *
 * 1. Enable ReplayGain                       —— iOS :74-88
 * 2. Auto cache played Songs                 —— iOS :90-96
 * 3. Song Playback Resume                    —— iOS :98-106
 * 4. Manual Playback                         —— iOS :108-110
 * 5. Cellular Streaming Format (Transcoding) —— iOS :112-126
 * 6. Cellular Streaming Bitrate Limit        —— iOS :128-141
 * 7. WiFi Streaming Format (Transcoding)     —— iOS :143-156
 * 8. WiFi Streaming Bitrate Limit            —— iOS :158-171
 * 9. Cache Format (Transcoding)              —— iOS :173-194
 *
 * 注：Scrobble streamed Songs 为账户级设置，已随 2.1.0 对齐移至 Account 页
 * （iOS AccountSettingsView.swift:175-183）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsScreen(
    onBackClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    streamViewModel: PlayerStreamSettingsViewModel = hiltViewModel()
) {
    // 从ViewModel获取所有设置的StateFlow
    val isReplayGainEnabled by viewModel.isReplayGainEnabled.collectAsState()
    val isPlayerAutoCachePlayedItems by viewModel.isPlayerAutoCachePlayedItems.collectAsState()
    val isPlaybackStartOnlyOnPlay by viewModel.isPlaybackStartOnlyOnPlay.collectAsState()
    val isRememberSongPlaybackProgress by streamViewModel.isRememberSongPlaybackProgress.collectAsState()
    val streamingMaxBitrateWifiPreference by viewModel.streamingMaxBitrateWifiPreference.collectAsState()
    val streamingMaxBitrateCellularPreference by viewModel.streamingMaxBitrateCellularPreference.collectAsState()
    val streamingFormatWifiPreference by streamViewModel.streamingFormatWifiPreference.collectAsState()
    val streamingFormatCellularPreference by streamViewModel.streamingFormatCellularPreference.collectAsState()
    val cacheTranscodingFormatPreference by viewModel.cacheTranscodingFormatPreference.collectAsState()

    // 获取MiniPlayer高度
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Settings",
                title = "Player, Stream & Scrobble",
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
            // ReplayGain（对应 iOS PlayerSettingsView.swift:74-88）
            // 消费链已存在：SettingsManager.isReplayGainEnabled → AudioChainCoordinator → GainProcessor
            SettingsSection(
                footer = "Automatically normalize track volume based on replay gain information for consistent loudness."
            ) {
                SettingsCheckBoxRow(
                    title = "Enable ReplayGain",
                    checked = isReplayGainEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setReplayGainEnabled(enabled)
                    }
                )
            }

            // 自动缓存播放的歌曲
            // 对应iOS PlayerSettingsView.swift:90-96
            SettingsSection {
                SettingsCheckBoxRow(
                    title = "Auto cache played Songs",
                    checked = isPlayerAutoCachePlayedItems,
                    onCheckedChange = { enabled ->
                        viewModel.setPlayerAutoCachePlayedItems(enabled)
                    }
                )
            }

            // 单曲播放进度记忆
            // 对应iOS PlayerSettingsView.swift:98-106（isPlayerSongPlaybackResumeEnabled）
            SettingsSection(
                footer = "Keeps track of song progress so playback continues from the previously saved position."
            ) {
                SettingsCheckBoxRow(
                    title = "Song Playback Resume",
                    checked = isRememberSongPlaybackProgress,
                    onCheckedChange = { enabled ->
                        streamViewModel.setRememberSongPlaybackProgress(enabled)
                    }
                )
            }

            // 手动播放
            // 对应iOS PlayerSettingsView.swift:108-110
            SettingsSection(
                footer = "Enable to start playback only when the Play button is pressed."
            ) {
                SettingsCheckBoxRow(
                    title = "Manual Playback",
                    checked = isPlaybackStartOnlyOnPlay,
                    onCheckedChange = { enabled ->
                        viewModel.setPlaybackStartOnlyOnPlay(enabled)
                    }
                )
            }

            // 蜂窝流媒体格式
            // 对应iOS PlayerSettingsView.swift:112-126
            SettingsSection(
                footer = "Select a transcoding format for streaming while using Cellular. Transcoding is recommended for better compatibility."
            ) {
                SettingsMenuRow(
                    title = "Cellular Streaming\nFormat (Transcoding)",
                    selectedValue = streamingFormatCellularPreference.displayName,
                    options = StreamingFormatPreference.entries.map { format ->
                        format.displayName to {
                            streamViewModel.setStreamingFormatCellularPreference(format)
                        }
                    }
                )
            }

            // 蜂窝流媒体比特率限制
            // 对应iOS PlayerSettingsView.swift:128-141
            SettingsSection(
                footer = "Set the maximum streaming bitrate for Cellular."
            ) {
                SettingsMenuRow(
                    title = "Cellular Streaming\nBitrate Limit",
                    selectedValue = streamingMaxBitrateCellularPreference.displayName,
                    options = StreamingMaxBitratePreference.entries.map { bitrate ->
                        bitrate.displayName to {
                            viewModel.setStreamingMaxBitrateCellularPreference(bitrate)
                        }
                    }
                )
            }

            // WiFi 流媒体格式
            // 对应iOS PlayerSettingsView.swift:143-156
            SettingsSection(
                footer = "Select a transcoding format for streaming while on WiFi. Transcoding is recommended for better compatibility."
            ) {
                SettingsMenuRow(
                    title = "WiFi Streaming\nFormat (Transcoding)",
                    selectedValue = streamingFormatWifiPreference.displayName,
                    options = StreamingFormatPreference.entries.map { format ->
                        format.displayName to {
                            streamViewModel.setStreamingFormatWifiPreference(format)
                        }
                    }
                )
            }

            // WiFi 流媒体比特率限制
            // 对应iOS PlayerSettingsView.swift:158-171
            SettingsSection(
                footer = "Set the maximum streaming bitrate for WiFi."
            ) {
                SettingsMenuRow(
                    title = "WiFi Streaming\nBitrate Limit",
                    selectedValue = streamingMaxBitrateWifiPreference.displayName,
                    options = StreamingMaxBitratePreference.entries.map { bitrate ->
                        bitrate.displayName to {
                            viewModel.setStreamingMaxBitrateWifiPreference(bitrate)
                        }
                    }
                )
            }

            // 缓存格式设置
            // 对应iOS PlayerSettingsView.swift:173-194（Android 仅 Subsonic，footer 取含 raw 说明的分支）
            SettingsSection(
                footer = "Select a transcoding format for cached songs. Changes will not apply to already downloaded songs; clear cache and redownload if needed.\n\nFor 'raw', Amperfy uses the Subsonic API's 'download' action, which skips transcoding. Other formats use the 'stream' action, which requires proper server configuration for transcoding."
            ) {
                SettingsMenuRow(
                    title = "Cache\nFormat (Transcoding)",
                    selectedValue = cacheTranscodingFormatPreference.displayName,
                    options = CacheTranscodingFormatPreference.entries.map { format ->
                        format.displayName to {
                            viewModel.setCacheTranscodingFormatPreference(format)
                        }
                    }
                )
            }
        }
    }
}
