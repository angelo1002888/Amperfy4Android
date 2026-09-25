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

package com.amperfy.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 全局事件日志（对应 iOS LogEntryMO）。
 *
 * **全局表**：无 account_id、无 account_scope FK（明确豁免账户纪律，与 iOS 全局日志一致）。
 * 主键 id 自增（@PrimaryKey autoGenerate）——EventLog 属首启丢弃数据，无需保留
 * UUID 身份。字段：creationDate、message、statusCode、type（LogEntryType.raw：
 * 0=API Error/1=Error/2=Info/3=Debug）。iOS suppressionTimeInterval 声明但从不读写，Android 不迁移。
 * 展示按 creation_date 倒序，INDEX(creation_date)。
 */
@Entity(
    tableName = "event_log",
    indices = [
        Index("creation_date"),
    ],
)
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    /** 创建时间戳（epoch 毫秒），由调用方传入 */
    @ColumnInfo(name = "creation_date") val creationDate: Long = 0,
    @ColumnInfo(name = "message") val message: String = "",
    @ColumnInfo(name = "status_code") val statusCode: Int = 0,
    /** LogEntryType.raw（0=API Error/1=Error/2=Info/3=Debug） */
    @ColumnInfo(name = "type") val type: Int = 1,
)
