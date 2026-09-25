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
import androidx.test.platform.app.InstrumentationRegistry
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.db.store.RoomDownloadLocalStore
import com.amperfy.data.local.db.store.RoomPlaybackStateStore
import com.amperfy.data.model.DownloadEntityType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * RoomDownloadLocalStore / RoomPlaybackStateStore（均为全量实现）合同。
 *
 * 合同演进留痕：
 * - **P4 批次 1 起 [RoomDownloadLocalStore] 构造只剩 db**，并补齐下载状态机
 *   （upsertRequested/markFinished/markFailed/cancel-all/clear/retry/启动恢复/单删/跨账户）全走
 *   Store 层的用例。
 * - **P4 批次 2 起 RoomPlaybackStateStore 亦委托清零**（playback_state 单行 + 三队列迁 Room），
 *   构造改为 (db, CredentialsManager, filesDir)，故 song 级三例改真实构造、mockk 随之退场；
 *   这三例只测 song 级方法（saveSongProgress/getSongProgress/incrementPlayCount），不触播放状态
 *   三方法，真实依赖无副作用（播放状态往返/恢复语义归 PlaybackStateStoreContractTest）。
 *
 * 时间戳一律字面量常量（DAO/Store 不取系统时钟）。
 */
@RunWith(AndroidJUnit4::class)
class RoomDownloadPlaybackStoreTest {

    private lateinit var db: AmperfyDatabase
    private lateinit var store: RoomDownloadLocalStore
    private val acct = DbTestFixture.ACCT_A
    private val acctB = DbTestFixture.ACCT_B

    /** download_entry.entity_type 的歌曲取值（Batch 4：DAO 单条查询一律带该谓词） */
    private val SONG_TYPE = DownloadEntityType.SONG.name

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
        store = RoomDownloadLocalStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * song 级本地统计用的 Store（P4 批次 2 起委托清零，真实依赖构造）。
     * CredentialsManager/filesDir 只服务于播放状态恢复装配，本类三例不触及，故无副作用。
     */
    private fun newPlaybackStore(): RoomPlaybackStateStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return RoomPlaybackStateStore(db, CredentialsManager(context), context.filesDir)
    }

    // ---- 下载缓存 ----

    @Test
    fun setSongCached_missingSongReturnsFalseAndSkipsLocalState() = runBlocking {
        assertFalse(store.setSongCached(acct, "nope", "songs/nope.mp3"))
        // 歌曲不在库中不写：既不返回 true，也不产生孤儿 song_local_state 行
        assertNull(db.songLocalStateDao().get(acct, "nope"))
    }

    /**
     * 缓存态唯一权威源为 song_local_state.cache_path。
     */
    @Test
    fun setSongCached_existingSongWritesLocalState() = runBlocking {
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, "s1")))

        assertTrue(store.setSongCached(acct, "s1", "songs/s1.mp3"))
        assertEquals("songs/s1.mp3", db.songLocalStateDao().get(acct, "s1")!!.cachePath)
    }

    // ---- 下载列表装配 ----

    @Test
    fun observeItems_assemblesFromEntriesOmittingMissingSongs() = runBlocking {
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, "s1"))) // s2 不在库
        // 条目经生产写入口 seed（镜像真实路径：状态由 finish/error 两列派生）
        store.upsertRequested(acct, "s1", now = 100)
        store.upsertRequested(acct, "s2", now = 200)
        store.markFinished(acct, "s2", now = 250)

        val items = store.observeItems(acct).first()

        assertEquals("歌曲不在库中的条目省略", 1, items.size)
        assertEquals("s1", items[0].song!!.id)
        assertTrue(items[0].isDownloading)
        assertFalse(items[0].isFinished)
        assertFalse(items[0].isError)
    }

    // ---- 下载状态机 ----

    /** 已存在记录再次请求 = reset：清 finish/error，**保留原 creationDate**（iOS download.reset()）。 */
    @Test
    fun upsertRequested_existingEntryResetsDatesKeepingCreationDate() = runBlocking {
        store.upsertRequested(acct, "s1", now = 100)
        store.markFailed(acct, "s1", now = 150)

        store.upsertRequested(acct, "s1", now = 300)

        val entry = db.downloadEntryDao().getByEntity(acct, SONG_TYPE, "s1")!!
        assertEquals("creationDate 必须保留首次请求时间", 100L, entry.creationDate)
        assertNull(entry.errorDate)
        assertNull(entry.finishDate)
        assertEquals(1, db.downloadEntryDao().getAll(acct).size)
    }

    /** markFailed 带「已完成不覆盖」守卫（仅 finishDate 为空时生效）。 */
    @Test
    fun markFailed_doesNotOverwriteFinishedEntry() = runBlocking {
        store.upsertRequested(acct, "s1", now = 100)
        store.markFinished(acct, "s1", now = 200)

        store.markFailed(acct, "s1", now = 300)

        val entry = db.downloadEntryDao().getByEntity(acct, SONG_TYPE, "s1")!!
        assertEquals(200L, entry.finishDate)
        assertNull("已成功的记录不得被失败覆盖", entry.errorDate)
    }

    /** Cancel all：只动两日期均空的记录，已完成/已失败的不碰。 */
    @Test
    fun markAllUnfinishedFailed_onlyTouchesPendingEntries() = runBlocking {
        store.upsertRequested(acct, "pending", now = 100)
        store.upsertRequested(acct, "done", now = 110)
        store.markFinished(acct, "done", now = 120)
        store.upsertRequested(acct, "failed", now = 130)
        store.markFailed(acct, "failed", now = 140)

        store.markAllUnfinishedFailed(acct, now = 500)

        val dao = db.downloadEntryDao()
        assertEquals(500L, dao.getByEntity(acct, SONG_TYPE, "pending")!!.errorDate)
        assertNull("已完成记录不得被 cancel all 标失败", dao.getByEntity(acct, SONG_TYPE, "done")!!.errorDate)
        assertEquals("既有失败时间不被刷新", 140L, dao.getByEntity(acct, SONG_TYPE, "failed")!!.errorDate)
    }

    /** Clear finished：删完成 + 失败，进行中保留在列表。 */
    @Test
    fun clearFinished_removesFinishedAndFailedKeepsPending() = runBlocking {
        store.upsertRequested(acct, "pending", now = 100)
        store.upsertRequested(acct, "done", now = 110)
        store.markFinished(acct, "done", now = 120)
        store.upsertRequested(acct, "failed", now = 130)
        store.markFailed(acct, "failed", now = 140)

        store.clearFinished(acct)

        assertEquals(listOf("pending"), db.downloadEntryDao().getAll(acct).map { it.songId })
    }

    /** Retry failed 与启动恢复两查询的分类与排序（后者按 creationDate 升序）。 */
    @Test
    fun failedAndRequestedSongIds_partitionByStateAndSortByCreationDate() = runBlocking {
        store.upsertRequested(acct, "late", now = 300)
        store.upsertRequested(acct, "early", now = 100)
        store.upsertRequested(acct, "done", now = 110)
        store.markFinished(acct, "done", now = 120)
        store.upsertRequested(acct, "failed", now = 130)
        store.markFailed(acct, "failed", now = 140)

        assertEquals(listOf("failed"), store.failedDownloads(acct).map { it.id })
        assertEquals(
            "启动恢复只含两日期均空的记录，按 creationDate 升序",
            listOf("early", "late"),
            store.requestedDownloads(acct).map { it.id },
        )
    }

    /** 单删（启动恢复时歌曲已不在库中的无法恢复条目）。 */
    @Test
    fun deleteEntry_removesOnlyTargetEntry() = runBlocking {
        store.upsertRequested(acct, "s1", now = 100)
        store.upsertRequested(acct, "s2", now = 110)

        store.deleteEntry(acct, "s1")

        assertNull(db.downloadEntryDao().getByEntity(acct, SONG_TYPE, "s1"))
        assertNotNull(db.downloadEntryDao().getByEntity(acct, SONG_TYPE, "s2"))
    }

    /** 跨账户隔离：账户 A 的 cancel all / clear finished 不影响账户 B 的记录。 */
    @Test
    fun downloadStateMachine_isAccountIsolated() = runBlocking {
        store.upsertRequested(acct, "s1", now = 100)
        store.upsertRequested(acctB, "s1", now = 100)

        store.markAllUnfinishedFailed(acct, now = 500)
        store.clearFinished(acct)

        assertEquals(emptyList<String>(), db.downloadEntryDao().getAll(acct).map { it.songId })
        val entryB = db.downloadEntryDao().getByEntity(acctB, SONG_TYPE, "s1")!!
        assertNull(entryB.errorDate)
        assertNull(entryB.finishDate)
        assertEquals(listOf("s1"), store.requestedDownloads(acctB).map { it.id })
    }

    // ---- 播放进度 / 播放次数 ----

    @Test
    fun saveAndGetSongProgress_roundtripAndClear() = runBlocking {
        val playbackStore = newPlaybackStore()
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, "s1")))

        playbackStore.saveSongProgress(acct, "s1", 1234L, 999L)
        assertEquals(1234L, playbackStore.getSongProgress(acct, "s1"))

        // 同为 null = 清零
        playbackStore.saveSongProgress(acct, "s1", null, null)
        assertNull(playbackStore.getSongProgress(acct, "s1"))
    }

    @Test
    fun saveSongProgress_missingSongSkips() = runBlocking {
        val playbackStore = newPlaybackStore()
        playbackStore.saveSongProgress(acct, "nope", 10L, 20L)
        assertNull(db.songLocalStateDao().get(acct, "nope"))
    }

    @Test
    fun incrementPlayCount_missingSkipsExistingBumps() = runBlocking {
        val playbackStore = newPlaybackStore()
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, "s1")))

        playbackStore.incrementPlayCount(acct, "nope")
        assertNull(db.songLocalStateDao().get(acct, "nope"))

        playbackStore.incrementPlayCount(acct, "s1")
        assertEquals(1, db.songLocalStateDao().get(acct, "s1")!!.playCount)
    }
}
