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

import android.util.Log
import com.amperfy.core.EventLogger
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.store.PlaybackStateStore
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.PlaybackState
import com.amperfy.data.model.Playable
import com.amperfy.data.model.PlayerMode
import com.amperfy.data.model.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PlaybackStateManager - 播放状态持久化管理器
 *
 * 对应iOS实现:
 * - LibraryStorage.getPlayerData() - 恢复状态
 * - PlayerData.setCurrentIndex() -> library.saveContext() - 保存状态
 * - AudioPlayer.savePlayInformation() - 保存播放进度
 *
 * 功能:
 * 1. 在播放状态变化时自动保存到数据库
 * 2. 应用启动时从数据库恢复播放状态
 * 3. 管理播放上下文（Album、Artist、Playlist等）
 * 4. 单曲播放进度存取（W4，iOS 2.1.0 isRememberSongPlaybackProgress；
 *    有效性判定留本类，落值经 PlaybackStateStore 写 Song 本地统计列）
 */
@Singleton
class PlaybackStateManager @Inject constructor(
    private val playbackStateStore: PlaybackStateStore,
    private val credentialsManager: CredentialsManager,
    private val eventLogger: EventLogger
) {
    private val TAG = "PlaybackStateManager"

    // 使用SupervisorJob确保协程不会因错误而取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 进度保存阈值（对应iOS的progressTimeStartThreshold和progressTimeEndThreshold）
    private val PROGRESS_START_THRESHOLD = 2000L  // 2秒
    private val PROGRESS_END_THRESHOLD = 2000L    // 2秒

    // 单曲进度有效区间边界（W4）：5s..(时长-5s) 之外视为「未开始/已听完」清零
    private val SONG_PROGRESS_EDGE_MS = 5_000L

    /**
     * 恢复的队列是否全部属于已知账户。
     * 规则：accounts_index 为空（迁移前）→ 全部视为有效；否则任一条目 accountId 非空且不在
     * index → 整体不可恢复。
     */
    private fun isRestorableForAccounts(state: PlaybackState): Boolean {
        val index = credentialsManager.getAccountsIndex()
        if (index.isEmpty()) return true
        val known = index.toHashSet()
        // Batch 2：播客队列同样纳入校验（shuffled 副本与 contextQueue 元素相同，随之覆盖）
        val all = state.playlist + state.userQueue + state.contextQueue + state.podcastQueue
        return all.none { it.accountId.isNotEmpty() && it.accountId !in known }
    }

    /**
     * 保存当前播放状态（扩展版，支持三层队列）
     *
     * iOS对应:
     * - PlayerData中每次修改后调用 library.saveContext()
     * - AudioPlayer.savePlayInformation()
     *
     * @param playlist 当前播放列表（Media3使用的底层列表）
     * @param currentIndex 当前播放索引
     * @param playProgress 播放进度（毫秒）
     * @param playDuration 歌曲总时长（毫秒）
     * @param contextType 播放上下文类型
     * @param contextId 上下文ID（Album/Artist/Playlist的ID）
     * @param contextName 上下文名称（用于显示）
     * @param playerMode 播放模式
     * @param wasPlaying 是否正在播放
     * @param userQueue 用户队列 (iOS: userQueuePlaylist)
     * @param contextQueue 上下文队列 (iOS: contextPlaylist，**始终为原始顺序**)
     * @param currentContextIndex 当前在生效上下文队列中的索引
     * @param shuffledContextQueue 打乱后的上下文副本 (Batch 2，iOS: shuffledContextPlaylist)
     * @param podcastQueue 播客独立队列 (Batch 2，iOS: podcastPlaylist)
     * @param podcastIndex 当前在播客队列中的索引 (Batch 2，iOS: PlayerMO.podcastIndex)
     * @param repeatMode 队列循环模式 (Batch 2，iOS: PlayerMO.repeatSetting)
     * @param isShuffle 队列随机模式 (Batch 2，iOS: PlayerMO.shuffleSetting)
     * @param playSource 播放来源标记
     * @param displayMode 播放器显示模式 (iOS: PopupPlayerVC.displayStyle)。
     *   **已迁 SettingsManager `player_display_style`（设备级用户偏好，非播放状态），
     *   本列冗余留待未来 schema 批清理**；当前仍透传写入，供老装机一次性回读迁移
     *
     * 注意：prevQueue不需要存储，iOS中是从contextQueue动态计算的
     */
    fun savePlaybackState(
        playlist: List<Playable>,
        currentIndex: Int,
        playProgress: Long,
        playDuration: Long,
        contextType: PlayContextType = PlayContextType.NONE,
        contextId: String? = null,
        contextName: String = "",
        playerMode: PlayerMode = PlayerMode.MUSIC,
        wasPlaying: Boolean = false,
        userQueue: List<Playable> = emptyList(),
        contextQueue: List<Playable> = emptyList(),
        currentContextIndex: Int = 0,
        shuffledContextQueue: List<Playable> = emptyList(),
        podcastQueue: List<Playable> = emptyList(),
        podcastIndex: Int = 0,
        repeatMode: RepeatMode = RepeatMode.OFF,
        isShuffle: Boolean = false,
        playSource: String = "SINGLE",
        displayMode: String = "LARGE"
    ) {
        scope.launch {
            try {
                // 应用iOS的进度保存逻辑：
                // 如果进度太接近开始或结束，保存为0
                val adjustedProgress = if (playDuration > 0 &&
                    playProgress > PROGRESS_START_THRESHOLD &&
                    playProgress < (playDuration - PROGRESS_END_THRESHOLD)
                ) {
                    playProgress
                } else {
                    0L
                }

                val state = PlaybackState(
                    id = 1,
                    playlist = playlist,
                    currentIndex = currentIndex,
                    playProgress = adjustedProgress,
                    playDuration = playDuration,
                    contextType = contextType,
                    contextId = contextId,
                    contextName = contextName,
                    playerMode = playerMode,
                    wasPlaying = wasPlaying,
                    userQueue = userQueue,
                    contextQueue = contextQueue,
                    currentContextIndex = currentContextIndex,
                    shuffledContextQueue = shuffledContextQueue,
                    podcastQueue = podcastQueue,
                    podcastIndex = podcastIndex,
                    repeatMode = repeatMode,
                    isShuffle = isShuffle,
                    playSource = playSource,
                    displayMode = displayMode,
                    savedAt = System.currentTimeMillis()
                )

                playbackStateStore.savePlaybackState(state)

                Log.d(TAG, "Saved playback state: ${playlist.size} songs, " +
                        "index=$currentIndex, progress=${adjustedProgress}ms, " +
                        "context=$contextType:$contextName, " +
                        "userQueue=${userQueue.size}, contextQueue=${contextQueue.size}, " +
                        "shuffled=${shuffledContextQueue.size}, podcast=${podcastQueue.size}:$podcastIndex, " +
                        "repeat=$repeatMode, shuffle=$isShuffle, " +
                        "playSource=$playSource, displayMode=$displayMode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save playback state", e)
            }
        }
    }

    /**
     * 快速保存播放进度（仅更新进度，不更新播放列表）
     * iOS: AudioPlayer.didElapsedTimeChange() -> savePlayInformation()
     */
    fun saveProgress(
        playProgress: Long,
        playDuration: Long,
        wasPlaying: Boolean = false
    ) {
        scope.launch {
            try {
                val currentState = playbackStateStore.getPlaybackState() ?: return@launch

                // 应用iOS的进度保存逻辑
                val adjustedProgress = if (playDuration > 0 &&
                    playProgress > PROGRESS_START_THRESHOLD &&
                    playProgress < (playDuration - PROGRESS_END_THRESHOLD)
                ) {
                    playProgress
                } else {
                    0L
                }

                val updatedState = currentState.copy(
                    playProgress = adjustedProgress,
                    playDuration = playDuration,
                    wasPlaying = wasPlaying,
                    savedAt = System.currentTimeMillis()
                )

                playbackStateStore.savePlaybackState(updatedState)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save progress", e)
            }
        }
    }

    // ===== 单曲播放进度（W4） =====

    /**
     * 写单曲播放进度（PlayerManager 在离场/暂停/周期快照时调用）。
     *
     * 写入规则：progress 在 5s..(时长-5s) 区间内才算有效进度，否则写 null 清零
     * （对齐 iOS/播客惯例：听完或刚开头不记）。有效性判定留在本类，Store 只落值。
     *
     * Batch 4：按曲目类型分派落表——歌曲写 song_local_state、播客单集写
     * podcast_episode_local_state（此前单集进度打在 song 表上，因单集不在 song 表被静默丢弃）。
     *
     * @param songId 服务端曲目 id（Playable.id）
     * @param accountId 曲目所属账户 ident（Playable.accountId）；空则回退 active（旧单账户数据）
     * @param progressMs 播放位置（毫秒）
     * @param durationMs 曲目总时长（毫秒）；未知（<=10s）时视为无效进度清零
     * @param isPodcastEpisode 曲目是否为播客单集（Playable.isPodcastEpisode）
     */
    fun saveSongProgress(
        songId: String,
        accountId: String,
        progressMs: Long,
        durationMs: Long,
        isPodcastEpisode: Boolean = false
    ) {
        scope.launch {
            try {
                val isValid = durationMs > 2 * SONG_PROGRESS_EDGE_MS &&
                    progressMs in SONG_PROGRESS_EDGE_MS..(durationMs - SONG_PROGRESS_EDGE_MS)
                val resolvedAccountId = resolveAccountId(accountId)
                val value = if (isValid) progressMs else null
                val updatedAt = if (isValid) System.currentTimeMillis() else null
                if (isPodcastEpisode) {
                    playbackStateStore.saveEpisodeProgress(resolvedAccountId, songId, value, updatedAt)
                } else {
                    playbackStateStore.saveSongProgress(resolvedAccountId, songId, value, updatedAt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save song progress: $songId", e)
            }
        }
    }

    /**
     * 读单曲/单集播放进度；无记录或无有效进度时返回 null
     * @param accountId 曲目所属账户 ident；空则回退 active
     * @param isPodcastEpisode 曲目是否为播客单集（决定读哪张本地状态表）
     */
    suspend fun getSongProgress(
        songId: String,
        accountId: String,
        isPodcastEpisode: Boolean = false
    ): Long? {
        return try {
            val resolvedAccountId = resolveAccountId(accountId)
            if (isPodcastEpisode) {
                playbackStateStore.getEpisodeProgress(resolvedAccountId, songId)
            } else {
                playbackStateStore.getSongProgress(resolvedAccountId, songId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get song progress: $songId", e)
            null
        }
    }

    /** accountId 为空（旧单账户数据）时回退 active 账户 ident */
    private fun resolveAccountId(accountId: String): String =
        accountId.ifEmpty { credentialsManager.getActiveAccountIdent() ?: "" }

    /**
     * 清除保存的播放状态
     */
    fun clearState() {
        scope.launch {
            try {
                playbackStateStore.clearPlaybackState()
                Log.d(TAG, "Cleared playback state")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear playback state", e)
            }
        }
    }

    /**
     * 获取保存的播放状态（挂起方法，PlayerManager.restorePlaybackState 调用）
     *
     * 恢复唯一通道：账户整体校验在此生效；PlayerManager.createMediaItem 的 fail-closed
     * 为第二道防线（覆盖恢复之后账户再消失的情况）。
     */
    suspend fun getSavedState(): PlaybackState? {
        return try {
            val state = playbackStateStore.getPlaybackState()
            // 多账户校验（W1）：恢复的队列条目携带 accountId（playback_queue_item 行存 account_id，
            // 恢复装配时回填到 Playable）；
            // 若任一条目账户已不存在（非空且不在 accounts_index）→ 整体丢弃恢复（简化：不做部分恢复）。
            // 迁移前无 index 时视为有效（旧数据 accountId 为空，直接放行）。
            if (state != null && !isRestorableForAccounts(state)) {
                eventLogger.info(
                    "PlaybackState",
                    message = "discarded restore: queue contains items from unknown account(s)"
                )
                Log.w(TAG, "Discarded playback restore: queue references unknown account")
                return null
            }
            state
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get saved state", e)
            null
        }
    }
}
