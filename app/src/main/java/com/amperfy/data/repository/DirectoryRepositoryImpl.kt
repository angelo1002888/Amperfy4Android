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

/**
 * 目录浏览域实现：音乐文件夹 → 索引（顶层目录）→ 目录内容三层的读取与同步。
 * 方法体自 MusicRepositoryImpl 逐字搬移。
 *
 * 出处：Repository 拆分批次 2。
 */
internal class DirectoryRepositoryImpl(
    subsonicApi: SubsonicApi,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val libraryLocalStore: com.amperfy.data.local.store.LibraryLocalStore,
    boundAccountInfo: AccountInfo?,
) : BaseSubsonicRepository(
    subsonicApi, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    DirectoryRepository {

    // ==================== 目录浏览（Phase 6.5） ====================

    override fun getMusicFolders(): Flow<List<MusicFolder>> =
        libraryLocalStore.getMusicFolders(currentAccountId)

    override fun observeMusicFolderById(folderId: String): Flow<MusicFolder?> =
        libraryLocalStore.observeMusicFolderById(currentAccountId, folderId)

    override fun getMusicFolderDirectories(folderId: String): Flow<List<Directory>> =
        libraryLocalStore.getMusicFolderDirectories(currentAccountId, folderId)

    override fun observeDirectoryById(directoryId: String): Flow<Directory?> =
        libraryLocalStore.observeDirectoryById(currentAccountId, directoryId)

    override fun getSubdirectories(directoryId: String): Flow<List<Directory>> =
        libraryLocalStore.getSubdirectories(currentAccountId, directoryId)

    override fun getDirectorySongs(directoryId: String): Flow<List<Song>> =
        libraryLocalStore.getDirectorySongs(currentAccountId, directoryId)

    override suspend fun syncMusicFolders(): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:789 guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestMusicFolders()
            val dtos = requireOk(response, "Sync music folders")
                .musicFolders?.musicFolder ?: emptyList()
            val aid = currentAccountId
            val folders = dtos.map { MusicFolder(it.id, it.name) }
            libraryLocalStore.replaceMusicFolders(aid, folders)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncMusicFolders error", e)
            Result.failure(e)
        }
    }

    override suspend fun syncIndexes(folderId: String): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:814 guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestIndexes(
                musicFolderId = folderId
            )
            val indexes = requireOk(response, "Sync indexes").indexes
            // iOS SsDirectoryParserDelegate：<index> 内的 <artist> = 顶层目录（id+name）；
            // 顶层 <child isDir=false>（散落歌曲）iOS 存库但 IndexesVC 不显示，Android 跳过（已知简化）
            val dirEntries = indexes?.index?.flatMap { it.artist } ?: emptyList()
            val aid = currentAccountId
            val directories = dirEntries.map { Directory(it.id, it.name, it.coverArt) }
            libraryLocalStore.replaceTopDirectories(aid, folderId, directories)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncIndexes error", e)
            Result.failure(e)
        }
    }

    override suspend fun syncDirectory(directoryId: String): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:846 guard isSyncAllowed（sync(directory:)）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestMusicDirectory(
                id = directoryId
            )
            val children = requireOk(response, "Sync directory").directory?.child ?: emptyList()
            val subdirDtos = children.filter { it.isDir }
            val songDtos = children.filter { !it.isDir }
            val aid = currentAccountId
            // <child isDir=true> 用 title 作为目录名（iOS :75-97）
            val subdirectories = subdirDtos.map { Directory(it.id, it.title, it.coverArt) }
            val songs = songDtos.map { it.toSong(aid) }
            libraryLocalStore.replaceDirectoryChildren(aid, directoryId, subdirectories, songs)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncDirectory error", e)
            Result.failure(e)
        }
    }

}
