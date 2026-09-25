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

package com.amperfy.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Genre
import com.amperfy.data.model.HomeSection
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Radio
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.addToQueueLater
import com.amperfy.data.model.addToQueueNext
import com.amperfy.data.model.appendContextQueue
import com.amperfy.data.model.insertContextQueue
import com.amperfy.data.model.toPlayable
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home 卡片项密封类（iOS: HomeManager 各 section 的 entity 包装）
 *
 * [key] = 业务 id 作 Compose key（不学 iOS 的随机 UUID，业务 id 稳定、随机重抽也能正确 diff）。
 */
sealed class HomeItem {
    abstract val key: String

    data class AlbumItem(val album: Album) : HomeItem() {
        override val key: String get() = "album:${album.id}"
    }

    data class ArtistItem(val artist: Artist) : HomeItem() {
        override val key: String get() = "artist:${artist.id}"
    }

    data class PlaylistItem(val playlist: Playlist) : HomeItem() {
        override val key: String get() = "playlist:${playlist.id}"
    }

    data class SongItem(val song: Song) : HomeItem() {
        override val key: String get() = "song:${song.id}"
    }

    data class PodcastItem(val podcast: Podcast) : HomeItem() {
        override val key: String get() = "podcast:${podcast.id}"
    }

    data class EpisodeItem(val episode: PodcastEpisode) : HomeItem() {
        override val key: String get() = "episode:${episode.id}"
    }

    data class RadioItem(val radio: Radio) : HomeItem() {
        override val key: String get() = "radio:${radio.id}"
    }

    data class GenreItem(val genre: Genre) : HomeItem() {
        override val key: String get() = "genre:${genre.name}"
    }
}

/**
 * Home 首页 ViewModel（W3，iOS: HomeVC + HomeManager）
 *
 * 铁律：仅注入 AppDelegate；HomeRepository 经 appDelegate.home 访问。
 *
 * - visibleSections 来自 settings.homeSections（C0 存 Int rawValue 数组，SettingsManager 已解析为枚举）。
 * - 非随机 section：HomeRepository 的 Room Flow → stateIn 到 sectionData。
 * - 随机 section：MutableStateFlow，进入页面/点击刷新/离线切换时内存重抽。
 * - onEnterScreen：在线时逐 section 并发远端同步（30s 防抖）+ 随机 section 重抽。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {

    private val home get() = appDelegate.home

    // W5：homeSections 改读 active 账户的账户级设置。账户切换时 UI 整体重建，
    // ViewModel 重构造即读到新 active 账户设置，故按当前 active ident 一次性绑定。
    private val accountIdent: String get() = appDelegate.accounts.activeAccountId ?: ""

    /** 可见 section 有序列表（编辑器保存后自动更新） */
    val visibleSections: StateFlow<List<HomeSection>> =
        appDelegate.accountSettings.settings(accountIdent)
            .map { setting -> setting.homeSections.mapNotNull { HomeSection.fromRawValue(it) } }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                appDelegate.accountSettings.settings(accountIdent).value.homeSections
                    .mapNotNull { HomeSection.fromRawValue(it) }
            )

    /** 离线模式（随机 section onlyCached 条件；来自 settings.isOfflineMode） */
    val isOfflineMode: StateFlow<Boolean> = appDelegate.settings.isOfflineMode

    // 播放指示器用（卡片高亮当前播放项）
    val currentSong = appDelegate.player.currentSong
    val isPlaying = appDelegate.player.isPlaying

    // ---- 非随机 section 的 Room Flow（各自 tag 成 section→items 对） ----
    private val nonRandomFlows: List<Flow<Pair<HomeSection, List<HomeItem>>>> = listOf(
        home.recentPlaylists().map { list ->
            HomeSection.RECENTLY_PLAYED_PLAYLISTS to list.map { HomeItem.PlaylistItem(it) }
        },
        home.recentAlbums().map { list ->
            HomeSection.RECENTLY_PLAYED_ALBUMS to list.map { HomeItem.AlbumItem(it) }
        },
        home.newestAlbums().map { list ->
            HomeSection.NEWEST_ALBUMS to list.map { HomeItem.AlbumItem(it) }
        },
        home.newestPodcastEpisodes().map { list ->
            HomeSection.NEWEST_PODCAST_EPISODES to list.map { HomeItem.EpisodeItem(it) }
        },
        home.podcasts().map { list ->
            HomeSection.PODCASTS to list.map { HomeItem.PodcastItem(it) }
        },
        home.radios().map { list ->
            HomeSection.RADIOS to list.map { HomeItem.RadioItem(it) }
        }
    )

    // ---- 随机 section 内存态（key=section） ----
    private val _randomData = MutableStateFlow<Map<HomeSection, List<HomeItem>>>(emptyMap())

    /**
     * 全部 section 的数据映射（非随机 Room Flow + 随机内存态合并）。
     * Screen 按 visibleSections 决定渲染哪些、按此 Map 取每个 section 的卡片。
     *
     * 初始值 null = 本地首读未完成，此时 Screen 不渲染 section 列表
     * （对齐 iOS HomeVC viewDidLoad 同步 fetch 后才首次 applySnapshot，
     * 避免首帧只有标题的堆叠）。Room Flow 首次发射后转为非空 Map。
     */
    val sectionData: StateFlow<Map<HomeSection, List<HomeItem>>?> = combine(
        combine(nonRandomFlows) { pairs -> pairs.toMap() },
        _randomData
    ) { nonRandom, random -> nonRandom + random }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 远端同步防抖时间戳（对齐 iOS updateFromRemote，30s）
    private var lastSyncAt = 0L

    init {
        // 离线切换（含首次发射）重建全部随机 section 数据（onlyCached 条件变化）
        viewModelScope.launch {
            appDelegate.settings.isOfflineMode.collect {
                HomeSection.entries.filter { s -> s.isRandom }.forEach { refreshRandom(it) }
            }
        }
    }

    /**
     * 进入页面：在线时逐 section 并发远端同步（30s 防抖），对齐 iOS HomeVC.viewIsAppearing
     * ——只做 updateFromRemote，**不**重抽随机 section。
     *
     * 随机 section 的重抽只在四个时机发生（对齐 iOS HomeManager.createFetchController +
     * SectionHeaderView.refresh）：首次数据（init 中 isOfflineMode collect 首发已覆盖）/
     * 离线切换（同 collect）/编辑器保存（saveSections）/手动点刷新按钮（refreshRandomSection）。
     */
    fun onEnterScreen() {
        if (appDelegate.settings.isOfflineMode.value) return
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < SYNC_DEBOUNCE_MS) return
        lastSyncAt = now

        syncSectionsFromRemote(visibleSections.value)
    }

    /**
     * 对给定 section 逐个拉远端数据（各 section 独立 launch → 并发；失败由各 repo 方法内部
     * 记录 EventLogger，此处静默忽略）。**不含防抖与离线判定**——由调用方各自决定：
     * onEnterScreen 走 30s 防抖，saveSections 的新增段绕过防抖立即补。
     */
    private fun syncSectionsFromRemote(sections: List<HomeSection>) {
        viewModelScope.launch {
            if (HomeSection.RECENTLY_PLAYED_ALBUMS in sections) {
                launch { runCatching { appDelegate.library.syncRecentAlbums() } }
            }
            if (HomeSection.NEWEST_ALBUMS in sections) {
                launch { runCatching { appDelegate.library.syncNewestAlbums() } }
            }
            if (HomeSection.RECENTLY_PLAYED_PLAYLISTS in sections) {
                launch { runCatching { appDelegate.playlists.syncPlaylists() } }
            }
            if (HomeSection.NEWEST_PODCAST_EPISODES in sections) {
                launch { runCatching { appDelegate.podcasts.syncNewestPodcastEpisodes() } }
            }
            if (HomeSection.RADIOS in sections) {
                launch { runCatching { appDelegate.library.syncRadios() } }
            }
        }
    }

    /** 就地重抽某个随机 section（对应 SectionHeader 右侧刷新图标） */
    fun refreshRandomSection(section: HomeSection) {
        if (!section.isRandom) return
        refreshRandom(section)
    }

    private fun refreshRandom(section: HomeSection) {
        viewModelScope.launch {
            val onlyCached = appDelegate.settings.isOfflineMode.value
            val items: List<HomeItem> = when (section) {
                HomeSection.RANDOM_ALBUMS ->
                    home.randomAlbums(onlyCached = onlyCached).map { HomeItem.AlbumItem(it) }
                HomeSection.RANDOM_ARTISTS ->
                    home.randomArtists(onlyCached = onlyCached).map { HomeItem.ArtistItem(it) }
                HomeSection.RANDOM_GENRES ->
                    home.randomGenres().map { HomeItem.GenreItem(it) }
                HomeSection.RANDOM_SONGS ->
                    home.randomSongs(onlyCached = onlyCached).map { HomeItem.SongItem(it) }
                else -> return@launch
            }
            _randomData.update { it + (section to items) }
        }
    }

    /**
     * 编辑器保存回调：写入 active 账户设置（StateFlow 自动驱动 visibleSections/Home 重渲染），
     * 并对全部可见随机 section 重抽（对齐 iOS presentSectionEditor onDone → createFetchController，
     * 否则新启用的随机 section 会空白直到手动刷新）。
     *
     * **新增的非随机 section 立即补一次远端同步**（在线时，绕过 30s 防抖）——对齐 iOS
     * 编辑器 dismiss 后 HomeVC.viewIsAppearing 会再走一遍 updateFromRemote。
     * 根因链（Android 侧此前新启用 Radios 只有标题无内容）：①saveSections 只重抽随机段，
     * 非随机段无人补同步；②编辑器是 ModalBottomSheet 覆盖层，关闭不重新触发 onEnterScreen
     * （即便触发也会被 30s 防抖挡掉）；③初始同步范围不含 radios（对齐 iOS syncInitial），
     * 本地 radio 表恒空 → Flow 只能发空列表。
     */
    fun saveSections(newOrder: List<HomeSection>) {
        // 差集须在写设置前取（写入后 visibleSections 会被 StateFlow 更新为新值）
        val added = newOrder - visibleSections.value.toSet()

        appDelegate.accountSettings.update(accountIdent) {
            it.copy(homeSections = newOrder.map { s -> s.rawValue })
        }
        newOrder.filter { it.isRandom }.forEach { refreshRandom(it) }

        if (added.isNotEmpty() && !appDelegate.settings.isOfflineMode.value) {
            syncSectionsFromRemote(added)
        }
    }

    // ==================== 播放（Song/Radio/Episode 单击直接播放；容器长按 Play/Shuffle） ====================

    /** 单击歌曲：以所在 section 的歌曲为上下文播放 */
    fun playSong(song: Song, sectionSongs: List<Song>) {
        viewModelScope.launch {
            val index = sectionSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            appDelegate.player.playPlaylist(
                songs = sectionSongs.map { it.toPlayable() },
                startIndex = index,
                contextType = PlayContextType.SONGS,
                contextId = "home_random_songs",
                contextName = HomeSection.RANDOM_SONGS.displayName
            )
        }
    }

    /** 单击电台：以所在 section 的电台为上下文播放（对齐 RadiosViewModel 语义） */
    fun playRadio(radio: Radio, sectionRadios: List<Radio>) {
        viewModelScope.launch {
            val index = sectionRadios.indexOfFirst { it.id == radio.id }.coerceAtLeast(0)
            appDelegate.player.playPlaylist(
                songs = sectionRadios.map { it.toPlayable() },
                startIndex = index,
                contextType = PlayContextType.SONGS,
                contextId = "home_radios",
                contextName = HomeSection.RADIOS.displayName
            )
        }
    }

    /** 单击播客单集：单集直接播放（对齐 PodcastDetail 语义，Home 单集为独立播放项） */
    fun playEpisode(episode: PodcastEpisode) {
        viewModelScope.launch {
            val playable = episode.toPlayableWithCredentials(
                appDelegate.credentials, appDelegate.mediaUrls
            )
            appDelegate.player.playSong(playable)
        }
    }

    /** 长按 Play/Shuffle 专辑：一次性取专辑歌曲后播放 */
    fun playAlbum(album: Album, shuffle: Boolean) {
        viewModelScope.launch {
            val songs = appDelegate.library.getAlbumSongs(album.id).first()
            if (songs.isEmpty()) return@launch
            val playables = songs.map { it.toPlayable() }
            if (shuffle) {
                appDelegate.player.playShuffled(
                    songs = playables,
                    contextType = PlayContextType.ALBUM,
                    contextId = album.id,
                    contextName = album.name
                )
            } else {
                appDelegate.player.playPlaylist(
                    songs = playables,
                    startIndex = 0,
                    contextType = PlayContextType.ALBUM,
                    contextId = album.id,
                    contextName = album.name
                )
            }
        }
    }

    /** 长按 Play/Shuffle 播放列表：一次性取歌曲后播放 */
    fun playPlaylist(playlist: Playlist, shuffle: Boolean) {
        viewModelScope.launch {
            val songs = appDelegate.playlists.getPlaylistSongs(playlist.id).first()
            if (songs.isEmpty()) return@launch
            val playables = songs.map { it.toPlayable() }
            if (shuffle) {
                appDelegate.player.playShuffled(
                    songs = playables,
                    contextType = PlayContextType.PLAYLIST,
                    contextId = playlist.id,
                    contextName = playlist.name
                )
            } else {
                appDelegate.player.playPlaylist(
                    songs = playables,
                    startIndex = 0,
                    contextType = PlayContextType.PLAYLIST,
                    contextId = playlist.id,
                    contextName = playlist.name
                )
            }
        }
    }

    // ---- 歌曲长按队列四操作（经 SongQueueExtensions，对齐项目铁律） ----
    fun songInsertContextQueue(song: Song) = song.insertContextQueue(appDelegate, viewModelScope)
    fun songAppendContextQueue(song: Song) = song.appendContextQueue(appDelegate, viewModelScope)
    fun songAddToQueueNext(song: Song) = song.addToQueueNext(appDelegate, viewModelScope)
    fun songAddToQueueLater(song: Song) = song.addToQueueLater(appDelegate, viewModelScope)

    // ==================== 长按上下文菜单（与各列表页同源）====================
    // 卡片菜单一律走各实体的 handleSwipeAction（滑动手势同一真相源），
    // 使 Home 卡片与对应列表行「同项同序同条件」（对齐 iOS：两处同为 EntityPreviewActionBuilder）

    /** 菜单动作结果协调（删除缓存确认 / 加入播放列表选择器） */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)

    /** 含缓存歌曲的容器 id 集合（菜单 Delete Cache / 离线 Play 门控，与各列表页 Cached 作用域同源） */
    val cachedAlbumIds: StateFlow<Set<String>> = appDelegate.library.getCachedAlbumIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val cachedArtistIds: StateFlow<Set<String>> = appDelegate.library.getCachedArtistIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val cachedPlaylistIds: StateFlow<Set<String>> = appDelegate.playlists.getCachedPlaylistIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val cachedGenreNames: StateFlow<Set<String>> = appDelegate.library.getCachedGenreNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 全缓存容器集合（对应 iOS isCachedCompletely）：菜单隐藏 Download */
    val fullyCachedAlbumIds: StateFlow<Set<String>> = appDelegate.library.getFullyCachedAlbumIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val fullyCachedArtistIds: StateFlow<Set<String>> = appDelegate.library.getFullyCachedArtistIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val fullyCachedPlaylistIds: StateFlow<Set<String>> =
        appDelegate.playlists.getFullyCachedPlaylistIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val fullyCachedGenreNames: StateFlow<Set<String>> = appDelegate.library.getFullyCachedGenreNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun handleAlbumSwipeAction(album: Album, action: SwipeActionType) {
        viewModelScope.launch { swipeCoordinator.onResult(album.handleSwipeAction(action, appDelegate)) }
    }

    fun handleArtistSwipeAction(artist: Artist, action: SwipeActionType) {
        viewModelScope.launch { swipeCoordinator.onResult(artist.handleSwipeAction(action, appDelegate)) }
    }

    fun handlePlaylistSwipeAction(playlist: Playlist, action: SwipeActionType) {
        viewModelScope.launch { swipeCoordinator.onResult(playlist.handleSwipeAction(action, appDelegate)) }
    }

    fun handleGenreSwipeAction(genre: Genre, action: SwipeActionType) {
        viewModelScope.launch { swipeCoordinator.onResult(genre.handleSwipeAction(action, appDelegate)) }
    }

    /**
     * 歌曲菜单动作；PLAY 由 Screen 直接调 playSong(song, sectionSongs) 保留 section 上下文语义，
     * 故此处不含 PLAY，也不需要给 handleSwipeAction 传 SwipePlayContext。
     *
     * 注：iOS Home 卡片菜单的 playContextCb 是 `PlayContext(containable: containable)`
     * （HomeVC.swift:397），歌曲卡即**单曲**上下文；Android 现状为所在 section 的歌曲列表，
     * 属既有差异，本批（㉓-c 只接列表页/详情页真实上下文）不动。
     */
    fun handleSongSwipeAction(song: Song, action: SwipeActionType) {
        viewModelScope.launch { swipeCoordinator.onResult(song.handleSwipeAction(action, appDelegate)) }
    }

    /** 播客单集菜单动作（PLAY / INSERT_PODCAST_QUEUE / APPEND_PODCAST_QUEUE） */
    fun handleEpisodeSwipeAction(episode: PodcastEpisode, action: SwipeActionType) {
        viewModelScope.launch { swipeCoordinator.onResult(episode.handleSwipeAction(action, appDelegate)) }
    }

    /** 播客（容器）菜单动作：全部可用单集为曲目集合，Batch 2 起落独立播客队列（对齐 iOS） */
    fun handlePodcastSwipeAction(podcast: Podcast, action: SwipeActionType) {
        viewModelScope.launch {
            val playables = appDelegate.podcasts.getPodcastEpisodes(podcast.id).first()
                .filter { it.isAvailableToUser }
                .map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            if (playables.isEmpty()) return@launch
            when (action) {
                SwipeActionType.PLAY -> appDelegate.player.play(
                    playables, PlayContextType.PODCAST, podcast.id, podcast.title
                )
                SwipeActionType.INSERT_PODCAST_QUEUE -> appDelegate.player.insertPodcastQueue(playables)
                SwipeActionType.APPEND_PODCAST_QUEUE -> appDelegate.player.appendPodcastQueue(playables)
                else -> Unit
            }
        }
    }

    // ---- 评分（对应 iOS EntityPreviewVC.setRating；乐观本地写 + 远端同步在 Repository 内） ----
    fun setAlbumRating(album: Album, rating: Int) {
        viewModelScope.launch { appDelegate.library.updateAlbumRating(album.id, rating) }
    }

    fun setArtistRating(artist: Artist, rating: Int) {
        viewModelScope.launch { appDelegate.library.updateArtistRating(artist.id, rating) }
    }

    fun setSongRating(song: Song, rating: Int) {
        viewModelScope.launch { appDelegate.library.updateSongRating(song.id, rating) }
    }

    /** 服务器删除单集（对应 iOS createDeleteOnServerAction；成功后重新 sync 该播客刷新状态） */
    fun deleteEpisodeOnServer(episode: PodcastEpisode) {
        viewModelScope.launch {
            appDelegate.podcasts.deletePodcastEpisodeOnServer(episode.id)
                .onSuccess { appDelegate.podcasts.syncPodcastDetails(episode.podcastId) }
        }
    }

    // ---- 电台队列四操作（对齐 RadiosViewModel 语义：电台原始 streamUrl 直连） ----
    fun radioInsertContextQueue(radio: Radio) {
        viewModelScope.launch { appDelegate.player.insertContextQueue(listOf(radio.toPlayable())) }
    }

    fun radioAppendContextQueue(radio: Radio) {
        viewModelScope.launch { appDelegate.player.appendContextQueue(listOf(radio.toPlayable())) }
    }

    fun radioAddToQueueNext(radio: Radio) {
        viewModelScope.launch { appDelegate.player.insertUserQueue(listOf(radio.toPlayable())) }
    }

    fun radioAddToQueueLater(radio: Radio) {
        viewModelScope.launch { appDelegate.player.appendUserQueue(listOf(radio.toPlayable())) }
    }

    companion object {
        private const val SYNC_DEBOUNCE_MS = 30_000L
    }
}
