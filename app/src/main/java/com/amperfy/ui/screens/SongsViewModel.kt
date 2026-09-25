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
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipePlayContext
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.ui.util.SortSectionMode
import com.amperfy.ui.util.SortSectionUtils
import com.amperfy.ui.util.SwipeActionCoordinator
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.PlayContext
import com.amperfy.data.model.PlayerMode
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.appendContextQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 歌曲排序方式。声明顺序即排序菜单顺序——对齐 iOS 2.0.0（Date Added 紧随 Name 之后）。
 * [displayName] 供 UI 展示（iOS 措辞）。
 */
/**
 * 一次性入队下载的「不提示阈值」——对应 iOS
 * `AppDelegate.maxPlayablesDownloadsToAddAtOnceWithoutWarning = 200`（AppDelegate.swift:52）。
 * 超过则先弹确认框（title "Many Songs"）。
 */
const val MANY_SONGS_WARNING_THRESHOLD = 200

/**
 * 歌曲行滑动/菜单 PLAY 一次带入播放上下文的最大歌曲数——对应 iOS
 * `SongsVC.maxPlayContextCount = 40`（SongsVC.swift:41）：上下文取当前列表自被点/被滑动曲
 * 起的 40 首（SongsVC.swift:276-288）。
 * Favorite Songs 在 iOS 就是同一个 SongsVC 挂 favorites 过滤，故与本页共用该常量。
 */
const val MAX_PLAY_CONTEXT_COUNT = 40

/**
 * 歌曲排序档 —— 值域与**声明顺序（= 菜单顺序）**逐项对齐 iOS：
 * `SongElementSortType`（FetchedResultsControllers.swift:108-116）+
 * `SongsVC.createSortButtonMenu` 的 Subsonic（非 ampache）分支
 * （SongsVC.swift:440-452：Name → Rating → Duration → Date Added）。
 *
 * 2026-08-10 删除 Android 自创的 ARTIST/ALBUM 两档（iOS 无对应项）；
 * 存量持久化值 "ARTIST"/"ALBUM" 经 [fromPersisted] 的 find 落空回退 [NAME]。
 * 收藏页的五档（多 "Starred date"）为 `FavoriteSongSortType`，见 SettingsPreferences.kt。
 */
enum class SongSortType(val displayName: String) {
    NAME("Name"),
    RATING("Rating"),
    DURATION("Duration"),
    DATE_ADDED("Date Added");

    /**
     * 该排序档的分段模式 —— 对应 iOS `SongElementSortType.asSectionIndexType`
     * （FetchedResultsControllers.swift:118-131）：
     * name→alphabet、rating→rating、duration→durationSong、addedDate→newestOrRecent（无索引）。
     *
     * 段头只有 rating 档画（SongsVC.swift:233-252 唯 rating 返回 tableSectionHeightLarge，
     * 其余档高 0）；duration 档分段只服务索引跳转与段边界线。
     */
    val sectionMode: SortSectionMode
        get() = when (this) {
            NAME -> SortSectionMode.ALPHABET
            RATING -> SortSectionMode.RATING
            DURATION -> SortSectionMode.DURATION_SONG
            DATE_ADDED -> SortSectionMode.NONE
        }

    /**
     * 行间分割线是否按段边界区分形态（段间全宽 / 段内 16dp inset）。
     * 按 Albums 页的iOS 实机观测（2026-08-10）**同构推广**：rating/duration 段间全宽；
     * name 档与 Albums 一样例外（字母组间仍 16dp inset）；addedDate 不分段。
     * 本页无独立真机判据，验收若有出入再改。
     */
    val hasFullWidthSectionDividers: Boolean
        get() = this == RATING || this == DURATION

    companion object {
        /** 解析持久化值（枚举名），无效或缺失回退 [NAME]（对齐 iOS defaultValue = .name） */
        fun fromPersisted(value: String?): SongSortType =
            entries.find { it.name == value } ?: NAME
    }
}

/**
 * 歌曲的**段头标题** —— 对应 iOS `SongsVC.tableView(_:titleForHeaderInSection:)`
 * （SongsVC.swift:253-275）：rating 档为 "N Star(s)"/"Not rated"，其余档 iOS 段头高 0，
 * Android 返回空串表示不画（含 duration 档：分段只服务索引与段边界）。
 *
 * 同一份映射也服务 Favorite Songs（iOS 就是同一个 SongsVC 挂 favorites 过滤，
 * 段头逻辑同构），故 FavoriteSongSortType 侧只做枚举转译、文案不另写一份。
 */
fun songSectionTitle(song: Song, mode: SortSectionMode): String = when (mode) {
    SortSectionMode.ALPHABET -> SortSectionUtils.alphabetSectionTitle(song.title)
    SortSectionMode.RATING -> SortSectionUtils.ratingSectionTitle(song.rating)
    // 歌曲无 year/durationAlbum 分段档（iOS SongElementSortType 无对应项）
    else -> ""
}

/**
 * 歌曲的**索引条标签** —— 对应 iOS FRC 的 `sectionIndexTitle(forSectionName:)`
 * （BasicFetchedResultsController.swift:182-200）：字母档与段头同值，rating 档取星级数字/"#"，
 * duration 档取时长桶（`IndexHeaderNameGenerator.sortByDurationSong`）。
 */
fun songIndexLabel(song: Song, mode: SortSectionMode): String = when (mode) {
    SortSectionMode.ALPHABET -> SortSectionUtils.alphabetSectionTitle(song.title)
    SortSectionMode.RATING -> SortSectionUtils.ratingIndexLabel(song.rating)
    SortSectionMode.DURATION_SONG -> SortSectionUtils.durationSongIndexLabel(song.duration)
    else -> ""
}

/**
 * 歌曲的**行间线段边界键**（相邻两行键不同 = 跨段 → 该行下方的线画全宽）。
 *
 * duration 档取**精确时长秒数**而非桶：iOS FRC 该档按 `combinedDuration` 精确值分段
 * （SongMO+CoreDataClass.swift:115-131 的排序键即分段键），故几乎每行都跨段、线几乎全是全宽，
 * 与 Albums 页实机所见一致；其余档段边界 = 段头标题（字母 / "N Stars"）。
 */
fun songRowSectionKey(song: Song, mode: SortSectionMode): String = when (mode) {
    SortSectionMode.DURATION_SONG -> song.duration.toString()
    else -> songSectionTitle(song, mode)
}

data class SongsUiState(
    val sortType: SongSortType = SongSortType.NAME,
    val searchText: String = "",
    val isSearchActive: Boolean = false
)

@HiltViewModel
class SongsViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    // 排序方式从设置读取（设备级持久化，对应 iOS settings.user.songsSortSetting）
    private val _uiState = MutableStateFlow(
        SongsUiState(
            sortType = SongSortType.fromPersisted(appDelegate.settings.songsSortSetting.value)
        )
    )
    val uiState: StateFlow<SongsUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // 播放器状态
    val isPlaying = appDelegate.player.isPlaying
    val currentPlayingSong = appDelegate.player.currentSong

    // 下载进度
    val downloadProgressMap = appDelegate.downloader.downloadProgressMap

    // 使用 Eagerly 立即开始收集，避免进入页面时显示空列表
    private val allSongs: StateFlow<List<Song>> = appDelegate.library.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredSongs: StateFlow<List<Song>> = combine(
        allSongs,
        _uiState
    ) { songs, state ->
        filterAndSortSongs(songs, state)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 切换排序并持久化（对应 iOS SongsVC saveSortPreference） */
    fun changeSortType(sortType: SongSortType) {
        _uiState.update { it.copy(sortType = sortType) }
        appDelegate.settings.setSongsSortSetting(sortType.name)
    }

    fun updateSearchText(text: String) {
        _uiState.update { it.copy(searchText = text, isSearchActive = text.isNotEmpty()) }
    }

    /**
     * 滑动动作配置与结果协调（删除缓存确认、播放列表选择器）
     */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val isOfflineMode = appDelegate.settings.isOfflineMode

    /**
     * 歌曲行滑动 / 长按菜单动作。
     *
     * PLAY / PLAY_SHUFFLED 带上本页真实播放上下文（对应 iOS SongsVC.swift:276-288
     * convertIndexPathToPlayContext）；其余动作与上下文无关。
     */
    fun handleSwipeAction(song: Song, action: SwipeActionType) {
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(
                    song.handleSwipeAction(action, appDelegate, buildPlayContext(song))
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error handling swipe action", e)
            }
        }
    }

    /**
     * 构造滑动/菜单播放上下文：当前展示列表（排序 + 搜索过滤后）自被滑动曲起截
     * [MAX_PLAY_CONTEXT_COUNT] 首、起始索引 0，上下文名 = iOS filterTitle "Songs"
     * （SongsVC.swift:132）。列表刚刷新导致被滑动曲已不在其中时返回 null，
     * 由 handleSwipeAction 退化为单曲上下文（对齐 iOS 的 `else { PlayContext(containable:) }`）。
     */
    private fun buildPlayContext(song: Song): SwipePlayContext? {
        val list = filteredSongs.value
        val index = list.indexOfFirst { it.id == song.id }
        if (index < 0) return null
        return SwipePlayContext(
            contextType = PlayContextType.NONE,
            contextId = null,
            contextName = "Songs",
            songs = list.drop(index).take(MAX_PLAY_CONTEXT_COUNT),
            startIndex = 0
        )
    }

    /**
     * 处理下拉刷新 - 对应iOS的handleRefresh（SongsVC.swift:412-427）
     *
     * iOS 走 AutoDownloadLibrarySyncer.syncNewestLibraryElements()（同步最新专辑 +
     * 对新专辑扇出同步歌曲），Android 同名仓库方法对齐该语义；
     * 离线模式直接结束刷新（对齐 iOS isOnlineMode 守卫）
     */
    fun handleRefresh() {
        viewModelScope.launch {
            if (appDelegate.settings.isOfflineMode.value) {
                _isRefreshing.value = false
                return@launch
            }
            _isRefreshing.value = true
            try {
                val newestSongs = appDelegate.library
                    .syncNewestLibraryElements()
                    .getOrNull()
                    .orEmpty()
                // Auto cache latest Songs（Settings→Account→Auto Cache）：新出现专辑的歌曲自动下载
                // 对应 iOS AutoDownloadLibrarySyncer.swift:73-80
                // 该设置为账户级（AccountSettingsStore），须按 active 账户 ident 读；
                // 读全局旧键会导致设置页开关对当前账户不生效
                val autoCacheEnabled = appDelegate.accountSettings
                    .settings(appDelegate.accounts.activeAccountId ?: "")
                    .value.isAutoCacheLatestSongs
                if (newestSongs.isNotEmpty() && autoCacheEnabled) {
                    appDelegate.downloader.downloadSongs(newestSongs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Refresh failed", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 播放功能
    // iOS: maxPlayContextCount = 40，只取从点击位置开始的有限首歌
    // ═══════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "SongsViewModel"
        // MAX_PLAY_CONTEXT_COUNT 上提为文件级常量（见文件头），Favorite Songs 页共用同一份
        private const val MAX_SONGS_TO_ADD_ONCE = 500
    }

    private fun Song.toPlayable() = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)

    fun playSong(song: Song) {
        viewModelScope.launch {
            val contextSongs = withContext(Dispatchers.Default) {
                val songs = filteredSongs.value
                val index = songs.indexOfFirst { it.id == song.id }
                if (index >= 0) {
                    val endIndex = minOf(index + MAX_PLAY_CONTEXT_COUNT, songs.size)
                    songs.subList(index, endIndex).map { it.toPlayable() }
                } else null
            }
            if (contextSongs != null) {
                appDelegate.player.playPlaylist(
                    songs = contextSongs,
                    startIndex = 0,
                    contextType = PlayContextType.NONE,
                    contextId = null,
                    contextName = "Songs"
                )
            }
        }
    }

    fun playAll() {
        viewModelScope.launch {
            val playables = withContext(Dispatchers.Default) {
                filteredSongs.value.take(MAX_SONGS_TO_ADD_ONCE).map { it.toPlayable() }
            }
            if (playables.isNotEmpty()) {
                appDelegate.player.playPlaylist(
                    songs = playables,
                    startIndex = 0,
                    contextType = PlayContextType.NONE,
                    contextId = null,
                    contextName = "Songs"
                )
            }
        }
    }

    fun shuffleAll() {
        viewModelScope.launch {
            val playables = withContext(Dispatchers.Default) {
                filteredSongs.value.shuffled().take(MAX_SONGS_TO_ADD_ONCE).map { it.toPlayable() }
            }
            if (playables.isNotEmpty()) {
                appDelegate.player.playPlaylist(
                    songs = playables,
                    startIndex = 0,
                    contextType = PlayContextType.NONE,
                    contextId = null,
                    contextName = "Songs"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 收藏 / 评分
    // ═══════════════════════════════════════════════════════════

    fun toggleFavorite(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appDelegate.library.toggleSongFavorite(song.id)
            } catch (e: Exception) {
                Log.e(TAG, "Toggle favorite error", e)
            }
        }
    }

    fun setSongRating(song: Song, rating: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appDelegate.library.updateSongRating(song.id, rating)
            } catch (e: Exception) {
                Log.e(TAG, "Set song rating error", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 队列操作 - 使用 SongQueueExtensions.kt
    // ═══════════════════════════════════════════════════════════

    fun addToQueueNext(song: Song) {
        song.addToQueueNext(appDelegate, viewModelScope)
    }

    fun addToQueueLater(song: Song) {
        song.addToQueueLater(appDelegate, viewModelScope)
    }

    fun insertContextQueue(song: Song) {
        song.insertContextQueue(appDelegate, viewModelScope)
    }

    fun appendContextQueue(song: Song) {
        song.appendContextQueue(appDelegate, viewModelScope)
    }

    // ═══════════════════════════════════════════════════════════
    // 下载 / 播放列表
    // ═══════════════════════════════════════════════════════════

    /**
     * Download Songs（对应 iOS SongsVC.createActionButtonMenu，SongsVC.swift:463-496）——
     * 把**全库歌曲**入下载队列。调用方在超过 [MANY_SONGS_WARNING_THRESHOLD] 时先弹确认。
     */
    fun downloadAllSongs() {
        appDelegate.downloader.downloadSongs(allSongs.value)
    }

    /** 当前全库歌曲数（供调用方判断是否需要弹「Many Songs」确认） */
    fun allSongsCount(): Int = allSongs.value.size

    fun downloadSong(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            appDelegate.downloader.downloadSong(song)
        }
    }

    fun deleteSongCache(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            appDelegate.downloader.deleteSongCache(song)
        }
    }

    fun addToPlaylist(song: Song) {
        swipeCoordinator.requestPlaylistSelector(listOf(song.id))
    }

    private fun filterAndSortSongs(songs: List<Song>, state: SongsUiState): List<Song> {
        var result = songs

        if (state.searchText.isNotEmpty()) {
            val searchLower = state.searchText.lowercase()
            result = result.filter {
                it.title.lowercase().contains(searchLower) ||
                it.artist?.lowercase()?.contains(searchLower) == true ||
                it.album.lowercase().contains(searchLower)
            }
        }

        result = when (state.sortType) {
            // NAME：store 已按 sort_key 排序返回（P3 批次 1c 下沉）；中文标题从码点序改为拼音分区序，与右侧字母索引一致（行为变化已记文档 15）
            SongSortType.NAME -> result
            // Date Added：按服务器 created 降序，无值排末尾（对齐 iOS Song.addedDate 语义）
            SongSortType.DATE_ADDED -> result.sortedWith(
                compareByDescending<Song> { it.created != null }
                    .thenByDescending { it.created ?: Long.MIN_VALUE }
            )
            // DURATION：时长**升序**（iOS SongMO.durationSortedFetchRequest 的
            // `NSSortDescriptor(key: combinedDuration, ascending: true)`，
            // SongMO+CoreDataClass.swift:115-131）；同时长内保持入参 sort_key（标题）序。
            // 修正：原为 sortedByDescending（方向与 iOS 相反，且会让时长桶索引倒序）
            SongSortType.DURATION -> result.sortedBy { it.duration }
            SongSortType.RATING -> result.sortedByDescending { it.rating }
        }

        return result
    }
}
