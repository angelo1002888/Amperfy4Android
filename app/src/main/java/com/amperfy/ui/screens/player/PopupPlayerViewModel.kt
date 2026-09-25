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

package com.amperfy.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.PlayerMode
import com.amperfy.data.model.RepeatMode
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.VisualizerType
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.player.PlayerIndex
import com.amperfy.player.audio.Spectrum
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PopupPlayerViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    // W7：可视化引擎（doc 13）——P3 回收后经 AppDelegate 聚合访问（对齐「ViewModel 仅注入 AppDelegate」）
    private val audioAnalyzer get() = appDelegate.audioAnalyzer

    val currentSong = appDelegate.player.currentSong
    val isPlaying = appDelegate.player.isPlaying
    val playlist = appDelegate.player.playlist
    val currentIndex = appDelegate.player.currentIndex

    // 三层队列数据（对应iOS: PopupPlayerVC - TableView sections）
    val prevQueue = appDelegate.player.prevQueue          // Previous queue (iOS: contextPrev)
    val userQueue = appDelegate.player.userQueue          // User queue (iOS: userQueue) - "Next in Queue"
    val contextNextQueue = appDelegate.player.contextNextQueue  // Context next queue (iOS: contextNext) - "Next From"
    val contextName = appDelegate.player.currentContextName  // Context name (iOS: player.contextName) - for "Next From" header

    // 当前歌曲详细信息（包含 favorite, rating 等元数据）
    // 对应iOS: player.currentlyPlaying
    // 修复：使用 flatMapLatest 响应式观察歌曲数据变化
    // 当在其他页面修改 Favorite/Rating 时，这里会自动更新
    val currentSongDetails: StateFlow<Song?> = currentSong.flatMapLatest { playable ->
        if (playable != null) {
            appDelegate.library.observeSongById(playable.id)
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Toast 消息事件流（一次性事件，用于显示 Favorite 操作结果）
    // 使用 SharedFlow 而非 StateFlow，因为这是一次性事件，不需要保持状态
    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    // Settings - 在线模式状态
    val isOnlineMode = appDelegate.settings.isOfflineMode.map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Queue 状态 - 用于Player Options菜单
    val hasQueue: StateFlow<Boolean> = appDelegate.player.currentSong.map { song ->
        song != null || appDelegate.player.prevQueue.value.isNotEmpty() ||
        appDelegate.player.userQueue.value.isNotEmpty() ||
        appDelegate.player.contextNextQueue.value.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasUserQueue: StateFlow<Boolean> = appDelegate.player.userQueue.map {
        it.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 性能优化：使用lazy初始化,只在UI真正需要时才开始轮询
    // 避免在ViewModel创建时就启动高频更新
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    // iOS PopupPlayerVC - Display mode state (使用 PlayerManager 的 displayMode)
    private val _displayMode = MutableStateFlow(PlayerDisplayMode.LARGE)
    val displayMode: StateFlow<PlayerDisplayMode> = _displayMode

    // 标记是否已经启动position更新
    private var hasStartedPositionUpdates = false

    init {
        // 观察 PlayerManager 的 displayMode 变化
        viewModelScope.launch {
            appDelegate.player.displayMode.collect { mode ->
                _displayMode.value = when (mode) {
                    "COMPACT" -> PlayerDisplayMode.COMPACT
                    else -> PlayerDisplayMode.LARGE
                }
            }
        }

        // 切歌时立即重置进度显示，不等 1s 轮询——否则切歌到轮询之间的窗口内
        // UI 显示上一首的 position（配合新歌 duration=TIME_UNSET→0，
        // fraction 被钳 1.0，小圆点跳到最右侧）；
        // duration 直接以新歌元数据时长起步（见 effectiveDurationMs）
        viewModelScope.launch {
            appDelegate.player.currentSong
                .map { it?.id }
                .distinctUntilChanged()
                .collect {
                    _currentPosition.value = 0L
                    _duration.value = metadataDurationMs()
                }
        }

        // 注意: currentSongDetails 现在是响应式 Flow，由 flatMapLatest 驱动
        // 无需手动 collect 和更新 _currentSongDetails
    }

    /**
     * 当前歌曲的元数据时长（毫秒）。电台无时长（0），保持 "--:--" 与 fraction 0
     */
    private fun metadataDurationMs(): Long =
        (appDelegate.player.currentSong.value?.duration ?: 0).toLong() * 1000L

    /**
     * 展示用时长：优先 ExoPlayer 实际时长；未知时回退歌曲元数据时长。
     * 转码流媒体（默认 Streaming Format = mp3）为 chunked 传输无 Content-Length，
     * ExoPlayer 需下载相当多数据后才能推断 duration（可长达几十秒甚至整首），
     * 期间用服务器元数据时长桥接，进度条立即可用（iOS AVPlayer 对 mp3 流的
     * 时长估算更快，故 iOS 无此回退；数值以服务器元数据为准并不失真）
     */
    private fun effectiveDurationMs(): Long {
        // 直播流（电台）duration 为 C.TIME_UNSET（负值），钳为 0 防进度计算异常
        val rawDuration = appDelegate.player.getDuration().coerceAtLeast(0L)
        return if (rawDuration > 0) rawDuration else metadataDurationMs()
    }

    /**
     * 启动播放进度更新 - 延迟初始化
     * 只有在UI真正需要显示进度时才调用此方法
     */
    fun ensurePositionUpdatesStarted() {
        if (!hasStartedPositionUpdates) {
            hasStartedPositionUpdates = true
            viewModelScope.launch {
                while (true) {
                    _currentPosition.value = appDelegate.player.getCurrentPosition()
                    _duration.value = effectiveDurationMs()
                    delay(1000)
                }
            }
        }
    }

    fun playPause() {
        appDelegate.player.playPause()
    }

    /**
     * 下一曲按钮（iOS: PlayerUIHandler.nextButtonPushed，PlayerUIHandler.swift:71-78）——
     * 音乐模式播下一曲；**播客模式改为快进 30s**
     */
    fun skipToNext() {
        if (playerMode.value == PlayerMode.PODCAST) {
            skipForward()
        } else {
            appDelegate.player.skipToNext()
        }
    }

    /**
     * 上一曲按钮（iOS: PlayerUIHandler.previousButtonPushed，PlayerUIHandler.swift:62-69）——
     * 音乐模式上一曲/重播；**播客模式改为后退 15s**
     */
    fun skipToPrevious() {
        if (playerMode.value == PlayerMode.PODCAST) {
            skipBackward()
        } else {
            appDelegate.player.skipToPrevious()
        }
    }

    fun seekTo(position: Long) {
        appDelegate.player.seekTo(position)
        // 乐观更新本 VM 独立的进度流（轮询见 ensurePositionUpdatesStarted，间隔 1000ms）：
        // 滑杆松手瞬间 isDragging=false，显示值改由 currentPosition 驱动，此刻若仍是
        // 松手前的旧值，滑块与时间标签会先弹回旧位置、下一轮询才跳到目标。
        // Media3 position masking 保证乐观值与后续轮询连续（同 PlayerManager.seekTo）
        _currentPosition.value = position
    }

    // ==================== Music Player Skip Buttons（Settings→Display）====================
    // 对应 iOS: PlayerUIHandler.refreshSkipButtons（music 模式按设置显隐，podcast 模式恒隐藏）；
    // skip 间隔 = 音乐 ±10s / 播客 +30s、-15s（PlayerFacade.swift:237-276），由 PlayerManager 统一给出

    /** 跳过按钮是否显示：设置开启且当前非播客模式（iOS podcast 分支 isHidden=true） */
    val areSkipButtonsVisible: StateFlow<Boolean> = combine(
        appDelegate.settings.isShowMusicPlayerSkipButtons,
        appDelegate.player.playerMode
    ) { show, mode -> show && mode != PlayerMode.PODCAST }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 跳过是否可用：电台不可 seek（iOS PlayerFacade.isSkipAvailable） */
    val isSkipAvailable: StateFlow<Boolean> = currentSong.map { it?.isRadio != true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun skipForward() {
        appDelegate.player.seekTo(
            appDelegate.player.getCurrentPosition() + appDelegate.player.skipForwardIntervalMs()
        )
    }

    fun skipBackward() {
        appDelegate.player.seekTo(
            (appDelegate.player.getCurrentPosition() - appDelegate.player.skipBackwardIntervalMs())
                .coerceAtLeast(0L)
        )
    }

    // ==================== Batch 2：Repeat / Shuffle / PlayerMode ====================
    // 对应 iOS: ContextQueueNextSectionHeader 的 repeat/shuffle 按钮（经 PlayerUIHandler）
    // 与 PlayerControlView.playerModeChangePressed

    /** 当前循环模式（播客模式恒 OFF，门控在 PlayerManager 内） */
    val repeatMode: StateFlow<RepeatMode> = appDelegate.player.repeatMode

    /** 当前随机模式（播客模式恒 false） */
    val isShuffle: StateFlow<Boolean> = appDelegate.player.isShuffle

    /** 当前播放模式（音乐 / 播客） */
    val playerMode: StateFlow<PlayerMode> = appDelegate.player.playerMode

    /**
     * Shuffle 按钮是否可用（iOS: PlayerUIHandler.refreshShuffleButton:215——
     * `shuffleButton.isEnabled = settings.isPlayerShuffleButtonEnabled`）。
     * 注意与上下文菜单策略不同：菜单里是**隐藏** Shuffle 项，播放器按钮是**禁用置灰**。
     */
    val isShuffleButtonEnabled: StateFlow<Boolean> = appDelegate.settings.isPlayerShuffleButtonEnabled

    /**
     * playerMode 切换按钮是否显示（iOS: PlayerControlView.refreshPlayerModeChangeButton:438-448——
     * `isHidden = podcastItemCount == 0 && playerMode != .podcast`）
     */
    val isPlayerModeButtonVisible: StateFlow<Boolean> = combine(
        appDelegate.player.podcastQueueCount,
        appDelegate.player.playerMode
    ) { podcastCount, mode -> podcastCount > 0 || mode == PlayerMode.PODCAST }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleRepeatMode() {
        appDelegate.player.toggleRepeatMode()
    }

    fun toggleShuffle() {
        appDelegate.player.toggleShuffle()
    }

    /** 切换音乐 / 播客模式（iOS: PlayerControlView.playerModeChangePressed:237-246） */
    fun togglePlayerMode() {
        appDelegate.player.setPlayerMode(
            if (playerMode.value == PlayerMode.PODCAST) PlayerMode.MUSIC else PlayerMode.PODCAST
        )
    }

    /**
     * Switch between COMPACT (queue list) and LARGE (artwork) display modes
     * iOS: PopupPlayerVC - switchDisplayStyleAction()
     */
    fun switchDisplayMode() {
        val newMode = when (_displayMode.value) {
            PlayerDisplayMode.COMPACT -> PlayerDisplayMode.LARGE
            PlayerDisplayMode.LARGE -> PlayerDisplayMode.COMPACT
        }
        _displayMode.value = newMode
        // 保存到 PlayerManager
        appDelegate.player.setDisplayMode(when (newMode) {
            PlayerDisplayMode.COMPACT -> "COMPACT"
            PlayerDisplayMode.LARGE -> "LARGE"
        })
    }

    // ===== 歌词菜单项（iOS: PlayerControlView.swift:509-529）=====

    /**
     * 歌词菜单项/按钮是否允许显示
     * iOS 2.1.0: LargeCurrentlyPlayingPlayerView.isLyricsButtonAllowedToDisplay（行328-331）
     * = playerMode == .music && availableApiTypes.contains(.subsonic)
     * ——2.1.0 已不再参与 isAlwaysHidePlayerLyricsButton 判定（该设置项已从 Display 页移除）。
     * Android 只有 Subsonic 后端，availableApiTypes 条件恒真；playerMode 门控照搬 iOS——
     * 播客模式下歌词按钮/菜单项隐藏。服务器是否支持歌词由 OpenSubsonic songLyrics 扩展检测
     * 在取词时短路（无歌词显示 "No Lyrics"，与 iOS 一致）。
     */
    val isLyricsButtonAllowedToDisplay: StateFlow<Boolean> =
        appDelegate.player.playerMode
            .map { it == PlayerMode.MUSIC }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                // 初值取当前模式，避免播客模式下订阅建立前先闪出歌词按钮
                appDelegate.player.playerMode.value == PlayerMode.MUSIC
            )

    /** 歌词视图当前是否显示（随设置持久化，iOS: settings.isPlayerLyricsDisplayed） */
    val isPlayerLyricsDisplayed: StateFlow<Boolean>
        get() = appDelegate.settings.isPlayerLyricsDisplayed

    /**
     * Show Lyrics
     * iOS: showLyricsAction（PlayerControlView.swift:512-521）——置位歌词显示；
     * 若当前非 Large 视图则切回 Large（对应 displayPlaylistPressed）。
     * 歌词视图本体随 Phase 6.2 实现，当前仅维护状态与视图切换。
     */
    fun showLyrics() {
        if (!appDelegate.settings.isPlayerLyricsDisplayed.value) {
            appDelegate.settings.setPlayerLyricsDisplayed(true)
            // 与可视化互斥（iOS showLyricsAction 同时置 isPlayerVisualizerDisplayed=false）
            appDelegate.settings.setPlayerVisualizerDisplayed(false)
        }
        if (_displayMode.value != PlayerDisplayMode.LARGE) {
            switchDisplayMode()
        }
    }

    /**
     * Hide Lyrics
     * iOS: hideLyricsAction（PlayerControlView.swift:523-527）
     */
    fun hideLyrics() {
        appDelegate.settings.setPlayerLyricsDisplayed(false)
    }

    /**
     * 当前歌曲歌词（Phase 6.2）
     * 对应 iOS: LargeCurrentlyPlayingPlayerView.initializeLyrics（换歌/开关切换时重新获取）
     * - 歌词显示关闭或无歌曲 → null（视图不显示）
     * - 获取成功 → 首选 synced 块（getFirstSyncedLyricsOrUnsyncedAsDefault）
     * - 不支持/无歌词 → NO_LYRICS 合成块（对应 iOS showLyricsAreNotAvailable）
     * 注：iOS 在歌曲同步时抓取歌词并落盘，显示时读缓存文件；Android 按需拉取 + 仓库内存缓存
     */
    val currentLyrics: StateFlow<com.amperfy.data.model.StructuredLyrics?> = combine(
        currentSong, appDelegate.settings.isPlayerLyricsDisplayed
    ) { song, displayed ->
        if (displayed) song?.id else null
    }.distinctUntilChanged()
        .flatMapLatest { songId ->
            if (songId == null) {
                flowOf(null)
            } else {
                kotlinx.coroutines.flow.flow<com.amperfy.data.model.StructuredLyrics?> {
                    emit(null) // 换歌先清空（对应 iOS lyricsView.clear()）
                    val lyrics = appDelegate.library.getLyrics(songId)
                        ?.getFirstSyncedLyricsOrUnsyncedAsDefault()
                    // 空白内容也算「无歌词」（见 [hasRealLyrics]）：否则会渲染成一屏空行，
                    // 而不是 iOS 的 "No Lyrics" 提示
                    emit(lyrics.takeIf { hasRealLyrics(it) } ?: NO_LYRICS)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 当前歌曲歌词（SongDetailsMenu "Show Lyrics" 用，iOS: EntityPreviewVC:738-752）
     * 与 currentLyrics 不同：不受 isPlayerLyricsDisplayed 开关影响——iOS 该菜单项依据
     * 同步时落盘的 lyricsRelFilePath 判断显隐；Android 无落盘缓存，按需拉取判定
     * （仓库内存缓存 + songLyrics 扩展检测短路，成本为每首歌至多一次请求）。
     * 电台/播客单集恒无歌词；无歌词 → null（菜单项隐藏，不用 NO_LYRICS 合成块）
     */
    val currentSongLyrics: StateFlow<com.amperfy.data.model.StructuredLyrics?> = currentSong
        .map { it?.takeUnless { p -> p.isRadio || p.isPodcastEpisode }?.id }
        .distinctUntilChanged()
        .flatMapLatest { songId ->
            if (songId == null) {
                flowOf(null)
            } else {
                kotlinx.coroutines.flow.flow<com.amperfy.data.model.StructuredLyrics?> {
                    emit(null) // 换歌先清空，避免上一首的可用性残留
                    emit(appDelegate.library.getLyrics(songId)?.getFirstSyncedLyricsOrUnsyncedAsDefault())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 歌词平滑滚动设置（LyricsView scrollAnimation） */
    val isLyricsSmoothScrolling: StateFlow<Boolean>
        get() = appDelegate.settings.isLyricsSmoothScrolling

    /** 当前播放位置（毫秒，供歌词活动行轮询；iOS 为 10Hz 时间观察器） */
    fun getCurrentPositionMs(): Long = appDelegate.player.getCurrentPosition()

    // ========== W7 音频可视化（iOS: LargeCurrentlyPlayingPlayerView）==========

    /** 可视化显示开关（iOS: settings.isPlayerVisualizerDisplayed） */
    val isPlayerVisualizerDisplayed: StateFlow<Boolean>
        get() = appDelegate.settings.isPlayerVisualizerDisplayed

    /** 当前可视化样式（iOS: settings.selectedVisualizerType） */
    val selectedVisualizerType: StateFlow<VisualizerType>
        get() = appDelegate.settings.selectedVisualizerType

    /**
     * 可视化按钮是否允许显示：恒真。
     *
     * iOS 的 Show/Hide Audio Visualizer 菜单项无模式门控（PlayerControlView.swift:354-380），
     * 无条件出现——与歌词按钮的 playerMode == .music 门控刻意不同，故电台/播客下同样允许。
     */
    val isVisualizerButtonAllowedToDisplay: StateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(true)

    /** 频谱快照流（供 Compose 可视化视图 collectAsState 消费） */
    val spectrum: StateFlow<Spectrum> = audioAnalyzer.spectrum

    /**
     * 大视图当前显示元素（对齐 iOS getDisplayElementBasedOnConfig）：
     * 优先级 歌词 > 可视化 > 封面（iOS 中两开关互斥，此处按优先级兜底）。
     */
    val largeDisplayElement: StateFlow<LargeDisplayElement> = combine(
        appDelegate.settings.isPlayerLyricsDisplayed,
        appDelegate.settings.isPlayerVisualizerDisplayed
    ) { lyrics, visualizer ->
        when {
            lyrics -> LargeDisplayElement.LYRICS
            visualizer -> LargeDisplayElement.VISUALIZER
            else -> LargeDisplayElement.ARTWORK
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LargeDisplayElement.ARTWORK)

    /**
     * Show Visualizer（iOS: showVisualizerAction，PlayerControlView.swift:356-370）——
     * 置位可视化、清歌词（互斥），非 LARGE 则切回 LARGE。
     */
    fun showVisualizer() {
        if (!appDelegate.settings.isPlayerVisualizerDisplayed.value) {
            appDelegate.settings.setPlayerVisualizerDisplayed(true)
            appDelegate.settings.setPlayerLyricsDisplayed(false)
        }
        if (_displayMode.value != PlayerDisplayMode.LARGE) {
            switchDisplayMode()
        }
    }

    /** Hide Visualizer（iOS: hideVisualizerAction，PlayerControlView.swift:372-380） */
    fun hideVisualizer() {
        appDelegate.settings.setPlayerVisualizerDisplayed(false)
    }

    /** 切换可视化样式（iOS: createVisualizerTypeMenu 选择项） */
    fun setVisualizerType(type: VisualizerType) {
        appDelegate.settings.setSelectedVisualizerType(type)
    }

    /** 播放器内评分显示开关（iOS: settings.isShowRating/isPlayerRatingDisplayed） */
    val isPlayerRatingDisplayed: StateFlow<Boolean>
        get() = appDelegate.settings.isPlayerRatingDisplayed

    /** Detailed Information 开关（音频徽标显隐依据） */
    val isShowDetailedInfo: StateFlow<Boolean>
        get() = appDelegate.settings.isShowDetailedInfo

    /**
     * 门控 AudioAnalyzer.isActive（CPU 归零验收项）：
     * 由 UI 侧综合「可视化开启 && PopupPlayer 可见 && LARGE && isPlaying」置位，
     * 退出播放器/转 COMPACT/暂停时必须置 false。
     */
    fun setAudioAnalyzerActive(active: Boolean) {
        audioAnalyzer.setActive(active)
    }

    /**
     * Clear user queue
     * iOS: PopupPlayerVC - clearUserQueue()
     */
    fun clearUserQueue() {
        appDelegate.player.clearUserQueue()
    }

    /**
     * Remove a song from queue
     * iOS: PopupPlayerVC - removePlayable(at:)
     */
    fun removeFromQueue(playable: com.amperfy.data.model.Playable) {
        appDelegate.player.removeFromQueue(playable)
    }

    /**
     * Play a specific song from the queue
     * iOS: 点击队列中的歌曲直接播放
     */
    fun playFromQueue(playable: com.amperfy.data.model.Playable) {
        appDelegate.player.playFromQueue(playable)
    }

    // ========== 队列行长按菜单：任意行的实体查询与动作 ==========
    // 对应 iOS: 队列行长按与当前歌曲 More 菜单同为 EntityPreviewActionBuilder.createMenu()
    // （PopupPlayer+TableViewExtension contextMenuConfigurationForRowAt）

    /** 按 id 观察库内 Song（长按菜单打开时按需订阅；电台/播客等非库内条目返回 null） */
    fun observeSong(songId: String) = appDelegate.library.observeSongById(songId)

    fun toggleFavoriteFor(song: Song) {
        val wasFavorite = song.isFavorite
        viewModelScope.launch {
            try {
                val result = appDelegate.library.toggleSongFavorite(song.id)
                if (result.isSuccess) {
                    _toastMessage.emit(
                        if (wasFavorite) "Removed from favorites" else "Added to favorites"
                    )
                } else {
                    _toastMessage.emit("Failed to update favorite status")
                }
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Toggle favorite error", e)
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }

    fun setRatingFor(song: Song, rating: Int) {
        viewModelScope.launch {
            try {
                val result = appDelegate.library.updateSongRating(song.id, rating)
                if (!result.isSuccess) {
                    _toastMessage.emit("Failed to set rating")
                }
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Set rating error", e)
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }

    fun downloadSongFor(song: Song) {
        viewModelScope.launch {
            try {
                appDelegate.downloader.downloadSong(song)
                _toastMessage.emit("Download started: ${song.title}")
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Download error", e)
                _toastMessage.emit("Failed to start download")
            }
        }
    }

    fun deleteSongCacheFor(song: Song) {
        viewModelScope.launch {
            try {
                val result = appDelegate.downloader.deleteSongCache(song)
                if (result.isSuccess) {
                    _toastMessage.emit("Cache deleted: ${song.title}")
                } else {
                    _toastMessage.emit("Failed to delete cache")
                }
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Delete cache error", e)
                _toastMessage.emit("Failed to delete cache")
            }
        }
    }

    fun addSongToPlaylist(songId: String) {
        swipeCoordinator.requestPlaylistSelector(listOf(songId))
    }

    // ========== 队列拖拽重排（Batch 3：跨 section 拖动） ==========
    // 对应iOS: PopupPlayer+TableViewExtension.moveRowAt -> PlayQueueHandler.movePlayable

    /**
     * COMPACT 队列初始滚动位置：直接定位到 Currently Playing 行
     * （对齐 iOS 打开播放器即显示当前歌曲；Previous 段结构 = header(1) + prev 行数）
     */
    fun initialQueueScrollIndex(): Int {
        val prevSize = appDelegate.player.prevQueue.value.size
        return if (prevSize > 0) 1 + prevSize else 0
    }

    /**
     * 队列行拖动提交（段内 + 跨段统一入口）
     * @param from 拖动前的段 + 段内索引
     * @param to 落点段 + 该段内的最终索引（可等于目标段原长度 = 拖到段末追加）
     */
    fun movePlayable(from: PlayerIndex, to: PlayerIndex) {
        appDelegate.player.movePlayable(from, to)
    }

    /**
     * Toggle favorite status of current song
     * 对应iOS: PopupPlayerVC.favoritePressed()
     *
     * 使用 SharedFlow 发送一次性 Toast 消息，符合事件驱动设计
     */
    fun toggleFavorite() {
        val song = currentSongDetails.value

        if (song == null) {
            viewModelScope.launch {
                _toastMessage.emit("No song playing")
            }
            return
        }

        // 记录操作前的状态，用于生成成功消息
        val wasFavorite = song.isFavorite

        // 启动异步任务切换收藏状态
        viewModelScope.launch {
            try {
                val result = appDelegate.library.toggleSongFavorite(song.id)
                if (result.isSuccess) {
                    // 无需手动刷新: currentSongDetails 由 flatMapLatest + observeSongById 驱动
                    // 库数据变化会自动触发 Flow 更新

                    // 发送成功消息
                    val message = if (wasFavorite) {
                        "Removed from favorites"
                    } else {
                        "Added to favorites"
                    }
                    _toastMessage.emit(message)
                } else {
                    val error = result.exceptionOrNull()
                    android.util.Log.e("PopupPlayerViewModel", "Toggle favorite failed: $error")
                    _toastMessage.emit("Failed to update favorite status")
                }
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Toggle favorite error", e)
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }

    /**
     * Set rating for current song (0-5 stars)
     * 对应iOS: EntityPreviewActionBuilder - Rating menu
     */
    fun setRating(rating: Int) {
        val song = currentSongDetails.value
        if (song == null) {
            viewModelScope.launch {
                _toastMessage.emit("No song playing")
            }
            return
        }

        viewModelScope.launch {
            try {
                val result = appDelegate.library.updateSongRating(song.id, rating)
                if (result.isSuccess) {
                    // 无需手动刷新: currentSongDetails 由 flatMapLatest + observeSongById 驱动
                    // 库数据变化会自动触发 Flow 更新
                    _toastMessage.emit("Rating set to $rating stars")
                } else {
                    _toastMessage.emit("Failed to set rating")
                }
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Set rating error", e)
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }

    /**
     * Download current song
     * 对应iOS: EntityPreviewActionBuilder - Download action
     */
    fun downloadSong() {
        val song = currentSongDetails.value
        if (song == null) {
            viewModelScope.launch {
                _toastMessage.emit("No song playing")
            }
            return
        }

        viewModelScope.launch {
            try {
                appDelegate.downloader.downloadSong(song)
                _toastMessage.emit("Download started: ${song.title}")
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Download error", e)
                _toastMessage.emit("Failed to start download")
            }
        }
    }

    /**
     * Delete cached file for current song
     * 对应iOS: EntityPreviewActionBuilder - Delete Cache action
     */
    fun deleteSongCache() {
        val song = currentSongDetails.value
        if (song == null) {
            viewModelScope.launch {
                _toastMessage.emit("No song playing")
            }
            return
        }

        viewModelScope.launch {
            try {
                val result = appDelegate.downloader.deleteSongCache(song)
                if (result.isSuccess) {
                    _toastMessage.emit("Cache deleted: ${song.title}")
                } else {
                    _toastMessage.emit("Failed to delete cache")
                }
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Delete cache error", e)
                _toastMessage.emit("Failed to delete cache")
            }
        }
    }

    /**
     * Clear all player queues
     * 对应iOS: PlayerControlView.createPlayerOptionsMenu - Clear Player action
     * iOS实现: player.clearQueues() - 完全清空播放器，停止播放，清空所有队列
     */
    fun clearPlayer() {
        viewModelScope.launch {
            try {
                appDelegate.player.clearAll()
                _toastMessage.emit("Player cleared")
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerViewModel", "Clear player error", e)
                _toastMessage.emit("Failed to clear player")
            }
        }
    }

    // ========== Phase 4.1 Playback Rate ==========
    // 对应iOS: PlayerControlView.createPlaybackRateMenu()

    /** 当前播放速率（持久化于 SettingsManager，PlayerManager 启动时恢复） */
    val playbackRate: StateFlow<Float> = appDelegate.player.playbackRate

    fun setPlaybackRate(rate: Float) {
        appDelegate.player.setPlaybackRate(rate)
    }

    // ========== Phase 4.2 Sleep Timer ==========
    // 对应iOS: AppDelegate.sleepTimer + createSleepTimerMenu()（AppDelegate.swift:483-583）

    /** 剩余秒数；0 = 未激活。菜单里显示剩余时间（iOS 显示触发时刻，此处显示剩余更直观） */
    val sleepTimerRemainingSeconds: StateFlow<Int> = appDelegate.player.sleepTimerRemainingSeconds

    /** 「播完当前曲后暂停」是否激活（Sleep Timer 的 End of Song 选项） */
    val isPauseAfterCurrentSong: StateFlow<Boolean> = appDelegate.player.isPauseAfterCurrentSong

    fun startSleepTimer(minutes: Int) {
        // 与「End of Song」互斥（iOS 两种状态同一入口，只能激活一种）
        appDelegate.player.setPauseAfterCurrentSong(false)
        appDelegate.player.startSleepTimer(minutes)
        viewModelScope.launch { _toastMessage.emit("Sleep timer set: $minutes min") }
    }

    /** Sleep Timer 子菜单 "End of Song"：播完当前曲后暂停 */
    fun activatePauseAfterCurrentSong() {
        appDelegate.player.cancelSleepTimer()
        appDelegate.player.setPauseAfterCurrentSong(true)
        viewModelScope.launch { _toastMessage.emit("Will pause at end of song") }
    }

    /** Sleep Timer 子菜单 "Turn Off"：关闭定时器与 End of Song */
    fun cancelSleepTimer() {
        appDelegate.player.cancelSleepTimer()
        appDelegate.player.setPauseAfterCurrentSong(false)
        viewModelScope.launch { _toastMessage.emit("Sleep timer turned off") }
    }

    // ========== Phase 4.3 Player Info ==========
    // 对应iOS: PlayerControlView.createPlayerOptionsMenu - Player Info action

    private val _showPlayerInfoDialog = MutableStateFlow(false)
    val showPlayerInfoDialog: StateFlow<Boolean> = _showPlayerInfoDialog.asStateFlow()

    fun showPlayerInfo() {
        _showPlayerInfoDialog.value = true
    }

    fun dismissPlayerInfo() {
        _showPlayerInfoDialog.value = false
    }

    /**
     * 播放器信息文本，对齐 iOS PlainDetailsVC.refresh() 的 player 分支
     * （PlainDetailsVC.swift:110-123）：Play Time（Remaining/Total）+ Queue Items（Previous/User/Next）。
     * 注：iOS 的 Player Info 不含播放模式/音频格式，此处保持一致。
     */
    fun buildPlayerInfoLines(): List<String> {
        val prev = appDelegate.player.prevQueue.value
        val user = appDelegate.player.userQueue.value
        val next = appDelegate.player.contextNextQueue.value
        val current = currentSong.value

        // Total：全部队列 + 当前曲总时长；Remaining：当前曲剩余 + User/Next 队列时长（对应 iOS queueHandler）
        val totalSeconds = prev.sumOf { it.duration } + (current?.duration ?: 0) +
            user.sumOf { it.duration } + next.sumOf { it.duration }
        val currentRemaining = if (current != null) {
            ((duration.value - currentPosition.value) / 1000L).toInt().coerceAtLeast(0)
        } else 0
        val remainingSeconds = currentRemaining + user.sumOf { it.duration } + next.sumOf { it.duration }

        return listOf(
            "Play Time",
            "Remaining: ${formatDurationLong(remainingSeconds)}",
            "Total: ${formatDurationLong(totalSeconds)}",
            "",
            "Queue Items",
            "Previous: ${prev.size}",
            "User: ${user.size}",
            "Next: ${next.size}"
        )
    }

    companion object {
        /** 播放速率档位，对应 iOS PlaybackRate.allCases（PlayerUtil.swift:119-187，0.5x–2x 共 7 档） */
        val PLAYBACK_RATES = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

        // skip 间隔已下沉到 PlayerManager.skipForward/BackwardIntervalMs()
        // （按 playerMode 分派音乐 ±10s / 播客 +30s、-15s，对应 iOS PlayerFacade.swift:237-276）

        /**
         * "No Lyrics" 合成歌词块（不可用状态，全行高亮展示）
         * 对应 iOS: showLyricsAreNotAvailable（LargeCurrentlyPlayingPlayerView.swift:146-154）
         */
        val NO_LYRICS = com.amperfy.data.model.StructuredLyrics(
            synced = false,
            line = listOf(com.amperfy.data.model.LyricsLine(value = "No Lyrics"))
        )

        /**
         * 「这首歌确实有歌词」的唯一判据——存在**至少一行非空白**。
         *
         * 假阳性有三层，逐层收口：
         * 1. `structuredLyrics` 数组为空 —— 仓库层 `lyricsList.lyrics.isEmpty()` 已挡（返回 null）；
         * 2. 数组非空但条目 `line` 为空 —— B6.1 用 `line.isNotEmpty()` 挡；
         * 3. `line` 非空但每行 `value` 都是空白 —— 本判据收口（服务器确会返回这种壳）。
         *
         * 对应 iOS `song.lyricsRelFilePath != nil`（EntityPreviewVC:771-781）：iOS 无歌词不落盘，
         * 故「文件存在」天然等价于「有真实歌词」，Android 需显式判内容。
         * 两个消费方共用本判据：当前曲 More 菜单的 Show Lyrics 显隐、播放器歌词页的 NO_LYRICS 兜底。
         */
        fun hasRealLyrics(lyrics: com.amperfy.data.model.StructuredLyrics?): Boolean =
            lyrics?.line?.any { it.value.isNotBlank() } == true

        /** 档位显示文本，对应 iOS PlaybackRate.description（"1x" 而非 "1.0x"） */
        fun formatPlaybackRate(rate: Float): String {
            val text = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()
            return "${text}x"
        }

        /** 时长格式化，对应 iOS asDurationString（"1h 3m 20s"） */
        fun formatDurationLong(totalSeconds: Int): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return when {
                h > 0 -> "${h}h ${m}m ${s}s"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }

        /** 睡眠定时器剩余时间显示（"1h 05m" / "12m 30s"） */
        fun formatSleepRemaining(totalSeconds: Int): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return when {
                h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
                m > 0 -> "${m}m ${s.toString().padStart(2, '0')}s"
                else -> "${s}s"
            }
        }
    }

    /**
     * Scroll to currently playing item in queue list
     * 对应iOS: PopupPlayerVC.scrollToCurrentlyPlayingRow()
     *
     * 经事件流通知 QueueListView 执行实际滚动（LazyListState 在 UI 层）
     */
    private val _scrollToCurrentlyPlayingEvent =
        kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToCurrentlyPlayingEvent:
        kotlinx.coroutines.flow.SharedFlow<Unit> = _scrollToCurrentlyPlayingEvent

    fun scrollToCurrentlyPlaying() {
        _scrollToCurrentlyPlayingEvent.tryEmit(Unit)
    }

    /**
     * 滑动动作配置与结果协调（删除缓存确认、播放列表选择器）
     */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /**
     * 处理队列中歌曲的滑动动作
     *
     * 刻意**不传** SwipePlayContext（单曲上下文）：播放器队列行属队列体系，
     * iOS 队列行的 Play 是跳到该行的 playerIndex（PopupPlayer+TableViewExtension
     * didSelectRowAt），而非用某个列表新建播放上下文；
     * 且 PopupPlayer 内两处菜单构造均未传 playContextCb，菜单里本就没有 Play/Shuffle
     * （见 PopupPlayerQueueView.buildQueueItemMenuItems 的说明）
     */
    fun handleSwipeAction(song: Song, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(song.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                android.util.Log.e("PopupPlayerVM", "Error handling swipe action", e)
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }

    /**
     * 将当前播放歌曲添加到播放列表（弹出选择器）
     * 对应 iOS: PopupPlayer 菜单 "Add to Playlist"
     */
    fun addCurrentSongToPlaylist() {
        val songId = currentSongDetails.value?.id ?: currentSong.value?.id ?: return
        swipeCoordinator.requestPlaylistSelector(listOf(songId))
    }

    /**
     * 将当前上下文队列（Previous + Current + Next From）添加到播放列表
     * 对应 iOS: addContextQueueToPlaylist（Phase 4.5）
     */
    fun addContextQueueToPlaylist() {
        val ids = buildList {
            addAll(appDelegate.player.prevQueue.value.map { it.id })
            currentSong.value?.let { add(it.id) }
            addAll(appDelegate.player.contextNextQueue.value.map { it.id })
        }
        if (ids.isNotEmpty()) {
            swipeCoordinator.requestPlaylistSelector(ids)
        }
    }
}

/**
 * Player display mode enum
 * iOS: PlayerDisplayStyle
 */
enum class PlayerDisplayMode {
    COMPACT,  // Show queue list
    LARGE     // Show large artwork
}

/**
 * LARGE 模式主区显示元素（对应 iOS LargeDisplayElement）：封面 / 歌词 / 可视化三态。
 * 切换逻辑见 [PopupPlayerViewModel.largeDisplayElement]（歌词 > 可视化 > 封面）。
 */
enum class LargeDisplayElement {
    ARTWORK,
    LYRICS,
    VISUALIZER
}
