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
import com.amperfy.data.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 选中播放列表与待加歌曲重复时的确认状态
 * （iOS: PlaylistSelectorVC.didSelectRowAt 的 UIAlertController，
 * "Some Songs are already in this Playlist."）
 */
data class SelectorDuplicatePrompt(
    val playlistId: String,
    /** 待加入的全部歌曲 id（Add Duplicates 时全加） */
    val songIds: List<String>,
    /** 其中不在该播放列表内的部分（Skip Duplicates 时只加这些） */
    val notContained: List<String>
)

/**
 * PlaylistSelectorViewModel - 播放列表选择器 ViewModel
 *
 * 对应 iOS: PlaylistSelectorVC.swift：
 * - 排序菜单 Name/Last time played/Change date/Duration，初值取 playlistsSortSetting
 *   但**不持久化**（iOS 注释：differs from PlaylistsVC）
 * - Select 多选模式：可把歌曲一次加入多个播放列表（"+" 提交）
 * - 选择时去重确认（Add Duplicates/Skip Duplicates/Cancel）
 * - New Playlist 表头仅创建播放列表（不加歌、不关闭，新列表出现在列表中）
 * - 进入时 syncDownPlaylistsWithoutSongs（Android: syncPlaylists）
 */
@HiltViewModel
class PlaylistSelectorViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    /** 本地排序（初值 = 播放列表页设置，改动不写回，对齐 iOS） */
    private val _sortType = MutableStateFlow(
        runCatching {
            PlaylistSortType.valueOf(appDelegate.settings.playlistsSortSetting.value)
        }.getOrDefault(PlaylistSortType.NAME)
    )
    val sortType: StateFlow<PlaylistSortType> = _sortType.asStateFlow()

    /** 多选模式（iOS: AddToPlaylistSelectMode.single/multi） */
    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    /**
     * 多选已选的播放列表 → 该列表实际要加入的歌曲 id
     * （iOS: selectedPlaylits [Playlist: [Song]]——Skip Duplicates 时各列表歌曲集不同）
     */
    private val _selectedPlaylists = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val selectedPlaylists: StateFlow<Map<String, List<String>>> = _selectedPlaylists.asStateFlow()

    private val _duplicatePrompt = MutableStateFlow<SelectorDuplicatePrompt?>(null)
    val duplicatePrompt: StateFlow<SelectorDuplicatePrompt?> = _duplicatePrompt.asStateFlow()

    val playlists: StateFlow<List<Playlist>> = combine(
        appDelegate.playlists.getAllPlaylists(),
        _searchText,
        _sortType
    ) { all, query, sort ->
        val filtered = if (query.isBlank()) all
        else all.filter { it.name.contains(query, ignoreCase = true) }
        when (sort) {
            PlaylistSortType.NAME -> filtered.sortedBy { it.name.lowercase() }
            PlaylistSortType.LAST_PLAYED -> filtered.sortedByDescending { it.lastPlayed ?: 0L }
            PlaylistSortType.CHANGE_DATE -> filtered.sortedByDescending { it.changed ?: it.created ?: 0L }
            PlaylistSortType.DURATION -> filtered.sortedWith(
                compareByDescending<Playlist> { it.duration }.thenByDescending { it.songCount }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // iOS: viewIsAppearing → syncDownPlaylistsWithoutSongs（离线跳过）
        viewModelScope.launch {
            if (appDelegate.settings.isOfflineMode.value) return@launch
            appDelegate.playlists.syncPlaylists()
        }
    }

    fun onSearchTextChanged(text: String) {
        _searchText.value = text
    }

    fun changeSortType(sortType: PlaylistSortType) {
        _sortType.value = sortType
    }

    /** Select 按钮：切换单选/多选，切换时清空已选（iOS: selectBarButtonPressed） */
    fun toggleSelectMode() {
        _isMultiSelectMode.value = !_isMultiSelectMode.value
        _selectedPlaylists.value = emptyMap()
    }

    /**
     * 点选某播放列表（iOS: didSelectRowAt）：
     * 多选下已选 → 取消；否则查重——有重复弹确认，无重复直接提交
     * （单选 = 立即上传并关闭；多选 = 记入已选集合）
     */
    fun onPlaylistTap(playlist: Playlist, songIds: List<String>, onDone: () -> Unit) {
        if (_selectedPlaylists.value.containsKey(playlist.id)) {
            _selectedPlaylists.value = _selectedPlaylists.value - playlist.id
            return
        }
        viewModelScope.launch {
            val existing = runCatching {
                appDelegate.playlists.getPlaylistSongs(playlist.id).first().map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val notContained = songIds.filterNot { it in existing }
            if (notContained.size != songIds.size) {
                _duplicatePrompt.value = SelectorDuplicatePrompt(
                    playlistId = playlist.id, songIds = songIds, notContained = notContained
                )
            } else {
                commitSelection(playlist.id, songIds, onDone)
            }
        }
    }

    /** 去重确认：Add Duplicates —— 连重复项全部加入 */
    fun confirmAddDuplicates(onDone: () -> Unit) {
        _duplicatePrompt.value?.let { commitSelection(it.playlistId, it.songIds, onDone) }
        _duplicatePrompt.value = null
    }

    /** 去重确认：Skip Duplicates —— 只加入不重复部分（全重复则不选中该列表） */
    fun skipDuplicates(onDone: () -> Unit) {
        _duplicatePrompt.value?.let { prompt ->
            if (prompt.notContained.isNotEmpty()) {
                commitSelection(prompt.playlistId, prompt.notContained, onDone)
            }
        }
        _duplicatePrompt.value = null
    }

    fun dismissDuplicatePrompt() {
        _duplicatePrompt.value = null
    }

    private fun commitSelection(playlistId: String, songIds: List<String>, onDone: () -> Unit) {
        if (_isMultiSelectMode.value) {
            _selectedPlaylists.value = _selectedPlaylists.value + (playlistId to songIds)
        } else {
            addSongs(playlistId, songIds, onDone)
        }
    }

    /** 多选 "+"：把歌曲加入全部已选播放列表后关闭（iOS: addBarButtonPressed） */
    fun addToSelectedPlaylists(onDone: () -> Unit) {
        val selected = _selectedPlaylists.value
        if (selected.isEmpty()) return
        _selectedPlaylists.value = emptyMap()
        viewModelScope.launch {
            selected.forEach { (playlistId, ids) ->
                appDelegate.playlists.addSongsToPlaylist(playlistId, ids)
                    .onFailure { android.util.Log.e(TAG, "Add songs to playlist failed", it) }
            }
            onDone()
        }
    }

    /**
     * 向已有播放列表添加歌曲（单选路径）
     */
    fun addSongs(playlistId: String, songIds: List<String>, onDone: () -> Unit) {
        viewModelScope.launch {
            appDelegate.playlists.addSongsToPlaylist(playlistId, songIds)
                .onFailure { android.util.Log.e(TAG, "Add songs to playlist failed", it) }
            onDone()
        }
    }

    /**
     * 新建播放列表（iOS NewPlaylistTableHeader：仅创建，不加歌不关闭，
     * 新列表经 Room Flow 自动出现在列表中供选择）
     */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            appDelegate.playlists.createPlaylist(trimmed)
                .onFailure { android.util.Log.e(TAG, "Create playlist failed", it) }
        }
    }

    /** 播放列表封面 URL（行封面显示） */
    fun getCoverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        val credentials = appDelegate.credentials.getCredentials() ?: return null
        return appDelegate.mediaUrls.getCoverArtUrl(
            coverArtId = coverArtId,
            username = credentials.username,
            password = credentials.password,
            baseUrl = credentials.serverUrl
        )
    }

    companion object {
        private const val TAG = "PlaylistSelectorVM"
    }
}
