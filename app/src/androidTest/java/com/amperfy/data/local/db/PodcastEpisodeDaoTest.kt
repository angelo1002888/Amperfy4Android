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
import com.amperfy.data.model.PodcastEpisodeRemoteStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PodcastEpisodeDao 软删除差集契约测试。
 *
 * 锁定：markDeletedMissing 只标 keepIds 外单集 = DELETED.raw（不物理删）、不影响其他频道/账户；
 * 空 keepIds（NOT IN () 边界）= 全标删除（与 P1 行为一致）；父频道删除级联删单集，
 * podcast_id 为 NULL 的孤儿单集不受频道级联影响。
 */
@RunWith(AndroidJUnit4::class)
class PodcastEpisodeDaoTest {

    private lateinit var db: AmperfyDatabase
    private val dao by lazy { db.podcastEpisodeDao() }

    private val deletedRaw = PodcastEpisodeRemoteStatus.DELETED.raw
    private val newRaw = PodcastEpisodeRemoteStatus.NEW.raw

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
        // 单集 FK → podcast(account_id, server_id)，须先有父频道行
        db.podcastDao().upsertRemote(
            listOf(
                DbTestFixture.podcast(serverId = "pod1"),
                DbTestFixture.podcast(serverId = "pod2"),
                DbTestFixture.podcast(accountId = DbTestFixture.ACCT_B, serverId = "pod1"),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun statusOf(accountId: String, serverId: String): Int =
        dao.getByServerId(accountId, serverId)!!.status

    @Test
    fun markDeletedMissing_marksOnlyMissing() = runBlocking {
        dao.upsertRemote(
            listOf(
                DbTestFixture.episode(serverId = "e1", podcastId = "pod1", status = newRaw),
                DbTestFixture.episode(serverId = "e2", podcastId = "pod1", status = newRaw),
                DbTestFixture.episode(serverId = "e3", podcastId = "pod1", status = newRaw),
                DbTestFixture.episode(serverId = "e4", podcastId = "pod2", status = newRaw),
                DbTestFixture.episode(
                    accountId = DbTestFixture.ACCT_B,
                    serverId = "e5",
                    podcastId = "pod1",
                    status = newRaw,
                ),
            ),
        )

        dao.markDeletedMissing(DbTestFixture.ACCT_A, "pod1", listOf("e1", "e2"), deletedRaw)

        // keepIds 外的 e3 标删除，keep 的 e1/e2 不变
        assertEquals(deletedRaw, statusOf(DbTestFixture.ACCT_A, "e3"))
        assertEquals(newRaw, statusOf(DbTestFixture.ACCT_A, "e1"))
        assertEquals(newRaw, statusOf(DbTestFixture.ACCT_A, "e2"))
        // 其他频道 pod2 不受影响
        assertEquals(newRaw, statusOf(DbTestFixture.ACCT_A, "e4"))
        // 其他账户 acctB/pod1 不受影响
        assertEquals(newRaw, statusOf(DbTestFixture.ACCT_B, "e5"))
    }

    @Test
    fun markDeletedMissing_emptyKeepIds_marksAll() = runBlocking {
        dao.upsertRemote(
            listOf(
                DbTestFixture.episode(serverId = "e1", podcastId = "pod1", status = newRaw),
                DbTestFixture.episode(serverId = "e2", podcastId = "pod1", status = newRaw),
                DbTestFixture.episode(serverId = "e4", podcastId = "pod2", status = newRaw),
            ),
        )

        // 空存活集：NOT IN () 边界 = 全标删除（与 P1 行为一致）
        dao.markDeletedMissing(DbTestFixture.ACCT_A, "pod1", emptyList(), deletedRaw)

        assertEquals(deletedRaw, statusOf(DbTestFixture.ACCT_A, "e1"))
        assertEquals(deletedRaw, statusOf(DbTestFixture.ACCT_A, "e2"))
        // 其他频道不受影响
        assertEquals(newRaw, statusOf(DbTestFixture.ACCT_A, "e4"))
    }

    @Test
    fun podcastCascade_deletesEpisodes() = runBlocking {
        dao.upsertRemote(
            listOf(
                DbTestFixture.episode(serverId = "e1", podcastId = "pod1", status = newRaw),
                // podcast_id 为 NULL 的孤儿单集（复合 FK 含 NULL 不强制）
                DbTestFixture.episode(serverId = "orphan", podcastId = null, status = newRaw),
            ),
        )

        // 删父频道 pod1（用 execSQL 测试内联）
        DbTestFixture.execSql(
            db,
            "DELETE FROM podcast WHERE account_id = ? AND server_id = ?",
            DbTestFixture.ACCT_A, "pod1",
        )

        // 强 FK ON DELETE CASCADE：pod1 单集随频道删除
        assertNull(dao.getByServerId(DbTestFixture.ACCT_A, "e1"))
        // podcast_id 为 NULL 的单集不受频道级联影响（仍靠 account_scope 参与账户级联）
        assertNotNull(dao.getByServerId(DbTestFixture.ACCT_A, "orphan"))
    }
}
