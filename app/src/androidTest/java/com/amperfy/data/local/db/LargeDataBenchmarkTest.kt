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

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 大数据量基准 + EXPLAIN QUERY PLAN 留痕。
 *
 * 真机运行，策略为**宽松上限断言 + Log 实际毫秒值**——只做防回归护栏，不设紧阈值
 * （硬件/SQLite 版本浮动，紧阈值会脆断）。实际耗时用 `Log.i("$TAG", ...)` 打印供人工留痕对照 15A。
 *
 * 计时用 [SystemClock.elapsedRealtime]（本批红线允许的基准时钟）；插入分批 ≤500。
 * EXPLAIN QUERY PLAN 对三条常用查询整段 Log；仅 ① artist 排序查询断言计划走索引
 * （contains "USING INDEX"），②③ 只记录不断言（EXISTS/JOIN 计划形态随 SQLite 版本浮动）。
 */
@RunWith(AndroidJUnit4::class)
class LargeDataBenchmarkTest {

    private companion object {
        const val TAG = "Topic15Bench"
        const val BENCH_UPPER_BOUND_MS = 3000L
        const val BATCH = 500

        // ---- 以下 SQL 与对应 DAO 逐字节保持一致，改 DAO 时必须同步 ----

        /** 与 ArtistDao.observeAll 同串（:accountId → ?）。 */
        const val SQL_ARTIST_OBSERVE_ALL =
            "SELECT * FROM artist WHERE account_id = ? " +
                "ORDER BY sort_key COLLATE BINARY, server_id"

        /** 与 AlbumDao.randomCachedAlbums 同串（:accountId → ?、:count → ?）。 */
        const val SQL_RANDOM_CACHED_ALBUMS =
            "SELECT album.* " +
                "FROM album " +
                "WHERE album.account_id = ? " +
                "  AND EXISTS ( " +
                "      SELECT 1 " +
                "      FROM song " +
                "      JOIN song_local_state " +
                "        ON song_local_state.account_id = song.account_id " +
                "       AND song_local_state.song_id = song.server_id " +
                "      WHERE song.account_id = album.account_id " +
                "        AND song.album_id = album.server_id " +
                "        AND song_local_state.cache_path IS NOT NULL " +
                "  ) " +
                "ORDER BY RANDOM() " +
                "LIMIT ?"

        /** 与 PlaylistSongDao.observeSongsOf 同串（:accountId → ?、:playlistId → ?）。 */
        const val SQL_PLAYLIST_SONG_JOIN =
            "SELECT song.* FROM playlist_song " +
                "JOIN song " +
                "  ON song.account_id = playlist_song.account_id " +
                " AND song.server_id = playlist_song.song_id " +
                "WHERE playlist_song.account_id = ? " +
                "  AND playlist_song.playlist_id = ? " +
                "ORDER BY playlist_song.position"

        /**
         * 与 ArtistDao.observeAllWithCounts 同串（COUNTS_CTE + COUNTS_SELECT，:accountId → ?，5 处）。
         * 改 ArtistDao 时必须同步（EXPLAIN 仅留痕不断言）。
         */
        const val SQL_ARTIST_WITH_COUNTS =
            "WITH related_song AS (" +
                "  SELECT song.artist_id AS artist_id, song.server_id AS song_id, song.duration AS duration " +
                "    FROM song WHERE song.account_id = ? AND song.artist_id IS NOT NULL " +
                "  UNION " +
                "  SELECT album.artist_id AS artist_id, song.server_id AS song_id, song.duration AS duration " +
                "    FROM song JOIN album " +
                "      ON album.account_id = song.account_id AND album.server_id = song.album_id " +
                "    WHERE song.account_id = ? AND album.artist_id IS NOT NULL" +
                "), related_album AS (" +
                "  SELECT album.artist_id AS artist_id, album.server_id AS album_id " +
                "    FROM album WHERE album.account_id = ? AND album.artist_id IS NOT NULL " +
                "  UNION " +
                "  SELECT song.artist_id AS artist_id, song.album_id AS album_id " +
                "    FROM song WHERE song.account_id = ? " +
                "      AND song.artist_id IS NOT NULL AND song.album_id IS NOT NULL" +
                "), song_agg AS (" +
                "  SELECT artist_id, COUNT(*) AS song_count, SUM(duration) AS duration " +
                "    FROM related_song GROUP BY artist_id" +
                "), album_agg AS (" +
                "  SELECT artist_id, COUNT(*) AS album_count FROM related_album GROUP BY artist_id" +
                ") " +
                "SELECT artist.*, " +
                "COALESCE(album_agg.album_count, 0) AS related_album_count, " +
                "COALESCE(song_agg.song_count, 0) AS related_song_count, " +
                "COALESCE(song_agg.duration, 0) AS related_duration " +
                "FROM artist " +
                "LEFT JOIN song_agg ON song_agg.artist_id = artist.server_id " +
                "LEFT JOIN album_agg ON album_agg.artist_id = artist.server_id " +
                "WHERE artist.account_id = ? " +
                "ORDER BY artist.sort_key COLLATE BINARY, artist.server_id"
    }

    private lateinit var db: AmperfyDatabase

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
    fun tenThousandAlbums_randomCachedAlbums() = runBlocking {
        val albumDao = db.albumDao()
        val songDao = db.songDao()
        val localDao = db.songLocalStateDao()

        // 插入 10000 album，分批 ≤500
        var inserted = 0
        while (inserted < 10_000) {
            val end = minOf(inserted + BATCH, 10_000)
            albumDao.upsertRemote((inserted until end).map { DbTestFixture.album(serverId = "A$it") })
            inserted = end
        }
        // 其中 500 张各配 1 首缓存歌
        var cached = 0
        while (cached < 500) {
            val end = minOf(cached + BATCH, 500)
            songDao.upsertRemote(
                (cached until end).map { DbTestFixture.song(serverId = "s$it", albumId = "A$it") },
            )
            (cached until end).forEach { localDao.setCachePath(DbTestFixture.ACCT_A, "s$it", "songs/s$it.mp3") }
            cached = end
        }

        val cachedAvg = averageMs(3) { db.albumDao().randomCachedAlbums(DbTestFixture.ACCT_A, 20) }
        val plainAvg = averageMs(3) { db.albumDao().randomAlbums(DbTestFixture.ACCT_A, 20) }
        Log.i(TAG, "randomCachedAlbums(10k albums, 500 cached, count=20) avg=${cachedAvg}ms")
        Log.i(TAG, "randomAlbums(10k albums, count=20) avg=${plainAvg}ms")

        assertTrue("randomCachedAlbums avg ${cachedAvg}ms exceeded bound", cachedAvg < BENCH_UPPER_BOUND_MS)
        assertTrue("randomAlbums avg ${plainAvg}ms exceeded bound", plainAvg < BENCH_UPPER_BOUND_MS)
    }

    @Test
    fun twoThousandSongPlaylist_replace() = runBlocking {
        db.playlistDao().upsertRemote(listOf(DbTestFixture.playlist(serverId = "p1")))
        val songIds = (0 until 2000).map { "s$it" }

        val avg = averageMs(3) { db.playlistSongDao().replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", songIds) }
        Log.i(TAG, "replacePlaylistSongs(2000 songs, full replace) avg=${avg}ms")

        assertEquals(2000, db.playlistSongDao().getSongIdsOrdered(DbTestFixture.ACCT_A, "p1").size)
        assertTrue("replacePlaylistSongs avg ${avg}ms exceeded bound", avg < BENCH_UPPER_BOUND_MS)
    }

    @Test
    fun explainQueryPlan_loggedAndIndexUsed() = runBlocking {
        // 适量种子数据供 planner 参考（非必需，但更贴近真实计划）
        db.artistDao().upsertRemote((0 until 100).map { DbTestFixture.artist(serverId = "ar$it", name = "Name$it") })
        db.albumDao().upsertRemote((0 until 100).map { DbTestFixture.album(serverId = "A$it") })
        db.songDao().upsertRemote((0 until 100).map { DbTestFixture.song(serverId = "s$it", albumId = "A$it") })
        (0 until 20).forEach { db.songLocalStateDao().setCachePath(DbTestFixture.ACCT_A, "s$it", "songs/s$it.mp3") }
        db.playlistDao().upsertRemote(listOf(DbTestFixture.playlist(serverId = "p1")))
        db.playlistSongDao().replacePlaylistSongs(DbTestFixture.ACCT_A, "p1", (0 until 50).map { "s$it" })

        val artistPlan = explain(SQL_ARTIST_OBSERVE_ALL, DbTestFixture.ACCT_A)
        val cachedAlbumsPlan = explain(SQL_RANDOM_CACHED_ALBUMS, DbTestFixture.ACCT_A, 20)
        val joinPlan = explain(SQL_PLAYLIST_SONG_JOIN, DbTestFixture.ACCT_A, "p1")

        Log.i(TAG, "EXPLAIN artist.observeAll:\n$artistPlan")
        Log.i(TAG, "EXPLAIN album.randomCachedAlbums:\n$cachedAlbumsPlan")
        Log.i(TAG, "EXPLAIN playlist_song JOIN song:\n$joinPlan")

        // ① 排序查询必须走 (account_id, sort_key, server_id) 索引；②③ 只记录不断言
        assertTrue(
            "artist observeAll should use an index but plan was:\n$artistPlan",
            artistPlan.contains("USING INDEX"),
        )
    }

    @Test
    fun artistWithCounts_fullListProjection() = runBlocking {
        // 500 artist / 1000 album / 10000 song，分批 ≤500
        var a = 0
        while (a < 500) {
            val end = minOf(a + BATCH, 500)
            db.artistDao().upsertRemote((a until end).map { DbTestFixture.artist(serverId = "ar$it", name = "Name$it") })
            a = end
        }
        var al = 0
        while (al < 1000) {
            val end = minOf(al + BATCH, 1000)
            db.albumDao().upsertRemote((al until end).map { DbTestFixture.album(serverId = "al$it", artistId = "ar${it % 500}") })
            al = end
        }
        var s = 0
        while (s < 10_000) {
            val end = minOf(s + BATCH, 10_000)
            db.songDao().upsertRemote(
                (s until end).map {
                    DbTestFixture.song(serverId = "s$it", albumId = "al${it % 1000}", artistId = "ar${it % 500}")
                        .copy(duration = it % 300)
                },
            )
            s = end
        }

        val avg = averageMs(3) { db.artistDao().observeAllWithCounts(DbTestFixture.ACCT_A).first() }
        Log.i(TAG, "observeAllWithCounts(500 artist/1000 album/10000 song) avg=${avg}ms")

        // EXPLAIN 仅留痕不断言（CTE 计划形态随 SQLite 版本浮动）
        val plan = explain(
            SQL_ARTIST_WITH_COUNTS,
            DbTestFixture.ACCT_A, DbTestFixture.ACCT_A, DbTestFixture.ACCT_A, DbTestFixture.ACCT_A, DbTestFixture.ACCT_A,
        )
        Log.i(TAG, "EXPLAIN artist.observeAllWithCounts:\n$plan")

        assertTrue("observeAllWithCounts avg ${avg}ms exceeded bound", avg < BENCH_UPPER_BOUND_MS)
    }

    /** 计时 runs 次取均值（毫秒，SystemClock.elapsedRealtime）。 */
    private suspend fun averageMs(runs: Int, block: suspend () -> Unit): Long {
        var total = 0L
        repeat(runs) {
            val start = SystemClock.elapsedRealtime()
            block()
            total += SystemClock.elapsedRealtime() - start
        }
        return total / runs
    }

    /** 取 SQL 的 EXPLAIN QUERY PLAN，整段拼为可读字符串。 */
    private fun explain(sql: String, vararg args: Any?): String {
        val builder = StringBuilder()
        db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $sql", arrayOf<Any?>(*args))
            .use { cursor ->
                while (cursor.moveToNext()) {
                    for (col in 0 until cursor.columnCount) {
                        val value = runCatching { cursor.getString(col) }.getOrNull()
                        if (value != null) {
                            builder.append(value).append('\t')
                        }
                    }
                    builder.append('\n')
                }
            }
        return builder.toString()
    }
}
