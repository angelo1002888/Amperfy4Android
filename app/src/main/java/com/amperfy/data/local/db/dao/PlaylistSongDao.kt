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

package com.amperfy.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.amperfy.data.local.db.entity.PlaylistSongEntity
import com.amperfy.data.local.db.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * playlist_song 有序关系 DAO。
 *
 * **唯一写入口是 replacePlaylistSongs（整表替换）**：无论是详情同步还是本地拖拽重排，
 * 都只能全删该 playlist 旧行 + 按顺序批量插入连续 position=0..N-1。
 * **禁止提供任何逐行 UPDATE position 方法**——交换两个以 position 为主键的行会主键冲突；
 * 该约束写进接口命名并由主键冲突/事务回滚测试锁定（测试在批次 3）。
 */
@Dao
interface PlaylistSongDao {
    /** 内部助手：删除该 playlist 全部旧行。仅供 replacePlaylistSongs 调用。 */
    @Query("DELETE FROM playlist_song WHERE account_id = :accountId AND playlist_id = :playlistId")
    suspend fun deleteForPlaylist(accountId: String, playlistId: String)

    /** 内部助手：批量插入连续 position 的行。仅供 replacePlaylistSongs 调用。 */
    @Insert
    suspend fun insertAll(rows: List<PlaylistSongEntity>)

    /**
     * 整表替换该 playlist 的歌曲顺序（唯一写入口）：先删旧行，再按 songIds 顺序
     * 批量插入 position=0..N-1。songIds 允许重复（同一歌曲可多次出现）。
     */
    @Transaction
    suspend fun replacePlaylistSongs(accountId: String, playlistId: String, songIds: List<String>) {
        deleteForPlaylist(accountId, playlistId)
        val rows = songIds.mapIndexed { index, songId ->
            PlaylistSongEntity(
                accountId = accountId,
                playlistId = playlistId,
                position = index,
                songId = songId,
            )
        }
        insertAll(rows)
    }

    /** 按 position 顺序返回歌曲 id 列表（允许重复）。 */
    @Query(
        "SELECT song_id FROM playlist_song " +
            "WHERE account_id = :accountId AND playlist_id = :playlistId " +
            "ORDER BY position",
    )
    suspend fun getSongIdsOrdered(accountId: String, playlistId: String): List<String>

    /**
     * 按 position 顺序 JOIN song 表返回歌曲实体。
     * INNER JOIN——尚未同步到本地的歌曲行不出现，父歌曲到达后自动补齐。
     */
    @Query(
        "SELECT song.* FROM playlist_song " +
            "JOIN song " +
            "  ON song.account_id = playlist_song.account_id " +
            " AND song.server_id = playlist_song.song_id " +
            "WHERE playlist_song.account_id = :accountId " +
            "  AND playlist_song.playlist_id = :playlistId " +
            "ORDER BY playlist_song.position",
    )
    fun observeSongsOf(accountId: String, playlistId: String): Flow<List<SongEntity>>

    // ==================== SongWithLocal 投影读 / 计数助手（P3 批次 2a） ====================

    /**
     * 按 position 顺序返回播放列表歌曲（+ 本地状态）。
     * 列形状与 [SongDao.SONG_WITH_LOCAL] 一致（song.* + cache_path + COALESCE(play_count,0)），
     * 复用 [SongWithLocal] 投影。playlist_song INNER JOIN song——未同步歌曲不出现；
     * 同一 song_id 重复出现产出重复行（合同要求）。
     *
     * **本查询引用 song_local_state 表，使 Room 表级失效覆盖成员歌曲缓存状态变化**——即「成员歌曲
     * 状态变化也要通知播放列表歌曲观察者」合同：
     * 同一收集器存活期间，成员歌曲 cache_path 由 null→非空（下载完成）会触发再发射，缓存图标实时刷新，
     * 不靠重新订阅（详见 LibraryReadContractTest 用例 2）。
     */
    @Query(
        "SELECT song.*, " +
            "song_local_state.cache_path AS cache_path, " +
            "COALESCE(song_local_state.play_count, 0) AS play_count " +
            "FROM playlist_song " +
            "JOIN song " +
            "  ON song.account_id = playlist_song.account_id " +
            " AND song.server_id = playlist_song.song_id " +
            "LEFT JOIN song_local_state " +
            "  ON song_local_state.account_id = song.account_id " +
            " AND song_local_state.song_id = song.server_id " +
            "WHERE playlist_song.account_id = :accountId " +
            "  AND playlist_song.playlist_id = :playlistId " +
            "ORDER BY playlist_song.position",
    )
    fun observeSongsWithLocalOf(accountId: String, playlistId: String): Flow<List<SongWithLocal>>

    /** 关系行数（含重复、含未同步歌曲的 position 占位；songCount 重算用）。 */
    @Query("SELECT COUNT(*) FROM playlist_song WHERE account_id = :accountId AND playlist_id = :playlistId")
    suspend fun countFor(accountId: String, playlistId: String): Int

    /**
     * 有序关系内本地存在歌曲的时长总和（重复歌曲按出现次数计；
     * INNER JOIN 排除未同步歌曲）。空时 COALESCE 兜底 0。
     */
    @Query(
        "SELECT COALESCE(SUM(song.duration), 0) FROM playlist_song " +
            "JOIN song " +
            "  ON song.account_id = playlist_song.account_id " +
            " AND song.server_id = playlist_song.song_id " +
            "WHERE playlist_song.account_id = :accountId " +
            "  AND playlist_song.playlist_id = :playlistId",
    )
    suspend fun sumDurationFor(accountId: String, playlistId: String): Int
}
