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
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.entity.PlaylistSongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PlaylistSongDao 整表替换契约测试。
 *
 * 锁定：唯一写入口 replacePlaylistSongs 的保序/允许重复/完整替换/事务原子性；
 * (account_id, playlist_id, position) 主键令逐行换位必然冲突；playlist 删除级联清关系；
 * observeSongsOf 的 INNER JOIN 跳过未同步歌曲。
 */
@RunWith(AndroidJUnit4::class)
class PlaylistSongDaoTest {

    private lateinit var db: AmperfyDatabase
    private val dao by lazy { db.playlistSongDao() }

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
        // playlist_song FK → playlist(account_id, server_id)，须先有父 playlist 行
        db.playlistDao().upsertRemote(listOf(DbTestFixture.playlist(serverId = "p1")))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun replace_preservesOrderAndAllowsDuplicates() = runBlocking {
        // songIds 含重复 s1
        dao.replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", listOf("s1", "s2", "s1"))

        assertEquals(listOf("s1", "s2", "s1"), dao.getSongIdsOrdered(DbTestFixture.ACCT_A, "p1"))
        // position 0..N-1 连续
        assertEquals(
            listOf(0, 1, 2),
            DbTestFixture.queryPositions(db, DbTestFixture.ACCT_A, "p1"),
        )
    }

    @Test
    fun replace_replacesOldRowsCompletely() = runBlocking {
        dao.replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", listOf("a", "b", "c", "d"))
        // 二次替换为更短列表：旧行不得残留
        dao.replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", listOf("x", "y"))

        assertEquals(listOf("x", "y"), dao.getSongIdsOrdered(DbTestFixture.ACCT_A, "p1"))
        assertEquals(listOf(0, 1), DbTestFixture.queryPositions(db, DbTestFixture.ACCT_A, "p1"))
    }

    @Test
    fun directPositionSwap_throwsPkConflict() = runBlocking {
        // 绕过 replace 直接 insertAll 两行同 (account, playlist, position=0)
        try {
            dao.insertAll(
                listOf(
                    PlaylistSongEntity(DbTestFixture.ACCT_A, "p1", 0, "s1"),
                    PlaylistSongEntity(DbTestFixture.ACCT_A, "p1", 0, "s2"),
                ),
            )
            fail("expected SQLiteConstraintException for duplicate (account, playlist, position)")
        } catch (e: SQLiteConstraintException) {
            // 预期：主键冲突（锁定「逐行换位必然冲突」）
        }
        // @Insert 批量在单事务内，冲突整批回滚——表状态不变（仍为空）
        assertTrue(dao.getSongIdsOrdered(DbTestFixture.ACCT_A, "p1").isEmpty())
    }

    @Test
    fun replace_rollsBackAtomically() = runBlocking {
        dao.replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", listOf("s1", "s2", "s3"))

        try {
            db.withTransaction {
                dao.replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", listOf("z1", "z2"))
                error("boom")
            }
            fail("expected the transaction to abort")
        } catch (e: IllegalStateException) {
            // 预期：事务中途异常
        }
        // 旧顺序完整保留（事务回滚不半套）
        assertEquals(
            listOf("s1", "s2", "s3"),
            dao.getSongIdsOrdered(DbTestFixture.ACCT_A, "p1"),
        )
    }

    @Test
    fun playlistCascade_deletesSongRows() = runBlocking {
        dao.replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", listOf("s1", "s2"))
        // 删父 playlist 行（PlaylistDao 无 delete 方法，用 execSQL 测试内联）
        DbTestFixture.execSql(
            db,
            "DELETE FROM playlist WHERE account_id = ? AND server_id = ?",
            DbTestFixture.ACCT_A, "p1",
        )
        // playlist_song FK ON DELETE CASCADE：关系行随父级联清空
        assertTrue(dao.getSongIdsOrdered(DbTestFixture.ACCT_A, "p1").isEmpty())
        assertEquals(0, DbTestFixture.countRows(db, "playlist_song", DbTestFixture.ACCT_A))
    }

    @Test
    fun observeSongsOf_innerJoinSkipsUnsyncedSongs() = runBlocking {
        // 只同步 s1、s3 到 song 表，s2 未同步
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(serverId = "s1", title = "First"),
                DbTestFixture.song(serverId = "s3", title = "Third"),
            ),
        )
        dao.replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", listOf("s1", "s2", "s3"))

        val songs = dao.observeSongsOf(DbTestFixture.ACCT_A, "p1").first()
        // INNER JOIN 只含已同步歌曲且按 position 保序
        assertEquals(listOf("s1", "s3"), songs.map { it.serverId })
    }
}
