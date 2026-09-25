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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 初始同步ViewModel (已重构为AppDelegate模式)
 * 对应iOS: SyncVC + SubsonicLibrarySyncer.syncInitial()
 *
 * 同步范围对齐 iOS syncInitial（**不含专辑歌曲**）：
 * Genres → Artists → Albums → Playlists → Podcasts
 * 专辑歌曲元数据由 BackgroundLibrarySyncer 在同步完成后渐进补齐
 * （对应 iOS startManagerAfterSync → backgroundLibrarySyncer.start()），
 * 专辑详情页按需同步兜底
 *
 * 错误语义对齐 iOS：Genres/Artists/Albums/Playlists 请求失败中断整个初始同步
 * （iOS syncInitial 抛出 → SyncVC 重试）；Podcasts 失败不中断
 * （iOS 以 requestServerPodcastSupport 守卫，服务器不支持则直接跳过）
 *
 * **离线例外**：iOS syncInitial 是唯一没有 `guard isSyncAllowed` 的同步方法
 * （SubsonicLibrarySyncer.swift:50）——离线时抛错走 "Sync Failed" + Retry。Android 的
 * Repository 层离线守卫会让各 sync 静默成功，故本 VM 在同步开始前自行做连通性预检。
 */
@HiltViewModel
class InitialSyncViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    companion object {
        private const val TAG = "InitialSyncViewModel"
    }

    private val _syncState = MutableStateFlow<InitialSyncState>(InitialSyncState.Idle)
    val syncState: StateFlow<InitialSyncState> = _syncState.asStateFlow()

    /**
     * 开始初始同步
     * 对应iOS: SyncVC.viewDidAppear() -> librarySyncer.syncInitial()
     */
    fun startInitialSync() {
        viewModelScope.launch {
            try {
                val credentials = appDelegate.credentials.getCredentials()
                if (credentials == null) {
                    _syncState.value = InitialSyncState.Error("No credentials found")
                    return@launch
                }

                // 连通性预检 —— 初始同步是离线守卫的**唯一例外**：
                // iOS syncInitial（SubsonicLibrarySyncer.swift:50）刻意不带各 sync 方法首行的
                // `guard isSyncAllowed`，离线时请求抛错 → SyncVC 显示 "Sync Failed" + Retry。
                // Android 的 Repository 层守卫（BaseSubsonicRepository.isSyncAllowed）会让各
                // sync 静默成功，若不在此拦截，离线首登会「空库却标记初始同步完成」。
                if (!appDelegate.networkMonitor.isConnectedToNetwork) {
                    Log.w(TAG, "Initial sync aborted: no network connection")
                    _syncState.value = InitialSyncState.Error("No network connection")
                    return@launch
                }

                // 进入初始同步前停掉后台渐进同步器（对应 iOS SyncVC.swift:58）
                appDelegate.backgroundLibrarySyncer.stop()

                Log.d(TAG, "Starting initial sync")

                // 1. 同步Genres
                // iOS: statusNotifyier?.notifySyncStarted(ofType: .genre, totalCount: 0)
                Log.d(TAG, "Syncing genres...")
                _syncState.value = InitialSyncState.Syncing(SyncStep.GENRES, progress = 10)
                val genresResult = appDelegate.library.syncGenres()
                if (genresResult.isFailure) {
                    Log.e(TAG, "Failed to sync genres: ${genresResult.exceptionOrNull()?.message}")
                    _syncState.value = InitialSyncState.Error(
                        "Failed to sync genres: ${genresResult.exceptionOrNull()?.message}"
                    )
                    return@launch
                }

                // 2. 同步Artists
                // iOS: statusNotifyier?.notifySyncStarted(ofType: .artist, totalCount: 0)
                Log.d(TAG, "Syncing artists...")
                _syncState.value = InitialSyncState.Syncing(SyncStep.ARTISTS, progress = 30)
                val artistsResult = appDelegate.library.syncArtists()
                if (artistsResult.isFailure) {
                    Log.e(TAG, "Failed to sync artists: ${artistsResult.exceptionOrNull()?.message}")
                    _syncState.value = InitialSyncState.Error(
                        "Failed to sync artists: ${artistsResult.exceptionOrNull()?.message}"
                    )
                    return@launch
                }

                // 3. 同步Albums（不含歌曲——iOS syncInitial 同样只拉专辑列表）
                // iOS: statusNotifyier?.notifySyncStarted(ofType: .album, totalCount: pollCountArtist)
                Log.d(TAG, "Syncing albums...")
                _syncState.value = InitialSyncState.Syncing(SyncStep.ALBUMS, progress = 55)
                val albumsResult = appDelegate.library.syncAlbums()
                if (albumsResult.isFailure) {
                    Log.e(TAG, "Failed to sync albums: ${albumsResult.exceptionOrNull()?.message}")
                    _syncState.value = InitialSyncState.Error(
                        "Failed to sync albums: ${albumsResult.exceptionOrNull()?.message}"
                    )
                    return@launch
                }

                // 4. 同步Playlists
                // iOS: statusNotifyier?.notifySyncStarted(ofType: .playlist, totalCount: 0)
                Log.d(TAG, "Syncing playlists...")
                _syncState.value = InitialSyncState.Syncing(SyncStep.PLAYLISTS, progress = 75)
                val playlistsResult = appDelegate.playlists.syncPlaylists()
                if (playlistsResult.isFailure) {
                    Log.e(TAG, "Failed to sync playlists: ${playlistsResult.exceptionOrNull()?.message}")
                    _syncState.value = InitialSyncState.Error(
                        "Failed to sync playlists: ${playlistsResult.exceptionOrNull()?.message}"
                    )
                    return@launch
                }

                // 5. 同步Podcasts（失败不中断——iOS 服务器不支持播客时直接跳过该步）
                // iOS: guard requestServerPodcastSupport else return
                Log.d(TAG, "Syncing podcasts...")
                _syncState.value = InitialSyncState.Syncing(SyncStep.PODCASTS, progress = 90)
                appDelegate.podcasts.syncPodcasts()
                    .onFailure {
                        Log.w(TAG, "Podcasts sync skipped/failed: ${it.message}")
                    }

                // 6. 标记完成并启动后台渐进同步器
                // iOS: storage.initialSyncCompletionStatus = .completed
                //      + AppDelegate.startManagerAfterSync() → backgroundLibrarySyncer.start()
                Log.d(TAG, "Initial sync completed")
                _syncState.value = InitialSyncState.Syncing(SyncStep.COMPLETED, progress = 100)
                // W5：按 active 账户 ident 置位初始同步完成标志（多账户隔离）
                val activeIdent = appDelegate.accounts.activeAccountId
                if (activeIdent != null) {
                    appDelegate.credentials.markInitialSyncCompleted(activeIdent)
                } else {
                    appDelegate.credentials.markInitialSyncCompleted()
                }
                appDelegate.backgroundLibrarySyncer.start()

                // 短暂延迟后显示完成状态
                kotlinx.coroutines.delay(500)
                _syncState.value = InitialSyncState.Completed

            } catch (e: Exception) {
                Log.e(TAG, "Error during initial sync", e)
                _syncState.value = InitialSyncState.Error(
                    "Sync failed: ${e.message}"
                )
            }
        }
    }

    /** 复位同步状态为 Idle（Completed 为一次性事件，消费/离屏后复位，防 Activity 级实例残留） */
    fun resetSyncState() {
        _syncState.value = InitialSyncState.Idle
    }

    /**
     * 重试同步
     */
    fun retrySync() {
        startInitialSync()
    }
}

/**
 * 初始同步状态
 */
sealed class InitialSyncState {
    object Idle : InitialSyncState()
    data class Syncing(
        val currentStep: SyncStep,
        val progress: Int  // 0-100
    ) : InitialSyncState()
    object Completed : InitialSyncState()
    data class Error(val message: String) : InitialSyncState()
}

/**
 * 同步步骤
 * 对应iOS的SyncCallbacks通知类型（genre/artist/album/playlist/podcast）
 */
enum class SyncStep(val displayName: String) {
    GENRES("Syncing Genres"),
    ARTISTS("Syncing Artists"),
    ALBUMS("Syncing Albums"),
    PLAYLISTS("Syncing Playlists"),
    PODCASTS("Syncing Podcasts"),
    COMPLETED("Sync Completed")
}
