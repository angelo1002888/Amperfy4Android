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
import com.amperfy.data.local.db.entity.ArtistEntity
import kotlinx.coroutines.flow.Flow

/**
 * artist 远端快照 DAO。
 *
 * 所有查询强制携带 account_id。排序统一 `sort_key COLLATE BINARY` + server_id
 * tie-break，保证 # 真正最后且结果稳定。
 *
 * P3 批次 1a 扩充：artist 的 albumCount/songCount/duration 不再是陈旧存储字段（不靠
 * 全表扫描把统计回写进实体），改由 [ArtistWithCounts] 投影经集合式 CTE 聚合算出
 * （口径见 [COUNTS_CTE]）。凡返回艺术家的查询一律返回该投影。
 */
@Dao
interface ArtistDao {
    /**
     * 远端快照全行 upsert。方法名强制 Remote 后缀：本地状态在独立表，
     * 物理上不可能覆盖 cache_path/play_count 等本地字段。
     */
    @Upsert
    suspend fun upsertRemote(rows: List<ArtistEntity>)

    @Query(
        "SELECT * FROM artist WHERE account_id = :accountId " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeAll(accountId: String): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): ArtistEntity?

    // ==================== 本地状态写 / 收藏快照两步 upsert（P3 批次 1a） ====================

    @Query("UPDATE artist SET starred_at = :starredAt WHERE account_id = :accountId AND server_id = :artistId")
    suspend fun updateStarred(accountId: String, artistId: String, starredAt: Long?)

    @Query("UPDATE artist SET rating = :rating WHERE account_id = :accountId AND server_id = :artistId")
    suspend fun updateRating(accountId: String, artistId: String, rating: Int)

    /** 收藏差集反向清理（keepIds 为空 = 全清）。 */
    @Query(
        "UPDATE artist SET starred_at = NULL " +
            "WHERE account_id = :accountId AND starred_at IS NOT NULL AND server_id NOT IN (:keepIds)",
    )
    suspend fun clearStarredExcept(accountId: String, keepIds: List<String>)

    /**
     * 收藏快照两步 upsert 之一：整行 INSERT OR IGNORE（新艺术家用入参 rating，已存在则忽略）。
     * 与 [updatePreservingRating] 配合——收藏快照须保留本地已有 rating：
     * 统计列不落库，故只需保留 rating（更新时不触碰该列）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<ArtistEntity>)

    /**
     * 收藏快照两步 upsert 之二：更新除 rating 外的列（rating 由 [insertIgnore] 或既有值保留）。
     *
     * `cover_art` 走 `COALESCE(:coverArt, cover_art)`——**入参为 null 时保留既有封面**。
     * 根因：本方法唯一调用方 applyFavoriteSnapshot 的数据来自 Subsonic `getStarred2`，
     * 该端点的 artist 条目常不带 `coverArt` 字段（只有 id/name/starred），映射后 coverArt 为 null；
     * 若无条件覆盖，一次「收藏同步」就会把已有的 `ar-*` 封面冲成 null，行内与详情页头部
     * 双双回落到艺术家默认艺术图，且此后不再恢复（下次全量同步前无人写回）。
     * 对齐 iOS 语义：Artwork 实体只在 remoteInfo 存在时更新，**从不清除**
     * （AmperfyKit/Api/LibrarySyncer 各 parse 里 `if let coverArtId = ... { artwork = ... }`，
     * 无 else 分支置 nil）。starred_at 仍无条件覆盖——它正是本次快照要表达的语义。
     */
    @Query(
        "UPDATE artist SET name = :name, cover_art = COALESCE(:coverArt, cover_art), " +
            "starred_at = :starredAt, " +
            "search_key = :searchKey, section_key = :sectionKey, sort_key = :sortKey " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updatePreservingRating(
        accountId: String,
        serverId: String,
        name: String,
        coverArt: String?,
        starredAt: Long?,
        searchKey: String,
        sectionKey: String,
        sortKey: String,
    )

    // ==================== ArtistWithCounts 投影查询（P3 批次 1a） ====================

    @Query(COUNTS_CTE + COUNTS_SELECT + "WHERE artist.account_id = :accountId " + ORDER_SORT_KEY)
    fun observeAllWithCounts(accountId: String): Flow<List<ArtistWithCounts>>

    @Query(COUNTS_CTE + COUNTS_SELECT + "WHERE artist.account_id = :accountId AND artist.server_id = :serverId")
    fun observeByServerId(accountId: String, serverId: String): Flow<ArtistWithCounts?>

    @Query(COUNTS_CTE + COUNTS_SELECT + "WHERE artist.account_id = :accountId AND artist.server_id = :serverId")
    suspend fun getWithCountsByServerId(accountId: String, serverId: String): ArtistWithCounts?

    @Query(
        COUNTS_CTE + COUNTS_SELECT +
            "WHERE artist.account_id = :accountId AND artist.starred_at IS NOT NULL " + ORDER_SORT_KEY,
    )
    fun observeFavorites(accountId: String): Flow<List<ArtistWithCounts>>

    /**
     * Album Artists（名下有直接关联专辑的艺术家，对照 iOS albums.@count > 0 谓词，
     * 用 EXISTS 而**非**相关计数列——计数含合辑会误带仅出现在合辑中的歌手）。
     */
    @Query(
        COUNTS_CTE + COUNTS_SELECT +
            "WHERE artist.account_id = :accountId AND EXISTS (" + ALBUM_ARTIST_EXISTS + ") " + ORDER_SORT_KEY,
    )
    fun observeAlbumArtists(accountId: String): Flow<List<ArtistWithCounts>>

    @Query(
        COUNTS_CTE + COUNTS_SELECT +
            "WHERE artist.account_id = :accountId AND artist.search_key LIKE :pattern ESCAPE '\\' " + ORDER_SORT_KEY,
    )
    fun searchByKey(accountId: String, pattern: String): Flow<List<ArtistWithCounts>>

    @Query(
        COUNTS_CTE + COUNTS_SELECT +
            "WHERE artist.account_id = :accountId AND artist.search_key LIKE :pattern ESCAPE '\\' " +
            "AND artist.starred_at IS NOT NULL " + ORDER_SORT_KEY,
    )
    fun searchFavoritesByKey(accountId: String, pattern: String): Flow<List<ArtistWithCounts>>

    @Query(
        COUNTS_CTE + COUNTS_SELECT +
            "WHERE artist.account_id = :accountId AND artist.search_key LIKE :pattern ESCAPE '\\' " +
            "AND EXISTS (" + ALBUM_ARTIST_EXISTS + ") " + ORDER_SORT_KEY,
    )
    fun searchAlbumArtistsByKey(accountId: String, pattern: String): Flow<List<ArtistWithCounts>>

    /**
     * 流派下艺术家：名下专辑或歌曲命中该流派。
     */
    @Query(
        COUNTS_CTE + COUNTS_SELECT +
            "WHERE artist.account_id = :accountId AND (" +
            "  EXISTS (SELECT 1 FROM album WHERE album.account_id = artist.account_id " +
            "            AND album.artist_id = artist.server_id AND album.genre = :name) " +
            "  OR EXISTS (SELECT 1 FROM song WHERE song.account_id = artist.account_id " +
            "            AND song.artist_id = artist.server_id AND song.genre = :name)" +
            ") " + ORDER_SORT_KEY,
    )
    fun observeByGenre(accountId: String, name: String): Flow<List<ArtistWithCounts>>

    @Query(COUNTS_CTE + COUNTS_SELECT + "WHERE artist.account_id = :accountId ORDER BY RANDOM() LIMIT :count")
    suspend fun randomWithCounts(accountId: String, count: Int): List<ArtistWithCounts>

    /**
     * 随机缓存艺术家：**只看直接关联歌曲**是否缓存（不经专辑间接关联）。
     */
    @Query(
        COUNTS_CTE + COUNTS_SELECT +
            "WHERE artist.account_id = :accountId AND EXISTS (" +
            "  SELECT 1 FROM song " +
            "    JOIN song_local_state " +
            "      ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "    WHERE song.account_id = artist.account_id AND song.artist_id = artist.server_id " +
            "      AND song_local_state.cache_path IS NOT NULL" +
            ") ORDER BY RANDOM() LIMIT :count",
    )
    suspend fun randomCachedWithCounts(accountId: String, count: Int): List<ArtistWithCounts>

    @Query("SELECT COUNT(*) FROM artist WHERE account_id = :accountId")
    fun observeCount(accountId: String): Flow<Long>

    companion object {
        /** iOS albums.@count > 0 谓词的 EXISTS 形态（Album Artist 过滤复用）。 */
        const val ALBUM_ARTIST_EXISTS =
            "SELECT 1 FROM album " +
                "WHERE album.account_id = artist.account_id AND album.artist_id = artist.server_id"

        /**
         * 相关计数聚合 CTE：
         * - related_song：直接歌曲（song.artist_id = X）∪ 经 album.artist_id = X 的歌曲，UNION 去重
         *   → 歌曲数 = COUNT、时长 = SUM(duration)；
         * - related_album：名下专辑（album.artist_id = X）∪ 直接歌曲的 album_id，UNION 去重 → 专辑数。
         *
         * 全为集合式 UNION + GROUP BY 单次聚合，禁止逐行相关子查询；CTE 内均按 :accountId 隔离，
         * 外层 LEFT JOIN artist 后对无关联艺术家兜底 0。尾部留空格接续 [COUNTS_SELECT]。
         */
        const val COUNTS_CTE =
            "WITH related_song AS (" +
                "  SELECT song.artist_id AS artist_id, song.server_id AS song_id, song.duration AS duration " +
                "    FROM song WHERE song.account_id = :accountId AND song.artist_id IS NOT NULL " +
                "  UNION " +
                "  SELECT album.artist_id AS artist_id, song.server_id AS song_id, song.duration AS duration " +
                "    FROM song JOIN album " +
                "      ON album.account_id = song.account_id AND album.server_id = song.album_id " +
                "    WHERE song.account_id = :accountId AND album.artist_id IS NOT NULL" +
                "), related_album AS (" +
                "  SELECT album.artist_id AS artist_id, album.server_id AS album_id " +
                "    FROM album WHERE album.account_id = :accountId AND album.artist_id IS NOT NULL " +
                "  UNION " +
                "  SELECT song.artist_id AS artist_id, song.album_id AS album_id " +
                "    FROM song WHERE song.account_id = :accountId " +
                "      AND song.artist_id IS NOT NULL AND song.album_id IS NOT NULL" +
                "), song_agg AS (" +
                "  SELECT artist_id, COUNT(*) AS song_count, SUM(duration) AS duration " +
                "    FROM related_song GROUP BY artist_id" +
                "), album_agg AS (" +
                "  SELECT artist_id, COUNT(*) AS album_count FROM related_album GROUP BY artist_id" +
                ") "

        /** 外层投影：artist 全列 + 三聚合列（LEFT JOIN 兜底 0）。尾部留空格接续 WHERE。 */
        const val COUNTS_SELECT =
            "SELECT artist.*, " +
                "COALESCE(album_agg.album_count, 0) AS related_album_count, " +
                "COALESCE(song_agg.song_count, 0) AS related_song_count, " +
                "COALESCE(song_agg.duration, 0) AS related_duration " +
                "FROM artist " +
                "LEFT JOIN song_agg ON song_agg.artist_id = artist.server_id " +
                "LEFT JOIN album_agg ON album_agg.artist_id = artist.server_id "

        const val ORDER_SORT_KEY = "ORDER BY artist.sort_key COLLATE BINARY, artist.server_id"
    }
}
