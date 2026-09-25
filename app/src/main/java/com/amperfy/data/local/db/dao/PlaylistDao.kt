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
import androidx.room.Query
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

/**
 * playlist 远端快照 DAO。
 *
 * observeAll 默认按 name 排序；UI 多排序（Name/LastPlayed/ChangeDate/Duration）为 ViewModel
 * 客户端排序（P3 批次 2：无字母索引、数据量小、域模型带全排序键，故不下推）。
 * 本地状态 last_played 在 playlist_local_state，远端 upsert 物理上无法覆盖。
 *
 * P3 批次 2a 扩充：凡返回播放列表的查询一律返回 [PlaylistWithState] 投影（playlist LEFT JOIN
 * playlist_local_state 取 last_played，见 [PLAYLIST_WITH_STATE]），领域 Playlist 的 lastPlayed
 * 全部来自本地状态列。search_key LIKE 的 pattern 由 Store 经 LibraryTextKeyNormalizer 生成并转义。
 * 变更写（updateName/updateCounts）与删除（deleteByServerId/deleteAllExcept）供 Store sync/编辑
 * 路径复用；顺序变更不经本 DAO（唯一写入口是 PlaylistSongDao.replacePlaylistSongs）。
 */
@Dao
interface PlaylistDao {
    /** 远端快照全行 upsert（Remote 后缀强制；本地状态在独立表不可能被覆盖）。 */
    @Upsert
    suspend fun upsertRemote(rows: List<PlaylistEntity>)

    @Query("SELECT * FROM playlist WHERE account_id = :accountId ORDER BY name")
    fun observeAll(accountId: String): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): PlaylistEntity?

    // ==================== PlaylistWithState 投影查询（P3 批次 2a） ====================

    /** 全部播放列表（+ 本地状态），按 name 排序（保持 observeAll 原序）。 */
    @Query(PLAYLIST_WITH_STATE + "WHERE playlist.account_id = :accountId ORDER BY playlist.name")
    fun observeAllWithState(accountId: String): Flow<List<PlaylistWithState>>

    /** 观察单个播放列表（主键直查）。 */
    @Query(PLAYLIST_WITH_STATE + "WHERE playlist.account_id = :accountId AND playlist.server_id = :serverId")
    fun observeWithStateByServerId(accountId: String, serverId: String): Flow<PlaylistWithState?>

    /** 单个播放列表（一次性）。 */
    @Query(PLAYLIST_WITH_STATE + "WHERE playlist.account_id = :accountId AND playlist.server_id = :serverId")
    suspend fun getWithStateByServerId(accountId: String, serverId: String): PlaylistWithState?

    /** 本地子串搜索（search_key LIKE，pattern 由 Store 经 normalizer 生成并转义），按 name 排序。 */
    @Query(
        PLAYLIST_WITH_STATE +
            "WHERE playlist.account_id = :accountId AND playlist.search_key LIKE :pattern ESCAPE '\\' " +
            "ORDER BY playlist.name",
    )
    fun searchByKey(accountId: String, pattern: String): Flow<List<PlaylistWithState>>

    /**
     * 预置（2b 接线用）：最近播放的播放列表（按 lastPlayed 倒序、
     * name 升序，取 20 条）。从未播放（last_played 为 null）
     * 经 COALESCE 兜底 0 排到最后，语义等义。
     */
    @Query(
        PLAYLIST_WITH_STATE +
            "WHERE playlist.account_id = :accountId " +
            "ORDER BY COALESCE(pls.last_played, 0) DESC, playlist.name ASC LIMIT 20",
    )
    fun observeRecentWithState(accountId: String): Flow<List<PlaylistWithState>>

    /**
     * 含缓存歌曲的播放列表 id 集合（Playlist cached = EXISTS 关联歌曲 cache_path 非空）。
     * 经 playlist_song 反查已缓存歌曲，不读任何 playlist 陈旧标志。
     */
    @Query(
        "SELECT DISTINCT playlist_id FROM playlist_song " +
            "WHERE account_id = :accountId " +
            "  AND EXISTS (SELECT 1 FROM song_local_state s " +
            "              WHERE s.account_id = playlist_song.account_id " +
            "                AND s.song_id = playlist_song.song_id " +
            "                AND s.cache_path IS NOT NULL)",
    )
    fun observeCachedPlaylistIds(accountId: String): Flow<List<String>>

    /**
     * 全部歌曲均已缓存的播放列表 id 集合（对应 iOS isCachedCompletely）。
     * 经 playlist_song 反查，口径与 observeCachedPlaylistIds 一致（不读任何陈旧标志）。
     */
    @Query(
        "SELECT playlist_id FROM playlist_song " +
            "LEFT JOIN song_local_state " +
            "  ON song_local_state.account_id = playlist_song.account_id " +
            "  AND song_local_state.song_id = playlist_song.song_id " +
            "WHERE playlist_song.account_id = :accountId " +
            "GROUP BY playlist_id " +
            "HAVING COUNT(*) = COUNT(song_local_state.cache_path)",
    )
    fun observeFullyCachedPlaylistIds(accountId: String): Flow<List<String>>

    /** 预置（2b 接线用）：播放列表数（列表页/统计用）。 */
    @Query("SELECT COUNT(*) FROM playlist WHERE account_id = :accountId")
    fun observeCount(accountId: String): Flow<Long>

    // ==================== 变更写（P3 批次 2a） ====================
    // 顺序变更一律不经本 DAO（唯一写入口 PlaylistSongDao.replacePlaylistSongs）。
    // 行不存在时 UPDATE 自然 no-op；不存在守卫由 Store 层负责（不存在即跳过）。

    /**
     * 重命名并同步重算 search_key（search_key 由 name 派生，须原子一并更新，
     * 否则出现「显示已改但搜索仍命中旧值」的静默不一致）。searchKey 由 Store 经 normalizer 算好传入。
     */
    @Query(
        "UPDATE playlist SET name = :name, search_key = :searchKey " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updateName(accountId: String, serverId: String, name: String, searchKey: String)

    /** 回写 songCount/duration（append/remove/reorder/details 后维护值）。 */
    @Query(
        "UPDATE playlist SET song_count = :songCount, duration = :duration " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updateCounts(accountId: String, serverId: String, songCount: Int, duration: Int)

    /** 删除单个播放列表（FK CASCADE 连带清 playlist_song；playlist_local_state 由 Store 显式清）。 */
    @Query("DELETE FROM playlist WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun deleteByServerId(accountId: String, serverId: String)

    /**
     * replacePlaylists prune：删除不在 keepServerIds 内的播放列表（限本账户，FK CASCADE 连带清
     * 被删列表的 playlist_song）。keepServerIds 为空 = 全删（NOT IN 空表语义，P2 已验证可行）。
     */
    @Query("DELETE FROM playlist WHERE account_id = :accountId AND server_id NOT IN (:keepServerIds)")
    suspend fun deleteAllExcept(accountId: String, keepServerIds: List<String>)

    companion object {
        /**
         * PlaylistWithState 投影公共 SELECT + LEFT JOIN 片段。
         * playlist 全列 + 本地状态 last_played（缺行为 null）。尾部留空格接续 WHERE/ORDER。
         */
        const val PLAYLIST_WITH_STATE =
            "SELECT playlist.*, pls.last_played AS last_played " +
                "FROM playlist " +
                "LEFT JOIN playlist_local_state pls " +
                "  ON pls.account_id = playlist.account_id " +
                " AND pls.playlist_id = playlist.server_id "
    }
}
