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

package com.amperfy.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.Song
import com.amperfy.data.model.Playable
import com.amperfy.data.model.toPlayableWithCredentials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Library首页ViewModel (已重构为AppDelegate模式)
 *
 * 功能:
 * - 显示最近的歌曲列表
 * - 播放控制（播放、暂停、上一首、下一首）
 *
 * 重构说明:
 * - 从3个依赖(musicRepository, credentialsManager, playerManager) → 1个依赖(appDelegate)
 * - 直接使用appDelegate的域入口（library/playlists/…）和appDelegate.player
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    val recentSongs: StateFlow<List<Song>> = appDelegate.library.getAllSongs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Use PlayerManager's state directly for consistency across all screens
    val isPlaying = appDelegate.player.isPlaying
    val currentPlayingSong: StateFlow<Playable?> = appDelegate.player.currentSong

    // MiniPlayer progress tracking
    // 对应iOS: MiniPlayerView uses player.elapsedTime and player.duration
    val currentPosition: StateFlow<Long> = appDelegate.player.currentPosition

    // Duration needs polling since Media3 provides it dynamically
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    init {
        // Poll duration every second (same as PopupPlayerViewModel)
        viewModelScope.launch {
            while (true) {
                // 直播流/切歌未就绪时 duration 为 C.TIME_UNSET（大负数），钳 0；
                // 转码流媒体长时间拿不到实际时长，回退歌曲元数据时长桥接
                // （与 PopupPlayerViewModel.effectiveDurationMs 同语义；
                //   电台元数据时长为 0 → MiniPlayer 进度条隐藏，符合预期）
                val rawDuration = appDelegate.player.getDuration().coerceAtLeast(0L)
                _duration.value = if (rawDuration > 0) {
                    rawDuration
                } else {
                    (appDelegate.player.currentSong.value?.duration ?: 0).toLong() * 1000L
                }
                delay(1000)
            }
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            appDelegate.player.playSong(
                song.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
            )
        }
    }

    fun playPause() {
        appDelegate.player.playPause()
    }

    /**
     * Play next song in queue
     * Equivalent to iOS: player.playNext()
     */
    fun playNext() {
        appDelegate.player.playNext()
    }

    /**
     * Play previous song in queue
     * Equivalent to iOS: player.playPreviousOrReplay()
     */
    fun playPrevious() {
        appDelegate.player.playPrevious()
    }

    // ==================== Library Edit（Phase 6.6） ====================

    /**
     * 资料库导航项显隐/排序设置（W5：改读 active 账户的账户级设置）
     * 对应 iOS: LibraryNavigatorConfigurator 从 account.settings.libraryDisplaySettings 读取。
     * 账户切换时 UI 整体重建，ViewModel 重构造即读到新 active 账户设置，故此处按当前
     * active ident 一次性绑定。
     */
    private val accountIdent: String get() = appDelegate.accounts.activeAccountId ?: ""

    val libraryDisplaySettings: kotlinx.coroutines.flow.StateFlow<com.amperfy.data.model.LibraryDisplaySettings> =
        kotlinx.coroutines.flow.MutableStateFlow(
            appDelegate.accountSettings.settings(accountIdent).value.libraryDisplaySettings
        ).also { flow ->
            viewModelScope.launch {
                appDelegate.accountSettings.settings(accountIdent)
                    .collect { flow.value = it.libraryDisplaySettings }
            }
        }

    /**
     * 保存编辑结果（Done 时一次性保存，对应 iOS editingPressed 退出编辑分支
     * LibraryNavigatorConfigurator.swift:288-300）
     */
    fun saveLibraryDisplaySettings(inUse: List<com.amperfy.data.model.LibraryDisplayType>) {
        appDelegate.accountSettings.update(accountIdent) {
            it.copy(libraryDisplaySettings = com.amperfy.data.model.LibraryDisplaySettings(inUse))
        }
    }
}
