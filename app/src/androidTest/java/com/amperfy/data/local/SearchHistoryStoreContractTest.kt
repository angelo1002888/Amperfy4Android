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

package com.amperfy.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.DbTestFixture
import com.amperfy.data.local.db.store.RoomSearchHistoryStore
import com.amperfy.data.model.SearchEntityType
import com.amperfy.data.model.SearchHistoryEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SearchHistoryStore 的行为合同测试（专题 15 P1 批次 3 建立，P3 批次 5 介质换绑，断言原样复测）。
 *
 * 合同锁定 SearchHistoryStore 三方法语义：
 * - observeHistory 按 searchedAt 倒序、账户隔离；
 * - add 对同一 (type, entityId) 复合键 upsert（只保留新值）；
 * - clear 只清目标账户。
 *
 * 介质改为 Room 内存库 + [RoomSearchHistoryStore]（直接实例化 Store，不经 RepositoryFixture）；
 * 租户根不预 seed——本 Store 的 add 写入口自带 ensureScope，acctA/acctB 由首次写入建根。
 */
@RunWith(AndroidJUnit4::class)
class SearchHistoryStoreContractTest {

    companion object {
        private const val ACCOUNT_A = DbTestFixture.ACCT_A
        private const val ACCOUNT_B = DbTestFixture.ACCT_B
    }

    private lateinit var db: AmperfyDatabase
    private lateinit var store: RoomSearchHistoryStore

    @Before
    fun setUp() {
        db = DbTestFixture.openDatabase()
        store = RoomSearchHistoryStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(
        entityId: String,
        type: SearchEntityType,
        name: String,
        searchedAt: Long
    ) = SearchHistoryEntry(
        entityId = entityId,
        type = type,
        name = name,
        subtitle = "sub-$entityId",
        coverArt = null,
        searchedAt = searchedAt
    )

    @Test
    fun add_observesSortedBySearchedAtDesc_accountIsolated() = runBlocking {
        store.add(ACCOUNT_A, entry("a1", SearchEntityType.ARTIST, "Artist 1", searchedAt = 100))
        store.add(ACCOUNT_A, entry("a2", SearchEntityType.ALBUM, "Album 2", searchedAt = 200))
        store.add(ACCOUNT_B, entry("b1", SearchEntityType.SONG, "Song B", searchedAt = 300))

        val historyA = store.observeHistory(ACCOUNT_A).first()

        assertEquals(
            "Account A history must be sorted by searchedAt DESC",
            listOf("a2", "a1"),
            historyA.map { it.entityId }
        )
        assertTrue(
            "Account A history must not contain account B's entry",
            historyA.none { it.entityId == "b1" }
        )
    }

    @Test
    fun add_sameTypeAndEntityId_upserts() = runBlocking {
        store.add(ACCOUNT_A, entry("x", SearchEntityType.ARTIST, "Old Name", searchedAt = 100))
        store.add(ACCOUNT_A, entry("x", SearchEntityType.ARTIST, "New Name", searchedAt = 300))

        val historyA = store.observeHistory(ACCOUNT_A).first()

        assertEquals("Same (type, entityId) must upsert to a single record", 1, historyA.size)
        assertEquals("New Name", historyA.first().name)
        assertEquals(300L, historyA.first().searchedAt)
    }

    @Test
    fun clear_onlyClearsTargetAccount() = runBlocking {
        store.add(ACCOUNT_A, entry("a1", SearchEntityType.ARTIST, "Artist 1", searchedAt = 100))
        store.add(ACCOUNT_B, entry("b1", SearchEntityType.SONG, "Song B", searchedAt = 200))

        store.clear(ACCOUNT_A)

        assertTrue(
            "Account A history must be empty after clear",
            store.observeHistory(ACCOUNT_A).first().isEmpty()
        )
        assertEquals(
            "Account B history must be untouched",
            listOf("b1"),
            store.observeHistory(ACCOUNT_B).first().map { it.entityId }
        )
    }
}
