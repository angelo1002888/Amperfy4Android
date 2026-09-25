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
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.Directory
import com.amperfy.data.model.MusicFolder
import com.amperfy.data.model.Song
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.repository.DirectoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 目录浏览域的 Ampache 实现（对应 iOS `AmpacheLibrarySyncer` :585-788）。
 *
 * **Ampache 没有目录浏览端点**——三层目录是用库实体拼出来的（iOS 同样如此）：
 * - 一级 = `catalogs`（音乐目录）；
 * - 二级 = `advanced_search(rule=catalog)` 取该 catalog 下的艺术家，目录 id 约定 `artist-<id>`；
 * - 三级 = 该艺术家的专辑（目录 id 约定 `album-<id>`），再下一层是该专辑的歌曲。
 *
 * 目录 id 的两个前缀是 iOS 的既定约定（:679-684 按前缀分派），Android 原样沿用
 * ——DirectoryDetailScreen 的下钻/返回逻辑与后端无关。
 *
 * 出处：Ampache API 移植 Batch 2。
 */
internal class AmpacheDirectoryRepositoryImpl(
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
    DirectoryRepository {

    // ==================== 本地读 ====================

    override fun getMusicFolders(): Flow<List<MusicFolder>> =
        libraryLocalStore.getMusicFolders(currentAccountId)

    override fun observeMusicFolderById(folderId: String): Flow<MusicFolder?> =
        libraryLocalStore.observeMusicFolderById(currentAccountId, folderId)

    override fun getMusicFolderDirectories(folderId: String): Flow<List<Directory>> =
        libraryLocalStore.getMusicFolderDirectories(currentAccountId, folderId)

    override fun observeDirectoryById(directoryId: String): Flow<Directory?> =
        libraryLocalStore.observeDirectoryById(currentAccountId, directoryId)

    override fun getSubdirectories(directoryId: String): Flow<List<Directory>> =
        libraryLocalStore.getSubdirectories(currentAccountId, directoryId)

    override fun getDirectorySongs(directoryId: String): Flow<List<Song>> =
        libraryLocalStore.getDirectorySongs(currentAccountId, directoryId)

    // ==================== 同步 ====================

    /** catalogs → 音乐文件夹（iOS syncMusicFolders，:585-610；Store 侧差集删除 + 级联清顶层目录） */
    override suspend fun syncMusicFolders(): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync music folders") {
            val page = ampacheApi.requestCatalogs()
            libraryLocalStore.replaceMusicFolders(
                currentAccountId, page.items.map { it.toMusicFolder() },
            )
        }
    }

    /**
     * 文件夹索引（iOS syncIndexes(musicFolder:)，:613-673）：
     * advanced_search 取该 catalog 下的艺术家 → 既 upsert 进艺术家表（iOS 同样解析成 Artist 实体），
     * 又以 `artist-<id>` 为 id 落成该文件夹的顶层目录（Store 侧差集删除消失项）。
     */
    override suspend fun syncIndexes(folderId: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync indexes") {
            val aid = currentAccountId
            val artists = ampacheApi.requestArtistWithinCatalog(folderId).items
            libraryLocalStore.upsertArtists(aid, artists.map { it.toArtist() })
            val directories = artists.map { artist ->
                Directory(
                    id = "$ARTIST_DIRECTORY_PREFIX${artist.id}",
                    name = artist.name,
                    coverArt = ampacheCoverArtKey(artist.artworkUrl),
                )
            }
            libraryLocalStore.replaceTopDirectories(aid, folderId, directories)
        }
    }

    /**
     * 目录内容（iOS sync(directory:)，:676-687 按前缀分派）：
     * - `artist-<id>`：同步该艺术家详情 → 子目录 = 其专辑（`album-<id>`），无歌曲；
     * - `album-<id>`：同步该专辑详情 → 无子目录，歌曲 = 专辑曲目；
     * - 其它前缀：无操作（iOS 的 else 分支同样什么都不做）。
     */
    override suspend fun syncDirectory(directoryId: String): Result<Unit> {
        if (!isSyncAllowed) return Result.success(Unit)
        currentCredentials() ?: return Result.failure(IllegalStateException("Not logged in"))
        return runAmpache("Sync directory") {
            when {
                directoryId.startsWith(ARTIST_DIRECTORY_PREFIX) ->
                    syncArtistDirectory(directoryId, directoryId.removePrefix(ARTIST_DIRECTORY_PREFIX))

                directoryId.startsWith(ALBUM_DIRECTORY_PREFIX) ->
                    syncAlbumDirectory(directoryId, directoryId.removePrefix(ALBUM_DIRECTORY_PREFIX))

                else -> Unit
            }
        }
    }

    /**
     * 艺术家目录（iOS sync(directory:thatIsArtistId:)，:729-787）。
     *
     * 同步范围与 [AmpacheLibraryRepositoryImpl.syncArtistDetails] 一致（artist + artist_albums），
     * 但两个域实现刻意**互不依赖**（各自持 store 与 api），故这里自带一份等价流程。
     */
    private suspend fun syncArtistDirectory(directoryId: String, artistId: String) {
        val aid = currentAccountId
        val artist = ampacheApi.requestArtistInfo(artistId).items.firstOrNull()
            ?: throw NoSuchElementException("Ampache: artist $artistId not found")
        libraryLocalStore.upsertArtists(aid, listOf(artist.toArtist()))
        val albums = ampacheApi.requestArtistAlbums(artistId).items
        libraryLocalStore.upsertArtistAlbums(aid, artistId, albums.map { it.toAlbum() })

        // 子目录取本地库（而非刚拿到的响应）：与 iOS 一致——iOS 用
        // getAlbums(whichContainsSongsWithArtist:) 反查本地，覆盖「歌曲挂该艺术家但专辑
        // 主艺术家是别人」的合辑情形
        val localAlbums = libraryLocalStore.getAlbumsByArtist(aid, artistId).first()
        val subdirectories = localAlbums.map { album ->
            Directory(
                id = "$ALBUM_DIRECTORY_PREFIX${album.id}",
                name = album.name,
                coverArt = album.coverArt,
            )
        }
        libraryLocalStore.replaceDirectoryChildren(aid, directoryId, subdirectories, emptyList())
    }

    /** 专辑目录（iOS sync(directory:thatIsAlbumId:)，:689-727） */
    private suspend fun syncAlbumDirectory(directoryId: String, albumId: String) {
        val aid = currentAccountId
        val album = ampacheApi.requestAlbumInfo(albumId).items.firstOrNull()
            ?: throw NoSuchElementException("Ampache: album $albumId not found")
        libraryLocalStore.upsertAlbums(aid, listOf(album.toAlbum()))
        val songs = ampacheApi.requestAlbumSongs(albumId).items.map { it.toSong(aid) }
        libraryLocalStore.upsertAlbumSongs(aid, albumId, songs)
        libraryLocalStore.replaceDirectoryChildren(aid, directoryId, emptyList(), songs)
    }

    private companion object {
        /** 目录 id 前缀约定（iOS AmpacheLibrarySyncer.swift:641/755） */
        const val ARTIST_DIRECTORY_PREFIX = "artist-"
        const val ALBUM_DIRECTORY_PREFIX = "album-"
    }
}
