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

package com.amperfy.data.repository.ampache

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.store.AlbumListKind
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Genre
import com.amperfy.data.model.LyricsList
import com.amperfy.data.model.Radio
import com.amperfy.data.model.Song
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.repository.LibraryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 资料库域的 Ampache 实现（对应 iOS `AmpacheLibrarySyncer` 的 library 部分）。
 *
 * 与 Subsonic 侧 [com.amperfy.data.repository.LibraryRepositoryImpl] **同构**：
 * 本地读方法逐字委托同一个 [LibraryLocalStore]（本地库结构与后端无关），
 * 只有远端方法换成 Ampache 端点 + [AmpacheDtoMappers] 映射。
 * 每个 sync 方法的 `isSyncAllowed` 早退、「服务器成功才写本地」纪律、EventLogger 钩子位
 * 与 Subsonic 侧一一对应（钩子下沉在 [runAmpache]）。
 *
 * 出处：Ampache API 移植 Batch 2。
 */
internal class AmpacheLibraryRepositoryImpl(
    ampacheApi: AmpacheApi,
    authSession: AmpacheAuthSession,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val libraryLocalStore: LibraryLocalStore,
    boundAccountInfo: AccountInfo?,
) : BaseAmpacheRepository(
    ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    LibraryRepository {

    // ==================== 本地读（与 Subsonic 侧逐字相同） ====================

    override fun getAllSongs(): Flow<List<Song>> = libraryLocalStore.getAllSongs(currentAccountId)

    override fun getAllAlbums(): Flow<List<Album>> = libraryLocalStore.getAllAlbums(currentAccountId)

    override fun getAllArtists(): Flow<List<Artist>> = libraryLocalStore.getAllArtists(currentAccountId)

    override fun getFavoriteSongs(): Flow<List<Song>> = libraryLocalStore.getFavoriteSongs(currentAccountId)

    override fun getFavoriteAlbums(): Flow<List<Album>> = libraryLocalStore.getFavoriteAlbums(currentAccountId)

    override fun getFavoriteArtists(): Flow<List<Artist>> = libraryLocalStore.getFavoriteArtists(currentAccountId)

    override fun getAlbumArtists(): Flow<List<Artist>> = libraryLocalStore.getAlbumArtists(currentAccountId)

    override fun observeArtistById(artistId: String): Flow<Artist?> =
        libraryLocalStore.observeArtistById(currentAccountId, artistId)

    override fun observeAlbumById(albumId: String): Flow<Album?> =
        libraryLocalStore.observeAlbumById(currentAccountId, albumId)

    override fun observeSongById(songId: String): Flow<Song?> =
        libraryLocalStore.observeSongById(currentAccountId, songId)

    override fun getArtistSongs(artistId: String): Flow<List<Song>> =
        libraryLocalStore.getArtistSongs(currentAccountId, artistId)

    override fun getArtistAlbums(artistId: String): Flow<List<Album>> =
        libraryLocalStore.getArtistAlbums(currentAccountId, artistId)

    override suspend fun getSongById(songId: String): Song? =
        libraryLocalStore.getSongById(currentAccountId, songId)

    override suspend fun getAlbumById(albumId: String): Album? =
        libraryLocalStore.getAlbumById(currentAccountId, albumId)

    override suspend fun getArtistById(artistId: String): Artist? =
        libraryLocalStore.getArtistById(currentAccountId, artistId)

    override fun getSongsByAlbum(albumId: String): Flow<List<Song>> =
        libraryLocalStore.getSongsByAlbum(currentAccountId, albumId)

    override fun getAlbumSongs(albumId: String): Flow<List<Song>> = getSongsByAlbum(albumId)

    override fun getSongsByArtist(artistId: String): Flow<List<Song>> =
        libraryLocalStore.getSongsByArtist(currentAccountId, artistId)

    override fun getAlbumsByArtist(artistId: String): Flow<List<Album>> =
        libraryLocalStore.getAlbumsByArtist(currentAccountId, artistId)

    override fun searchSongs(query: String): Flow<List<Song>> =
        libraryLocalStore.searchSongs(currentAccountId, query)

    override fun searchAlbums(query: String): Flow<List<Album>> =
        libraryLocalStore.searchAlbums(currentAccountId, query)

    override fun searchArtists(query: String): Flow<List<Artist>> =
        libraryLocalStore.searchArtists(currentAccountId, query)

    override fun searchFavoriteArtists(query: String): Flow<List<Artist>> =
        libraryLocalStore.searchFavoriteArtists(currentAccountId, query)

    override fun searchAlbumArtists(query: String): Flow<List<Artist>> =
        libraryLocalStore.searchAlbumArtists(currentAccountId, query)

    override fun getCachedAlbumIds(): Flow<Set<String>> =
        libraryLocalStore.getCachedAlbumIds(currentAccountId)

    override fun getCachedArtistIds(): Flow<Set<String>> =
        libraryLocalStore.getCachedArtistIds(currentAccountId)

    override fun getDownloadedSongs(): Flow<List<Song>> =
        libraryLocalStore.getDownloadedSongs(currentAccountId)

    override fun observeArtistCount(): Flow<Long> =
        libraryLocalStore.observeArtistCount(currentAccountId)

    override fun observeAlbumCount(): Flow<Long> =
        libraryLocalStore.observeAlbumCount(currentAccountId)

    override fun observeSongCount(): Flow<Long> =
        libraryLocalStore.observeSongCount(currentAccountId)

    override fun observeCachedSongCount(): Flow<Long> =
        libraryLocalStore.observeCachedSongCount(currentAccountId)

    override fun observeSyncedAlbumCount(): Flow<Long> =
        libraryLocalStore.observeSyncedAlbumCount(currentAccountId)

    override suspend fun getArtworkCount(): Long =
        libraryLocalStore.getArtworkCount(currentAccountId)

    override fun getAllGenres(): Flow<List<Genre>> =
        libraryLocalStore.getAllGenres(currentAccountId)

    override fun observeGenreByName(name: String): Flow<Genre?> =
        libraryLocalStore.observeGenreByName(currentAccountId, name)

    override fun getGenreSongs(genreName: String): Flow<List<Song>> =
        libraryLocalStore.getGenreSongs(currentAccountId, genreName)

    override fun getGenreAlbums(genreName: String): Flow<List<Album>> =
        libraryLocalStore.getGenreAlbums(currentAccountId, genreName)

    override fun getGenreArtists(genreName: String): Flow<List<Artist>> =
        libraryLocalStore.getGenreArtists(currentAccountId, genreName)

    override fun getCachedGenreNames(): Flow<Set<String>> =
        libraryLocalStore.getCachedGenreNames(currentAccountId)

    override fun getFullyCachedAlbumIds(): Flow<Set<String>> =
        libraryLocalStore.getFullyCachedAlbumIds(currentAccountId)

    override fun getFullyCachedArtistIds(): Flow<Set<String>> =
        libraryLocalStore.getFullyCachedArtistIds(currentAccountId)

    override fun getFullyCachedGenreNames(): Flow<Set<String>> =
        libraryLocalStore.getFullyCachedGenreNames(currentAccountId)

    override fun getAllRadios(): Flow<List<Radio>> =
        libraryLocalStore.getAllRadios(currentAccountId)

    override suspend fun getAlbumIdsWithoutSyncedSongs(): List<String> =
        libraryLocalStore.getAlbumIdsWithoutSyncedSongs(currentAccountId)

    override suspend fun markAlbumSongsSynced(albumId: String) {
        libraryLocalStore.markAlbumSongsSynced(currentAccountId, albumId)
    }

    // ==================== 收藏 / 评分（写） ====================
    // 口径与 Subsonic 侧一致：离线静默成功（iOS setFavorite/setRating 首行 guard isSyncAllowed，
    // AmpacheLibrarySyncer.swift:1246/1270 等）；服务器成功才写本地。

    override suspend fun toggleArtistFavorite(artistId: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        val aid = currentAccountId
        val newFavoriteState = libraryLocalStore.getArtistById(aid, artistId)?.starred == null
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Set artist favorite") {
            // iOS AmpacheLibrarySyncer.setFavorite(artist:isFavorite:)（:1298-1307）
            ampacheApi.requestSetFavoriteArtist(artistId, newFavoriteState)
            libraryLocalStore.setArtistStarred(
                aid, artistId,
                if (newFavoriteState) AMPACHE_FLAGGED_STARRED_AT else null,
            )
        }
    }

    override suspend fun toggleAlbumFavorite(albumId: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        val aid = currentAccountId
        val newFavoriteState = libraryLocalStore.getAlbumById(aid, albumId)?.starred == null
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Set album favorite") {
            // iOS AmpacheLibrarySyncer.setFavorite(album:isFavorite:)（:1287-1296）
            ampacheApi.requestSetFavoriteAlbum(albumId, newFavoriteState)
            libraryLocalStore.setAlbumStarred(
                aid, albumId,
                if (newFavoriteState) AMPACHE_FLAGGED_STARRED_AT else null,
            )
        }
    }

    override suspend fun toggleSongFavorite(songId: String): Result<Unit> {
        // 以本地库现状取反（歌曲不在本地库时视为未收藏），与 Subsonic 侧同
        val currentlyFavorite = libraryLocalStore.getSongById(currentAccountId, songId)?.starred != null
        return setSongFavorite(songId, !currentlyFavorite)
    }

    override suspend fun setSongFavorite(songId: String, isFavorite: Boolean): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Set song favorite") {
            // iOS AmpacheLibrarySyncer.setFavorite(song:isFavorite:)（:1270-1285）
            ampacheApi.requestSetFavoriteSong(songId, isFavorite)
            libraryLocalStore.setSongStarred(
                currentAccountId, songId,
                if (isFavorite) AMPACHE_FLAGGED_STARRED_AT else null,
            )
        }
    }

    override suspend fun updateArtistRating(artistId: String, rating: Int): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        val validRating = rating.coerceIn(0, 5)
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Set artist rating") {
            // iOS AmpacheLibrarySyncer.setRating(artist:rating:)（:1262-1268）
            ampacheApi.requestRateArtist(artistId, validRating)
            libraryLocalStore.setArtistRating(currentAccountId, artistId, validRating)
        }
    }

    override suspend fun updateAlbumRating(albumId: String, rating: Int): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        val validRating = rating.coerceIn(0, 5)
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Set album rating") {
            // iOS AmpacheLibrarySyncer.setRating(album:rating:)（:1254-1260）
            ampacheApi.requestRateAlbum(albumId, validRating)
            libraryLocalStore.setAlbumRating(currentAccountId, albumId, validRating)
        }
    }

    override suspend fun updateSongRating(songId: String, rating: Int): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        val validRating = rating.coerceIn(0, 5)
        currentCredentials() ?: return Result.failure(Exception("No credentials"))
        return runAmpache("Set song rating") {
            // iOS AmpacheLibrarySyncer.setRating(song:rating:)（:1246-1252）
            ampacheApi.requestRateSong(songId, validRating)
            libraryLocalStore.setSongRating(currentAccountId, songId, validRating)
        }
    }

    // ==================== Scrobble（写，Batch 2 G 组新增缝） ====================

    /**
     * Ampache **没有 nowPlaying 概念**——直接成功返回、不发任何请求。
     * 对应 iOS AmpacheLibrarySyncer.syncNowPlaying（:1224-1232：注释
     * "Ampache has no equivalend to Subsonic's NowPlaying" 后空实现）。
     */
    override suspend fun reportNowPlaying(songId: String): Result<Unit> = Result.success(Unit)

    /**
     * 听毕上报 = `record_play`（iOS AmpacheLibrarySyncer.scrobble(song:date:)，:1234-1244）。
     * [playedAtMillis] 供离线补传历史播放（iOS 传播放发生时刻）。
     */
    override suspend fun scrobble(songId: String, playedAtMillis: Long): Result<Unit> {
        if (!isSyncAllowed) return Result.failure(IllegalStateException("No network connection"))
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Scrobble") {
            ampacheApi.requestRecordPlay(songId, playedAtMillis)
            Unit
        }
    }

    // ==================== 库同步（读） ====================

    /**
     * 全量艺术家同步（分页）——对应 iOS syncInitial 的 artist 段
     * （AmpacheLibrarySyncer.swift:81-119）：握手响应给总数 → `ceil(总数/500)` 页并发拉取。
     *
     * 两处与 iOS 的刻意差异：
     * 1. iOS 写的是 `for index in Array(0 ... pollCount)`（**闭区间**，多请求一页空数据），
     *    Android 用半开区间恰好 pollCount 页；
     * 2. iOS 用无上限 TaskGroup，Android 限 [MAX_CONCURRENT_POLLS] 路（同 Subsonic 侧专辑分页，
     *    避免弱网/弱服务器被打满）。
     * 每页取回即 upsert（Room 复合主键幂等），中途失败保留已写入页。
     */
    override suspend fun syncArtists(): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync artists") {
            val aid = currentAccountId
            val handshake = authSession.reauthenticate()
            pollPages(handshake.artistCount) { startIndex ->
                val page = ampacheApi.requestArtists(startIndex = startIndex)
                if (page.items.isNotEmpty()) {
                    libraryLocalStore.upsertArtists(aid, page.items.map { it.toArtist() })
                }
            }
        }
    }

    /** 全量专辑同步（分页）——对应 iOS syncInitial 的 album 段（:120-160），口径同 [syncArtists] */
    override suspend fun syncAlbums(): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync albums") {
            val aid = currentAccountId
            val handshake = authSession.reauthenticate()
            pollPages(handshake.albumCount) { startIndex ->
                val page = ampacheApi.requestAlbums(startIndex = startIndex)
                if (page.items.isNotEmpty()) {
                    libraryLocalStore.upsertAlbums(aid, page.items.map { it.toAlbum() })
                }
            }
        }
    }

    /**
     * 按握手响应给的实体总数分页并发拉取：页数 = `ceil(total/500)`（**至少 1 页**——
     * 总数为 0 时也要发一次，服务器未回传 count 的情况下不能认定库为空）。
     * 某页返回不足 500 条属正常（服务器侧过滤/并发写入），不做额外探测。
     */
    private suspend fun pollPages(totalCount: Int, pollPage: suspend (startIndex: Int) -> Unit) =
        coroutineScope {
            val pageSize = AmpacheApi.MAX_ITEM_COUNT_TO_POLL_AT_ONCE
            val pageCount = maxOf(
                1,
                kotlin.math.ceil(totalCount.toDouble() / pageSize).toInt(),
            )
            val gate = Semaphore(MAX_CONCURRENT_POLLS)
            (0 until pageCount).map { page ->
                async { gate.withPermit { pollPage(page * pageSize) } }
            }.awaitAll()
            Unit
        }

    override suspend fun syncGenres(): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync genres") {
            // iOS syncInitial 的 genre 段（AmpacheLibrarySyncer.swift:51-78）
            val page = ampacheApi.requestGenres()
            libraryLocalStore.replaceGenres(currentAccountId, page.items.map { it.toGenre() })
        }
    }

    override suspend fun syncGenreDetails(genreName: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        return runAmpache("Sync genre details") {
            // 对应 iOS sync(genre:)（:217-228）：对流派已知专辑逐个 sync(album:) 扇出
            // ——Ampache 同样没有 by-genre 的歌曲端点
            val albumIds = libraryLocalStore.getGenreAlbumIdsOnce(currentAccountId, genreName)
            coroutineScope {
                albumIds.map { albumId -> async { syncAlbumDetails(albumId) } }.forEach { it.await() }
            }
        }
    }

    /**
     * 艺术家详情 = `artist` + `artist_albums`（对应 iOS sync(artist:)，:231-359）。
     *
     * **不拉 artist_songs**（本批约定，与 Android Subsonic 侧同范围）：艺术家页的歌曲
     * 由各专辑歌曲同步汇入；iOS 那次 artist_songs 请求在 Android 是纯冗余流量。
     * 空 items = 服务器已删该艺术家（iOS 走 throwForNotFoundErrors 分支）→ failure。
     */
    override suspend fun syncArtistDetails(artistId: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync artist details") {
            syncArtistDetailsInternal(artistId)
        }
    }

    private suspend fun syncArtistDetailsInternal(artistId: String) {
        val aid = currentAccountId
        val info = ampacheApi.requestArtistInfo(artistId)
        val artist = info.items.firstOrNull()
            ?: throw NoSuchElementException("Ampache: artist $artistId not found")
        libraryLocalStore.upsertArtists(aid, listOf(artist.toArtist()))

        val albums = ampacheApi.requestArtistAlbums(artistId)
        libraryLocalStore.upsertArtistAlbums(aid, artistId, albums.items.map { it.toAlbum() })
    }

    /**
     * 专辑详情 = `album` + `album_songs`（对应 iOS sync(album:)，:362-452）。
     * upsertAlbumSongs 末尾标记 isSongsSynced（Store 语义），与 Subsonic 侧一致。
     */
    override suspend fun syncAlbumDetails(albumId: String): Result<Unit> {
        // 注：BackgroundLibrarySyncer 调用前已自行判连通性；CancellationException 由
        // runAmpache 透传（否则 stop() 时会被误标 isSongsSynced 而永久跳过该专辑）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync album details") {
            syncAlbumDetailsInternal(albumId)
        }
    }

    private suspend fun syncAlbumDetailsInternal(albumId: String) {
        val aid = currentAccountId
        val info = ampacheApi.requestAlbumInfo(albumId)
        val album = info.items.firstOrNull()
            ?: throw NoSuchElementException("Ampache: album $albumId not found")
        libraryLocalStore.upsertAlbums(aid, listOf(album.toAlbum()))

        val songs = ampacheApi.requestAlbumSongs(albumId)
        libraryLocalStore.upsertAlbumSongs(aid, albumId, songs.items.map { it.toSong(aid) })
    }

    /**
     * 收藏三类（对应 iOS syncFavoriteLibraryElements，:860-942）：
     * advanced_search(favorite) 各拉一次 → 一次事务应用快照 + 反向清理。
     *
     * `starred` 兜底到占位值：advanced_search 的结果按定义就是收藏项，但服务器未必回
     * `<flag>`；若映射出 null，applyFavoriteSnapshot 会把它们写成「未收藏」
     * （Subsonic 侧同坑同解，见 LibraryRepositoryImpl.syncFavoriteElements 注释）。
     */
    override suspend fun syncFavoriteElements(): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync favorite elements") {
            val aid = currentAccountId
            val artists = ampacheApi.requestFavoriteArtists().items.map { dto ->
                dto.toArtist().let { it.copy(starred = it.starred ?: AMPACHE_FLAGGED_STARRED_AT) }
            }
            val albums = ampacheApi.requestFavoriteAlbums().items.map { dto ->
                dto.toAlbum().let { it.copy(starred = it.starred ?: AMPACHE_FLAGGED_STARRED_AT) }
            }
            val songs = ampacheApi.requestFavoriteSongs().items.map { dto ->
                dto.toSong(aid).let { it.copy(starred = it.starred ?: AMPACHE_FLAGGED_STARRED_AT) }
            }
            libraryLocalStore.applyFavoriteSnapshot(aid, artists, albums, songs)
        }
    }

    /** stats(type=album, filter=newest)（iOS syncNewestAlbums，:790-822） */
    override suspend fun syncNewestAlbums(count: Int, offset: Int): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync album list") {
            val page = ampacheApi.requestNewestAlbums(offset = offset, count = count)
            libraryLocalStore.applyAlbumListPage(
                currentAccountId, AlbumListKind.NEWEST, offset, page.items.map { it.toAlbum() },
            )
        }
    }

    /** stats(type=album, filter=recent)（iOS syncRecentAlbums，:825-857） */
    override suspend fun syncRecentAlbums(count: Int, offset: Int): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync album list") {
            val page = ampacheApi.requestRecentAlbums(offset = offset, count = count)
            libraryLocalStore.applyAlbumListPage(
                currentAccountId, AlbumListKind.RECENT, offset, page.items.map { it.toAlbum() },
            )
        }
    }

    /**
     * 最新专辑 + 新增专辑的歌曲扇出（对应 iOS AutoDownloadLibrarySyncer.syncNewestLibraryElements，
     * 与后端无关，结构与 Subsonic 侧逐字一致：差集判定与「首次填充返回空」守卫同源）。
     */
    override suspend fun syncNewestLibraryElements(count: Int): Result<List<Song>> {
        if (!isSyncAllowed) return Result.success(emptyList())
        return runAmpache("Sync newest library elements") {
            val aid = currentAccountId
            val oldNewestAlbumIds = libraryLocalStore.getNewestAlbumIds(aid)

            syncNewestAlbums(count = count).getOrThrow()

            val updatedNewestAlbumIds = libraryLocalStore.getNewestAlbumIds(aid)
            val newAlbumIds = updatedNewestAlbumIds - oldNewestAlbumIds

            // 最新列表中歌曲未同步的专辑逐个补齐（单个失败不中断其余）
            libraryLocalStore.getNewestAlbumIdsWithoutSyncedSongs(aid).forEach { albumId ->
                syncAlbumDetails(albumId)
            }

            // 首次填充（旧集合为空）不算「新增」，避免把 20 张专辑的歌全下下来
            if (oldNewestAlbumIds.isNotEmpty() && newAlbumIds.isNotEmpty()) {
                newAlbumIds.flatMap { albumId -> libraryLocalStore.getAlbumSongsOnce(aid, albumId) }
            } else {
                emptyList()
            }
        }
    }

    override suspend fun syncRadios(): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync radios") {
            // iOS syncRadios（:945-976）：live_streams 全量 + 差集清理（Store 侧 replace 语义）
            val page = ampacheApi.requestRadios()
            libraryLocalStore.replaceRadios(currentAccountId, page.items.map { it.toRadio() })
        }
    }

    /**
     * Ampache **不提供歌词**：iOS `AmpacheLibrarySyncer.parseLyrics` 直接
     * `throw BackendError.notSupported`（:1387-1389），播放器侧显示 "No Lyrics"。
     */
    override suspend fun getLyrics(songId: String): LyricsList? = null

    private companion object {
        /** 分页并发上限（iOS 用无上限 TaskGroup，这里限流，同 Subsonic 侧专辑分页） */
        const val MAX_CONCURRENT_POLLS = 4
    }
}
