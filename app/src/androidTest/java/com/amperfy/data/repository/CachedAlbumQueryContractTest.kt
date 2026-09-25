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
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.DbTestFixture
import com.amperfy.data.local.db.store.RoomLibraryLocalStore
import com.amperfy.data.local.store.LibraryLocalStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cached 随机专辑查询的纠正式合同测试（P0 bug，修复提交 01485a5）。
 *
 * 合同：Home「Cached 随机专辑」的判据是「专辑名下存在已缓存歌曲」，而非无生产写入路径、
 * 恒为 false 的专辑 isCached 存储字段——后者会导致 Cached 模式恒空。
 *
 * 判据 = AlbumWithState 的计算列 is_cached（EXISTS 属于该专辑且 song_local_state.cache_path
 * 非空的歌曲，即缓存合同的读侧形态），经 [RoomLibraryLocalStore.randomAlbums]（HomeRepository
 * 委托的 Home 读路径）(onlyCached=true) 复测；同时锁定多账户隔离（WHERE account_id）。
 *
 * seed 经 Room DAO 插行（含 song_local_state.cache_path 缓存态），断言经读路径读回领域模型 id 集合。
 * randomAlbums 返回随机顺序，故断言全部走集合比较（不依赖顺序）。
 */
@RunWith(AndroidJUnit4::class)
class CachedAlbumQueryContractTest {

    companion object {
        private const val ACCOUNT_A = DbTestFixture.ACCT_A
        private const val ACCOUNT_B = DbTestFixture.ACCT_B

        /** 足够大，一次抽尽本账户全部候选专辑（合同关心的是集合成员，非随机采样） */
        private const val COUNT = 10
    }

    private lateinit var db: AmperfyDatabase
    private lateinit var store: LibraryLocalStore

    @Before
    fun setUp() {
        db = DbTestFixture.openDatabase()
        store = RoomLibraryLocalStore(db)
        seedFixtures()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 测试数据（与迁移前一致）：
     * - 账户 A：专辑 X（1 首已缓存歌）、专辑 Y（1 首未缓存歌）、专辑 Z（无歌）
     * - 账户 B：专辑 W（1 首已缓存歌）
     * 缓存态经 song_local_state.cache_path（非空 = 已缓存）表达。
     */
    private fun seedFixtures() = runBlocking {
        DbTestFixture.seedScopes(db)

        db.albumDao().upsertRemote(
            listOf(
                DbTestFixture.album(ACCOUNT_A, "albumX", "Album X"),
                DbTestFixture.album(ACCOUNT_A, "albumY", "Album Y"),
                DbTestFixture.album(ACCOUNT_A, "albumZ", "Album Z"),
                DbTestFixture.album(ACCOUNT_B, "albumW", "Album W"),
            )
        )
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(ACCOUNT_A, "songX1", "Song X1", albumId = "albumX"),
                DbTestFixture.song(ACCOUNT_A, "songY1", "Song Y1", albumId = "albumY"),
                DbTestFixture.song(ACCOUNT_B, "songW1", "Song W1", albumId = "albumW"),
            )
        )
        // 仅 X/W 名下歌曲已缓存（cache_path 非空）；Y 名下歌曲未缓存
        db.songLocalStateDao().setCachePath(ACCOUNT_A, "songX1", "/cache/x1.mp3")
        db.songLocalStateDao().setCachePath(ACCOUNT_B, "songW1", "/cache/w1.mp3")
    }

    @Test
    fun cachedFilter_includesOnlyAlbumsWithDownloadedSongs() = runBlocking {
        val results = store.randomAlbums(ACCOUNT_A, COUNT, onlyCached = true)

        assertEquals(
            "Cached filter must return exactly the albums having downloaded songs",
            setOf("albumX"),
            results.map { it.id }.toSet()
        )
    }

    @Test
    fun cachedFilter_isAccountIsolated() = runBlocking {
        val resultsA = store.randomAlbums(ACCOUNT_A, COUNT, onlyCached = true)
        val resultsB = store.randomAlbums(ACCOUNT_B, COUNT, onlyCached = true)

        assertTrue(
            "Account A results must not contain account B's album W",
            resultsA.none { it.id == "albumW" }
        )
        assertEquals(
            "Account B must see exactly its own cached album W",
            setOf("albumW"),
            resultsB.map { it.id }.toSet()
        )
    }

    @Test
    fun uncachedQuery_returnsAllAccountAlbums() = runBlocking {
        val results = store.randomAlbums(ACCOUNT_A, COUNT, onlyCached = false)

        assertEquals(
            "Without the cached filter all of account A's albums must be returned",
            setOf("albumX", "albumY", "albumZ"),
            results.map { it.id }.toSet()
        )
    }
}
