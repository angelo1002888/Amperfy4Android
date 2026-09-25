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

package com.amperfy.data.local.store

import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * 播放列表读路径的持久化边界（专题 15 P1 批次 4）
 *
 * MusicRepositoryImpl 播放列表读方法的数据库访问收口于此，只出入领域模型/标量/Flow，
 * 不出现任何数据库类型。全部方法带 accountId 隔离键（复合主键 localId 内含 accountId）。
 *
 * P3 批次 2b 起为 Room 全量实现
 * （data/local/db/store/RoomPlaylistLocalStore）。
 */
interface PlaylistLocalStore {

    /** 全部播放列表（accountId 隔离，数据变化自动发射） */
    fun getAllPlaylists(accountId: String): Flow<List<Playlist>>

    /** 观察单个播放列表（主键直查） */
    fun observePlaylistById(accountId: String, playlistId: String): Flow<Playlist?>

    /** 单个播放列表（一次性查询） */
    suspend fun getPlaylistById(accountId: String, playlistId: String): Playlist?

    /**
     * 播放列表歌曲（有序）。必须保留 songs.* keyPaths 深度 2 通知——
     * 下载完成后成员歌曲缓存图标实时刷新依赖它（详见实现注释）。
     */
    fun getPlaylistSongs(accountId: String, playlistId: String): Flow<List<Song>>

    /** 本地搜索播放列表（name CONTAINS[c]，Subsonic search3 不返回播放列表） */
    fun searchPlaylists(accountId: String, query: String): Flow<List<Playlist>>

    /** 含缓存歌曲的播放列表 id 集合（列表页/搜索 Cached 作用域用） */
    fun getCachedPlaylistIds(accountId: String): Flow<Set<String>>

    /** 全部歌曲均已缓存的播放列表 id 集合（对应 iOS isCachedCompletely，菜单隐藏 Download 用）。 */
    fun getFullyCachedPlaylistIds(accountId: String): Flow<Set<String>>

    // ==================== 本地状态写（批次 5a） ====================

    /**
     * 更新播放列表最近播放时间戳（lastPlayedAt 由 repo 传入，Store 不取时间源；
     * 播放列表不存在时静默跳过）。
     */
    suspend fun updatePlaylistLastPlayed(accountId: String, playlistId: String, lastPlayedAt: Long)

    // ==================== sync/变更写（批次 5c） ====================
    // 语义整块平移自 MusicRepositoryImpl 写块：查询串/保留字段语义/事务边界不变；
    // DTO→domain 映射、parseStarredDate 解析、requireOk、日志留在 repo。
    // 入出仅领域模型/标量，metadata 的 created/changed 已是 Long?（repo 解析完成）。

    /**
     * upsert 一批播放列表元数据（不触碰 songs 关联，避免覆盖已同步的歌曲列表；无 prune）。
     * createPlaylist 两个写点共用。
     */
    suspend fun upsertPlaylistsMetadata(accountId: String, playlists: List<Playlist>)

    /**
     * 整表替换播放列表（单事务）：逐个 upsert 元数据后 prune 差集——服务器已删除的
     * 播放列表本地同步删除（限本账户）。
     */
    suspend fun replacePlaylists(accountId: String, playlists: List<Playlist>)

    /**
     * 应用播放列表详情（单事务）：逐首 upsert 歌曲（保留本地下载状态/所有权字段 +
     * artist/album 关系挂接）并按顺序收集，再 upsert 播放列表元数据并用受管歌曲重建
     * 有序 songs 列表。
     *
     * 注意：songCount/duration 由受管歌曲计算，metadata 自带的 songCount/duration 忽略。
     */
    suspend fun applyPlaylistDetails(
        accountId: String,
        playlistId: String,
        metadata: Playlist,
        songs: List<Song>
    )

    /** 删除播放列表（不存在时静默跳过） */
    suspend fun deletePlaylist(accountId: String, playlistId: String)

    /** 重命名播放列表（不存在时静默跳过） */
    suspend fun renamePlaylist(accountId: String, playlistId: String, newName: String)

    /**
     * 追加歌曲到播放列表（增量累加语义）。详情已同步（songs 非空或本就空列表）时维护
     * songs 关系；未同步详情时 songCount 来自服务器元数据，只做增量累加避免被重置。
     * 本地查不到的歌（如在线搜索结果）也计入 songCount 总数，待下次详情同步补全。
     */
    suspend fun appendPlaylistSongs(accountId: String, playlistId: String, songIds: List<String>)

    /** 按索引移除播放列表歌曲并重算 songCount/duration（越界或不存在时静默跳过） */
    suspend fun removePlaylistSongAt(accountId: String, playlistId: String, index: Int)

    /** 按 id 顺序重排播放列表歌曲并重建有序 songs 列表（不存在时静默跳过） */
    suspend fun reorderPlaylistSongs(accountId: String, playlistId: String, orderedSongIds: List<String>)
}
