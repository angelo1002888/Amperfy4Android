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
import androidx.test.platform.app.InstrumentationRegistry
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.DbTestFixture
import com.amperfy.data.local.db.store.RoomPlaybackStateStore
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.PlaybackState
import com.amperfy.data.model.Playable
import com.amperfy.data.model.PlayerMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PlaybackStateStore 的行为合同测试（专题 15 P1 批次 2 建立，P4 批次 2 介质换绑 + 恢复语义扩充）。
 *
 * 合同锁定三组既有语义：
 * - 播放状态往返：三队列内容（ids/accountIds）、isUserQueuePlaying↔playSource 映射、displayMode
 *   等标量逐字段保真；
 * - 清除后读回 null；
 * - song 级进度/播放计数按 (account_id, song_id) 隔离账户（同 serverId 不撞号）。
 *
 * 介质改为 Room 内存库 + [RoomPlaybackStateStore]（照 AccountLocalStore/SearchHistoryStore 的换绑
 * 先例，直接实例化 Store，不经 RepositoryFixture）。CredentialsManager 用真实实例（测试不写凭证
 * → 恢复走「凭证缺失」分支：条目仍保留、stream/cover URL 降级为空，故既有断言不受影响）。
 *
 * **往返合同有意不断言 streamUrl/coverArt**：设计上禁止把 stream/cover URL 与 cache_path 存进
 * playback_queue_item（会在下载完成、服务器 URL 切换、元数据更新后过时），这些值一律恢复时现生成；
 * 队列条目只保证身份（accountId + entityId）与非权威 fallback 显示字段可往返。
 *
 * P4 批次 2 新增 5 例锁定恢复语义六条：JOIN 最新实体优先、SONG 缺失走 fallback 列、
 * 陈旧 cache_path 清空并按未缓存处理、RADIO 引用缺失跳过并修正索引、RADIO/EPISODE 类型标志与
 * 流 URL 还原口径。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackStateStoreContractTest {

    companion object {
        private const val ACCOUNT_A = DbTestFixture.ACCT_A
        private const val ACCOUNT_B = DbTestFixture.ACCT_B
    }

    private lateinit var db: AmperfyDatabase
    private lateinit var filesDir: File
    private lateinit var store: RoomPlaybackStateStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = DbTestFixture.openDatabase()
        filesDir = context.filesDir
        store = RoomPlaybackStateStore(db, CredentialsManager(context), filesDir)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun playable(id: String, accountId: String) = Playable(
        id = id,
        title = "title-$id",
        artist = "artist",
        album = "album",
        duration = 100,
        coverArt = null,
        streamUrl = "https://example/stream/$id",
        accountId = accountId
    )

    /** 电台条目（isRadio=true → item_type=RADIO） */
    private fun radioPlayable(id: String, accountId: String = ACCOUNT_A) =
        playable(id, accountId).copy(isRadio = true, duration = 0)

    /** 播客单集条目（isPodcastEpisode=true → item_type=PODCAST_EPISODE） */
    private fun episodePlayable(id: String, accountId: String = ACCOUNT_A) =
        playable(id, accountId).copy(isPodcastEpisode = true)

    private fun stateOf(
        playlist: List<Playable>,
        currentIndex: Int = 0,
        contextQueue: List<Playable> = emptyList(),
        currentContextIndex: Int = 0,
    ) = PlaybackState(
        id = 1,
        playlist = playlist,
        currentIndex = currentIndex,
        contextQueue = contextQueue,
        currentContextIndex = currentContextIndex,
    )

    @Test
    fun saveThenGet_roundTripsAllFields() = runBlocking {
        val state = PlaybackState(
            id = 1,
            playlist = listOf(playable("p1", ACCOUNT_A), playable("p2", ACCOUNT_A)),
            currentIndex = 1,
            playProgress = 12_345,
            playDuration = 200_000,
            contextType = PlayContextType.PLAYLIST,
            contextId = "ctx-1",
            contextName = "My Playlist",
            playerMode = PlayerMode.MUSIC,
            wasPlaying = true,
            userQueue = listOf(playable("u1", ACCOUNT_A), playable("u2", ACCOUNT_B)),
            contextQueue = listOf(playable("c1", ACCOUNT_A), playable("c2", ACCOUNT_A)),
            currentContextIndex = 3,
            playSource = "USER",
            displayMode = "COMPACT",
            savedAt = 999_000
        )

        store.savePlaybackState(state)
        val restored = store.getPlaybackState()!!

        assertEquals(listOf("p1", "p2"), restored.playlist.map { it.id })
        assertEquals(listOf("u1", "u2"), restored.userQueue.map { it.id })
        assertEquals(listOf(ACCOUNT_A, ACCOUNT_B), restored.userQueue.map { it.accountId })
        assertEquals(listOf("c1", "c2"), restored.contextQueue.map { it.id })
        assertEquals(1, restored.currentIndex)
        assertEquals(3, restored.currentContextIndex)
        assertEquals(12_345L, restored.playProgress)
        assertEquals(200_000L, restored.playDuration)
        assertEquals(PlayContextType.PLAYLIST, restored.contextType)
        assertEquals("ctx-1", restored.contextId)
        assertEquals("My Playlist", restored.contextName)
        assertEquals(true, restored.wasPlaying)
        // isUserQueuePlaying(true) ← playSource == "USER"
        assertEquals("USER", restored.playSource)
        assertEquals("COMPACT", restored.displayMode)
    }

    @Test
    fun clear_thenGetReturnsNull() = runBlocking {
        store.savePlaybackState(
            PlaybackState(id = 1, playlist = listOf(playable("p1", ACCOUNT_A)))
        )
        store.clearPlaybackState()
        assertNull(store.getPlaybackState())
    }

    @Test
    fun saveSongProgress_writesAndClears() = runBlocking {
        // 两账户同 serverId 歌曲，(account_id, song_id) 复合主键隔离
        DbTestFixture.seedScopes(db)
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(ACCOUNT_A, serverId = "song1"),
                DbTestFixture.song(ACCOUNT_B, serverId = "song1"),
            )
        )

        store.saveSongProgress(ACCOUNT_A, "song1", progressMs = 60_000, updatedAt = 1000)
        assertEquals(60_000L, store.getSongProgress(ACCOUNT_A, "song1"))
        assertNull("账户 B 同 serverId 歌曲不受影响", store.getSongProgress(ACCOUNT_B, "song1"))

        // 清零：progressMs/updatedAt 同为 null
        store.saveSongProgress(ACCOUNT_A, "song1", progressMs = null, updatedAt = null)
        assertNull(store.getSongProgress(ACCOUNT_A, "song1"))
    }

    @Test
    fun incrementPlayCount_incrementsOnlyTargetAccount() = runBlocking {
        DbTestFixture.seedScopes(db)
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(ACCOUNT_A, serverId = "song1"),
                DbTestFixture.song(ACCOUNT_B, serverId = "song1"),
            )
        )

        store.incrementPlayCount(ACCOUNT_A, "song1")
        store.incrementPlayCount(ACCOUNT_A, "song1")

        // 本地播放次数落在 song_local_state，行未建时视为 0
        val countA = db.songLocalStateDao().get(ACCOUNT_A, "song1")?.playCount ?: 0
        val countB = db.songLocalStateDao().get(ACCOUNT_B, "song1")?.playCount ?: 0
        assertEquals(2, countA)
        assertEquals(0, countB)

        // 不存在的 songId 调用不抛异常（静默不写）
        store.incrementPlayCount(ACCOUNT_A, "missing")
    }

    // ==================== 恢复语义（P4 批次 2 新增） ====================

    @Test
    fun restore_prefersLatestEntityOverFallbackColumns() = runBlocking {
        // 恢复语义第 1/3 条：JOIN 到的最新实体优先于队列里的非权威 fallback 快照
        DbTestFixture.seedScopes(db)
        db.songDao().upsertRemote(
            listOf(DbTestFixture.song(ACCOUNT_A, serverId = "s1", title = "latest"))
        )
        store.savePlaybackState(
            stateOf(listOf(playable("s1", ACCOUNT_A).copy(title = "stale")))
        )

        val restored = store.getPlaybackState()!!

        assertEquals("标题必须取自最新实体，而非入队时的 fallback 快照", "latest", restored.playlist[0].title)
    }

    @Test
    fun restore_songMissingFromLibraryUsesFallbackColumns() = runBlocking {
        // 恢复语义第 4 条：在线搜索播放的歌曲不入库，恢复时用 fallback 列且视为未缓存
        DbTestFixture.seedScopes(db)
        val online = Playable(
            id = "online-1",
            title = "Online Title",
            artist = "Online Artist",
            album = "Online Album",
            duration = 321,
            coverArt = null,
            streamUrl = "https://example/stream/online-1",
            accountId = ACCOUNT_A,
        )
        store.savePlaybackState(stateOf(listOf(online)))

        val restored = store.getPlaybackState()!!.playlist.single()

        assertEquals("online-1", restored.id)
        assertEquals("Online Title", restored.title)
        assertEquals("Online Artist", restored.artist)
        assertEquals("Online Album", restored.album)
        assertEquals(321, restored.duration)
        assertFalse("库中无实体的条目一律视为未缓存", restored.isDownloaded)
    }

    @Test
    fun restore_staleCachePathIsClearedAndTreatedAsUncached() = runBlocking {
        // 恢复语义第 2 条：cache_path 指向的文件已不存在 → 按未缓存恢复并清空陈旧路径
        DbTestFixture.seedScopes(db)
        db.songDao().upsertRemote(listOf(DbTestFixture.song(ACCOUNT_A, serverId = "s1")))
        val missingPath = "cache/${System.nanoTime()}-does-not-exist.mp3"
        assertFalse("前置条件：该缓存文件必须不存在", File(filesDir, missingPath).exists())
        db.songLocalStateDao().setCachePath(ACCOUNT_A, "s1", missingPath)

        store.savePlaybackState(stateOf(listOf(playable("s1", ACCOUNT_A))))
        val restored = store.getPlaybackState()!!.playlist.single()

        assertFalse("缓存文件缺失 → 按未缓存恢复", restored.isDownloaded)
        assertNull(restored.downloadPath)
        assertNull(
            "陈旧 cache_path 必须被清空（getPlaybackState 内唯一允许的写）",
            db.songLocalStateDao().get(ACCOUNT_A, "s1")?.cachePath
        )
    }

    @Test
    fun restore_missingRadioIsSkippedAndIndexAdjusted() = runBlocking {
        // 恢复语义第 5 条：RADIO 引用缺失 → 跳过 + position 重排 + currentIndex 修正
        DbTestFixture.seedScopes(db)
        db.songDao().upsertRemote(
            listOf(
                DbTestFixture.song(ACCOUNT_A, serverId = "s1"),
                DbTestFixture.song(ACCOUNT_A, serverId = "s2"),
            )
        )
        store.savePlaybackState(
            stateOf(
                playlist = listOf(
                    playable("s1", ACCOUNT_A),
                    radioPlayable("gone"),          // 库中无该电台
                    playable("s2", ACCOUNT_A),
                ),
                currentIndex = 2,
            )
        )

        val restored = store.getPlaybackState()!!

        assertEquals(listOf("s1", "s2"), restored.playlist.map { it.id })
        assertEquals("被跳过条目在当前索引之前 → 索引减 1", 1, restored.currentIndex)
    }

    @Test
    fun restore_radioAndEpisodeKeepTypeFlagsAndStreamUrls() = runBlocking {
        // 恢复语义第 1 条：RADIO 用实体原始 stream_url 直连；EPISODE 用 stream_id ?? server_id 重建
        DbTestFixture.seedScopes(db)
        val radioStream = "http://radio.example/live.mp3"
        db.radioDao().upsertRemote(
            listOf(DbTestFixture.radio(ACCOUNT_A, serverId = "r1").copy(streamUrl = radioStream))
        )
        db.podcastDao().upsertRemote(listOf(DbTestFixture.podcast(ACCOUNT_A, serverId = "pc1")))
        db.podcastEpisodeDao().upsertRemote(
            listOf(
                DbTestFixture.episode(ACCOUNT_A, serverId = "e1", podcastId = "pc1")
                    .copy(streamId = "ep-stream", duration = 1800)
            )
        )
        store.savePlaybackState(
            stateOf(listOf(radioPlayable("r1"), episodePlayable("e1")))
        )

        val restored = store.getPlaybackState()!!.playlist
        val radio = restored[0]
        val episode = restored[1]

        assertTrue("电台标志必须还原", radio.isRadio)
        assertEquals("电台流 URL = 实体原始快照（不经服务器 URL 生成）", radioStream, radio.streamUrl)
        assertEquals(0, radio.duration)

        assertTrue("播客单集标志必须还原", episode.isPodcastEpisode)
        assertEquals("单集 artist 显示 JOIN 出的父频道名", "Podcast pc1", episode.artist)
        assertEquals(1800, episode.duration)
        assertTrue(
            "单集流 URL：无凭证时降级空串，有凭证时按 stream_id ?? server_id 重建",
            episode.streamUrl.isEmpty() || episode.streamUrl.contains("id=ep-stream")
        )
    }
}
