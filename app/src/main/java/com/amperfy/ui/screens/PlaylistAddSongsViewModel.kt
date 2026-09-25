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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Directory
import com.amperfy.data.model.Genre
import com.amperfy.data.model.MusicFolder
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「添加歌曲到播放列表」分类浏览器的页面（内部导航栈的元素）
 *
 * 对应 iOS: PlaylistAdd 目录下的各 VC（PlaylistAddLibraryVC 为 Root，
 * 其余分类/详情页共用一个 AddToPlaylistManager）。
 * Android 以 ViewModel 内部页面栈实现 iOS 的模态导航栈（栈内切换的 push/pop 转场
 * 由 UI 层 AnimatedContent 承担）。
 */
sealed class AddPage(
    /** 导航栏返回按钮显示用的短标签（= 上一页的分类名） */
    val backLabel: String
) {
    /** 分类入口列表（iOS: PlaylistAddLibraryVC，行 = addToPlaylistSettings.inUse） */
    data class Root(val playlistName: String) : AddPage(playlistName)

    data object GenreList : AddPage("Genres")
    data class ArtistList(val favoritesOnly: Boolean) :
        AddPage(if (favoritesOnly) "Favorite Artists" else "Artists")

    enum class AlbumListKind(val label: String) {
        ALL("Albums"), FAVORITES("Favorite Albums"),
        NEWEST("Newest Albums"), RECENT("Recently Played Albums")
    }

    data class AlbumList(val kind: AlbumListKind) : AddPage(kind.label)
    data class SongList(val favoritesOnly: Boolean) :
        AddPage(if (favoritesOnly) "Favorite Songs" else "Songs")

    data object PlaylistList : AddPage("Playlists")
    data object MusicFolderList : AddPage("Directories")

    data class GenreDetail(val name: String) : AddPage(name)
    data class ArtistDetail(val id: String, val name: String) : AddPage(name)
    data class AlbumDetail(val id: String, val name: String) : AddPage(name)
    data class PlaylistDetail(val id: String, val name: String) : AddPage(name)
    data class FolderIndexes(val id: String, val name: String) : AddPage(name)
    data class DirectoryDetail(val id: String, val name: String) : AddPage(name)
}

/** 选中歌曲与目标播放列表重复时的确认（iOS: AddToPlaylistManager 的 UIAlertController） */
data class DuplicatePrompt(
    /** 待加入的全部歌曲（含重复项，Add Duplicate(s) 时全加） */
    val songs: List<Song>,
    /** 其中不在播放列表内的部分（Skip Duplicates 时只加这些） */
    val notContained: List<Song>,
    /** 单选（点行）还是批量（All 按钮） */
    val isBulk: Boolean
)

/**
 * PlaylistAddSongsViewModel - 向播放列表添加歌曲（资料库分类浏览多选）
 *
 * 对应 iOS: AddToPlaylistManager + PlaylistAdd 系列 VC：
 * - 分类入口 = LibraryDisplaySettings.addToPlaylistSettings.inUse（11 项固定顺序）
 * - 动态标题 "Add N Songs to \"<playlist>\""
 * - 选择去重：已在播放列表中的歌曲弹确认（Add Duplicate(s)/Skip (Duplicates)/Cancel）
 * - Done：>100 首弹性能警告（Add Songs Anyway/Abort/Cancel）后 syncUpload
 *
 * **装配依赖**：本 VM 由 PlaylistAddSongsScreen 经 `hiltViewModel()` 在宿主路由
 * `playlist/{playlistId}` 的作用域内取得（界面为播放列表详情页内的 ModalBottomSheet，
 * 自身不是导航路由），故 [SavedStateHandle] 的 `playlistId` 直接来自宿主路由的同名参数。
 * 若将来把本界面挪到别的宿主，须保证该宿主路由仍有 `playlistId` 参数。
 * 另：VM 生命周期随宿主详情页存续（iOS 每次 present 都是新建 VC 栈），故 sheet 每次打开
 * 必须调用 [resetSession] 清上次会话残留。
 */
@HiltViewModel
class PlaylistAddSongsViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    /** 目标播放列表（名称用于标题） */
    val playlist: StateFlow<Playlist?> = appDelegate.playlists.observePlaylistById(playlistId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 目标播放列表现有歌曲 id（去重判定，iOS: playlist.notContaines(playables:)） */
    private val playlistSongIds: StateFlow<Set<String>> =
        appDelegate.playlists.getPlaylistSongs(playlistId)
            .map { songs -> songs.map { it.id }.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // ==================== 选择状态（iOS: AddToPlaylistManager.elementsToAdd）====================

    private val _selection = MutableStateFlow<List<Song>>(emptyList())
    val selection: StateFlow<List<Song>> = _selection.asStateFlow()
    val selectedIds: StateFlow<Set<String>> = _selection
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 动态标题（iOS: AddToPlaylistManager.title） */
    val title: StateFlow<String> = combine(_selection, playlist) { sel, pl ->
        val name = pl?.name ?: ""
        if (sel.isNotEmpty()) "Add ${sel.size} Songs to \"$name\"" else "Add Songs to \"$name\""
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "Add Songs")

    private val _duplicatePrompt = MutableStateFlow<DuplicatePrompt?>(null)
    val duplicatePrompt: StateFlow<DuplicatePrompt?> = _duplicatePrompt.asStateFlow()

    /** 性能警告待确认的歌曲数（iOS: warningElementsToAddCount = 100） */
    private val _performanceWarningCount = MutableStateFlow<Int?>(null)
    val performanceWarningCount: StateFlow<Int?> = _performanceWarningCount.asStateFlow()

    // ==================== 内部页面栈 ====================

    private val _pageStack = MutableStateFlow<List<AddPage>>(listOf(AddPage.Root("")))
    val pageStack: StateFlow<List<AddPage>> = _pageStack.asStateFlow()

    fun push(page: AddPage) {
        _pageStack.value = _pageStack.value + page
    }

    /** @return false 表示已在根页（应由调用方关闭整个界面） */
    fun pop(): Boolean {
        val stack = _pageStack.value
        if (stack.size <= 1) return false
        _pageStack.value = stack.dropLast(1)
        return true
    }

    /**
     * 会话重置——sheet 每次打开时调用（对应 iOS 每次 present 都是新建的 VC 栈 +
     * 新的 AddToPlaylistManager）：清空选择、页面栈回根页、关掉两个待确认弹窗。
     */
    fun resetSession() {
        _selection.value = emptyList()
        _pageStack.value = listOf(AddPage.Root(""))
        _duplicatePrompt.value = null
        _performanceWarningCount.value = null
    }

    // ==================== 选择操作（iOS: AddToPlaylistManager.toggleSelection）====================

    /** 单曲点选：已选则取消；未选且已在播放列表 → 弹重复确认 */
    fun toggleSong(song: Song) {
        val current = _selection.value
        if (current.any { it.id == song.id }) {
            _selection.value = current.filterNot { it.id == song.id }
            return
        }
        if (song.id in playlistSongIds.value) {
            _duplicatePrompt.value = DuplicatePrompt(
                songs = listOf(song), notContained = emptyList(), isBulk = false
            )
        } else {
            _selection.value = current + song
        }
    }

    /**
     * 「All」批量点选（iOS toolbar 的 All 按钮）：
     * 列表中已有被选中的 → 全部取消这些选中；否则全选（重复项弹批量确认）
     */
    fun toggleAll(songs: List<Song>) {
        val current = _selection.value
        val selectedIdSet = current.map { it.id }.toSet()
        val alreadySelected = songs.filter { it.id in selectedIdSet }
        if (alreadySelected.isNotEmpty()) {
            val removeIds = alreadySelected.map { it.id }.toSet()
            _selection.value = current.filterNot { it.id in removeIds }
            return
        }
        // 排除本次列表内部的重复行（同曲多行）后再判定
        val distinct = songs.distinctBy { it.id }
        val notContained = distinct.filterNot { it.id in playlistSongIds.value }
        if (notContained.size != distinct.size) {
            _duplicatePrompt.value = DuplicatePrompt(
                songs = distinct, notContained = notContained, isBulk = true
            )
        } else {
            _selection.value = current + distinct
        }
    }

    /** 重复确认：Add Duplicate(s) —— 连重复项一起加入 */
    fun confirmAddDuplicates() {
        _duplicatePrompt.value?.let { prompt ->
            val existing = _selection.value.map { it.id }.toSet()
            _selection.value = _selection.value + prompt.songs.filterNot { it.id in existing }
        }
        _duplicatePrompt.value = null
    }

    /** 重复确认：Skip (Duplicates) —— 只加入不重复的部分 */
    fun skipDuplicates() {
        _duplicatePrompt.value?.let { prompt ->
            val existing = _selection.value.map { it.id }.toSet()
            _selection.value = _selection.value + prompt.notContained.filterNot { it.id in existing }
        }
        _duplicatePrompt.value = null
    }

    fun dismissDuplicatePrompt() {
        _duplicatePrompt.value = null
    }

    // ==================== Done（iOS: doneBarButtonPressed）====================

    /** Done：空选择直接关闭；>100 首先弹性能警告 */
    fun done(onClose: () -> Unit) {
        val songs = _selection.value
        if (songs.isEmpty()) {
            onClose()
            return
        }
        if (songs.size > WARNING_ELEMENTS_TO_ADD_COUNT) {
            _performanceWarningCount.value = songs.size
        } else {
            upload(onClose)
        }
    }

    /** 性能警告：Add Songs Anyway */
    fun confirmPerformanceWarning(onClose: () -> Unit) {
        _performanceWarningCount.value = null
        upload(onClose)
    }

    /** 性能警告：Abort —— 不上传直接关闭（iOS 同语义） */
    fun abortPerformanceWarning(onClose: () -> Unit) {
        _performanceWarningCount.value = null
        onClose()
    }

    /** 性能警告：Cancel —— 留在页面 */
    fun dismissPerformanceWarning() {
        _performanceWarningCount.value = null
    }

    private fun upload(onClose: () -> Unit) {
        val ids = _selection.value.map { it.id }
        viewModelScope.launch {
            appDelegate.playlists.addSongsToPlaylist(playlistId, ids)
                .onFailure { android.util.Log.e(TAG, "Add songs to playlist failed", it) }
            onClose()
        }
    }

    // ==================== 各分类页数据（冷 Flow，页面组合时按需收集）====================

    fun genres(): Flow<List<Genre>> = appDelegate.library.getAllGenres()

    fun artists(favoritesOnly: Boolean): Flow<List<Artist>> =
        if (favoritesOnly) appDelegate.library.getFavoriteArtists()
        else appDelegate.library.getAllArtists()

    fun albums(kind: AddPage.AlbumListKind): Flow<List<Album>> = when (kind) {
        AddPage.AlbumListKind.ALL -> appDelegate.library.getAllAlbums()
        AddPage.AlbumListKind.FAVORITES -> appDelegate.library.getFavoriteAlbums()
        // Newest/Recent：本地按同步时写入的保序序号过滤排序（同 AlbumsViewModel 方案）
        AddPage.AlbumListKind.NEWEST -> appDelegate.library.getAllAlbums()
            .map { all -> all.filter { it.newestIndex > 0 }.sortedBy { it.newestIndex } }
        AddPage.AlbumListKind.RECENT -> appDelegate.library.getAllAlbums()
            .map { all -> all.filter { it.recentIndex > 0 }.sortedBy { it.recentIndex } }
    }

    fun songs(favoritesOnly: Boolean): Flow<List<Song>> =
        if (favoritesOnly) appDelegate.library.getFavoriteSongs()
        else appDelegate.library.getAllSongs()

    fun playlists(): Flow<List<Playlist>> = appDelegate.playlists.getAllPlaylists()
        .map { all -> all.filterNot { it.id == playlistId } } // 目标播放列表自身不列出

    fun musicFolders(): Flow<List<MusicFolder>> = appDelegate.directories.getMusicFolders()

    fun folderDirectories(folderId: String): Flow<List<Directory>> =
        appDelegate.directories.getMusicFolderDirectories(folderId)

    fun subDirectories(directoryId: String): Flow<List<Directory>> =
        appDelegate.directories.getSubdirectories(directoryId)

    fun directorySongs(directoryId: String): Flow<List<Song>> =
        appDelegate.directories.getDirectorySongs(directoryId)

    fun genreArtists(name: String): Flow<List<Artist>> = appDelegate.library.getGenreArtists(name)
    fun genreAlbums(name: String): Flow<List<Album>> = appDelegate.library.getGenreAlbums(name)
    fun genreSongs(name: String): Flow<List<Song>> = appDelegate.library.getGenreSongs(name)

    fun artistAlbums(artistId: String): Flow<List<Album>> =
        appDelegate.library.getArtistAlbums(artistId)

    fun artistSongs(artistId: String): Flow<List<Song>> =
        appDelegate.library.getArtistSongs(artistId)

    fun albumSongs(albumId: String): Flow<List<Song>> = appDelegate.library.getAlbumSongs(albumId)

    fun playlistSongs(id: String): Flow<List<Song>> = appDelegate.playlists.getPlaylistSongs(id)

    // ==================== Cached 作用域数据源（iOS 搜索栏 scope buttons "Cached"）====================
    // 歌曲的 Cached 判定直接看 Song.isDownloaded；容器类实体经下列「含缓存歌曲」集合判定，
    // 与列表页（Genres/Artists/Albums/Playlists）的 Cached 作用域同源。

    fun cachedGenreNames(): Flow<Set<String>> = appDelegate.library.getCachedGenreNames()

    fun cachedArtistIds(): Flow<Set<String>> = appDelegate.library.getCachedArtistIds()

    fun cachedAlbumIds(): Flow<Set<String>> = appDelegate.library.getCachedAlbumIds()

    fun cachedPlaylistIds(): Flow<Set<String>> = appDelegate.playlists.getCachedPlaylistIds()

    companion object {
        private const val TAG = "PlaylistAddSongsVM"

        /** iOS: AddToPlaylistManager.warningElementsToAddCount */
        const val WARNING_ELEMENTS_TO_ADD_COUNT = 100
    }
}
