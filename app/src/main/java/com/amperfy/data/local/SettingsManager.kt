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

package com.amperfy.data.local

import android.content.Context
import android.content.SharedPreferences
import com.amperfy.data.model.EqualizerSetting
import com.amperfy.data.model.HomeSection
import com.amperfy.data.model.LibraryDisplaySettings
import com.amperfy.data.model.LibraryDisplayType
import com.amperfy.data.model.SwipeActionSettings
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.VisualizerType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用设置管理器（统一版本）
 * 对应iOS: Settings类（在AppDelegate中作为全局单例）
 *
 * 整合了原SettingsData和SettingsManager的所有功能
 * 使用StateFlow提供响应式数据
 * 通过Hilt注入，可在AppDelegate和各ViewModel中使用
 *
 * ## 设置分层（对齐 iOS 2.1.0 Settings.swift 三层结构）
 *
 * iOS 的 `Settings` 拆为三个层级，Android 的对应物归属如下：
 *
 * | iOS 层 | 语义 | iOS 成员 | Android 对应物 |
 * |---|---|---|---|
 * | `AppSettings`（Settings.swift:26-46） | 装机级一次性状态，不随账户变 | 仅 3 键：`isLibrarySynced`、`isLibrarySyncInfoReadByUser`、`librarySyncVersion` | 无同名容器——归属 [CredentialsManager]（每账户 `initial_sync_completed`，即 iOS isLibrarySynced 的按账户化）；iOS librarySyncVersion 在 Android 无对应物 |
 * | `UserSettings`（Settings.swift:48-299） | 设备级用户偏好，全账户共享 | 显示/播放器/流媒体/缓存/排序等大部分键 | **本类的全部键** |
 * | `AccountSetting` | 账户级偏好，按 ident 分别存 | themePreference、isAutoCacheLatestSongs、isAutoCacheLatestPodcastEpisodes、isScrobbleStreamedItems、artworkDownloadSetting、homeSections、libraryDisplaySettings 等 | [AccountSettingsStore]（按 ident 命名空间读写） |
 *
 * **刻意简化（决策记录 2026-08-05）**：不在本类内再拆 `app.` / `user.` 两个内部对象——
 * App 层的 3 个对应物已物理分属另两个类，拆出空壳对象是纯仪式；且 `user.` 前缀改造需动
 * 数百处调用点、零行为收益。故本类即 User 层容器，**新增键一律为设备级**；若某键语义上
 * 属账户级（跟随账户切换而变），应加到 [AccountSettingsStore] 而非本类。
 *
 * 部分历史全局键（theme_preference / auto_cache_* / scrobble_streamed_items /
 * artwork_download_setting / home_sections / library_display_settings / streaming_format）
 * 已迁至账户层，本类保留它们**仅作账户级缺省源**（AccountSettingsStore 缺字段时回退读取），
 * 各自 KDoc 已单独标注，勿新增写入方、勿删除。
 *
 * 功能分组：
 * 1. 基础设置 - 离线模式、屏幕锁定等
 * 2. 显示设置 - 详细信息、时长显示等
 * 3. 主题设置 - 主题颜色、外观模式等
 * 4. 播放器设置 - 播放器UI、歌词等
 * 5. 流媒体设置 - 比特率、格式等
 * 6. 缓存设置 - 自动缓存、缓存大小等
 * 7. 音频设置 - 均衡器、ReplayGain等
 * 8. 封面设置 - 封面下载策略
 * 9. 资料库列表设置 - 各列表页排序/筛选/样式持久化
 * 10. 滑动手势设置 - 左右滑动动作配置
 */
@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("amperfy_settings", Context.MODE_PRIVATE)
    }

    private val gson = Gson()

    init {
        // 废弃键清理：iOS 2.1.0 已删除 "Music Player Lyrics Button" 设置项
        // （播放器歌词按钮显隐改由 music 模式 + Subsonic 判定，
        // LargeCurrentlyPlayingPlayerView.swift:328），Android 的 UI 与门控早已移除，
        // 本次连同属性/setter/键常量一并删除，并清掉历史装机残留的落盘值
        if (prefs.contains(LEGACY_KEY_ALWAYS_HIDE_PLAYER_LYRICS_BUTTON)) {
            prefs.edit().remove(LEGACY_KEY_ALWAYS_HIDE_PLAYER_LYRICS_BUTTON).apply()
        }
    }

    // ===== 1. 基础设置 =====

    /**
     * 离线模式
     * 对应iOS: settings.isOfflineMode
     */
    private val _isOfflineMode = MutableStateFlow(
        prefs.getBoolean(KEY_OFFLINE_MODE, false)
    )
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()

    fun setOfflineMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, enabled).apply()
        _isOfflineMode.value = enabled
    }

    /**
     * 屏幕锁定防止偏好
     * 对应iOS: settings.screenLockPreventionPreference
     */
    private val _screenLockPreventionPreference = MutableStateFlow(
        ScreenLockPreventionPreference.fromString(
            prefs.getString(KEY_SCREEN_LOCK_PREVENTION, ScreenLockPreventionPreference.NEVER.value)
                ?: ScreenLockPreventionPreference.NEVER.value
        )
    )
    val screenLockPreventionPreference: StateFlow<ScreenLockPreventionPreference> =
        _screenLockPreventionPreference.asStateFlow()

    fun setScreenLockPreventionPreference(preference: ScreenLockPreventionPreference) {
        prefs.edit().putString(KEY_SCREEN_LOCK_PREVENTION, preference.value).apply()
        _screenLockPreventionPreference.value = preference
    }

    // ===== 2. 显示设置 =====

    /**
     * 显示详细信息
     * 对应iOS: settings.isShowDetailedInfo
     */
    private val _isShowDetailedInfo = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_DETAILED_INFO, false)
    )
    val isShowDetailedInfo: StateFlow<Boolean> = _isShowDetailedInfo.asStateFlow()

    fun setShowDetailedInfo(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_DETAILED_INFO, show).apply()
        _isShowDetailedInfo.value = show
    }

    /**
     * 显示歌曲时长
     * 对应iOS: settings.isShowSongDuration
     */
    private val _isShowSongDuration = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_SONG_DURATION, false)
    )
    val isShowSongDuration: StateFlow<Boolean> = _isShowSongDuration.asStateFlow()

    fun setShowSongDuration(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SONG_DURATION, show).apply()
        _isShowSongDuration.value = show
    }

    /**
     * 显示专辑时长
     * 对应iOS: settings.isShowAlbumDuration
     */
    private val _isShowAlbumDuration = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_ALBUM_DURATION, false)
    )
    val isShowAlbumDuration: StateFlow<Boolean> = _isShowAlbumDuration.asStateFlow()

    fun setShowAlbumDuration(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ALBUM_DURATION, show).apply()
        _isShowAlbumDuration.value = show
    }

    /**
     * 显示艺术家时长
     * 对应iOS: settings.isShowArtistDuration
     */
    private val _isShowArtistDuration = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_ARTIST_DURATION, false)
    )
    val isShowArtistDuration: StateFlow<Boolean> = _isShowArtistDuration.asStateFlow()

    fun setShowArtistDuration(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ARTIST_DURATION, show).apply()
        _isShowArtistDuration.value = show
    }

    // ===== 3. 主题设置 =====

    /**
     * 主题颜色偏好
     * 对应iOS: settings.themePreference
     *
     * **仅作账户级缺省源**：iOS 2.1.0 中主题色为账户级设置（AccountSettingsView.swift:139-162），
     * Android 已随之迁至 AccountSettingsStore.themePreference；本全局键只在
     * AccountSettingsStore 缺字段回退时被读，setter 现无调用方（勿新增写入方）。
     */
    private val _themePreference = MutableStateFlow(
        ThemePreference.fromString(
            prefs.getString(KEY_THEME_PREFERENCE, ThemePreference.BLUE.value)
                ?: ThemePreference.BLUE.value
        )
    )
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    fun setThemePreference(preference: ThemePreference) {
        prefs.edit().putString(KEY_THEME_PREFERENCE, preference.value).apply()
        _themePreference.value = preference
    }

    /**
     * 外观模式（深色/浅色）
     * 对应iOS: settings.appearanceMode
     */
    private val _appearanceMode = MutableStateFlow(
        AppearanceMode.fromString(
            prefs.getString(KEY_APPEARANCE_MODE, AppearanceMode.SYSTEM.value)
                ?: AppearanceMode.SYSTEM.value
        )
    )
    val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    fun setAppearanceMode(mode: AppearanceMode) {
        prefs.edit().putString(KEY_APPEARANCE_MODE, mode.value).apply()
        _appearanceMode.value = mode
    }

    /**
     * 触觉反馈启用
     * 对应iOS: settings.isHapticsEnabled
     */
    private val _isHapticsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_HAPTICS_ENABLED, true)
    )
    val isHapticsEnabled: StateFlow<Boolean> = _isHapticsEnabled.asStateFlow()

    fun setHapticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
        _isHapticsEnabled.value = enabled
    }

    // ===== 4. 播放器设置 =====

    /**
     * 播放器随机按钮启用
     * 对应iOS: settings.isPlayerShuffleButtonEnabled
     * 默认值 false（默认隐藏 Shuffle 按钮/菜单项）——刻意差异（2026-08-01）：与 iOS 2.1.0
     * 源码默认值 true（Settings.swift:109）相异，以 iOS 实际运行观感为准
     */
    private val _isPlayerShuffleButtonEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_PLAYER_SHUFFLE_BUTTON, false)
    )
    val isPlayerShuffleButtonEnabled: StateFlow<Boolean> = _isPlayerShuffleButtonEnabled.asStateFlow()

    fun setPlayerShuffleButtonEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_SHUFFLE_BUTTON, enabled).apply()
        _isPlayerShuffleButtonEnabled.value = enabled
    }

    /**
     * 显示音乐播放器跳过按钮
     * 对应iOS: settings.isShowMusicPlayerSkipButtons
     */
    private val _isShowMusicPlayerSkipButtons = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_SKIP_BUTTONS, false)
    )
    val isShowMusicPlayerSkipButtons: StateFlow<Boolean> = _isShowMusicPlayerSkipButtons.asStateFlow()

    fun setShowMusicPlayerSkipButtons(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SKIP_BUTTONS, show).apply()
        _isShowMusicPlayerSkipButtons.value = show
    }

    /**
     * 播放器显示样式（LARGE = 大封面播放器 / COMPACT = 队列列表态）
     * 对应iOS: settings.user.playerDisplayStyle（Settings.swift:287，UserSettings 设备级）
     *
     * 此前 Android 误把它混在 playback_state 播放状态快照里（Room `display_mode` 列）——
     * 显示样式是用户偏好而非播放状态，随「清空播放状态」丢失不合理。现迁至本类；
     * 旧值一次性回读迁移见 PlayerManager 恢复路径（[isPlayerDisplayStyleSet] 判定）。
     */
    private val _playerDisplayStyle = MutableStateFlow(
        prefs.getString(KEY_PLAYER_DISPLAY_STYLE, PLAYER_DISPLAY_STYLE_LARGE)
            ?: PLAYER_DISPLAY_STYLE_LARGE
    )
    val playerDisplayStyle: StateFlow<String> = _playerDisplayStyle.asStateFlow()

    fun setPlayerDisplayStyle(style: String) {
        prefs.edit().putString(KEY_PLAYER_DISPLAY_STYLE, style).apply()
        _playerDisplayStyle.value = style
    }

    /**
     * 键是否已落盘：供 PlayerManager 做「旧 Room display_mode 值一次性迁移」的判定
     * （键不存在才回读旧列并固化，存在则不覆盖用户偏好）
     */
    fun isPlayerDisplayStyleSet(): Boolean = prefs.contains(KEY_PLAYER_DISPLAY_STYLE)

    /**
     * 播放器当前是否显示歌词视图（播放器内切换的状态，随设置持久化）
     * 对应iOS: settings.isPlayerLyricsDisplayed（PersistentStorage.swift:774-783）
     * 隐藏歌词按钮时 iOS 会同时强制其为 false（DisplaySettingsView.swift:52-57）
     */
    private val _isPlayerLyricsDisplayed = MutableStateFlow(
        prefs.getBoolean(KEY_PLAYER_LYRICS_DISPLAYED, false)
    )
    val isPlayerLyricsDisplayed: StateFlow<Boolean> = _isPlayerLyricsDisplayed.asStateFlow()

    fun setPlayerLyricsDisplayed(displayed: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_LYRICS_DISPLAYED, displayed).apply()
        _isPlayerLyricsDisplayed.value = displayed
    }

    /**
     * 歌词平滑滚动
     * 对应iOS: settings.isLyricsSmoothScrolling
     */
    private val _isLyricsSmoothScrolling = MutableStateFlow(
        prefs.getBoolean(KEY_LYRICS_SMOOTH_SCROLLING, true)
    )
    val isLyricsSmoothScrolling: StateFlow<Boolean> = _isLyricsSmoothScrolling.asStateFlow()

    fun setLyricsSmoothScrolling(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRICS_SMOOTH_SCROLLING, enabled).apply()
        _isLyricsSmoothScrolling.value = enabled
    }

    /**
     * 仅在播放时开始回放
     * 对应iOS: settings.isPlaybackStartOnlyOnPlay
     */
    private val _isPlaybackStartOnlyOnPlay = MutableStateFlow(
        prefs.getBoolean(KEY_PLAYBACK_START_ONLY_ON_PLAY, false)
    )
    val isPlaybackStartOnlyOnPlay: StateFlow<Boolean> = _isPlaybackStartOnlyOnPlay.asStateFlow()

    fun setPlaybackStartOnlyOnPlay(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYBACK_START_ONLY_ON_PLAY, enabled).apply()
        _isPlaybackStartOnlyOnPlay.value = enabled
    }

    // ===== 5. 流媒体设置 =====

    /**
     * WiFi流媒体最大比特率
     * 对应iOS: settings.streamingMaxBitrateWifiPreference（默认 noLimit）
     * 生效于 MusicRepository.getStreamUrl 的 maxBitRate 参数（按当前网络二选一）
     */
    private val _streamingMaxBitrateWifiPreference = MutableStateFlow(
        StreamingMaxBitratePreference.fromString(
            prefs.getString(KEY_STREAMING_MAX_BITRATE_WIFI, StreamingMaxBitratePreference.NO_LIMIT.value)
                ?: StreamingMaxBitratePreference.NO_LIMIT.value
        )
    )
    val streamingMaxBitrateWifiPreference: StateFlow<StreamingMaxBitratePreference> =
        _streamingMaxBitrateWifiPreference.asStateFlow()

    fun setStreamingMaxBitrateWifiPreference(preference: StreamingMaxBitratePreference) {
        prefs.edit().putString(KEY_STREAMING_MAX_BITRATE_WIFI, preference.value).apply()
        _streamingMaxBitrateWifiPreference.value = preference
    }

    /**
     * 蜂窝网络流媒体最大比特率
     * 对应iOS: settings.streamingMaxBitrateCellularPreference（默认 noLimit）
     */
    private val _streamingMaxBitrateCellularPreference = MutableStateFlow(
        StreamingMaxBitratePreference.fromString(
            prefs.getString(KEY_STREAMING_MAX_BITRATE_CELLULAR, StreamingMaxBitratePreference.NO_LIMIT.value)
                ?: StreamingMaxBitratePreference.NO_LIMIT.value
        )
    )
    val streamingMaxBitrateCellularPreference: StateFlow<StreamingMaxBitratePreference> =
        _streamingMaxBitrateCellularPreference.asStateFlow()

    fun setStreamingMaxBitrateCellularPreference(preference: StreamingMaxBitratePreference) {
        prefs.edit().putString(KEY_STREAMING_MAX_BITRATE_CELLULAR, preference.value).apply()
        _streamingMaxBitrateCellularPreference.value = preference
    }

    /**
     * 流媒体格式（单一偏好，不分 WiFi/蜂窝——iOS 1.2.3 即如此）
     * 对应iOS: settings.streamingFormatPreference（默认 mp3）
     * 生效于 MusicRepository.getStreamUrl 的 format 参数
     */
    private val _streamingFormatPreference = MutableStateFlow(
        StreamingFormatPreference.fromString(
            prefs.getString(KEY_STREAMING_FORMAT, StreamingFormatPreference.MP3.value)
                ?: StreamingFormatPreference.MP3.value
        )
    )
    val streamingFormatPreference: StateFlow<StreamingFormatPreference> =
        _streamingFormatPreference.asStateFlow()

    fun setStreamingFormatPreference(preference: StreamingFormatPreference) {
        prefs.edit().putString(KEY_STREAMING_FORMAT, preference.value).apply()
        _streamingFormatPreference.value = preference
    }

    /**
     * 流媒体格式按 WiFi/蜂窝分别配置（iOS 2.0.0；C0 只加键，W4 接 getStreamUrl、W7 接 UI）
     * 旧单一键 [streamingFormatPreference] 的值作为两键的初始值（读时迁移），
     * 全量接线后旧键仅作缺省值保留
     */
    private val _streamingFormatWifiPreference = MutableStateFlow(
        StreamingFormatPreference.fromString(
            prefs.getString(KEY_STREAMING_FORMAT_WIFI, null)
                ?: prefs.getString(KEY_STREAMING_FORMAT, StreamingFormatPreference.MP3.value)
                ?: StreamingFormatPreference.MP3.value
        )
    )
    val streamingFormatWifiPreference: StateFlow<StreamingFormatPreference> =
        _streamingFormatWifiPreference.asStateFlow()

    fun setStreamingFormatWifiPreference(preference: StreamingFormatPreference) {
        prefs.edit().putString(KEY_STREAMING_FORMAT_WIFI, preference.value).apply()
        _streamingFormatWifiPreference.value = preference
    }

    private val _streamingFormatCellularPreference = MutableStateFlow(
        StreamingFormatPreference.fromString(
            prefs.getString(KEY_STREAMING_FORMAT_CELLULAR, null)
                ?: prefs.getString(KEY_STREAMING_FORMAT, StreamingFormatPreference.MP3.value)
                ?: StreamingFormatPreference.MP3.value
        )
    )
    val streamingFormatCellularPreference: StateFlow<StreamingFormatPreference> =
        _streamingFormatCellularPreference.asStateFlow()

    fun setStreamingFormatCellularPreference(preference: StreamingFormatPreference) {
        prefs.edit().putString(KEY_STREAMING_FORMAT_CELLULAR, preference.value).apply()
        _streamingFormatCellularPreference.value = preference
    }

    // ===== 6. 缓存设置 =====

    /**
     * 自动缓存最新歌曲
     * 对应iOS: settings.isAutoCacheLatestSongs
     *
     * **仅作账户级缺省源**：实际读写在 AccountSettingsStore（Settings→Account→Auto Cache），
     * setter 现无调用方。
     */
    private val _isAutoCacheLatestSongs = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_CACHE_LATEST_SONGS, false)
    )
    val isAutoCacheLatestSongs: StateFlow<Boolean> = _isAutoCacheLatestSongs.asStateFlow()

    fun setAutoCacheLatestSongs(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CACHE_LATEST_SONGS, enabled).apply()
        _isAutoCacheLatestSongs.value = enabled
    }

    /**
     * 自动缓存最新播客剧集
     * 对应iOS: settings.isAutoDownloadLatestPodcastEpisodesActive
     * （AutoDownloadLibrarySyncer.swift:92）
     *
     * **仅作账户级缺省源**：UI 入口在 Settings→Account→Auto Cache（读写 AccountSettingsStore）；
     * Android 暂无播客单集下载缓存（已知简化），故账户层值亦暂无消费方。
     */
    private val _isAutoCacheLatestPodcastEpisodes = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_CACHE_PODCAST_EPISODES, false)
    )
    val isAutoCacheLatestPodcastEpisodes: StateFlow<Boolean> = _isAutoCacheLatestPodcastEpisodes.asStateFlow()

    fun setAutoCacheLatestPodcastEpisodes(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CACHE_PODCAST_EPISODES, enabled).apply()
        _isAutoCacheLatestPodcastEpisodes.value = enabled
    }

    /**
     * 播客列表显示模式（Podcasts / Episodes sorted by release date）
     * 对应iOS: settings.podcastsShowSetting（PodcastsVC 排序菜单切换并持久化）
     */
    private val _podcastsShowSetting = MutableStateFlow(
        PodcastsShowType.fromString(
            prefs.getString(KEY_PODCASTS_SHOW_SETTING, PodcastsShowType.PODCASTS.value)
                ?: PodcastsShowType.PODCASTS.value
        )
    )
    val podcastsShowSetting: StateFlow<PodcastsShowType> = _podcastsShowSetting.asStateFlow()

    fun setPodcastsShowSetting(type: PodcastsShowType) {
        prefs.edit().putString(KEY_PODCASTS_SHOW_SETTING, type.value).apply()
        _podcastsShowSetting.value = type
    }

    /**
     * 收藏歌曲排序方式（Name/Rating/Duration/Starred date/Date Added，
     * 顺序对齐 iOS SongsVC.swift:440-446 的 favorites 分支）
     * 对应iOS: settings.favoriteSongSortSetting（SongsVC displayFilter=.favorites 时的
     * Sort 菜单持久化，saveSortPreference）
     *
     * **默认值 = Starred date**（iOS `SongElementSortType.defaultValueForFavorite = .starredDate`，
     * FetchedResultsControllers.swift:116）；2026-08-10 由 NAME 改正，未显式选过排序的用户
     * 首次进页会看到按收藏时间倒序（= iOS 行为）
     */
    private val _favoriteSongsSortSetting = MutableStateFlow(
        FavoriteSongSortType.fromString(
            prefs.getString(KEY_FAVORITE_SONGS_SORT_SETTING, FavoriteSongSortType.STARRED_DATE.value)
                ?: FavoriteSongSortType.STARRED_DATE.value
        )
    )
    val favoriteSongsSortSetting: StateFlow<FavoriteSongSortType> =
        _favoriteSongsSortSetting.asStateFlow()

    fun setFavoriteSongsSortSetting(type: FavoriteSongSortType) {
        prefs.edit().putString(KEY_FAVORITE_SONGS_SORT_SETTING, type.value).apply()
        _favoriteSongsSortSetting.value = type
    }

    /**
     * 播放器自动缓存播放项目
     * 对应iOS: BackendAudioPlayer.isAutoCachePlayedItems
     *
     * 当启用时，播放流式音频会同时触发后台下载
     * 下载完成后，后续播放将使用本地缓存
     */
    private val _isPlayerAutoCachePlayedItems = MutableStateFlow(
        prefs.getBoolean(KEY_PLAYER_AUTO_CACHE_PLAYED, true)  // iOS默认启用
    )
    val isPlayerAutoCachePlayedItems: StateFlow<Boolean> = _isPlayerAutoCachePlayedItems.asStateFlow()

    fun setPlayerAutoCachePlayedItems(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_AUTO_CACHE_PLAYED, enabled).apply()
        _isPlayerAutoCachePlayedItems.value = enabled
    }

    /**
     * 缓存大小限制 (MB)，0 = 不限制（对应 iOS settings.cacheLimit，默认 0 = No Limit）
     * 超限时 DownloadManager 拒绝新下载（与 iOS 一致，无 LRU 淘汰）
     */
    private val _cacheSizeLimitMB = MutableStateFlow(
        prefs.getInt(KEY_CACHE_SIZE_LIMIT, DEFAULT_CACHE_SIZE_MB)
    )
    val cacheSizeLimitMB: StateFlow<Int> = _cacheSizeLimitMB.asStateFlow()

    fun setCacheSizeLimitMB(limitMB: Int) {
        prefs.edit().putInt(KEY_CACHE_SIZE_LIMIT, limitMB).apply()
        _cacheSizeLimitMB.value = limitMB
    }

    /**
     * 获取缓存大小限制（字节）
     */
    fun getCacheSizeLimitBytes(): Long {
        return _cacheSizeLimitMB.value.toLong() * 1024 * 1024
    }

    /**
     * 缓存转码格式偏好
     * 对应iOS: settings.cacheTranscodingFormatPreference（默认 mp3；raw 走 download 端点）
     * 生效于 DownloadManager.executeDownload 的下载 URL 构建
     */
    private val _cacheTranscodingFormatPreference = MutableStateFlow(
        CacheTranscodingFormatPreference.fromString(
            prefs.getString(KEY_CACHE_TRANSCODING_FORMAT, CacheTranscodingFormatPreference.MP3.value)
                ?: CacheTranscodingFormatPreference.MP3.value
        )
    )
    val cacheTranscodingFormatPreference: StateFlow<CacheTranscodingFormatPreference> =
        _cacheTranscodingFormatPreference.asStateFlow()

    fun setCacheTranscodingFormatPreference(preference: CacheTranscodingFormatPreference) {
        prefs.edit().putString(KEY_CACHE_TRANSCODING_FORMAT, preference.value).apply()
        _cacheTranscodingFormatPreference.value = preference
    }

    // ===== 7. 音频设置 =====

    /**
     * Scrobble流媒体项目
     * 对应iOS: settings.isScrobbleStreamedItems
     *
     * **仅作账户级缺省源**：实际读写在 AccountSettingsStore（Settings→Account），
     * 消费方 ScrobbleSyncer 读账户层；setter 现无调用方。
     */
    private val _isScrobbleStreamedItems = MutableStateFlow(
        prefs.getBoolean(KEY_SCROBBLE_STREAMED_ITEMS, false)
    )
    val isScrobbleStreamedItems: StateFlow<Boolean> = _isScrobbleStreamedItems.asStateFlow()

    fun setScrobbleStreamedItems(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCROBBLE_STREAMED_ITEMS, enabled).apply()
        _isScrobbleStreamedItems.value = enabled
    }

    /**
     * 播放速率（0.5x–2.0x）
     * 对应iOS: settings.playbackRate（PlayerControlView.createPlaybackRateMenu 选择后持久化）
     */
    private val _playbackRate = MutableStateFlow(
        prefs.getFloat(KEY_PLAYBACK_RATE, 1.0f)
    )
    val playbackRate: StateFlow<Float> = _playbackRate.asStateFlow()

    fun setPlaybackRate(rate: Float) {
        prefs.edit().putFloat(KEY_PLAYBACK_RATE, rate).apply()
        _playbackRate.value = rate
    }

    /**
     * 均衡器启用（iOS 1.2.3 无此功能，设置键保留但不在 UI 暴露；Backlog：Android 特有均衡器）
     */
    private val _isEqualizerEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_EQUALIZER_ENABLED, false)
    )
    val isEqualizerEnabled: StateFlow<Boolean> = _isEqualizerEnabled.asStateFlow()

    fun setEqualizerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EQUALIZER_ENABLED, enabled).apply()
        _isEqualizerEnabled.value = enabled
    }

    /**
     * ReplayGain启用（iOS 1.2.3 无此功能——isReplayGainEnabled 不存在于 1.2.3 源码，
     * 为更高版本引入；设置键保留但不在 UI 暴露）
     */
    private val _isReplayGainEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_REPLAY_GAIN_ENABLED, true)
    )
    val isReplayGainEnabled: StateFlow<Boolean> = _isReplayGainEnabled.asStateFlow()

    fun setReplayGainEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REPLAY_GAIN_ENABLED, enabled).apply()
        _isReplayGainEnabled.value = enabled
    }

    /**
     * 均衡器档位列表（C0 合同键，键名冻结 `equalizerSettings`；
     * W2 接 EqualizerController、W7 接 EqualizerSettingsScreen）
     */
    private val _equalizerSettings = MutableStateFlow(loadEqualizerSettings())
    val equalizerSettings: StateFlow<List<EqualizerSetting>> = _equalizerSettings.asStateFlow()

    private fun loadEqualizerSettings(): List<EqualizerSetting> {
        val json = prefs.getString(KEY_EQUALIZER_SETTINGS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<EqualizerSetting>>() {}.type)
        } catch (e: Exception) {
            android.util.Log.e("SettingsManager", "Failed to parse equalizer settings, falling back to empty", e)
            emptyList()
        }
    }

    fun setEqualizerSettings(settings: List<EqualizerSetting>) {
        prefs.edit().putString(KEY_EQUALIZER_SETTINGS, gson.toJson(settings)).apply()
        _equalizerSettings.value = settings
    }

    /**
     * 当前激活的均衡器档位 id（键名冻结 `activeEqualizerSettingId`；空 = Off）
     */
    private val _activeEqualizerSettingId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE_EQUALIZER_SETTING_ID, "") ?: ""
    )
    val activeEqualizerSettingId: StateFlow<String> = _activeEqualizerSettingId.asStateFlow()

    fun setActiveEqualizerSettingId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_EQUALIZER_SETTING_ID, id).apply()
        _activeEqualizerSettingId.value = id
    }

    /**
     * 播放器内音频可视化显示开关（iOS 2.0.0: isPlayerVisualizerDisplayed；C0 只加键，W7 接 UI）
     */
    private val _isPlayerVisualizerDisplayed = MutableStateFlow(
        prefs.getBoolean(KEY_PLAYER_VISUALIZER_DISPLAYED, false)
    )
    val isPlayerVisualizerDisplayed: StateFlow<Boolean> = _isPlayerVisualizerDisplayed.asStateFlow()

    fun setPlayerVisualizerDisplayed(displayed: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_VISUALIZER_DISPLAYED, displayed).apply()
        _isPlayerVisualizerDisplayed.value = displayed
    }

    /**
     * 可视化样式（iOS 2.0.0: selectedVisualizerType；**只存 String value**，
     * 与 iOS String rawValue 一致——格式合同冻结，见 VisualizerType）
     */
    private val _selectedVisualizerType = MutableStateFlow(
        VisualizerType.fromValue(
            prefs.getString(KEY_SELECTED_VISUALIZER_TYPE, VisualizerType.RING.value)
                ?: VisualizerType.RING.value
        )
    )
    val selectedVisualizerType: StateFlow<VisualizerType> = _selectedVisualizerType.asStateFlow()

    fun setSelectedVisualizerType(type: VisualizerType) {
        prefs.edit().putString(KEY_SELECTED_VISUALIZER_TYPE, type.value).apply()
        _selectedVisualizerType.value = type
    }

    /**
     * 播放器内五星评分显示（iOS 2.1.0: isPlayerRatingDisplayed，默认 false 对齐 "optional"；
     * C0 只加键，W7 接 RatingView 与 DisplaySettingsScreen 开关）
     */
    private val _isPlayerRatingDisplayed = MutableStateFlow(
        prefs.getBoolean(KEY_PLAYER_RATING_DISPLAYED, false)
    )
    val isPlayerRatingDisplayed: StateFlow<Boolean> = _isPlayerRatingDisplayed.asStateFlow()

    fun setPlayerRatingDisplayed(displayed: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_RATING_DISPLAYED, displayed).apply()
        _isPlayerRatingDisplayed.value = displayed
    }

    /**
     * 保存并恢复单曲播放进度（iOS 2.1.0: isRememberSongPlaybackProgress，默认 false；
     * C0 只加键，W4 接进度保存逻辑、W7 接 PlayerSettingsScreen 开关）
     */
    private val _isRememberSongPlaybackProgress = MutableStateFlow(
        prefs.getBoolean(KEY_REMEMBER_SONG_PLAYBACK_PROGRESS, false)
    )
    val isRememberSongPlaybackProgress: StateFlow<Boolean> = _isRememberSongPlaybackProgress.asStateFlow()

    fun setRememberSongPlaybackProgress(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMEMBER_SONG_PLAYBACK_PROGRESS, enabled).apply()
        _isRememberSongPlaybackProgress.value = enabled
    }

    // ===== 8. 封面设置 =====

    /**
     * 封面下载策略
     * 对应iOS: settings.artworkDownloadSetting（默认 Download only once）
     */
    private val _artworkDownloadSetting = MutableStateFlow(
        ArtworkDownloadSetting.fromString(
            prefs.getString(KEY_ARTWORK_DOWNLOAD_SETTING, ArtworkDownloadSetting.ONLY_ONCE.value)
                ?: ArtworkDownloadSetting.ONLY_ONCE.value
        )
    )
    val artworkDownloadSetting: StateFlow<ArtworkDownloadSetting> = _artworkDownloadSetting.asStateFlow()

    fun setArtworkDownloadSetting(setting: ArtworkDownloadSetting) {
        prefs.edit().putString(KEY_ARTWORK_DOWNLOAD_SETTING, setting.value).apply()
        _artworkDownloadSetting.value = setting
    }

    // ===== 9. 资料库列表设置 =====

    /**
     * 播放列表排序方式（存 PlaylistSortType 枚举名，UI 层解析）
     * 对应 iOS: settings.playlistsSortSetting（PlaylistsVC.swift:39、92）
     */
    private val _playlistsSortSetting = MutableStateFlow(
        prefs.getString(KEY_PLAYLISTS_SORT_SETTING, "NAME") ?: "NAME"
    )
    val playlistsSortSetting: StateFlow<String> = _playlistsSortSetting.asStateFlow()

    fun setPlaylistsSortSetting(value: String) {
        prefs.edit().putString(KEY_PLAYLISTS_SORT_SETTING, value).apply()
        _playlistsSortSetting.value = value
    }

    /**
     * 艺术家排序方式（存 ArtistSortType 枚举名，UI 层解析）
     * 对应 iOS: settings.user.artistsSortSetting（Settings.swift:233-236，
     * 默认 ArtistElementSortType.defaultValue = .name）
     */
    private val _artistsSortSetting = MutableStateFlow(
        prefs.getString(KEY_ARTISTS_SORT_SETTING, SORT_NAME) ?: SORT_NAME
    )
    val artistsSortSetting: StateFlow<String> = _artistsSortSetting.asStateFlow()

    fun setArtistsSortSetting(value: String) {
        prefs.edit().putString(KEY_ARTISTS_SORT_SETTING, value).apply()
        _artistsSortSetting.value = value
    }

    /**
     * 专辑排序方式（存 AlbumSortType 枚举名，UI 层解析）
     * 对应 iOS: settings.user.albumsSortSetting（Settings.swift:239-242，
     * 默认 AlbumElementSortType.defaultValue = .name）
     *
     * 注意：Newest/Recent 两个导航入口有强制排序（iOS applyFilter 同语义），不读本键
     */
    private val _albumsSortSetting = MutableStateFlow(
        prefs.getString(KEY_ALBUMS_SORT_SETTING, SORT_NAME) ?: SORT_NAME
    )
    val albumsSortSetting: StateFlow<String> = _albumsSortSetting.asStateFlow()

    fun setAlbumsSortSetting(value: String) {
        prefs.edit().putString(KEY_ALBUMS_SORT_SETTING, value).apply()
        _albumsSortSetting.value = value
    }

    /**
     * 歌曲排序方式（存 SongSortType 枚举名，UI 层解析）
     * 对应 iOS: settings.user.songsSortSetting（Settings.swift:251-260，
     * 默认 SongElementSortType.defaultValue = .name；收藏页另有
     * [favoriteSongsSortSetting] 独立键，对应 iOS defaultValueForFavorite）
     */
    private val _songsSortSetting = MutableStateFlow(
        prefs.getString(KEY_SONGS_SORT_SETTING, SORT_NAME) ?: SORT_NAME
    )
    val songsSortSetting: StateFlow<String> = _songsSortSetting.asStateFlow()

    fun setSongsSortSetting(value: String) {
        prefs.edit().putString(KEY_SONGS_SORT_SETTING, value).apply()
        _songsSortSetting.value = value
    }

    /**
     * 艺术家分类筛选（存 ArtistDisplayFilter 枚举名，UI 层解析）
     * 对应 iOS: settings.user.artistsFilterSetting（Settings.swift:269-272，
     * 默认 ArtistCategoryFilter.defaultValue = .albumArtists）
     *
     * 注意：带导航参数进入（artists?filter=favorites）时导航参数优先，不读本键
     */
    private val _artistsFilterSetting = MutableStateFlow(
        prefs.getString(KEY_ARTISTS_FILTER_SETTING, ARTISTS_FILTER_ALBUM_ARTISTS)
            ?: ARTISTS_FILTER_ALBUM_ARTISTS
    )
    val artistsFilterSetting: StateFlow<String> = _artistsFilterSetting.asStateFlow()

    fun setArtistsFilterSetting(value: String) {
        prefs.edit().putString(KEY_ARTISTS_FILTER_SETTING, value).apply()
        _artistsFilterSetting.value = value
    }

    /**
     * 专辑列表显示样式（存 AlbumStyleType 枚举名：TABLE / GRID，UI 层解析）
     * 对应 iOS: settings.user.albumsStyleSetting（Settings.swift:275-278）
     *
     * **默认 GRID**：对齐 iOS AlbumsDisplayStyle.defaultValue = .grid
     * （FetchedResultsControllers.swift:174）；Android 此前内存默认 TABLE 属未对齐残留
     */
    private val _albumsStyleSetting = MutableStateFlow(
        prefs.getString(KEY_ALBUMS_STYLE_SETTING, ALBUMS_STYLE_GRID) ?: ALBUMS_STYLE_GRID
    )
    val albumsStyleSetting: StateFlow<String> = _albumsStyleSetting.asStateFlow()

    fun setAlbumsStyleSetting(value: String) {
        prefs.edit().putString(KEY_ALBUMS_STYLE_SETTING, value).apply()
        _albumsStyleSetting.value = value
    }

    /**
     * 专辑网格列数（2..5）
     * 对应 iOS: settings.user.albumsGridSizeSetting（Settings.swift:293-299）
     *
     * iOS 默认按设备分叉：iPad 4、iPhone 3；Android **不做该分叉**（平板暂无独立布局分支），
     * 统一取手机默认值 3
     */
    private val _albumsGridSizeSetting = MutableStateFlow(
        prefs.getInt(KEY_ALBUMS_GRID_SIZE_SETTING, DEFAULT_ALBUMS_GRID_SIZE)
    )
    val albumsGridSizeSetting: StateFlow<Int> = _albumsGridSizeSetting.asStateFlow()

    fun setAlbumsGridSizeSetting(size: Int) {
        prefs.edit().putInt(KEY_ALBUMS_GRID_SIZE_SETTING, size).apply()
        _albumsGridSizeSetting.value = size
    }

    // ===== 10. 滑动手势设置 =====

    /**
     * 滑动手势动作配置
     * 对应 iOS: settings.swipeActionSettings
     *
     * 存储为 JSON 字符串，包含左滑和右滑的动作列表
     */
    private val _swipeActionSettings = MutableStateFlow(
        loadSwipeActionSettings()
    )
    val swipeActionSettings: StateFlow<SwipeActionSettings> = _swipeActionSettings.asStateFlow()

    private fun loadSwipeActionSettings(): SwipeActionSettings {
        val json = prefs.getString(KEY_SWIPE_ACTION_SETTINGS, null)
        return if (json != null) {
            try {
                gson.fromJson(json, SwipeActionSettings::class.java)
            } catch (e: Exception) {
                android.util.Log.e("SettingsManager", "Failed to parse swipe action settings, falling back to default", e)
                SwipeActionSettings.DEFAULT
            }
        } else {
            SwipeActionSettings.DEFAULT
        }
    }

    fun setSwipeActionSettings(settings: SwipeActionSettings) {
        val json = gson.toJson(settings)
        prefs.edit().putString(KEY_SWIPE_ACTION_SETTINGS, json).apply()
        _swipeActionSettings.value = settings
    }

    /**
     * Reset swipe action settings to default
     */
    fun resetSwipeActionSettings() {
        setSwipeActionSettings(SwipeActionSettings.DEFAULT)
    }

    /**
     * 资料库导航项显隐/排序设置（Phase 6.6 Library Edit）
     * 对应 iOS: storage.settings.libraryDisplaySettings（UserDefaults key
     * "libraryDisplaySettings"，PersistentStorage.swift:728-745）
     *
     * 与 iOS 一致只持久化 inUse 的有序 rawValue 数组；notUsed 由模型推导
     */
    private val _libraryDisplaySettings = MutableStateFlow(loadLibraryDisplaySettings())
    val libraryDisplaySettings: StateFlow<LibraryDisplaySettings> =
        _libraryDisplaySettings.asStateFlow()

    private fun loadLibraryDisplaySettings(): LibraryDisplaySettings {
        val json = prefs.getString(KEY_LIBRARY_DISPLAY_SETTINGS, null)
            ?: return LibraryDisplaySettings.DEFAULT
        return try {
            val rawValues: List<Int> = gson.fromJson(
                json, object : TypeToken<List<Int>>() {}.type
            )
            LibraryDisplaySettings(
                inUse = rawValues.mapNotNull { LibraryDisplayType.fromRawValue(it) }
            )
        } catch (e: Exception) {
            android.util.Log.e("SettingsManager", "Failed to parse library display settings, falling back to default", e)
            LibraryDisplaySettings.DEFAULT
        }
    }

    fun setLibraryDisplaySettings(settings: LibraryDisplaySettings) {
        val json = gson.toJson(settings.inUse.map { it.rawValue })
        prefs.edit().putString(KEY_LIBRARY_DISPLAY_SETTINGS, json).apply()
        _libraryDisplaySettings.value = settings
    }

    /**
     * Home 首页 section 配置（iOS 2.1.0: AccountSetting.homeSections）
     *
     * C0 全局暂存键（`home_sections`）：多账户 AccountSettingsStore（W5）落地时
     * 随设置迁移搬入账户层，本键此后仅作缺省值。持久化格式冻结：
     * Int rawValue 有序数组（模式同 libraryDisplaySettings），无值回退默认 4 项。
     */
    private val _homeSections = MutableStateFlow(loadHomeSections())
    val homeSections: StateFlow<List<HomeSection>> = _homeSections.asStateFlow()

    private fun loadHomeSections(): List<HomeSection> {
        val json = prefs.getString(KEY_HOME_SECTIONS, null) ?: return HomeSection.DEFAULT
        return try {
            val rawValues: List<Int> = gson.fromJson(
                json, object : TypeToken<List<Int>>() {}.type
            )
            rawValues.mapNotNull { HomeSection.fromRawValue(it) }
        } catch (e: Exception) {
            android.util.Log.e("SettingsManager", "Failed to parse home sections, falling back to default", e)
            HomeSection.DEFAULT
        }
    }

    fun setHomeSections(sections: List<HomeSection>) {
        val json = gson.toJson(sections.map { it.rawValue })
        prefs.edit().putString(KEY_HOME_SECTIONS, json).apply()
        _homeSections.value = sections
    }

    // ===== SharedPreferences Keys =====

    companion object {
        // 键名一律冻结：改键名等于丢用户已有偏好（无迁移路径），只允许新增

        // --- 基础设置（离线模式、屏幕锁定）---
        private const val KEY_OFFLINE_MODE = "offline_mode"
        private const val KEY_SCREEN_LOCK_PREVENTION = "screen_lock_prevention"

        // --- 显示设置（Settings→Display）---
        private const val KEY_SHOW_DETAILED_INFO = "show_detailed_info"
        private const val KEY_SHOW_SONG_DURATION = "show_song_duration"
        private const val KEY_SHOW_ALBUM_DURATION = "show_album_duration"
        private const val KEY_SHOW_ARTIST_DURATION = "show_artist_duration"

        // --- 外观（appearance_mode/haptics 为设备级；theme_preference 已迁账户层）---
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"

        // --- 播放器设置（Settings→Player + 播放器内切换态）---
        private const val KEY_PLAYER_SHUFFLE_BUTTON = "player_shuffle_button"
        private const val KEY_SHOW_SKIP_BUTTONS = "show_skip_buttons"
        private const val KEY_LYRICS_SMOOTH_SCROLLING = "lyrics_smooth_scrolling"
        private const val KEY_PLAYER_LYRICS_DISPLAYED = "player_lyrics_displayed"
        private const val KEY_PLAYBACK_START_ONLY_ON_PLAY = "playback_start_only_on_play"
        // 播放器显示样式（iOS UserSettings.playerDisplayStyle；旧值曾混在 playback_state 表）
        private const val KEY_PLAYER_DISPLAY_STYLE = "player_display_style"

        // --- 流媒体设置 ---
        private const val KEY_STREAMING_MAX_BITRATE_WIFI = "streaming_max_bitrate_wifi"
        private const val KEY_STREAMING_MAX_BITRATE_CELLULAR = "streaming_max_bitrate_cellular"
        // 单一流媒体格式（对齐 iOS 1.2.3；v2.1.0 起仅作 wifi/cellular 双键的缺省值）
        private const val KEY_STREAMING_FORMAT = "streaming_format"
        // 流媒体格式分网络（iOS 2.0.0，C0 合同键）
        private const val KEY_STREAMING_FORMAT_WIFI = "streaming_format_wifi"
        private const val KEY_STREAMING_FORMAT_CELLULAR = "streaming_format_cellular"

        // --- 缓存设置 ---
        private const val KEY_PLAYER_AUTO_CACHE_PLAYED = "player_auto_cache_played"
        private const val KEY_CACHE_SIZE_LIMIT = "cache_size_limit"
        private const val KEY_CACHE_TRANSCODING_FORMAT = "cache_transcoding_format"

        // --- 音频设置（均衡器 / ReplayGain / 播放速率）---
        private const val KEY_EQUALIZER_ENABLED = "equalizer_enabled"
        private const val KEY_REPLAY_GAIN_ENABLED = "replay_gain_enabled"
        private const val KEY_PLAYBACK_RATE = "playback_rate"
        // 均衡器/可视化/播放器新设置（C0 合同键，键名冻结）
        private const val KEY_EQUALIZER_SETTINGS = "equalizerSettings"
        private const val KEY_ACTIVE_EQUALIZER_SETTING_ID = "activeEqualizerSettingId"
        private const val KEY_PLAYER_VISUALIZER_DISPLAYED = "isPlayerVisualizerDisplayed"
        private const val KEY_SELECTED_VISUALIZER_TYPE = "selectedVisualizerType"
        private const val KEY_PLAYER_RATING_DISPLAYED = "isPlayerRatingDisplayed"
        private const val KEY_REMEMBER_SONG_PLAYBACK_PROGRESS = "isRememberSongPlaybackProgress"

        // --- 资料库列表设置（各列表页排序/筛选/样式，对应 iOS UserSettings 同名键）---
        private const val KEY_PLAYLISTS_SORT_SETTING = "playlists_sort_setting"
        private const val KEY_ARTISTS_SORT_SETTING = "artists_sort_setting"
        private const val KEY_ALBUMS_SORT_SETTING = "albums_sort_setting"
        private const val KEY_SONGS_SORT_SETTING = "songs_sort_setting"
        private const val KEY_FAVORITE_SONGS_SORT_SETTING = "favorite_songs_sort_setting"
        private const val KEY_ARTISTS_FILTER_SETTING = "artists_filter_setting"
        private const val KEY_ALBUMS_STYLE_SETTING = "albums_style_setting"
        private const val KEY_ALBUMS_GRID_SIZE_SETTING = "albums_grid_size_setting"
        private const val KEY_PODCASTS_SHOW_SETTING = "podcasts_show_setting"

        // --- 滑动手势设置 ---
        private const val KEY_SWIPE_ACTION_SETTINGS = "swipe_action_settings"

        // --- 已迁账户层的历史全局键（AccountSettingsStore 缺字段时的缺省源）---
        // 勿删、勿新增写入方；各属性 KDoc 已单独标注
        private const val KEY_THEME_PREFERENCE = "theme_preference"
        private const val KEY_AUTO_CACHE_LATEST_SONGS = "auto_cache_latest_songs"
        private const val KEY_AUTO_CACHE_PODCAST_EPISODES = "auto_cache_podcast_episodes"
        private const val KEY_SCROBBLE_STREAMED_ITEMS = "scrobble_streamed_items"
        private const val KEY_ARTWORK_DOWNLOAD_SETTING = "artwork_download_setting"
        private const val KEY_HOME_SECTIONS = "home_sections"
        private const val KEY_LIBRARY_DISPLAY_SETTINGS = "library_display_settings"

        // --- 已废弃键（仅用于 init 清理落盘残留，勿复活）---
        private const val LEGACY_KEY_ALWAYS_HIDE_PLAYER_LYRICS_BUTTON =
            "always_hide_player_lyrics_button"

        // --- 默认值 ---
        // 0 = 不限制（对齐 iOS cacheLimit 默认值）
        private const val DEFAULT_CACHE_SIZE_MB = 0
        // 各列表排序默认「按名称」（对齐 iOS 各 SortType.defaultValue = .name）
        private const val SORT_NAME = "NAME"
        // 艺术家默认筛选（对齐 iOS ArtistCategoryFilter.defaultValue = .albumArtists）
        private const val ARTISTS_FILTER_ALBUM_ARTISTS = "ALBUM_ARTISTS"
        // 专辑默认网格视图（对齐 iOS AlbumsDisplayStyle.defaultValue = .grid）
        private const val ALBUMS_STYLE_GRID = "GRID"
        // 专辑网格列数默认 3（iOS 手机默认值，Settings.swift:293-299）
        private const val DEFAULT_ALBUMS_GRID_SIZE = 3
        // 播放器显示样式默认大封面（对齐 iOS PlayerDisplayStyle.defaultValue = .large）
        private const val PLAYER_DISPLAY_STYLE_LARGE = "LARGE"
    }
}
