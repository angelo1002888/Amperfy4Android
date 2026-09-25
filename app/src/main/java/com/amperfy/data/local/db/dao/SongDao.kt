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
import com.amperfy.data.local.db.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * song 远端快照 DAO。
 *
 * observeCachedSongs 经 JOIN song_local_state（双列匹配）+ cache_path IS NOT NULL 过滤，
 * 缓存判断唯一依据是本地状态表，与 album/artist cached 语义一致。
 *
 * P3 批次 1a 扩充：凡返回歌曲的查询一律返回 [SongWithLocal] 投影（song LEFT JOIN
 * song_local_state，见 [SONG_WITH_LOCAL]），领域 Song 的 isDownloaded/downloadPath/playCount
 * 全部来自本地状态列。Flow 查询排序统一 `sort_key COLLATE BINARY, server_id`。
 * 所有查询强制携带 account_id。
 */
@Dao
interface SongDao {
    /** 远端快照全行 upsert（Remote 后缀强制；本地状态在独立表不可能被覆盖）。 */
    @Upsert
    suspend fun upsertRemote(rows: List<SongEntity>)

    @Query(
        "SELECT * FROM song WHERE account_id = :accountId " +
            "ORDER BY sort_key COLLATE BINARY, server_id",
    )
    fun observeAll(accountId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM song WHERE account_id = :accountId AND server_id = :serverId")
    suspend fun getByServerId(accountId: String, serverId: String): SongEntity?

    // ==================== 本地状态写（P3 批次 1a，单列 UPDATE） ====================
    // 收藏/评分先服务器成功后落库；行不存在自然 no-op（不在库即跳过）。

    @Query("UPDATE song SET starred_at = :starredAt WHERE account_id = :accountId AND server_id = :songId")
    suspend fun updateStarred(accountId: String, songId: String, starredAt: Long?)

    @Query("UPDATE song SET rating = :rating WHERE account_id = :accountId AND server_id = :songId")
    suspend fun updateRating(accountId: String, songId: String, rating: Int?)

    /**
     * 收藏差集反向清理（applyFavoriteSnapshot 用）：本账户已收藏但不在 keepIds 内的取消收藏。
     * keepIds 为空 = 全清（NOT IN 空表语义，P2 已验证可行）。
     */
    @Query(
        "UPDATE song SET starred_at = NULL " +
            "WHERE account_id = :accountId AND starred_at IS NOT NULL AND server_id NOT IN (:keepIds)",
    )
    suspend fun clearStarredExcept(accountId: String, keepIds: List<String>)

    // ==================== 收藏快照两步写（对齐 ArtistDao/AlbumDao，2026-08-09 修） ====================

    /**
     * 收藏快照两步写之一：整行 INSERT OR IGNORE（未知歌曲按快照行整行落地——新行没有旧值可保）。
     * 已存在的行由 [updateFavoriteSnapshot] 逐列择优更新，本方法对其无副作用。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<SongEntity>)

    /**
     * 收藏快照两步写之二：**只允许「置收藏 + 改善元数据」，永不降级**。
     *
     * 根因：本方法唯一调用方 applyFavoriteSnapshot 的数据来自 Subsonic `getStarred2`，
     * 该端点的 song 条目视服务器实现常为**残缺行**（可能缺 genre/year/coverArt/duration/
     * bitRate/size/contentType/suffix/path/replayGain，且不带 userRating）。原实现走 `@Upsert`
     * 整行替换，一次「收藏同步」就把这些字段写成 null/0/""，冲掉此前全量同步（getAlbum 等）
     * 来的元数据——封面回落默认艺术图、时长显示 0:00、ReplayGain 标签丢失，本地 rating
     * 也一并归零，且此后不再恢复（下次全量同步前无人写回）。
     *
     * 对齐 iOS 语义：SsPlayableParserDelegate/SsSongParserDelegate 只设置响应里**出现的**字段
     * （SsPlayableParserDelegate.swift:61-105：title:61、track:68、year:71、duration:74、size:77、
     * bitRate:80、contentType:83、discNumber:86、coverArt:103 全为 `if let` 守卫；
     * SsSongParserDelegate.swift:63-138：artistId:63、albumId:98、genre:121、created:134 同样
     * `if let`），Core Data 中未提及的字段保持原值，从不清除。
     *
     * 逐列分档（accountId/serverId 为定位键，不在 SET 中）：
     * - **无条件写**：`starred_at`（本次快照正是要表达的收藏语义）；`title` 及其派生
     *   `search_key`/`section_key`/`sort_key`（title 在快照中恒有值，SongDto.title 非空；
     *   派生键须与 title 原子更新，否则搜索/索引键脱节）。
     * - **有值才写（COALESCE(:v, 列)）**：`album_id`、`artist_id`、`album_artist_id`、`track`、
     *   `disc`、`year`、`genre`、`bitrate`、`content_type`、`size`、`cover_art`、`suffix`、
     *   `created_at`、`stream_url`、`type`、四个 `replay_gain_*`——均为可空列，
     *   null 即「本次快照未提及」，保留既有值。
     * - **非空默认列用 CASE 守卫**：`album_name`/`artist_name`（默认 ""，DTO 缺 album/artist 时
     *   映射为 ""，`!= ''` 才写）；`duration`（默认 0，`> 0` 才写——0 只可能是「未下发」）；
     *   `path`（默认 ""，`!= ''` 才写；path 是离线播放回退地址，空串覆盖会致播放失败）。
     *   歌曲无 album_sort_key/artist_sort_key 派生键，故此三列无需成对更新。
     * - **一律不碰**：`rating`（本地评分；与 artist/album 路径同构——iOS 侧
     *   SsPlayableParserDelegate.swift:89 是无条件 `userRating ?? 0`，Android 刻意相异，
     *   避免残缺快照清空本地评分）；`is_video`（布尔列无「缺失」表达形式，
     *   DTO 缺省即 false，无法与「服务器明确下发 false」区分，宁可不写）。
     */
    @Query(
        "UPDATE song SET " +
            "title = :title, " +
            "starred_at = :starredAt, " +
            "album_id = COALESCE(:albumId, album_id), " +
            "artist_id = COALESCE(:artistId, artist_id), " +
            "album_artist_id = COALESCE(:albumArtistId, album_artist_id), " +
            "track = COALESCE(:track, track), " +
            "disc = COALESCE(:disc, disc), " +
            "year = COALESCE(:year, year), " +
            "genre = COALESCE(:genre, genre), " +
            "bitrate = COALESCE(:bitrate, bitrate), " +
            "content_type = COALESCE(:contentType, content_type), " +
            "size = COALESCE(:size, size), " +
            "cover_art = COALESCE(:coverArt, cover_art), " +
            "suffix = COALESCE(:suffix, suffix), " +
            "created_at = COALESCE(:createdAt, created_at), " +
            "stream_url = COALESCE(:streamUrl, stream_url), " +
            "type = COALESCE(:type, type), " +
            "replay_gain_track_gain = COALESCE(:replayGainTrackGain, replay_gain_track_gain), " +
            "replay_gain_track_peak = COALESCE(:replayGainTrackPeak, replay_gain_track_peak), " +
            "replay_gain_album_gain = COALESCE(:replayGainAlbumGain, replay_gain_album_gain), " +
            "replay_gain_album_peak = COALESCE(:replayGainAlbumPeak, replay_gain_album_peak), " +
            "album_name = CASE WHEN :albumName != '' THEN :albumName ELSE album_name END, " +
            "artist_name = CASE WHEN :artistName != '' THEN :artistName ELSE artist_name END, " +
            "duration = CASE WHEN :duration > 0 THEN :duration ELSE duration END, " +
            "path = CASE WHEN :path != '' THEN :path ELSE path END, " +
            "search_key = :searchKey, section_key = :sectionKey, sort_key = :sortKey " +
            "WHERE account_id = :accountId AND server_id = :serverId",
    )
    suspend fun updateFavoriteSnapshot(
        accountId: String,
        serverId: String,
        title: String,
        albumId: String?,
        albumName: String,
        artistId: String?,
        artistName: String,
        albumArtistId: String?,
        track: Int?,
        disc: Int?,
        year: Int?,
        genre: String?,
        duration: Int,
        bitrate: Int?,
        contentType: String?,
        size: Long?,
        coverArt: String?,
        suffix: String?,
        path: String,
        type: String?,
        streamUrl: String?,
        createdAt: Long?,
        starredAt: Long?,
        replayGainTrackGain: Float?,
        replayGainTrackPeak: Float?,
        replayGainAlbumGain: Float?,
        replayGainAlbumPeak: Float?,
        searchKey: String,
        sectionKey: String,
        sortKey: String,
    )

    // ==================== SongWithLocal 投影查询（P3 批次 1a） ====================

    /** 全部歌曲（+ 本地状态）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId " + ORDER_SORT_KEY)
    fun observeAllWithLocal(accountId: String): Flow<List<SongWithLocal>>

    /** 观察单首歌曲（主键直查）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId AND song.server_id = :serverId")
    fun observeByServerId(accountId: String, serverId: String): Flow<SongWithLocal?>

    /** 单首歌曲（一次性）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId AND song.server_id = :serverId")
    suspend fun getWithLocalByServerId(accountId: String, serverId: String): SongWithLocal?

    /** 批量取歌（IN，供下载列表一次装配，禁逐条）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId AND song.server_id IN (:serverIds)")
    suspend fun getRowsByServerIds(accountId: String, serverIds: List<String>): List<SongWithLocal>

    /** 专辑内歌曲。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId AND song.album_id = :albumId " + ORDER_SORT_KEY)
    fun observeByAlbum(accountId: String, albumId: String): Flow<List<SongWithLocal>>

    /** 艺术家的歌曲（仅直接关联）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId AND song.artist_id = :artistId " + ORDER_SORT_KEY)
    fun observeByArtistDirect(accountId: String, artistId: String): Flow<List<SongWithLocal>>

    /**
     * 艺术家的歌曲并集：song.artist_id = X OR 经 album.artist_id = X
     * （双谓词命中的同一歌曲由主键天然去重）。
     */
    @Query(
        SONG_WITH_LOCAL +
            "WHERE song.account_id = :accountId AND (" +
            "  song.artist_id = :artistId " +
            "  OR EXISTS (SELECT 1 FROM album " +
            "             WHERE album.account_id = song.account_id " +
            "               AND album.server_id = song.album_id " +
            "               AND album.artist_id = :artistId)" +
            ") " + ORDER_SORT_KEY,
    )
    fun observeArtistSongsUnion(accountId: String, artistId: String): Flow<List<SongWithLocal>>

    /** 收藏歌曲（starred_at 非空）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId AND song.starred_at IS NOT NULL " + ORDER_SORT_KEY)
    fun observeFavorites(accountId: String): Flow<List<SongWithLocal>>

    /** 已下载（缓存）歌曲。 */
    @Query(
        SONG_WITH_LOCAL +
            "WHERE song.account_id = :accountId AND song_local_state.cache_path IS NOT NULL " + ORDER_SORT_KEY,
    )
    fun observeDownloadedWithLocal(accountId: String): Flow<List<SongWithLocal>>

    /** 本地子串搜索（search_key LIKE，pattern 由 store 经 normalizer 生成并转义）。 */
    @Query(
        SONG_WITH_LOCAL +
            "WHERE song.account_id = :accountId AND song.search_key LIKE :pattern ESCAPE '\\' " + ORDER_SORT_KEY,
    )
    fun searchByKey(accountId: String, pattern: String): Flow<List<SongWithLocal>>

    /** 流派下歌曲（本地 genre 字段匹配）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId AND song.genre = :name " + ORDER_SORT_KEY)
    fun observeByGenre(accountId: String, name: String): Flow<List<SongWithLocal>>

    /** 随机歌曲（ORDER BY RANDOM）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId ORDER BY RANDOM() LIMIT :count")
    suspend fun randomRows(accountId: String, count: Int): List<SongWithLocal>

    /** 随机缓存歌曲（cache_path 非空）。 */
    @Query(
        SONG_WITH_LOCAL +
            "WHERE song.account_id = :accountId AND song_local_state.cache_path IS NOT NULL " +
            "ORDER BY RANDOM() LIMIT :count",
    )
    suspend fun randomCachedRows(accountId: String, count: Int): List<SongWithLocal>

    /** 专辑内歌曲（一次性；供 sync 收集新歌）。 */
    @Query(SONG_WITH_LOCAL + "WHERE song.account_id = :accountId AND song.album_id = :albumId")
    suspend fun getByAlbumOnce(accountId: String, albumId: String): List<SongWithLocal>

    // ==================== 缓存推导 / 计数（P3 批次 1a） ====================

    /** 含缓存歌曲的专辑 id 集合（经已缓存歌曲反查 album_id）。 */
    @Query(
        "SELECT DISTINCT song.album_id FROM song " +
            "JOIN song_local_state " +
            "  ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "WHERE song.account_id = :accountId " +
            "  AND song_local_state.cache_path IS NOT NULL AND song.album_id IS NOT NULL",
    )
    fun cachedAlbumIds(accountId: String): Flow<List<String>>

    /**
     * 含缓存歌曲的艺术家 id 集合：已缓存歌曲的直接 artist_id ∪ 经 album.artist_id，
     * 并集去重。
     */
    @Query(
        "SELECT DISTINCT artist_id FROM (" +
            "  SELECT song.artist_id AS artist_id FROM song " +
            "    JOIN song_local_state " +
            "      ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "    WHERE song.account_id = :accountId " +
            "      AND song_local_state.cache_path IS NOT NULL AND song.artist_id IS NOT NULL " +
            "  UNION " +
            "  SELECT album.artist_id AS artist_id FROM song " +
            "    JOIN song_local_state " +
            "      ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "    JOIN album " +
            "      ON album.account_id = song.account_id AND album.server_id = song.album_id " +
            "    WHERE song.account_id = :accountId " +
            "      AND song_local_state.cache_path IS NOT NULL AND album.artist_id IS NOT NULL" +
            ")",
    )
    fun cachedArtistIds(accountId: String): Flow<List<String>>

    /** 含缓存歌曲的流派名集合（Cached 作用域过滤用）。 */
    @Query(
        "SELECT DISTINCT song.genre FROM song " +
            "JOIN song_local_state " +
            "  ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "WHERE song.account_id = :accountId " +
            "  AND song_local_state.cache_path IS NOT NULL AND song.genre IS NOT NULL",
    )
    fun cachedGenreNames(accountId: String): Flow<List<String>>

    // ==================== 全缓存推导（对应 iOS AbstractPlayable.isCachedCompletely） ====================
    // 口径：GROUP BY 容器 HAVING 该容器歌曲总数 == 已缓存数（COUNT(cache_path) 只计非空），
    // 空容器（无歌曲）不出现在结果里——iOS isCachedCompletely 对空集合虽为 true，
    // 但空容器本就无可下载项，Download 显示与否无实际差别。

    /** 全部歌曲均已缓存的专辑 id 集合。 */
    @Query(
        "SELECT song.album_id FROM song " +
            "LEFT JOIN song_local_state " +
            "  ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "WHERE song.account_id = :accountId AND song.album_id IS NOT NULL " +
            "GROUP BY song.album_id " +
            "HAVING COUNT(*) = COUNT(song_local_state.cache_path)",
    )
    fun fullyCachedAlbumIds(accountId: String): Flow<List<String>>

    /**
     * 全部歌曲均已缓存的艺术家 id 集合。
     * 归属口径与 cachedArtistIds 一致：歌曲直接 artist_id ∪ 经 album.artist_id，
     * 先展开成 (artist_id, song 是否缓存) 再按艺术家聚合。
     */
    @Query(
        "SELECT artist_id FROM (" +
            "  SELECT song.artist_id AS artist_id, song_local_state.cache_path AS cache_path " +
            "    FROM song " +
            "    LEFT JOIN song_local_state " +
            "      ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "    WHERE song.account_id = :accountId AND song.artist_id IS NOT NULL " +
            "  UNION ALL " +
            "  SELECT album.artist_id AS artist_id, song_local_state.cache_path AS cache_path " +
            "    FROM song " +
            "    LEFT JOIN song_local_state " +
            "      ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "    JOIN album " +
            "      ON album.account_id = song.account_id AND album.server_id = song.album_id " +
            "    WHERE song.account_id = :accountId AND album.artist_id IS NOT NULL" +
            ") GROUP BY artist_id HAVING COUNT(*) = COUNT(cache_path)",
    )
    fun fullyCachedArtistIds(accountId: String): Flow<List<String>>

    /** 全部歌曲均已缓存的流派名集合（Subsonic 流派以 name 为标识）。 */
    @Query(
        "SELECT song.genre FROM song " +
            "LEFT JOIN song_local_state " +
            "  ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "WHERE song.account_id = :accountId AND song.genre IS NOT NULL " +
            "GROUP BY song.genre " +
            "HAVING COUNT(*) = COUNT(song_local_state.cache_path)",
    )
    fun fullyCachedGenreNames(accountId: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM song WHERE account_id = :accountId")
    fun observeCount(accountId: String): Flow<Long>

    @Query(
        "SELECT COUNT(*) FROM song " +
            "JOIN song_local_state " +
            "  ON song_local_state.account_id = song.account_id AND song_local_state.song_id = song.server_id " +
            "WHERE song.account_id = :accountId AND song_local_state.cache_path IS NOT NULL",
    )
    fun observeCachedCount(accountId: String): Flow<Long>

    companion object {
        /**
         * SongWithLocal 投影公共 SELECT + LEFT JOIN 片段。
         * 拼接实体全列 + 本地状态 cache_path/play_count；play_count 缺行 COALESCE 兜底 0。
         * 尾部留空格，供各查询接续 WHERE/ORDER。
         */
        const val SONG_WITH_LOCAL =
            "SELECT song.*, " +
                "song_local_state.cache_path AS cache_path, " +
                "COALESCE(song_local_state.play_count, 0) AS play_count " +
                "FROM song " +
                "LEFT JOIN song_local_state " +
                "  ON song_local_state.account_id = song.account_id " +
                " AND song_local_state.song_id = song.server_id "

        /** 字母列表统一排序（sort_key 二进制序 + server_id tie-break）。 */
        const val ORDER_SORT_KEY = "ORDER BY song.sort_key COLLATE BINARY, song.server_id"
    }
}
