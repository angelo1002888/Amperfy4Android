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

package com.amperfy.core

import android.util.Log
import com.amperfy.data.local.store.EventLogStore
import com.amperfy.data.model.AmperfyLogStatusCode
import com.amperfy.data.model.LogEntry
import com.amperfy.data.model.LogEntryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EventLogger - 事件日志记录器（Phase 5.5）
 *
 * 对应 iOS: AmperfyKit/Api/EventLogger.swift
 * - debug/info/error 三级入口 + report(topic, throwable)，经 [EventLogStore] 落库
 *   （iOS saveAndDisplay:204-236，message = "topic: message"）
 * - iOS 的 FloatingNotificationBanner 弹窗与 supressAlerts 抑制机制为 UI 行为，
 *   Android 暂不弹横幅，仅落库供 Event Log 页查看（已知差异）
 * - 展示按 creationDate 倒序（iOS LogEntryMO.creationDateSortedFetchRequest）
 * - 专题 15 P1：持久化经 EventLogStore 边界，本类不再感知底层数据库
 */
@Singleton
class EventLogger @Inject constructor(
    private val store: EventLogStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun debug(topic: String, message: String) =
        saveLogEntry(LogEntryType.DEBUG, AmperfyLogStatusCode.INFO.raw, topic, message)

    fun info(topic: String, statusCode: AmperfyLogStatusCode = AmperfyLogStatusCode.INFO, message: String) =
        saveLogEntry(LogEntryType.INFO, statusCode.raw, topic, message)

    fun error(topic: String, statusCode: AmperfyLogStatusCode, message: String) =
        saveLogEntry(LogEntryType.ERROR, statusCode.raw, topic, message)

    /**
     * API 错误（Subsonic 业务失败 / HTTP 错误）
     * 对应 iOS report(topic:error:ResponseError)，statusCode 记录 HTTP 码或 Subsonic error code
     */
    fun apiError(topic: String, statusCode: Int, message: String) =
        saveLogEntry(LogEntryType.API_ERROR, statusCode, topic, message)

    /** 对应 iOS report(topic:error:) - 通用异常上报 */
    fun report(topic: String, throwable: Throwable) =
        saveLogEntry(
            LogEntryType.ERROR,
            AmperfyLogStatusCode.COMMON_ERROR.raw,
            topic,
            throwable.message ?: throwable.javaClass.simpleName
        )

    /** 事件日志（倒序）- 供 EventLogScreen 展示 */
    fun getLogEntries(): Flow<List<LogEntry>> = store.observeEntries()

    /** 最新 N 条日志快照 - 供支持邮件附件（iOS LogData latestEventsCount=30） */
    suspend fun getLatestLogEntries(limit: Int): List<LogEntry> = store.latest(limit)

    suspend fun getTotalEventCount(): Long = store.totalCount()

    private fun saveLogEntry(type: LogEntryType, statusCode: Int, topic: String, message: String) {
        val fullMessage = "$topic: $message"
        Log.println(
            when (type) {
                LogEntryType.DEBUG -> Log.DEBUG
                LogEntryType.INFO -> Log.INFO
                else -> Log.ERROR
            },
            TAG, fullMessage
        )
        scope.launch {
            try {
                store.append(
                    LogEntry(
                        creationDate = System.currentTimeMillis(),
                        message = fullMessage,
                        statusCode = statusCode,
                        type = type
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist log entry", e)
            }
        }
    }

    companion object {
        private const val TAG = "EventLogger"
    }
}
