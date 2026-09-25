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

package com.amperfy.core

import android.util.Log
import com.amperfy.data.download.DownloadManager
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.repository.LibraryRepository
import com.amperfy.data.repository.PodcastRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * 后台渐进同步器 —— 进程内串行补齐专辑歌曲元数据
 *
 * 对应 iOS: AmperfyKit/Api/BackgroundLibrarySyncer.swift
 * - 进程内组件，非 OS 后台任务调度（iOS 同样不经 BGTaskScheduler/Background App Refresh）
 * - App 启动（对应 iOS startManagerForNormalOperation）与初始同步完成后
 *   （对应 startManagerAfterSync）start()；登出/Resync 时 stop()，
 *   进程终止即自然结束（对应 iOS applicationWillTerminate → stop()）
 * - 存在前提：初始同步只拉艺术家/专辑等列表，不拉专辑歌曲（对齐 iOS syncInitial），
 *   歌曲元数据由本组件渐进补齐；专辑详情页的按需同步（AlbumDetailViewModel.fetch）兜底
 *
 * 执行流程（对齐 BackgroundLibrarySyncer.swift:102-147）:
 * 1. 先同步最新专辑（iOS: autoDownloadLibrarySyncer.syncNewestLibraryElements，
 *    Auto cache latest Songs 开启时对新出现专辑的歌曲触发自动下载）
 * 2. 查询全部 isSongsSynced == false 的专辑（iOS getAlbumWithoutSyncedSongs），
 *    逐个串行 syncAlbumDetails（iOS taskQueue.maxConcurrentOperationCount = 1）
 *
 * 关键语义（与 iOS 一致）:
 * - 每个专辑同步前检查 !isOfflineMode && isConnectedToNetwork，不满足则跳过
 *   （不标记，下次启动重试）；无 WiFi-only/电量/批次/定时调度约束
 * - 同步失败记 EventLogger 后仍标记 isSongsSynced = true（不重试，避免每次启动
 *   重扫失败项；iOS 错误分支 album.isSongsMetaDataSynced = true）
 *
 * W5：每账户组件——由 [AccountComponentsRegistry] 构造时绑定 [accountInfo] 与该账户的
 * [musicRepository]/[downloadManager]（不再是 Hilt 单例）。凭证/自动缓存设置按绑定账户。
 */
class BackgroundLibrarySyncer(
    // 只消费 library 域四方法，类型收窄至窄接口（Repository 拆分批次 1）
    private val musicRepository: LibraryRepository,
    // Batch 4：最新单集同步 + 自动缓存（只用 syncNewestPodcastEpisodes 一个方法）
    private val podcastRepository: PodcastRepository,
    private val credentialsManager: CredentialsManager,
    private val settingsManager: SettingsManager,
    private val networkMonitor: NetworkMonitor,
    private val downloadManager: DownloadManager,
    private val eventLogger: EventLogger,
    private val accountSettingsStore: com.amperfy.data.local.AccountSettingsStore,
    private val accountInfo: com.amperfy.data.model.AccountInfo,
) {
    companion object {
        private const val TAG = "BackgroundLibSyncer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 对应 iOS: isCurrentlyActive */
    val isActive: Boolean
        get() = job?.isActive == true

    /** 对应 iOS: start()（已在跑则不重复启动，等价 iOS isCurrentlyActive 守卫） */
    fun start() {
        if (isActive) return
        job = scope.launch { syncAlbumSongsInBackground() }
    }

    /** 对应 iOS: stop() → taskQueue.cancelAllOperations() */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** 对应 iOS: settings.isOnlineMode && networkMonitor.isConnectedToNetwork */
    private fun isSyncAllowed(): Boolean =
        !settingsManager.isOfflineMode.value && networkMonitor.isConnectedToNetwork

    private suspend fun syncAlbumSongsInBackground() {
        // 无凭证（登出竞态）时直接退出，避免逐专辑失败并被错误标记 isSongsSynced
        credentialsManager.getCredentials(accountInfo.ident) ?: return
        Log.i(TAG, "start")

        // 1) 最新专辑 + 自动缓存（iOS BackgroundLibrarySyncer.swift:106-118）
        if (isSyncAllowed()) {
            musicRepository.syncNewestLibraryElements()
                .onSuccess { newestSongs ->
                    if (newestSongs.isNotEmpty() &&
                        accountSettingsStore.settings(accountInfo.ident).value.isAutoCacheLatestSongs) {
                        downloadManager.downloadSongs(newestSongs)
                    }
                }
                .onFailure { e ->
                    eventLogger.report("Latest Library Elements Background Sync", e)
                }
        }

        // 1b) 最新播客单集 + 自动缓存（Batch 4，iOS AutoDownloadLibrarySyncer.swift:95-115）
        //     与上方 isAutoCacheLatestSongs 块结构对称：仓库返回本次新增的最新单集（差集，
        //     初次填充返回空），开关开启时整批下载。
        //     **与 iOS 的差异（已知简化）**：iOS 在 PodcastsVC 每次进入页面时经
        //     AutoDownloadLibrarySyncer 触发，Android 把自动缓存触发点收口在本后台同步单点
        //     ——PodcastsViewModel/HomeViewModel 的同步调用不再重复触发下载，避免每次进页面
        //     都可能拉起一批下载。
        if (isSyncAllowed()) {
            podcastRepository.syncNewestPodcastEpisodes()
                .onSuccess { newEpisodes ->
                    if (newEpisodes.isNotEmpty() &&
                        accountSettingsStore.settings(accountInfo.ident).value.isAutoCacheLatestPodcastEpisodes) {
                        downloadManager.downloadEpisodes(newEpisodes)
                    }
                }
                .onFailure { e ->
                    eventLogger.report("Newest Podcast Episodes Background Sync", e)
                }
        }

        // 2) 渐进补齐专辑歌曲（iOS BackgroundLibrarySyncer.swift:120-143）
        val albumIds = musicRepository.getAlbumIdsWithoutSyncedSongs()
        Log.i(TAG, "albums without synced songs: ${albumIds.size}")
        for (albumId in albumIds) {
            coroutineContext.ensureActive()
            // 条件不满足时跳过且不标记（保持未同步，下次启动重试；对齐 iOS guard-return）
            if (!isSyncAllowed()) continue
            val result = musicRepository.syncAlbumDetails(albumId)
            if (result.isFailure) {
                eventLogger.report(
                    "Album Background Sync",
                    result.exceptionOrNull() ?: Exception("Album $albumId sync failed")
                )
                // 失败也标记，避免每次启动重扫（iOS 同语义；详情页按需同步兜底）
                musicRepository.markAlbumSongsSynced(albumId)
            }
        }

        Log.i(TAG, "stopped")
    }
}
