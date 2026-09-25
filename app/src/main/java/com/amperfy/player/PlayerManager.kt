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

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Playable
import com.amperfy.data.model.PlayerMode
import com.amperfy.data.model.RepeatMode
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackStateManager: PlaybackStateManager,
    private val settingsManager: SettingsManager,
    // W5：播放器保持全局单例；下载/scrobble/流 URL 均按曲目所属账户经 registry 路由。
    private val registry: com.amperfy.core.AccountComponentsRegistry,
    private val accountManager: com.amperfy.core.AccountManager,
    private val credentialsManager: com.amperfy.data.local.CredentialsManager,
) {

    /**
     * 按曲目所属账户解析服务栈：
     * - accountId 为空（旧单账户数据）→ 回退 active 账户组件；
     * - accountId 非空但解析不到（账户已登出/迁移异常）→ null，调用方 fail-closed，禁止回退 active。
     */
    private fun componentsFor(playable: Playable): com.amperfy.core.AccountComponents? =
        if (playable.accountId.isEmpty()) {
            accountManager.activeAccount.value?.let { registry.get(it.info) }
        } else {
            registry.getByIdent(playable.accountId)
        }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    // 协程作用域，用于状态保存
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentSong = MutableStateFlow<Playable?>(null)
    val currentSong: StateFlow<Playable?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _playlist = MutableStateFlow<List<Playable>>(emptyList())
    val playlist: StateFlow<List<Playable>> = _playlist.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // 播放上下文信息
    private var currentContextType = PlayContextType.NONE
    private var currentContextId: String? = null
    // 原始上下文名称（iOS: PlayerData.contextName，持久化的是这个原始值）；
    // 对外暴露的 currentContextName 为带 "Mixed Context" 回退的派生流，声明在 contextNextQueue 之后
    private val _currentContextName = MutableStateFlow("")

    /**
     * 播放模式（Batch 2，iOS: PlayerData.playerMode）——音乐 / 播客双队列的选择开关。
     * 两种模式的队列与索引各自独立保存，切换时互不清空（iOS stopButRemainIndex）。
     */
    private val _playerMode = MutableStateFlow(PlayerMode.MUSIC)
    val playerMode: StateFlow<PlayerMode> = _playerMode.asStateFlow()

    /** 是否处于播客模式的即时判定（供类内命令式分支，勿用派生流以免读到滞后值） */
    private val isPodcastMode: Boolean
        get() = _playerMode.value == PlayerMode.PODCAST

    // ========== 三层队列架构 (iOS对应: PlayQueueHandler) ==========

    /**
     * User Queue - 用户手动添加的队列 (iOS: userQueuePlaylist)
     * 优先级最高，紧跟当前播放项
     * 对应iOS: PlayQueueHandler.userQueuePlaylist
     */
    private val _userQueue = MutableStateFlow<List<Playable>>(emptyList())

    /**
     * 对外的 User Queue：**播客模式下恒为空**（iOS PlayerData.isUserQueueVisible:297-305——
     * 播客模式 user 段不可见），内部 [_userQueue] 内容保留，切回音乐即复现
     */
    val userQueue: StateFlow<List<Playable>> = combine(
        _userQueue,
        _playerMode
    ) { queue: List<Playable>, mode: PlayerMode ->
        if (mode == PlayerMode.PODCAST) emptyList() else queue
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Context Queue - 播放上下文队列 (iOS: activeQueue)
     * 由 playPlaylist() 设置，代表当前播放的专辑/艺术家/播放列表
     * 对应iOS: PlayQueueHandler.activeQueue
     */
    private val _contextQueue = MutableStateFlow<List<Playable>>(emptyList())

    /**
     * 当前播放项在 contextQueue 中的索引
     * -1 表示不在 contextQueue 中（在 userQueue 或单曲播放）
     * 对应iOS: PlayQueueHandler.currentIndex
     */
    private val _currentContextIndex = MutableStateFlow(0)
    // 注：Batch 2 起类内一律经 [activeIndex] 读写「当前索引」——它按播放模式分派到
    // 音乐侧 _currentContextIndex 或播客侧 _currentPodcastIndex；直写 _currentContextIndex
    // 仅限明确只作用于音乐侧的场景（setShuffle / playPlaylist 装载 / 清队列 / 恢复）

    /**
     * 标记当前播放的歌曲来源
     */
    private enum class PlaySource {
        CONTEXT,   // 来自 contextQueue (对应iOS: 从activeQueue播放)
        USER,      // 来自 userQueue (对应iOS: 从userQueuePlaylist播放)
        SINGLE     // 单曲播放 (对应iOS: 播放单个项目)
    }
    private var currentPlaySource = PlaySource.SINGLE

    // ========== Batch 2：Shuffle 副本 / 播客独立队列 / Repeat 与 Shuffle 开关 ==========

    /**
     * 打乱后的上下文队列副本（iOS: PlayerData.shuffledContextPlaylist）。
     * 与 [_contextQueue] 元素相同、顺序不同；**[_contextQueue] 永不因 shuffle 改动**，
     * 关闭 shuffle 时靠它还原原始顺序与索引。
     */
    private val _shuffledContextQueue = MutableStateFlow<List<Playable>>(emptyList())

    /** 播客独立队列（iOS: PlayerData.podcastPlaylist），与音乐上下文队列完全并列 */
    private val _podcastQueue = MutableStateFlow<List<Playable>>(emptyList())

    /**
     * 播客队列条目数（iOS: PlayerData.podcastItemCount，PlayerData.swift:174-176）——
     * 播放器底排 playerMode 按钮的显隐依据
     */
    val podcastQueueCount: StateFlow<Int> = _podcastQueue
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** 播客队列当前索引（iOS: PlayerMO.podcastIndex），与音乐侧 [_currentContextIndex] 各自独立 */
    private val _currentPodcastIndex = MutableStateFlow(0)

    /** 循环模式内部存值（iOS: PlayerMO.repeatSetting）；对外读取经 [repeatMode] 做播客门控 */
    private val _repeatModeInternal = MutableStateFlow(RepeatMode.OFF)

    /** 随机模式内部存值（iOS: PlayerMO.shuffleSetting）；对外读取经 [isShuffle] 做播客门控 */
    private val _isShuffleInternal = MutableStateFlow(false)

    /**
     * 循环模式（iOS: PlayerData.repeatMode，PlayerData.swift:217-224）——
     * **播客模式下恒读 off**，内部存值原样保留，切回音乐即恢复
     */
    val repeatMode: StateFlow<RepeatMode> = combine(
        _repeatModeInternal,
        _playerMode
    ) { mode: RepeatMode, playerMode: PlayerMode ->
        if (playerMode == PlayerMode.PODCAST) RepeatMode.OFF else mode
    }.stateIn(scope, SharingStarted.Eagerly, RepeatMode.OFF)

    /**
     * 随机模式（iOS: PlayerData.isShuffle，PlayerData.swift:185-192）——
     * **播客模式下恒读 false**，内部存值原样保留
     */
    val isShuffle: StateFlow<Boolean> = combine(
        _isShuffleInternal,
        _playerMode
    ) { shuffle: Boolean, playerMode: PlayerMode ->
        if (playerMode == PlayerMode.PODCAST) false else shuffle
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * [repeatMode] 的**即时**版本（同样做播客门控）。命令式路径必须读它——
     * [repeatMode] 由 combine 协程异步更新，「刚 set 完就读」会拿到上一帧的值。
     */
    private val effectiveRepeatMode: RepeatMode
        get() = if (isPodcastMode) RepeatMode.OFF else _repeatModeInternal.value

    /** [isShuffle] 的**即时**版本（同上，命令式路径专用） */
    private val effectiveIsShuffle: Boolean
        get() = if (isPodcastMode) false else _isShuffleInternal.value

    /**
     * **生效上下文队列的唯一读取口**（iOS: PlayerData.activeQueue）：
     * 播客模式 → 播客队列；音乐模式 → shuffle 开则打乱副本、否则原始队列。
     *
     * 命令式路径（rebuildPlaylist / playNext / playPrevious / removeFromQueue / 队列重排…）
     * 一律读本属性，**禁止直读 [_contextQueue]**——直读会在 shuffle 开启或播客模式下拿错队列。
     * 用即时 getter 而非派生 StateFlow：后者由 combine 协程异步更新，
     * 「写队列 → 立刻读」会读到上一帧的滞后值。
     */
    private val activeContextQueue: List<Playable>
        get() = when {
            isPodcastMode -> _podcastQueue.value
            _isShuffleInternal.value -> _shuffledContextQueue.value
            else -> _contextQueue.value
        }

    /** [activeContextQueue] 的响应式版本，仅供 UI 派生流使用（滞后一帧对 UI 无影响） */
    private val activeContextQueueFlow: StateFlow<List<Playable>> = combine(
        _playerMode,
        _isShuffleInternal,
        _contextQueue,
        _shuffledContextQueue,
        _podcastQueue
    ) { mode: PlayerMode, shuffle: Boolean, ctx: List<Playable>,
        shuffled: List<Playable>, podcast: List<Playable> ->
        when {
            mode == PlayerMode.PODCAST -> podcast
            shuffle -> shuffled
            else -> ctx
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 生效索引（iOS: PlayerData.currentIndex）：音乐模式读写 [_currentContextIndex]，
     * 播客模式读写 [_currentPodcastIndex]
     */
    private var activeIndex: Int
        get() = if (isPodcastMode) _currentPodcastIndex.value else _currentContextIndex.value
        set(value) {
            if (isPodcastMode) _currentPodcastIndex.value = value else _currentContextIndex.value = value
        }

    /** [activeIndex] 的响应式版本，仅供 UI 派生流使用 */
    private val activeIndexFlow: StateFlow<Int> = combine(
        _playerMode,
        _currentContextIndex,
        _currentPodcastIndex
    ) { mode: PlayerMode, musicIndex: Int, podcastIndex: Int ->
        if (mode == PlayerMode.PODCAST) podcastIndex else musicIndex
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * 生效播放来源（iOS: PlayerData.isUserQueuePlaying 在播客模式恒 false，
     * 即播客侧永远按「从活动队列播放」处理）
     */
    private val effectivePlaySource: PlaySource
        get() = if (isPodcastMode) PlaySource.CONTEXT else currentPlaySource

    /**
     * 生效 User Queue（iOS: PlayerData.isUserQueueVisible 在播客模式恒 false，
     * 故 userQueueCount 亦为 0——播客模式下 user 队列既不显示也不参与播放推进，但**内容保留**）
     */
    private val activeUserQueue: List<Playable>
        get() = if (isPodcastMode) emptyList() else _userQueue.value

    /**
     * Previous Queue - 已播放的历史 (iOS: prevQueueCount)
     * 派生自 contextQueue[0 until currentContextIndex]
     * 对应iOS: PlayQueueHandler.prevQueueCount
     *
     * iOS实现：Previous队列是从activeQueue动态计算的，而不是单独存储
     * 当 isUserQueuePlaying=true 且 currentIndex=0 时，previous包含1首歌（activeQueue第一首）
     * 当 isUserQueuePlaying=false 时，previous = activeQueue[0 until currentIndex]
     */
    val prevQueue: StateFlow<List<Playable>> = combine(
        activeContextQueueFlow,   // Batch 2：读生效队列（shuffle 副本 / 播客队列）
        activeIndexFlow           // 修复：添加索引作为依赖
    ) { contextQ: List<Playable>, ctxIndex: Int ->
        val result = computePrevQueue(contextQ, ctxIndex, effectivePlaySource)
        android.util.Log.d("PlayerManager", "prevQueue recalculated: size=${result.size}, playSource=${effectivePlaySource}, activeIndex=$ctxIndex, activeQueueSize=${contextQ.size}")
        result
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    // Eagerly：打开全屏播放器 COMPACT 视图时首帧就需要真实的 prev 段大小来初始定位到
    // Currently Playing（WhileSubscribed 会导致首帧读到空列表初值，打开后可见跳动）；
    // combine 仅在队列变化时重算，常驻成本可忽略

    /**
     * Next Queue - 即将播放的队列 (iOS: nextQueueCount)
     * 合并规则: userQueue + contextQueue 剩余部分
     * 注意：这个是内部使用的合并队列，UI中应该分别显示 userQueue 和 contextNextQueue
     */
    val nextQueue: StateFlow<List<Playable>> = combine(
        userQueue,                // Batch 2：播客模式恒空的门控版
        activeContextQueueFlow,
        activeIndexFlow
    ) { userQ: List<Playable>, contextQ: List<Playable>, ctxIndex: Int ->
        // Batch 3：起始索引统一走 computeNextQueueStart（与 movePlayable 的换算同源）
        userQ + contextQ.drop(computeNextQueueStart(ctxIndex, effectivePlaySource))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList()) // 优化：只在有订阅者时计算，停止订阅5秒后自动停止

    /**
     * Context Next Queue - contextQueue 的剩余部分 (iOS: nextQueueCount)
     * 对应iOS: player.nextQueueCount - 只包含 contextQueue 的剩余部分，不包括 userQueue
     * UI中的 "Next From" section 应该显示这个队列
     */
    val contextNextQueue: StateFlow<List<Playable>> = combine(
        activeContextQueueFlow,   // Batch 2：读生效队列
        activeIndexFlow
    ) { contextQ: List<Playable>, ctxIndex: Int ->
        // Batch 3：同 nextQueue，起始索引走 computeNextQueueStart
        contextQ.drop(computeNextQueueStart(ctxIndex, effectivePlaySource))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList()) // 优化：只在有订阅者时计算，停止订阅5秒后自动停止

    /**
     * 上下文名称（"Next From" 副标题）
     * 对应 iOS: PlayerFacade.contextName（PlayerFacade.swift:327-335）
     *
     * iOS 语义：play(context:) 设置名称；insert/append/clearContextQueue 将名称清空；
     * 名称为空时，若上下文队列仍有内容（prev/next 非空，或正在播放上下文歌曲）则显示
     * "Mixed Context"，完全为空时显示空字符串。
     *
     * Batch 2：**播客模式恒为 "Podcasts"**（iOS PlayerData.contextName:340-347）
     */
    val currentContextName: StateFlow<String> = combine(
        _currentContextName,
        prevQueue,
        contextNextQueue,
        _currentSong,
        _playerMode
    ) { name: String, prev: List<Playable>, next: List<Playable>, current: Playable?, mode: PlayerMode ->
        when {
            mode == PlayerMode.PODCAST -> PODCAST_CONTEXT_NAME
            name.isNotEmpty() -> name
            prev.isEmpty() && next.isEmpty() &&
                (current == null || currentPlaySource == PlaySource.USER) -> ""
            else -> "Mixed Context"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "")

    // 是否已经恢复过状态
    private var hasRestoredState = false

    init {
        initializeController()
        startProgressTracking()
        startScrobbleTracking()
    }

    /**
     * Scrobble 跟踪（Phase 4.7；2.1.0 W4 补 resumed 分支）：
     * 歌曲在播放状态下切换时上报开始、暂停时结算累计、同曲恢复播放时续走计时器
     * 对应 iOS: ScrobbleSyncer 实现 MusicPlayable 回调（didStartPlaying/didPause/didStopPlaying）。
     * 只在 isPlaying 时报告开始，避免启动恢复播放状态（restorePlaybackState）被误计为一次播放
     */
    private fun startScrobbleTracking() {
        scope.launch {
            var reportedSongId: String? = null
            var wasPlaying = false
            // W5：scrobbler 按当前曲目所属账户路由；记住本曲的 scrobbler 供 pause/resume/stop 复用
            var activeScrobbler: ScrobbleSyncer? = null
            combine(_currentSong, _isPlaying) { song, playing -> song to playing }
                .collect { (song, playing) ->
                    when {
                        song == null -> {
                            reportedSongId = null
                            activeScrobbler?.onPlaybackStopped()
                            activeScrobbler = null
                        }
                        playing && song.id != reportedSongId -> {
                            reportedSongId = song.id
                            val next = componentsFor(song)?.scrobbleSyncer
                            // 换账户则先结算旧账户 scrobbler
                            if (next !== activeScrobbler) activeScrobbler?.onPlaybackStopped()
                            activeScrobbler = next
                            activeScrobbler?.onSongStarted(song)
                        }
                        // 同曲 isPlaying false→true：恢复播放（状态机 resumed 分支，
                        // 累计已达标则立即标记，否则以剩余时长重启定时器）
                        playing && !wasPlaying && song.id == reportedSongId -> {
                            activeScrobbler?.onPlaybackResumed()
                        }
                        !playing && song.id == reportedSongId -> {
                            activeScrobbler?.onPlaybackPaused()
                        }
                    }
                    wasPlaying = playing
                }
        }
    }
    
    private fun initializeController() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(playerListener)

            // 控制器准备好后，恢复播放状态与持久化的播放速率
            restorePlaybackState()
            applyPersistedPlaybackRate()
        }, MoreExecutors.directExecutor())
    }

    // 用于追踪是否已经预加载了下一首
    private var hasPreloadedNext = false

    // ========== 单曲播放进度保存/恢复 (W4, iOS 2.1.0: isRememberSongPlaybackProgress) ==========

    /** 周期快照写单曲进度的节流间隔：30s 一次，避免进度追踪协程每秒写库 */
    private val songProgressSaveIntervalMs = 30_000L

    /** 上次周期写单曲进度的墙钟时间戳 */
    private var lastSongProgressSaveMs = 0L

    /**
     * 给「离场」歌曲写单曲播放进度。
     * 写入规则（5s..(时长-5s) 区间内才存，否则清零——听完或刚开头不记）
     * 由 PlaybackStateManager.saveSongProgress 统一实现。
     * 电台不适用；歌曲在开关关闭时完全不写（避免无谓的写放大）。
     *
     * Batch 2 反转播客短路（iOS AudioPlayer.seekToLastStoppedPlayTime:256-267——
     * `playable.isPodcastEpisode || ((isSong || 出错) && isPlayerSongPlaybackResumeEnabled)`）：
     * **播客单集无条件记忆进度、不看 resume 开关**；歌曲仍受开关门控。
     */
    private fun saveSongProgressFor(song: Playable?, positionMs: Long) {
        if (song == null) return
        if (song.isRadio) return
        if (!song.isPodcastEpisode && !settingsManager.isRememberSongPlaybackProgress.value) return
        playbackStateManager.saveSongProgress(
            songId = song.id,
            accountId = song.accountId,
            progressMs = positionMs,
            durationMs = song.duration * 1000L,
            // Batch 4：单集进度落 podcast_episode_local_state（此前写 song 表被静默丢弃）
            isPodcastEpisode = song.isPodcastEpisode
        )
    }

    /**
     * 装载新曲后按需恢复单曲播放进度。
     * 恢复入口收在「主动切到某曲」的装载路径单点（playSong/playPlaylist/
     * playDirectFromUserQueue/playDirectFromContext，均在 createMediaItem 装载之后调用）：
     * - App 重启的全局恢复（restorePlaybackState）直接以持久化播放状态的 playProgress
     *   调 setMediaItems 定位，不经过本方法——两条恢复路径互不触发、不打架
     * - Media3 自然切歌（AUTO transition）也不经过本方法，下一曲从 0 开始
     *
     * Batch 2：播客单集无条件恢复（同 [saveSongProgressFor] 的 iOS 依据）
     */
    private fun maybeRestoreSongProgress(song: Playable) {
        if (song.isRadio) return
        if (!song.isPodcastEpisode && !settingsManager.isRememberSongPlaybackProgress.value) return
        scope.launch {
            try {
                val progress = playbackStateManager
                    .getSongProgress(song.id, song.accountId, song.isPodcastEpisode)
                    ?: return@launch
                // 异步查询期间可能已切走，仅当仍是当前曲时 seek
                if (progress > 0 && _currentSong.value?.id == song.id) {
                    android.util.Log.d("PlayerManager", "Restore song progress: ${song.title} -> ${progress}ms")
                    seekTo(progress)
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerManager", "Failed to restore song progress", e)
            }
        }
    }

    /**
     * 启动进度追踪协程，定期保存播放进度
     * iOS: AudioPlayer.didElapsedTimeChange()
     */
    private fun startProgressTracking() {
        scope.launch {
            while (true) {
                delay(1000) // 每秒更新一次
                val position = getCurrentPosition()
                val duration = getDuration()
                _currentPosition.value = position

                // 如果正在播放，保存进度
                if (_isPlaying.value && duration > 0) {
                    playbackStateManager.saveProgress(
                        playProgress = position,
                        playDuration = duration,
                        wasPlaying = _isPlaying.value
                    )

                    // 单曲进度周期快照（W4）：30s 节流，避免每秒写库
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastSongProgressSaveMs >= songProgressSaveIntervalMs) {
                        lastSongProgressSaveMs = nowMs
                        saveSongProgressFor(_currentSong.value, position)
                    }

                    // 预加载下一首歌曲的自动缓存
                    // 对应iOS: BackendAudioPlayer.checkForPreloadNextPlayerItem()
                    // 当剩余时间小于10秒时，触发下一首的自动下载
                    checkForPreloadNextItem(position, duration)
                }
            }
        }
    }

    /**
     * 检查是否需要预加载下一首歌曲
     * 对应iOS: BackendAudioPlayer.checkForPreloadNextPlayerItem()
     * 当剩余时间<10秒时，触发下一首的自动缓存下载
     */
    private fun checkForPreloadNextItem(position: Long, duration: Long) {
        if (!settingsManager.isPlayerAutoCachePlayedItems.value) return
        // Repeat Single 时不存在「下一首」——iOS 的 nextPlayablePreloadCB 在 .single 下直接返回 nil
        // （AudioPlayer.swift:80-85），故预下载整体抑制
        if (effectiveRepeatMode == RepeatMode.SINGLE) return

        val remainingTime = (duration - position) / 1000 // 转换为秒

        // 当剩余时间小于10秒且还未预加载时
        if (remainingTime in 1..10 && !hasPreloadedNext) {
            val currentIndex = _currentIndex.value
            val playlist = _playlist.value

            // 检查是否有下一首
            if (currentIndex + 1 < playlist.size) {
                val nextSong = playlist[currentIndex + 1]

                // 如果下一首未缓存，触发下载（仅电台不缓存；Batch 4 起播客单集与歌曲同管线，
                // 对齐 iOS PlayerDownloadPreparationHandler.swift:55,67 只判 !playable.isRadio）
                if (!nextSong.isRadio && !nextSong.isDownloaded) {
                    hasPreloadedNext = true
                    android.util.Log.d("PlayerManager", "Preloading next song: ${nextSong.title} (remaining: ${remainingTime}s)")

                    scope.launch {
                        try {
                            componentsFor(nextSong)?.downloader?.downloadPlayable(nextSong)
                        } catch (e: Exception) {
                            android.util.Log.e("PlayerManager", "Failed to preload next song", e)
                        }
                    }
                }
            }
        }

        // 重置预加载标志（当播放新歌曲时）
        if (remainingTime > 15) {
            hasPreloadedNext = false
        }
    }

    /**
     * 恢复播放状态（扩展版，支持队列恢复）
     * iOS: AmperfyKit.createPlayer() -> getPlayerData() -> AudioPlayer.seekToLastStoppedPlayTime()
     */
    private fun restorePlaybackState() {
        if (hasRestoredState) return

        scope.launch {
            try {
                val savedState = playbackStateManager.getSavedState()
                if (savedState != null && savedState.playlist.isNotEmpty()) {
                    hasRestoredState = true

                    android.util.Log.d("PlayerManager", "Restoring playback state: playlist=${savedState.playlist.size}, userQueue=${savedState.userQueue.size}, contextQueue=${savedState.contextQueue.size}")

                    // 恢复队列数据（Batch 2：五条队列 + 双索引全量还原）
                    _userQueue.value = savedState.userQueue
                    _contextQueue.value = savedState.contextQueue
                    _shuffledContextQueue.value = savedState.shuffledContextQueue
                    _podcastQueue.value = savedState.podcastQueue
                    _currentContextIndex.value = savedState.currentContextIndex
                    _currentPodcastIndex.value = savedState.podcastIndex

                    // 恢复播放来源
                    currentPlaySource = when (savedState.playSource) {
                        "CONTEXT" -> PlaySource.CONTEXT
                        "USER" -> PlaySource.USER
                        else -> PlaySource.SINGLE
                    }

                    // 恢复播放列表
                    _playlist.value = savedState.playlist
                    _currentIndex.value = savedState.currentIndex

                    // 恢复上下文信息
                    currentContextType = savedState.contextType
                    currentContextId = savedState.contextId
                    _currentContextName.value = savedState.contextName
                    _playerMode.value = savedState.playerMode

                    // 恢复 Repeat / Shuffle 开关（Batch 2）；Single 需同步给 Media3 原生单曲循环
                    _repeatModeInternal.value = savedState.repeatMode
                    _isShuffleInternal.value = savedState.isShuffle
                    applyRepeatModeToController()

                    // 显示模式已迁至 SettingsManager（设备级 player_display_style，
                    // 对应 iOS settings.user.playerDisplayStyle）；这里仅做**一次性迁移**：
                    // 新键从未落盘（老装机升级）才回读 Room 旧列并立即固化，之后不再覆盖用户偏好
                    if (!settingsManager.isPlayerDisplayStyleSet()) {
                        _displayMode.value = savedState.displayMode
                        settingsManager.setPlayerDisplayStyle(savedState.displayMode)
                    }

                    // 设置当前歌曲
                    if (savedState.currentIndex < savedState.playlist.size) {
                        val song = savedState.playlist[savedState.currentIndex]
                        _currentSong.value = song

                        // 创建媒体项
                        val mediaItems = savedState.playlist.map { createMediaItem(it) }
                        controller?.setMediaItems(mediaItems, savedState.currentIndex, savedState.playProgress)
                        controller?.prepare()

                        // 不自动播放，只恢复到停止状态（iOS行为）
                        // iOS: 恢复后播放器处于暂停状态，但记住了播放进度

                        android.util.Log.d("PlayerManager", "Restored to: ${song.title}, progress=${savedState.playProgress}ms, playSource=${currentPlaySource}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerManager", "Failed to restore playback state", e)
            }
        }
    }
    
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            _playbackState.value = if (isPlaying) PlaybackState.Playing else PlaybackState.Paused

            // 播放/暂停状态改变时保存
            saveCurrentState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> _playbackState.value = PlaybackState.Idle
                Player.STATE_BUFFERING -> _playbackState.value = PlaybackState.Buffering
                Player.STATE_READY -> _playbackState.value = if (_isPlaying.value) PlaybackState.Playing else PlaybackState.Paused
                Player.STATE_ENDED -> {
                    _playbackState.value = PlaybackState.Ended
                    if (_isPauseAfterCurrentSong.value) {
                        // 自然播放到底：清除「播完当前曲暂停」标志；此时不做 Repeat 绕回
                        // （对齐 iOS didItemFinishedPlaying 的 isShouldPauseAfterFinishedPlaying 优先分支）
                        _isPauseAfterCurrentSong.value = false
                    } else {
                        handleRepeatAllOnQueueEnd()
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let {
                val index = controller?.currentMediaItemIndex ?: 0
                _currentIndex.value = index
                if (index < _playlist.value.size) {
                    val newSong = _playlist.value[index]

                    // 上一首离场进度（W4）：controller 此刻已指向新曲，取进度追踪协程
                    // 最后一次 tick 的位置（≤1s 误差）。仅在曲目真正变化时写——
                    // 主动切歌路径（playSong/playDirectFrom*）已提前更新 _currentSong
                    // 并在各自方法内写过离场进度，此处 id 相同会跳过，不重复写
                    val departingSong = _currentSong.value
                    if (departingSong != null && departingSong.id != newSong.id) {
                        saveSongProgressFor(departingSong, _currentPosition.value)
                    }

                    _currentSong.value = newSong

                    // 处理Media3自动播放下一首的情况
                    // 当reason为AUTO_TRANSITION时，需要更新队列状态
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        android.util.Log.d("PlayerManager", "onMediaItemTransition AUTO: index=$index, song=${newSong.title}")

                        // Sleep Timer「End of Song」：上一曲播完即暂停（对应 iOS isShouldPauseAfterFinishedPlaying）
                        if (_isPauseAfterCurrentSong.value) {
                            _isPauseAfterCurrentSong.value = false
                            pause()
                        }

                        // 检测是否从userQueue切换到contextQueue
                        // playlist结构: [currentSong] + userQueue + contextQueue remainder
                        // index=0是当前歌曲，index=1开始是队列
                        // 如果index > 0，说明Media3自动播放了下一首
                        if (index > 0) {
                            // 计算这首歌在队列中的位置
                            val queueIndex = index - 1  // 减去currentSong的位置

                            // 检查是否超过了 userQueue 的范围（播客模式 activeUserQueue 恒空，
                            // 故必定走上下文分支——与 iOS 播客侧无 user 队列一致）
                            if (queueIndex >= activeUserQueue.size) {
                                // 已经超出userQueue，说明现在播放的是生效上下文队列
                                android.util.Log.d("PlayerManager", "  AUTO transition from userQueue to contextQueue")

                                // 从userQueue切换到上下文队列
                                // 需要：
                                // 1. 清空userQueue（因为已经播放完了）
                                // 2. 递增 activeIndex（按模式落到音乐/播客索引）
                                // 3. 切换currentPlaySource

                                if (!isPodcastMode) _userQueue.value = emptyList()
                                activeIndex += 1
                                currentPlaySource = PlaySource.CONTEXT

                                android.util.Log.d("PlayerManager", "  Updated: activeIndex=$activeIndex, currentPlaySource=$currentPlaySource")
                                android.util.Log.d("PlayerManager", "  contextName=${_currentContextName.value}")

                                // 重建playlist以更新UI（保留正在播放的曲目：自然切歌瞬间
                                // 整表 setMediaItems 会重新 prepare 刚开始播放的流造成卡顿）
                                rebuildPlaylist(preserveCurrentItem = true)
                            } else {
                                // 仍在userQueue中，移除已播放的歌曲
                                android.util.Log.d("PlayerManager", "  AUTO transition within userQueue, removing played songs")
                                _userQueue.value = _userQueue.value.drop(queueIndex + 1)
                                currentPlaySource = PlaySource.USER
                            }
                        }
                    }

                    // 切换歌曲时触发自动缓存（包括 Next/Previous 按钮切换）
                    // 对应iOS: BackendAudioPlayer.handleRequest() 中的自动缓存逻辑
                    triggerAutoCacheForCurrentSong(newSong, index)
                }

                // 切换歌曲时保存状态
                saveCurrentState()

                // 重置预加载标志
                hasPreloadedNext = false
            }
        }
    }
    
    fun play() {
        // 如果没有当前歌曲，不做任何操作
        if (_currentSong.value == null) {
            android.util.Log.d("PlayerManager", "play: no current song, nothing to play")
            return
        }

        controller?.play()

        // 从暂停恢复播放时，触发当前歌曲和后续歌曲的自动缓存下载
        // 对应iOS: 点击播放按钮时触发下载
        val currentIndex = _currentIndex.value
        val currentSong = _currentSong.value
        if (currentSong != null && settingsManager.isPlayerAutoCachePlayedItems.value) {
            triggerAutoCacheForCurrentSong(currentSong, currentIndex)
        }
    }

    fun pause() {
        // 暂停即写当前曲单曲进度（W4）：显式暂停立即落一次，不等周期快照的 30s 节流
        // （位置在 controller.pause() 前后均可，取值相同；先取再暂停语义更直观）
        saveSongProgressFor(_currentSong.value, getCurrentPosition())
        controller?.pause()
    }

    fun playPause() {
        // 如果没有当前歌曲，不做任何操作
        if (_currentSong.value == null) {
            android.util.Log.d("PlayerManager", "playPause: no current song, nothing to do")
            return
        }

        if (_isPlaying.value) {
            pause()
        } else {
            play()
        }
    }

    fun skipToNext() {
        // 如果没有当前歌曲且队列为空，不做任何操作
        if (_currentSong.value == null && activeUserQueue.isEmpty() && activeContextQueue.isEmpty()) {
            android.util.Log.d("PlayerManager", "skipToNext: no song and queue empty, nothing to do")
            return
        }
        // iOS: PlayerUIHandler.nextButtonPushed() -> player.playNext()
        playNext()
    }

    fun skipToPrevious() {
        // 如果没有当前歌曲，不做任何操作
        if (_currentSong.value == null) {
            android.util.Log.d("PlayerManager", "skipToPrevious: no current song, nothing to do")
            return
        }
        // iOS: PlayerUIHandler.previousButtonPushed() -> player.playPreviousOrReplay()
        playPrevious()
    }

    /**
     * Play next song in queue - 支持三层队列优先级
     * 对应iOS: AudioPlayer.swift line 202-220
     *
     * iOS逻辑 (line 210-220):
     * private var nextPlayerIndex: PlayerIndex? {
     *   if queueHandler.userQueueCount > 0 {
     *     return PlayerIndex(queueType: .user, index: 0)
     *   } else if queueHandler.nextQueueCount > 0 {
     *     return PlayerIndex(queueType: .next, index: 0)
     *   } else if playerStatus.repeatMode == .all, queueHandler.prevQueueCount > 0 {
     *     return PlayerIndex(queueType: .prev, index: 0)
     *   } else {
     *     return nil
     *   }
     * }
     *
     * 播放顺序优先级:
     * 1. userQueue[0] (最高优先级)
     * 2. contextQueue[currentContextIndex + 1] (无论当前是从哪个队列播放)
     * 3. 停止播放（队列结束）
     */
    fun playNext() {
        android.util.Log.d("PlayerManager", "playNext: mode=${_playerMode.value}, userQueueSize=${activeUserQueue.size}, playSource=${effectivePlaySource}, activeIndex=$activeIndex, activeQueueSize=${activeContextQueue.size}")

        when {
            // 优先级1: User Queue
            // iOS: if queueHandler.userQueueCount > 0（播客模式 userQueueCount 恒 0，故 activeUserQueue 为空）
            activeUserQueue.isNotEmpty() -> {
                val nextSong = activeUserQueue.first()
                android.util.Log.d("PlayerManager", "playNext from userQueue: ${nextSong.title}")

                // 从 userQueue 移除
                _userQueue.value = _userQueue.value.drop(1)

                // 播放
                playDirectFromUserQueue(nextSong)
            }

            // 优先级2: 生效上下文队列的剩余部分
            // iOS: else if queueHandler.nextQueueCount > 0
            // 注意：无论当前是从 USER 还是 CONTEXT 播放，只要队列还有剩余，就继续播放
            hasNextInContext() -> {
                android.util.Log.d("PlayerManager", "playNext: switching from ${effectivePlaySource} to CONTEXT")

                // 当前若在播放 userQueue，activeIndex 仍指向上次在上下文队列的位置，同样递增即可
                activeIndex += 1

                val nextSong = activeContextQueue[activeIndex]
                android.util.Log.d("PlayerManager", "playNext from activeQueue[$activeIndex]: ${nextSong.title}")

                playDirectFromContext(nextSong)
            }

            // 优先级3: Repeat All 绕回队首
            // iOS: else if playerStatus.repeatMode == .all, queueHandler.prevQueueCount > 0
            //      → PlayerIndex(queueType: .prev, index: 0)（AudioPlayer.swift:214-224）
            effectiveRepeatMode == RepeatMode.ALL && currentPrevQueue().isNotEmpty() -> {
                val queue = activeContextQueue
                activeIndex = 0
                android.util.Log.d("PlayerManager", "playNext: repeat ALL wrap to head: ${queue[0].title}")
                playDirectFromContext(queue[0])
            }

            // 优先级4: 队列结束，无歌曲可播放
            // iOS: return nil -> 不做任何操作
            else -> {
                android.util.Log.d("PlayerManager", "playNext: queue empty, nothing to play")
                // 队列为空时不做任何操作，避免无限递归
            }
        }
    }

    /**
     * Play previous song or replay current if at beginning
     * 对应iOS: AudioPlayer.playPreviousOrReplay() + AudioPlayer.playPrevious()
     *
     * iOS逻辑 (AudioPlayer.swift line 182-200):
     * 1. 如果 elapsedTime >= 5秒：重播当前歌曲 (replayCurrentItem)
     * 2. 如果 elapsedTime < 5秒 且 prevQueueCount > 0：播放Previous队列最后一首
     * 3. 否则：重播当前歌曲
     *
     * 注意：iOS没有"3秒内再次点击"的逻辑，每次点击都是独立判断
     */
    fun playPrevious() {
        val currentPosition = getCurrentPosition()
        android.util.Log.d("PlayerManager", "playPrevious: position=$currentPosition, contextSource=$effectivePlaySource, activeIndex=$activeIndex")

        // 如果没有当前歌曲，不做任何操作
        if (_currentSong.value == null) {
            android.util.Log.d("PlayerManager", "playPrevious: no current song, nothing to do")
            return
        }

        // iOS: AudioPlayer.shouldCurrentItemReplayedInsteadOfPrevious()
        // return backendAudioPlayer.elapsedTime >= Self.replayInsteadPlayPreviousTimeInSec (5.0秒)
        val shouldReplay = currentPosition >= 5000 // 5秒阈值，对应iOS的5.0秒

        if (shouldReplay) {
            // iOS: replayCurrentItem() - 重播当前歌曲
            android.util.Log.d("PlayerManager", "playPrevious: position >= 5s, replaying current song")
            seekTo(0)
        } else {
            // iOS: playPrevious() - 播放Previous队列的最后一首
            // if queueHandler.prevQueueCount > 0
            val prev = currentPrevQueue()
            if (prev.isNotEmpty()) {
                // iOS: play(playerIndex: PlayerIndex(queueType: .prev, index: queueHandler.prevQueueCount - 1))
                val prevIndex = prev.size - 1  // prevQueueCount - 1
                val prevSong = prev[prevIndex] // 取最后一首（最近播放的）

                android.util.Log.d("PlayerManager", "playPrevious: playing from prevQueue[$prevIndex]: ${prevSong.title}, prevQueue.size=${prev.size}")

                // iOS: markAndGetPlayableAsPlaying (line 266-271)
                // else if playerIndex.queueType == .prev:
                //   if isUserQueuePlaying { removeItemFromUserQueue(at: 0) }
                //   playable = getPrevQueueItem(at: playerIndex.index)
                //   setCurrentIndex(playerIndex.index)
                //   setUserQueuePlaying(false)
                //
                // 注意：prevQueue 是 contextQueue[0 until currentContextIndex] (正在播放contextQueue时)
                // 或者 contextQueue[0...currentContextIndex] (正在播放userQueue时，包含currentContextIndex)

                // iOS: 如果当前正在播放 userQueue，需要清空它的第一项（因为要切换到 context 了）
                if (currentPlaySource == PlaySource.USER && _userQueue.value.isNotEmpty()) {
                    _userQueue.value = _userQueue.value.drop(1)
                    android.util.Log.d("PlayerManager", "playPrevious: removed current playing item from userQueue")
                }

                // iOS: setCurrentIndex(playerIndex.index) = setCurrentIndex(prevIndex)
                activeIndex = prevIndex
                // iOS: setUserQueuePlaying(false)
                currentPlaySource = PlaySource.CONTEXT

                playDirectFromContext(prevSong)
            } else if (effectiveRepeatMode == RepeatMode.ALL && hasNextInContext()) {
                // iOS AudioPlayer.swift:198-199：
                // else if repeatMode == .all, nextQueueCount > 0 →
                //   play(PlayerIndex(.next, nextQueueCount - 1))，即跳到生效队列末首
                val queue = activeContextQueue
                val lastIndex = queue.size - 1
                android.util.Log.d("PlayerManager", "playPrevious: repeat ALL wrap to tail: ${queue[lastIndex].title}")

                if (currentPlaySource == PlaySource.USER && _userQueue.value.isNotEmpty()) {
                    _userQueue.value = _userQueue.value.drop(1)
                }
                activeIndex = lastIndex
                currentPlaySource = PlaySource.CONTEXT

                playDirectFromContext(queue[lastIndex])
            } else {
                // iOS: 没有Previous队列时，重播当前歌曲
                android.util.Log.d("PlayerManager", "playPrevious: no prevQueue, replaying current song")
                seekTo(0)
            }
        }
    }

    // ========== Batch 2：Repeat 模式（iOS: PlayerUtil.RepeatMode + AudioPlayer 分支）==========

    /**
     * 设置循环模式（iOS: PlayerFacade.setRepeatMode，PlayerFacade.swift:449-452）。
     *
     * Single 直接映射 Media3 原生 [Player.REPEAT_MODE_ONE]——语义恰好等价 iOS 的 `.single`
     * 「只作用于自然播完」：手动 playNext/playPrevious 走整表重建路径（rebuildPlaylist +
     * seekToDefaultPosition），不经 Media3 的自动推进，故不受 REPEAT_MODE_ONE 影响。
     *
     * **All 刻意不映射 [Player.REPEAT_MODE_ALL]**（已知差异）：Media3 的播放列表只是
     * 「当前曲 + 队列尾部」的切片，REPEAT_MODE_ALL 会在该切片内循环而非在整条上下文队列内绕回，
     * 语义不符。All 的绕回由 [handleRepeatAllOnQueueEnd] / [playNext] / [playPrevious] 自行实现，
     * 代价是**通知栏/MediaSession 的 repeat 态只反映 Single**（All 在系统 UI 上显示为 off）。
     */
    fun setRepeatMode(mode: RepeatMode) {
        _repeatModeInternal.value = mode
        applyRepeatModeToController()
        saveCurrentState()
    }

    /** 循环按钮点击：off → all → single → off（iOS: PlayerUIHandler.repeatButtonPushed） */
    fun toggleRepeatMode() {
        setRepeatMode(effectiveRepeatMode.nextMode)
    }

    /** 把当前生效的循环模式同步给 Media3（仅 Single 映射原生循环，见 [setRepeatMode] 说明） */
    private fun applyRepeatModeToController() {
        controller?.repeatMode = if (effectiveRepeatMode == RepeatMode.SINGLE) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    /**
     * 队列自然播完（Media3 STATE_ENDED）时的 Repeat All 绕回，
     * 对齐 iOS AudioPlayer.didItemFinishedPlaying（AudioPlayer.swift:127-143）：
     * - repeat all 且队列里只剩当前一首（prev/user/next 全空）→ replayCurrentItem（原地重播）；
     * - 否则 → playNext()，由其 Repeat All 分支绕回队首。
     *
     * 两条路径都尊重 Manual Playback：iOS replayCurrentItem 经 insertIntoPlayer 传
     * `autoStartPlayback = !isPlaybackStartOnlyOnPlay`，playNext 分支则被 `else if
     * !isPlaybackStartOnlyOnPlay` 整体门控。
     */
    private fun handleRepeatAllOnQueueEnd() {
        if (effectiveRepeatMode != RepeatMode.ALL) return
        val queue = activeContextQueue
        if (queue.isEmpty()) return

        val isOnlyCurrentItem = currentPrevQueue().isEmpty() &&
            activeUserQueue.isEmpty() && !hasNextInContext()
        if (isOnlyCurrentItem) {
            android.util.Log.d("PlayerManager", "repeat ALL: replay current item")
            controller?.seekTo(0)
            if (!settingsManager.isPlaybackStartOnlyOnPlay.value) {
                controller?.play()
            }
        } else if (!settingsManager.isPlaybackStartOnlyOnPlay.value) {
            android.util.Log.d("PlayerManager", "repeat ALL: wrap around to queue head")
            playNext()
        }
    }

    // ========== Batch 2：Shuffle 模式（iOS: PlayerData.setShuffle，PlayerData.swift:194-215）==========

    /**
     * 开/关随机播放。逐条对齐 iOS setShuffle：
     * - 开：重新打乱副本 → 把当前曲搬到副本 index 0 → currentIndex 置 0（Previous 段立即变空）；
     * - 关：在**原始队列**里找回当前曲位置，还原 currentIndex；
     * - 两种情况下 [_contextQueue] 原始顺序都不变，User Queue 完全不动。
     *
     * 切换后只 [rebuildPlaylist] 且 preserveCurrentItem=true——不整表 setMediaItems，
     * 正在播放的流不被打断（见 rebuildPlaylist 注释）。
     */
    fun setShuffle(enabled: Boolean) {
        if (isPodcastMode) return  // iOS: 播客模式 isShuffle 恒 false，无切换语义
        val current = _currentSong.value

        if (enabled) {
            val shuffled = _contextQueue.value.shuffled().toMutableList()
            if (current != null) {
                val indexInShuffled = shuffled.indexOfFirst { it.id == current.id }
                if (indexInShuffled >= 0) {
                    shuffled.add(0, shuffled.removeAt(indexInShuffled))
                    _currentContextIndex.value = 0
                }
            }
            _shuffledContextQueue.value = shuffled
        } else {
            if (current != null) {
                val indexInOriginal = _contextQueue.value.indexOfFirst { it.id == current.id }
                if (indexInOriginal >= 0) {
                    _currentContextIndex.value = indexInOriginal
                }
            }
        }
        _isShuffleInternal.value = enabled

        rebuildPlaylist(preserveCurrentItem = true)
        saveCurrentState()
        android.util.Log.d("PlayerManager", "setShuffle($enabled): activeIndex=$activeIndex, shuffledSize=${_shuffledContextQueue.value.size}")
    }

    /** 随机按钮点击（iOS: PlayerFacade.toggleShuffle，PlayerFacade.swift:429-433） */
    fun toggleShuffle() {
        setShuffle(!effectiveIsShuffle)
    }

    /**
     * 直接播放 userQueue 中的歌曲
     * 对应iOS: 设置 currentPlayable 并更新播放状态
     * iOS: PlayQueueHandler.markAndGetPlayableAsPlaying - 会从userQueue移除当前播放项
     */
    private fun playDirectFromUserQueue(song: Playable) {
        // 上一首离场进度（W4）：主动切歌，_currentSong 更新前捕获旧曲及其实时位置
        saveSongProgressFor(_currentSong.value, getCurrentPosition())

        currentPlaySource = PlaySource.USER
        _currentSong.value = song

        // iOS逻辑：当从userQueue播放时，该歌曲应该从userQueue中移除
        // 对应iOS: PlayQueueHandler.swift line 257-259
        // if isUserQueuePlaying { removeItemFromUserQueue(at: 0) }
        // 这样当前播放的歌曲不会出现在Next in Queue列表中
        _userQueue.value = _userQueue.value.filter { it.id != song.id }

        // 重新构建 _playlist
        rebuildPlaylist()

        // 播放（索引总是0，因为当前歌曲在 _playlist[0]）
        controller?.seekToDefaultPosition(0)
        // Manual Playback（Settings→Player→Manual Playback）：新曲目入播放器只装载不自动开播
        // 对应 iOS AudioPlayer.insertIntoPlayer autoStartPlayback = !isPlaybackStartOnlyOnPlay
        if (!settingsManager.isPlaybackStartOnlyOnPlay.value) {
            controller?.play()
        }

        // 装载完成后按需恢复该曲的记忆进度（W4，开关开且有有效进度才 seek）
        maybeRestoreSongProgress(song)

        saveCurrentState()

        // 触发自动缓存
        triggerAutoCacheForCurrentSong(song, 0)

        // 重置预加载标志
        hasPreloadedNext = false
    }

    /**
     * 直接播放 contextQueue 中的歌曲
     * 对应iOS: 设置 activeQueue 的 currentIndex
     */
    private fun playDirectFromContext(song: Playable) {
        android.util.Log.d("PlayerManager", "playDirectFromContext: ${song.title}, activeIndex=$activeIndex")

        // 上一首离场进度（W4）：主动切歌，_currentSong 更新前捕获旧曲及其实时位置
        saveSongProgressFor(_currentSong.value, getCurrentPosition())

        currentPlaySource = PlaySource.CONTEXT
        _currentSong.value = song

        // 重新构建 _playlist
        rebuildPlaylist()

        // 播放（索引总是0）
        controller?.seekToDefaultPosition(0)
        // Manual Playback（Settings→Player→Manual Playback）：新曲目入播放器只装载不自动开播
        // 对应 iOS AudioPlayer.insertIntoPlayer autoStartPlayback = !isPlaybackStartOnlyOnPlay
        if (!settingsManager.isPlaybackStartOnlyOnPlay.value) {
            controller?.play()
        }

        // 装载完成后按需恢复该曲的记忆进度（W4，开关开且有有效进度才 seek）
        maybeRestoreSongProgress(song)

        saveCurrentState()

        // 触发自动缓存
        triggerAutoCacheForCurrentSong(song, activeIndex)

        // 重置预加载标志
        hasPreloadedNext = false

        android.util.Log.d("PlayerManager", "playDirectFromContext: completed")
    }
    
    fun seekTo(position: Long) {
        controller?.seekTo(position)
        // 乐观更新进度流：进度追踪协程每 1000ms 才刷新一次 _currentPosition
        // （见 startProgressTracking），若不在此立即写入目标位置，seek 后到下一次
        // 轮询之间的窗口内所有消费方（miniplayer 进度条等）仍显示 seek 前的旧值 → 回弹。
        // Media3 MediaController 有 position masking——seekTo 后 getCurrentPosition()
        // 立即返回目标位置，故乐观值与后续轮询天然连续，不会再跳回
        _currentPosition.value = position
    }
    
    /**
     * 播放单曲 - 设置为 SINGLE 模式
     * 对应iOS: 直接播放单个项目
     */
    fun playSong(song: Playable) {
        android.util.Log.d("PlayerManager", "playSong: ${song.title}")

        // 上一首离场进度（W4）：主动切歌，_currentSong 更新前捕获旧曲及其实时位置
        saveSongProgressFor(_currentSong.value, getCurrentPosition())

        // 单曲播放一律走音乐侧（全部调用方均为音乐库入口；播客单集经 play(..., PODCAST) 进播客队列）。
        // 切模式先暂停，对齐 iOS setPlayerModeForContextPlay（PlayerFacade.swift:612-615）
        if (isPodcastMode) {
            controller?.pause()
            _playerMode.value = PlayerMode.MUSIC
        }

        // 先设置状态
        _currentSong.value = song
        currentPlaySource = PlaySource.SINGLE

        // 清空队列（单曲播放不使用队列）；shuffle 副本同步清空并关闭开关
        // （iOS play(context:) 在 music 模式下强制 setShuffle(false)，PlayerFacade.swift:604-607）
        _userQueue.value = emptyList()
        _contextQueue.value = emptyList()
        _shuffledContextQueue.value = emptyList()
        _isShuffleInternal.value = false
        _currentContextIndex.value = 0

        // 更新上下文为单曲播放
        currentContextType = PlayContextType.NONE
        currentContextId = song.id
        _currentContextName.value = song.title

        // 重新构建 _playlist（只包含当前歌曲）
        _playlist.value = listOf(song)
        _currentIndex.value = 0

        val mediaItem = createMediaItem(song)
        controller?.setMediaItem(mediaItem)
        controller?.prepare()
        // Manual Playback（Settings→Player→Manual Playback）：新曲目入播放器只装载不自动开播
        // 对应 iOS AudioPlayer.insertIntoPlayer autoStartPlayback = !isPlaybackStartOnlyOnPlay
        if (!settingsManager.isPlaybackStartOnlyOnPlay.value) {
            controller?.play()
        }

        // 装载完成后按需恢复该曲的记忆进度（W4，开关开且有有效进度才 seek）
        maybeRestoreSongProgress(song)

        // 保存状态
        saveCurrentState()

        // 触发自动缓存
        triggerAutoCacheForCurrentSong(song, 0)

        // 重置预加载标志
        hasPreloadedNext = false
    }

    /**
     * 播放列表（修改版）- 设置为 contextQueue
     * 对应iOS: PlayQueueHandler.play(context:)
     *
     * 与原有实现的差异：
     * - 将播放列表设置为 contextQueue（而不是直接设置 _playlist）
     * - 设置 currentPlaySource 为 CONTEXT
     * - 使用 rebuildPlaylist() 合并队列
     */
    fun playPlaylist(
        songs: List<Playable>,
        startIndex: Int = 0,
        contextType: PlayContextType = PlayContextType.NONE,
        contextId: String? = null,
        contextName: String = ""
    ) {
        android.util.Log.d("PlayerManager", "playPlaylist: ${songs.size} songs, startIndex=$startIndex, contextType=$contextType")

        // 上一首离场进度（W4）：主动切上下文，_currentSong 更新前捕获旧曲及其实时位置
        saveSongProgressFor(_currentSong.value, getCurrentPosition())

        // Batch 2：按上下文类型切播放模式（iOS PlayerFacade.setPlayerModeForContextPlay:612-615
        // ——先 pause 再改 mode）。**另一侧队列原样保留**，不再像旧实现那样让播客覆盖音乐上下文队列
        val targetMode = if (contextType == PlayContextType.PODCAST) PlayerMode.PODCAST else PlayerMode.MUSIC
        if (_playerMode.value != targetMode) {
            controller?.pause()
            _playerMode.value = targetMode
        }

        if (targetMode == PlayerMode.PODCAST) {
            // iOS clearActiveQueue + appendActiveQueue 在播客模式作用于 podcastPlaylist
            _podcastQueue.value = songs
            _currentPodcastIndex.value = startIndex
            // iOS: contextName 只在 music 上下文设置（播客侧对外恒显示 "Podcasts"）
        } else {
            // iOS play(context:)：music 模式强制关闭 shuffle（PlayerFacade.swift:604-607）
            _isShuffleInternal.value = false
            // iOS clearActiveQueue 清 context + shuffled 两条，appendActiveQueue 同样双写
            _contextQueue.value = songs
            _shuffledContextQueue.value = songs
            _currentContextIndex.value = startIndex
            _currentContextName.value = contextName
        }
        currentPlaySource = PlaySource.CONTEXT

        // 设置当前歌曲
        if (startIndex < songs.size) {
            _currentSong.value = songs[startIndex]
        }

        // 更新播放上下文
        currentContextType = contextType
        currentContextId = contextId

        // 重新构建 _playlist 并播放
        rebuildPlaylist()
        // 新上下文从头开始播放（对齐 playDirectFromContext / iOS play(context:)）。
        // rebuildPlaylist 会把旧曲目的播放位置带给新歌（电台为直播流、position 极大，
        // 若不归零会导致切换后的歌曲被 seek 到末尾而立即跳到下一首）
        controller?.seekToDefaultPosition(0)
        // Manual Playback（Settings→Player→Manual Playback）：新曲目入播放器只装载不自动开播
        // 对应 iOS AudioPlayer.insertIntoPlayer autoStartPlayback = !isPlaybackStartOnlyOnPlay
        if (!settingsManager.isPlaybackStartOnlyOnPlay.value) {
            controller?.play()
        }

        // 装载完成后按需恢复起始曲的记忆进度（W4，开关开且有有效进度才 seek）
        if (startIndex < songs.size) {
            maybeRestoreSongProgress(songs[startIndex])
        }

        // 保存状态
        saveCurrentState()

        // 触发自动缓存
        if (startIndex < songs.size) {
            triggerAutoCacheForCurrentSong(songs[startIndex], startIndex)
        }

        // 重置预加载标志
        hasPreloadedNext = false
    }
    
    fun addToQueue(song: Playable) {
        val mediaItem = createMediaItem(song)
        controller?.addMediaItem(mediaItem)
        _playlist.value = _playlist.value + song
    }
    
    fun addToQueue(songs: List<Playable>) {
        val mediaItems = songs.map { createMediaItem(it) }
        controller?.addMediaItems(mediaItems)
        _playlist.value = _playlist.value + songs
    }
    
    fun clearQueue() {
        controller?.clearMediaItems()
        _playlist.value = emptyList()
        _currentSong.value = null
        _currentIndex.value = 0
    }

    /**
     * 完全清空播放器 - 停止播放并清空所有队列
     * 对应iOS: PlayerFacade.clearQueues()
     * iOS实现:
     * 1. musicPlayer.stop()
     * 2. queueHandler.clearActiveQueue() (清空contextQueue)
     * 3. queueHandler.clearUserQueue()
     * 4. musicPlayer.notifyPlayerStopped()
     */
    fun clearAll() {
        android.util.Log.d("PlayerManager", "clearAll - clearing player (mode=${_playerMode.value})")

        // 1. 停止播放
        controller?.stop()
        controller?.clearMediaItems()

        // 2. 清空所有状态
        _playlist.value = emptyList()
        _currentSong.value = null
        _currentIndex.value = 0
        _isPlaying.value = false

        // 3. 按模式清队列（iOS PlayerFacade.clearQueues:586-596——
        //    clearActiveQueue 只清「活动」那一侧；随后仅 music 模式追加 clearUserQueue，
        //    播客模式 break，User Queue 与音乐上下文队列一并保留）
        if (isPodcastMode) {
            _podcastQueue.value = emptyList()
            _currentPodcastIndex.value = 0
        } else {
            _contextQueue.value = emptyList()
            _shuffledContextQueue.value = emptyList()
            _userQueue.value = emptyList()
            _currentContextIndex.value = 0
            _currentContextName.value = ""
        }
        // prevQueue 是 derived StateFlow，会自动更新为空
        currentPlaySource = PlaySource.SINGLE

        // 4. 保存状态
        saveCurrentState()

        android.util.Log.d("PlayerManager", "clearAll completed")
    }

    fun getCurrentPosition(): Long {
        return controller?.currentPosition ?: 0L
    }
    
    fun getDuration(): Long {
        return controller?.duration ?: 0L
    }

    // ========== 播放速率 (Phase 4.1) ==========
    // 对应 iOS: PlayerControlView.createPlaybackRateMenu -> player.setPlaybackRate

    private val _playbackRate = MutableStateFlow(settingsManager.playbackRate.value)
    val playbackRate: StateFlow<Float> = _playbackRate.asStateFlow()

    /**
     * 设置播放速率（0.5x–2.0x）并持久化；下次启动经 restorePlaybackState 后自动恢复
     */
    fun setPlaybackRate(rate: Float) {
        _playbackRate.value = rate
        controller?.setPlaybackSpeed(rate)
        settingsManager.setPlaybackRate(rate)
    }

    /** 控制器就绪后应用持久化的播放速率（initializeController 回调中调用） */
    private fun applyPersistedPlaybackRate() {
        val rate = settingsManager.playbackRate.value
        _playbackRate.value = rate
        if (rate != 1.0f) {
            controller?.setPlaybackSpeed(rate)
        }
    }

    // ========== 睡眠定时器 (Phase 4.2) ==========
    // 对应 iOS: AppDelegate.sleepTimer（Timer 到时暂停播放）

    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    /** 剩余秒数；0 = 定时器未激活 */
    private val _sleepTimerRemainingSeconds = MutableStateFlow(0)
    val sleepTimerRemainingSeconds: StateFlow<Int> = _sleepTimerRemainingSeconds.asStateFlow()

    /**
     * 启动睡眠定时器：到时暂停播放并自动清除
     * 重复启动会重置为新时长（与 iOS 一致）
     */
    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingSeconds.value = minutes * 60
        sleepTimerJob = scope.launch {
            while (_sleepTimerRemainingSeconds.value > 0) {
                delay(1000)
                _sleepTimerRemainingSeconds.value--
            }
            pause()
            sleepTimerJob = null
        }
    }

    /** 取消睡眠定时器 */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingSeconds.value = 0
    }

    /**
     * 播完当前曲后暂停（Sleep Timer 的 "End of Song" 选项）
     * 对应 iOS: player.isShouldPauseAfterFinishedPlaying（PlayerFacade.swift:379-382）
     */
    private val _isPauseAfterCurrentSong = MutableStateFlow(false)
    val isPauseAfterCurrentSong: StateFlow<Boolean> = _isPauseAfterCurrentSong.asStateFlow()

    fun setPauseAfterCurrentSong(enabled: Boolean) {
        _isPauseAfterCurrentSong.value = enabled
    }

    // 播放器显示模式：初值取设备级设置（对应 iOS settings.user.playerDisplayStyle）
    private val _displayMode = MutableStateFlow(settingsManager.playerDisplayStyle.value)
    val displayMode: StateFlow<String> = _displayMode.asStateFlow()

    /**
     * 设置显示模式
     * iOS: PopupPlayerVC.displayStyle（写回 settings.user.playerDisplayStyle）
     *
     * 仍调用 [saveCurrentState] 是为保持 Room `display_mode` 列的透传现状
     * （本批零 schema 变更，该列已退化为冗余）
     */
    fun setDisplayMode(mode: String) {
        _displayMode.value = mode
        settingsManager.setPlayerDisplayStyle(mode)
        saveCurrentState()
    }

    /**
     * 保存当前播放状态（扩展版，包含队列数据）
     * iOS: PlayerData中每次修改后调用 library.saveContext()
     */
    private fun saveCurrentState() {
        playbackStateManager.savePlaybackState(
            playlist = _playlist.value,
            currentIndex = _currentIndex.value,
            playProgress = getCurrentPosition(),
            playDuration = getDuration(),
            contextType = currentContextType,
            contextId = currentContextId,
            contextName = _currentContextName.value,
            playerMode = _playerMode.value,
            wasPlaying = _isPlaying.value,
            userQueue = _userQueue.value,
            contextQueue = _contextQueue.value,
            currentContextIndex = _currentContextIndex.value,
            // Batch 2：shuffle 副本 / 播客队列与索引 / repeat 与 shuffle 开关全量落库
            shuffledContextQueue = _shuffledContextQueue.value,
            podcastQueue = _podcastQueue.value,
            podcastIndex = _currentPodcastIndex.value,
            repeatMode = _repeatModeInternal.value,
            isShuffle = _isShuffleInternal.value,
            playSource = currentPlaySource.name,
            displayMode = _displayMode.value
        )
    }
    
    private fun createMediaItem(song: Playable): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .build()

        // 优先使用本地缓存文件，否则使用流式URL
        // 对应iOS: BackendAudioPlayer.handleRequest() line 412-432
        // iOS会验证文件是否真实存在: fileManager.fileExits(relFilePath:)
        android.util.Log.d("PlayerManager", "createMediaItem for ${song.title}: isDownloaded=${song.isDownloaded}, downloadPath=${song.downloadPath}")

        val uri = if (song.isDownloaded && !song.downloadPath.isNullOrEmpty()) {
            // 验证文件是否真实存在（对应iOS: fileManager.fileExits）
            // downloadPath 存相对 filesDir 路径，经 DownloadPathResolver 还原绝对路径
            // （兼容存量绝对路径）
            val cachedFile = com.amperfy.data.download.DownloadPathResolver.resolve(context.filesDir, song.downloadPath)
            val fileExists = cachedFile.exists()
            android.util.Log.d("PlayerManager", "Cache file check: path=${song.downloadPath}, exists=$fileExists, size=${if (fileExists) cachedFile.length() else 0}")

            if (fileExists) {
                // 使用本地文件（绝对路径）
                android.util.Log.d("PlayerManager", "✅ Playing from local cache: ${cachedFile.absolutePath}")
                cachedFile.absolutePath
            } else {
                // 文件不存在，fallback到streaming
                // 对应iOS: BackendAudioPlayer line 433-478
                android.util.Log.w("PlayerManager", "❌ Cache file not found: ${song.downloadPath}, falling back to stream")

                // 触发自动缓存下载（后台任务）
                triggerAutoCacheIfEnabled(song)

                song.streamUrl
            }
        } else {
            // 使用流式URL（W5：按曲目所属账户重建）
            val resolved = resolveStreamUri(song)
            if (resolved == null) {
                // fail-closed：accountId 非空但账户不存在（已登出/迁移异常）——
                // 禁止回退 active 账户、禁止旧 URL 重试。异步从队列移除该项；
                // 正在播放则移除后播放器自然跳至下一有效项。返回空 URI 占位随即被移除。
                android.util.Log.w("PlayerManager", "fail-closed: account gone for ${song.title}, removing from queue")
                scope.launch { removeFromQueue(song) }
                ""
            } else {
                triggerAutoCacheIfEnabled(song)
                resolved
            }
        }

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * 按曲目所属账户重建流 URL（fail-closed）：
     * - 电台/播客单集：直接用曲目自带 streamUrl（电台原始直连、播客用 streamId，不走 getStreamUrl）；
     * - 普通歌曲：经 componentsFor(song) 取该账户 music.getStreamUrl（凭证/activeServerUrl/
     *   流媒体格式与比特率均取自曲目所属账户 + 当前网络，比预烘焙 URL 更准确）；
     * - 账户不存在（accountId 非空却解析不到）→ 返回 null（调用方 fail-closed）。
     */
    private fun resolveStreamUri(song: Playable): String? {
        if (song.isRadio || song.isPodcastEpisode) return song.streamUrl
        val comp = componentsFor(song) ?: return null   // 账户不存在 → fail-closed
        val ident = comp.accountInfo.ident
        val creds = credentialsManager.getCredentials(ident)
            ?: credentialsManager.getCredentials()  // 旧单账户兜底（ident 命名空间键缺失）
            ?: return song.streamUrl
        return comp.music.getStreamUrl(song.id, creds.username, creds.password, creds.serverUrl)
    }

    /**
     * 登出某账户的播放侧处理（对齐 iOS PlayQueueHandler.logout，登出顺序第 1 步）：
     * 任一队列含该账户曲目 → clearAll + 停止播放；否则不动。
     */
    fun logoutAccount(info: com.amperfy.data.model.AccountInfo) {
        // Batch 2：播客队列同样纳入判定（iOS PlayQueueHandler.logout:405-414 检查
        // contextQueue / userQueuePlaylist / podcastQueue 三条）
        val affected = (_playlist.value + _userQueue.value + _contextQueue.value + _podcastQueue.value)
            .any { it.accountId == info.ident }
        if (affected) {
            android.util.Log.d("PlayerManager", "logoutAccount ${info.ident}: queue affected, remove all items")
            removeAllQueues()
        }
    }

    /**
     * 全量清空（iOS: PlayerData.removeAllItems:526-534）——**不分模式**，音乐/播客两侧队列与索引
     * 一并归零。仅用于登出这类整体重置场景；用户的 "Clear Player" 走按模式的 [clearAll]。
     */
    private fun removeAllQueues() {
        controller?.stop()
        controller?.clearMediaItems()

        _playlist.value = emptyList()
        _currentSong.value = null
        _currentIndex.value = 0
        _isPlaying.value = false

        _contextQueue.value = emptyList()
        _shuffledContextQueue.value = emptyList()
        _userQueue.value = emptyList()
        _podcastQueue.value = emptyList()
        _currentContextIndex.value = 0
        _currentPodcastIndex.value = 0
        currentPlaySource = PlaySource.SINGLE
        _currentContextName.value = ""
        _playerMode.value = PlayerMode.MUSIC

        saveCurrentState()
    }

    /**
     * 当切换到新歌曲时触发自动缓存
     * 对应iOS: BackendAudioPlayer.handleRequest() + PlayerDownloadPreparationHandler.didStartPlayingFromBeginning()
     *
     * iOS策略：
     * 1. 下载当前正在播放的歌曲
     * 2. 预下载接下来的 3 首歌曲 (preDownloadCount = 3)
     */
    private fun triggerAutoCacheForCurrentSong(song: Playable, currentIndex: Int) {
        if (!settingsManager.isPlayerAutoCachePlayedItems.value) {
            return
        }

        val playlist = _playlist.value
        android.util.Log.d("PlayerManager", "Auto-caching triggered for index $currentIndex: ${song.title}")

        // 1. 下载当前曲目（电台不缓存，对应 iOS BackendAudioPlayer.swift:377 !playable.isRadio；
        //    Batch 4 起播客单集同样自动缓存，iOS 亦只排除 radio）。W5：按曲目所属账户的 downloader 路由
        val songDownloader = componentsFor(song)?.downloader
        if (songDownloader != null && !song.isRadio && !song.isDownloaded && !songDownloader.isDownloadingOrPending(song.id)) {
            android.util.Log.d("PlayerManager", "Auto-caching current song: ${song.title}")
            songDownloader.downloadPlayable(song)
        }

        // 2. 预下载接下来的 3 首歌曲（对应iOS: preDownloadCount = 3；电台跳过，
        //    对应 iOS PlayerDownloadPreparationHandler.swift:55,67 !playable.isRadio）
        for (i in 1..3) {
            val nextIndex = currentIndex + i
            if (nextIndex < playlist.size) {
                val nextSong = playlist[nextIndex]
                val nextDownloader = componentsFor(nextSong)?.downloader
                if (nextDownloader != null && !nextSong.isRadio && !nextSong.isDownloaded && !nextDownloader.isDownloadingOrPending(nextSong.id)) {
                    android.util.Log.d("PlayerManager", "Pre-downloading song[$nextIndex]: ${nextSong.title}")
                    nextDownloader.downloadPlayable(nextSong)
                }
            }
        }
    }

    /**
     * 在 createMediaItem 时不再触发下载，只返回 MediaItem
     * 下载触发已移至 onMediaItemTransition
     */
    private fun triggerAutoCacheIfEnabled(song: Playable) {
        // 已移至 triggerAutoCacheForCurrentSong，此方法保留以避免编译错误
        // 不在这里触发下载，因为此时 _playlist 和 _currentIndex 可能还未设置
    }
    
    fun release() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture ?: return)
    }

    // ========== 三层队列辅助方法 ==========

    /**
     * 判断当前是否从 contextQueue 播放
     * 对应iOS: 检查 activeQueue 状态
     */
    private fun isPlayingFromContext(): Boolean {
        return effectivePlaySource == PlaySource.CONTEXT
    }

    /**
     * 判断生效上下文队列中是否还有下一曲
     * 对应iOS: PlayQueueHandler.nextQueueCount > 0（PlayQueueHandler.swift:140-146）
     */
    private fun hasNextInContext(): Boolean {
        return activeIndex < activeContextQueue.size - 1
    }

    /**
     * Previous 段的纯函数计算（iOS: PlayQueueHandler.prevQueueCount + getAllPrevQueueItems，
     * PlayQueueHandler.swift:51-86）。派生流与命令式路径共用同一份逻辑，避免两处漂移：
     * - isUserQueuePlaying 且 index == -1 → 空；
     * - isUserQueuePlaying 且 index == 0 且队列非空 → 队首 1 首；
     * - index > 0 → isUserQueuePlaying ? queue[0..index] : queue[0..<index]；
     * - 其余 → 空。
     */
    private fun computePrevQueue(
        contextQ: List<Playable>,
        ctxIndex: Int,
        source: PlaySource
    ): List<Playable> = when {
        source == PlaySource.USER && ctxIndex == -1 -> emptyList()
        source == PlaySource.USER && ctxIndex == 0 && contextQ.isNotEmpty() -> listOf(contextQ[0])
        ctxIndex > 0 -> if (source == PlaySource.USER) {
            contextQ.take(ctxIndex + 1)
        } else {
            contextQ.take(ctxIndex)
        }
        else -> emptyList()
    }

    /**
     * Previous 段的**即时**快照（不经派生流，避免「刚改完队列就读」拿到滞后值）。
     * 命令式路径（playNext / playPrevious / playFromQueue / 队列重排）一律用它。
     */
    private fun currentPrevQueue(): List<Playable> =
        computePrevQueue(activeContextQueue, activeIndex, effectivePlaySource)

    /**
     * Next From 段在生效上下文队列中的**起始绝对索引**（iOS: PlayQueueHandler.movePlayable
     * 里的 `offsetToNext`，PlayQueueHandler.swift:310-312）——显示派生流
     * （[nextQueue] / [contextNextQueue]）与 [movePlayable] 的索引换算共用同一真相源。
     *
     * 与 iOS 的等价关系（iOS: `offsetToNext = prevQueueCount + (isUserQueuePlaying ? 0 : 1)`）：
     * - CONTEXT 播放：prevCount = ctxIndex，故 offsetToNext = prevCount + 1 = ctxIndex + 1；
     * - USER 播放：prevCount = ctxIndex + 1（prev 段含 contextQ[ctxIndex]），
     *   故 offsetToNext = prevCount + 0 = ctxIndex + 1。
     * 两者数值同为 ctxIndex + 1，与 iOS `nextQueueCount = songCount - currentIndex - 1`
     * （:140-146，与 isUserQueuePlaying 无关）一致。
     *
     * 2026-08-03 修正：此前 USER 播放态下 Next From 返回**整条**上下文队列，
     * 与 prev 段（take(ctxIndex+1)）重叠显示，也与 rebuildPlaylist 装给 Media3 的
     * 尾段（drop(ctxIndex+1)）矛盾。
     */
    private fun computeNextQueueStart(ctxIndex: Int, source: PlaySource): Int = when (source) {
        PlaySource.CONTEXT, PlaySource.USER -> (ctxIndex + 1).coerceAtLeast(0)
        // SINGLE 为 Android 特有来源（iOS 无对应）：整条上下文队列都算「即将播放」，
        // 与 rebuildPlaylist 的 SINGLE 分支（添加整个上下文队列）保持一致
        PlaySource.SINGLE -> 0
    }

    // ========== 队列操作方法 (对应iOS: PlayQueueHandler) ==========

    /**
     * 插入到 User Queue 开头 (Play Next)
     * 对应iOS: PlayQueueHandler.insertUserQueue()
     */
    fun insertUserQueue(playables: List<Playable>) {
        android.util.Log.d("PlayerManager", "insertUserQueue: ${playables.size} songs")
        _userQueue.value = playables + _userQueue.value

        // 重新构建 _playlist
        rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 追加到 User Queue 末尾 (Play Later)
     * 对应iOS: PlayQueueHandler.appendUserQueue()
     */
    fun appendUserQueue(playables: List<Playable>) {
        android.util.Log.d("PlayerManager", "appendUserQueue: ${playables.size} songs")
        _userQueue.value = _userQueue.value + playables

        // 重新构建 _playlist
        rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 清空 User Queue
     * 对应iOS: PlayQueueHandler.clearUserQueue()
     * 注意: 如果当前播放的歌曲在 userQueue 中，保留它
     */
    fun clearUserQueue() {
        android.util.Log.d("PlayerManager", "clearUserQueue")
        val currentSong = _currentSong.value

        if (currentPlaySource == PlaySource.USER && currentSong != null) {
            // 保留当前播放项，清空其他
            _userQueue.value = emptyList()
            // 切换到单曲模式
            currentPlaySource = PlaySource.SINGLE
        } else {
            _userQueue.value = emptyList()
        }

        // 重新构建 _playlist
        rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 插入到 Context Queue (当前播放位置之后)
     * 对应iOS: PlayQueueHandler.insertContextQueue()
     */
    fun insertContextQueue(playables: List<Playable>) {
        android.util.Log.d("PlayerManager", "insertContextQueue: ${playables.size} songs")
        // 上下文队列被手动修改后不再是单一来源，清空名称（iOS PlayQueueHandler.swift:198-201，
        // 此后 currentContextName 派生流按队列内容显示 "Mixed Context"）
        _currentContextName.value = ""
        // iOS PlayerData.insertContextQueue:460-471：contextPlaylist 与 shuffledContextPlaylist
        // **同一 targetIndex 双写**；原队列为空时 targetIndex = 0
        val targetIndex = if (_contextQueue.value.isEmpty()) 0 else _currentContextIndex.value + 1
        _contextQueue.value = _contextQueue.value.toMutableList().apply {
            addAll(targetIndex.coerceIn(0, size), playables)
        }
        _shuffledContextQueue.value = _shuffledContextQueue.value.toMutableList().apply {
            addAll(targetIndex.coerceIn(0, size), playables)
        }

        // 重新构建 _playlist
        rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 追加到 Context Queue 末尾
     * 对应iOS: PlayQueueHandler.appendContextQueue()
     */
    fun appendContextQueue(playables: List<Playable>) {
        android.util.Log.d("PlayerManager", "appendContextQueue: ${playables.size} songs")
        // 同 insertContextQueue：清空上下文名称（iOS PlayQueueHandler.swift:203-205）
        _currentContextName.value = ""
        // iOS PlayerData.appendContextQueue:473-476：两条队列都追加到各自末尾
        _contextQueue.value = _contextQueue.value + playables
        _shuffledContextQueue.value = _shuffledContextQueue.value + playables

        // 重新构建 _playlist
        rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 插入到 Podcast Queue（当前单集之后）
     * 对应iOS: PlayerData.insertPodcastQueue()（PlayerData.swift:487-493）——
     * 目标位置 currentPodcastIndex + 1；队列为空时落 0。**与音乐上下文队列完全独立**。
     */
    fun insertPodcastQueue(playables: List<Playable>) {
        android.util.Log.d("PlayerManager", "insertPodcastQueue: ${playables.size} episodes")
        val targetIndex = if (_podcastQueue.value.isEmpty()) 0 else _currentPodcastIndex.value + 1
        _podcastQueue.value = _podcastQueue.value.toMutableList().apply {
            addAll(targetIndex.coerceIn(0, size), playables)
        }

        // 只有当前就在播客模式时才需要重排 Media3 列表
        if (isPodcastMode) rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 追加到 Podcast Queue 末尾
     * 对应iOS: PlayerData.appendPodcastQueue()（PlayerData.swift:495-497）
     */
    fun appendPodcastQueue(playables: List<Playable>) {
        android.util.Log.d("PlayerManager", "appendPodcastQueue: ${playables.size} episodes")
        _podcastQueue.value = _podcastQueue.value + playables

        if (isPodcastMode) rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 清空 Context Queue
     * 对应iOS: PlayerData.clearContextQueue()（PlayerData.swift:509-520，context + shuffled 同清）
     */
    fun clearContextQueue() {
        android.util.Log.d("PlayerManager", "clearContextQueue")
        // iOS PlayQueueHandler.swift:234-237：清空队列同时清空上下文名称
        _currentContextName.value = ""
        _contextQueue.value = emptyList()
        _shuffledContextQueue.value = emptyList()
        _currentContextIndex.value = 0
        if (currentPlaySource == PlaySource.CONTEXT) {
            currentPlaySource = PlaySource.SINGLE
        }

        // 重新构建 _playlist
        rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 从队列中删除指定歌曲
     * 对应iOS: PlayQueueHandler.removePlayable(at:) → removeItemFromActiveQueue（:439-449）——
     * 活动队列删除的同时，**非活动队列（shuffle 的另一侧副本）也删掉同一条目**，
     * 保证 shuffle 开关来回切换时两条队列的元素集合始终一致。
     */
    fun removeFromQueue(playable: Playable) {
        android.util.Log.d("PlayerManager", "removeFromQueue: ${playable.title}")

        // 从 userQueue 移除
        _userQueue.value = _userQueue.value.filter { it.id != playable.id }

        // 生效队列中的位置决定索引是否要前移（iOS: index < currentIndex → currentIndex - 1）
        val indexInActive = activeContextQueue.indexOfFirst { it.id == playable.id }
        if (indexInActive >= 0) {
            if (indexInActive < activeIndex) {
                activeIndex -= 1
            }
            if (isPodcastMode) {
                _podcastQueue.value = _podcastQueue.value.filter { it.id != playable.id }
            } else {
                // 双写：原始队列与打乱副本都删（活动 + 非活动）
                _contextQueue.value = _contextQueue.value.filter { it.id != playable.id }
                _shuffledContextQueue.value = _shuffledContextQueue.value.filter { it.id != playable.id }
            }
        }

        // 重新构建 _playlist
        rebuildPlaylist(preserveCurrentItem = true)

        saveCurrentState()
    }

    /**
     * 获取队列计数
     * 对应iOS: PlayQueueHandler.userQueueCount, prevQueueCount, nextQueueCount
     */
    fun getUserQueueCount(): Int = activeUserQueue.size
    fun getPrevQueueCount(): Int = currentPrevQueue().size
    fun getNextQueueCount(): Int = nextQueue.value.size

    // ========== 队列拖拽重排（Batch 3：跨 section 拖动，2026-08-03） ==========
    // 对应 iOS: PopupPlayer+TableViewExtension.moveRowAt → PlayQueueHandler.movePlayable
    // （PlayQueueHandler.swift:309-391）。九种 section 组合逐条直译，索引换算共用
    // computeNextQueueStart / currentPrevQueue。
    //
    // 与 iOS 的**唯一结构性差异**：iOS 的 userQueuePlaylist 在「正从 user 队列播放」时
    // 把正在播放项**留在队列首位**，故各处要加 userQueueOffsetIsUserQueuePlaying（+1）
    // 偏移；Android 的 [_userQueue] 从不含正在播放项（等价 iOS 的「可见 user 段」），
    // 该偏移在此一律为 0，**不照抄 +1**。

    /**
     * 跨 section 队列拖动（iOS: PlayQueueHandler.movePlayable，:309-391）
     *
     * @param from 拖动起点（**拖动前**队列坐标下的段 + 段内索引）
     * @param to 落点（**拖动后**目标段内的最终索引；允许 == 目标段原长度，即拖到段末追加）
     */
    fun movePlayable(from: PlayerIndex, to: PlayerIndex) {
        val isUser = effectivePlaySource == PlaySource.USER
        val prevCount = currentPrevQueue().size
        val userCount = activeUserQueue.size
        val offsetToNext = computeNextQueueStart(activeIndex, effectivePlaySource)
        val nextCount = (activeContextQueue.size - offsetToNext).coerceAtLeast(0)

        // guard（iOS :314-322）：源索引须落在源段内，落点允许等于目标段长度（段末追加）
        if (from.index < 0 || to.index < 0) return
        val fromCount = when (from.queueType) {
            PlayerQueueType.PREV -> prevCount
            PlayerQueueType.USER -> userCount
            PlayerQueueType.NEXT -> nextCount
        }
        val toCount = when (to.queueType) {
            PlayerQueueType.PREV -> prevCount
            PlayerQueueType.USER -> userCount
            PlayerQueueType.NEXT -> nextCount
        }
        if (from.index >= fromCount) return
        if (to.index > toCount) return
        if (from == to) return
        // 播客模式 user 段不渲染（activeUserQueue 恒空），任何涉及 user 的落点都是脏数据
        if (isPodcastMode &&
            (from.queueType == PlayerQueueType.USER || to.queueType == PlayerQueueType.USER)
        ) return

        when {
            // Prev <=> Prev（iOS :324-326）
            from.queueType == PlayerQueueType.PREV && to.queueType == PlayerQueueType.PREV -> {
                moveContextItem(from.index, to.index)
            }

            // Next <=> Next（iOS :327-329）
            from.queueType == PlayerQueueType.NEXT && to.queueType == PlayerQueueType.NEXT -> {
                moveContextItem(offsetToNext + from.index, offsetToNext + to.index)
            }

            // User <=> User（iOS :330-336）
            from.queueType == PlayerQueueType.USER && to.queueType == PlayerQueueType.USER -> {
                moveUserQueueItemAt(from.index, to.index)
            }

            // Prev ==> Next（iOS :338-346）
            from.queueType == PlayerQueueType.PREV && to.queueType == PlayerQueueType.NEXT -> {
                if (!isUser) {
                    moveContextItem(from.index, offsetToNext + to.index - 1)
                } else if (from.index == activeIndex && to.index == 0) {
                    // USER 播放态下 prev 末行即 contextQ[activeIndex]，拖到 next 首位
                    // 等价于「只把索引前移一格」，队列本身不动
                    activeIndex -= 1
                } else {
                    moveContextItem(from.index, offsetToNext + to.index - 1)
                    activeIndex -= 1
                }
            }

            // Next ==> Prev（iOS :348-356）
            from.queueType == PlayerQueueType.NEXT && to.queueType == PlayerQueueType.PREV -> {
                if (!isUser) {
                    moveContextItem(offsetToNext + from.index, to.index)
                } else if (from.index == 0 && to.index == activeIndex + 1) {
                    // 对称于上：next 首行拖到 prev 末尾 = 只把索引后移一格
                    activeIndex += 1
                } else {
                    moveContextItem(offsetToNext + from.index, to.index)
                    activeIndex += 1
                }
            }

            // User ==> Next（iOS :359-363）
            from.queueType == PlayerQueueType.USER && to.queueType == PlayerQueueType.NEXT -> {
                val item = activeUserQueue[from.index]
                appendToActiveContextQueues(item)
                moveContextItem(activeContextQueue.size - 1, offsetToNext + to.index)
                removeItemFromUserQueueAt(from.index)
            }

            // User ==> Prev（iOS :365-372）
            from.queueType == PlayerQueueType.USER && to.queueType == PlayerQueueType.PREV -> {
                val item = activeUserQueue[from.index]
                appendToActiveContextQueues(item)
                moveContextItem(activeContextQueue.size - 1, to.index)
                // USER 播放态下 prev 段 = contextQ[0..activeIndex]，插入后需把索引后移一格
                // 才能让新条目落进 prev 段（CONTEXT 态由 moveContextItem 内部调整）
                if (isUser) activeIndex += 1
                removeItemFromUserQueueAt(from.index)
            }

            // Prev ==> User（iOS :375-381）
            from.queueType == PlayerQueueType.PREV && to.queueType == PlayerQueueType.USER -> {
                val item = activeContextQueue[from.index]
                _userQueue.value = _userQueue.value + item
                moveUserQueueItemAt(_userQueue.value.size - 1, to.index)
                removeItemFromActiveQueueAt(from.index)
            }

            // Next ==> User（iOS :383-389）
            from.queueType == PlayerQueueType.NEXT && to.queueType == PlayerQueueType.USER -> {
                val item = activeContextQueue[offsetToNext + from.index]
                _userQueue.value = _userQueue.value + item
                moveUserQueueItemAt(_userQueue.value.size - 1, to.index)
                removeItemFromActiveQueueAt(offsetToNext + from.index)
            }
        }

        // Prev 段整体不在 Media3 播放列表内（playlist = 当前曲 + userQueue + context 后段），
        // 纯 prev 内重排无需重建；其余组合都会改动 Media3 的队列尾部
        if (from.queueType != PlayerQueueType.PREV || to.queueType != PlayerQueueType.PREV) {
            rebuildPlaylist(preserveCurrentItem = true)
        }
        saveCurrentState()
    }

    /**
     * 生效上下文队列内按**绝对索引**搬移（iOS: PlayQueueHandler.moveContextItem，:451-463）
     *
     * 只动活动队列（shuffle 开则是打乱副本），非活动副本不同步重排——iOS 同。
     * 索引调整仅在「非 user 播放」时进行（iOS `guard !isUserQueuePlaying`）；
     * Android 的 SINGLE 来源亦走调整分支。
     */
    private fun moveContextItem(fromAbs: Int, toAbs: Int) {
        val queue = activeContextQueue.toMutableList()
        if (fromAbs !in queue.indices || toAbs !in queue.indices || fromAbs == toAbs) return
        queue.add(toAbs, queue.removeAt(fromAbs))
        writeActiveContextQueue(queue)

        if (effectivePlaySource == PlaySource.USER) return
        val idx = activeIndex
        when {
            idx == fromAbs -> activeIndex = toAbs
            fromAbs < idx && idx <= toAbs -> activeIndex = idx - 1
            toAbs <= idx && idx < fromAbs -> activeIndex = idx + 1
        }
    }

    /** User Queue 内搬移（iOS: PlayQueueHandler.moveUserQueueItem，:465-469） */
    private fun moveUserQueueItemAt(fromIndex: Int, toIndex: Int) {
        val queue = _userQueue.value.toMutableList()
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        queue.add(toIndex, queue.removeAt(fromIndex))
        _userQueue.value = queue
    }

    /** User Queue 内按索引删除（iOS: PlayQueueHandler.removeItemFromUserQueue，:434-437） */
    private fun removeItemFromUserQueueAt(index: Int) {
        val queue = _userQueue.value.toMutableList()
        if (index !in queue.indices) return
        queue.removeAt(index)
        _userQueue.value = queue
    }

    /**
     * 生效上下文队列内按绝对索引删除（iOS: PlayQueueHandler.removeItemFromActiveQueue，:439-449）
     *
     * 先按 iOS 顺序调整索引，再从活动队列删除；**非活动副本按 id 删首个出现**
     * （iOS `playerQueues.inactiveQueue.remove(firstOccurrenceOfPlayable:)`）——
     * 音乐模式下 shuffle 开则非活动副本是 [_contextQueue]、关则是 [_shuffledContextQueue]；
     * 播客模式没有非活动副本。
     */
    private fun removeItemFromActiveQueueAt(index: Int) {
        val queue = activeContextQueue.toMutableList()
        if (index !in queue.indices) return
        val removed = queue[index]

        val idx = activeIndex
        if (index < idx) {
            activeIndex = idx - 1
        } else if (effectivePlaySource == PlaySource.USER && index == idx) {
            activeIndex = idx - 1
        }

        queue.removeAt(index)
        writeActiveContextQueue(queue)

        if (!isPodcastMode) {
            if (_isShuffleInternal.value) {
                val inactive = _contextQueue.value.toMutableList()
                val i = inactive.indexOfFirst { it.id == removed.id }
                if (i >= 0) {
                    inactive.removeAt(i)
                    _contextQueue.value = inactive
                }
            } else {
                val inactive = _shuffledContextQueue.value.toMutableList()
                val i = inactive.indexOfFirst { it.id == removed.id }
                if (i >= 0) {
                    inactive.removeAt(i)
                    _shuffledContextQueue.value = inactive
                }
            }
        }
    }

    /**
     * 把单个条目追加到上下文队列末尾（iOS: PlayerData.appendContextQueue，PlayerData.swift:473-476）
     *
     * 音乐模式**双写** [_contextQueue] 与 [_shuffledContextQueue]；播客模式只追加 [_podcastQueue]。
     * **刻意不复用公有 [appendContextQueue] / [insertContextQueue]**——那两个会清空
     * [_currentContextName]（对齐 iOS PlayQueueHandler 层语义），而 iOS movePlayable 走的是
     * PlayerData 层 append（:360/366），全程不动 contextName。
     */
    private fun appendToActiveContextQueues(item: Playable) {
        if (isPodcastMode) {
            _podcastQueue.value = _podcastQueue.value + item
        } else {
            _contextQueue.value = _contextQueue.value + item
            _shuffledContextQueue.value = _shuffledContextQueue.value + item
        }
    }

    /**
     * 写回生效上下文队列（[activeContextQueue] 的写侧对偶）。
     *
     * 段内重排只动**活动**队列，非活动副本保持原顺序——对齐 iOS
     * PlayQueueHandler.moveContextItem（:451-463，只操作 activeQueue，不同步 inactiveQueue）。
     */
    private fun writeActiveContextQueue(queue: List<Playable>) {
        when {
            isPodcastMode -> _podcastQueue.value = queue
            _isShuffleInternal.value -> _shuffledContextQueue.value = queue
            else -> _contextQueue.value = queue
        }
    }

    /**
     * 重新构建 _playlist（合并三层队列）
     * _playlist 是 Media3 实际使用的播放列表
     * 对应iOS: PlayQueueHandler 动态合并队列的逻辑
     *
     * @param preserveCurrentItem true = 队列修改场景（当前播放曲目不变）：
     *   不整表 setMediaItems（那会重新 prepare 正在播放的流，造成数秒卡顿），
     *   而是保留正在播放的 media item，仅增量替换其后的队列尾部
     *   （iOS 行为：队列变化从不打断当前播放）
     */
    private fun rebuildPlaylist(preserveCurrentItem: Boolean = false) {
        val currentSong = _currentSong.value

        // 合并规则:
        // [currentSong] + userQueue + contextQueue(从currentContextIndex+1开始)
        val newPlaylist = mutableListOf<Playable>()

        // 1. 当前歌曲
        if (currentSong != null) {
            newPlaylist.add(currentSong)
        }

        // 2. userQueue（播客模式恒空，对齐 iOS isUserQueueVisible）
        newPlaylist.addAll(activeUserQueue)

        // 3. 生效上下文队列的剩余部分（Batch 2：shuffle 开则取打乱副本，播客模式取播客队列）
        // 注意：无论当前从哪里播放，都应该从 activeIndex + 1 开始添加
        // 这样Media3自动播放时，userQueue播放完后会继续播放Next From的第一首
        val activeQueue = activeContextQueue
        val activeIdx = activeIndex
        if (isPlayingFromContext()) {
            // 正在播放上下文队列，添加从 activeIndex + 1 开始的部分
            newPlaylist.addAll(activeQueue.drop(activeIdx + 1))
        } else if (effectivePlaySource == PlaySource.USER && activeIdx >= 0) {
            // 正在播放userQueue，但 activeIndex 指向上次在上下文队列的位置
            newPlaylist.addAll(activeQueue.drop(activeIdx + 1))
        } else {
            // 其他情况（SINGLE 或 activeIndex < 0），添加整个上下文队列
            newPlaylist.addAll(activeQueue)
        }

        // 更新 _playlist 和 Media3
        _playlist.value = newPlaylist
        _currentIndex.value = 0  // 当前歌曲总是在索引0

        val c = controller
        if (c != null && preserveCurrentItem && currentSong != null && c.mediaItemCount > 0) {
            // 队列修改：正在播放的 media item 原封不动，仅重排其前后的队列项。
            // 归一化到 [当前曲目] + 新队列尾部 的结构：
            // 1) 删掉当前曲目之后的所有项  2) 删掉当前曲目之前的所有项（当前项移到索引0）
            // 3) 追加新的队列尾部。removeMediaItems/addMediaItems 不触碰播放中的项，无卡顿
            val cur = c.currentMediaItemIndex
            if (cur < c.mediaItemCount - 1) {
                c.removeMediaItems(cur + 1, c.mediaItemCount)
            }
            if (cur > 0) {
                c.removeMediaItems(0, cur)
            }
            c.addMediaItems(newPlaylist.drop(1).map { createMediaItem(it) })
        } else {
            // 换歌/冷启动：整表替换（保持当前播放位置；换新上下文时由调用方 seek 归零）
            val currentPosition = getCurrentPosition()
            val mediaItems = newPlaylist.map { createMediaItem(it) }
            c?.setMediaItems(mediaItems, 0, currentPosition)
            c?.prepare()
        }

        android.util.Log.d("PlayerManager", "rebuildPlaylist: total=${newPlaylist.size}, preserve=$preserveCurrentItem, mode=${_playerMode.value}, userQueue=${activeUserQueue.size}, contextNext=${if (isPlayingFromContext()) activeQueue.size - activeIdx - 1 else activeQueue.size}")
    }

    /**
     * Play a specific song from the queue
     * 对应iOS: PlayQueueHandler.markAndGetPlayableAsPlaying()
     *
     * iOS behavior (line 253-288):
     * - Clicking song in userQueue: Remove current playing item if isUserQueuePlaying, then remove all items before clicked
     * - Clicking song in Previous: Remove current userQueue item, set currentIndex, set isUserQueuePlaying=false
     * - Clicking song in Next From: Remove current userQueue item, update currentIndex based on isUserQueuePlaying
     *
     * @param playable 要播放的歌曲
     */
    fun playFromQueue(playable: Playable) {
        android.util.Log.d("PlayerManager", "playFromQueue: ${playable.title}")

        // 查找歌曲在哪个队列中（Batch 2：上下文侧一律查生效队列）
        val userQueueIndex = activeUserQueue.indexOfFirst { it.id == playable.id }
        val contextQueueIndex = activeContextQueue.indexOfFirst { it.id == playable.id }
        val prevQueueIndex = currentPrevQueue().indexOfFirst { it.id == playable.id }

        when {
            // 在 userQueue 中 (Next in Queue)
            // iOS: PlayQueueHandler.swift line 255-265
            userQueueIndex >= 0 -> {
                android.util.Log.d("PlayerManager", "playFromQueue: from userQueue at index $userQueueIndex")

                // iOS逻辑：
                // 1. 如果当前正在播放userQueue，先移除当前播放项 (line 257-259)
                // 2. 移除被点击项之前的所有项 (line 260-264)
                // 3. 设置 isUserQueuePlaying = true (line 265)

                var updatedUserQueue = _userQueue.value.toMutableList()

                // 如果正在播放userQueue，移除第一项（当前播放项）
                if (currentPlaySource == PlaySource.USER && updatedUserQueue.isNotEmpty()) {
                    updatedUserQueue.removeAt(0)
                    android.util.Log.d("PlayerManager", "  Removed current playing item from userQueue")
                    // 需要调整索引，因为移除了第一项
                    val adjustedIndex = (userQueueIndex - 1).coerceAtLeast(0)
                    // 移除被点击项之前的所有项
                    if (adjustedIndex > 0) {
                        repeat(adjustedIndex) {
                            updatedUserQueue.removeAt(0)
                        }
                    }
                } else {
                    // 不在播放userQueue，直接移除被点击项之前的所有项
                    if (userQueueIndex > 0) {
                        repeat(userQueueIndex) {
                            updatedUserQueue.removeAt(0)
                        }
                    }
                }

                _userQueue.value = updatedUserQueue

                playDirectFromUserQueue(playable)
            }

            // 在 prevQueue 中 (Previous)
            // iOS: PlayQueueHandler.swift line 266-273
            prevQueueIndex >= 0 -> {
                android.util.Log.d("PlayerManager", "playFromQueue: from prevQueue at index $prevQueueIndex")
                android.util.Log.d("PlayerManager", "  BEFORE: activeIndex=$activeIndex, playSource=$effectivePlaySource")

                // iOS逻辑：
                // 1. 如果正在播放userQueue，移除当前userQueue项 (line 268-270)
                // 2. 设置 currentIndex = playerIndex.index (line 272)
                // 3. 设置 isUserQueuePlaying = false (line 273)

                if (currentPlaySource == PlaySource.USER && _userQueue.value.isNotEmpty()) {
                    // 只移除第一项（当前播放项），保留其余userQueue
                    _userQueue.value = _userQueue.value.drop(1)
                    android.util.Log.d("PlayerManager", "  Removed current playing item from userQueue, remaining: ${_userQueue.value.size}")
                }

                // 设置 currentIndex 为 prevQueue 中的索引
                activeIndex = prevQueueIndex
                android.util.Log.d("PlayerManager", "  AFTER: activeIndex=$activeIndex")

                playDirectFromContext(playable)
            }

            // 在生效上下文队列中 (Next From)
            // iOS: PlayQueueHandler.swift line 274-285
            contextQueueIndex >= 0 -> {
                android.util.Log.d("PlayerManager", "playFromQueue: from contextQueue at index $contextQueueIndex")
                android.util.Log.d("PlayerManager", "  BEFORE: activeIndex=$activeIndex, playSource=$effectivePlaySource")
                android.util.Log.d("PlayerManager", "  activeQueue.size=${activeContextQueue.size}, userQueue.size=${activeUserQueue.size}")

                // iOS逻辑：
                // 1. 如果正在播放userQueue，移除当前userQueue项 (line 276-278)
                // 2. 根据 isUserQueuePlaying 计算新的 currentIndex (line 280-284)
                // 3. 设置 isUserQueuePlaying = false (line 285)

                // 关键修复：只有当前播放源是 USER 且 userQueue 第一项是当前播放歌曲时才移除
                // 如果当前播放的歌曲已经从 userQueue 移出，则不应该删除 userQueue 的第一项
                if (currentPlaySource == PlaySource.USER &&
                    _userQueue.value.isNotEmpty() &&
                    _userQueue.value.first().id == _currentSong.value?.id) {
                    // 只移除第一项（当前播放项），保留其余userQueue
                    _userQueue.value = _userQueue.value.drop(1)
                    android.util.Log.d("PlayerManager", "  Removed current playing item from userQueue, remaining: ${_userQueue.value.size}")
                } else if (currentPlaySource == PlaySource.USER) {
                    // 当前播放的歌曲已经不在 userQueue 的第一项了，不删除
                    android.util.Log.d("PlayerManager", "  Current song already removed from userQueue, keeping userQueue intact")
                }

                // 计算新的 currentContextIndex
                // iOS line 280-284:
                // if isUserQueuePlaying {
                //   setCurrentIndex(prevQueueCount + playerIndex.index)
                // } else {
                //   setCurrentIndex(prevQueueCount + 1 + playerIndex.index)
                // }
                //
                // 在Android中，contextQueueIndex是生效上下文队列的绝对索引
                // 直接使用contextQueueIndex即可
                activeIndex = contextQueueIndex
                android.util.Log.d("PlayerManager", "  AFTER: activeIndex=$activeIndex")

                playDirectFromContext(playable)
            }

            else -> {
                // 如果不在队列中，直接播放
                android.util.Log.w("PlayerManager", "Song not found in queue, playing directly")
                currentPlaySource = PlaySource.SINGLE
                _currentSong.value = playable
                rebuildPlaylist()
                controller?.seekTo(0, 0)
                controller?.play()
            }
        }

        saveCurrentState()
    }

    /**
     * 播放歌曲列表（便捷方法）
     * 对应 iOS: PlayerFacade.play(context:)
     */
    fun play(
        songs: List<Playable>,
        contextType: PlayContextType = PlayContextType.NONE,
        contextId: String? = null,
        contextName: String = ""
    ) {
        playPlaylist(songs, 0, contextType, contextId, contextName)
    }

    /**
     * 随机播放歌曲列表（便捷方法）
     *
     * Batch 2 改写为 iOS 语义（PlayerFacade.playShuffled，PlayerFacade.swift:617-626）：
     * 先关 shuffle → **以随机起始索引播放该上下文（队列顺序不变）** → 再开 shuffle
     * （setShuffle 生成打乱副本并把当前曲搬到副本首位）。
     * 旧实现是 `songs.shuffled()` 一次性把上下文队列本身打乱，
     * 那样原始顺序丢失、关闭 shuffle 后无法还原，且 shuffle 开关状态与队列脱节。
     */
    fun playShuffled(
        songs: List<Playable>,
        contextType: PlayContextType = PlayContextType.NONE,
        contextId: String? = null,
        contextName: String = ""
    ) {
        if (songs.isEmpty()) return
        val startIndex = songs.indices.random()
        // playPlaylist 内部已按 iOS play(context:) 强制置 isShuffle=false
        playPlaylist(songs, startIndex, contextType, contextId, contextName)
        setShuffle(true)
    }

    // ========== Batch 2：播放模式手动切换（iOS: PlayerFacade.setPlayerMode:488-492）==========

    /**
     * 手动切换音乐 / 播客模式（播放器底排的 playerMode 按钮）。
     *
     * iOS 语义：`musicPlayer.stopButRemainIndex()` + `playerStatus.setPlayerMode()` +
     * `notifyPlaylistUpdated()`——停止当前播放，但**双侧索引都保留**（不同于 stop()，
     * stop 会 setCurrentIndex(0) 并清 user queue）；随后按目标模式重排列表，当前曲就位不自动开播。
     */
    fun setPlayerMode(mode: PlayerMode) {
        if (_playerMode.value == mode) return
        android.util.Log.d("PlayerManager", "setPlayerMode: ${_playerMode.value} -> $mode")

        // 离场进度（含播客单集，Batch 2 起无条件记忆）
        saveSongProgressFor(_currentSong.value, getCurrentPosition())

        // stopButRemainIndex：停播放、保留两侧索引与队列
        controller?.stop()
        _isPlaying.value = false

        _playerMode.value = mode
        // 切模式后 repeat/shuffle 的对外读数随之变化（播客恒 off/false），同步给 Media3
        applyRepeatModeToController()

        // 目标模式的当前曲就位
        val target = activeContextQueue.getOrNull(activeIndex)
        _currentSong.value = target
        currentPlaySource = PlaySource.CONTEXT

        if (target == null) {
            _playlist.value = emptyList()
            _currentIndex.value = 0
            controller?.clearMediaItems()
        } else {
            rebuildPlaylist()
            // 从头装载（不自动开播，对齐 stopButRemainIndex 后仅刷新列表）
            controller?.seekToDefaultPosition(0)
            maybeRestoreSongProgress(target)
        }

        saveCurrentState()
    }

    // ========== Batch 2：skip 间隔（iOS: PlayerFacade skipForward/BackwardInterval:242-276）==========

    /** 前进间隔（毫秒）：音乐 10s / 播客 30s */
    fun skipForwardIntervalMs(): Long =
        if (isPodcastMode) SKIP_PODCAST_FORWARD_MS else SKIP_MUSIC_INTERVAL_MS

    /** 后退间隔（毫秒）：音乐 10s / 播客 15s */
    fun skipBackwardIntervalMs(): Long =
        if (isPodcastMode) SKIP_PODCAST_BACKWARD_MS else SKIP_MUSIC_INTERVAL_MS

    companion object {
        /** 播客模式下的上下文名称（iOS: PlayerData.contextName 的 podcast 分支返回 "Podcasts"） */
        private const val PODCAST_CONTEXT_NAME = "Podcasts"

        /** 音乐 skip 间隔（iOS: skipForward/BackwardMusicInterval = 10.0） */
        private const val SKIP_MUSIC_INTERVAL_MS = 10_000L

        /** 播客前进间隔（iOS: skipForwardPodcastInterval = 30.0） */
        private const val SKIP_PODCAST_FORWARD_MS = 30_000L

        /** 播客后退间隔（iOS: skipBackwardPodcastInterval = 15.0） */
        private const val SKIP_PODCAST_BACKWARD_MS = 15_000L
    }
}

sealed class PlaybackState {
    object Idle : PlaybackState()
    object Buffering : PlaybackState()
    object Playing : PlaybackState()
    object Paused : PlaybackState()
    object Ended : PlaybackState()
}
