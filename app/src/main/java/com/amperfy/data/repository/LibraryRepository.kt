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

package com.amperfy.data.repository

import com.amperfy.data.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 资料库域仓库：歌曲/专辑/艺术家/流派/电台的读写、收藏与评分、库统计、本地过滤搜索、歌词与 library 同步。
 * 对应 iOS: LibraryStorage + SubsonicLibrarySyncer 的 library 部分（artist/album/song/genre/radio）。
 * 出处：Repository 拆分批次 1——方法自 MusicRepository 原样搬移，签名与注释不变。
 */
interface LibraryRepository {
    fun getAllSongs(): Flow<List<Song>>
    fun getAllAlbums(): Flow<List<Album>>
    fun getAllArtists(): Flow<List<Artist>>

    // Favorite queries - 对应iOS: LibraryDisplayType.favoriteSongs/favoriteAlbums/favoriteArtists
    fun getFavoriteSongs(): Flow<List<Song>>
    fun getFavoriteAlbums(): Flow<List<Album>>
    fun getFavoriteArtists(): Flow<List<Artist>>
    fun getAlbumArtists(): Flow<List<Artist>>
    fun observeArtistById(artistId: String): Flow<Artist?>
    fun observeAlbumById(albumId: String): Flow<Album?>
    fun observeSongById(songId: String): Flow<Song?>
    fun getArtistSongs(artistId: String): Flow<List<Song>>
    fun getArtistAlbums(artistId: String): Flow<List<Album>>

    suspend fun getSongById(songId: String): Song?
    suspend fun getAlbumById(albumId: String): Album?
    suspend fun getArtistById(artistId: String): Artist?

    suspend fun toggleArtistFavorite(artistId: String): Result<Unit>
    suspend fun toggleAlbumFavorite(albumId: String): Result<Unit>
    suspend fun toggleSongFavorite(songId: String): Result<Unit>
    /** 显式设置歌曲收藏状态（供不依赖本地库现状的调用方使用，如在线搜索结果）。 */
    suspend fun setSongFavorite(songId: String, isFavorite: Boolean): Result<Unit>
    suspend fun updateArtistRating(artistId: String, rating: Int): Result<Unit>
    suspend fun updateAlbumRating(albumId: String, rating: Int): Result<Unit>
    suspend fun updateSongRating(songId: String, rating: Int): Result<Unit>

    fun getSongsByAlbum(albumId: String): Flow<List<Song>>
    fun getAlbumSongs(albumId: String): Flow<List<Song>>  // Alias for compatibility
    fun getSongsByArtist(artistId: String): Flow<List<Song>>
    fun getAlbumsByArtist(artistId: String): Flow<List<Album>>

    fun searchSongs(query: String): Flow<List<Song>>
    fun searchAlbums(query: String): Flow<List<Album>>
    fun searchArtists(query: String): Flow<List<Artist>>
    fun searchFavoriteArtists(query: String): Flow<List<Artist>>
    fun searchAlbumArtists(query: String): Flow<List<Artist>>
    /** 含缓存歌曲的专辑 id 集合（搜索 Cached 作用域过滤）。对应 iOS: LibraryStorage 缓存过滤查询 */
    fun getCachedAlbumIds(): Flow<Set<String>>
    /** 含缓存歌曲的艺术家 id 集合（搜索 Cached 作用域过滤）。 */
    fun getCachedArtistIds(): Flow<Set<String>>

    // ==================== 库统计 / 下载 ====================
    // 对应 iOS: LibrarySettingsView 的统计数字 + DownloadsVC 数据源

    /** 已下载（缓存）的歌曲列表。对应 iOS: getCachedSongs / DownloadsVC 已完成列表 */
    fun getDownloadedSongs(): Flow<List<Song>>
    /** 各实体数量（实时）。对应 iOS: LibrarySettingsView 统计行 */
    fun observeArtistCount(): Flow<Long>
    fun observeAlbumCount(): Flow<Long>
    fun observeSongCount(): Flow<Long>
    fun observeCachedSongCount(): Flow<Long>
    /** 已同步歌曲元数据的专辑数（后台同步进度显示用）。 */
    fun observeSyncedAlbumCount(): Flow<Long>

    /**
     * 封面总数（一次性）：album/artist/podcast 三表 cover_art 去重计数。
     * 对应 iOS: LibraryStorage.getArtworkCount(for:)（ArtworkSettingsView 统计行）。
     */
    suspend fun getArtworkCount(): Long

    // ==================== 流派（Phase 6.1） ====================
    // 对应 iOS: GenresVC / GenreDetailVC / SubsonicLibrarySyncer

    /** 观察全部流派（按 name 排序）。对应 iOS: GenreMO.alphabeticSortedFetchRequest */
    fun getAllGenres(): Flow<List<Genre>>
    /** 观察单个流派（name 为标识，Subsonic 无流派 id） */
    fun observeGenreByName(name: String): Flow<Genre?>
    /** 流派下歌曲（本地 genre 字段匹配）。对应 iOS: GenreSongsFetchedResultsController */
    fun getGenreSongs(genreName: String): Flow<List<Song>>
    /** 流派下专辑。对应 iOS: GenreAlbumsFetchedResultsController */
    fun getGenreAlbums(genreName: String): Flow<List<Album>>
    /** 流派下艺术家（经专辑/歌曲关联反查）。对应 iOS: GenreArtistsFetchedResultsController */
    fun getGenreArtists(genreName: String): Flow<List<Artist>>
    /** 含缓存歌曲的流派名集合（Cached 作用域过滤用） */
    fun getCachedGenreNames(): Flow<Set<String>>
    /** 全部歌曲均已缓存的专辑 id 集合（对应 iOS isCachedCompletely：菜单隐藏 Download） */
    fun getFullyCachedAlbumIds(): Flow<Set<String>>
    /** 全部歌曲均已缓存的艺术家 id 集合 */
    fun getFullyCachedArtistIds(): Flow<Set<String>>
    /** 全部歌曲均已缓存的流派名集合 */
    fun getFullyCachedGenreNames(): Flow<Set<String>>
    /** 同步流派列表（getGenres）。对应 iOS: syncInitial 中的 requestGenres + SsGenreParserDelegate */
    suspend fun syncGenres(): Result<Unit>
    /**
     * 同步流派详情：对流派已知专辑逐个 syncAlbumDetails 扇出
     * 对应 iOS: SubsonicLibrarySyncer.sync(genre:)（行216-227）——无 by-genre 端点
     */
    suspend fun syncGenreDetails(genreName: String): Result<Unit>

    // ==================== 电台（Phase 6.3） ====================
    // 对应 iOS: RadiosVC / SubsonicLibrarySyncer.syncRadios

    /** 观察全部电台（按 title 排序，iOS RadioMO.alphabeticSortedFetchRequest 以 title 为标识） */
    fun getAllRadios(): Flow<List<Radio>>
    /** 同步电台列表（getInternetRadioStations）。对应 iOS: syncRadios（含服务器已删除项清理） */
    suspend fun syncRadios(): Result<Unit>

    // ==================== 歌词（Phase 6.2） ====================

    /**
     * 获取歌曲结构化歌词（OpenSubsonic getLyricsBySongId，需服务器支持 songLyrics 扩展）
     * 不支持/无歌词时返回 null。对应 iOS: syncLyrics + parseLyrics
     * 注：iOS 在歌曲同步时抓取并落盘缓存（lyricsRelFilePath）；Android 为按需拉取 + 内存缓存（已知差异）
     */
    suspend fun getLyrics(songId: String): LyricsList?

    // ==================== Scrobble（Ampache 移植 Batch 2 追加的 API 无关缝） ====================
    // 对应 iOS LibrarySyncer 协议的 syncNowPlaying(song:songPosition:) / scrobble(song:date:)
    // ——两后端语义差异极大（Ampache 没有 nowPlaying 概念、提交动作是 record_play），
    // 故由域实现分派，ScrobbleSyncer 只管状态机不管协议。

    /**
     * 上报「正在播放」（Subsonic: scrobble submission=false；Ampache: **无对应物，空操作**）。
     * 对应 iOS: syncNowPlaying(song:songPosition:)（AmpacheLibrarySyncer.swift:1224-1232 为空实现）。
     */
    suspend fun reportNowPlaying(songId: String): Result<Unit>

    /**
     * 提交播放记录（Subsonic: scrobble submission=true + time；Ampache: record_play + date）。
     * 对应 iOS: scrobble(song:date:)。
     *
     * @param playedAtMillis 实际播放发生时刻（毫秒），供离线补传历史播放
     */
    suspend fun scrobble(songId: String, playedAtMillis: Long): Result<Unit>

    suspend fun syncArtists(): Result<Unit>
    suspend fun syncAlbums(): Result<Unit>
    /** 同步收藏（已加星标）的艺术家/专辑/歌曲。对应 iOS: syncFavoriteLibraryElements / getStarred2 */
    suspend fun syncFavoriteElements(): Result<Unit>
    /** 同步最新专辑（getAlbumList2 type=newest），按服务器顺序写入 newestIndex。对应 iOS: requestNewestAlbums */
    suspend fun syncNewestAlbums(count: Int = 20, offset: Int = 0): Result<Unit>
    /** 同步最近播放专辑（getAlbumList2 type=recent），按服务器顺序写入 recentIndex。对应 iOS: requestRecentAlbums */
    suspend fun syncRecentAlbums(count: Int = 20, offset: Int = 0): Result<Unit>
    /**
     * 同步最新专辑并对其中歌曲未同步的专辑扇出同步歌曲。
     * 对应 iOS: AutoDownloadLibrarySyncer.syncNewestLibraryElements（syncNewestAlbums 后
     * 对 !isSongsMetaDataSynced 的新专辑逐个 sync(album:)）
     *
     * @return 本次同步「新出现」的 newest 专辑的全部歌曲（首次填充返回空列表）；
     * 调用方在 isAutoCacheLatestSongs 开启时对其触发下载
     * （对应 iOS AutoDownloadLibrarySyncer.swift:73-80 的自动下载分支）
     */
    suspend fun syncNewestLibraryElements(count: Int = 20): Result<List<Song>>
    suspend fun syncArtistDetails(artistId: String): Result<Unit>
    suspend fun syncAlbumDetails(albumId: String): Result<Unit>

    /**
     * 查询所有歌曲元数据未同步的专辑 id（供 BackgroundLibrarySyncer 渐进补齐）
     * 对应 iOS: LibraryStorage.getAlbumWithoutSyncedSongs()（谓词 isSongsMetaDataSynced == FALSE）
     * suspend（P3 批次 1b 起）：Room 版 DAO 挂起查询，调用点已在协程内。
     */
    suspend fun getAlbumIdsWithoutSyncedSongs(): List<String>

    /**
     * 将专辑标记为歌曲已同步（同步失败时调用，避免每次启动重扫失败项）
     * 对应 iOS: BackgroundLibrarySyncer 错误分支 album.isSongsMetaDataSynced = true
     */
    suspend fun markAlbumSongsSynced(albumId: String)
}
