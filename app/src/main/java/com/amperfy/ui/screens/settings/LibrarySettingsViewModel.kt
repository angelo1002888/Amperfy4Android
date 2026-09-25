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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * LibrarySettingsViewModel - 资料库设置页 ViewModel（Phase 5.1）
 *
 * 对应 iOS: LibrarySettingsView.swift（iOS 用 1 秒 Timer 轮询统计；
 * Android 统计用 Room count Flow 实时驱动，缓存大小 2 秒轮询）
 */
@HiltViewModel
class LibrarySettingsViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    // ===== 库统计（实时）=====
    val playlistCount: StateFlow<Long> = appDelegate.playlists.observePlaylistCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val artistCount: StateFlow<Long> = appDelegate.library.observeArtistCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val albumCount: StateFlow<Long> = appDelegate.library.observeAlbumCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val songCount: StateFlow<Long> = appDelegate.library.observeSongCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val podcastCount: StateFlow<Long> = appDelegate.podcasts.observePodcastCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val cachedSongCount: StateFlow<Long> = appDelegate.library.observeCachedSongCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /**
     * Initial Sync 状态（对应 iOS initialSyncCompletionStatus.description）
     *
     * W5 后多账户下完成标志按 active 账户 ident 写入命名空间键（见 InitialSyncViewModel 写入侧），
     * 故此处须按 ident 读命名空间键；ident 为 null（未登录）时回退无参旧版全局键。
     */
    val initialSyncStatus: String
        get() {
            val ident = appDelegate.accounts.activeAccountId
            val pending = if (ident != null) {
                appDelegate.credentials.needsInitialSync(ident)
            } else {
                appDelegate.credentials.needsInitialSync()
            }
            return if (pending) "Pending" else "Completed"
        }

    /**
     * 后台歌曲同步进度：已同步歌曲元数据的专辑占比
     * 对应 iOS: autoSyncProgressText（albumWithSyncedSongsCount * 100 / albumCount，"%.1f%%"）
     */
    val syncProgressText: StateFlow<String> = combine(
        appDelegate.library.observeSyncedAlbumCount(),
        appDelegate.library.observeAlbumCount()
    ) { synced, total ->
        if (total < 1) "0.0%" else String.format(Locale.US, "%.1f%%", synced * 100.0 / total)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.0%")

    // ===== 缓存 =====

    /** 缓存大小文本，2 秒轮询（iOS 用 1 秒 Timer 刷新整页） */
    val cacheSizeText: StateFlow<String> = flow {
        while (true) {
            emit(formatByteSize(appDelegate.downloader.getCacheSize()))
            delay(2000)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0 bytes")

    val cacheSizeLimitMB: StateFlow<Int> = appDelegate.settings.cacheSizeLimitMB

    fun setCacheSizeLimit(mb: Int) = appDelegate.settings.setCacheSizeLimitMB(mb)

    // 注：Auto Cache（Newest Songs / Newest Podcast Episodes）为账户级设置，
    // 随 2.1.0 对齐移至 Account 页（iOS AccountSettingsView.swift:164-173），
    // 本 VM 不再持有其入口；Resync Library 同理（iOS AccountSettingsView.swift:222-235）。

    // ===== 操作 =====

    /**
     * Download all songs in library：全部未缓存歌曲加入下载队列
     * 对应 iOS: getSongsForCompleteLibraryDownload -> download(objects:)
     */
    fun downloadAllSongs() {
        viewModelScope.launch {
            val songs = appDelegate.library.getAllSongs().first().filter { !it.isDownloaded }
            appDelegate.downloader.downloadSongs(songs)
        }
    }

    /**
     * Delete Cache：暂停播放、停止下载、清空全部缓存
     * 对应 iOS: player.stop + downloadManager.stop + deletePlayableCache
     */
    fun deleteCompleteCache() {
        viewModelScope.launch {
            appDelegate.player.pause()
            appDelegate.downloader.cancelAllDownloads()
            appDelegate.downloader.clearAllCache()
                .onFailure { android.util.Log.e(TAG, "Delete cache failed", it) }
        }
    }

    companion object {
        private const val TAG = "LibrarySettingsVM"

        /** 缓存限制可选档位（MB；0 = No Limit）。iOS 为双轮 Picker，Android 简化为预设档 */
        val CACHE_LIMIT_OPTIONS_MB = listOf(0, 250, 500, 1024, 2048, 5120, 10240, 20480, 51200)

        fun formatCacheLimit(mb: Int): String = when {
            mb <= 0 -> "No Limit"
            mb < 1024 -> "$mb MB"
            else -> "${mb / 1024} GB"
        }

        /** 字节数格式化；<1MB 显示 "0 bytes"（对齐 iOS completeCacheSize 行为） */
        fun formatByteSize(bytes: Long): String = when {
            bytes < 1_000_000 -> "0 bytes"
            bytes < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
        }
    }
}
