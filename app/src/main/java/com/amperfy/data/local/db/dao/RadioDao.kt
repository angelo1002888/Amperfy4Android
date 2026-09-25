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
import com.amperfy.data.local.db.entity.RadioEntity
import kotlinx.coroutines.flow.Flow

/**
 * radio 远端快照 DAO。
 *
 * RadiosScreen 有字母索引，observeAll 按 sort_key COLLATE BINARY + server_id tie-break。
 * deleteByServerIds 为电台全量替换同步的差集删除——**必须带 account_id**（禁止单列 serverId 删）。
 */
@Dao
interface RadioDao {
    /** 远端快照全行 upsert（Remote 后缀强制）。 */
    @Upsert
    suspend fun upsertRemote(rows: List<RadioEntity>)

    @Query(
        "SELECT * FROM radio WHERE account_id = :accountId " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeAll(accountId: String): Flow<List<RadioEntity>>

    /**
     * 前 20 条电台（Home radios() 用，按标题升序取 20 条）。
     * 排序按 sort_key COLLATE BINARY + server_id tie-break——码点序→拼音分区序属 1c 同款既定下沉变化
     * （3b 记录）；与 observeAll 同序，仅多 LIMIT 20。
     */
    @Query(
        "SELECT * FROM radio WHERE account_id = :accountId " +
            "ORDER BY sort_key COLLATE BINARY, server_id LIMIT 20",
    )
    fun observeAllLimited(accountId: String): Flow<List<RadioEntity>>

    @Query("SELECT * FROM radio WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): RadioEntity?

    /**
     * 批量按 server_id 取电台（P4 批次 2：播放队列恢复一次 IN 装配，禁逐条回表）。
     * 与 [getByServerId] 同谓词、同账户约束，仅由单条改批量；缺失的 id 不出现在结果里，
     * 由调用方记日志跳过。
     */
    @Query("SELECT * FROM radio WHERE account_id = :accountId AND server_id IN (:serverIds)")
    suspend fun getByServerIds(accountId: String, serverIds: List<String>): List<RadioEntity>

    /** 差集删除（全量替换同步用），带 account_id。 */
    @Query("DELETE FROM radio WHERE account_id = :accountId AND server_id IN (:serverIds)")
    suspend fun deleteByServerIds(accountId: String, serverIds: List<String>)

    /**
     * 保留集差集**硬删除**（P3 批次 3a）：删除不在 keepServerIds 内的电台（服务器已删电台
     * 直接 delete——iOS 软删标 remoteStatus，Android 硬删，UI 语义一致）。
     * keepServerIds 为本次服务器返回的存活电台 server_id；空列表 = 全删（NOT IN () 边界）。
     * 与 deleteByServerIds 并存：replaceRadios 全量替换走**本方法**，deleteByServerIds 供按 id 集删除。
     * 带 account_id（禁止单列 serverId 删）。
     */
    @Query("DELETE FROM radio WHERE account_id = :accountId AND server_id NOT IN (:keepServerIds)")
    suspend fun deleteAllExcept(accountId: String, keepServerIds: List<String>)
}
