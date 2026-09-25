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
 * 目录浏览域仓库：音乐文件夹 → 索引（顶层目录）→ 目录内容三层的读取与同步。
 * 对应 iOS: MusicFoldersVC / IndexesVC / DirectoriesVC + SubsonicLibrarySyncer:688-763。
 * 出处：Repository 拆分批次 1——方法自 MusicRepository 原样搬移，签名与注释不变。
 */
interface DirectoryRepository {
    // ==================== 目录浏览（Phase 6.5） ====================
    // 对应 iOS: MusicFoldersVC / IndexesVC / DirectoriesVC + SubsonicLibrarySyncer:688-763

    /** 观察全部音乐文件夹（按 id 排序，iOS MusicFolderMO.idSortedFetchRequest） */
    fun getMusicFolders(): Flow<List<MusicFolder>>
    /** 观察单个音乐文件夹（IndexesScreen 标题用） */
    fun observeMusicFolderById(folderId: String): Flow<MusicFolder?>
    /** 音乐文件夹的顶层目录（iOS MusicFolderDirectoriesFetchedResultsController，谓词 musicFolder==） */
    fun getMusicFolderDirectories(folderId: String): Flow<List<Directory>>
    /** 观察单个目录（DirectoryDetailScreen 标题用） */
    fun observeDirectoryById(directoryId: String): Flow<Directory?>
    /** 目录的子目录（iOS DirectorySubdirectoriesFetchedResultsController，谓词 parent==） */
    fun getSubdirectories(directoryId: String): Flow<List<Directory>>
    /** 目录内歌曲（iOS DirectorySongsFetchedResultsController，按 track 排序） */
    fun getDirectorySongs(directoryId: String): Flow<List<Song>>
    /** 同步音乐文件夹列表（getMusicFolders）。对应 iOS: syncMusicFolders（差集删除） */
    suspend fun syncMusicFolders(): Result<Unit>
    /** 同步文件夹索引（getIndexes）。对应 iOS: syncIndexes(musicFolder:)（差集删除顶层目录） */
    suspend fun syncIndexes(folderId: String): Result<Unit>
    /**
     * 同步目录内容（getMusicDirectory）。对应 iOS: sync(directory:)——
     * 差集删除消失的子目录实体；消失的歌曲仅解除目录关联（iOS removeFromSongs，不删歌曲）
     */
    suspend fun syncDirectory(directoryId: String): Result<Unit>
}
