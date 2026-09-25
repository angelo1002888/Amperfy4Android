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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.DbTestFixture
import com.amperfy.testutil.RepositoryFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 多账户同 serverId 隔离合同。
 *
 * 合同：schema v13 复合本地主键 localId = "$accountId:$serverId" 下，账户 A 与账户 B
 * 存在**相同 serverId** 的实体时，绑定各账户的 MusicRepositoryImpl（boundAccountInfo 路径，
 * currentAccountId = ident）在单实体查询/列表 Flow/删除后残留/计数四类路径上互不可见、互不影响。
 * Repository 对外接口一律使用服务端 id（serverId），复合主键为内部实现细节。
 *
 * P3 批次 1b：library 域实体 seed 换 Room（两账户各插 scope 由 fixture 完成），读/计数走
 * Room 复合主键 (account_id, server_id) + WHERE account_id；删除隔离用直插 DELETE 模拟。
 * playlist 等未迁移域未涉及本用例。场景/断言语义逐条不变。
 */
@RunWith(AndroidJUnit4::class)
class MultiAccountIsolationContractTest {

    companion object {
        private const val SHARED_SONG_ID = "song1"
        private const val SHARED_ALBUM_ID = "album1"
        private const val SHARED_ARTIST_ID = "artist1"
    }

    private lateinit var fx: RepositoryFixture

    @Before
    fun setUp() {
        fx = RepositoryFixture.create()
        seedFixtures()
    }

    @After
    fun tearDown() {
        fx.close()
    }

    /**
     * 测试数据：账户 A 与账户 B 各有一组 Song/Album/Artist，serverId 完全相同、名称不同（Room 插行）。
     */
    private fun seedFixtures() = runBlocking {
        val a = fx.accountA.ident
        val b = fx.accountB.ident
        fx.db.artistDao().upsertRemote(
            listOf(
                DbTestFixture.artist(a, SHARED_ARTIST_ID, "Artist A"),
                DbTestFixture.artist(b, SHARED_ARTIST_ID, "Artist B"),
            )
        )
        fx.db.albumDao().upsertRemote(
            listOf(
                DbTestFixture.album(a, SHARED_ALBUM_ID, "Album A"),
                DbTestFixture.album(b, SHARED_ALBUM_ID, "Album B"),
            )
        )
        fx.db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(a, SHARED_SONG_ID, "Song A1"),
                DbTestFixture.song(b, SHARED_SONG_ID, "Song B1"),
            )
        )
        Unit
    }

    @Test
    fun getById_returnsOwnAccountEntityForSameServerId() = runBlocking {
        assertEquals(
            "Repository bound to account A must resolve the shared song id to A's entity",
            "Song A1",
            fx.repositoryA.getSongById(SHARED_SONG_ID)?.title
        )
        assertEquals(
            "Repository bound to account B must resolve the shared song id to B's entity",
            "Song B1",
            fx.repositoryB.getSongById(SHARED_SONG_ID)?.title
        )
        assertEquals(
            "Repository bound to account A must resolve the shared album id to A's entity",
            "Album A",
            fx.repositoryA.getAlbumById(SHARED_ALBUM_ID)?.name
        )
        assertEquals(
            "Repository bound to account B must resolve the shared album id to B's entity",
            "Album B",
            fx.repositoryB.getAlbumById(SHARED_ALBUM_ID)?.name
        )
    }

    @Test
    fun listQueries_containOnlyOwnAccountData() = runBlocking {
        assertEquals(
            "getAllSongs of account A must contain exactly A's songs",
            listOf("Song A1"),
            fx.repositoryA.getAllSongs().first().map { it.title }
        )
        assertEquals(
            "getAllSongs of account B must contain exactly B's songs",
            listOf("Song B1"),
            fx.repositoryB.getAllSongs().first().map { it.title }
        )
        assertEquals(
            "getAllAlbums of account A must contain exactly A's albums",
            listOf("Album A"),
            fx.repositoryA.getAllAlbums().first().map { it.name }
        )
        assertEquals(
            "getAllAlbums of account B must contain exactly B's albums",
            listOf("Album B"),
            fx.repositoryB.getAllAlbums().first().map { it.name }
        )
    }

    /**
     * 删除隔离：现有差集/删除公开方法均为先服务器后本地的网络路径，本批次不触网络命令流，
     * 故按任务约定直接以 DELETE 模拟「删除账户 A 的实体」（允许测试内联 SQL），锁定复合主键
     * (account_id, server_id) 删除边界——同 serverId 的账户 B 实体不受影响。
     */
    @Test
    fun deletingOneAccountsEntity_keepsSameServerIdEntityOfOtherAccount() = runBlocking {
        assertNotNull(
            "Precondition: account A's song must exist before deletion",
            fx.repositoryA.getSongById(SHARED_SONG_ID)
        )
        DbTestFixture.execSql(
            fx.db,
            "DELETE FROM song WHERE account_id = ? AND server_id = ?",
            fx.accountA.ident,
            SHARED_SONG_ID,
        )

        assertNull(
            "Account A's song must be gone after deletion",
            fx.repositoryA.getSongById(SHARED_SONG_ID)
        )
        assertEquals(
            "Account B's song with the same serverId must survive deletion of A's entity",
            "Song B1",
            fx.repositoryB.getSongById(SHARED_SONG_ID)?.title
        )
    }

    @Test
    fun counts_areIsolatedPerAccount() = runBlocking {
        // 给账户 A 追加一首歌，制造 A/B 计数不同（Room 插行）
        fx.db.songDao().upsertRemote(listOf(DbTestFixture.song(fx.accountA.ident, "song2", "Song A2")))

        assertEquals(
            "Account A song count must include only A's songs",
            2L,
            fx.repositoryA.observeSongCount().first()
        )
        assertEquals(
            "Account B song count must be unaffected by A's additional songs",
            1L,
            fx.repositoryB.observeSongCount().first()
        )
        assertEquals(
            "Account A album count must include only A's albums",
            1L,
            fx.repositoryA.observeAlbumCount().first()
        )
        assertEquals(
            "Account B album count must include only B's albums",
            1L,
            fx.repositoryB.observeAlbumCount().first()
        )
    }
}
