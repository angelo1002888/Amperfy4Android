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

package com.amperfy.data.model

import com.amperfy.core.AppDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 滑动动作扩展函数
 *
 * 为 Album, Song, Artist 等实体提供统一的滑动动作处理
 * 对应 iOS: BasicTableViewController.createSwipeAction
 */

// ═══════════════════════════════════════════════════════════
// Album 扩展函数
// ═══════════════════════════════════════════════════════════

/**
 * 处理 Album 的滑动动作
 */
suspend fun Album.handleSwipeAction(
    action: SwipeActionType,
    appDelegate: AppDelegate
): SwipeActionResult {
    return when (action) {
        SwipeActionType.INSERT_USER_QUEUE -> {
            val songs = loadAlbumSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_USER_QUEUE -> {
            val songs = loadAlbumSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_CONTEXT_QUEUE -> {
            val songs = loadAlbumSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_CONTEXT_QUEUE -> {
            val songs = loadAlbumSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY -> {
            val songs = loadAlbumSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.play(playables, PlayContextType.ALBUM, id, name)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY_SHUFFLED -> {
            val songs = loadAlbumSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.playShuffled(playables, PlayContextType.ALBUM, id, name)
            SwipeActionResult.Success
        }
        SwipeActionType.DOWNLOAD -> {
            val songs = loadAlbumSongs(id, appDelegate)
            appDelegate.downloader.downloadSongs(songs)
            SwipeActionResult.Success
        }
        SwipeActionType.REMOVE_FROM_CACHE -> {
            val songs = loadAlbumSongs(id, appDelegate)
            SwipeActionResult.NeedsConfirmation(songs)
        }
        SwipeActionType.ADD_TO_PLAYLIST -> {
            val songs = loadAlbumSongs(id, appDelegate)
            SwipeActionResult.ShowPlaylistSelector(songs.map { it.id })
        }
        SwipeActionType.FAVORITE -> {
            appDelegate.library.toggleAlbumFavorite(id)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_PODCAST_QUEUE,
        SwipeActionType.APPEND_PODCAST_QUEUE,
        SwipeActionType.REMOVE_FROM_QUEUE -> {
            SwipeActionResult.NotSupported
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Song 扩展函数
// ═══════════════════════════════════════════════════════════

/**
 * 歌曲行滑动 / 长按菜单 PLAY 的播放上下文（对应 iOS `SwipeActionContext.playContext`）。
 *
 * iOS 各页 swipeCallback 构造 SwipeActionContext 时都带上本页的真实播放上下文，
 * 滑动播放因而能连播整页/整个容器：
 * - 歌曲列表页（SongsVC.swift:276-288 convertIndexPathToPlayContext）：
 *   当前列表自被滑动曲起截 `maxPlayContextCount = 40` 首（SongsVC.swift:41），起始索引 0，
 *   上下文名 = filterTitle（"Songs" / "Favorite Songs"，SongsVC.swift:132/:138）；
 * - 容器详情页（AlbumDetailVC.swift:173-180、ArtistDetailVC.swift:216-223、
 *   GenreDetailVC.swift:242-248、PlaylistDetailVC.swift:249-254）：
 *   `getContextSongs(onlyCachedSongs: isOfflineMode)` 的**全部**歌曲 +
 *   被滑动曲在其中的位置，containable = album/artist/genre/playlist。
 *
 * 传 null 表示单曲上下文——iOS 本就写作 `PlayContext(containable: playable)` 的调用点
 * （HomeVC.swift:397 的卡片长按菜单、DownloadsVC.swift:107-112 的下载行）。
 */
data class SwipePlayContext(
    val contextType: PlayContextType,
    val contextId: String?,
    val contextName: String,
    val songs: List<Song>,
    val startIndex: Int
)

/**
 * 处理 Song 的滑动动作
 *
 * [playContext] 为该行所在页面的播放上下文（见 [SwipePlayContext]），只作用于
 * PLAY / PLAY_SHUFFLED——对应 iOS BasicTableViewController.swift:389-396
 * `case .play: player.play(context: actionContext.playContext)` /
 * `case .playShuffled: player.playShuffled(context: playContext)`。
 * 传 null 时退化为单曲上下文。
 */
suspend fun Song.handleSwipeAction(
    action: SwipeActionType,
    appDelegate: AppDelegate,
    playContext: SwipePlayContext? = null
): SwipeActionResult {
    return when (action) {
        SwipeActionType.INSERT_USER_QUEUE -> {
            addToQueueNext(appDelegate, CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_USER_QUEUE -> {
            addToQueueLater(appDelegate, CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_CONTEXT_QUEUE -> {
            insertContextQueue(appDelegate, CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_CONTEXT_QUEUE -> {
            appendContextQueue(appDelegate, CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY -> {
            if (playContext != null) {
                // iOS: player.play(context: actionContext.playContext)
                appDelegate.player.playPlaylist(
                    songs = playContext.songs.map {
                        it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                    },
                    startIndex = playContext.startIndex,
                    contextType = playContext.contextType,
                    contextId = playContext.contextId,
                    contextName = playContext.contextName
                )
            } else {
                // 无上下文的调用点：iOS 本就是 PlayContext(containable: song)（单曲）
                val playable = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                appDelegate.player.play(listOf(playable), PlayContextType.NONE, id, title)
            }
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY_SHUFFLED -> {
            if (playContext != null) {
                // 随机起点由 playShuffled 内部选取（对齐 iOS PlayerFacade.playShuffled）；
                // 单曲列表时随机索引恒 0，天然等价 iOS 的 isKeepIndexDuringShuffle
                // （BasicTableViewController.swift:391-396）
                appDelegate.player.playShuffled(
                    songs = playContext.songs.map {
                        it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                    },
                    contextType = playContext.contextType,
                    contextId = playContext.contextId,
                    contextName = playContext.contextName
                )
            } else {
                val playable = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
                appDelegate.player.playShuffled(listOf(playable), PlayContextType.NONE, id, title)
            }
            SwipeActionResult.Success
        }
        SwipeActionType.DOWNLOAD -> {
            appDelegate.downloader.downloadSongs(listOf(this))
            SwipeActionResult.Success
        }
        SwipeActionType.REMOVE_FROM_CACHE -> {
            SwipeActionResult.NeedsConfirmation(listOf(this))
        }
        SwipeActionType.ADD_TO_PLAYLIST -> {
            SwipeActionResult.ShowPlaylistSelector(listOf(id))
        }
        SwipeActionType.FAVORITE -> {
            appDelegate.library.toggleSongFavorite(id)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_PODCAST_QUEUE,
        SwipeActionType.APPEND_PODCAST_QUEUE,
        SwipeActionType.REMOVE_FROM_QUEUE -> {
            SwipeActionResult.NotSupported
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Artist 扩展函数
// ═══════════════════════════════════════════════════════════

/**
 * 处理 Artist 的滑动动作
 */
suspend fun Artist.handleSwipeAction(
    action: SwipeActionType,
    appDelegate: AppDelegate
): SwipeActionResult {
    return when (action) {
        SwipeActionType.INSERT_USER_QUEUE -> {
            val songs = loadArtistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_USER_QUEUE -> {
            val songs = loadArtistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_CONTEXT_QUEUE -> {
            val songs = loadArtistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_CONTEXT_QUEUE -> {
            val songs = loadArtistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY -> {
            val songs = loadArtistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.play(playables, PlayContextType.ARTIST, id, name)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY_SHUFFLED -> {
            val songs = loadArtistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.playShuffled(playables, PlayContextType.ARTIST, id, name)
            SwipeActionResult.Success
        }
        SwipeActionType.DOWNLOAD -> {
            val songs = loadArtistSongs(id, appDelegate)
            appDelegate.downloader.downloadSongs(songs)
            SwipeActionResult.Success
        }
        SwipeActionType.REMOVE_FROM_CACHE -> {
            val songs = loadArtistSongs(id, appDelegate)
            SwipeActionResult.NeedsConfirmation(songs)
        }
        SwipeActionType.ADD_TO_PLAYLIST -> {
            val songs = loadArtistSongs(id, appDelegate)
            SwipeActionResult.ShowPlaylistSelector(songs.map { it.id })
        }
        SwipeActionType.FAVORITE -> {
            appDelegate.library.toggleArtistFavorite(id)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_PODCAST_QUEUE,
        SwipeActionType.APPEND_PODCAST_QUEUE,
        SwipeActionType.REMOVE_FROM_QUEUE -> {
            SwipeActionResult.NotSupported
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Playlist 扩展函数
// ═══════════════════════════════════════════════════════════

/**
 * 加载播放列表歌曲；本地为空时先从服务器同步歌曲详情
 *
 * 对应 iOS: PlaylistsVC.swipeCallback 中先 playlist.fetch 再构造 SwipeActionContext
 */
private suspend fun loadPlaylistSongs(
    playlistId: String,
    appDelegate: AppDelegate
): List<Song> {
    var songs = appDelegate.playlists.getPlaylistSongs(playlistId).first()
    if (songs.isEmpty()) {
        appDelegate.playlists.syncPlaylistDetails(playlistId)
        songs = appDelegate.playlists.getPlaylistSongs(playlistId).first()
    }
    return songs
}

/**
 * 加载专辑歌曲；本地为空时先从服务器同步专辑详情
 *
 * 根因：初始同步刻意不拉专辑歌曲（对齐 iOS syncInitial），歌曲由 BackgroundLibrarySyncer
 * 渐进补齐。全新安装后立刻对专辑执行动作时纯本地读会拿到空列表，导致 play(emptyList) 无声失败。
 *
 * 对应 iOS: HomeVC/AlbumsVC 在预览菜单打开与 swipeCallback 中先 `containable.fetch(...)`
 * 再执行动作（Album 的 fetch = sync(album:)）。刻意差异：iOS 是打开预览即无条件 fetch，
 * Android 改为动作执行前按需同步——已同步过的专辑零额外延迟。
 */
private suspend fun loadAlbumSongs(
    albumId: String,
    appDelegate: AppDelegate
): List<Song> {
    var songs = appDelegate.library.getAlbumSongs(albumId).first()
    if (songs.isEmpty()) {
        appDelegate.library.syncAlbumDetails(albumId)
        songs = appDelegate.library.getAlbumSongs(albumId).first()
    }
    return songs
}

/**
 * 加载艺术家歌曲；本地为空时先从服务器同步艺术家详情（模式同 loadAlbumSongs）
 */
private suspend fun loadArtistSongs(
    artistId: String,
    appDelegate: AppDelegate
): List<Song> {
    var songs = appDelegate.library.getArtistSongs(artistId).first()
    if (songs.isEmpty()) {
        appDelegate.library.syncArtistDetails(artistId)
        songs = appDelegate.library.getArtistSongs(artistId).first()
    }
    return songs
}

/**
 * 加载流派歌曲；本地为空时先同步流派详情（模式同 loadAlbumSongs）
 *
 * 流派以 name 为标识（Subsonic 无流派 id）；syncGenreDetails = 对该流派专辑逐个
 * syncAlbumDetails 扇出（iOS sync(genre:) 语义）。
 */
private suspend fun loadGenreSongs(
    genreName: String,
    appDelegate: AppDelegate
): List<Song> {
    var songs = appDelegate.library.getGenreSongs(genreName).first()
    if (songs.isEmpty()) {
        appDelegate.library.syncGenreDetails(genreName)
        songs = appDelegate.library.getGenreSongs(genreName).first()
    }
    return songs
}

/**
 * 处理 Playlist 的滑动动作
 *
 * 对应 iOS: PlaylistsVC 通过 SwipeActionContext(containable: playlist) 对整个播放列表
 * 应用配置的滑动动作。播放列表不可收藏，故 FAVORITE 不支持。
 */
suspend fun Playlist.handleSwipeAction(
    action: SwipeActionType,
    appDelegate: AppDelegate
): SwipeActionResult {
    return when (action) {
        SwipeActionType.INSERT_USER_QUEUE -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_USER_QUEUE -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_CONTEXT_QUEUE -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_CONTEXT_QUEUE -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.play(playables, PlayContextType.PLAYLIST, id, name)
            appDelegate.playlists.updatePlaylistLastPlayed(id)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY_SHUFFLED -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            val playables = songs.map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.playShuffled(playables, PlayContextType.PLAYLIST, id, name)
            appDelegate.playlists.updatePlaylistLastPlayed(id)
            SwipeActionResult.Success
        }
        SwipeActionType.DOWNLOAD -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            appDelegate.downloader.downloadSongs(songs)
            SwipeActionResult.Success
        }
        SwipeActionType.REMOVE_FROM_CACHE -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            SwipeActionResult.NeedsConfirmation(songs)
        }
        SwipeActionType.ADD_TO_PLAYLIST -> {
            val songs = loadPlaylistSongs(id, appDelegate)
            SwipeActionResult.ShowPlaylistSelector(songs.map { it.id })
        }
        SwipeActionType.FAVORITE,
        SwipeActionType.INSERT_PODCAST_QUEUE,
        SwipeActionType.APPEND_PODCAST_QUEUE,
        SwipeActionType.REMOVE_FROM_QUEUE -> {
            SwipeActionResult.NotSupported
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Genre 扩展函数
// ═══════════════════════════════════════════════════════════

/**
 * 处理 Genre 的滑动/长按菜单动作
 *
 * 对应 iOS: GenresVC 通过 SwipeActionContext(containable: genre) 对整个流派应用动作
 * （EntityPreviewVC.configureFor(genre:) 与 playlist 完全同构）。
 * 流派不可收藏（非 AbstractLibraryEntity 的可收藏项），故 FAVORITE 不支持。
 * Subsonic 无流派 id，以 name 为标识（见 Genre.kt）。
 */
suspend fun Genre.handleSwipeAction(
    action: SwipeActionType,
    appDelegate: AppDelegate
): SwipeActionResult {
    // 流派歌曲取自本地 genre 字段匹配（无 by-genre 端点，iOS 亦为本地 FRC）；
    // 本地为空时按需同步该流派专辑歌曲后重读（专辑歌曲初始同步不拉，见 loadGenreSongs）
    suspend fun songs(): List<Song> = loadGenreSongs(name, appDelegate)
    return when (action) {
        SwipeActionType.INSERT_USER_QUEUE -> {
            val playables = songs().map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_USER_QUEUE -> {
            val playables = songs().map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_CONTEXT_QUEUE -> {
            val playables = songs().map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_CONTEXT_QUEUE -> {
            val playables = songs().map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY -> {
            val playables = songs().map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.play(playables, PlayContextType.GENRE, name, name)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY_SHUFFLED -> {
            val playables = songs().map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.playShuffled(playables, PlayContextType.GENRE, name, name)
            SwipeActionResult.Success
        }
        SwipeActionType.DOWNLOAD -> {
            appDelegate.downloader.downloadSongs(songs())
            SwipeActionResult.Success
        }
        SwipeActionType.REMOVE_FROM_CACHE -> {
            SwipeActionResult.NeedsConfirmation(songs())
        }
        SwipeActionType.ADD_TO_PLAYLIST -> {
            SwipeActionResult.ShowPlaylistSelector(songs().map { it.id })
        }
        SwipeActionType.FAVORITE,
        SwipeActionType.INSERT_PODCAST_QUEUE,
        SwipeActionType.APPEND_PODCAST_QUEUE,
        SwipeActionType.REMOVE_FROM_QUEUE -> {
            SwipeActionResult.NotSupported
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Directory 扩展函数
// ═══════════════════════════════════════════════════════════

/**
 * 加载目录歌曲；本地为空时先向服务器同步该目录内容
 * （模式同 loadPlaylistSongs——列表页的子目录行尚未下钻过，本地必然没有歌曲）
 *
 * 离线模式不发起同步（同步必失败），直接返回本地结果
 */
private suspend fun loadDirectorySongs(
    directoryId: String,
    appDelegate: AppDelegate
): List<Song> {
    var songs = appDelegate.directories.getDirectorySongs(directoryId).first()
    if (songs.isEmpty() && !appDelegate.settings.isOfflineMode.value) {
        appDelegate.directories.syncDirectory(directoryId)
        songs = appDelegate.directories.getDirectorySongs(directoryId).first()
    }
    return songs
}

/**
 * 处理 Directory 的滑动/长按菜单动作
 *
 * 对应 iOS: DirectoriesVC/IndexesVC 的 SwipeActionContext(containable: directory)
 * （EntityPreviewVC.configureFor(directory:)：装配同 playlist，另加
 * `isAddToPlaylist = isOnlineMode && !playables.isEmpty`）。
 * 目录不可收藏/评分（iOS Directory.swift:103-108）。
 * 已知简化：只取该目录本层的歌曲，不递归子目录（iOS Directory.playables 亦为本层）。
 */
suspend fun Directory.handleSwipeAction(
    action: SwipeActionType,
    appDelegate: AppDelegate
): SwipeActionResult {
    return when (action) {
        SwipeActionType.INSERT_USER_QUEUE -> {
            val playables = loadDirectorySongs(id, appDelegate)
                .map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_USER_QUEUE -> {
            val playables = loadDirectorySongs(id, appDelegate)
                .map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendUserQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_CONTEXT_QUEUE -> {
            val playables = loadDirectorySongs(id, appDelegate)
                .map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.insertContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_CONTEXT_QUEUE -> {
            val playables = loadDirectorySongs(id, appDelegate)
                .map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.appendContextQueue(playables)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY -> {
            val playables = loadDirectorySongs(id, appDelegate)
                .map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.play(playables, PlayContextType.FOLDER, id, name)
            SwipeActionResult.Success
        }
        SwipeActionType.PLAY_SHUFFLED -> {
            val playables = loadDirectorySongs(id, appDelegate)
                .map { it.toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls) }
            appDelegate.player.playShuffled(playables, PlayContextType.FOLDER, id, name)
            SwipeActionResult.Success
        }
        SwipeActionType.DOWNLOAD -> {
            appDelegate.downloader.downloadSongs(loadDirectorySongs(id, appDelegate))
            SwipeActionResult.Success
        }
        SwipeActionType.REMOVE_FROM_CACHE -> {
            val songs = loadDirectorySongs(id, appDelegate)
            // 离线且无歌曲时不弹空确认框（同步被短路，列表必然为空）
            if (songs.isEmpty()) SwipeActionResult.NotSupported
            else SwipeActionResult.NeedsConfirmation(songs)
        }
        SwipeActionType.ADD_TO_PLAYLIST -> {
            val songs = loadDirectorySongs(id, appDelegate)
            if (songs.isEmpty()) SwipeActionResult.NotSupported
            else SwipeActionResult.ShowPlaylistSelector(songs.map { it.id })
        }
        SwipeActionType.FAVORITE,
        SwipeActionType.INSERT_PODCAST_QUEUE,
        SwipeActionType.APPEND_PODCAST_QUEUE,
        SwipeActionType.REMOVE_FROM_QUEUE -> {
            SwipeActionResult.NotSupported
        }
    }
}

// ═══════════════════════════════════════════════════════════
// PodcastEpisode 扩展函数
// ═══════════════════════════════════════════════════════════

/**
 * 处理播客单集的滑动动作（Phase 6.4）
 *
 * 对应 iOS: PodcastsVC/PodcastDetailVC（playContextTypeOfElements = .podcast）。
 * Batch 2 起对齐 iOS 的独立 podcastPlaylist（PlayerData.swift:487-497）：
 * Insert/Append Podcast Queue 直接落播客队列，不再借道音乐上下文队列。
 * 单集仍不可收藏/加播放列表（iOS configureFor(podcastEpisode:) 亦为 false）；
 * Batch 4 起支持 Download / Delete Cache（单集下载管线落地）。
 *
 * 播放门控对齐 iOS EntityPreviewVC:262-284——在线要求 isAvailableToUser、
 * 离线要求已缓存（isAvailableToUser 本身已含 cached 优先，故离线额外判 isDownloaded）。
 */
suspend fun PodcastEpisode.handleSwipeAction(
    action: SwipeActionType,
    appDelegate: AppDelegate
): SwipeActionResult {
    val isOfflineMode = appDelegate.settings.isOfflineMode.value
    // iOS: !((!isAvailableToUser && isOnlineMode) || (!isCached && isOfflineMode))
    val isPlayable = if (isOfflineMode) isDownloaded else isAvailableToUser
    return when (action) {
        SwipeActionType.PLAY -> {
            if (!isPlayable) return SwipeActionResult.NotSupported
            val playable = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
            appDelegate.player.play(listOf(playable), PlayContextType.PODCAST, podcastId, podcastTitle)
            SwipeActionResult.Success
        }
        SwipeActionType.INSERT_PODCAST_QUEUE -> {
            if (!isPlayable) return SwipeActionResult.NotSupported
            val playable = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
            appDelegate.player.insertPodcastQueue(listOf(playable))
            SwipeActionResult.Success
        }
        SwipeActionType.APPEND_PODCAST_QUEUE -> {
            if (!isPlayable) return SwipeActionResult.NotSupported
            val playable = toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
            appDelegate.player.appendPodcastQueue(listOf(playable))
            SwipeActionResult.Success
        }
        // Batch 4：单集下载（iOS createDownloadAction 对 AbstractPlayable 一视同仁）
        SwipeActionType.DOWNLOAD -> {
            if (isDownloaded || isOfflineMode || !isAvailableToUser) return SwipeActionResult.NotSupported
            appDelegate.downloader.downloadEpisode(this)
            SwipeActionResult.Success
        }
        // Batch 4：删除单集缓存——与歌曲侧同样先弹确认（iOS createDeleteCacheAction 的 UIAlertController）
        SwipeActionType.REMOVE_FROM_CACHE -> {
            if (!isDownloaded) SwipeActionResult.NotSupported
            else SwipeActionResult.NeedsEpisodeCacheConfirmation(listOf(this))
        }
        else -> SwipeActionResult.NotSupported
    }
}

/**
 * 滑动动作执行结果
 */
sealed class SwipeActionResult {
    object Success : SwipeActionResult()
    object NotSupported : SwipeActionResult()

    /** 删除缓存需要用户确认，携带待删除的歌曲以便确认后执行 */
    data class NeedsConfirmation(val songs: List<Song>) : SwipeActionResult()

    /**
     * 删除播客单集缓存需要用户确认（Batch 4，与 [NeedsConfirmation] 对称）。
     * 单集与歌曲是两个领域类型，删除入口也不同（deleteEpisodeCache），故分开承载。
     */
    data class NeedsEpisodeCacheConfirmation(val episodes: List<PodcastEpisode>) : SwipeActionResult()

    data class ShowPlaylistSelector(val songIds: List<String>) : SwipeActionResult()
}
