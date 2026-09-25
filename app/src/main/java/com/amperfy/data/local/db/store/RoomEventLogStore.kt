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

package com.amperfy.data.local.db.store

import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.entity.EventLogEntity
import com.amperfy.data.local.store.EventLogStore
import com.amperfy.data.model.LogEntry
import com.amperfy.data.model.LogEntryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * EventLogStore 的 Room 全量实现（专题 15 P4 批次 1）。
 *
 * 接口 4 方法全属日志域，构造只需 [AmperfyDatabase]；
 * 语义：倒序、limit 截断、总数统计。
 *
 * **全局表**：event_log 无 account_id、无 account_scope FK（明确豁免账户纪律，与 iOS 全局
 * 日志一致），故本 Store 方法不带 accountId、写入口也无 ensureScope。
 *
 * 身份：主键用 id 自增（不使用 UUID，见实体 KDoc）——日志属首启
 * 丢弃数据，无跨库对齐需求；append 传 id 默认 0 走 autoGenerate。
 *
 * **已知平移语义**：升级不迁移旧日志——首启后 Settings→Support 的
 * Event Log 为空、支持邮件附件 AmperfyLog.json 只含新库产生的条目。
 */
class RoomEventLogStore(
    private val db: AmperfyDatabase,
) : EventLogStore {

    private val eventLogDao get() = db.eventLogDao()

    override suspend fun append(entry: LogEntry) {
        eventLogDao.insert(entry.toEntity())
    }

    override fun observeEntries(): Flow<List<LogEntry>> =
        eventLogDao.observeAll()
            .map { rows -> rows.map { it.toLogEntry() } }
            .distinctUntilChanged()

    override suspend fun latest(limit: Int): List<LogEntry> =
        eventLogDao.getLatest(limit).map { it.toLogEntry() }

    override suspend fun totalCount(): Long = eventLogDao.count()

    // ==================== 日志 conversions ====================
    // 日志条目仅本 Store 使用，映射留在 Store 内（不进 db/mapper 公共文件）。

    private fun EventLogEntity.toLogEntry() = LogEntry(
        creationDate = creationDate,
        message = message,
        statusCode = statusCode,
        // raw 兜底：未知 raw 退化为 ERROR（LogEntryType.fromRaw 内部约定）
        type = LogEntryType.fromRaw(type),
    )

    private fun LogEntry.toEntity() = EventLogEntity(
        // id 留默认 0 → autoGenerate 自增
        creationDate = creationDate,
        message = message,
        statusCode = statusCode,
        type = type.raw,
    )
}
