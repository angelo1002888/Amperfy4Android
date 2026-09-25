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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * PlaylistEditViewModel - 播放列表编辑页 ViewModel
 *
 * 对应 iOS: PlaylistEditVC.swift（独立编辑页）
 *
 * 负责：观察播放列表元数据与有序歌曲、重命名、重排序、删除歌曲。
 * 添加歌曲由独立的 [PlaylistAddSongsViewModel] 负责。
 */
@HiltViewModel
class PlaylistEditViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    val playlist: StateFlow<Playlist?> = appDelegate.playlists.observePlaylistById(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val songs: StateFlow<List<Song>> = appDelegate.playlists.getPlaylistSongs(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 串行化重排/删除等服务器同步操作：快速连续拖拽时两个「全量 remove+add」请求
     * 并发到达服务器会导致旧顺序覆盖新顺序。与 repo 的 (account_id, playlist_id) 分片锁互补
     * ——本 VM 级锁防同一 VM 内连续操作交错，repo 锁防后台同步与编辑命令交错。
     */
    private val syncMutex = Mutex()

    /**
     * 重命名（自动上传服务器）。空名或与原名相同则忽略。
     * 对应 iOS: GenericDetailTableHeader.endEditing -> syncUpload(playlistToUpdateName:)
     */
    fun rename(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == playlist.value?.name) return
        viewModelScope.launch {
            appDelegate.playlists.renamePlaylist(playlistId, trimmed)
                .onFailure { android.util.Log.e(TAG, "Rename failed", it) }
        }
    }

    /**
     * 按给定顺序整体重排（自动上传服务器）。拖拽结束时一次性提交。
     * 仅用于等长重排（updatePlaylistOrder 的 remove 索引按新列表长度生成）。
     * 对应 iOS: PlaylistEditVC 拖拽 -> syncUpload(playlistToUpdateOrder:)
     */
    fun reorder(orderedIds: List<String>) {
        if (orderedIds == songs.value.map { it.id }) return
        viewModelScope.launch {
            syncMutex.withLock {
                // 本地先行（repo updatePlaylistOrder 先 Room 整表替换再上传）：失败也不回滚本地顺序，
                // 本地已持久化新序、songs Flow 将发射新序，故失败只记日志（对齐 iOS）。
                appDelegate.playlists.updatePlaylistOrder(playlistId, orderedIds)
                    .onFailure { android.util.Log.e(TAG, "Reorder failed", it) }
            }
        }
    }

    /**
     * 删除选中索引处的歌曲（自动上传服务器）。
     * 按索引降序逐个删除，先删的索引不影响后删的索引；按索引而非歌曲 id 删除，
     * 播放列表含重复歌曲时只删所选条目。
     * 对应 iOS: PlaylistEditVC.deleteBarButtonPressed -> syncUpload(playlistToDeleteSong:index:)
     * （SubsonicLibrarySyncer 同样按索引降序逐个删）
     */
    fun removeSongs(removeIndices: Set<Int>) {
        if (removeIndices.isEmpty()) return
        viewModelScope.launch {
            syncMutex.withLock {
                for (index in removeIndices.sortedDescending()) {
                    val result = appDelegate.playlists.removeSongFromPlaylist(playlistId, index)
                    if (result.isFailure) {
                        android.util.Log.e(TAG, "Remove song at $index failed", result.exceptionOrNull())
                        break
                    }
                }
            }
        }
    }

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
        private const val TAG = "PlaylistEditVM"
    }
}
