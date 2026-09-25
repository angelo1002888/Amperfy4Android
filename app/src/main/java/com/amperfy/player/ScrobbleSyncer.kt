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

package com.amperfy.player

import android.content.Context
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.model.Playable
import com.amperfy.data.repository.LibraryRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ScrobbleSyncer - 播放记录同步（Phase 4.7；2.1.0 W4 重写「听够」状态机）
 *
 * 对应 iOS: AmperfyKit/Player/ScrobbleSyncer.swift（2.1.0 版），语义逐项对齐：
 * - 歌曲开始播放：上报 nowPlaying（scrobble submission=false，仅在线）+ 本地 playCount+1
 *   （对应 iOS didStartPlaying + AbstractPlayable.countPlayed）
 * - 「听够」判定（2.1.0 状态机）：累计真实播放时长达到
 *   min(时长一半, 240s) 后标记——暂停期间计时器暂停（accumulatedPlayMs 累计各播放段），
 *   恢复播放时以剩余时长重启定时器；且要求缓存播放或开启 isScrobbleStreamedItems 设置
 *   （判定位置与旧版一致，在标记「听够」时检查）
 * - 切歌/停止时若已「听够」则提交 scrobble submission=true（对应 iOS syncSongStopped；
 *   提交时机收敛到切歌/停止，暂停仅结算累计不提交）
 * - 离线或失败时入队缓存（对应 iOS CoreData ScrobbleEntry，此处用 SharedPreferences JSON），
 *   在线恢复或下次成功提交后按最旧优先逐条重传（对应 iOS uploadInBackground）
 *
 * W5：每账户组件——由 [com.amperfy.core.AccountComponentsRegistry] 构造时绑定 [accountInfo]
 * 与该账户的 [library]（不再是 Hilt 单例）。凭证/离线队列/scrobble 设置均按绑定账户。
 *
 * Ampache 移植 Batch 2：协议动作经 [LibraryRepository.reportNowPlaying]/[LibraryRepository.scrobble]
 * 分派（原先直接持 SubsonicApi）——本类只管「听够」状态机与离线队列，不再知道后端类型。
 * Ampache 侧 nowPlaying 无对应物（空操作成功）、提交动作是 record_play。
 */
class ScrobbleSyncer(
    @ApplicationContext context: Context,
    /** 该账户的资料库域（协议动作分派入口，见类注释） */
    private val library: LibraryRepository,
    private val credentialsManager: CredentialsManager,
    private val settingsManager: SettingsManager,
    private val playbackStateStore: com.amperfy.data.local.store.PlaybackStateStore,
    private val accountSettingsStore: com.amperfy.data.local.AccountSettingsStore,
    private val accountInfo: com.amperfy.data.model.AccountInfo,
) {

    /** 待重传的 scrobble 记录（songId + 实际播放时间戳，对应 iOS ScrobbleEntry.date） */
    data class PendingScrobble(val songId: String, val timeMs: Long)

    // 离线队列按账户命名（"pending_scrobbles_<ident>"）
    private val prefs = context.getSharedPreferences("amperfy_scrobble", Context.MODE_PRIVATE)
    private val pendingKey = "${KEY_PENDING_SCROBBLES}_${accountInfo.ident}"

    /** 本账户凭证（绑定账户命名空间；scrobble/nowPlaying 上报用） */
    private fun accountCredentials() = credentialsManager.getCredentials(accountInfo.ident)
    private val gson = Gson()

    // 状态读写均在主线程调度器上串行（Retrofit suspend / Store 写入内部自行切线程）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queueMutex = Mutex()

    // ==================== 「听够」状态机（2.1.0 W4） ====================

    // 当前歌曲的收听判定状态
    private var currentSongId: String? = null
    /** 当前歌曲是否为缓存播放（onSongStarted 时算定，标记「听够」时参与判定） */
    private var currentSongIsCached = false
    private var listenedEnough = false
    private var alreadySubmitted = false
    private var markJob: Job? = null

    /** 累计真实播放时长（毫秒）：只累计「播放中」的墙钟时间，暂停期间不走表 */
    private var accumulatedPlayMs = 0L
    /** 本段播放起点（clock() 时间戳）；仅 isTiming=true 时有意义 */
    private var playStartTimestamp = 0L
    /** 「听够」阈值 = min(时长一半, 240s)，onSongStarted 时算定 */
    private var thresholdMs = 0L
    /** 是否处于计时段（onSongStarted/onPlaybackResumed 开启，onPlaybackPaused/结算关闭） */
    private var isTiming = false

    /**
     * 时钟注入面（最小注入：单测用 TestCoroutineScheduler 虚拟时间替换，生产默认墙钟）。
     * 状态机的累计计算与 scrobble time 参数均取自此时钟
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        // 离线模式关闭（网络恢复）时重传缓存队列
        scope.launch {
            settingsManager.isOfflineMode.collect { offline ->
                if (!offline) flushPendingScrobbles()
            }
        }
    }

    /**
     * 歌曲开始播放（PlayerManager 在歌曲切换且正在播放时调用）
     */
    fun onSongStarted(song: Playable) {
        // 结算上一首（切歌即提交已达标的，未达标则丢弃；累计/阈值等状态全部清零，
        // 不带入新曲。对应 iOS startSongPlayed 开头的 syncSongStopped）
        settleCurrent()

        // 电台/播客单集不参与 scrobble/播放计数（对应 iOS startSongPlayed 的 asSong 守卫，
        // ScrobbleSyncer.swift:177-179——Radio/PodcastEpisode 的 asSong 为 nil 直接 return）
        // settleCurrent 已清零全部状态，直接返回即可
        if (song.isRadio || song.isPodcastEpisode) {
            return
        }

        currentSongId = song.id
        currentSongIsCached = song.isDownloaded
        listenedEnough = false
        alreadySubmitted = false

        // 本地统计：开始播放即 playCount+1（对应 iOS AbstractPlayable.countPlayed；
        // 本地统计与 scrobble 成功与否相互独立）
        scope.launch {
            try {
                playbackStateStore.incrementPlayCount(accountInfo.ident, song.id)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Update local playCount failed", e)
            }
        }

        // nowPlaying 上报（submission=false，仅在线，不缓存重传；对应 iOS songPosition=.start）
        if (!settingsManager.isOfflineMode.value) {
            scope.launch {
                // 未登录早退（认证参数由 SubsonicAuthInterceptor 按账户注入）
                accountCredentials() ?: return@launch
                library.reportNowPlaying(song.id)
                    .onFailure { android.util.Log.w(TAG, "nowPlaying report failed", it) }
            }
        }

        // 「听够」阈值：min(时长一半, 240s)。coerceAtLeast(1s) 为时长未知（0）时的
        // 防御下限（沿袭旧实现，避免 0 延时立即达标）
        accumulatedPlayMs = 0L
        thresholdMs = (song.duration * 1000L / 2)
            .coerceAtMost(MAX_WAIT_MS)
            .coerceAtLeast(1000L)
        startTimingSegment(remainingMs = thresholdMs)
    }

    /**
     * 暂停：把本段播放时长并入累计并取消定时器（不提交，
     * 提交时机收敛到切歌/停止）。isTiming 守卫使重复回调幂等
     */
    fun onPlaybackPaused() {
        if (!isTiming || currentSongId == null) return
        accumulatedPlayMs += clock() - playStartTimestamp
        isTiming = false
        markJob?.cancel()
    }

    /**
     * 恢复播放（同曲 && isPlaying false→true，PlayerManager 驱动）：
     * 若累计已达阈值立即标记「听够」（定时器取消与到点竞争的兜底），
     * 否则以剩余时长重启定时器
     */
    fun onPlaybackResumed() {
        val songId = currentSongId ?: return
        if (isTiming) return // 已在计时段（防重复回调），不重启定时器
        if (accumulatedPlayMs >= thresholdMs) {
            markListenedIfEligible(songId)
            // 仍开启计时段，保证后续 pause 的累计口径一致
            isTiming = true
            playStartTimestamp = clock()
            markJob?.cancel()
        } else {
            startTimingSegment(remainingMs = thresholdMs - accumulatedPlayMs)
        }
    }

    /** 停止/清空播放器：结算并清除（对应 iOS didStopPlaying） */
    fun onPlaybackStopped() {
        settleCurrent()
    }

    /**
     * 开启一个计时段：记录起点并启动「剩余时长」定时器。
     * 定时器只计墙钟播放时间——seek 前后 isPlaying 不变则计时连续（不扣也不加），
     * 与 iOS 一致（iOS 同样不扣 seek），保持简单
     */
    private fun startTimingSegment(remainingMs: Long) {
        val songId = currentSongId ?: return
        isTiming = true
        playStartTimestamp = clock()
        markJob?.cancel()
        markJob = scope.launch {
            delay(remainingMs)
            markListenedIfEligible(songId)
        }
    }

    /**
     * 标记「听够」：需满足缓存播放或允许 scrobble 流媒体
     * （isScrobbleStreamedItems 判定位置与旧版一致，在标记时检查）
     */
    private fun markListenedIfEligible(songId: String) {
        if (currentSongId != songId) return
        if (currentSongIsCached || accountSettingsStore.settings(accountInfo.ident).value.isScrobbleStreamedItems) {
            listenedEnough = true
        }
    }

    /** 结算当前歌曲：提交已达标的 → 状态全部清零（切歌/停止共用） */
    private fun settleCurrent() {
        // 计时段进行中则先并入累计（保持累计口径完整；提交判定仍以 listenedEnough 为准）
        if (isTiming && currentSongId != null) {
            accumulatedPlayMs += clock() - playStartTimestamp
        }
        submitIfListened()
        markJob?.cancel()
        currentSongId = null
        currentSongIsCached = false
        listenedEnough = false
        alreadySubmitted = false
        accumulatedPlayMs = 0L
        playStartTimestamp = 0L
        thresholdMs = 0L
        isTiming = false
    }

    private fun submitIfListened() {
        val songId = currentSongId ?: return
        if (!listenedEnough || alreadySubmitted) return
        alreadySubmitted = true
        val timeMs = clock()
        scope.launch {
            if (submitScrobble(songId, timeMs)) {
                // 提交成功顺带重传积压队列（对应 iOS scrobble .end 成功后调 start()）
                flushPendingScrobbles()
            } else {
                enqueuePending(PendingScrobble(songId, timeMs))
            }
        }
    }

    private suspend fun submitScrobble(songId: String, timeMs: Long): Boolean {
        if (settingsManager.isOfflineMode.value) return false
        // 未登录早退（认证参数由 SubsonicAuthInterceptor 按账户注入）
        accountCredentials() ?: return false
        return library.scrobble(songId, timeMs)
            .onFailure { android.util.Log.w(TAG, "Scrobble failed: $songId", it) }
            .isSuccess
    }

    // ==================== 离线缓存队列 ====================

    private suspend fun enqueuePending(entry: PendingScrobble) {
        queueMutex.withLock {
            val queue = loadPending().toMutableList()
            queue.add(entry)
            // 上限保护，超出丢弃最旧记录
            while (queue.size > MAX_QUEUE_SIZE) queue.removeAt(0)
            savePending(queue)
        }
    }

    /** 批量重传：最旧优先逐条上传，失败即停（对应 iOS uploadInBackground 串行逐条） */
    fun flushPendingScrobbles() {
        scope.launch {
            queueMutex.withLock {
                if (settingsManager.isOfflineMode.value) return@withLock
                var queue = loadPending()
                while (queue.isNotEmpty()) {
                    val entry = queue.first()
                    if (!submitScrobble(entry.songId, entry.timeMs)) break
                    queue = queue.drop(1)
                    savePending(queue)
                }
            }
        }
    }

    private fun loadPending(): List<PendingScrobble> {
        val json = prefs.getString(pendingKey, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<PendingScrobble>>() {}.type)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse pending scrobbles, falling back to empty", e)
            emptyList()
        }
    }

    private fun savePending(queue: List<PendingScrobble>) {
        prefs.edit().putString(pendingKey, gson.toJson(queue)).apply()
    }

    companion object {
        private const val TAG = "ScrobbleSyncer"

        /** 「听够」累计播放时长上限（毫秒），对应 iOS 2.1.0 的 240s 封顶（1.2.3 为 20s） */
        private const val MAX_WAIT_MS = 240_000L

        /** 离线缓存队列上限（iOS 无上限，SharedPreferences 方案做保护） */
        private const val MAX_QUEUE_SIZE = 500

        private const val KEY_PENDING_SCROBBLES = "pending_scrobbles"
    }
}
