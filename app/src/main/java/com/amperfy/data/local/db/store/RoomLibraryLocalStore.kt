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

package com.amperfy.data.local.db.store

import androidx.room.withTransaction
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.entity.AccountScopeEntity
import com.amperfy.data.local.db.mapper.LibraryTextKeyNormalizer
import com.amperfy.data.local.db.mapper.toAlbum
import com.amperfy.data.local.db.mapper.toArtist
import com.amperfy.data.local.db.mapper.toDirectory
import com.amperfy.data.local.db.mapper.toEntity
import com.amperfy.data.local.db.mapper.toGenre
import com.amperfy.data.local.db.mapper.toMusicFolder
import com.amperfy.data.local.db.mapper.toPlaylist
import com.amperfy.data.local.db.mapper.toPodcast
import com.amperfy.data.local.db.mapper.toPodcastEpisode
import com.amperfy.data.local.db.mapper.toRadio
import com.amperfy.data.local.db.mapper.toSong
import com.amperfy.data.local.store.AlbumListKind
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Directory
import com.amperfy.data.model.Genre
import com.amperfy.data.model.MusicFolder
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.PodcastEpisodeRemoteStatus
import com.amperfy.data.model.Radio
import com.amperfy.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * LibraryLocalStore 的 Room 实现（专题 15 P3 批次 1a 引入，批次 4b 起接口方法已全部
 * override，P5 起为唯一实现）。
 *
 * **全部读写走 Room DAO**：Artist/Album/Song/Genre + Home、playlist、podcast/episode/radio、
 * directory/musicFolder 域的读写均由本类承担，构造只需 [AmperfyDatabase]。
 *
 * P3 批次 1b 起换绑为 LibraryLocalStore 的生产实现（首个运行时行为变化）：
 * library 域读写与歌曲本地状态切片真正走 Room；租户根由各 sync 写入口 [ensureScope] 幂等保证。
 *
 * P3 批次 2b 起 recentPlaylists / observePlaylistCount 也切 Room（随 playlist 域换绑同批下推到
 * playlistDao）。
 *
 * P3 批次 3b 起 podcast/episode/radio 域读写切 Room（该域首个运行时行为变化）：Home 的
 * radios/podcasts/newestPodcastEpisodes、列表 getAllRadios/getAllPodcasts/getAllPodcastEpisodes、
 * 详情 observePodcastById/getPodcastEpisodes、计数 observePodcastCount，以及 replaceRadios/
 * replacePodcasts/applyPodcastDetails/upsertNewestPodcastEpisodes 写全部走 Room DAO。
 *
 * P3 批次 4b 起 directory/musicFolder 域读写切 Room（该域首个运行时行为变化）：
 * 三层目录页的 getMusicFolders/observeMusicFolderById/getMusicFolderDirectories/observeDirectoryById/
 * getSubdirectories/getDirectorySongs 读，与 replaceMusicFolders/replaceTopDirectories/
 * replaceDirectoryChildren 写全部走 Room DAO。
 *
 * Flow override 一律映射领域模型后 `distinctUntilChanged()`（收敛表级失效带来的等值重发）。
 * 事务用 `db.withTransaction { }`。
 */
class RoomLibraryLocalStore(
    private val db: AmperfyDatabase,
) : LibraryLocalStore {

    private val artistDao get() = db.artistDao()
    private val albumDao get() = db.albumDao()
    private val songDao get() = db.songDao()
    private val genreDao get() = db.genreDao()
    private val albumSyncStateDao get() = db.albumSyncStateDao()
    private val playlistDao get() = db.playlistDao()
    private val radioDao get() = db.radioDao()
    private val podcastDao get() = db.podcastDao()
    private val episodeDao get() = db.podcastEpisodeDao()
    private val musicFolderDao get() = db.musicFolderDao()
    private val directoryDao get() = db.directoryDao()
    private val songDirectoryDao get() = db.songDirectoryDao()

    /** 本地子串搜索 LIKE 模式：normalizer 生成 search_key 再转义通配符，配合 DAO `ESCAPE '\'`。 */
    private fun likePattern(query: String): String =
        "%" + LibraryTextKeyNormalizer.escapeLikePattern(LibraryTextKeyNormalizer.searchKey(query)) + "%"

    /**
     * 保证账户租户根 account_scope 行存在。全部账户级表 FK→account_scope 级联，缺父行
     * 时 sync 写会被 FK 拒绝——生产路径首次 sync 前无人插 account_scope，故每个 sync 写入口最先
     * 幂等 upsert 一次（withTransaction 内则作事务第一步）。P4 起改由账户生命周期维护
     * （登录时建、登出 deleteByAccountId 级联删除），届时从各写入口移除本调用。
     */
    private suspend fun ensureScope(accountId: String) {
        db.accountScopeDao().upsert(AccountScopeEntity(accountId))
    }

    // ==================== Home ====================

    override fun recentAlbums(accountId: String): Flow<List<Album>> =
        albumDao.recentAlbums(accountId).map { rows -> rows.map { it.toAlbum() } }.distinctUntilChanged()

    override fun newestAlbums(accountId: String): Flow<List<Album>> =
        albumDao.newestAlbums(accountId).map { rows -> rows.map { it.toAlbum() } }.distinctUntilChanged()

    override suspend fun randomAlbums(accountId: String, count: Int, onlyCached: Boolean): List<Album> =
        if (onlyCached) {
            albumDao.randomCachedWithState(accountId, count).map { it.toAlbum() }
        } else {
            albumDao.randomWithState(accountId, count).map { it.toAlbum() }
        }

    override suspend fun randomArtists(accountId: String, count: Int, onlyCached: Boolean): List<Artist> =
        if (onlyCached) {
            artistDao.randomCachedWithCounts(accountId, count).map { it.toArtist() }
        } else {
            artistDao.randomWithCounts(accountId, count).map { it.toArtist() }
        }

    override suspend fun randomSongs(accountId: String, count: Int, onlyCached: Boolean): List<Song> =
        if (onlyCached) {
            songDao.randomCachedRows(accountId, count).map { it.toSong() }
        } else {
            songDao.randomRows(accountId, count).map { it.toSong() }
        }

    override suspend fun randomGenres(accountId: String, count: Int): List<Genre> =
        genreDao.randomRows(accountId, count).map { it.toGenre() }

    /**
     * 最近播放的播放列表（Home「Recently Played」section）。P3 批次 2b 切 Room：
     * playlistDao.observeRecentWithState 按 lastPlayed 倒序、name 升序取 20 条
     * （COALESCE(last_played,0) 兜底令从未播放的排最后）。
     */
    override fun recentPlaylists(accountId: String): Flow<List<Playlist>> =
        playlistDao.observeRecentWithState(accountId).map { rows -> rows.map { it.toPlaylist() } }.distinctUntilChanged()

    /** 播放列表数（资料库统计）。P3 批次 2b 切 Room。 */
    override fun observePlaylistCount(accountId: String): Flow<Long> =
        playlistDao.observeCount(accountId).distinctUntilChanged()

    // ==================== Song/Album/Artist 读 ====================

    override fun getAllSongs(accountId: String): Flow<List<Song>> =
        songDao.observeAllWithLocal(accountId).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    override fun getAllAlbums(accountId: String): Flow<List<Album>> =
        albumDao.observeAllWithState(accountId).map { rows -> rows.map { it.toAlbum() } }.distinctUntilChanged()

    override fun getAllArtists(accountId: String): Flow<List<Artist>> =
        artistDao.observeAllWithCounts(accountId).map { rows -> rows.map { it.toArtist() } }.distinctUntilChanged()

    override fun getFavoriteSongs(accountId: String): Flow<List<Song>> =
        songDao.observeFavorites(accountId).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    override fun getFavoriteAlbums(accountId: String): Flow<List<Album>> =
        albumDao.observeFavorites(accountId).map { rows -> rows.map { it.toAlbum() } }.distinctUntilChanged()

    override fun getFavoriteArtists(accountId: String): Flow<List<Artist>> =
        artistDao.observeFavorites(accountId).map { rows -> rows.map { it.toArtist() } }.distinctUntilChanged()

    override fun getAlbumArtists(accountId: String): Flow<List<Artist>> =
        artistDao.observeAlbumArtists(accountId).map { rows -> rows.map { it.toArtist() } }.distinctUntilChanged()

    override fun observeArtistById(accountId: String, artistId: String): Flow<Artist?> =
        artistDao.observeByServerId(accountId, artistId).map { it?.toArtist() }.distinctUntilChanged()

    override fun observeAlbumById(accountId: String, albumId: String): Flow<Album?> =
        albumDao.observeByServerId(accountId, albumId).map { it?.toAlbum() }.distinctUntilChanged()

    override fun observeSongById(accountId: String, songId: String): Flow<Song?> =
        songDao.observeByServerId(accountId, songId).map { it?.toSong() }.distinctUntilChanged()

    override fun getArtistSongs(accountId: String, artistId: String): Flow<List<Song>> =
        songDao.observeArtistSongsUnion(accountId, artistId).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    override fun getArtistAlbums(accountId: String, artistId: String): Flow<List<Album>> =
        albumDao.observeArtistAlbumsUnion(accountId, artistId).map { rows -> rows.map { it.toAlbum() } }.distinctUntilChanged()

    override suspend fun getSongById(accountId: String, songId: String): Song? =
        songDao.getWithLocalByServerId(accountId, songId)?.toSong()

    override suspend fun getAlbumById(accountId: String, albumId: String): Album? =
        albumDao.getWithStateByServerId(accountId, albumId)?.toAlbum()

    override suspend fun getArtistById(accountId: String, artistId: String): Artist? =
        artistDao.getWithCountsByServerId(accountId, artistId)?.toArtist()

    override fun getSongsByAlbum(accountId: String, albumId: String): Flow<List<Song>> =
        songDao.observeByAlbum(accountId, albumId).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    override fun getSongsByArtist(accountId: String, artistId: String): Flow<List<Song>> =
        songDao.observeByArtistDirect(accountId, artistId).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    override fun getAlbumsByArtist(accountId: String, artistId: String): Flow<List<Album>> =
        albumDao.observeByArtistDirect(accountId, artistId).map { rows -> rows.map { it.toAlbum() } }.distinctUntilChanged()

    // ==================== 搜索 ====================

    override fun searchSongs(accountId: String, query: String): Flow<List<Song>> =
        songDao.searchByKey(accountId, likePattern(query)).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    override fun searchAlbums(accountId: String, query: String): Flow<List<Album>> =
        albumDao.searchByKey(accountId, likePattern(query)).map { rows -> rows.map { it.toAlbum() } }.distinctUntilChanged()

    override fun searchArtists(accountId: String, query: String): Flow<List<Artist>> =
        artistDao.searchByKey(accountId, likePattern(query)).map { rows -> rows.map { it.toArtist() } }.distinctUntilChanged()

    override fun searchFavoriteArtists(accountId: String, query: String): Flow<List<Artist>> =
        artistDao.searchFavoritesByKey(accountId, likePattern(query)).map { rows -> rows.map { it.toArtist() } }.distinctUntilChanged()

    override fun searchAlbumArtists(accountId: String, query: String): Flow<List<Artist>> =
        artistDao.searchAlbumArtistsByKey(accountId, likePattern(query)).map { rows -> rows.map { it.toArtist() } }.distinctUntilChanged()

    // ==================== 缓存推导 / 下载 ====================

    override fun getCachedAlbumIds(accountId: String): Flow<Set<String>> =
        songDao.cachedAlbumIds(accountId).map { it.toSet() }.distinctUntilChanged()

    override fun getCachedArtistIds(accountId: String): Flow<Set<String>> =
        songDao.cachedArtistIds(accountId).map { it.toSet() }.distinctUntilChanged()

    override fun getDownloadedSongs(accountId: String): Flow<List<Song>> =
        songDao.observeDownloadedWithLocal(accountId).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    override fun getCachedGenreNames(accountId: String): Flow<Set<String>> =
        songDao.cachedGenreNames(accountId).map { it.toSet() }.distinctUntilChanged()

    override fun getFullyCachedAlbumIds(accountId: String): Flow<Set<String>> =
        songDao.fullyCachedAlbumIds(accountId).map { it.toSet() }.distinctUntilChanged()

    override fun getFullyCachedArtistIds(accountId: String): Flow<Set<String>> =
        songDao.fullyCachedArtistIds(accountId).map { it.toSet() }.distinctUntilChanged()

    override fun getFullyCachedGenreNames(accountId: String): Flow<Set<String>> =
        songDao.fullyCachedGenreNames(accountId).map { it.toSet() }.distinctUntilChanged()

    // ==================== 计数 ====================

    override fun observeArtistCount(accountId: String): Flow<Long> =
        artistDao.observeCount(accountId).distinctUntilChanged()

    override fun observeAlbumCount(accountId: String): Flow<Long> =
        albumDao.observeCount(accountId).distinctUntilChanged()

    override fun observeSongCount(accountId: String): Flow<Long> =
        songDao.observeCount(accountId).distinctUntilChanged()

    override fun observeCachedSongCount(accountId: String): Flow<Long> =
        songDao.observeCachedCount(accountId).distinctUntilChanged()

    override fun observeSyncedAlbumCount(accountId: String): Flow<Long> =
        albumDao.observeSyncedCount(accountId).distinctUntilChanged()

    override suspend fun getArtworkCount(accountId: String): Long =
        albumDao.getArtworkCount(accountId)

    override suspend fun getAlbumIdsWithoutSyncedSongs(accountId: String): List<String> =
        albumDao.getAlbumIdsWithoutSyncedSongs(accountId)

    // ==================== 流派 ====================

    override fun getAllGenres(accountId: String): Flow<List<Genre>> =
        genreDao.observeAll(accountId).map { rows -> rows.map { it.toGenre() } }.distinctUntilChanged()

    override fun observeGenreByName(accountId: String, name: String): Flow<Genre?> =
        genreDao.observeByName(accountId, name).map { it?.toGenre() }.distinctUntilChanged()

    override fun getGenreSongs(accountId: String, genreName: String): Flow<List<Song>> =
        songDao.observeByGenre(accountId, genreName).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    override fun getGenreAlbums(accountId: String, genreName: String): Flow<List<Album>> =
        albumDao.observeByGenre(accountId, genreName).map { rows -> rows.map { it.toAlbum() } }.distinctUntilChanged()

    override fun getGenreArtists(accountId: String, genreName: String): Flow<List<Artist>> =
        artistDao.observeByGenre(accountId, genreName).map { rows -> rows.map { it.toArtist() } }.distinctUntilChanged()

    // ==================== 本地状态写 ====================
    // artist/album 收藏·评分为单列 UPDATE：行不存在自然 no-op（不存在即跳过）。

    override suspend fun setArtistStarred(accountId: String, artistId: String, starredAt: Long?) {
        artistDao.updateStarred(accountId, artistId, starredAt)
    }

    override suspend fun setAlbumStarred(accountId: String, albumId: String, starredAt: Long?) {
        albumDao.updateStarred(accountId, albumId, starredAt)
    }

    override suspend fun setArtistRating(accountId: String, artistId: String, rating: Int) {
        artistDao.updateRating(accountId, artistId, rating)
    }

    override suspend fun setAlbumRating(accountId: String, albumId: String, rating: Int) {
        albumDao.updateRating(accountId, albumId, rating)
    }

    /**
     * 歌曲收藏 = 纯 Room UPDATE（单写，无双写）。
     */
    override suspend fun setSongStarred(accountId: String, songId: String, starredAt: Long?) {
        songDao.updateStarred(accountId, songId, starredAt)
    }

    /** 歌曲评分 = 纯 Room UPDATE（同 [setSongStarred]）。 */
    override suspend fun setSongRating(accountId: String, songId: String, rating: Int) {
        songDao.updateRating(accountId, songId, rating)
    }

    /**
     * 标记专辑歌曲元数据已同步。实体不存在时跳过：先查专辑存在，缺失则
     * 跳过——避免为库外专辑写出孤儿 album_sync_state 行。专辑存在即隐含
     * account_scope 存在（album 行 FK→account_scope），故此处无须 ensureScope。
     */
    override suspend fun markAlbumSongsSynced(accountId: String, albumId: String) {
        if (albumDao.getByServerId(accountId, albumId) == null) return
        albumSyncStateDao.setSongsSynced(accountId, albumId, true)
    }

    // ==================== library 核心 sync 写 ====================
    // 每个 sync 写入口最先 ensureScope（租户根保证）；无原生事务的三个 upsert 一并包进
    // withTransaction，令「建租户根 + upsert」原子提交。

    override suspend fun upsertArtists(accountId: String, artists: List<Artist>) {
        db.withTransaction {
            ensureScope(accountId)
            artistDao.upsertRemote(artists.map { it.toEntity(accountId) })
        }
    }

    /** 专辑 isSongsSynced/newest/recent 在独立表，结构上不受 album 全行 upsert 影响（天然保留）。 */
    override suspend fun upsertAlbums(accountId: String, albums: List<Album>) {
        db.withTransaction {
            ensureScope(accountId)
            albumDao.upsertRemote(albums.map { it.toEntity(accountId) })
        }
    }

    override suspend fun upsertArtistAlbums(accountId: String, artistId: String, albums: List<Album>) {
        db.withTransaction {
            ensureScope(accountId)
            albumDao.upsertRemote(albums.map { it.toEntity(accountId, artistIdOverride = artistId) })
        }
    }

    /** 歌曲远端 upsert + 标记专辑已同步；本地状态表不触碰 = 结构性保留。 */
    override suspend fun upsertAlbumSongs(accountId: String, albumId: String, songs: List<Song>) {
        db.withTransaction {
            ensureScope(accountId)
            songDao.upsertRemote(songs.map { it.toEntity(accountId, albumIdOverride = albumId) })
            albumSyncStateDao.setSongsSynced(accountId, albumId, true)
        }
    }

    override suspend fun applyAlbumListPage(
        accountId: String,
        kind: AlbumListKind,
        offset: Int,
        albums: List<Album>,
    ) {
        db.withTransaction {
            ensureScope(accountId)
            if (offset == 0) {
                when (kind) {
                    AlbumListKind.NEWEST -> albumSyncStateDao.clearNewestIndexes(accountId)
                    AlbumListKind.RECENT -> albumSyncStateDao.clearRecentIndexes(accountId)
                }
            }
            albumDao.upsertRemote(albums.map { it.toEntity(accountId) })
            albums.forEachIndexed { i, album ->
                val index = offset + i + 1
                when (kind) {
                    AlbumListKind.NEWEST -> albumSyncStateDao.setNewestIndex(accountId, album.id, index)
                    AlbumListKind.RECENT -> albumSyncStateDao.setRecentIndex(accountId, album.id, index)
                }
            }
        }
    }

    /**
     * 收藏快照全量应用（单事务）：三表**一律两步写**（INSERT OR IGNORE 全行 + 逐条择优 UPDATE），
     * 再做三表反向清理（取消已收藏但不在服务器列表中的收藏；空列表 = 全清）。
     *
     * 根因（2026-08-09 修）：本函数唯一数据源是 Subsonic `getStarred2`，
     * 其 album/song 条目视服务器实现常为**残缺行**（可能缺 genre/year/coverArt/duration/
     * bitRate/replayGain 等，且不带 userRating）。artist 路径此前已改为「两步写」防冲，但
     * album/song 仍走 `@Upsert` **整行替换**——一次收藏同步就把残缺字段写成 null/0/""，
     * 冲掉此前全量同步来的元数据（封面回落默认艺术图、时长归 0、ReplayGain 丢失），
     * 连本地 rating 也一并归零（AlbumEntity.rating 默认 0 / SongEntity.rating 默认 null），
     * 且此后不再恢复（下次全量同步前无人写回）。
     *
     * 修复：album/song 改为与 artist 同构的两步写——`insertIgnore` 只补新行、
     * `updateFavoriteSnapshot` 对既有行逐列择优（无条件写收藏态与名称派生键、可空列
     * COALESCE、非空默认列 CASE 守卫、rating 一律不碰），即
     * **只允许「置收藏 + 改善元数据」，永不降级**。派生键在此处用 [LibraryTextKeyNormalizer]
     * 现算传参（与 mapper 同一实现，保证与 name/title 原子一致）。
     *
     * 对齐 iOS：SubsonicLibrarySyncer.swift:706-751 syncFavoriteLibraryElements 经各
     * ParserDelegate 只设置响应里**出现的**字段（SsAlbumParserDelegate.swift:64-116、
     * SsPlayableParserDelegate.swift:61-105、SsSongParserDelegate.swift:63-138 全为 `if let` 守卫），
     * Core Data 未提及字段保持原值、从不清除；三表末尾的
     * `notFavorite...Anymore.forEach { $0.isFavorite = false }`（:730/:740/:750）
     * 即本函数的 clearStarredExcept。逐列分档理由见两个 DAO 的 updateFavoriteSnapshot KDoc。
     */
    override suspend fun applyFavoriteSnapshot(
        accountId: String,
        artists: List<Artist>,
        albums: List<Album>,
        songs: List<Song>,
    ) {
        db.withTransaction {
            ensureScope(accountId)
            artistDao.insertIgnore(artists.map { it.toEntity(accountId) })
            artists.forEach { artist ->
                artistDao.updatePreservingRating(
                    accountId = accountId,
                    serverId = artist.id,
                    name = artist.name,
                    coverArt = artist.coverArt,
                    starredAt = artist.starred,
                    searchKey = LibraryTextKeyNormalizer.searchKey(artist.name),
                    sectionKey = LibraryTextKeyNormalizer.sectionKey(artist.name),
                    sortKey = LibraryTextKeyNormalizer.sortKey(artist.name),
                )
            }

            albumDao.insertIgnore(albums.map { it.toEntity(accountId) })
            albums.forEach { album ->
                albumDao.updateFavoriteSnapshot(
                    accountId = accountId,
                    serverId = album.id,
                    name = album.name,
                    artistId = album.artistId,
                    artistName = album.artist,
                    genre = album.genre,
                    year = album.year,
                    songCount = album.songCount,
                    duration = album.duration,
                    coverArt = album.coverArt,
                    createdAt = album.created,
                    starredAt = album.starred,
                    searchKey = LibraryTextKeyNormalizer.searchKey(album.name),
                    sectionKey = LibraryTextKeyNormalizer.sectionKey(album.name),
                    sortKey = LibraryTextKeyNormalizer.sortKey(album.name),
                    artistSortKey = LibraryTextKeyNormalizer.sortKey(album.artist),
                )
            }

            songDao.insertIgnore(songs.map { it.toEntity(accountId) })
            songs.forEach { song ->
                songDao.updateFavoriteSnapshot(
                    accountId = accountId,
                    serverId = song.id,
                    title = song.title,
                    albumId = song.albumId,
                    albumName = song.album,
                    artistId = song.artistId,
                    artistName = song.artist,
                    albumArtistId = song.albumArtistId,
                    track = song.track,
                    disc = song.discNumber,
                    year = song.year,
                    genre = song.genre,
                    duration = song.duration,
                    bitrate = song.bitRate,
                    contentType = song.contentType,
                    size = song.size,
                    coverArt = song.coverArt,
                    suffix = song.suffix,
                    path = song.path,
                    type = song.type,
                    streamUrl = song.streamUrl,
                    createdAt = song.created,
                    starredAt = song.starred,
                    replayGainTrackGain = song.replayGainTrackGain,
                    replayGainTrackPeak = song.replayGainTrackPeak,
                    replayGainAlbumGain = song.replayGainAlbumGain,
                    replayGainAlbumPeak = song.replayGainAlbumPeak,
                    searchKey = LibraryTextKeyNormalizer.searchKey(song.title),
                    sectionKey = LibraryTextKeyNormalizer.sectionKey(song.title),
                    sortKey = LibraryTextKeyNormalizer.sortKey(song.title),
                )
            }

            artistDao.clearStarredExcept(accountId, artists.map { it.id })
            albumDao.clearStarredExcept(accountId, albums.map { it.id })
            songDao.clearStarredExcept(accountId, songs.map { it.id })
        }
    }

    override suspend fun replaceGenres(accountId: String, genres: List<Genre>) {
        db.withTransaction {
            ensureScope(accountId)
            genreDao.deleteMissingByName(accountId, genres.map { it.name })
            genreDao.upsertRemote(genres.map { it.toEntity(accountId) })
        }
    }

    // ==================== sync 支撑读（一次性） ====================

    override suspend fun getNewestAlbumIds(accountId: String): Set<String> =
        albumDao.getNewestAlbumIds(accountId).toSet()

    override suspend fun getNewestAlbumIdsWithoutSyncedSongs(accountId: String): List<String> =
        albumDao.getNewestAlbumIdsWithoutSyncedSongs(accountId)

    override suspend fun getAlbumSongsOnce(accountId: String, albumId: String): List<Song> =
        songDao.getByAlbumOnce(accountId, albumId).map { it.toSong() }

    override suspend fun getGenreAlbumIdsOnce(accountId: String, genreName: String): List<String> =
        albumDao.getGenreAlbumIdsOnce(accountId, genreName)

    // ==================== 电台 / 播客读（P3 批次 3b） ====================
    // 全部映射领域模型后 distinctUntilChanged；排序由 DAO sort_key/publish_date 下推，
    // 客户端不再重排。

    override fun getAllRadios(accountId: String): Flow<List<Radio>> =
        radioDao.observeAll(accountId).map { rows -> rows.map { it.toRadio() } }.distinctUntilChanged()

    /** Home「Radios」section 前 20 条。 */
    override fun radios(accountId: String): Flow<List<Radio>> =
        radioDao.observeAllLimited(accountId).map { rows -> rows.map { it.toRadio() } }.distinctUntilChanged()

    override fun getAllPodcasts(accountId: String): Flow<List<Podcast>> =
        podcastDao.observeActive(accountId).map { rows -> rows.map { it.toPodcast() } }.distinctUntilChanged()

    /** Home「Podcasts」section 前 20 条（仅存活频道）。 */
    override fun podcasts(accountId: String): Flow<List<Podcast>> =
        podcastDao.observeActiveLimited(accountId).map { rows -> rows.map { it.toPodcast() } }.distinctUntilChanged()

    override fun observePodcastById(accountId: String, podcastId: String): Flow<Podcast?> =
        podcastDao.observeByServerId(accountId, podcastId).map { it?.toPodcast() }.distinctUntilChanged()

    override fun observePodcastCount(accountId: String): Flow<Long> =
        podcastDao.observeActiveCount(accountId).distinctUntilChanged()

    /** 某频道单集（详情页）——投影版带出父频道名。 */
    override fun getPodcastEpisodes(accountId: String, podcastId: String): Flow<List<PodcastEpisode>> =
        episodeDao.observeEpisodesOf(accountId, podcastId).map { rows -> rows.map { it.toPodcastEpisode() } }.distinctUntilChanged()

    /** 全部存活频道单集（Episodes 显示模式）——投影版带父频道名。 */
    override fun getAllPodcastEpisodes(accountId: String): Flow<List<PodcastEpisode>> =
        episodeDao.observeAllOfActivePodcastsWithTitle(accountId).map { rows -> rows.map { it.toPodcastEpisode() } }.distinctUntilChanged()

    /** Home「Newest Episodes」前 20 条——投影版带父频道名。 */
    override fun newestPodcastEpisodes(accountId: String): Flow<List<PodcastEpisode>> =
        episodeDao.newestOfActivePodcastsWithTitle(accountId).map { rows -> rows.map { it.toPodcastEpisode() } }.distinctUntilChanged()

    // ==================== 电台 / 播客 sync 写（P3 批次 3b） ====================
    // 每个写入口最先 ensureScope（租户根保证），全程 db.withTransaction 原子提交。

    /**
     * 电台全量替换：upsert 存活电台 + 差集**硬删除**不在本次列表内的电台
     * （先删后 upsert；同事务内顺序无妨——deleteAllExcept 只删 keep 集之外，upsert 的行均在 keep 集）。
     */
    override suspend fun replaceRadios(accountId: String, radios: List<Radio>) {
        db.withTransaction {
            ensureScope(accountId)
            radioDao.upsertRemote(radios.map { it.toEntity(accountId) })
            radioDao.deleteAllExcept(accountId, radios.map { it.id })
        }
    }

    /**
     * 播客频道全量替换（软删除差集 + existing-or-create 保留 episodeCount）：
     * 1. markRemoteDeletedMissing：不在本次列表内的频道标 remote_status=1（软删除，不物理删）；
     * 2. insertIgnore：新频道建行——episode_count 强制 0
     *    （既存行由 IGNORE 保留原 episode_count）；
     * 3. updateChannelMetadata（复活版）：逐个更新元数据并置 remote_status=0
     *    （复活曾软删的频道），**不触碰 episode_count**。
     */
    override suspend fun replacePodcasts(accountId: String, podcasts: List<Podcast>) {
        db.withTransaction {
            ensureScope(accountId)
            podcastDao.markRemoteDeletedMissing(accountId, podcasts.map { it.id })
            // 新行 episode_count=0；既存行的 episode_count 由 IGNORE 保留
            podcastDao.insertIgnore(podcasts.map { it.toEntity(accountId).copy(episodeCount = 0) })
            podcasts.forEach { podcast ->
                podcastDao.updateChannelMetadata(
                    accountId = accountId,
                    serverId = podcast.id,
                    title = podcast.title,
                    description = podcast.depiction,
                    coverArt = podcast.coverArt,
                    searchKey = LibraryTextKeyNormalizer.searchKey(podcast.title),
                    sortKey = LibraryTextKeyNormalizer.sortKey(podcast.title),
                )
            }
        }
    }

    /**
     * 播客详情同步（对照 iOS sync(podcast:) diff）：
     * 1. 频道元数据：仅当 channelUpdate 非空且频道行存在
     *    时更新，用 updateChannelMetadataKeepStatus **保持 remote_status**（详情同步不复活软删频道，
     *    只赋 title/depiction/coverArt 不动 remoteStatus）；
     * 2. 软删除差集：该频道中不在本次 episodes 内的单集标 status=DELETED（不物理删）；
     * 3. 逐单集 upsert：父频道 id 经 [resolveEpisodePodcastId] 判存在（FK 约束，preferredId=podcastId）；
     * 4. recalcEpisodeCount：按非删除单集数重算（频道不存在时 UPDATE 自然 no-op）。
     */
    override suspend fun applyPodcastDetails(
        accountId: String,
        podcastId: String,
        channelUpdate: Podcast?,
        episodes: List<PodcastEpisode>,
    ) {
        db.withTransaction {
            ensureScope(accountId)
            if (channelUpdate != null && podcastDao.getByServerId(accountId, podcastId) != null) {
                podcastDao.updateChannelMetadataKeepStatus(
                    accountId = accountId,
                    serverId = podcastId,
                    title = channelUpdate.title,
                    description = channelUpdate.depiction,
                    coverArt = channelUpdate.coverArt,
                    searchKey = LibraryTextKeyNormalizer.searchKey(channelUpdate.title),
                    sortKey = LibraryTextKeyNormalizer.sortKey(channelUpdate.title),
                )
            }
            episodeDao.markDeletedMissing(
                accountId, podcastId, episodes.map { it.id }, PodcastEpisodeRemoteStatus.DELETED.raw,
            )
            episodes.forEach { episode ->
                val channelId = resolveEpisodePodcastId(
                    accountId, preferredId = podcastId, episodeChannelId = episode.podcastId,
                )
                episodeDao.upsertRemote(listOf(episode.toEntity(accountId, podcastIdOverride = channelId)))
            }
            podcastDao.recalcEpisodeCount(accountId, podcastId, PodcastEpisodeRemoteStatus.DELETED.raw)
        }
    }

    /**
     * upsert 一批跨播客最新单集（无指定父频道）：
     * 父频道 id 经 [resolveEpisodePodcastId]（preferredId=null → 直接走单集自带 podcastId 分支）；
     * 之后对涉及的非空 podcastId 逐个 recalcEpisodeCount（podcastId 去重）。
     */
    override suspend fun upsertNewestPodcastEpisodes(accountId: String, episodes: List<PodcastEpisode>) {
        db.withTransaction {
            ensureScope(accountId)
            episodes.forEach { episode ->
                val channelId = resolveEpisodePodcastId(
                    accountId, preferredId = null, episodeChannelId = episode.podcastId,
                )
                episodeDao.upsertRemote(listOf(episode.toEntity(accountId, podcastIdOverride = channelId)))
            }
            episodes.map { it.podcastId }.filter { it.isNotEmpty() }.distinct().forEach { channelId ->
                podcastDao.recalcEpisodeCount(accountId, channelId, PodcastEpisodeRemoteStatus.DELETED.raw)
            }
        }
    }

    /**
     * 解析单集应关联的父频道 server_id（供 FK 安全的 podcastIdOverride），
     * 优先指定频道、否则按单集自带 podcastId 查频道：
     * - preferredId（applyPodcastDetails 传本次同步的频道 id；upsertNewest 传 null）非空且其频道行存在 → 用 preferredId；
     * - 否则 episodeChannelId（单集自带频道 id，空串视同无，对照 repo 映射 podcastId = channelId ?: ""）其行存在 → 用之；
     * - 都不存在 → null。podcast_episode 对 podcast(account_id, podcast_id) 有强 FK CASCADE，
     *   频道不在本地时**必须传 null** 否则 FK 拒插；此时单集仍靠 account_scope FK 参与账户级联清理。
     */
    private suspend fun resolveEpisodePodcastId(
        accountId: String,
        preferredId: String?,
        episodeChannelId: String,
    ): String? {
        if (preferredId != null && podcastDao.getByServerId(accountId, preferredId) != null) return preferredId
        val channelId = episodeChannelId.takeIf { it.isNotEmpty() } ?: return null
        return if (podcastDao.getByServerId(accountId, channelId) != null) channelId else null
    }

    // ==================== 目录浏览读（P3 批次 4b） ====================
    // 全部映射领域模型后 distinctUntilChanged。排序一律由 DAO 下推、客户端不再重排：
    // 音乐文件夹按 server_id，目录按 sort_key（拼音分区序，与 1c 同款设计），
    // 目录内歌曲按 track（NULL 优先）。

    override fun getMusicFolders(accountId: String): Flow<List<MusicFolder>> =
        musicFolderDao.observeAll(accountId).map { rows -> rows.map { it.toMusicFolder() } }.distinctUntilChanged()

    override fun observeMusicFolderById(accountId: String, folderId: String): Flow<MusicFolder?> =
        musicFolderDao.observeByServerId(accountId, folderId).map { it?.toMusicFolder() }.distinctUntilChanged()

    override fun getMusicFolderDirectories(accountId: String, folderId: String): Flow<List<Directory>> =
        directoryDao.observeByMusicFolder(accountId, folderId).map { rows -> rows.map { it.toDirectory() } }.distinctUntilChanged()

    override fun observeDirectoryById(accountId: String, directoryId: String): Flow<Directory?> =
        directoryDao.observeByServerId(accountId, directoryId).map { it?.toDirectory() }.distinctUntilChanged()

    override fun getSubdirectories(accountId: String, directoryId: String): Flow<List<Directory>> =
        directoryDao.observeChildren(accountId, directoryId).map { rows -> rows.map { it.toDirectory() } }.distinctUntilChanged()

    /** 目录内歌曲——关系在 song_directory 独立表，故经该表 JOIN 投影。 */
    override fun getDirectorySongs(accountId: String, directoryId: String): Flow<List<Song>> =
        songDirectoryDao.observeSongsWithLocalIn(accountId, directoryId).map { rows -> rows.map { it.toSong() } }.distinctUntilChanged()

    // ==================== 目录浏览 sync 写（P3 批次 4b） ====================
    // 每个写入口最先 ensureScope（租户根保证），全程 db.withTransaction 原子提交。

    /**
     * 音乐文件夹全量替换：
     * 1. getServerIdsExcept 先取本次将被差集删除的文件夹 id，再 deleteByMusicFolderIds 级联清理
     *    其下顶层目录——music_folder 与 directory 间无 FK（标量 ID），级联须显式两步完成
     *    （删文件夹前先删其 music_folder_id 下的目录）；
     * 2. deleteAllExcept 删文件夹自身；
     * 3. upsertRemote 存活文件夹（music_folder 无挂载列，@Upsert 全行覆盖安全）。
     */
    override suspend fun replaceMusicFolders(accountId: String, folders: List<MusicFolder>) {
        db.withTransaction {
            ensureScope(accountId)
            val keepIds = folders.map { it.id }
            val doomed = musicFolderDao.getServerIdsExcept(accountId, keepIds)
            if (doomed.isNotEmpty()) {
                directoryDao.deleteByMusicFolderIds(accountId, doomed)
            }
            musicFolderDao.deleteAllExcept(accountId, keepIds)
            musicFolderDao.upsertRemote(folders.map { it.toEntity(accountId) })
        }
    }

    /**
     * 某音乐文件夹的顶层目录全量替换（folderId 范围差集删 +
     * existing-or-create 只写 name/coverArt/musicFolderId）：
     * 1. deleteInFolderExcept：该文件夹下不在本次列表内的顶层目录硬删；
     * 2. insertIgnoreAll：新目录建行（只填 music_folder_id 挂载列，parent_id 留 null）；
     * 3. 逐条 updateTopLevelMetadata：写 name/cover_art/music_folder_id 并原子重算 sort_key，
     *    **不触碰 parent_id**（@Upsert 会整行覆盖抹掉子目录路径写入的挂载列，故本路径禁用
     *    upsertRemote，见 [com.amperfy.data.local.db.dao.DirectoryDao] 类 KDoc）。
     */
    override suspend fun replaceTopDirectories(accountId: String, folderId: String, directories: List<Directory>) {
        db.withTransaction {
            ensureScope(accountId)
            directoryDao.deleteInFolderExcept(accountId, folderId, directories.map { it.id })
            directoryDao.insertIgnoreAll(directories.map { it.toEntity(accountId, musicFolderId = folderId) })
            directories.forEach { dir ->
                directoryDao.updateTopLevelMetadata(
                    accountId = accountId,
                    serverId = dir.id,
                    name = dir.name,
                    coverArt = dir.coverArt,
                    musicFolderId = folderId,
                    sortKey = LibraryTextKeyNormalizer.sortKey(dir.name),
                )
            }
        }
    }

    /**
     * 目录子内容全量替换（对照 iOS SsDirectoryParserDelegate）：
     * 1. 子目录：deleteChildrenExcept 差集**硬删实体** + insertIgnoreAll 建新行 + 逐条
     *    updateChildMetadata 写 name/cover_art/parent_id（**不触碰 music_folder_id**，两步 upsert
     *    理由同 [replaceTopDirectories]）；
     * 2. 歌曲实体：songDao.upsertRemote 全行 upsert——目录同步发现的新歌自然进入全局歌曲表。
     *    本地所有权字段（cache_path/play_count/play_progress）在 song_local_state 分表，
     *    remote upsert 物理上不触碰 = 结构性保留（保留语义由表结构兜底）；
     * 3. 歌曲关系差集：本次负载中消失的歌曲**仅 clearForSongs 解除目录关联，不删歌曲实体**
     *    （iOS removeFromSongs，冻结语义，DirectorySyncContractTest 锁定）；
     * 4. 逐首 setDirectory 建/改关联（song_directory 对 directory 无 FK，目录行未落库亦可写）。
     */
    override suspend fun replaceDirectoryChildren(
        accountId: String,
        directoryId: String,
        subdirectories: List<Directory>,
        songs: List<Song>
    ) {
        db.withTransaction {
            ensureScope(accountId)
            directoryDao.deleteChildrenExcept(accountId, directoryId, subdirectories.map { it.id })
            directoryDao.insertIgnoreAll(subdirectories.map { it.toEntity(accountId, parentId = directoryId) })
            subdirectories.forEach { dir ->
                directoryDao.updateChildMetadata(
                    accountId = accountId,
                    serverId = dir.id,
                    name = dir.name,
                    coverArt = dir.coverArt,
                    parentId = directoryId,
                    sortKey = LibraryTextKeyNormalizer.sortKey(dir.name),
                )
            }

            songDao.upsertRemote(songs.map { it.toEntity(accountId) })

            val serverSongIds = songs.map { it.id }.toSet()
            val missing = songDirectoryDao.getSongIdsIn(accountId, directoryId).filter { it !in serverSongIds }
            if (missing.isNotEmpty()) {
                songDirectoryDao.clearForSongs(accountId, missing)
            }
            songs.forEach { song ->
                songDirectoryDao.setDirectory(accountId, song.id, directoryId)
            }
        }
    }
}
