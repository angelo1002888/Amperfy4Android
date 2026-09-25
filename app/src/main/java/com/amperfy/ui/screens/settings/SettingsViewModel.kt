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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.SwipeActionSettings
import com.amperfy.data.local.AppearanceMode
import com.amperfy.data.local.CacheTranscodingFormatPreference
import com.amperfy.data.local.ScreenLockPreventionPreference
import com.amperfy.data.local.StreamingFormatPreference
import com.amperfy.data.local.StreamingMaxBitratePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SettingsViewModel (已重构为AppDelegate模式)
 * 管理Settings的业务逻辑和UI状态
 * 对应iOS中Settings对象和相关的AppDelegate方法
 *
 * 重构说明：
 * - 使用AppDelegate统一访问SettingsManager
 * - SettingsManager已整合了原SettingsData的所有功能
 * - 所有设置项都通过appDelegate.settings访问
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    application: Application
) : AndroidViewModel(application) {

    // 应用版本信息
    private val _appVersion = MutableStateFlow("")
    val appVersion: StateFlow<String> = _appVersion

    private val _buildNumber = MutableStateFlow("")
    val buildNumber: StateFlow<String> = _buildNumber

    init {
        loadAppVersionInfo()
    }

    /**
     * 加载应用版本信息
     * 对应iOS的AppDelegate.version和AppDelegate.buildNumber
     */
    private fun loadAppVersionInfo() {
        viewModelScope.launch {
            try {
                val packageInfo = getApplication<Application>().packageManager
                    .getPackageInfo(getApplication<Application>().packageName, 0)
                _appVersion.value = packageInfo.versionName ?: "Unknown"
                // longVersionCode 需 API 28，minSdk 26 用 compat（API 26/27 直接调用会 NoSuchMethodError）
                _buildNumber.value =
                    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo).toString()
            } catch (e: Exception) {
                _appVersion.value = "Unknown"
                _buildNumber.value = "Unknown"
            }
        }
    }

    // ===== Getter方法 - 获取当前设置值 =====
    // 直接从appDelegate.settings获取StateFlow

    val isOfflineMode: StateFlow<Boolean>
        get() = appDelegate.settings.isOfflineMode

    val screenLockPreventionPreference: StateFlow<ScreenLockPreventionPreference>
        get() = appDelegate.settings.screenLockPreventionPreference

    val isShowDetailedInfo: StateFlow<Boolean>
        get() = appDelegate.settings.isShowDetailedInfo

    val isShowSongDuration: StateFlow<Boolean>
        get() = appDelegate.settings.isShowSongDuration

    val isShowAlbumDuration: StateFlow<Boolean>
        get() = appDelegate.settings.isShowAlbumDuration

    val isShowArtistDuration: StateFlow<Boolean>
        get() = appDelegate.settings.isShowArtistDuration

    val appearanceMode: StateFlow<AppearanceMode>
        get() = appDelegate.settings.appearanceMode

    val isHapticsEnabled: StateFlow<Boolean>
        get() = appDelegate.settings.isHapticsEnabled

    val swipeActionSettings: StateFlow<SwipeActionSettings>
        get() = appDelegate.settings.swipeActionSettings

    val isPlayerShuffleButtonEnabled: StateFlow<Boolean>
        get() = appDelegate.settings.isPlayerShuffleButtonEnabled

    val isShowMusicPlayerSkipButtons: StateFlow<Boolean>
        get() = appDelegate.settings.isShowMusicPlayerSkipButtons

    val isLyricsSmoothScrolling: StateFlow<Boolean>
        get() = appDelegate.settings.isLyricsSmoothScrolling

    // 播放器内五星评分显示（iOS: isShowRating/isPlayerRatingDisplayed）
    val isPlayerRatingDisplayed: StateFlow<Boolean>
        get() = appDelegate.settings.isPlayerRatingDisplayed

    val isPlaybackStartOnlyOnPlay: StateFlow<Boolean>
        get() = appDelegate.settings.isPlaybackStartOnlyOnPlay

    val streamingMaxBitrateWifiPreference: StateFlow<StreamingMaxBitratePreference>
        get() = appDelegate.settings.streamingMaxBitrateWifiPreference

    val streamingMaxBitrateCellularPreference: StateFlow<StreamingMaxBitratePreference>
        get() = appDelegate.settings.streamingMaxBitrateCellularPreference

    // 单一流媒体格式（对齐 iOS 1.2.3 PlayerSettingsView，不分 WiFi/蜂窝）
    val streamingFormatPreference: StateFlow<StreamingFormatPreference>
        get() = appDelegate.settings.streamingFormatPreference

    // 注：账户级三项（Auto cache Songs / Podcast Episodes、Scrobble streamed Songs）
    // 随 2.1.0 对齐迁至 Account 页，读写由 AccountSettingsViewModel 直连
    // AccountSettingsStore；本 VM 不再持有其入口。

    val isPlayerAutoCachePlayedItems: StateFlow<Boolean>
        get() = appDelegate.settings.isPlayerAutoCachePlayedItems

    val cacheSizeLimitMB: StateFlow<Int>
        get() = appDelegate.settings.cacheSizeLimitMB

    val cacheTranscodingFormatPreference: StateFlow<CacheTranscodingFormatPreference>
        get() = appDelegate.settings.cacheTranscodingFormatPreference

    /**
     * ReplayGain 启用（iOS 2.1.0 PlayerSettingsView.swift:74-88 "Enable ReplayGain"）
     * 消费链：SettingsManager → AudioChainCoordinator → GainProcessor
     */
    val isReplayGainEnabled: StateFlow<Boolean>
        get() = appDelegate.settings.isReplayGainEnabled

    // ===== Setter方法 - 更新设置值 =====
    // 直接调用appDelegate.settings的方法

    /**
     * 设置离线模式
     * 对应iOS的settings.isOfflineMode
     */
    fun setOfflineMode(enabled: Boolean) {
        appDelegate.settings.setOfflineMode(enabled)
    }

    /**
     * 设置屏幕锁定防止偏好
     * 对应iOS的screenLockPreventionOffPressed等方法；
     * FLAG_KEEP_SCREEN_ON 的实际应用由 MainActivity.KeepScreenOnEffect
     * 收集 StateFlow 响应式处理（iOS 则需手动调 configureLockScreenPrevention）
     */
    fun screenLockPreventionOffPressed() {
        appDelegate.settings.setScreenLockPreventionPreference(ScreenLockPreventionPreference.NEVER)
    }

    fun screenLockPreventionOnPressed() {
        appDelegate.settings.setScreenLockPreventionPreference(ScreenLockPreventionPreference.ALWAYS)
    }

    fun screenLockPreventionChargingPressed() {
        appDelegate.settings.setScreenLockPreventionPreference(ScreenLockPreventionPreference.ONLY_IF_CHARGING)
    }

    /**
     * 设置显示详细信息
     */
    fun setShowDetailedInfo(show: Boolean) {
        appDelegate.settings.setShowDetailedInfo(show)
    }

    /**
     * 设置显示歌曲时长
     */
    fun setShowSongDuration(show: Boolean) {
        appDelegate.settings.setShowSongDuration(show)
    }

    /**
     * 设置显示专辑时长
     */
    fun setShowAlbumDuration(show: Boolean) {
        appDelegate.settings.setShowAlbumDuration(show)
    }

    /**
     * 设置显示艺术家时长
     */
    fun setShowArtistDuration(show: Boolean) {
        appDelegate.settings.setShowArtistDuration(show)
    }

    /**
     * 设置外观模式
     * 对应iOS的setAppearanceMode(style:)
     */
    fun setAppearanceMode(mode: AppearanceMode) {
        // 深浅色应用由 MainActivity 收集 appearanceMode StateFlow 响应式生效
        appDelegate.settings.setAppearanceMode(mode)
    }

    /**
     * 设置触觉反馈启用状态
     */
    fun setHapticsEnabled(enabled: Boolean) {
        appDelegate.settings.setHapticsEnabled(enabled)
    }

    /**
     * 设置播放器随机按钮启用状态
     */
    fun setPlayerShuffleButtonEnabled(enabled: Boolean) {
        appDelegate.settings.setPlayerShuffleButtonEnabled(enabled)
    }

    /**
     * 设置显示音乐播放器跳过按钮
     */
    fun setShowMusicPlayerSkipButtons(show: Boolean) {
        appDelegate.settings.setShowMusicPlayerSkipButtons(show)
    }

    /**
     * 设置歌词平滑滚动
     */
    fun setLyricsSmoothScrolling(enabled: Boolean) {
        appDelegate.settings.setLyricsSmoothScrolling(enabled)
    }

    /**
     * 设置播放器内评分显示
     */
    fun setPlayerRatingDisplayed(displayed: Boolean) {
        appDelegate.settings.setPlayerRatingDisplayed(displayed)
    }

    /**
     * 设置仅在播放时开始回放
     */
    fun setPlaybackStartOnlyOnPlay(enabled: Boolean) {
        appDelegate.settings.setPlaybackStartOnlyOnPlay(enabled)
    }

    /**
     * 设置WiFi流媒体最大比特率
     */
    fun setStreamingMaxBitrateWifiPreference(preference: StreamingMaxBitratePreference) {
        appDelegate.settings.setStreamingMaxBitrateWifiPreference(preference)
    }

    /**
     * 设置蜂窝网络流媒体最大比特率
     */
    fun setStreamingMaxBitrateCellularPreference(preference: StreamingMaxBitratePreference) {
        appDelegate.settings.setStreamingMaxBitrateCellularPreference(preference)
    }

    /**
     * 设置流媒体格式（单一偏好，对齐 iOS 1.2.3）
     */
    fun setStreamingFormatPreference(preference: StreamingFormatPreference) {
        appDelegate.settings.setStreamingFormatPreference(preference)
    }

    /**
     * 设置 ReplayGain 启用（对应 iOS settings.isReplayGainEnabled）
     */
    fun setReplayGainEnabled(enabled: Boolean) {
        appDelegate.settings.setReplayGainEnabled(enabled)
    }

    /**
     * 设置播放器自动缓存播放项目
     */
    fun setPlayerAutoCachePlayedItems(enabled: Boolean) {
        appDelegate.settings.setPlayerAutoCachePlayedItems(enabled)
    }

    /**
     * 设置缓存大小限制
     */
    fun setCacheSizeLimitMB(limitMB: Int) {
        appDelegate.settings.setCacheSizeLimitMB(limitMB)
    }

    /**
     * 设置缓存转码格式偏好
     */
    fun setCacheTranscodingFormatPreference(preference: CacheTranscodingFormatPreference) {
        appDelegate.settings.setCacheTranscodingFormatPreference(preference)
    }

    /**
     * 设置滑动动作配置
     * 对应iOS: settings.swipeActionSettings
     */
    fun setSwipeActionSettings(settings: SwipeActionSettings) {
        appDelegate.settings.setSwipeActionSettings(settings)
    }

    /**
     * 恢复默认滑动动作配置
     */
    fun resetSwipeActionSettings() {
        appDelegate.settings.resetSwipeActionSettings()
    }
}
