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
import com.amperfy.data.local.db.entity.DownloadEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * download_entry 下载请求状态机 DAO（Downloads 模式）。
 *
 * observeEntries **只观察 download_entry 不 JOIN song**：普通 Song 元数据同步不触发
 * Downloads Flow；Store 拿到条目后再按类型各做一次批量 JOIN 装配 DownloadItem，消除 N+1。
 * 状态由 finish_date/error_date 派生，两个状态转移方法各一个（markFinished/markFailed）；
 * resetForRetry 清空两日期供 Retry failed 重下。所有时间由调用方传入，DAO 不取系统时钟。
 *
 * Batch 4：主键第二列 entity_type（SONG / PODCAST_EPISODE）**全部单条方法一律带该谓词**
 * ——歌曲与播客单集的 id 分属两个服务端命名空间，可能撞号，只按 song_id 定位会串行改到另一类型
 * 的记录。返回 id 的两个查询（Retry failed / 启动恢复）改返回 [DownloadEntryRefRow] 投影，
 * 由 Store 映射为领域 DownloadRef。
 */
@Dao
interface DownloadEntryDao {
    /** 创建/更新下载请求记录（upsert 整行；时间由调用方在实体内给出）。 */
    @Upsert
    suspend fun upsert(entry: DownloadEntryEntity)

    /** 只观察 download_entry（不 JOIN），按创建时间升序。 */
    @Query("SELECT * FROM download_entry WHERE account_id = :accountId ORDER BY creation_date ASC")
    fun observeEntries(accountId: String): Flow<List<DownloadEntryEntity>>

    /** 单条记录（供 upsertRequested 判「已存在则 reset、否则新建」，先查后写）。 */
    @Query(
        "SELECT * FROM download_entry WHERE account_id = :accountId " +
            "AND entity_type = :entityType AND song_id = :songId",
    )
    suspend fun getByEntity(accountId: String, entityType: String, songId: String): DownloadEntryEntity?

    @Query("SELECT * FROM download_entry WHERE account_id = :accountId ORDER BY creation_date ASC")
    suspend fun getAll(accountId: String): List<DownloadEntryEntity>

    @Query(
        "DELETE FROM download_entry WHERE account_id = :accountId " +
            "AND entity_type = :entityType AND song_id = :songId",
    )
    suspend fun deleteByEntity(accountId: String, entityType: String, songId: String)

    /** 标记下载完成（设 finish_date）。 */
    @Query(
        "UPDATE download_entry SET finish_date = :finishDate, updated_at = :updatedAt " +
            "WHERE account_id = :accountId AND entity_type = :entityType AND song_id = :songId",
    )
    suspend fun markFinished(
        accountId: String,
        entityType: String,
        songId: String,
        finishDate: Long,
        updatedAt: Long,
    )

    /**
     * 标记下载失败/取消（设 error_date）。
     *
     * `AND finish_date IS NULL` 守卫：已成功的记录不被失败覆盖（P2 骨架漏写该条件，P4 批次 1 补齐）。
     */
    @Query(
        "UPDATE download_entry SET error_date = :errorDate, updated_at = :updatedAt " +
            "WHERE account_id = :accountId AND entity_type = :entityType AND song_id = :songId " +
            "AND finish_date IS NULL",
    )
    suspend fun markFailed(
        accountId: String,
        entityType: String,
        songId: String,
        errorDate: Long,
        updatedAt: Long,
    )

    /**
     * Cancel all：该账户全部未完成（finish/error 均空）记录标记失败，
     * 批量置 error_date。
     * 不分类型（Cancel all 语义即整账户），故无 entity_type 谓词。
     */
    @Query(
        "UPDATE download_entry SET error_date = :errorDate, updated_at = :updatedAt " +
            "WHERE account_id = :accountId AND finish_date IS NULL AND error_date IS NULL",
    )
    suspend fun markAllUnfinishedFailed(accountId: String, errorDate: Long, updatedAt: Long)

    /**
     * Clear finished：删除已结束（finish 或 error 非空）的记录，
     * 整账户批删。
     * 同为整账户语义，无 entity_type 谓词。
     */
    @Query(
        "DELETE FROM download_entry WHERE account_id = :accountId " +
            "AND (finish_date IS NOT NULL OR error_date IS NOT NULL)",
    )
    suspend fun deleteFinished(accountId: String)

    /**
     * Retry failed：全部失败（error_date 非空）记录的 (entity_type, song_id)。
     * 按 creation_date 升序——无合同依赖，仅为结果确定性（便于测试与重下顺序稳定）。
     */
    @Query(
        "SELECT entity_type, song_id FROM download_entry WHERE account_id = :accountId " +
            "AND error_date IS NOT NULL ORDER BY creation_date ASC",
    )
    suspend fun getFailedRefs(accountId: String): List<DownloadEntryRefRow>

    /**
     * 启动恢复：未完成（finish/error 均空）记录的 (entity_type, song_id)，按 creation_date 升序
     * （对照 iOS setupDownloadQueue）。
     */
    @Query(
        "SELECT entity_type, song_id FROM download_entry WHERE account_id = :accountId " +
            "AND finish_date IS NULL AND error_date IS NULL ORDER BY creation_date ASC",
    )
    suspend fun getRequestedRefs(accountId: String): List<DownloadEntryRefRow>

    /** 重置为待重下（清空 finish/error 日期），供 Retry failed。 */
    @Query(
        "UPDATE download_entry SET finish_date = NULL, error_date = NULL, updated_at = :updatedAt " +
            "WHERE account_id = :accountId AND entity_type = :entityType AND song_id = :songId",
    )
    suspend fun resetForRetry(accountId: String, entityType: String, songId: String, updatedAt: Long)
}
