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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 服务器写与本地写物理隔离契约测试。
 *
 * 锁定：remote upsert 只写 song 表、物理上无法触碰 song_local_state 三字段
 * （cache_path/play_progress/play_count）；本地状态方法行不存在时先建后改（upsert 语义）；
 * setCachePath(null) 清缓存标记；song 行远端删除不连带删除本地状态（song_local_state 刻意不 FK song）。
 */
@RunWith(AndroidJUnit4::class)
class SongLocalStateOwnershipTest {

    private lateinit var db: AmperfyDatabase
    private val songDao by lazy { db.songDao() }
    private val localDao by lazy { db.songLocalStateDao() }

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
    fun remoteUpsert_cannotTouchLocalState() = runBlocking {
        // 先写远端快照 v1，再写全部本地状态
        songDao.upsertRemote(listOf(DbTestFixture.song(serverId = "s1", title = "Title v1")))
        localDao.setCachePath(DbTestFixture.ACCT_A, "s1", "songs/s1.mp3")
        localDao.setProgress(DbTestFixture.ACCT_A, "s1", progressMs = 1234, updatedAt = 999)
        localDao.incrementPlayCount(DbTestFixture.ACCT_A, "s1")

        // 远端 upsert 同 id 新快照（title 变更）
        songDao.upsertRemote(listOf(DbTestFixture.song(serverId = "s1", title = "Title v2")))

        // song 表元数据更新
        assertEquals("Title v2", songDao.getByServerId(DbTestFixture.ACCT_A, "s1")?.title)
        // song_local_state 三字段原封不动（物理隔离）
        val local = localDao.get(DbTestFixture.ACCT_A, "s1")!!
        assertEquals("songs/s1.mp3", local.cachePath)
        assertEquals(1234L, local.playProgressMs)
        assertEquals(999L, local.playProgressUpdatedAt)
        assertEquals(1, local.playCount)
    }

    @Test
    fun incrementPlayCount_fromAbsentRow_createsThenBumps() = runBlocking {
        // 行不存在时：先建（play_count=0）再 +1
        localDao.incrementPlayCount(DbTestFixture.ACCT_A, "s2")

        assertEquals(1, localDao.get(DbTestFixture.ACCT_A, "s2")?.playCount)
    }

    @Test
    fun setCachePath_null_clearsCacheMarker() = runBlocking {
        localDao.setCachePath(DbTestFixture.ACCT_A, "s3", "songs/s3.mp3")
        assertEquals("songs/s3.mp3", localDao.get(DbTestFixture.ACCT_A, "s3")?.cachePath)

        localDao.setCachePath(DbTestFixture.ACCT_A, "s3", null)
        assertNull(localDao.get(DbTestFixture.ACCT_A, "s3")?.cachePath)
    }

    @Test
    fun localState_survivesRemoteSongDeletion() = runBlocking {
        songDao.upsertRemote(listOf(DbTestFixture.song(serverId = "s4")))
        localDao.setCachePath(DbTestFixture.ACCT_A, "s4", "songs/s4.mp3")

        // 删 song 行（用 execSQL 模拟远端差集删除）
        DbTestFixture.execSql(
            db,
            "DELETE FROM song WHERE account_id = ? AND server_id = ?",
            DbTestFixture.ACCT_A, "s4",
        )

        // song_local_state 不 FK song，本地状态行仍在（不连带删除）
        val local = localDao.get(DbTestFixture.ACCT_A, "s4")
        assertNotNull(local)
        assertEquals("songs/s4.mp3", local?.cachePath)
    }
}
