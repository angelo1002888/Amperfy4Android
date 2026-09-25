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

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.*
import com.amperfy.data.remote.SubsonicApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 播放列表域实现：播放列表读取、本地搜索、计数与增删改（含歌曲增删与重排）。
 * 方法体自 MusicRepositoryImpl 逐字搬移。
 *
 * 状态字段归属：分片锁表 [playlistMutexes] 原为单体实例字段，现为本域实例字段——
 * 本实例随 MusicRepositoryImpl 构造一次性创建（每账户一套），分片键与互斥范围
 * 与拆分前等价（全部 playlist 命令仍共用同一张锁表）。
 *
 * 出处：Repository 拆分批次 2。
 */
internal class PlaylistRepositoryImpl(
    subsonicApi: SubsonicApi,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val playlistLocalStore: com.amperfy.data.local.store.PlaylistLocalStore,
    private val libraryLocalStore: com.amperfy.data.local.store.LibraryLocalStore,
    boundAccountInfo: AccountInfo?,
) : BaseSubsonicRepository(
    subsonicApi, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    PlaylistRepository {

    /**
     * 播放列表命令分片锁：后台同步与编辑命令共用按
     * (account_id, playlist_id) 分片的 Mutex，避免执行中的旧同步响应覆盖刚完成的编辑。
     * 本 Repository 实例已按账户绑定（[boundAccountInfo]），键只需 playlistId 即等价于
     * (account_id, playlist_id) 分片。
     */
    private val playlistMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun playlistMutex(playlistId: String): Mutex =
        playlistMutexes.computeIfAbsent(playlistId) { Mutex() }

    override fun getAllPlaylists(): Flow<List<Playlist>> = playlistLocalStore.getAllPlaylists(currentAccountId)

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

    override suspend fun syncPlaylists(): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:916 guard isSyncAllowed（syncDownPlaylistsWithoutSongs）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestPlaylists()
            // HTTP + Subsonic status 双重校验（status=failed 抛异常转 Result.failure，EventLogger 钩子在 requireOk 内）
            val dtos = requireOk(response, "Sync playlists").playlists.playlist

            playlistLocalStore.replacePlaylists(currentAccountId, dtos.map { it.toPlaylist() })

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 与编辑命令共用 (account_id, playlist_id) 分片锁：避免执行中的旧同步响应
    // 覆盖刚完成的编辑。锁内不再调用其余三个 playlist 顺序命令（无互调）。
    override suspend fun syncPlaylistDetails(playlistId: String): Result<Unit> =
        playlistMutex(playlistId).withLock {
            // iOS SubsonicLibrarySyncer.swift:930 guard isSyncAllowed（syncDown(playlist:)）
            if (!isSyncAllowed) return@withLock Result.success(Unit)
            currentCredentials()
                ?: return@withLock Result.failure(IllegalStateException("Not logged in"))
            try {
                val response = subsonicApi.requestPlaylistSongs(id = playlistId)
                val detail = requireOk(response, "Sync playlist details").playlist

                val aid = currentAccountId
                // metadata 的 created/changed 由 repo 解析为 Long?；songCount/duration 不传，Store 按受管歌曲计算
                val metadata = Playlist(
                    id = playlistId,
                    name = detail.name,
                    comment = detail.comment,
                    owner = detail.owner,
                    isPublic = detail.isPublic,
                    coverArt = detail.coverArt,
                    created = detail.created?.let { parseStarredDate(it) },
                    changed = detail.changed?.let { parseStarredDate(it) }
                )
                val songs = detail.entry?.map { it.toSong(aid) } ?: emptyList()
                playlistLocalStore.applyPlaylistDetails(aid, playlistId, metadata, songs)

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("MusicRepository", "syncPlaylistDetails error", e)
                Result.failure(e)
            }
        }

    /**
     * 新建播放列表 —— 离线时**不静默成功**（本域唯一例外）：返回值必须携带服务器分配 id 的
     * [Playlist]，离线时无从构造，只能返回 failure。iOS 的对应流程是先在本地建列表（id 为空）
     * 再 `syncUpload(playlistToUpdateName:)`（:981 guard 跳过上传），Android 恪守服务器先行，
     * 故整体不创建。调用方（PlaylistSelectorViewModel:191）只记日志、不弹错误，
     * 用户观感仍是「列表没出现」而非报错。
     */
    override suspend fun createPlaylist(name: String): Result<Playlist> {
        if (!isSyncAllowed) return Result.failure(IllegalStateException("No network connection"))
        return try {
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            // 1. 创建：优先使用响应体中服务器分配的 playlist（同名列表存在时名称回查不可靠）
            val createResp = subsonicApi.requestPlaylistCreate(
                name = name
            )
            val createdDto = requireOk(createResp, "Create playlist").playlist
            val aid = currentAccountId
            if (createdDto != null) {
                playlistLocalStore.upsertPlaylistsMetadata(aid, listOf(createdDto.toPlaylist()))
                return Result.success(createdDto.toPlaylist())
            }

            // 2. 部分服务器不在响应中返回 playlist：拉取最新列表，按名称定位服务器分配的 id
            //    （对应 iOS updatePlaylistIdViaItsName 兜底路径）
            val listResp = subsonicApi.requestPlaylists()
            val dtos = requireOk(listResp, "Refresh playlists after create").playlists.playlist
            val match = dtos.filter { it.name == name }
                .maxByOrNull { it.changed ?: it.created ?: "" }
                ?: return Result.failure(Exception("Created playlist not found by name"))

            playlistLocalStore.upsertPlaylistsMetadata(aid, dtos.map { it.toPlaylist() })
            Result.success(match.toPlaylist())
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "createPlaylist error", e)
            Result.failure(e)
        }
    }

    override suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:1045 guard isSyncAllowed（syncUpload(playlistIdToDelete:)）
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            val response = subsonicApi.requestPlaylistDelete(
                id = playlistId
            )
            requireOk(response, "Delete playlist")

            playlistLocalStore.deletePlaylist(currentAccountId, playlistId)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "deletePlaylist error", e)
            Result.failure(e)
        }
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:981 guard isSyncAllowed（syncUpload(playlistToUpdateName:)）
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            val response = subsonicApi.requestPlaylistUpdate(
                playlistId = playlistId,
                name = newName
            )
            requireOk(response, "Rename playlist")

            playlistLocalStore.renamePlaylist(currentAccountId, playlistId, newName)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "renamePlaylist error", e)
            Result.failure(e)
        }
    }

    override suspend fun updatePlaylistLastPlayed(playlistId: String) {
        try {
            val now = System.currentTimeMillis()
            playlistLocalStore.updatePlaylistLastPlayed(currentAccountId, playlistId, now)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "updatePlaylistLastPlayed error", e)
        }
    }

    // 分片锁包裹整个执行体。
    override suspend fun addSongsToPlaylist(playlistId: String, songIds: List<String>): Result<Unit> =
        playlistMutex(playlistId).withLock {
            try {
                if (songIds.isEmpty()) return@withLock Result.success(Unit)
                // iOS SubsonicLibrarySyncer.swift:995 guard isSyncAllowed（syncUpload(playlistToAddSongs:)）
                if (!isSyncAllowed) return@withLock Result.success(Unit)
                currentCredentials()
                    ?: return@withLock Result.failure(Exception("No credentials"))

                val response = subsonicApi.requestPlaylistUpdate(
                    playlistId = playlistId,
                    songIdToAdd = songIds
                )
                requireOk(response, "Add songs to playlist")

                playlistLocalStore.appendPlaylistSongs(currentAccountId, playlistId, songIds)
                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("MusicRepository", "addSongsToPlaylist error", e)
                Result.failure(e)
            }
        }

    // 分片锁包裹整个执行体。
    override suspend fun removeSongFromPlaylist(playlistId: String, index: Int): Result<Unit> =
        playlistMutex(playlistId).withLock {
            try {
                // iOS SubsonicLibrarySyncer.swift:1009 guard isSyncAllowed（syncUpload(playlistToDeleteSong:)）
                if (!isSyncAllowed) return@withLock Result.success(Unit)
                currentCredentials()
                    ?: return@withLock Result.failure(Exception("No credentials"))

                val response = subsonicApi.requestPlaylistUpdate(
                    playlistId = playlistId,
                    songIndexToRemove = listOf(index)
                )
                requireOk(response, "Remove song from playlist")

                playlistLocalStore.removePlaylistSongAt(currentAccountId, playlistId, index)
                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("MusicRepository", "removeSongFromPlaylist error", e)
                Result.failure(e)
            }
        }

    /**
     * 播放列表拖拽重排——**本地先行**（对齐 iOS
     * PlaylistDetailVC.movePlaylistItem「先持久化本地顺序再上传」）。本地先行是与 iOS 对齐的
     * 唯一例外——其余全部 playlist 命令仍是服务器先行；上传失败**不回滚**本地顺序（对齐 iOS
     * 失败只记日志），后续服务端详情同步允许覆盖本地顺序，不设 pending-order。
     * 与其余 playlist 命令共用 (account_id, playlist_id) 分片锁。
     *
     * 离线守卫**只挡第 2 步上传**（不同于本域其余方法的方法首行早退）：iOS 的
     * `guard isSyncAllowed`（SubsonicLibrarySyncer.swift:1029 syncUpload(playlistToUpdateOrder:)）
     * 同样只覆盖上传，本地顺序早已由 PlaylistDetailVC 持久化。守卫若前置到方法开头，
     * 离线拖拽会被 Room Flow 立刻弹回原序——与 iOS 及本方法「本地先行」合同均相悖。
     */
    override suspend fun updatePlaylistOrder(playlistId: String, orderedSongIds: List<String>): Result<Unit> =
        playlistMutex(playlistId).withLock {
            // 无凭证不动本地：本地先行的前置守卫，登录缺失时不该落库（在本地落库之前判）。
            currentCredentials()
                ?: return@withLock Result.failure(Exception("No credentials"))
            try {
                // 1. 本地先行：Room 事务整表替换本地顺序，立即持久化，UI 的 songs Flow 随即发射新序。
                playlistLocalStore.reorderPlaylistSongs(currentAccountId, playlistId, orderedSongIds)

                // 1b. 离线：本地顺序已落库，上传静默跳过（iOS SubsonicLibrarySyncer.swift:1029）
                if (!isSyncAllowed) return@withLock Result.success(Unit)

                // 2. 再向服务器上传：移除全部索引后按新顺序重新添加（服务器先处理 remove 再 add）。
                //    仅用于「等长重排」——orderedSongIds 必须与服务器当前列表数量一致；
                //    删除歌曲请走 removeSongFromPlaylist（按索引降序逐个删，见 PlaylistEditViewModel.removeSongs）
                val indicesToRemove = if (orderedSongIds.isEmpty()) emptyList() else (0 until orderedSongIds.size).toList()
                val response = subsonicApi.requestPlaylistUpdate(
                    playlistId = playlistId,
                    songIndexToRemove = indicesToRemove,
                    songIdToAdd = orderedSongIds
                )
                requireOk(response, "Reorder playlist")

                Result.success(Unit)
            } catch (e: Exception) {
                // 上传失败或 requireOk 拒绝：不回滚本地顺序（对齐 iOS 只记日志）。
                android.util.Log.e("MusicRepository", "updatePlaylistOrder error", e)
                Result.failure(e)
            }
        }

}
