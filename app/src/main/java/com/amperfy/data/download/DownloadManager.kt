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

package com.amperfy.data.download

import android.content.Context
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.store.DownloadLocalStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.DownloadEntityType
import com.amperfy.data.model.DownloadItem
import com.amperfy.data.model.Playable
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Song
import com.amperfy.data.repository.MediaUrlRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DownloadManager - 管理歌曲/播客单集下载和缓存
 *
 * 对应iOS: DownloadManager / PlayableDownloadManager
 *
 * iOS实现细节:
 * - parallelDownloadsCount = 4 (同时并行下载4首)
 * - 下载对象为 AbstractPlayable：歌曲落 Library/Amperfy/accounts/<server>/<user>/songs/，
 *   播客单集落同层 episodes/ 目录（CacheFileManager.swift:727-751 按 isSong 分叉，
 *   文件扩展名由 contentType 经 MimeFileConverter.getFilenameExtension 推导）
 * - isCached = relFilePath != nil
 * - 删除缓存: 删除文件 + 设置relFilePath = nil
 */
/**
 * W5：每账户组件——由 [com.amperfy.core.AccountComponentsRegistry] 构造时绑定 [boundAccountInfo]
 * 与该账户的 [mediaUrls]（不再是 Hilt 单例）。缓存目录/主键分层按绑定账户，不再查 active。
 *
 * Batch 4：下载管线泛化到播客单集——公有入口 [downloadEpisode]/[downloadEpisodes]/
 * [deleteEpisodeCache] 与歌曲侧一一对称，内部统一收敛到 [DownloadTarget]；
 * download_entry 读写全部带 entity_type（歌曲与单集的服务端 id 分属两个命名空间，可能撞号）。
 */
class DownloadManager(
    @ApplicationContext private val context: Context,
    /**
     * 该账户的媒体 URL 域（Ampache 移植 Batch 2 起替代原来的 `subsonicApi`）：
     * 下载 URL 的三种 Cache Format 分支已搬进 MediaUrlRepository 实现，
     * 下载侧只管「拿一条 URL 去下」，不再知道后端是 Subsonic 还是 Ampache。
     */
    private val mediaUrls: MediaUrlRepository,
    private val credentialsManager: CredentialsManager,
    private val settingsManager: com.amperfy.data.local.SettingsManager,
    private val downloadStore: DownloadLocalStore,
    private val eventLogger: com.amperfy.core.EventLogger,
    private val networkMonitor: com.amperfy.core.NetworkMonitor,
    /** 绑定账户；为 null 时回退 active（兜底，正常由 registry 传入非空值） */
    private val boundAccountInfo: AccountInfo? = null,
) {
    /**
     * 单首歌曲/单集的下载进度信息
     *
     * songId 为服务端实体 id（单集时即 episode.id）——UI 侧只有实体 id，
     * 故进度 Map 的键不带类型前缀（见 [progressMap]）。
     */
    data class DownloadProgress(
        val songId: String,
        // 0.0 ~ 1.0；null = 总大小未知（如转码 chunked 无 Content-Length），UI 显示不确定态转圈
        val progress: Float?,
        val isDownloading: Boolean = true,
        val isCompleted: Boolean = false,
        val error: String? = null
    )

    /**
     * 下载对象的统一内部抽象（Batch 4，对应 iOS 以 AbstractPlayable 作 download(object:) 入参）。
     *
     * @param entityType 决定缓存目录（songs/ 或 episodes/）与 download_entry.entity_type
     * @param id 服务端实体 id（记录主键 / 缓存文件名）
     * @param apiId 请求用 id——单集为 `streamId ?? id`（iOS SubsonicApi.swift:73-76
     *   generateUrl(forDownloadingPlayable:) 同款取值）；歌曲即 id
     * @param suffix 已知扩展名（歌曲取 Song.suffix）；null 时由响应 Content-Type 推导
     */
    private data class DownloadTarget(
        val entityType: DownloadEntityType,
        val id: String,
        val apiId: String,
        val title: String,
        val suffix: String?,
    ) {
        /** 运行时集合键：两类型 id 可能撞号，故一律带类型前缀 */
        val key: String get() = "${entityType.name}:$id"

        companion object {
            fun of(song: Song) = DownloadTarget(
                entityType = DownloadEntityType.SONG,
                id = song.id,
                apiId = song.id,
                title = song.title,
                suffix = song.suffix,
            )

            fun of(episode: PodcastEpisode) = DownloadTarget(
                entityType = DownloadEntityType.PODCAST_EPISODE,
                id = episode.id,
                // iOS: let apiId = playableInfo.streamId ?? playableInfo.id
                apiId = episode.streamId ?: episode.id,
                title = episode.title,
                // 单集元数据无 suffix 字段，扩展名从响应 Content-Type 推导
                suffix = null,
            )
        }
    }

    // 写入/主键构造用绑定账户 ident；未绑定时回退 active
    private val currentAccountId: String
        get() = boundAccountInfo?.ident
            ?: credentialsManager.getCredentials()
                ?.let { AccountInfo.create(it.serverUrl, it.username).ident }
            ?: ""

    /** 本组件绑定的账户身份（缓存目录/主键分层用）；未绑定时回退 active */
    private fun currentAccountInfo(): AccountInfo? =
        boundAccountInfo
            ?: credentialsManager.getCredentials()
                ?.let { AccountInfo.create(it.serverUrl, it.username) }

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 并行下载信号量 - 限制同时下载数量
    private val downloadSemaphore = Semaphore(PARALLEL_DOWNLOADS_COUNT)

    /**
     * 当前所有下载任务的进度 Map<实体 id, progress>
     *
     * **键为纯实体 id 而非类型化键**：消费方（SongListItem / PodcastEpisodeListItem /
     * DownloadsScreen）手上只有实体 id，带前缀会全部查空。歌曲与单集 id 理论上可撞号，
     * 撞号时进度环可能串行到另一行（纯显示态、随下载结束自然消失），已知简化。
     */
    private val _downloadProgressMap = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, DownloadProgress>> = _downloadProgressMap.asStateFlow()

    // 内部使用的可变Map
    private val progressMap = ConcurrentHashMap<String, DownloadProgress>()

    // 下载队列 - 等待下载的类型化键（DownloadTarget.key）
    private val pendingDownloads = ConcurrentHashMap.newKeySet<String>()

    // 正在下载的类型化键
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()

    // 每个下载对象的协程（用于取消单个下载），键同为类型化键
    private val downloadJobs = ConcurrentHashMap<String, Job>()

    /**
     * 持久化下载记录（DownloadsScreen 数据源，按请求时间升序）
     * 对应 iOS: DownloadsFetchedResultsController（DownloadMO creationDate 排序）
     *
     * 专题 15 P1：领域 [DownloadItem] 列表（store 内批量装配歌曲/单集，替代 ViewModel 逐条查询的 N+1）。
     */
    val downloadItems: Flow<List<DownloadItem>> =
        // 绑定账户的下载记录（DownloadsScreen 只显示 active 账户）。
        // 用冷 flow 在「被收集时」读 currentAccountId——避免登录前构造时把空
        // accountId 固化进查询（DownloadsScreen 总在登录后打开）。
        flow { emitAll(downloadStore.observeItems(currentAccountId)) }

    companion object {
        private const val TAG = "DownloadManager"
        private const val SONGS_DIR = "songs"

        /** 播客单集缓存目录（对应 iOS CacheFileManager.episodesDir = "episodes"，:734） */
        private const val EPISODES_DIR = "episodes"
        private const val ACCOUNTS_DIR = "accounts"
        private const val TEMP_SUFFIX = ".tmp"
        // iOS: parallelDownloadsCount = 4
        private const val PARALLEL_DOWNLOADS_COUNT = 4

        /**
         * 扩展名兜底（iOS MimeFileConverter.filenameExtensionUnknown = "unknown"；
         * Android 取可播放的 mp3——扩展名只是 ExoPlayer 的提示，容器仍由内容嗅探决定）
         */
        private const val DEFAULT_FILE_EXTENSION = "mp3"

        // 下载最大尝试次数（初次 + 2 次重试）——见下方 downloadHttpClient 与 executeDownload 重试循环
        private const val DOWNLOAD_MAX_ATTEMPTS = 3

        // 恢复联网后重跑下载队列前的去抖窗口（WiFi↔蜂窝切换期间连通性可能短暂反复）
        private const val NETWORK_RESUME_DEBOUNCE_MS = 500L

        /**
         * MIME → 文件扩展名（对应 iOS MimeFileConverter.getFilenameExtension(mimeType:)，
         * CacheFileManager.swift:70-78——iOS 经 UTType 反查 + 自维护 mimeTypes 表补 ogg/flac）。
         * Android 无 UTType，按 iOS 实际覆盖到的音频类型显式列表；未命中兜底 [DEFAULT_FILE_EXTENSION]。
         */
        private val MIME_TO_EXTENSION = mapOf(
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/mp4" to "m4a",
            "audio/m4a" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/aac" to "aac",
            "audio/aacp" to "aac",
            "audio/ogg" to "ogg",
            "application/ogg" to "ogx",
            "audio/opus" to "opus",
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",
            "audio/wav" to "wav",
            "audio/wave" to "wav",
            "audio/x-wav" to "wav",
            "audio/x-ms-wma" to "wma",
        )

        /**
         * 下载专用 HTTP 客户端（所有账户实例共享一份，故置于 companion）。
         *
         * 根因（logcat + Event Log 实证）：下载原先走共享 OkHttpClient 的 HTTP/2 连接，与播放
         * 流媒体及 4 路并行下载在同一条 h2 连接上多路复用，服务器/反代在此并发下重置流
         * （okhttp3.internal.http2.StreamResetException: stream was reset: INTERNAL_ERROR），
         * 导致下载读流循环中途抛错、isDownloaded 永不写库。
         *
         * 这里强制 HTTP/1.1（每次下载独占一条连接，不做流多路复用）从根本规避该重置；
         * 下载 URL 为经 MediaUrlRepository 构建的绝对 URL（含 scheme/host/认证查询参数，
         * baseUrl 取绑定账户 active_server_url），故不经 AccountBaseUrlInterceptor 也能直连。
         */
        private val downloadHttpClient: OkHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))   // 规避 h2 多路复用被服务器重置（根因）
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    init {
        // 应用启动时清理不完整的下载文件
        cleanupIncompleteDownloads()
        // 恢复上次未完成的下载请求（对应 iOS DownloadManager.start → setupDownloadQueue）
        resumeRequestedDownloads()
        // 恢复联网后自动续传
        observeNetworkForResume()
    }

    /**
     * 恢复联网后自动重跑下载队列
     * 对应 iOS: DownloadManager.networkStatusChanged → isAllowedToTriggerDownload 时
     * setupDownloadQueue（DownloadManager.swift:519-548）
     *
     * drop(1) 跳过 StateFlow 的当前值（构造瞬间不重复触发 init 里已跑过的恢复）；
     * filter { it } 只在「恢复联网」时动作（断网侧的挂起由 [suspendDownloadOnNetworkLoss] 处理）；
     * debounce 防 WiFi↔蜂窝切换期间连通性短暂反复（iOS 侧由「只在翻转时发通知」天然去抖，
     * Android 这里再加一层窗口）。
     */
    @OptIn(FlowPreview::class)
    private fun observeNetworkForResume() {
        scope.launch {
            networkMonitor.isConnected
                .drop(1)
                .filter { it }
                .debounce(NETWORK_RESUME_DEBOUNCE_MS)
                .collect {
                    android.util.Log.d(TAG, "Network restored, resuming requested downloads")
                    resumeRequestedDownloads()
                }
        }
    }

    /**
     * 启动时把持久化记录中未完成（未成功且未失败）的下载重新入队
     * 对应 iOS: setupDownloadQueue（getRequestedDownloads 重新 addDownloadTaskOperation）
     *
     * Batch 4：条目按 entity_type 分派到各自的实体解析；实体已不在库中的记录删除（两类型同口径）。
     */
    private fun resumeRequestedDownloads() {
        scope.launch {
            val aid = currentAccountId
            downloadStore.requestedDownloads(aid).forEach { ref ->
                when (ref.entityType) {
                    DownloadEntityType.SONG -> {
                        val song = downloadStore.getSong(aid, ref.id)
                        when {
                            // 歌曲已不在库中，记录无法恢复，直接删除
                            song == null -> downloadStore.deleteEntry(aid, ref.id, DownloadEntityType.SONG)
                            song.isDownloaded -> markDownloadFinished(DownloadTarget.of(song))
                            else -> downloadSong(song)
                        }
                    }

                    DownloadEntityType.PODCAST_EPISODE -> {
                        val episode = downloadStore.getEpisode(aid, ref.id)
                        when {
                            episode == null ->
                                downloadStore.deleteEntry(aid, ref.id, DownloadEntityType.PODCAST_EPISODE)
                            episode.isDownloaded -> markDownloadFinished(DownloadTarget.of(episode))
                            else -> downloadEpisode(episode)
                        }
                    }
                }
            }
        }
    }

    // ===== 持久化下载记录维护（对应 iOS DownloadRequestManager 对 DownloadMO 的维护）=====

    /** 请求时建立/重置记录（重试沿用原 creationDate，对应 iOS download.reset()） */
    private suspend fun upsertDownloadEntry(target: DownloadTarget) {
        downloadStore.upsertRequested(
            currentAccountId, target.id, System.currentTimeMillis(), target.entityType
        )
    }

    private suspend fun markDownloadFinished(target: DownloadTarget) {
        downloadStore.markFinished(
            currentAccountId, target.id, System.currentTimeMillis(), target.entityType
        )
    }

    private suspend fun markDownloadError(target: DownloadTarget) {
        downloadStore.markFailed(
            currentAccountId, target.id, System.currentTimeMillis(), target.entityType
        )
    }

    /**
     * 获取媒体缓存目录（账户分层）：
     * files/accounts/<serverHash>/<userHash>/{songs|episodes}/
     * 无凭证时维持旧目录 files/{songs|episodes}/（未登录不下载，仅为兜底不崩溃）
     *
     * 对应 iOS CacheFileManager.getOrCreateAbsoluteSongsDirectory /
     * getOrCreateAbsolutePodcastEpisodesDirectory（CacheFileManager.swift:747-770）
     */
    private fun getMediaDirectory(entityType: DownloadEntityType, accountInfo: AccountInfo?): File {
        val dirName = when (entityType) {
            DownloadEntityType.SONG -> SONGS_DIR
            DownloadEntityType.PODCAST_EPISODE -> EPISODES_DIR
        }
        val dir = if (accountInfo != null) {
            File(context.filesDir, "$ACCOUNTS_DIR/${accountInfo.serverHash}/${accountInfo.userHash}/$dirName")
        } else {
            File(context.filesDir, dirName)
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** 指定账户的歌曲缓存目录 */
    private fun getSongsDirectory(accountInfo: AccountInfo?): File =
        getMediaDirectory(DownloadEntityType.SONG, accountInfo)

    /** 指定账户的播客单集缓存目录 */
    private fun getEpisodesDirectory(accountInfo: AccountInfo?): File =
        getMediaDirectory(DownloadEntityType.PODCAST_EPISODE, accountInfo)

    /** 指定账户的两个媒体目录（缓存统计/清理一律覆盖两者） */
    private fun mediaDirectories(accountInfo: AccountInfo?): List<File> =
        listOf(getSongsDirectory(accountInfo), getEpisodesDirectory(accountInfo))

    /**
     * 获取缓存文件路径。扩展名优先用已知 suffix（歌曲），否则用调用方从响应
     * Content-Type 推导出的 [extension]（单集）
     */
    private fun getMediaFilePath(target: DownloadTarget, extension: String): File =
        File(getMediaDirectory(target.entityType, currentAccountInfo()), "${target.id}.$extension")

    /**
     * 获取临时下载文件路径：`<id>.tmp`（不带媒体扩展名）
     *
     * 单集的最终扩展名要等响应 Content-Type 才能确定，故临时文件统一不带扩展名，
     * 传输完成后再按最终扩展名重命名；清理逻辑按 `.tmp` 后缀扫描，新旧两种形态都覆盖。
     */
    private fun getTempFilePath(target: DownloadTarget): File =
        File(getMediaDirectory(target.entityType, currentAccountInfo()), "${target.id}$TEMP_SUFFIX")

    /**
     * MIME → 扩展名（对应 iOS MimeFileConverter.getFilenameExtension）。
     * Content-Type 可能带参数（如 "audio/mpeg; charset=utf-8"），取分号前的主类型。
     */
    private fun filenameExtension(contentType: String?): String {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        return MIME_TO_EXTENSION[mime] ?: DEFAULT_FILE_EXTENSION
    }

    /**
     * 清理不完整的下载文件（.tmp文件）
     * 在应用启动时调用，删除歌曲/单集两个目录中上次下载中断留下的临时文件
     */
    private fun cleanupIncompleteDownloads() {
        scope.launch {
            try {
                val tempFiles = mediaDirectories(currentAccountInfo()).flatMap { dir ->
                    dir.listFiles { file -> file.name.endsWith(TEMP_SUFFIX) }?.toList() ?: emptyList()
                }

                if (tempFiles.isNotEmpty()) {
                    android.util.Log.d(TAG, "Cleaning up ${tempFiles.size} incomplete download(s)")
                    tempFiles.forEach { file ->
                        file.delete()
                        android.util.Log.d(TAG, "Deleted incomplete file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error cleaning up incomplete downloads", e)
            }
        }
    }

    /**
     * 更新进度Map并通知观察者
     */
    private fun updateProgress(songId: String, progress: DownloadProgress) {
        progressMap[songId] = progress
        _downloadProgressMap.value = progressMap.toMap()
    }

    /**
     * 移除进度信息
     */
    private fun removeProgress(songId: String) {
        progressMap.remove(songId)
        _downloadProgressMap.value = progressMap.toMap()
    }

    /**
     * 检查歌曲/单集是否正在下载或等待下载
     *
     * 入参为纯实体 id（调用方手上只有 id），故两个类型都查——撞号时略保守地返回 true
     * （多一次跳过而非重复下载），与 [progressMap] 同款取舍。
     */
    fun isDownloadingOrPending(songId: String): Boolean =
        DownloadEntityType.entries.any { type ->
            val key = "${type.name}:$songId"
            activeDownloads.contains(key) || pendingDownloads.contains(key)
        }

    /**
     * 获取歌曲的下载进度 (0.0 ~ 1.0)，如果没有在下载返回null
     */
    fun getDownloadProgress(songId: String): Float? {
        return progressMap[songId]?.progress
    }

    /**
     * 下载歌曲（加入队列，使用并行下载）
     * 对应iOS: PlayableDownloadManager.download(object:notifier:)
     */
    fun downloadSong(song: Song) {
        if (song.isDownloaded) return
        enqueue(DownloadTarget.of(song))
    }

    /**
     * 下载播客单集（Batch 4，与 [downloadSong] 逐条对称）
     * 对应iOS: PlayableDownloadManager.download(object:)——iOS 下载对象为 AbstractPlayable，
     * 歌曲与单集共用同一队列/并发/重试策略
     */
    fun downloadEpisode(episode: PodcastEpisode) {
        if (episode.isDownloaded) return
        enqueue(DownloadTarget.of(episode))
    }

    /** 批量下载播客单集（自动缓存最新单集、菜单 Download 用） */
    fun downloadEpisodes(episodes: List<PodcastEpisode>) {
        episodes.forEach { downloadEpisode(it) }
    }

    /** 入队公共路径（歌曲/单集共用，对应 iOS 单一 download(object:) 入口） */
    private fun enqueue(target: DownloadTarget) {
        // 检查是否正在下载或等待下载
        if (isDownloadingOrPending(target.id)) {
            return
        }

        // 缓存超限时拒绝新下载（对应 iOS storageExceedsCacheLimit：仅阻止下载，不做 LRU 淘汰）
        if (storageExceedsCacheLimit()) {
            android.util.Log.w(TAG, "Cache size limit exceeded, download refused: ${target.title}")
            return
        }

        pendingDownloads.add(target.key)
        // 尚无长度信息，progress = null（未知/不确定态）
        updateProgress(target.id, DownloadProgress(target.id, null, isDownloading = true))

        val job = scope.launch {
            // 先落持久化记录再执行（同协程内保证 upsert 先于 finish/error 标记）
            upsertDownloadEntry(target)
            downloadSemaphore.withPermit {
                pendingDownloads.remove(target.key)
                activeDownloads.add(target.key)
                try {
                    executeDownload(target)
                } finally {
                    activeDownloads.remove(target.key)
                    downloadJobs.remove(target.key)
                }
            }
        }
        downloadJobs[target.key] = job
    }

    /**
     * 取消单个下载（等待中或进行中均可）
     * 记录保留并标记为已取消（iOS: download.isCanceled = true 即 error = .canceled，
     * 条目留在列表中显示感叹号，可经 Retry failed downloads 重试）
     */
    fun cancelDownload(song: Song) {
        cancelTarget(DownloadTarget.of(song))
    }

    /** 取消单个单集下载（与 [cancelDownload] 对称） */
    fun cancelEpisodeDownload(episode: PodcastEpisode) {
        cancelTarget(DownloadTarget.of(episode))
    }

    private fun cancelTarget(target: DownloadTarget) {
        pendingDownloads.remove(target.key)
        downloadJobs.remove(target.key)?.cancel()
        activeDownloads.remove(target.key)
        // 清理未完成的临时文件与进度条目
        getTempFilePath(target).delete()
        removeProgress(target.id)
        scope.launch { markDownloadError(target) }
        android.util.Log.d(TAG, "Download canceled: ${target.title}")
    }

    /**
     * 取消全部下载（等待中 + 进行中），已完成/失败的记录保留
     * 对应 iOS: DownloadsVC "Cancel all downloads"（DownloadRequestManager.cancelDownloads：
     * 未完成条目标记 isCanceled，记录不删除）
     */
    fun cancelAllDownloads() {
        val keys = (pendingDownloads + activeDownloads).toSet()
        pendingDownloads.clear()
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        activeDownloads.clear()
        // 类型化键 → 实体 id（进度 Map 以实体 id 为键）
        keys.forEach { key -> removeProgress(key.substringAfter(':')) }
        scope.launch {
            downloadStore.markAllUnfinishedFailed(currentAccountId, System.currentTimeMillis())
        }
        // 清理全部临时文件（歌曲 + 单集两个目录）
        mediaDirectories(currentAccountInfo()).forEach { dir ->
            dir.listFiles { f -> f.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
        }
        android.util.Log.d(TAG, "All downloads canceled (${keys.size})")
    }

    /**
     * 清除已结束（完成/失败/已取消）的下载记录，保留进行中与等待中的
     * 对应 iOS: DownloadRequestManager.clearFinishedDownloads
     * （删除 finishDate 或 errorDate 非空的 DownloadMO）
     */
    fun clearFinishedDownloads() {
        scope.launch {
            downloadStore.clearFinished(currentAccountId)
            val finishedIds = progressMap.filterValues { !it.isDownloading }.keys
            finishedIds.forEach { progressMap.remove(it) }
            _downloadProgressMap.value = progressMap.toMap()
        }
    }

    /**
     * 重试全部失败（含已取消）的下载
     * 对应 iOS: getAndResetFailedDownloads（errorDate 非空的条目 reset 后重新入队）
     */
    fun retryFailedDownloads() {
        scope.launch {
            val aid = currentAccountId
            downloadStore.failedDownloads(aid).forEach { ref ->
                removeProgress(ref.id)
                when (ref.entityType) {
                    DownloadEntityType.SONG ->
                        downloadStore.getSong(aid, ref.id)?.let { downloadSong(it) }
                    DownloadEntityType.PODCAST_EPISODE ->
                        downloadStore.getEpisode(aid, ref.id)?.let { downloadEpisode(it) }
                }
            }
        }
    }

    /** 缓存是否已超过大小限制（limit = 0 表示不限制） */
    fun storageExceedsCacheLimit(): Boolean {
        val limitMB = settingsManager.cacheSizeLimitMB.value
        return limitMB > 0 && getCacheSize() > limitMB.toLong() * 1024 * 1024
    }

    /**
     * 通过Playable下载（歌曲 / 播客单集，按 isPodcastEpisode 分派）
     *
     * Batch 4 起单集不再被静默丢弃——经 store 解析回领域单集后走同一条下载管线
     * （对应 iOS PlayerDownloadPreparationHandler 对 AbstractPlayable 一视同仁）。
     */
    fun downloadPlayable(playable: Playable) {
        if (playable.isDownloaded || isDownloadingOrPending(playable.id)) {
            return
        }

        scope.launch {
            if (playable.isPodcastEpisode) {
                val episode = downloadStore.getEpisode(currentAccountId, playable.id)
                if (episode != null) {
                    downloadEpisode(episode)
                } else {
                    android.util.Log.w(TAG, "Podcast episode not found in database: ${playable.id}")
                }
            } else {
                val song = downloadStore.getSong(currentAccountId, playable.id)
                if (song != null) {
                    downloadSong(song)
                } else {
                    android.util.Log.w(TAG, "Song not found in database: ${playable.id}")
                }
            }
        }
    }

    /**
     * 批量下载歌曲（用于下载专辑/播放列表）
     * 对应iOS: 播放时下载当前歌曲+连续的后续歌曲
     */
    fun downloadSongs(songs: List<Song>) {
        songs.forEach { song ->
            downloadSong(song)
        }
    }

    /**
     * 下载播放列表中从指定位置开始的连续歌曲
     * 对应iOS: 播放一首歌时下载当前及后续连续的歌曲
     * @param songs 完整播放列表
     * @param startIndex 开始位置
     * @param count 要下载的数量（默认4首，对应iOS parallelDownloadsCount）
     */
    fun downloadFromPlaylist(songs: List<Song>, startIndex: Int, count: Int = PARALLEL_DOWNLOADS_COUNT) {
        val endIndex = minOf(startIndex + count, songs.size)
        for (i in startIndex until endIndex) {
            downloadSong(songs[i])
        }
    }

    /**
     * 执行实际的下载操作（歌曲/单集共用；差异全部收敛在 [DownloadTarget] 与落库分支）
     */
    private suspend fun executeDownload(target: DownloadTarget) = withContext(Dispatchers.IO) {
        try {
            // 绑定账户凭证（下载 URL 按本组件绑定账户构建；未绑定回退 active）
            val credentials = boundAccountInfo?.let { credentialsManager.getCredentials(it.ident) }
                ?: credentialsManager.getCredentials()
            if (credentials == null) {
                updateProgress(target.id, DownloadProgress(target.id, null, isDownloading = false, error = "No credentials"))
                markDownloadError(target)
                return@withContext
            }

            // 再次检查是否已下载
            val alreadyCached = when (target.entityType) {
                DownloadEntityType.SONG ->
                    downloadStore.getSong(currentAccountId, target.id)?.isDownloaded == true
                DownloadEntityType.PODCAST_EPISODE ->
                    downloadStore.getEpisode(currentAccountId, target.id)?.isDownloaded == true
            }
            if (alreadyCached) {
                removeProgress(target.id)
                markDownloadFinished(target)
                return@withContext
            }

            // 下载 URL（Ampache 移植 Batch 2 起由 MediaUrlRepository 按后端构建）：
            // Cache Format（Settings→Player→Cache Format (Transcoding)）的三分支语义与
            // estimateContentLength 的根因注释已随实现搬到 MediaUrlRepositoryImpl.getDownloadUrl，
            // 此处只保留下载侧要知道的一件事——转码响应可能是 chunked（无 Content-Length），
            // 下方进度循环 totalBytes = -1 时跳过进度更新，UI 显示不确定态转圈，下载仍正常完成。
            // 请求 id 一律取 target.apiId（单集为 streamId ?? id，iOS SubsonicApi.swift:73-76）。
            val downloadUrl = mediaUrls.getDownloadUrl(
                apiId = target.apiId,
                isPodcastEpisode = target.entityType == DownloadEntityType.PODCAST_EPISODE,
                transcoding = settingsManager.cacheTranscodingFormatPreference.value,
                username = credentials.username,
                password = credentials.password,
                baseUrl = credentials.serverUrl,
            )

            android.util.Log.d(TAG, "Downloading ${target.entityType.name}: ${target.title}")

            // 使用临时文件下载，完成后再重命名
            // 这样可以避免下载中断时留下不完整的文件
            val tempFile = getTempFilePath(target)
            // 响应 Content-Type（单集据此定扩展名；歌曲有 suffix 时不使用）
            var responseContentType: String? = null

            // 传输重试循环（根因见 companion.downloadHttpClient）：downloadHttpClient 已强制
            // HTTP/1.1 规避 h2 流重置；这里再对「发请求 → 读流」中途的 IOException（含
            // okhttp StreamResetException、瞬时网络抖动）兜底重试——最多 DOWNLOAD_MAX_ATTEMPTS 次
            // （初次 + 2 次重试），退避 1s/2s；每次重试删除临时文件从头重下。
            // HTTP 非 2xx 属服务器明确拒绝，不重试（保持原有语义，直接 return）；
            // CancellationException（用户取消）不落 IOException 分支，直接上抛交外层处理。
            var transferError: IOException? = null
            var attempt = 0
            while (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                attempt++
                try {
                    if (attempt > 1) {
                        // 重试前清理上次残留并复位进度，条目保持 isDownloading
                        tempFile.delete()
                        // 复位为未知长度（progress = null，不确定态）
                        updateProgress(target.id, DownloadProgress(target.id, null, isDownloading = true))
                    }

                    downloadHttpClient.newCall(Request.Builder().url(downloadUrl).build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            val error = "HTTP ${response.code}"
                            updateProgress(target.id, DownloadProgress(target.id, null, isDownloading = false, error = error))
                            markDownloadError(target)
                            android.util.Log.e(TAG, "Download failed: $error")
                            return@withContext
                        }

                        val body = response.body
                        if (body == null) {
                            updateProgress(target.id, DownloadProgress(target.id, null, isDownloading = false, error = "Empty response"))
                            markDownloadError(target)
                            return@withContext
                        }

                        // 单集扩展名的唯一来源（iOS 取 playable.contentTypeTranscoded ?? contentType）
                        responseContentType = response.header("Content-Type")

                        val totalBytes = body.contentLength()
                        var downloadedBytes = 0L
                        body.byteStream().use { input ->
                            tempFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    if (totalBytes > 0) {
                                        val progress = downloadedBytes.toFloat() / totalBytes
                                        updateProgress(target.id, DownloadProgress(target.id, progress, isDownloading = true))
                                    }
                                }
                            }
                        }
                    }

                    // 传输成功 → 跳出重试循环
                    transferError = null
                    break
                } catch (e: IOException) {
                    // 传输中途失败（含 okhttp StreamResetException）：删除临时文件，退避后重试
                    transferError = e
                    tempFile.delete()
                    if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                        android.util.Log.w(TAG, "Download transfer failed (attempt $attempt/$DOWNLOAD_MAX_ATTEMPTS), retrying: ${target.title}", e)
                        delay(1000L * attempt)   // 退避 1s、2s
                    }
                }
            }

            // 全部尝试失败 → 上抛交外层 catch 记 EventLogger + markDownloadError
            transferError?.let { throw it }

            // 最终扩展名：歌曲用元数据 suffix；单集（无 suffix）由响应 Content-Type 推导，
            // 对应 iOS CacheFileManager.createRelPath:730-739（contentTypeTranscoded/contentType
            // → MimeFileConverter.getFilenameExtension）
            val extension = target.suffix?.takeIf { it.isNotBlank() } ?: filenameExtension(responseContentType)
            val outputFile = getMediaFilePath(target, extension)

            // 下载完成，将临时文件重命名为正式文件
            if (tempFile.exists()) {
                // 如果目标文件已存在，先删除
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                val renamed = tempFile.renameTo(outputFile)
                if (!renamed) {
                    // 如果重命名失败，尝试复制
                    tempFile.copyTo(outputFile, overwrite = true)
                    tempFile.delete()
                }
            }

            // 更新数据库：标记已缓存
            // 存相对 filesDir 的路径——避免 App 数据目录变化后失效，
            // 读取处统一经 DownloadPathResolver.resolve 还原绝对路径
            val relativePath = outputFile.relativeTo(context.filesDir).path
            val cached = when (target.entityType) {
                DownloadEntityType.SONG ->
                    downloadStore.setSongCached(currentAccountId, target.id, relativePath)
                DownloadEntityType.PODCAST_EPISODE ->
                    downloadStore.setEpisodeCached(currentAccountId, target.id, relativePath)
            }
            if (!cached) {
                android.util.Log.e(
                    TAG, "Entity not found in database for update: ${target.entityType.name} ${target.id}"
                )
            }

            updateProgress(target.id, DownloadProgress(target.id, 1f, isDownloading = false, isCompleted = true))
            markDownloadFinished(target)
            android.util.Log.d(TAG, "Download completed: ${target.title}")

            // 保留已完成的下载记录更长时间（5秒）
            // 给数据库 Flow 足够时间更新 UI
            delay(5000)
            removeProgress(target.id)

        } catch (e: CancellationException) {
            // 用户取消：清理临时文件与进度条目，不记录为错误
            getTempFilePath(target).delete()
            removeProgress(target.id)
            throw e
        } catch (e: Exception) {
            // 断网 = 挂起而非失败（对应 iOS networkStatusChanged → suspendDownloads，
            // DownloadManager.swift:519-548：iOS 断网时挂起在途任务，条目仍是 requested 态，
            // 恢复联网后被重新入队）。这里在传输异常落点复查连通性：此刻无网即视为网络中断，
            // 不写 error_date——条目保持 finish/error 双空的 requested 态，恢复联网时由
            // resumeRequestedDownloads 自然捞回；error_date 仍专表「失败/用户取消」，
            // 不会被网络抖动污染（否则 Retry failed 会复活用户取消的项）。
            if (!networkMonitor.isConnectedToNetwork) {
                suspendDownloadOnNetworkLoss(target)
                return@withContext
            }
            android.util.Log.e(TAG, "Download error: ${target.title}", e)
            // 对应 iOS EventLogger downloadError 事件（statusCode=1）
            eventLogger.error(
                "Download Error",
                com.amperfy.data.model.AmperfyLogStatusCode.DOWNLOAD_ERROR,
                "${target.title}: ${e.message ?: e.javaClass.simpleName}"
            )
            updateProgress(target.id, DownloadProgress(target.id, null, isDownloading = false, error = e.message))
            markDownloadError(target)
        }
    }

    /**
     * 网络中断挂起：清理临时文件与进度条目，**不写 error_date**（记录保持 requested 态）。
     *
     * 只清 progressMap 与 .tmp：pendingDownloads 早在进入 executeDownload 前已移除，
     * activeDownloads/downloadJobs 由 [enqueue] 的 finally 统一移除——这里若抢先移除，
     * 恢复联网后新入队的同曲任务会被随后执行的旧 finally 反注册（新任务失去取消能力且
     * isDownloadingOrPending 失真），故刻意不碰这两处。
     */
    private fun suspendDownloadOnNetworkLoss(target: DownloadTarget) {
        getTempFilePath(target).delete()
        removeProgress(target.id)
        android.util.Log.i(TAG, "No internet; download suspended (stays requested): ${target.title}")
    }

    /**
     * 删除歌曲缓存
     */
    suspend fun deleteSongCache(song: Song): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cachedFile = if (song.downloadPath != null) {
                // 兼容相对（新）/绝对（迁移前存量）两种形态
                DownloadPathResolver.resolve(context.filesDir, song.downloadPath)
            } else {
                getMediaFilePath(DownloadTarget.of(song), song.suffix ?: DEFAULT_FILE_EXTENSION)
            }

            if (cachedFile.exists()) {
                cachedFile.delete()
            }

            // 清除歌曲缓存标记（歌曲不在库中时静默跳过，与原实现一致）
            downloadStore.clearSongCached(currentAccountId, song.id)

            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Delete cache error: ${song.title}", e)
            Result.failure(e)
        }
    }

    /**
     * 删除播客单集缓存（Batch 4，镜像 [deleteSongCache]）
     *
     * 单集无 suffix 字段，cachePath 为空时无法反推文件名 → 兜底扫描 episodes/ 目录中
     * 以 `<id>.` 开头的文件（正常路径上 cachePath 必非空，此分支仅防御脏数据）。
     */
    suspend fun deleteEpisodeCache(episode: PodcastEpisode): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cachedFiles = if (episode.cachePath != null) {
                listOf(DownloadPathResolver.resolve(context.filesDir, episode.cachePath))
            } else {
                getEpisodesDirectory(currentAccountInfo())
                    .listFiles { f -> f.name.startsWith("${episode.id}.") }
                    ?.toList() ?: emptyList()
            }

            cachedFiles.forEach { if (it.exists()) it.delete() }

            // 清除单集缓存标记（单集不在库中时静默跳过，与歌曲侧一致）
            downloadStore.clearEpisodeCached(currentAccountId, episode.id)

            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Delete episode cache error: ${episode.title}", e)
            Result.failure(e)
        }
    }

    /**
     * 批量删除缓存
     */
    suspend fun deleteSongsCache(songs: List<Song>): Result<Unit> {
        var failedCount = 0
        songs.forEach { song ->
            if (deleteSongCache(song).isFailure) {
                failedCount++
            }
        }
        return if (failedCount == 0) Result.success(Unit)
        else Result.failure(Exception("$failedCount songs failed"))
    }

    /**
     * 获取缓存大小（active 账户；现有调用方语义 = active 账户）
     */
    fun getCacheSize(): Long = getCacheSize(currentAccountInfo())

    /**
     * 指定账户的缓存大小（统计分账户）——歌曲 + 单集两个目录，
     * 对应 iOS calculatePlayableCacheSize（songsDir + episodesDir，CacheFileManager.swift:300-310）
     */
    fun getCacheSize(accountInfo: AccountInfo?): Long {
        return mediaDirectories(accountInfo)
            .flatMap { it.walkTopDown().filter { f -> f.isFile } }
            .sumOf { it.length() }
    }

    /**
     * 全部账户缓存总大小（含旧的 files/songs、files/episodes 兜底目录）。
     * cacheSizeLimitMB 维持全局，但本方法此阶段无调用方（W5 用）。
     */
    fun getTotalCacheSize(): Long {
        val roots = listOf(
            File(context.filesDir, ACCOUNTS_DIR),
            File(context.filesDir, SONGS_DIR),
            File(context.filesDir, EPISODES_DIR)
        )
        return roots.filter { it.exists() }
            .flatMap { it.walkTopDown().filter { f -> f.isFile } }
            .sumOf { it.length() }
    }

    /**
     * 删除整个账户的缓存目录（登出用，供 W5 调用；本阶段无调用方）。
     * 对应 iOS deleteAccountCache。
     */
    fun deleteAccountCache(accountInfo: AccountInfo) {
        mediaDirectories(accountInfo).forEach { it.deleteRecursively() }
    }

    /**
     * 清除所有缓存（active 账户）——歌曲与单集两个目录 + 两张本地状态表的缓存标记
     */
    suspend fun clearAllCache(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val aid = currentAccountId
            mediaDirectories(currentAccountInfo()).forEach { dir ->
                dir.deleteRecursively()
                dir.mkdirs()
            }

            // 本账户全部已下载歌曲/单集清除缓存标记
            downloadStore.clearAllSongsCached(aid)
            downloadStore.clearAllEpisodesCached(aid)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * 下载路径解析工具（C 项收口）：
 * 歌曲 downloadPath 存相对 filesDir 的路径；读取处统一经此还原绝对 File。
 * 兼容存量：以 '/' 开头视为绝对路径（迁移前写入的 outputFile.absolutePath）。
 */
object DownloadPathResolver {
    fun resolve(filesDir: File, stored: String): File =
        if (stored.startsWith("/")) File(stored) else File(filesDir, stored)
}
