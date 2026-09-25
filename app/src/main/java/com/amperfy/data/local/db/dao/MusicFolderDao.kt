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
import androidx.room.Query
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.MusicFolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * music_folder 远端快照 DAO。
 *
 * observeAll 按 **server_id** 排序（对照 iOS MusicFolderMO.idSortedFetchRequest）——P2 骨架原写
 * `ORDER BY name`，P3 批次 4a 修正（无调用方无测试引用，直接改）。music_folder 表无派生键，故不涉 sort_key。
 * 注：MusicFolderEntity 的 KDoc 仍写「排序按 name」，实体文件属冻结的 21 表不改，排序口径以本
 * DAO 为准。
 *
 * deleteByServerIds/deleteAllExcept 为目录全量替换同步的差集删除，**必须带 account_id**。
 *
 * P3 批次 4a 扩充（纯新增不接线，4b 收口 RoomLibraryLocalStore 委托）：
 * - observeByServerId：观察单个文件夹（主键直查）；
 * - getServerIdsExcept + deleteAllExcept：replaceMusicFolders 差集删除的两步——先查「将被删除的
 *   文件夹 id」以级联清理其下顶层目录（DirectoryDao.deleteByMusicFolderIds），再删文件夹自身
 *   （先删目录、再删文件夹）。
 */
@Dao
interface MusicFolderDao {
    /** 远端快照全行 upsert（Remote 后缀强制）。 */
    @Upsert
    suspend fun upsertRemote(rows: List<MusicFolderEntity>)

    /** 全部音乐文件夹，按 server_id 升序。 */
    @Query("SELECT * FROM music_folder WHERE account_id = :accountId ORDER BY server_id")
    fun observeAll(accountId: String): Flow<List<MusicFolderEntity>>

    @Query("SELECT * FROM music_folder WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): MusicFolderEntity?

    /** 观察单个音乐文件夹（主键直查）。 */
    @Query("SELECT * FROM music_folder WHERE account_id = :accountId AND server_id = :serverId")
    fun observeByServerId(accountId: String, serverId: String): Flow<MusicFolderEntity?>

    /** 差集删除（全量替换同步用），带 account_id。 */
    @Query("DELETE FROM music_folder WHERE account_id = :accountId AND server_id IN (:serverIds)")
    suspend fun deleteByServerIds(accountId: String, serverIds: List<String>)

    // ==================== 差集删除（P3 批次 4a） ====================

    /**
     * 查「本次同步将被差集删除」的文件夹 server_id（keepServerIds 之外的）。
     * 供 replaceMusicFolders 在删文件夹前先级联清理其下顶层目录（music_folder 与 directory 间
     * 无 FK，级联须由 Store 显式两步完成）。空 keepServerIds = 返回本账户全部（NOT IN () 边界）。
     */
    @Query(
        "SELECT server_id FROM music_folder " +
            "WHERE account_id = :accountId AND server_id NOT IN (:keepServerIds)",
    )
    suspend fun getServerIdsExcept(accountId: String, keepServerIds: List<String>): List<String>

    /**
     * 差集硬删除：不在服务器返回集内的文件夹删除。
     * keepServerIds 为空 = 本账户全删（NOT IN () 边界，P2 已验证可行）。
     */
    @Query("DELETE FROM music_folder WHERE account_id = :accountId AND server_id NOT IN (:keepServerIds)")
    suspend fun deleteAllExcept(accountId: String, keepServerIds: List<String>)
}
