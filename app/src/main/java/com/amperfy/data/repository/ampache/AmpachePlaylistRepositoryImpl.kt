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

package com.amperfy.data.repository.ampache

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.local.store.PlaylistLocalStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Song
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 播放列表域的 Ampache 实现（对应 iOS `AmpacheLibrarySyncer` 的 playlist 部分，:1018-1184）。
 *
 * 与 Subsonic 侧 [com.amperfy.data.repository.PlaylistRepositoryImpl] 同构，
 * 含同一套 (account_id, playlist_id) 分片锁（避免执行中的旧同步响应覆盖刚完成的编辑）。
 *
 * 三处 Ampache 特有语义：
 * - 加歌是**逐首**请求（`playlist_add_song`，iOS :1079-1090 同样 for 循环）；
 * - 按位置删歌的 `track` 是 **1-based**（+1 已在 [AmpacheApi.requestPlaylistDeleteItem] 内做）；
 * - 重排是**整表提交**（`playlist_edit` 带 items + tracks），不是 Subsonic 的「删全部再逐个加」。
 *
 * 刻意**不复刻** iOS `validatePlaylistId`（:1142-1184：本地先建空列表、上传时用返回 id 补建）
 * ——Android 的 createPlaylist 恪守服务器先行，本地不存在「id 为空的待上传列表」这一状态。
 *
 * 出处：Ampache API 移植 Batch 2。
 */
internal class AmpachePlaylistRepositoryImpl(
    ampacheApi: AmpacheApi,
    authSession: AmpacheAuthSession,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val playlistLocalStore: PlaylistLocalStore,
    private val libraryLocalStore: LibraryLocalStore,
    boundAccountInfo: AccountInfo?,
) : BaseAmpacheRepository(
    ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    PlaylistRepository {

    /** 播放列表命令分片锁（与 Subsonic 侧同源；本实例已按账户绑定，键只需 playlistId） */
    private val playlistMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun playlistMutex(playlistId: String): Mutex =
        playlistMutexes.computeIfAbsent(playlistId) { Mutex() }

    // ==================== 本地读 ====================

    override fun getAllPlaylists(): Flow<List<Playlist>> =
        playlistLocalStore.getAllPlaylists(currentAccountId)

    override fun observePlaylistById(playlistId: String): Flow<Playlist?> =
        playlistLocalStore.observePlaylistById(currentAccountId, playlistId)

    override suspend fun getPlaylistById(playlistId: String): Playlist? =
        playlistLocalStore.getPlaylistById(currentAccountId, playlistId)

    override fun getPlaylistSongs(playlistId: String): Flow<List<Song>> =
        playlistLocalStore.getPlaylistSongs(currentAccountId, playlistId)

    override fun searchPlaylists(query: String): Flow<List<Playlist>> =
        playlistLocalStore.searchPlaylists(currentAccountId, query)

    override fun getCachedPlaylistIds(): Flow<Set<String>> =
        playlistLocalStore.getCachedPlaylistIds(currentAccountId)

    override fun getFullyCachedPlaylistIds(): Flow<Set<String>> =
        playlistLocalStore.getFullyCachedPlaylistIds(currentAccountId)

    override fun observePlaylistCount(): Flow<Long> =
        libraryLocalStore.observePlaylistCount(currentAccountId)

    override suspend fun updatePlaylistLastPlayed(playlistId: String) {
        try {
            playlistLocalStore.updatePlaylistLastPlayed(
                currentAccountId, playlistId, System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            android.util.Log.e(AMPACHE_LOG_TAG, "updatePlaylistLastPlayed error", e)
        }
    }

    // ==================== 同步 ====================

    override suspend fun syncPlaylists(): Result<Unit> {
        // iOS syncDownPlaylistsWithoutSongs（:1018-1030）guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync playlists") {
            val page = ampacheApi.requestPlaylists()
            playlistLocalStore.replacePlaylists(currentAccountId, page.items.map { it.toPlaylist() })
        }
    }

    /**
     * 播放列表详情（对应 iOS syncDown(playlist:)，:1033-1076）：
     * `playlist` 取元数据（iOS 在 validatePlaylistId 里发同一请求）+ `playlist_songs` 取有序曲目。
     * **响应顺序即列表顺序**，Store 侧按传入顺序重建。
     */
    override suspend fun syncPlaylistDetails(playlistId: String): Result<Unit> =
        playlistMutex(playlistId).withLock {
            if (!isSyncAllowed) return@withLock Result.success(Unit)
            currentCredentials()
                ?: return@withLock Result.failure(IllegalStateException("Not logged in"))
            runAmpache("Sync playlist details") {
                val aid = currentAccountId
                val metadataDto = ampacheApi.requestPlaylist(playlistId).items.firstOrNull()
                // 元数据缺失（服务器已删/未回传）时用本地已知名称兜底，避免把名称写空
                val metadata = metadataDto?.toPlaylist()?.copy(id = playlistId)
                    ?: playlistLocalStore.getPlaylistById(aid, playlistId)
                    ?: Playlist(id = playlistId, name = "")
                val songs = ampacheApi.requestPlaylistSongs(playlistId).items.map { it.toSong(aid) }
                // songCount/duration 由 Store 按受管歌曲计算，metadata 自带的忽略
                playlistLocalStore.applyPlaylistDetails(aid, playlistId, metadata, songs)
            }
        }

    // ==================== CRUD（服务器先行） ====================

    /**
     * 新建播放列表（iOS 无对应物——它是本地先建、上传时 validatePlaylistId 补 id）。
     * 离线时**不静默成功**：返回值必须携带服务器分配 id（口径同 Subsonic 侧）。
     */
    override suspend fun createPlaylist(name: String): Result<Playlist> {
        if (!isSyncAllowed) return Result.failure(IllegalStateException("No network connection"))
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Create playlist") {
            // playlist_create 响应回吐新建的 playlist，其 id 即服务器分配的 id
            val created = ampacheApi.requestPlaylistCreate(name).items.firstOrNull()
                ?: throw IllegalStateException("Ampache: playlist_create returned no playlist")
            val playlist = created.toPlaylist()
            playlistLocalStore.upsertPlaylistsMetadata(currentAccountId, listOf(playlist))
            playlist
        }
    }

    override suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        // iOS syncUpload(playlistIdToDelete:)（:1135-1140）guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Delete playlist") {
            ampacheApi.requestPlaylistDelete(playlistId)
            playlistLocalStore.deletePlaylist(currentAccountId, playlistId)
        }
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String): Result<Unit> {
        // iOS syncUpload(playlistToUpdateName:)（:1111-1119）guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Rename playlist") {
            ampacheApi.requestPlaylistEditOnlyName(playlistId, newName)
            playlistLocalStore.renamePlaylist(currentAccountId, playlistId, newName)
        }
    }

    /** 加歌 = **逐首** playlist_add_song（iOS :1079-1090 同样逐首，Ampache 无批量参数） */
    override suspend fun addSongsToPlaylist(playlistId: String, songIds: List<String>): Result<Unit> =
        playlistMutex(playlistId).withLock {
            if (songIds.isEmpty()) return@withLock Result.success(Unit)
            if (!isSyncAllowed) return@withLock Result.success(Unit)
            currentCredentials() ?: return@withLock Result.failure(Exception("No credentials"))
            runAmpache("Add songs to playlist") {
                songIds.forEach { songId -> ampacheApi.requestPlaylistAddSong(playlistId, songId) }
                playlistLocalStore.appendPlaylistSongs(currentAccountId, playlistId, songIds)
            }
        }

    /** 删歌按位置（iOS syncUpload(playlistToDeleteSong:index:)，:1093-1108；track = index+1 在 API 层做） */
    override suspend fun removeSongFromPlaylist(playlistId: String, index: Int): Result<Unit> =
        playlistMutex(playlistId).withLock {
            if (!isSyncAllowed) return@withLock Result.success(Unit)
            currentCredentials() ?: return@withLock Result.failure(Exception("No credentials"))
            runAmpache("Remove song from playlist") {
                ampacheApi.requestPlaylistDeleteItem(playlistId, index)
                playlistLocalStore.removePlaylistSongAt(currentAccountId, playlistId, index)
            }
        }

    /**
     * 拖拽重排——**本地先行**（与 Subsonic 侧同一合同，对齐 iOS PlaylistDetailVC.movePlaylistItem）：
     * 先落本地顺序，再整表提交 `playlist_edit`（iOS syncUpload(playlistToUpdateOrder:)，:1122-1132）。
     * 上传失败**不回滚**本地顺序；离线守卫只挡上传（前置会让离线拖拽被 Room Flow 弹回原序）。
     */
    override suspend fun updatePlaylistOrder(
        playlistId: String,
        orderedSongIds: List<String>,
    ): Result<Unit> = playlistMutex(playlistId).withLock {
        currentCredentials() ?: return@withLock Result.failure(Exception("No credentials"))
        runAmpache("Reorder playlist") {
            playlistLocalStore.reorderPlaylistSongs(currentAccountId, playlistId, orderedSongIds)
            if (isSyncAllowed) {
                // iOS 对空列表直接 return（`guard !songIds.isEmpty`，:1128）
                if (orderedSongIds.isNotEmpty()) {
                    ampacheApi.requestPlaylistEdit(playlistId, orderedSongIds)
                }
            }
        }
    }
}
