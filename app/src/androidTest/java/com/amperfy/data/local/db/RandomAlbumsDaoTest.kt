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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * randomCachedAlbums / randomAlbums 契约测试。
 *
 * 目标 SQL 与 Cached 热修合同的 DAO 级镜像：Cached 唯一判断依据是歌曲 cache_path
 * （EXISTS + JOIN song_local_state），不读 album.isCached。锁定：仅有缓存歌曲的专辑出现、
 * 无缓存歌曲/cache_path 为 NULL 的绝不出现、账户隔离、LIMIT 生效。
 */
@RunWith(AndroidJUnit4::class)
class RandomAlbumsDaoTest {

    private lateinit var db: AmperfyDatabase
    private val albumDao by lazy { db.albumDao() }
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
    fun randomCachedAlbums_returnsOnlyAlbumsWithCachedSongs() = runBlocking {
        // A1：有缓存歌曲 → 应出现
        albumDao.upsertRemote(listOf(DbTestFixture.album(serverId = "A1")))
        songDao.upsertRemote(listOf(DbTestFixture.song(serverId = "sa", albumId = "A1")))
        localDao.setCachePath(DbTestFixture.ACCT_A, "sa", "songs/sa.mp3")

        // A2：歌曲有本地状态行但 cache_path 为 NULL → 不算缓存
        albumDao.upsertRemote(listOf(DbTestFixture.album(serverId = "A2")))
        songDao.upsertRemote(listOf(DbTestFixture.song(serverId = "sb", albumId = "A2")))
        localDao.setCachePath(DbTestFixture.ACCT_A, "sb", null)

        // A3：无任何歌曲 → 不出现
        albumDao.upsertRemote(listOf(DbTestFixture.album(serverId = "A3")))

        val result = albumDao.randomCachedAlbums(DbTestFixture.ACCT_A, 20)
        assertEquals(setOf("A1"), result.map { it.serverId }.toSet())
    }

    @Test
    fun randomCachedAlbums_accountIsolation() = runBlocking {
        // acctA 缓存专辑 A1
        albumDao.upsertRemote(listOf(DbTestFixture.album(DbTestFixture.ACCT_A, "A1")))
        songDao.upsertRemote(listOf(DbTestFixture.song(DbTestFixture.ACCT_A, "sa", albumId = "A1")))
        localDao.setCachePath(DbTestFixture.ACCT_A, "sa", "songs/sa.mp3")

        // acctB 缓存专辑 B1（同 serverId 空间不同账户）
        albumDao.upsertRemote(listOf(DbTestFixture.album(DbTestFixture.ACCT_B, "B1")))
        songDao.upsertRemote(listOf(DbTestFixture.song(DbTestFixture.ACCT_B, "sb", albumId = "B1")))
        localDao.setCachePath(DbTestFixture.ACCT_B, "sb", "songs/sb.mp3")

        val result = albumDao.randomCachedAlbums(DbTestFixture.ACCT_A, 20)
        assertEquals(setOf("A1"), result.map { it.serverId }.toSet())
    }

    @Test
    fun randomAlbums_respectsLimit() = runBlocking {
        albumDao.upsertRemote(
            (1..5).map { DbTestFixture.album(serverId = "A$it") },
        )

        assertEquals(3, albumDao.randomAlbums(DbTestFixture.ACCT_A, 3).size)
    }
}
