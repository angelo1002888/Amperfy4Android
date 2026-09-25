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

package com.amperfy.data.local.store

import com.amperfy.data.model.LogEntry
import kotlinx.coroutines.flow.Flow

/**
 * 事件日志持久化边界（P1）
 *
 * EventLogger 经本接口读写日志，不感知底层数据库。
 * P4 批次 1 起为 Room 全量实现（data/local/db/store/RoomEventLogStore，event_log 全局表）。
 */
interface EventLogStore {

    /** 追加一条日志（失败抛异常，由调用方决定兜底策略） */
    suspend fun append(entry: LogEntry)

    /** 全部日志，按 creationDate 倒序（iOS LogEntryMO.creationDateSortedFetchRequest） */
    fun observeEntries(): Flow<List<LogEntry>>

    /** 最新 N 条日志快照，按 creationDate 倒序（支持邮件附件，iOS latestEventsCount=30） */
    suspend fun latest(limit: Int): List<LogEntry>

    /** 日志总条数（EventLogScreen 统计） */
    suspend fun totalCount(): Long
}
