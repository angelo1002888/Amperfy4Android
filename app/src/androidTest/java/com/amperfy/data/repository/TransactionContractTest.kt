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

package com.amperfy.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.DbTestFixture
import com.amperfy.testutil.RepositoryFixture
import kotlinx.coroutines.CancellationException
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
 * 写事务原子性合同（同步事务纪律）。
 *
 * 合同：
 * 1. 事务块中途异常 → 已写入的实体完整回滚，不得提交半套数据（P2 起 Room
 *    `database.withTransaction` 复测同一合同）；
 * 2. 事务块内抛出 [CancellationException] → 写入同样不提交，且异常按原类型向上传播，
 *    不得被吞成普通失败后提交半套数据。
 *
 * 直接在测试引擎上断言事务级合同，不经 Repository 方法——该行合同的主体是「事务」本身；
 * Repository 侧 Cancellation 传播已由 syncAlbumDetails 的显式 rethrow 承担（见 MusicRepository.kt
 * 该方法 catch 块注释）。
 *
 * **P5 起单引擎**：双引擎并存期（P3 批次 1b-P4）曾同时锁定旧引擎写原语与 Room
 * `database.withTransaction` 两组孪生用例；旧引擎随 P5 删除后其两例一并退役，只留
 * [roomRuntimeExceptionMidTransaction_rollsBackAllWrites] /
 * [roomCancellationInsideTransaction_doesNotCommitAndPropagatesAsCancellation]，
 * 锁定事务回滚 + Cancellation 原类型传播在 Room 上同样成立。
 */
@RunWith(AndroidJUnit4::class)
class TransactionContractTest {

    companion object {
        private const val SONG_ID_RUNTIME_ROOM = "tx-runtime-room"
        private const val SONG_ID_CANCEL_ROOM = "tx-cancel-room"
    }

    private lateinit var fx: RepositoryFixture

    @Before
    fun setUp() {
        fx = RepositoryFixture.create()
    }

    @After
    fun tearDown() {
        fx.close()
    }

    @Test
    fun roomRuntimeExceptionMidTransaction_rollsBackAllWrites() = runBlocking {
        val a = fx.accountA.ident
        try {
            fx.db.withTransaction {
                fx.db.songDao().upsertRemote(
                    listOf(DbTestFixture.song(a, SONG_ID_RUNTIME_ROOM, "Tx Song"))
                )
                throw RuntimeException("boom mid-transaction")
            }
            fail("withTransaction must rethrow the exception thrown inside the transaction block")
        } catch (e: RuntimeException) {
            assertEquals(
                "The original exception must propagate unchanged",
                "boom mid-transaction",
                e.message
            )
        }

        assertNull(
            "Row written before the mid-transaction exception must be rolled back",
            fx.db.songDao().getByServerId(a, SONG_ID_RUNTIME_ROOM)
        )
        assertEquals(
            "No partial data may be committed by the aborted transaction",
            0,
            DbTestFixture.countRows(fx.db, "song", a)
        )
    }

    @Test
    fun roomCancellationInsideTransaction_doesNotCommitAndPropagatesAsCancellation() = runBlocking {
        val a = fx.accountA.ident
        var thrown: Throwable? = null
        try {
            fx.db.withTransaction {
                fx.db.songDao().upsertRemote(
                    listOf(DbTestFixture.song(a, SONG_ID_CANCEL_ROOM, "Tx Song Cancel"))
                )
                throw CancellationException("cancelled mid-transaction")
            }
        } catch (t: Throwable) {
            thrown = t
        }

        // CancellationException 必须保持原类型向上传播，不得被转换成普通失败。
        // 只断言类型 + 不提交（按设计口径）——不断言 message：Room withTransaction 经
        // withContext 传播 Cancellation 时不保证透传原 message。
        assertTrue(
            "CancellationException must propagate as CancellationException, got: $thrown",
            thrown is CancellationException
        )

        assertNull(
            "Row written before the cancellation must not be committed",
            fx.db.songDao().getByServerId(a, SONG_ID_CANCEL_ROOM)
        )
        assertEquals(
            "No partial data may be committed by the cancelled transaction",
            0,
            DbTestFixture.countRows(fx.db, "song", a)
        )
    }
}
