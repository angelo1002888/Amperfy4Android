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
import com.amperfy.data.local.db.store.RoomAccountLocalStore
import com.amperfy.data.local.db.store.RoomDownloadLocalStore
import com.amperfy.data.model.Account
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.BackendApiType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AccountLocalStore 的行为合同测试（专题 15 P1 批次 3 建立，P4 批次 1 介质换绑 + **断言翻转**）。
 *
 * 介质改为 Room 内存库 + [RoomAccountLocalStore]（照 SearchHistoryStoreContractTest 的换绑模式，
 * 直接实例化 Store，不预 seed 租户根——建根正是本 Store 的职责）。
 *
 * 设计要点：**账户元数据（serverUrl/userName/apiType）不落库**——租户根 account_scope 只存
 * account_id，账户资料唯一来源是 CredentialsManager。
 * 故合同锁定账户生命周期对租户根的维护：
 * - upsertAccount 建 account_scope 行，重复 upsert 幂等（不炸、仍单行）；
 * - deleteAccount 删行并经 ON DELETE CASCADE 级联清光该账户全部账户级表数据（P4 logout 即时清理），
 *   其他账户数据不受影响；
 * - 删不存在的 ident 静默不抛异常。
 */
@RunWith(AndroidJUnit4::class)
class AccountLocalStoreContractTest {

    private lateinit var db: AmperfyDatabase
    private lateinit var store: RoomAccountLocalStore

    @Before
    fun setUp() {
        db = DbTestFixture.openDatabase()
        store = RoomAccountLocalStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun accountOf(serverUrl: String, userName: String): Account {
        val info = AccountInfo.create(serverUrl, userName)
        return Account(info, serverUrl, userName, BackendApiType.SUBSONIC)
    }

    @Test
    fun upsert_createsScopeRowIdempotently() = runBlocking {
        val account = accountOf("https://server-a.example.com", "userA")
        store.upsertAccount(account)

        // 同 ident 再 upsert（元数据变化对库侧无影响，只需幂等不炸）
        store.upsertAccount(account.copy(serverUrl = "https://server-a-new.example.com"))

        val scopes = db.accountScopeDao().getAll()
        assertEquals("Same ident must not produce duplicate scope rows", 1, scopes.size)
        assertEquals(account.info.ident, scopes.first().accountId)
    }

    @Test
    fun delete_removesScopeAndCascadesAccountData() = runBlocking {
        val accountA = accountOf("https://server-a.example.com", "userA")
        val accountB = accountOf("https://server-b.example.com", "userB")
        val identA = accountA.info.ident
        val identB = accountB.info.ident
        store.upsertAccount(accountA)
        store.upsertAccount(accountB)

        // 两账户各 seed 一首歌 + 一条下载记录（下载记录走生产写入口）
        val downloadStore = RoomDownloadLocalStore(db)
        db.songDao().upsertRemote(listOf(DbTestFixture.song(identA, "s1")))
        db.songDao().upsertRemote(listOf(DbTestFixture.song(identB, "s1")))
        downloadStore.upsertRequested(identA, "s1", now = 100)
        downloadStore.upsertRequested(identB, "s1", now = 100)

        store.deleteAccount(identA)

        assertEquals(
            "Account A scope must be removed",
            listOf(identB),
            db.accountScopeDao().getAll().map { it.accountId },
        )
        assertEquals("Account A songs must cascade", 0, DbTestFixture.countRows(db, "song", identA))
        assertEquals(
            "Account A downloads must cascade",
            0,
            DbTestFixture.countRows(db, "download_entry", identA),
        )
        assertEquals("Account B songs must remain", 1, DbTestFixture.countRows(db, "song", identB))
        assertEquals(
            "Account B downloads must remain",
            1,
            DbTestFixture.countRows(db, "download_entry", identB),
        )
    }

    @Test
    fun delete_unknownIdentIsSilent() = runBlocking {
        // 不存在的 ident 不抛异常（登出重入/半途失败重试的幂等要求）
        store.deleteAccount("nonexistent-ident")
        assertEquals(0, db.accountScopeDao().getAll().size)
    }
}
