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

package com.amperfy.data.local.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.store.RoomEventLogStore
import com.amperfy.data.model.LogEntry
import com.amperfy.data.model.LogEntryType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RoomEventLogStore 合同（专题 15 P4 批次 1，全局表）。
 *
 * 锁定 EventLogStore 四方法在 Room 介质下的语义：
 * append 字段映射（含 LogEntryType.raw 往返）、observeEntries 按 creationDate 倒序且无 limit、
 * latest(limit) 倒序截断、totalCount 计数。event_log 为全局表——无 accountId、无租户根依赖，
 * 故不 seed account_scope。时间戳一律字面量常量（Store/DAO 不取系统时钟）。
 */
@RunWith(AndroidJUnit4::class)
class RoomEventLogStoreTest {

    private lateinit var db: AmperfyDatabase
    private lateinit var store: RoomEventLogStore

    @Before
    fun setUp() {
        db = DbTestFixture.openDatabase()
        store = RoomEventLogStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(
        creationDate: Long,
        message: String,
        statusCode: Int = 7,
        type: LogEntryType = LogEntryType.INFO,
    ) = LogEntry(creationDate = creationDate, message = message, statusCode = statusCode, type = type)

    @Test
    fun append_persistsAllFieldsIncludingTypeRoundtrip() = runBlocking {
        store.append(entry(100, "api boom", statusCode = 5, type = LogEntryType.API_ERROR))

        val stored = store.observeEntries().first().single()
        assertEquals(100L, stored.creationDate)
        assertEquals("api boom", stored.message)
        assertEquals(5, stored.statusCode)
        // type 以 raw 落库、读回经 LogEntryType.fromRaw 还原
        assertEquals(LogEntryType.API_ERROR, stored.type)
    }

    @Test
    fun observeEntries_sortedByCreationDateDescWithoutLimit() = runBlocking {
        store.append(entry(100, "oldest"))
        store.append(entry(300, "newest"))
        store.append(entry(200, "middle"))

        assertEquals(
            listOf("newest", "middle", "oldest"),
            store.observeEntries().first().map { it.message },
        )
    }

    @Test
    fun latest_takesNewestEntriesUpToLimit() = runBlocking {
        store.append(entry(100, "e1"))
        store.append(entry(200, "e2"))
        store.append(entry(300, "e3"))

        assertEquals(listOf("e3", "e2"), store.latest(2).map { it.message })
        // limit 大于总数时返回全部（支持邮件附件 latestEventsCount=30 的常见情形）
        assertEquals(3, store.latest(30).size)
    }

    @Test
    fun totalCount_countsAllEntries() = runBlocking {
        assertEquals(0L, store.totalCount())

        store.append(entry(100, "e1"))
        store.append(entry(200, "e2", type = LogEntryType.ERROR))

        assertEquals(2L, store.totalCount())
    }
}
