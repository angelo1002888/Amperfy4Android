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
import com.amperfy.data.local.db.entity.EventLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * event_log 全局日志 DAO（全局表无 account_id）。
 *
 * insert 依赖 id 自增（实体 id 传 0）；observeRecent 按 creation_date 倒序取前 limit 条
 * （EventLogScreen 用）；deleteAll 供清空日志。时间由调用方传入。
 */
@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(entry: EventLogEntity)

    /**
     * 全部日志按 creation_date 倒序（**无 limit**）——
     * EventLogScreen 展示全部条目。
     */
    @Query("SELECT * FROM event_log ORDER BY creation_date DESC")
    fun observeAll(): Flow<List<EventLogEntity>>

    /** 最新 N 条快照（倒序取前 limit），供支持邮件附件（iOS latestEventsCount=30）。 */
    @Query("SELECT * FROM event_log ORDER BY creation_date DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<EventLogEntity>

    /** 日志总条数（EventLogScreen 统计）。 */
    @Query("SELECT COUNT(*) FROM event_log")
    suspend fun count(): Long

    @Query("DELETE FROM event_log")
    suspend fun deleteAll()
}
