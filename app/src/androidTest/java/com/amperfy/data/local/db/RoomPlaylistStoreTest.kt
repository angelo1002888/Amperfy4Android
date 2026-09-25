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
import com.amperfy.data.local.db.store.RoomPlaylistLocalStore
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RoomPlaylistLocalStore 读写合同（专题 15 P3 批次 2a）。
 *
 * 直接实例化 [RoomPlaylistLocalStore]，内存库 + seedScopes，runBlocking。
 * 逐用例锁定语义：整表替换保序/重复、counts 按歌曲重算、
 * 详情未同步时只累加不重置、本地缺失歌只计数不建关系、prune 级联清 playlist_song + 显式清
 * playlist_local_state、last_played 归本地状态且缺失静默跳过、缓存 EXISTS 推导、跨账户隔离，
 * 以及 observeSongsWithLocalOf 表级失效再发射（2b「keyPaths 深度 2 通知」的 Room 等价物）。
 */
@RunWith(AndroidJUnit4::class)
class RoomPlaylistStoreTest {

    private lateinit var db: AmperfyDatabase
    private lateinit var store: RoomPlaylistLocalStore
    private val acct = DbTestFixture.ACCT_A

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
        store = RoomPlaylistLocalStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun playlist(
        id: String,
        name: String = "PL $id",
        songCount: Int = 0,
        duration: Int = 0,
    ) = Playlist(id = id, name = name, songCount = songCount, duration = duration)

    private fun song(id: String, duration: Int = 0) =
        Song(id = id, title = "Song $id", artist = "A", album = "Al", duration = duration, path = "p")

    /** 直接把歌曲元数据写入 song 表（供 append/reorder 断言本地存在与否）。 */
    private suspend fun seedSong(id: String, duration: Int = 0) =
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, id).copy(duration = duration)))

    private suspend fun orderedIds(playlistId: String) =
        db.playlistSongDao().getSongIdsOrdered(acct, playlistId)

    // ---- applyPlaylistDetails ----

    @Test
    fun applyPlaylistDetails_preservesOrder() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1", songCount = 99, duration = 999), listOf(song("s1"), song("s2"), song("s3")))

        assertEquals(listOf("s1", "s2", "s3"), store.getPlaylistSongs(acct, "p1").first().map { it.id })
    }

    @Test
    fun applyPlaylistDetails_duplicatesKeepBothPositions() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1"), song("s2"), song("s1")))

        assertEquals(listOf("s1", "s2", "s1"), store.getPlaylistSongs(acct, "p1").first().map { it.id })
    }

    @Test
    fun applyPlaylistDetails_reSyncReplacesAndRecomputesCountsIgnoringMetadata() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1", songCount = 100, duration = 5000), listOf(song("s1", 10), song("s2", 20)))
        // 重同步整表替换，metadata 自带错误 songCount/duration 被忽略，按歌曲重算
        store.applyPlaylistDetails(acct, "p1", playlist("p1", songCount = 777, duration = 8888), listOf(song("s3", 30)))

        assertEquals(listOf("s3"), store.getPlaylistSongs(acct, "p1").first().map { it.id })
        val pl = store.getPlaylistById(acct, "p1")!!
        assertEquals(1, pl.songCount)
        assertEquals(30, pl.duration)
    }

    // ---- upsertPlaylistsMetadata ----

    @Test
    fun upsertPlaylistsMetadata_doesNotTouchPlaylistSong() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1"), song("s2")))

        store.upsertPlaylistsMetadata(acct, listOf(playlist("p1", name = "Renamed", songCount = 42)))

        assertEquals(listOf("s1", "s2"), orderedIds("p1")) // 关系未被触碰
        assertEquals("Renamed", store.getPlaylistById(acct, "p1")!!.name)
    }

    // ---- replacePlaylists ----

    @Test
    fun replacePlaylists_prunesDiffCascadeAndLocalState() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1")))
        store.applyPlaylistDetails(acct, "p2", playlist("p2"), listOf(song("s2")))
        store.updatePlaylistLastPlayed(acct, "p2", 555)

        store.replacePlaylists(acct, listOf(playlist("p1")))

        assertNotNull(store.getPlaylistById(acct, "p1"))
        assertNull(store.getPlaylistById(acct, "p2"))
        // 被删列表 p2 的 playlist_song 随 FK 级联清空
        assertTrue(orderedIds("p2").isEmpty())
        // 被删列表 p2 的 playlist_local_state 显式清除（不级联 playlist，须 Store 清）
        assertNull(db.playlistLocalStateDao().get(acct, "p2"))
    }

    // ---- appendPlaylistSongs ----

    @Test
    fun appendPlaylistSongs_detailsSynced_appendsRelationAndAccumulatesCounts() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1", 10)))
        seedSong("s2", 20)

        store.appendPlaylistSongs(acct, "p1", listOf("s2"))

        assertEquals(listOf("s1", "s2"), orderedIds("p1"))
        val pl = store.getPlaylistById(acct, "p1")!!
        assertEquals(2, pl.songCount)
        assertEquals(30, pl.duration)
    }

    @Test
    fun appendPlaylistSongs_detailsNotSynced_noRelationAndCountsAccumulateNotReset() = runBlocking {
        // 仅有服务器元数据 songCount=5、无关系行 → detailsSynced = false
        store.upsertPlaylistsMetadata(acct, listOf(playlist("p1", songCount = 5, duration = 100)))
        seedSong("s1", 20)

        store.appendPlaylistSongs(acct, "p1", listOf("s1"))

        assertTrue(orderedIds("p1").isEmpty()) // 未同步详情不建关系
        val pl = store.getPlaylistById(acct, "p1")!!
        assertEquals(6, pl.songCount) // 5 + 1，不被重置
        assertEquals(120, pl.duration) // 100 + 20
    }

    @Test
    fun appendPlaylistSongs_localMissingSong_countsIncludeMissingRelationOnlyPresent() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1", 10)))
        seedSong("s2", 20)
        // sMissing 不在 song 表

        store.appendPlaylistSongs(acct, "p1", listOf("s2", "sMissing"))

        assertEquals(listOf("s1", "s2"), orderedIds("p1")) // 关系只挂本地存在者
        val pl = store.getPlaylistById(acct, "p1")!!
        assertEquals(3, pl.songCount) // 1 + 2（含缺失，服务器已接受全部）
        assertEquals(30, pl.duration) // 10 + 20（缺失计 0）
    }

    // ---- removePlaylistSongAt ----

    @Test
    fun removePlaylistSongAt_removesAndRecomputes_outOfBoundsSkips() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1", 10), song("s2", 20), song("s3", 30)))

        store.removePlaylistSongAt(acct, "p1", 1) // 删 s2

        assertEquals(listOf("s1", "s3"), orderedIds("p1"))
        var pl = store.getPlaylistById(acct, "p1")!!
        assertEquals(2, pl.songCount)
        assertEquals(40, pl.duration)

        store.removePlaylistSongAt(acct, "p1", 99) // 越界静默跳过
        assertEquals(listOf("s1", "s3"), orderedIds("p1"))
        pl = store.getPlaylistById(acct, "p1")!!
        assertEquals(2, pl.songCount)
        assertEquals(40, pl.duration)
    }

    // ---- reorderPlaylistSongs ----

    @Test
    fun reorderPlaylistSongs_reordersAndDropsMissing() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1", 10), song("s2", 20), song("s3", 30)))

        store.reorderPlaylistSongs(acct, "p1", listOf("s3", "sMissing", "s1", "s2"))

        assertEquals(listOf("s3", "s1", "s2"), orderedIds("p1")) // 缺失 id 被丢弃
        val pl = store.getPlaylistById(acct, "p1")!!
        assertEquals(3, pl.songCount)
        assertEquals(60, pl.duration)
    }

    // ---- updatePlaylistLastPlayed ----

    @Test
    fun updatePlaylistLastPlayed_missingSkipsNoOrphan_existingReadsBack() = runBlocking {
        // 播放列表缺失 → 静默跳过，不留孤儿 state 行
        store.updatePlaylistLastPlayed(acct, "ghost", 123)
        assertNull(db.playlistLocalStateDao().get(acct, "ghost"))

        store.upsertPlaylistsMetadata(acct, listOf(playlist("p1")))
        store.updatePlaylistLastPlayed(acct, "p1", 777)

        assertEquals(777L, store.getPlaylistById(acct, "p1")!!.lastPlayed)
        // getAllPlaylists LEFT JOIN 带出 lastPlayed
        assertEquals(777L, store.getAllPlaylists(acct).first().first { it.id == "p1" }.lastPlayed)
    }

    // ---- getCachedPlaylistIds ----

    @Test
    fun getCachedPlaylistIds_existsSemanticsToggleWithCachePath() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1")))
        store.applyPlaylistDetails(acct, "p2", playlist("p2"), listOf(song("s2")))

        assertEquals(emptySet<String>(), store.getCachedPlaylistIds(acct).first())

        db.songLocalStateDao().setCachePath(acct, "s1", "songs/s1.mp3")
        assertEquals(setOf("p1"), store.getCachedPlaylistIds(acct).first())

        db.songLocalStateDao().setCachePath(acct, "s1", null)
        assertEquals(emptySet<String>(), store.getCachedPlaylistIds(acct).first())
    }

    // ---- searchPlaylists ----

    @Test
    fun searchPlaylists_hitMissAndAccountIsolation() = runBlocking {
        store.upsertPlaylistsMetadata(acct, listOf(playlist("p1", name = "Chill Vibes"), playlist("p2", name = "Workout")))
        // B 账户同名，不得串入 A 的搜索结果
        store.upsertPlaylistsMetadata(DbTestFixture.ACCT_B, listOf(playlist("pb", name = "Chill Vibes")))

        assertEquals(setOf("p1"), store.searchPlaylists(acct, "chill").first().map { it.id }.toSet())
        assertEquals(emptySet<String>(), store.searchPlaylists(acct, "zzz").first().map { it.id }.toSet())
        assertEquals(setOf("p1"), store.searchPlaylists(acct, "vibes").first().map { it.id }.toSet())
    }

    // ---- observeSongsWithLocalOf 表级失效（2b 合同 DAO 级前置）----

    @Test
    fun getPlaylistSongs_reEmitsWhenMemberSongCacheStateChanges() = runBlocking {
        store.applyPlaylistDetails(acct, "p1", playlist("p1"), listOf(song("s1")))

        val channel = Channel<List<Song>>(Channel.UNLIMITED)
        val collectJob = launch(Dispatchers.Default) {
            store.getPlaylistSongs(acct, "p1").collect { channel.send(it) }
        }

        try {
            withTimeout(5000) {
                // 1. 首次发射：含 s1 且未缓存
                val first = channel.receive().firstOrNull { it.id == "s1" }
                assertNotNull("First emission must contain s1", first)
                assertFalse("s1 must start not downloaded", first!!.isDownloaded)

                // 2. 同一收集器存活期间写 cache_path（下载完成）
                db.songLocalStateDao().setCachePath(acct, "s1", "songs/s1.mp3")

                // 3. 表级失效触发再发射，直至 s1.isDownloaded == true（不重新订阅）
                while (true) {
                    val s1 = channel.receive().firstOrNull { it.id == "s1" }
                    if (s1 != null && s1.isDownloaded) break
                }
            }
        } finally {
            collectJob.cancel()
        }
    }
}
