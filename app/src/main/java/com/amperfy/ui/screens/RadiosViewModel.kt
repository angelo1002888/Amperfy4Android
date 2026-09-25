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
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Radio
import com.amperfy.data.model.toPlayable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Radios UI 状态
 */
data class RadiosUiState(
    val searchText: String = "",
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * Radios ViewModel（Phase 6.3）
 * 对应 iOS: RadiosVC + RadiosFetchedResultsController
 *
 * - 列表按 title 字母排序（iOS RadioMO.alphabeticSortedFetchRequest 以 title 为标识）
 * - 搜索按标题匹配，无 All/Cached 作用域（iOS 无 scope buttons——电台不可缓存）
 * - 进入与下拉刷新均同步 getInternetRadioStations（iOS viewIsAppearing/handleRefresh →
 *   syncRadios，离线跳过）
 * - 点击行以全部电台为上下文播放（iOS convertIndexPathToPlayContext：
 *   PlayContext(name: "Radios", index, 全部电台)）
 */
@HiltViewModel
class RadiosViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    private val _uiState = MutableStateFlow(RadiosUiState())
    val uiState: StateFlow<RadiosUiState> = _uiState.asStateFlow()

    val filteredRadios: StateFlow<List<Radio>> = combine(
        appDelegate.library.getAllRadios(),
        _uiState
    ) { radios, state ->
        // store 已按 sort_key（拼音分区 + 原文，# 分区 99 严格最后）排序返回（P3 批次 3b 下沉），
        // 无须客户端重排——旧的「#」映射为大写 Z 前缀参与字符串比较的 hack（大写 Z 码点 < 小写 a，令
        // # 项错排在小写字母 Z 名称之前）随之修正
        if (state.searchText.isEmpty()) {
            radios
        } else {
            radios.filter { it.title.contains(state.searchText, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 播放状态（行播放指示器用）
    val currentSong = appDelegate.player.currentSong
    val isPlaying = appDelegate.player.isPlaying

    init {
        // iOS: viewIsAppearing → updateFromRemote → syncRadios
        syncRadios(showRefreshing = false)
    }

    fun onSearchTextChanged(text: String) {
        _uiState.update { it.copy(searchText = text) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 下拉刷新（离线模式直接结束，对应 iOS handleRefresh 的 isOnlineMode 守卫） */
    fun handleRefresh() {
        syncRadios(showRefreshing = true)
    }

    private fun syncRadios(showRefreshing: Boolean) {
        viewModelScope.launch {
            if (appDelegate.settings.isOfflineMode.value) {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
                return@launch
            }
            if (showRefreshing) _uiState.update { it.copy(isRefreshing = true) }
            try {
                appDelegate.library.syncRadios()
                    .onFailure { e ->
                        // iOS 错误主题 "Radios Sync"
                        _uiState.update { it.copy(error = "Radios Sync failed: ${e.message}") }
                    }
            } finally {
                if (showRefreshing) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * 播放电台：以当前（过滤后）全部电台为上下文，从所点行开始
     * 对应 iOS: convertIndexPathToPlayContext（RadiosVC.swift:159-165）
     */
    fun playRadio(radio: Radio) {
        viewModelScope.launch {
            try {
                val radios = filteredRadios.value
                val index = radios.indexOfFirst { it.id == radio.id }.coerceAtLeast(0)
                appDelegate.player.playPlaylist(
                    songs = radios.map { it.toPlayable() },
                    startIndex = index,
                    contextType = PlayContextType.SONGS,
                    contextId = "radios",
                    contextName = "Radios"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to play radio", e)
            }
        }
    }

    /** 头部 Play：从第一个电台开始（iOS handleHeaderPlay） */
    fun playAll() {
        filteredRadios.value.firstOrNull()?.let { playRadio(it) }
    }

    /** 头部 Shuffle：随机挑一个电台开始（iOS handleHeaderShuffle：index 随机，非乱序队列） */
    /**
     * 头部 Shuffle（文案为 "Random"）——对齐 iOS `RadiosVC.handleHeaderShuffle`（:161-172）：
     * 取前 [MAX_RADIOS_TO_ADD_ONCE] 个电台**整批入队**，起始索引随机，
     * 且**不开播放器 shuffle**——该页 `isShuffleOnContextNeccessary: false`（RadiosVC.swift:77），
     * 按下只走 `player.play(context:)`（LibraryElementDetailTableHeaderView.swift:140-151）。
     *
     * 注意与旧实现的区别：此前是 `playRadio(radios.random())`，只播一个随机电台、其余不入队。
     */
    fun shufflePlay() {
        viewModelScope.launch {
            val radios = filteredRadios.value.take(MAX_RADIOS_TO_ADD_ONCE)
            if (radios.isEmpty()) return@launch
            appDelegate.player.playPlaylist(
                songs = radios.map { it.toPlayable() },
                startIndex = radios.indices.random(),
                contextType = PlayContextType.SONGS,
                contextId = "radios",
                contextName = "Radios"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 队列操作 - 对应 iOS More 菜单 Music Queue 子菜单
    // （EntityPreviewVC.configureFor(radio:) isMusicQueue = true）
    // 电台不走 SongQueueExtensions：Radio.toPlayable() 为纯转换（原始 streamUrl 直连，
    // 无须凭证生成 URL），故直接调 PlayerManager、无协程
    // ═══════════════════════════════════════════════════════════

    fun insertContextQueue(radio: Radio) {
        appDelegate.player.insertContextQueue(listOf(radio.toPlayable()))
    }

    fun appendContextQueue(radio: Radio) {
        appDelegate.player.appendContextQueue(listOf(radio.toPlayable()))
    }

    fun addToQueueNext(radio: Radio) {
        appDelegate.player.insertUserQueue(listOf(radio.toPlayable()))
    }

    fun addToQueueLater(radio: Radio) {
        appDelegate.player.appendUserQueue(listOf(radio.toPlayable()))
    }

    companion object {
        private const val TAG = "RadiosViewModel"

        /** 一次入队上限——对齐 iOS `PlayerFacade.maxSongsToAddOnce`（PlayerFacade.swift:236 = 500） */
        private const val MAX_RADIOS_TO_ADD_ONCE = 500
    }
}
