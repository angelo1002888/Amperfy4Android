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

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.store.AlbumListKind
import com.amperfy.data.model.*
import com.amperfy.data.remote.SubsonicApi
import com.amperfy.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 资料库域实现：歌曲/专辑/艺术家/流派/电台的读写、收藏与评分、库统计、本地过滤搜索、
 * 歌词与 library 同步。方法体自 MusicRepositoryImpl 逐字搬移。
 *
 * 状态字段归属：歌词内存缓存（[lyricsCache]/[lyricsNotAvailable]）与 OpenSubsonic
 * 扩展支持缓存（[songLyricsExtensionSupport]）原为单体实例字段，现为本域实例字段——
 * 本实例随 MusicRepositoryImpl 构造一次性创建（每账户一套），生命周期与拆分前等价。
 *
 * 出处：Repository 拆分批次 2。
 */
internal class LibraryRepositoryImpl(
    subsonicApi: SubsonicApi,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    private val libraryLocalStore: com.amperfy.data.local.store.LibraryLocalStore,
    /**
     * App 私有文件根目录（生产为 `context.filesDir`）：歌词落盘缓存的账户分层目录基点。
     * 刻意传 File 而非 Context——本层不引入 Android 框架依赖。
     */
    private val filesDir: java.io.File,
    boundAccountInfo: AccountInfo?,
) : BaseSubsonicRepository(
    subsonicApi, credentialsManager, eventLogger, networkMonitor, boundAccountInfo
),
    LibraryRepository {

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

    /**
     * 收藏/评分上行方法的离线口径（本方法与下方 toggle*Favorite / update*Rating 同口径）：
     * 对应 iOS setFavorite/setRating 首行 `guard isSyncAllowed`（SubsonicLibrarySyncer.swift:
     * 1124-1180）——离线时静默返回成功，不发请求、不记 EventLogger。
     *
     * **与 iOS 的微差**：iOS 由调用方先改本地（LibraryEntity.isFavorite setter）再调 syncer，
     * 离线时本地先变、下次同步被服务器状态冲掉；Android 恪守「服务器成功才写本地」纪律，
     * 故离线时服务器与本地**都不写**（整体 no-op）。两端对用户都是静默无错误提示。
     */
    override suspend fun toggleArtistFavorite(artistId: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            val aid = currentAccountId
            // 先获取当前状态（主键直查）
            val currentlyFavorite = libraryLocalStore.getArtistById(aid, artistId)?.starred != null
            val newFavoriteState = !currentlyFavorite

            // 未登录早退（认证参数由 SubsonicAuthInterceptor 按账户注入，不再随调用传递）
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            // 同步到服务器 - 对应iOS: SubsonicLibrarySyncer.setFavorite(artist:isFavorite:)
            val serverResponse = if (newFavoriteState) {
                subsonicApi.starArtist(
                    artistId = artistId
                )
            } else {
                subsonicApi.unstarArtist(
                    artistId = artistId
                )
            }

            requireOk(serverResponse, "Set artist favorite")

            // 服务器同步成功后，更新本地数据库
            libraryLocalStore.setArtistStarred(
                aid, artistId,
                if (newFavoriteState) System.currentTimeMillis() else null
            )
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "Toggle artist favorite error", e)
            Result.failure(e)
        }
    }

    override suspend fun toggleAlbumFavorite(albumId: String): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:1165 guard isSyncAllowed（口径见 toggleArtistFavorite）
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            val aid = currentAccountId
            // 先获取当前状态（主键直查）
            val currentlyFavorite = libraryLocalStore.getAlbumById(aid, albumId)?.starred != null
            val newFavoriteState = !currentlyFavorite

            // 未登录早退（认证参数由 SubsonicAuthInterceptor 按账户注入，不再随调用传递）
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            // 同步到服务器 - 对应iOS: SubsonicLibrarySyncer.setFavorite(album:isFavorite:)
            val serverResponse = if (newFavoriteState) {
                subsonicApi.starAlbum(
                    albumId = albumId
                )
            } else {
                subsonicApi.unstarAlbum(
                    albumId = albumId
                )
            }

            requireOk(serverResponse, "Set album favorite")

            // 服务器同步成功后，更新本地数据库
            libraryLocalStore.setAlbumStarred(
                aid, albumId,
                if (newFavoriteState) System.currentTimeMillis() else null
            )
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "Toggle album favorite error", e)
            Result.failure(e)
        }
    }

    override suspend fun toggleSongFavorite(songId: String): Result<Unit> {
        // 以本地库现状取反（歌曲不在本地库时视为未收藏）
        val currentlyFavorite = libraryLocalStore.getSongById(currentAccountId, songId)?.starred != null
        return setSongFavorite(songId, !currentlyFavorite)
    }

    // toggleSongFavorite 经本方法早退，无需另设守卫
    override suspend fun setSongFavorite(songId: String, isFavorite: Boolean): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:1148 guard isSyncAllowed（口径见 toggleArtistFavorite）
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            // 未登录早退（认证参数由 SubsonicAuthInterceptor 按账户注入，不再随调用传递）
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            // 同步到服务器 - 对应iOS: SubsonicLibrarySyncer.setFavorite(song:isFavorite:)
            val serverResponse = if (isFavorite) {
                subsonicApi.starSong(
                    id = songId
                )
            } else {
                subsonicApi.unstarSong(
                    id = songId
                )
            }
            requireOk(serverResponse, "Set song favorite")

            // 服务器同步成功后，更新本地数据库（歌曲不在本地库时跳过，如在线搜索结果）
            libraryLocalStore.setSongStarred(
                currentAccountId, songId,
                if (isFavorite) System.currentTimeMillis() else null
            )
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "Set song favorite error", e)
            Result.failure(e)
        }
    }

    override suspend fun updateArtistRating(artistId: String, rating: Int): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:1140 guard isSyncAllowed（口径见 toggleArtistFavorite）
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            // 限制rating范围 0-5
            val validRating = rating.coerceIn(0, 5)

            // 未登录早退（认证参数由 SubsonicAuthInterceptor 按账户注入，不再随调用传递）
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            // 同步到服务器 - 对应iOS: SubsonicLibrarySyncer.setRating(artist:rating:)
            val serverResponse = subsonicApi.setRating(
                id = artistId,
                rating = validRating
            )

            requireOk(serverResponse, "Set artist rating")

            // 服务器同步成功后，更新本地数据库
            libraryLocalStore.setArtistRating(currentAccountId, artistId, validRating)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "Update artist rating error", e)
            Result.failure(e)
        }
    }

    override suspend fun updateAlbumRating(albumId: String, rating: Int): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:1132 guard isSyncAllowed（口径见 toggleArtistFavorite）
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            // 限制rating范围 0-5
            val validRating = rating.coerceIn(0, 5)

            // 未登录早退（认证参数由 SubsonicAuthInterceptor 按账户注入，不再随调用传递）
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            // 同步到服务器 - 对应iOS: SubsonicLibrarySyncer.setRating(album:rating:)
            val serverResponse = subsonicApi.setRating(
                id = albumId,
                rating = validRating
            )

            requireOk(serverResponse, "Set album rating")

            // 服务器同步成功后，更新本地数据库
            libraryLocalStore.setAlbumRating(currentAccountId, albumId, validRating)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "Update album rating error", e)
            Result.failure(e)
        }
    }

    override suspend fun updateSongRating(songId: String, rating: Int): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:1124 guard isSyncAllowed（口径见 toggleArtistFavorite）
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            // 限制rating范围 0-5
            val validRating = rating.coerceIn(0, 5)

            // 未登录早退（认证参数由 SubsonicAuthInterceptor 按账户注入，不再随调用传递）
            currentCredentials()
                ?: return Result.failure(Exception("No credentials"))

            // 同步到服务器 - 对应iOS: SubsonicLibrarySyncer.setRating(song:rating:)
            val serverResponse = subsonicApi.setRating(
                id = songId,
                rating = validRating
            )

            requireOk(serverResponse, "Set song rating")

            // 服务器同步成功后，更新本地数据库
            libraryLocalStore.setSongRating(currentAccountId, songId, validRating)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "Update song rating error", e)
            Result.failure(e)
        }
    }

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

    // ==================== 库统计 / 下载 ====================

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

    // ==================== Scrobble（Ampache 移植 Batch 2 的 API 无关缝） ====================
    // 方法体自 ScrobbleSyncer 搬入（原先 ScrobbleSyncer 直接持 SubsonicApi）：
    // 「成功」判定沿用原式 `isSuccessful && status == "ok"`（**刻意不走 requireOk**——
    // requireOk 会记 EventLogger，而 scrobble 失败是常态（离线队列自会重传），
    // 记事件日志属行为变更）。调用方按 Result.isSuccess 判定，与原布尔返回等价。

    override suspend fun reportNowPlaying(songId: String): Result<Unit> = try {
        val response = subsonicApi.requestScrobble(id = songId, submission = false)
        if (response.isSuccessful && response.body()?.subsonicResponse?.status == "ok") {
            Result.success(Unit)
        } else {
            Result.failure(Exception("nowPlaying failed: HTTP ${response.code()}"))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun scrobble(songId: String, playedAtMillis: Long): Result<Unit> = try {
        val response = subsonicApi.requestScrobble(
            id = songId, submission = true, time = playedAtMillis
        )
        if (response.isSuccessful && response.body()?.subsonicResponse?.status == "ok") {
            Result.success(Unit)
        } else {
            Result.failure(Exception("scrobble failed: HTTP ${response.code()}"))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun syncArtists(): Result<Unit> {
        // 离线静默跳过（iOS CommonLibrarySyncer.swift:37 isSyncAllowed；本方法对应
        // iOS syncInitial 的 artist 段，下拉刷新场景 iOS 同样受 isSyncAllowed 门控）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestArtists()
            // HTTP + Subsonic status 双重校验（status=failed 抛异常转 Result.failure，EventLogger 钩子在 requireOk 内）
            val artists = requireOk(response, "Sync artists").artists.index.flatMap { index ->
                index.artist.map { it.toArtist() }
            }

            libraryLocalStore.upsertArtists(currentAccountId, artists)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 全量专辑同步（分页）
     * 对应 iOS: SubsonicLibrarySyncer.syncInitial 的专辑段（SubsonicLibrarySyncer.swift:112-159）
     *
     * getAlbumList2 单次最多返回 [ALBUM_POLL_PAGE_SIZE] 条，只调一次的旧实现在专辑数 >500 的库
     * 拿不全。这里对齐 iOS：先由 getArtists 响应逐艺术家 albumCount 求和得总数，
     * 按 ceil(总数/500) 算页数并发拉取；总数取不到时退化为串行探测（拉到不满一页为止）。
     *
     * **不清表**：每页取回即 upsert（Room 复合主键幂等去重，故不需要 iOS 那种事后去重清理），
     * 中途失败保留已写入页——绝不因一次失败留下残缺库。
     */
    override suspend fun syncAlbums(): Result<Unit> {
        // 离线静默跳过（iOS CommonLibrarySyncer.swift:37 isSyncAllowed）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val totalAlbumCount = fetchTotalAlbumCount()
            if (totalAlbumCount > 0) {
                syncAlbumsByPageCount(totalAlbumCount)
            } else {
                // 总数不可用（getArtists 失败）或求和为 0（服务器不回传 albumCount）：
                // 不能据此认定库为空，改用串行探测逐页拉取
                syncAlbumsByProbing()
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "Exception in syncAlbums", e)
            Result.failure(e)
        }
    }

    /**
     * 专辑总数 = getArtists 响应中各艺术家 albumCount 之和
     * （对应 iOS `artists.reduce(0) { $0 + $1.remoteAlbumCount }`，SubsonicLibrarySyncer.swift:116）
     * 请求失败不视为整体失败（EventLogger 钩子已在 requireOk 内记录），返回 0 交由调用方退化探测。
     */
    private suspend fun fetchTotalAlbumCount(): Int = try {
        val response = subsonicApi.requestArtists()
        // HTTP + Subsonic status 双重校验（status=failed 抛异常，EventLogger 钩子在 requireOk 内）
        requireOk(response, "Sync albums (album count)").artists.index
            .sumOf { index -> index.artist.sumOf { it.albumCount } }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("MusicRepository", "syncAlbums: album count unavailable, falling back to probing", e)
        0
    }

    /**
     * 已知总数：页数 = ceil(total/500)（至少 1 页），[MAX_CONCURRENT_ALBUM_POLLS] 路并发拉取，
     * 每页取回立即 upsert。任一页失败即抛出（整体 Result.failure），已写入页保留。
     */
    private suspend fun syncAlbumsByPageCount(totalAlbumCount: Int) =
        coroutineScope {
            val pageCount = maxOf(
                1,
                kotlin.math.ceil(totalAlbumCount.toDouble() / ALBUM_POLL_PAGE_SIZE).toInt()
            )
            val gate = Semaphore(MAX_CONCURRENT_ALBUM_POLLS)
            (0 until pageCount).map { page ->
                async {
                    gate.withPermit {
                        val offset = page * ALBUM_POLL_PAGE_SIZE
                        val body = requireOk(
                            subsonicApi.requestAlbums(
                                count = ALBUM_POLL_PAGE_SIZE,
                                offset = offset
                            ),
                            "Sync albums"
                        )
                        val container = body.albumList2
                        if (container == null) {
                            // 首页缺容器视为服务器异常（保持旧实现语义）；
                            // 后续页缺容器只当空页——albumCount 高估时尾页可能无内容
                            if (offset == 0) throw Exception("albumList2 is null in response")
                            return@async
                        }
                        val albums = container.album.map { it.toAlbum() }
                        if (albums.isNotEmpty()) {
                            // Insert albums with artist relationships（保留本地维护字段，见 upsertAlbum）
                            libraryLocalStore.upsertAlbums(currentAccountId, albums)
                        }
                    }
                }
            }.awaitAll()
            Unit
        }

    /**
     * 总数不可用时的兜底：从 offset=0 起串行逐页拉取，某页返回条数不足一页即停。
     * 串行（非并发）是因为终止条件依赖上一页结果。
     */
    private suspend fun syncAlbumsByProbing() {
        var offset = 0
        while (true) {
            val body = requireOk(
                subsonicApi.requestAlbums(
                    count = ALBUM_POLL_PAGE_SIZE,
                    offset = offset
                ),
                "Sync albums"
            )
            val container = body.albumList2
            if (container == null) {
                // status=ok 但缺 albumList2 容器：首页视为失败（保持旧实现语义），后续页视为拉完
                if (offset == 0) throw Exception("albumList2 is null in response")
                break
            }
            val page = container.album
            if (page.isNotEmpty()) {
                libraryLocalStore.upsertAlbums(currentAccountId, page.map { it.toAlbum() })
            }
            if (page.size < ALBUM_POLL_PAGE_SIZE) break
            offset += ALBUM_POLL_PAGE_SIZE
        }
    }

    override suspend fun syncFavoriteElements(): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:707 guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestFavoriteElements()
            // 服务器全部取消收藏时 starred2 可能整体缺失，视为三类均为空
            val starred = requireOk(response, "Sync favorite elements").starred2

            val aid = currentAccountId
            // 艺术家/专辑/歌曲：getStarred2 的条目必为收藏，服务器未回传 starred 字段时以当前时间兜底。
            // 否则 applyFavoriteSnapshot 的 updateFavoriteSnapshot 无条件写 starred_at = null，
            // 会把本就在收藏快照里的条目写成「未收藏」（反向清理的 keepIds 含它，亦不会兜底）。
            // iOS 侧收藏态与日期分离故无此坑：SsArtistParserDelegate.swift:68 /
            // SsAlbumParserDelegate.swift:71 均为 `isFavorite = attributeDict["starred"] != nil`，
            // SsPlayableParserDelegate.swift:90-102 亦先置 isFavorite = true 再单独解析 starredDate。
            val artists = starred?.artist?.map { dto ->
                dto.toArtist().let { it.copy(starred = it.starred ?: System.currentTimeMillis()) }
            } ?: emptyList()
            val albums = starred?.album?.map { dto ->
                dto.toAlbum().let { it.copy(starred = it.starred ?: System.currentTimeMillis()) }
            } ?: emptyList()
            val songs = starred?.song?.map { dto ->
                dto.toSong(aid).let { it.copy(starred = it.starred ?: System.currentTimeMillis()) }
            } ?: emptyList()

            libraryLocalStore.applyFavoriteSnapshot(aid, artists, albums, songs)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncFavoriteElements error", e)
            Result.failure(e)
        }
    }

    override suspend fun syncNewestAlbums(count: Int, offset: Int): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:637 guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return syncAlbumList(
            kind = AlbumListKind.NEWEST,
            offset = offset,
            apiCall = { subsonicApi.requestNewestAlbums(count = count, offset = offset) }
        )
    }

    override suspend fun syncRecentAlbums(count: Int, offset: Int): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:672 guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return syncAlbumList(
            kind = AlbumListKind.RECENT,
            offset = offset,
            apiCall = { subsonicApi.requestRecentAlbums(count = count, offset = offset) }
        )
    }

    override suspend fun syncNewestLibraryElements(count: Int): Result<List<Song>> {
        // 离线静默跳过：返回空「本次新增歌曲」列表，调用方（BackgroundLibrarySyncer）
        // 的自动缓存分支自然不触发（iOS AutoDownloadLibrarySyncer 同受 isSyncAllowed 门控）
        if (!isSyncAllowed) return Result.success(emptyList())
        return try {
            val aid = currentAccountId
            // 同步前记录 newest 专辑集合（iOS oldNewestAlbums，AutoDownloadLibrarySyncer.swift:46-53）
            val oldNewestAlbumIds = libraryLocalStore.getNewestAlbumIds(aid)

            syncNewestAlbums(count = count).getOrThrow()

            val updatedNewestAlbumIds = libraryLocalStore.getNewestAlbumIds(aid)
            val newAlbumIds = updatedNewestAlbumIds - oldNewestAlbumIds

            // 对最新列表中歌曲未同步的专辑扇出同步（iOS fetchNeededNewestAlbums；
            // 单个专辑失败不中断其余，成功与否由 syncAlbumDetails 内部标记 isSongsSynced）
            val fetchNeeded = libraryLocalStore.getNewestAlbumIdsWithoutSyncedSongs(aid)
            fetchNeeded.forEach { albumId ->
                syncAlbumDetails(albumId)
            }

            // 新出现专辑的歌曲（供 Auto cache latest Songs 自动下载，
            // iOS AutoDownloadLibrarySyncer.swift:73-80）；旧集合为空说明是首次填充，
            // 不算「新增」（iOS !oldNewestAlbums.isEmpty 守卫）
            val newestSongs = if (oldNewestAlbumIds.isNotEmpty() && newAlbumIds.isNotEmpty()) {
                newAlbumIds.flatMap { albumId ->
                    libraryLocalStore.getAlbumSongsOnce(aid, albumId)
                }
            } else {
                emptyList()
            }
            Result.success(newestSongs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncNewestLibraryElements failed", e)
            Result.failure(e)
        }
    }

    /**
     * 通用：拉取 getAlbumList2 结果并 upsert 到本地，同时按服务器返回顺序写入保序序号。
     * 对应 iOS SubsonicLibrarySyncer 的 newestIndex/recentIndex 方案（服务器顺序即展示顺序）。
     * offset == 0（首页/刷新）时先清空既有序号，保证本地集合与服务器当前列表一致。
     */
    private suspend fun syncAlbumList(
        kind: AlbumListKind,
        offset: Int,
        apiCall: suspend () -> retrofit2.Response<SubsonicResponse<AlbumListResponse>>
    ): Result<Unit> {
        return try {
            val body = requireOk(apiCall(), "Sync album list")
            val albums = body.albumList2?.album?.map { it.toAlbum() } ?: emptyList()

            libraryLocalStore.applyAlbumListPage(currentAccountId, kind, offset, albums)
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncAlbumList error", e)
            Result.failure(e)
        }
    }

    override suspend fun syncArtistDetails(artistId: String): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:263 guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestArtist(id = artistId)
            run {
                // HTTP + Subsonic status 双重校验（status=failed 抛异常转 Result.failure，EventLogger 钩子在 requireOk 内）
                val artistDetail = requireOk(response, "Sync artist details").artist

                val aid = currentAccountId
                // Insert artist
                libraryLocalStore.upsertArtists(aid, listOf(artistDetail.toArtist()))

                artistDetail.album?.let { albums ->
                    // 直接转换专辑，保留原始 coverArt ID
                    val albumsWithIds = albums.map { albumDto ->
                        albumDto.toAlbum()
                    }

                    // Insert albums with artist relationships（固定关联到 artistId）
                    libraryLocalStore.upsertArtistAlbums(aid, artistId, albumsWithIds)
                }

                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun syncAlbumDetails(albumId: String): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:336 guard isSyncAllowed
        // 注：BackgroundLibrarySyncer 在调用前已自行判 isSyncAllowed()，离线时不会走到这里，
        // 故「静默成功被当作已同步并标记 isSongsSynced」的风险不存在（详见该类 :130）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestAlbum(id = albumId)
            run {
                // HTTP + Subsonic status 双重校验（status=failed 抛异常转 Result.failure，EventLogger 钩子在 requireOk 内）
                val albumDetail = requireOk(response, "Sync album details").album

                // 直接转换专辑，保留原始 coverArt ID
                val album = albumDetail.toAlbum()

                val aid = currentAccountId
                // 保存 Album（使用服务器返回的 rating/favorite 元数据，保留本地维护字段，见 upsertAlbum）
                libraryLocalStore.upsertAlbums(aid, listOf(album))

                // 保存songs并建立artist-song和album-song关联
                albumDetail.song?.let { songs ->
                    libraryLocalStore.upsertAlbumSongs(aid, albumId, songs.map { it.toSong(aid) })
                }
                Result.success(Unit)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消须向上传播——否则 BackgroundLibrarySyncer stop() 时会被当作
            // 普通失败标记 isSongsSynced，导致该专辑歌曲被永久跳过
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAlbumIdsWithoutSyncedSongs(): List<String> =
        libraryLocalStore.getAlbumIdsWithoutSyncedSongs(currentAccountId)

    override suspend fun markAlbumSongsSynced(albumId: String) {
        libraryLocalStore.markAlbumSongsSynced(currentAccountId, albumId)
    }

    // ==================== 流派（Phase 6.1） ====================

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

    override suspend fun syncGenres(): Result<Unit> {
        // 离线静默跳过（iOS CommonLibrarySyncer.swift:37 isSyncAllowed）
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestGenres()
            val dtos = requireOk(response, "Sync genres").genres?.genre ?: emptyList()
            val aid = currentAccountId
            val genres = dtos.map { Genre(it.value, it.albumCount, it.songCount) }
            libraryLocalStore.replaceGenres(aid, genres)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncGenres error", e)
            Result.failure(e)
        }
    }

    override suspend fun syncGenreDetails(genreName: String): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:250 guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        return try {
            // 对应 iOS sync(genre:)：对流派已知专辑逐个 sync(album:) 扇出（无 by-genre 端点）；
            // 专辑未同步过歌曲时 syncAlbumDetails 会拉取 getAlbum 建立歌曲的 genre 字段
            val albumIds = libraryLocalStore.getGenreAlbumIdsOnce(currentAccountId, genreName)
            coroutineScope {
                albumIds.map { albumId ->
                    async { syncAlbumDetails(albumId) }
                }.forEach { it.await() }
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncGenreDetails error", e)
            Result.failure(e)
        }
    }

    // ==================== 电台（Phase 6.3） ====================

    override fun getAllRadios(): Flow<List<Radio>> =
        libraryLocalStore.getAllRadios(currentAccountId)

    override suspend fun syncRadios(): Result<Unit> {
        // iOS SubsonicLibrarySyncer.swift:756 guard isSyncAllowed
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials()
            ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val response = subsonicApi.requestInternetRadioStations()
            val dtos = requireOk(response, "Sync radios")
                .internetRadioStations?.internetRadioStation ?: emptyList()
            val aid = currentAccountId
            // 空缺省值语义（name/streamUrl 缺省为空串）随映射前移，与原写块一致
            val radios = dtos.map { Radio(it.id, it.name ?: "", it.streamUrl ?: "", it.homePageUrl) }
            libraryLocalStore.replaceRadios(aid, radios)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MusicRepository", "syncRadios error", e)
            Result.failure(e)
        }
    }

    // ==================== 歌词（Phase 6.2；落盘缓存 Batch 5） ====================

    /** OpenSubsonic songLyrics 扩展支持缓存（会话级，对应 iOS openSubsonicExtensionsSupport） */
    @Volatile
    private var songLyricsExtensionSupport: Boolean? = null

    /** 歌词内存缓存（songId → LyricsList；一级缓存，进程级） */
    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, LyricsList>()

    /** 「本曲无歌词」会话级去重集合：只活在进程内，重启后重新向服务器验证（不落盘） */
    private val lyricsNotAvailable = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * 歌词三级缓存：内存 → 磁盘 → 网络（对应 iOS SubsonicLibrarySyncer.syncLyrics，
     * SubsonicLibrarySyncer.swift:435-514 + CacheFileManager.swift:709-725 的
     * `lyrics/songs/<id>.xml` 目录结构）。
     *
     * 拉取时机与 iOS 一致：均为播放器按需触发，并非同步期预取
     * （iOS sync(song:) 的调用方是 PopupPlayerVC.swift:211 与 LyricsVC.swift:74）。
     * 「无歌词」两端都不落盘——Android 仅用 [lyricsNotAvailable] 做会话级去重，
     * 服务器事后补上的歌词在下次进程启动后即可拿到（iOS 为每次进播放器重发，粒度略不同）。
     *
     * 与 iOS 的两处刻意差异：
     * 1. **存解析后的 domain JSON 而非原始 XML**：Android 走 Retrofit + Gson，
     *    响应体已被转换器消费，拿不到原始字节；读取时直接反序列化，省掉重复解析。
     * 2. **不落库文件路径列**：路径由 songId 确定性推导，文件存在即命中，与 iOS
     *    Song.lyricsRelFilePath 列行为等价；免 Room schema 变更，也不需要重扫回填。
     *
     * 磁盘层刻意排在扩展检测之前：扩展检测本身是一次网络请求，离线时先挂会让已落盘的
     * 歌词读不出来（本批修复的核心问题）。
     */
    override suspend fun getLyrics(songId: String): LyricsList? {
        // 一级：内存
        lyricsCache[songId]?.let { return it }
        if (songId in lyricsNotAvailable) return null

        // 二级：磁盘（未登录 / 空 songId 时 lyricsFile 为 null，直接跳过本层）
        val diskFile = lyricsFile(songId)
        if (diskFile != null) {
            // 存在性判断 / 读取 / 损坏删除全在同一个 IO 上下文内完成（调用方可能是主线程）
            val cached: LyricsList? = withContext(Dispatchers.IO) {
                if (!diskFile.isFile) {
                    null
                } else {
                    readLyricsFromDisk(diskFile)?.takeIf { it.lyrics.isNotEmpty() } ?: run {
                        // 文件损坏/结构不完整，或早期版本落下的空歌词文件（陈旧残留，今后不再产生）
                        // → 删掉后落到网络层重取
                        diskFile.delete()
                        null
                    }
                }
            }
            // cached 为 null = 未命中（或刚删掉损坏/空文件）→ 继续走网络
            if (cached != null) {
                lyricsCache[songId] = cached   // 命中 → 回填内存缓存
                return cached
            }
        }

        // 三级：网络 —— 离线静默跳过（iOS SubsonicLibrarySyncer.swift:436
        // `sync(song:) { guard isSyncAllowed }`，syncLyrics 由其调用）。
        // 只挡网络层：上面的内存/磁盘两级不受影响，已落盘歌词离线照常显示。
        if (!isSyncAllowed) return null
        currentCredentials() ?: return null
        return try {
            // 首次调用检测服务器 OpenSubsonic songLyrics 扩展（对应 iOS isOpenSubsonicExtensionSupported）
            val supported = songLyricsExtensionSupport ?: run {
                val extResp = subsonicApi.requestOpenSubsonicExtensions()
                val extensions = extResp.body()?.subsonicResponse?.openSubsonicExtensions
                    ?.map { it.name } ?: emptyList()
                ("songLyrics" in extensions).also { songLyricsExtensionSupport = it }
            }
            // 服务器整体不支持是服务器级结论（非本曲无歌词），不落盘
            if (!supported) return null

            val response = subsonicApi.requestLyricsBySongId(
                songId = songId
            )
            val dtos = requireOk(response, "Get lyrics").lyricsList?.structuredLyrics ?: emptyList()
            val lyricsList = LyricsList(lyrics = dtos.map { dto ->
                StructuredLyrics(
                    lang = dto.lang ?: "",
                    synced = dto.synced ?: false,
                    line = dto.line?.map { LyricsLine(start = it.start, value = it.value ?: "") }
                        ?: emptyList(),
                    displayArtist = dto.displayArtist,
                    displayTitle = dto.displayTitle,
                    offset = dto.offset ?: 0
                )
            })
            if (lyricsList.lyrics.isEmpty()) {
                // 无歌词不落盘（对齐 iOS）：只做会话级去重，服务器后补的歌词下次启动仍能拿到
                lyricsNotAvailable.add(songId)
                null
            } else {
                // 有歌词才写盘；写盘失败只记日志，不影响本次返回值
                if (diskFile != null) {
                    withContext(Dispatchers.IO) { writeLyricsToDisk(diskFile, lyricsList) }
                }
                lyricsCache[songId] = lyricsList
                lyricsList
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 网络/解析异常 ≠ 无歌词：不写盘、不进 lyricsNotAvailable，下次仍会重试
            android.util.Log.e("MusicRepository", "getLyrics error", e)
            null
        }
    }

    /**
     * 歌词缓存文件：`files/accounts/<serverHash>/<userHash>/lyrics/songs/<songId>.json`
     * （对应 iOS CacheFileManager 的 lyricsDir + songsDir 两级，扩展名因刻意差异 1 改为 json）。
     *
     * 登出清理零改动：[com.amperfy.core.AccountManager] 删整棵 `accounts/<sh>/<uh>/` 树即覆盖。
     * 文件名对 songId 做 URL 编码——普通 id（字母数字/`.`/`-`/`_`）原样保留，含 `/` 等分隔符的
     * 服务端 id 被编码，既防越目录写入又保证映射唯一（不会两首歌撞同一文件）。
     */
    private fun lyricsFile(songId: String): java.io.File? {
        if (songId.isEmpty()) return null
        val account = currentAccountInfo() ?: return null
        val fileName = java.net.URLEncoder.encode(songId, "UTF-8")
        return java.io.File(
            filesDir,
            "$ACCOUNTS_DIR/${account.serverHash}/${account.userHash}/" +
                "$LYRICS_DIR/$LYRICS_SONGS_DIR/$fileName.$LYRICS_FILE_EXTENSION"
        )
    }

    /**
     * 读磁盘歌词。返回 null = 文件损坏/结构不完整（调用方删文件重取）；
     * 返回空 [LyricsList] = 早期版本落下的空歌词文件，调用方同样删文件重取。
     */
    private fun readLyricsFromDisk(file: java.io.File): LyricsList? = try {
        val dto = LYRICS_GSON.fromJson(file.readText(), DiskLyricsList::class.java)
        // lyrics 字段缺失（Gson 塞 null）视为结构损坏
        dto?.lyrics?.let { list -> LyricsList(lyrics = list.mapNotNull { it?.toModel() }) }
    } catch (e: Exception) {
        android.util.Log.w("MusicRepository", "readLyricsFromDisk error: ${file.name}", e)
        null
    }

    /** 原子落盘：先写 `.tmp` 再 rename，避免写一半崩溃留下半截 JSON 被当成损坏文件反复重取。 */
    private fun writeLyricsToDisk(target: java.io.File, lyricsList: LyricsList) {
        try {
            val dir = target.parentFile ?: return
            if (!dir.exists() && !dir.mkdirs()) return
            val temp = java.io.File(dir, "${target.name}$LYRICS_TEMP_SUFFIX")
            temp.writeText(LYRICS_GSON.toJson(lyricsList.toDiskDto()))
            if (!temp.renameTo(target)) {
                // 少数文件系统 rename 不覆盖已存在目标：删旧再试一次，仍失败则清临时文件
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicRepository", "writeLyricsToDisk error: ${target.name}", e)
        }
    }

    // ---- 歌词磁盘 DTO（全字段可空 + toModel 回退，项目既定范式）----
    // Gson 反序列化 Kotlin data class 不走构造默认值（Unsafe 分配），直接 fromJson 到
    // domain 类会把非空字段填 null 并在后续解引用处 NPE；故磁盘格式单独定义可空 DTO
    // （同 AccountSettingsStore.AccountSettingDto:94-112）。列表元素也声明可空——
    // JSON 数组里的 null 元素同样会被原样注入。

    private data class DiskLyricsList(
        val lyrics: List<DiskStructuredLyrics?>? = null,
    )

    private data class DiskStructuredLyrics(
        val lang: String? = null,
        val synced: Boolean? = null,
        val line: List<DiskLyricsLine?>? = null,
        val displayArtist: String? = null,
        val displayTitle: String? = null,
        val offset: Int? = null,
    )

    private data class DiskLyricsLine(
        val start: Long? = null,
        val value: String? = null,
    )

    private fun LyricsList.toDiskDto() = DiskLyricsList(lyrics = lyrics.map { it.toDiskDto() })

    private fun StructuredLyrics.toDiskDto() = DiskStructuredLyrics(
        lang = lang,
        synced = synced,
        line = line.map { it.toDiskDto() },
        displayArtist = displayArtist,
        displayTitle = displayTitle,
        offset = offset,
    )

    private fun LyricsLine.toDiskDto() = DiskLyricsLine(start = start, value = value)

    private fun DiskStructuredLyrics.toModel() = StructuredLyrics(
        lang = lang ?: "",
        synced = synced ?: false,
        line = line?.mapNotNull { it?.toModel() } ?: emptyList(),
        displayArtist = displayArtist,
        displayTitle = displayTitle,
        offset = offset ?: 0,
    )

    private fun DiskLyricsLine.toModel() = LyricsLine(start = start, value = value ?: "")

    private companion object {
        /** getAlbumList2 单次拉取上限（对应 iOS SubsonicLibrarySyncer.maxItemCountToPollAtOnce = 500） */
        const val ALBUM_POLL_PAGE_SIZE = 500

        /** 专辑分页并发上限（iOS 用 TaskGroup 不限流，这里限流避免弱网/弱服务器被打满） */
        const val MAX_CONCURRENT_ALBUM_POLLS = 4

        // 歌词落盘目录常量（与 DownloadManager 的 accounts/<sh>/<uh>/ 分层同源）
        const val ACCOUNTS_DIR = "accounts"
        const val LYRICS_DIR = "lyrics"
        const val LYRICS_SONGS_DIR = "songs"
        const val LYRICS_FILE_EXTENSION = "json"
        const val LYRICS_TEMP_SUFFIX = ".tmp"

        /** 歌词磁盘序列化用 Gson（无自定义适配器，进程级复用一个实例即可） */
        val LYRICS_GSON = com.google.gson.Gson()
    }
}
