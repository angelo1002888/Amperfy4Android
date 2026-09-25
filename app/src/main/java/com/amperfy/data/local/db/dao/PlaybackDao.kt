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
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.PlaybackQueueItemEntity
import com.amperfy.data.local.db.entity.PlaybackStateEntity

/**
 * 播放状态 + 队列 DAO。
 *
 * 单 DAO 同管两张全局表 playback_state 与 playback_queue_item，因二者必须在同一事务保存/清空。
 * **唯一写入口是 replaceAllQueues（整队列替换）**：先删全部旧队列行（Batch 2 起为 5 个 queueType：
 * PLAYLIST/USER/CONTEXT/CONTEXT_SHUFFLED/PODCAST）、批量插入连续 position、最后 upsert
 * playback_state（顺序固定）。清表式删除天然覆盖新增段，无需按段枚举。
 * **禁止逐行 UPDATE position**——(queue_type, position) 主键换位会主键冲突；该约束写进接口命名
 * 并由主键冲突/事务回滚测试锁定（测试在批次 3）。
 */
@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun getState(): PlaybackStateEntity?

    /** 某队列的有序条目（PLAYLIST/USER/CONTEXT/CONTEXT_SHUFFLED/PODCAST 之一），按 position。 */
    @Query("SELECT * FROM playback_queue_item WHERE queue_type = :queueType ORDER BY position")
    suspend fun getQueueItems(queueType: String): List<PlaybackQueueItemEntity>

    /** 内部助手：删除全部队列行（全局仅五队列，直接清表）。仅供事务方法调用。 */
    @Query("DELETE FROM playback_queue_item")
    suspend fun deleteAllQueueItems()

    /** 内部助手：批量插入队列条目。仅供 replaceAllQueues 调用。 */
    @Insert
    suspend fun insertQueueItems(items: List<PlaybackQueueItemEntity>)

    /** 内部助手：upsert 单行播放状态（id=1）。仅供 replaceAllQueues 调用。 */
    @Upsert
    suspend fun upsertState(state: PlaybackStateEntity)

    /** 内部助手：删除单行播放状态。仅供 clearAll 调用。 */
    @Query("DELETE FROM playback_state")
    suspend fun deleteState()

    /**
     * 整队列替换 + 保存状态（唯一写入口，顺序固定）：
     * 1. 删全部 queue_type 旧行；2. 批量插入连续 position；3. upsert playback_state。
     * 三步同事务，观察者不会读到半状态。
     */
    @Transaction
    suspend fun replaceAllQueues(state: PlaybackStateEntity, items: List<PlaybackQueueItemEntity>) {
        deleteAllQueueItems()
        insertQueueItems(items)
        upsertState(state)
    }

    /** 清空播放状态与全部队列（登出/清状态）。 */
    @Transaction
    suspend fun clearAll() {
        deleteAllQueueItems()
        deleteState()
    }
}
