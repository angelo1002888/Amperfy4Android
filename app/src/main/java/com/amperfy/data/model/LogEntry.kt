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

package com.amperfy.data.model

/**
 * 事件日志条目类型
 * 对应 iOS: LogEntryType（AmperfyKit/Storage/EntityWrappers/LogEntry.swift:27-41）
 */
enum class LogEntryType(val raw: Int, val displayName: String) {
    API_ERROR(0, "API Error"),
    ERROR(1, "Error"),
    INFO(2, "Info"),
    DEBUG(3, "Debug");

    companion object {
        fun fromRaw(raw: Int): LogEntryType = entries.find { it.raw == raw } ?: ERROR
    }
}

/**
 * 事件状态码
 * 对应 iOS: AmperfyLogStatusCode（AmperfyKit/Api/EventLogger.swift:28-36）
 */
enum class AmperfyLogStatusCode(val raw: Int) {
    DOWNLOAD_ERROR(1),
    PLAYER_ERROR(2),
    EMAIL_ERROR(3),
    INTERNAL_ERROR(4),
    CONNECTION_ERROR(5),
    COMMON_ERROR(6),
    INFO(7)
}

/**
 * 事件日志条目领域模型
 * 对应 iOS: LogEntry wrapper（creationDate/message/statusCode/type）
 */
data class LogEntry(
    val creationDate: Long,   // epoch millis
    val message: String,
    val statusCode: Int,
    val type: LogEntryType
)
