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
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

/**
 * album 远端快照 DAO。
 *
 * randomAlbums/randomCachedAlbums：Cached 判断唯一依据是歌曲
 * cache_path（EXISTS + JOIN song_local_state），不再读取任何 album.isCached 陈旧标志。
 *
 * P3 批次 1a 扩充：凡返回专辑的查询一律返回 [AlbumWithState] 投影（album LEFT JOIN
 * album_sync_state 取 newest/recent 序号 + EXISTS 计算 is_cached，见 [ALBUM_WITH_STATE]）。
 * 现有 randomAlbums/randomCachedAlbums（返回 AlbumEntity）保留不动，新增投影版语义不变。
 * Flow 查询排序统一 `sort_key COLLATE BINARY, server_id`。
 */
@Dao
interface AlbumDao {
    /** 远端快照全行 upsert（Remote 后缀强制；本地状态在独立表不可能被覆盖）。 */
    @Upsert
    suspend fun upsertRemote(rows: List<AlbumEntity>)

    @Query(
        "SELECT * FROM album WHERE account_id = :accountId " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeAll(accountId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): AlbumEntity?

    /**
     * Home 随机专辑（非 Cached 模式）。
     * `ORDER BY RANDOM() LIMIT :count` 扫描匹配行并建临时排序，先作为 v1 目标实现。
     */
    @Query(
        "SELECT * FROM album " +
            "WHERE account_id = :accountId " +
            "ORDER BY RANDOM() " +
            "LIMIT :count",
    )
    suspend fun randomAlbums(accountId: String, count: Int): List<AlbumEntity>

    /**
     * Home 随机专辑（Cached 模式）。
     * 唯一判断依据是歌曲 cache_path，不读 album.isCached。
     */
    @Query(
        "SELECT album.* " +
            "FROM album " +
            "WHERE album.account_id = :accountId " +
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
            "LIMIT :count",
    )
    suspend fun randomCachedAlbums(accountId: String, count: Int): List<AlbumEntity>

    // ==================== 本地状态写（P3 批次 1a，单列 UPDATE） ====================

    @Query("UPDATE album SET starred_at = :starredAt WHERE account_id = :accountId AND server_id = :albumId")
    suspend fun updateStarred(accountId: String, albumId: String, starredAt: Long?)

    @Query("UPDATE album SET rating = :rating WHERE account_id = :accountId AND server_id = :albumId")
    suspend fun updateRating(accountId: String, albumId: String, rating: Int)

    /** 收藏差集反向清理（keepIds 为空 = 全清）。 */
    @Query(
        "UPDATE album SET starred_at = NULL " +
            "WHERE account_id = :accountId AND starred_at IS NOT NULL AND server_id NOT IN (:keepIds)",
    )
    suspend fun clearStarredExcept(accountId: String, keepIds: List<String>)

    // ==================== 收藏快照两步写（对齐 ArtistDao，2026-08-09 修） ====================

    /**
     * 收藏快照两步写之一：整行 INSERT OR IGNORE（未知专辑按快照行整行落地——新行没有旧值可保）。
     * 已存在的行由 [updateFavoriteSnapshot] 逐列择优更新，本方法对其无副作用。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<AlbumEntity>)

    /**
     * 收藏快照两步写之二：**只允许「置收藏 + 改善元数据」，永不降级**。
     *
     * 根因：本方法唯一调用方 applyFavoriteSnapshot 的数据来自 Subsonic `getStarred2`，
     * 该端点的 album 条目视服务器实现常为**残缺行**（可能只带 id/name/artist/starred，
     * 缺 genre/year/coverArt/duration/songCount/created，且不带 userRating）。
     * 原实现走 `@Upsert` 整行替换，一次「收藏同步」就把这些残缺字段写成 null/0，
     * 冲掉此前全量同步（getAlbumList2/getArtist）来的元数据，连本地 rating 也一并归零
     * （AlbumEntity.rating 默认 0），且此后不再恢复（下次全量同步前无人写回）。
     *
     * 对齐 iOS 语义：SsAlbumParserDelegate 只设置响应里**出现的**字段——
     * `if let attributeYear = attributeDict["year"] { ... }`（SsAlbumParserDelegate.swift:64-116：
     * name:64、coverArt:67、year:72、songCount:75、duration:79、artist:83、genre:104 全为
     * `if let` 守卫），Core Data 中未提及的字段保持原值，从不清除。
     *
     * 逐列分档（accountId/serverId 为定位键，不在 SET 中）：
     * - **无条件写**：`starred_at`（本次快照正是要表达的收藏语义，与 artist 路径一致）；
     *   `name` 及其派生 `search_key`/`section_key`/`sort_key`（name 在快照中恒有值，
     *   iOS 侧 `if let name` 亦必然命中；派生键必须与 name 原子更新，否则搜索/索引键脱节）。
     * - **有值才写（COALESCE(:v, 列)）**：`artist_id`、`genre`、`year`、`cover_art`、`created_at`
     *   ——五列在实体中均可空，null 即「本次快照未提及」，保留既有值。
     * - **非空默认列用 CASE 守卫**：`artist_name`（默认 ""，DTO 缺 artist 时映射为 ""，
     *   `!= ''` 才写；`artist_sort_key` 由同一 artist_name 派生，故与之成对置于同一守卫内）；
     *   `song_count`、`duration`（默认 0，`> 0` 才写——0 只可能是「未下发」，
     *   专辑不存在 0 首歌/0 秒的有效快照）。
     * - **一律不碰**：`rating`（本地评分；对照 artist 路径 updatePreservingRating 的保 rating 语义
     *   ——iOS 侧 SsAlbumParserDelegate.swift:70 是无条件 `userRating ?? 0`，Android 刻意相异，
     *   避免残缺快照清空本地评分）；`remote_play_count`（收藏快照不是播放计数权威，
     *   Album.playCount 缺省即 0，写入会把远端统计冲成 0）。
     */
    @Query(
        "UPDATE album SET " +
            "name = :name, " +
            "starred_at = :starredAt, " +
            "artist_id = COALESCE(:artistId, artist_id), " +
            "genre = COALESCE(:genre, genre), " +
            "year = COALESCE(:year, year), " +
            "cover_art = COALESCE(:coverArt, cover_art), " +
            "created_at = COALESCE(:createdAt, created_at), " +
            "artist_name = CASE WHEN :artistName != '' THEN :artistName ELSE artist_name END, " +
            "artist_sort_key = CASE WHEN :artistName != '' THEN :artistSortKey ELSE artist_sort_key END, " +
            "song_count = CASE WHEN :songCount > 0 THEN :songCount ELSE song_count END, " +
            "duration = CASE WHEN :duration > 0 THEN :duration ELSE duration END, " +
            "search_key = :searchKey, section_key = :sectionKey, sort_key = :sortKey " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updateFavoriteSnapshot(
        accountId: String,
        serverId: String,
        name: String,
        artistId: String?,
        artistName: String,
        genre: String?,
        year: Int?,
        songCount: Int,
        duration: Int,
        coverArt: String?,
        createdAt: Long?,
        starredAt: Long?,
        searchKey: String,
        sectionKey: String,
        sortKey: String,
        artistSortKey: String,
    )

    // ==================== AlbumWithState 投影查询（P3 批次 1a） ====================

    @Query(ALBUM_WITH_STATE + "WHERE album.account_id = :accountId " + ORDER_SORT_KEY)
    fun observeAllWithState(accountId: String): Flow<List<AlbumWithState>>

    @Query(ALBUM_WITH_STATE + "WHERE album.account_id = :accountId AND album.server_id = :serverId")
    fun observeByServerId(accountId: String, serverId: String): Flow<AlbumWithState?>

    @Query(ALBUM_WITH_STATE + "WHERE album.account_id = :accountId AND album.server_id = :serverId")
    suspend fun getWithStateByServerId(accountId: String, serverId: String): AlbumWithState?

    @Query(ALBUM_WITH_STATE + "WHERE album.account_id = :accountId AND album.starred_at IS NOT NULL " + ORDER_SORT_KEY)
    fun observeFavorites(accountId: String): Flow<List<AlbumWithState>>

    /** 艺术家的专辑（仅 album.artist 直接关联）。 */
    @Query(ALBUM_WITH_STATE + "WHERE album.account_id = :accountId AND album.artist_id = :artistId " + ORDER_SORT_KEY)
    fun observeByArtistDirect(accountId: String, artistId: String): Flow<List<AlbumWithState>>

    /**
     * 艺术家的专辑并集：album.artist_id = X OR 含该艺术家歌曲的专辑，
     * 两支合并去重。
     */
    @Query(
        ALBUM_WITH_STATE +
            "WHERE album.account_id = :accountId AND (" +
            "  album.artist_id = :artistId " +
            "  OR EXISTS (SELECT 1 FROM song " +
            "             WHERE song.account_id = album.account_id " +
            "               AND song.album_id = album.server_id " +
            "               AND song.artist_id = :artistId)" +
            ") " + ORDER_SORT_KEY,
    )
    fun observeArtistAlbumsUnion(accountId: String, artistId: String): Flow<List<AlbumWithState>>

    @Query(ALBUM_WITH_STATE + "WHERE album.account_id = :accountId AND album.genre = :name " + ORDER_SORT_KEY)
    fun observeByGenre(accountId: String, name: String): Flow<List<AlbumWithState>>

    @Query(
        ALBUM_WITH_STATE +
            "WHERE album.account_id = :accountId AND album.search_key LIKE :pattern ESCAPE '\\' " + ORDER_SORT_KEY,
    )
    fun searchByKey(accountId: String, pattern: String): Flow<List<AlbumWithState>>

    /** 最近播放专辑（recent_index > 0，序号升序，最多 20）。 */
    @Query(
        ALBUM_WITH_STATE +
            "WHERE album.account_id = :accountId AND ass.recent_index > 0 " +
            "ORDER BY ass.recent_index ASC LIMIT 20",
    )
    fun recentAlbums(accountId: String): Flow<List<AlbumWithState>>

    /** 最新专辑（newest_index > 0，序号升序，最多 20）。 */
    @Query(
        ALBUM_WITH_STATE +
            "WHERE album.account_id = :accountId AND ass.newest_index > 0 " +
            "ORDER BY ass.newest_index ASC LIMIT 20",
    )
    fun newestAlbums(accountId: String): Flow<List<AlbumWithState>>

    @Query(ALBUM_WITH_STATE + "WHERE album.account_id = :accountId ORDER BY RANDOM() LIMIT :count")
    suspend fun randomWithState(accountId: String, count: Int): List<AlbumWithState>

    @Query(
        ALBUM_WITH_STATE +
            "WHERE album.account_id = :accountId AND EXISTS ( " + CACHED_EXISTS + ") " +
            "ORDER BY RANDOM() LIMIT :count",
    )
    suspend fun randomCachedWithState(accountId: String, count: Int): List<AlbumWithState>

    @Query("SELECT COUNT(*) FROM album WHERE account_id = :accountId")
    fun observeCount(accountId: String): Flow<Long>

    /**
     * 该账户封面（artwork）总数：album/artist/podcast 三表 cover_art 去重计数（一次性）。
     *
     * 对应 iOS: LibraryStorage.getArtworkCount(for:)——iOS 有独立 Artwork 实体，Android 无，
     * 故以「引用到的不同 cover_art 标识数」近似。口径说明：只计三张有独立封面语义的表；
     * song/playlist/radio/directory 虽也有 cover_art 列，但其值多为所属专辑/目录封面的重复
     * 引用（不代表新增封面资源），计入会显著虚高，故排除。
     *
     * 三表 UNION ALL 后一次 COUNT(DISTINCT)，跨表去重（同一 cover_art 只算一张）。
     */
    @Query(
        "SELECT COUNT(DISTINCT cover_art) FROM ( " +
            "SELECT cover_art FROM album WHERE account_id = :accountId AND cover_art IS NOT NULL " +
            "UNION ALL " +
            "SELECT cover_art FROM artist WHERE account_id = :accountId AND cover_art IS NOT NULL " +
            "UNION ALL " +
            "SELECT cover_art FROM podcast WHERE account_id = :accountId AND cover_art IS NOT NULL " +
            ") AS all_cover_arts",
    )
    suspend fun getArtworkCount(accountId: String): Long

    // ==================== sync 支撑读（P3 批次 1a） ====================

    /** 流派下专辑 id（一次性；供 syncGenreDetails 扇出）。 */
    @Query("SELECT server_id FROM album WHERE account_id = :accountId AND genre = :genreName")
    suspend fun getGenreAlbumIdsOnce(accountId: String, genreName: String): List<String>

    /** 当前 newest 专辑 id（JOIN album 保证专辑仍存在）。 */
    @Query(
        "SELECT album.server_id FROM album " +
            "JOIN album_sync_state ass ON ass.account_id = album.account_id AND ass.album_id = album.server_id " +
            "WHERE album.account_id = :accountId AND ass.newest_index > 0",
    )
    suspend fun getNewestAlbumIds(accountId: String): List<String>

    /** newest 列表中歌曲未同步的专辑 id。 */
    @Query(
        "SELECT album.server_id FROM album " +
            "JOIN album_sync_state ass ON ass.account_id = album.account_id AND ass.album_id = album.server_id " +
            "WHERE album.account_id = :accountId AND ass.newest_index > 0 AND ass.is_songs_synced = 0",
    )
    suspend fun getNewestAlbumIdsWithoutSyncedSongs(accountId: String): List<String>

    /**
     * 全部歌曲元数据未同步的专辑 id（BackgroundLibrarySyncer 渐进补齐用）。
     * LEFT JOIN 同步态：**无状态行 = 未同步**（COALESCE 兜底 0）。
     * suspend 挂起查询：调用链经 MusicRepositoryImpl.getAlbumIdsWithoutSyncedSongs（P3 批次 1b
     * 完成 suspend 传染）→ LibraryLocalStore → 本方法，全链在协程内、不阻塞主线程。
     */
    @Query(
        "SELECT album.server_id FROM album " +
            "LEFT JOIN album_sync_state ass ON ass.account_id = album.account_id AND ass.album_id = album.server_id " +
            "WHERE album.account_id = :accountId AND COALESCE(ass.is_songs_synced, 0) = 0",
    )
    suspend fun getAlbumIdsWithoutSyncedSongs(accountId: String): List<String>

    /** 已同步歌曲元数据的专辑数（后台同步进度显示用）。 */
    @Query(
        "SELECT COUNT(*) FROM album " +
            "JOIN album_sync_state ass ON ass.account_id = album.account_id AND ass.album_id = album.server_id " +
            "WHERE album.account_id = :accountId AND ass.is_songs_synced = 1",
    )
    fun observeSyncedCount(accountId: String): Flow<Long>

    companion object {
        /** 缓存 EXISTS 子查询体（供 is_cached 计算列与随机缓存查询复用）。 */
        const val CACHED_EXISTS =
            "SELECT 1 FROM song " +
                "JOIN song_local_state " +
                "  ON song_local_state.account_id = song.account_id " +
                " AND song_local_state.song_id = song.server_id " +
                "WHERE song.account_id = album.account_id " +
                "  AND song.album_id = album.server_id " +
                "  AND song_local_state.cache_path IS NOT NULL "

        /**
         * AlbumWithState 投影公共 SELECT + LEFT JOIN 片段。
         * album 全列 + newest/recent 序号（COALESCE 0）+ is_cached 计算列。尾部留空格接续 WHERE/ORDER。
         */
        const val ALBUM_WITH_STATE =
            "SELECT album.*, " +
                "COALESCE(ass.newest_index, 0) AS newest_index, " +
                "COALESCE(ass.recent_index, 0) AS recent_index, " +
                "EXISTS (" + CACHED_EXISTS + ") AS is_cached " +
                "FROM album " +
                "LEFT JOIN album_sync_state ass " +
                "  ON ass.account_id = album.account_id AND ass.album_id = album.server_id "

        const val ORDER_SORT_KEY = "ORDER BY album.sort_key COLLATE BINARY, album.server_id"
    }
}
