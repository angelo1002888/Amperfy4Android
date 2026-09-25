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

package com.amperfy.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.DirectoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * directory 远端快照 DAO。
 *
 * observeChildren 按 parent_id 下钻、observeByMusicFolder 按 music_folder_id 取顶层目录，
 * 组内均按 `sort_key COLLATE BINARY, server_id` 排序。deleteByServerIds 等差集删除
 * **必须带 account_id**（P3 目录同步差集清理需要）。
 *
 * 排序口径：按 sort_key 拼音分区序（而非 name 的 UTF-16 码点序）——与 1c 「码点序→分区序」
 * 同款设计，4b 记录。
 *
 * P3 批次 4a 扩充（纯新增不接线，4b 收口 RoomLibraryLocalStore 委托）：
 * - 读：observeByMusicFolder（顶层目录）、observeByServerId（单目录观察，
 *   主键直查）；
 * - 差集删：deleteByMusicFolderIds（replaceMusicFolders 级联清文件夹下顶层目录）、
 *   deleteInFolderExcept（replaceTopDirectories 差集）、deleteChildrenExcept（replaceDirectoryChildren
 *   子目录差集，均硬删实体）；
 * - **两组两步 upsert**（本表关键忠实性点）：directory 有 parent_id / music_folder_id 两个挂载列，
 *   分别由「顶层目录同步（getIndexes）」与「子目录同步（getMusicDirectory）」写入，两条路径各只写
 *   自己那一列（replaceTopDirectories 只写 name/coverArt/musicFolderId、
 *   replaceDirectoryChildren 只写 name/coverArt/parentId）。@Upsert 会整行覆盖，把另一路径写入的
 *   挂载列清成 null（同一目录既是某文件夹顶层、又可被父目录列出时会互相抹掉），故此路径
 *   **禁用** upsertRemote：改走 insertIgnoreAll 建新行 + 定向 UPDATE 只写本路径的列。
 *   两个 UPDATE 随 name 原子重算 sort_key（由 Store 经 LibraryTextKeyNormalizer 生成后传入）。
 */
@Dao
interface DirectoryDao {
    /** 远端快照全行 upsert（Remote 后缀强制）。**挂载列写入路径禁用**，见类 KDoc。 */
    @Upsert
    suspend fun upsertRemote(rows: List<DirectoryEntity>)

    @Query(
        "SELECT * FROM directory WHERE account_id = :accountId " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeAll(accountId: String): Flow<List<DirectoryEntity>>

    /** 某父目录下的直接子目录（parent_id 匹配），组内按 sort_key 排序。 */
    @Query(
        "SELECT * FROM directory " +
            "WHERE account_id = :accountId AND parent_id = :parentId " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeChildren(accountId: String, parentId: String): Flow<List<DirectoryEntity>>

    @Query("SELECT * FROM directory WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): DirectoryEntity?

    /** 差集删除（全量替换同步用），带 account_id。 */
    @Query("DELETE FROM directory WHERE account_id = :accountId AND server_id IN (:serverIds)")
    suspend fun deleteByServerIds(accountId: String, serverIds: List<String>)

    // ==================== 读扩充（P3 批次 4a） ====================

    /**
     * 某音乐文件夹下的顶层目录（music_folder_id 匹配）。
     * 排序为 `sort_key COLLATE BINARY, server_id`。
     */
    @Query(
        "SELECT * FROM directory " +
            "WHERE account_id = :accountId AND music_folder_id = :musicFolderId " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeByMusicFolder(accountId: String, musicFolderId: String): Flow<List<DirectoryEntity>>

    /** 观察单个目录（主键直查）。 */
    @Query("SELECT * FROM directory WHERE account_id = :accountId AND server_id = :serverId")
    fun observeByServerId(accountId: String, serverId: String): Flow<DirectoryEntity?>

    // ==================== 差集删除（P3 批次 4a） ====================

    /**
     * 删除这些音乐文件夹下的顶层目录（replaceMusicFolders 差集删文件夹时的级联清理：
     * 删文件夹前先删其 music_folder_id 下的目录）。
     * music_folder 与 directory 间无 FK（标量 ID），级联须由 Store 显式调用本方法完成。
     * musicFolderIds 为空 = 无匹配行，自然 no-op。
     */
    @Query(
        "DELETE FROM directory " +
            "WHERE account_id = :accountId AND music_folder_id IN (:musicFolderIds)",
    )
    suspend fun deleteByMusicFolderIds(accountId: String, musicFolderIds: List<String>)

    /**
     * 某音乐文件夹下顶层目录的差集硬删（replaceTopDirectories：
     * music_folder_id = folderId 内不在服务器返回集的目录删除）。
     * keepServerIds 为空 = 该文件夹下顶层目录全删（NOT IN () 边界）。
     */
    @Query(
        "DELETE FROM directory " +
            "WHERE account_id = :accountId AND music_folder_id = :musicFolderId " +
            "  AND server_id NOT IN (:keepServerIds)",
    )
    suspend fun deleteInFolderExcept(accountId: String, musicFolderId: String, keepServerIds: List<String>)

    /**
     * 某父目录下子目录的差集硬删（replaceDirectoryChildren：
     * parent_id = directoryId 内不在服务器返回集的子目录删除）。
     * keepServerIds 为空 = 该父目录下子目录全删（NOT IN () 边界）。
     */
    @Query(
        "DELETE FROM directory " +
            "WHERE account_id = :accountId AND parent_id = :parentId " +
            "  AND server_id NOT IN (:keepServerIds)",
    )
    suspend fun deleteChildrenExcept(accountId: String, parentId: String, keepServerIds: List<String>)

    // ==================== 两步 upsert（P3 批次 4a，保护另一组挂载列） ====================

    /**
     * 两步 upsert 第 1 步：仅插入不存在的目录行（已存在则 IGNORE，保留其两个挂载列现值）。
     * 即「已存在则沿用、否则新建」——新行的 parent_id /
     * music_folder_id 由传入实体决定（Store 只填本路径那一列，另一列传 null）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(rows: List<DirectoryEntity>)

    /**
     * 两步 upsert 第 2 步（顶层目录路径，getIndexes）：写 name/cover_art/music_folder_id，
     * **不触碰 parent_id**。
     * sort_key 随 name 由 Store 经 LibraryTextKeyNormalizer 原子生成后传入。
     * 行不存在时自然 no-op（新行已由 insertIgnoreAll 建好）。
     */
    @Query(
        "UPDATE directory SET name = :name, cover_art = :coverArt, " +
            "music_folder_id = :musicFolderId, sort_key = :sortKey " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updateTopLevelMetadata(
        accountId: String,
        serverId: String,
        name: String,
        coverArt: String?,
        musicFolderId: String,
        sortKey: String,
    )

    /**
     * 两步 upsert 第 2 步（子目录路径，getMusicDirectory）：写 name/cover_art/parent_id，
     * **不触碰 music_folder_id**。
     * sort_key 随 name 由 Store 经 LibraryTextKeyNormalizer 原子生成后传入。
     * 行不存在时自然 no-op。
     */
    @Query(
        "UPDATE directory SET name = :name, cover_art = :coverArt, " +
            "parent_id = :parentId, sort_key = :sortKey " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updateChildMetadata(
        accountId: String,
        serverId: String,
        name: String,
        coverArt: String?,
        parentId: String,
        sortKey: String,
    )
}
