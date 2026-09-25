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
import com.amperfy.data.local.db.entity.EventLogEntity
import com.amperfy.data.local.db.entity.PlaybackStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * account_scope 级联删除 vs 全局表契约测试。
 *
 * 锁定：deleteByAccountId 触发 ON DELETE CASCADE 清空该账户全部账户级表（含经 playlist 二级
 * 级联的 playlist_song），另一账户同名数据不受影响；event_log/playback_state/playback_queue_item
 * 三全局表无 account_scope FK，不随 scope 删除消失。
 */
@RunWith(AndroidJUnit4::class)
class AccountScopeCascadeTest {

    private lateinit var db: AmperfyDatabase

    /** 账户级表清单（deleteByAccountId 后目标账户应全为 0，另一账户保留）。 */
    private val accountTables = listOf(
        "artist", "album", "song", "genre", "song_local_state", "album_sync_state",
        "playlist", "playlist_song", "playlist_local_state", "radio", "podcast",
        "podcast_episode", "music_folder", "directory", "song_directory",
        "download_entry", "search_history",
    )

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 给某账户在每张代表性账户表各插 1 行（含 playlist_song 二级级联链）。 */
    private suspend fun seedAccount(acct: String) {
        db.artistDao().upsertRemote(listOf(DbTestFixture.artist(acct, "ar1", "Artist")))
        db.albumDao().upsertRemote(listOf(DbTestFixture.album(acct, "al1")))
        db.songDao().upsertRemote(listOf(DbTestFixture.song(acct, "s1")))
        db.genreDao().upsertRemote(listOf(DbTestFixture.genre(acct, "Rock")))
        db.songLocalStateDao().setCachePath(acct, "s1", "songs/s1.mp3")
        db.albumSyncStateDao().setSongsSynced(acct, "al1", true)
        db.playlistDao().upsertRemote(listOf(DbTestFixture.playlist(acct, "p1")))
        db.playlistSongDao().replacePlaylistSongs(acct, "p1", listOf("s1"))
        db.playlistLocalStateDao().setLastPlayed(acct, "p1", 123)
        db.radioDao().upsertRemote(listOf(DbTestFixture.radio(acct, "r1")))
        db.podcastDao().upsertRemote(listOf(DbTestFixture.podcast(acct, "pod1")))
        db.podcastEpisodeDao().upsertRemote(
            listOf(DbTestFixture.episode(acct, "e1", podcastId = "pod1")),
        )
        db.musicFolderDao().upsertRemote(listOf(DbTestFixture.musicFolder(acct, "mf1")))
        db.directoryDao().upsertRemote(listOf(DbTestFixture.directory(acct, "d1")))
        db.songDirectoryDao().setDirectory(acct, "s1", "d1")
        db.downloadEntryDao().upsert(DbTestFixture.download(acct, "s1"))
        db.searchHistoryDao().upsert(DbTestFixture.searchHistory(acct, "SONG", "s1"))
    }

    @Test
    fun deleteScope_cascadesAllAccountTables() = runBlocking {
        seedAccount(DbTestFixture.ACCT_A)
        seedAccount(DbTestFixture.ACCT_B)

        // 前置：两账户每表各 1 行
        for (table in accountTables) {
            assertEquals("$table acctA before", 1, DbTestFixture.countRows(db, table, DbTestFixture.ACCT_A))
            assertEquals("$table acctB before", 1, DbTestFixture.countRows(db, table, DbTestFixture.ACCT_B))
        }

        db.accountScopeDao().deleteByAccountId(DbTestFixture.ACCT_A)

        // acctA 全表清空（含经 playlist 二级级联的 playlist_song），acctB 完全保留
        for (table in accountTables) {
            assertEquals("$table acctA after", 0, DbTestFixture.countRows(db, table, DbTestFixture.ACCT_A))
            assertEquals("$table acctB after", 1, DbTestFixture.countRows(db, table, DbTestFixture.ACCT_B))
        }
    }

    @Test
    fun globalTables_unaffectedByScopeDeletion() = runBlocking {
        seedAccount(DbTestFixture.ACCT_A)
        // 全局表：event_log + playback_state + playback_queue_item
        db.eventLogDao().insert(EventLogEntity(creationDate = 100, message = "hello"))
        db.playbackDao().replaceAllQueues(
            PlaybackStateEntity(id = 1, savedAt = 100),
            listOf(DbTestFixture.queueItem("PLAYLIST", 0, entityId = "a")),
        )

        db.accountScopeDao().deleteByAccountId(DbTestFixture.ACCT_A)

        // 全局表行保留（无 account_scope FK）
        assertEquals(1, DbTestFixture.countAllRows(db, "event_log"))
        assertNotNull(db.playbackDao().getState())
        assertEquals(1, DbTestFixture.countAllRows(db, "playback_queue_item"))
        // 账户表已随 scope 清空（对照）
        assertEquals(0, DbTestFixture.countRows(db, "song", DbTestFixture.ACCT_A))
    }
}
