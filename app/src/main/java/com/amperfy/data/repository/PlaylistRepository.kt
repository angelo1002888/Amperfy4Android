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

package com.amperfy.data.repository

import com.amperfy.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 播放列表域仓库：播放列表读取、本地搜索、计数与增删改（含歌曲增删与重排）。
 * 对应 iOS: LibraryStorage 播放列表查询 + SubsonicLibrarySyncer.syncDown/syncUpload(playlist…) 系列。
 * 出处：Repository 拆分批次 1——方法自 MusicRepository 原样搬移，签名与注释不变。
 */
interface PlaylistRepository {
    fun getAllPlaylists(): Flow<List<Playlist>>
    fun observePlaylistById(playlistId: String): Flow<Playlist?>

    suspend fun getPlaylistById(playlistId: String): Playlist?

    fun getPlaylistSongs(playlistId: String): Flow<List<Song>>

    /** 本地搜索播放列表（Subsonic search3 不返回播放列表，对齐 iOS 仅本地搜索）。 */
    fun searchPlaylists(query: String): Flow<List<Playlist>>
    /** 含缓存歌曲的播放列表 id 集合（列表页/搜索 Cached 作用域用）。对应 iOS: getPlaylists(onlyCached:) */
    fun getCachedPlaylistIds(): Flow<Set<String>>
    /** 全部歌曲均已缓存的播放列表 id 集合（对应 iOS isCachedCompletely：菜单隐藏 Download） */
    fun getFullyCachedPlaylistIds(): Flow<Set<String>>

    /** 播放列表数量（实时）。对应 iOS: LibrarySettingsView 统计行 */
    fun observePlaylistCount(): Flow<Long>

    suspend fun syncPlaylists(): Result<Unit>

    // ==================== 播放列表 CRUD ====================
    // 对应 iOS: SubsonicLibrarySyncer.syncDown/syncUpload 系列
    // 遵循「先服务器后本地」原则；凭证由 Repository 内部获取

    /** 同步播放列表歌曲详情（含 entry 歌曲列表）。对应 iOS: syncDown(playlist:) */
    suspend fun syncPlaylistDetails(playlistId: String): Result<Unit>

    /** 创建播放列表。对应 iOS: createPlaylistRemote + updatePlaylistIdViaItsName */
    suspend fun createPlaylist(name: String): Result<Playlist>

    /** 删除播放列表。对应 iOS: syncUpload(playlistIdToDelete:) */
    suspend fun deletePlaylist(playlistId: String): Result<Unit>

    /** 重命名播放列表。对应 iOS: syncUpload(playlistToUpdateName:) */
    suspend fun renamePlaylist(playlistId: String, newName: String): Result<Unit>

    /** 向播放列表添加歌曲。对应 iOS: syncUpload(playlistToAddSongs:songs:) */
    suspend fun addSongsToPlaylist(playlistId: String, songIds: List<String>): Result<Unit>

    /** 删除播放列表中指定索引的歌曲。对应 iOS: syncUpload(playlistToDeleteSong:index:) */
    suspend fun removeSongFromPlaylist(playlistId: String, index: Int): Result<Unit>

    /** 按新顺序重排播放列表（移除全部后按序重加）。对应 iOS: syncUpload(playlistToUpdateOrder:) */
    suspend fun updatePlaylistOrder(playlistId: String, orderedSongIds: List<String>): Result<Unit>

    /** 记录播放列表的最近播放时间（仅本地）。对应 iOS: Playlist.playedSong / lastTimePlayed */
    suspend fun updatePlaylistLastPlayed(playlistId: String)
}
