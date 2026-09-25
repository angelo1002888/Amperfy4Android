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

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.entity.PlaybackStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PlaybackDao 三队列 + 状态原子替换契约测试。
 *
 * 锁定：replaceAllQueues 整队列替换 + 同事务保存状态、二次替换旧行全清、空队列边界仅清队列不丢状态、
 * (queue_type, position) 主键令逐行换位冲突、clearAll 清状态与队列、跨账户 item 共存恢复。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackDaoTest {

    private lateinit var db: AmperfyDatabase
    private val dao by lazy { db.playbackDao() }

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun state(savedAt: Long, musicIndex: Int = 0) =
        PlaybackStateEntity(id = 1, musicIndex = musicIndex, savedAt = savedAt)

    @Test
    fun replaceAllQueues_savesAtomicallyAndReplaces() = runBlocking {
        val items = listOf(
            DbTestFixture.queueItem("PLAYLIST", 0, entityId = "a"),
            DbTestFixture.queueItem("PLAYLIST", 1, entityId = "b"),
            DbTestFixture.queueItem("USER", 0, entityId = "c"),
            DbTestFixture.queueItem("CONTEXT", 0, entityId = "d"),
        )
        dao.replaceAllQueues(state(savedAt = 100, musicIndex = 3), items)

        assertEquals(3, dao.getState()?.musicIndex)
        // 按 position 保序
        assertEquals(listOf("a", "b"), dao.getQueueItems("PLAYLIST").map { it.entityId })
        assertEquals(listOf("c"), dao.getQueueItems("USER").map { it.entityId })
        assertEquals(listOf("d"), dao.getQueueItems("CONTEXT").map { it.entityId })

        // 二次替换：旧行全清、状态更新
        dao.replaceAllQueues(
            state(savedAt = 200, musicIndex = 9),
            listOf(DbTestFixture.queueItem("PLAYLIST", 0, entityId = "z")),
        )
        assertEquals(9, dao.getState()?.musicIndex)
        assertEquals(listOf("z"), dao.getQueueItems("PLAYLIST").map { it.entityId })
        assertTrue(dao.getQueueItems("USER").isEmpty())
        assertTrue(dao.getQueueItems("CONTEXT").isEmpty())
    }

    @Test
    fun replaceAllQueues_emptyItemsClearsQueuesButSavesState() = runBlocking {
        dao.replaceAllQueues(
            state(savedAt = 100),
            listOf(DbTestFixture.queueItem("PLAYLIST", 0, entityId = "a")),
        )
        // 空队列边界：只清队列但状态仍保存
        dao.replaceAllQueues(state(savedAt = 300, musicIndex = 7), emptyList())

        assertTrue(dao.getQueueItems("PLAYLIST").isEmpty())
        assertTrue(dao.getQueueItems("USER").isEmpty())
        assertTrue(dao.getQueueItems("CONTEXT").isEmpty())
        assertEquals(7, dao.getState()?.musicIndex)
        assertEquals(300L, dao.getState()?.savedAt)
    }

    @Test
    fun directInsert_duplicatePosition_throwsPkConflict() = runBlocking {
        try {
            dao.insertQueueItems(
                listOf(
                    DbTestFixture.queueItem("PLAYLIST", 0, entityId = "a"),
                    DbTestFixture.queueItem("PLAYLIST", 0, entityId = "b"),
                ),
            )
            fail("expected SQLiteConstraintException for duplicate (queue_type, position)")
        } catch (e: SQLiteConstraintException) {
            // 预期：主键冲突
        }
        assertTrue(dao.getQueueItems("PLAYLIST").isEmpty())
    }

    @Test
    fun clearAll_removesStateAndQueues() = runBlocking {
        dao.replaceAllQueues(
            state(savedAt = 100),
            listOf(DbTestFixture.queueItem("PLAYLIST", 0, entityId = "a")),
        )
        dao.clearAll()

        assertNull(dao.getState())
        assertTrue(dao.getQueueItems("PLAYLIST").isEmpty())
    }

    @Test
    fun crossAccountItems_persistAndRestore() = runBlocking {
        // 同一 PLAYLIST 队列内不同 account_id 的 item 共存（跨账户队列）
        val items = listOf(
            DbTestFixture.queueItem("PLAYLIST", 0, accountId = DbTestFixture.ACCT_A, entityId = "a"),
            DbTestFixture.queueItem("PLAYLIST", 1, accountId = DbTestFixture.ACCT_B, entityId = "b"),
        )
        dao.replaceAllQueues(state(savedAt = 100), items)

        val restored = dao.getQueueItems("PLAYLIST")
        assertEquals(listOf("a", "b"), restored.map { it.entityId })
        assertEquals(
            listOf(DbTestFixture.ACCT_A, DbTestFixture.ACCT_B),
            restored.map { it.accountId },
        )
    }
}
