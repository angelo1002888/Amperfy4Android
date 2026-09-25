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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * sort_key SQL 下推排序契约测试（DAO 下推验证）。
 *
 * 锁定 observeAll 的 `ORDER BY sort_key COLLATE BINARY, server_id` 下推语义：
 * - A–Z 分区序（英文首字母、中文拼音首字母）；
 * - `#` 组（数字/符号）经二进制 collate 严格晚于 Z 组（sortKey 前缀 99 > 25）；
 * - 同分区内按原始串二进制序；
 * - sort_key 相同时以 server_id 稳定 tie-break。
 *
 * 派生键经 LibraryTextKeyNormalizer 生成（与生产同一规则源），非测试手拼。
 */
@RunWith(AndroidJUnit4::class)
class SortKeyOrderingDaoTest {

    private lateinit var db: AmperfyDatabase
    private val dao by lazy { db.artistDao() }

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeAll_ordersBySortKeyBinaryHashLast() = runBlocking {
        // 打乱插入顺序，证明排序来自查询而非插入序
        dao.upsertRemote(
            listOf(
                DbTestFixture.artist(serverId = "z1", name = "Zebra"),
                DbTestFixture.artist(serverId = "a1", name = "Apple"),
                DbTestFixture.artist(serverId = "n1", name = "123"),
                DbTestFixture.artist(serverId = "b1", name = "Banana"),
                DbTestFixture.artist(serverId = "id2", name = "Same"),
                DbTestFixture.artist(serverId = "bj", name = "北京"),
                DbTestFixture.artist(serverId = "x1", name = "!!!"),
                DbTestFixture.artist(serverId = "a2", name = "apple2"),
                DbTestFixture.artist(serverId = "id1", name = "Same"),
            ),
        )

        val ordered = dao.observeAll(DbTestFixture.ACCT_A).first().map { it.name to it.serverId }

        assertEquals(
            listOf(
                "Apple" to "a1",   // A 分区，大写 'A'(0x41) 组内先于小写
                "apple2" to "a2",  // A 分区，小写 'a'(0x61)
                "Banana" to "b1",  // B 分区，'B'(0x42) 先于中文首字节
                "北京" to "bj",     // B 分区（拼音 BEI），UTF-8 首字节 0xE5 > 'B'
                "Same" to "id1",   // S 分区，sort_key 相同 → server_id 稳定 tie-break（id1<id2）
                "Same" to "id2",
                "Zebra" to "z1",   // Z 分区（分区序 25）
                "!!!" to "x1",     // # 组（分区序 99），严格晚于 Z；'!'(0x21) 先于 '1'
                "123" to "n1",     // # 组，'1'(0x31)
            ),
            ordered,
        )
    }
}
