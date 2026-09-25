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
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Genre
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Radio
import com.amperfy.data.model.Song
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home 首页专用数据仓库（W3，iOS: HomeManager + 各 FetchedResultsController）
 *
 * 归属：本阶段为 @Singleton，构造注入 LibraryLocalStore + CredentialsManager，
 * 内部 `aid` 取当前凭证的 AccountInfo.ident（未登录时空串）。W5 落地多账户后迁入
 * AccountComponents（每账户一实例，构造绑定 accountInfo）。
 *
 * 查询已收口 LibraryLocalStore（实现见 data/local/db/store/RoomLibraryLocalStore），
 * 本类只做账户解析与委托——按当前账户 ident 转发每个查询给 Store（专题 15 P1 批次 3）。
 */
@Singleton
class HomeRepository @Inject constructor(
    private val libraryLocalStore: LibraryLocalStore,
    private val credentialsManager: CredentialsManager
) {

    /** 当前账户 ident（写入/查询隔离键）；W5 改为构造时绑定 accountInfo */
    private val aid: String
        get() = credentialsManager.getCredentials()
            ?.let { AccountInfo.create(it.serverUrl, it.username).ident }
            ?: ""

    // ==================== Flow 类（Room DAO Flow 表级失效自动发射，映射领域模型） ====================

    /**
     * 最近播放专辑（iOS: recentlyPlayedAlbums）：recentIndex > 0（1 起，0=不在列表），
     * 按 recentIndex 升序保留服务器返回顺序，最多 20。
     */
    fun recentAlbums(): Flow<List<Album>> = libraryLocalStore.recentAlbums(aid)

    /** 最新专辑（iOS: newestAlbums）：newestIndex > 0，按 newestIndex 升序，最多 20。 */
    fun newestAlbums(): Flow<List<Album>> = libraryLocalStore.newestAlbums(aid)

    /**
     * 最近播放的播放列表（iOS: PlaylistMO.lastPlayedDateFetchRequest）：不过滤未播放，
     * 显示账户全部播放列表，按 lastPlayed 倒序（null 排最后，再按名称升序），最多 20。
     * lastPlayed 为本地维护字段（Subsonic 不返回，跨设备不同步）。
     */
    fun recentPlaylists(): Flow<List<Playlist>> = libraryLocalStore.recentPlaylists(aid)

    /**
     * 最新播客单集（iOS: newestPodcastEpisodes）：按发布日期倒序，最多 20。
     * 与 MusicRepository.getAllPodcastEpisodes 一致，仅取所属播客未软删除（remoteStatus==0）的单集。
     */
    fun newestPodcastEpisodes(): Flow<List<PodcastEpisode>> = libraryLocalStore.newestPodcastEpisodes(aid)

    /** 播客列表（iOS: podcasts）：未软删除，按标题升序，最多 20。 */
    fun podcasts(): Flow<List<Podcast>> = libraryLocalStore.podcasts(aid)

    /** 电台列表（iOS: radios）：按标题升序，最多 20。 */
    fun radios(): Flow<List<Radio>> = libraryLocalStore.radios(aid)

    // ==================== 随机类（进入页面/点击刷新时重抽） ====================

    /**
     * 随机专辑：SQL 侧 `ORDER BY RANDOM() LIMIT count` 直接抽样（AlbumDao.randomAlbums），
     * 不做全量物化拷贝。onlyCached=true 语义 = 专辑名下有已下载歌曲（song 表子查询推导，
     * 与 getCachedAlbumIds 口径一致）——专辑自身无可靠缓存标记列，必须经歌曲推导。
     */
    suspend fun randomAlbums(count: Int = 20, onlyCached: Boolean): List<Album> =
        libraryLocalStore.randomAlbums(aid, count, onlyCached)

    /**
     * 随机艺术家。onlyCached 语义 = 名下有已缓存歌曲（song 表子查询过滤；
     * 无直接缓存字段故用子查询）。
     */
    suspend fun randomArtists(count: Int = 20, onlyCached: Boolean): List<Artist> =
        libraryLocalStore.randomArtists(aid, count, onlyCached)

    /** 随机歌曲。onlyCached=true 时仅取已下载（isDownloaded）歌曲。 */
    suspend fun randomSongs(count: Int = 20, onlyCached: Boolean): List<Song> =
        libraryLocalStore.randomSongs(aid, count, onlyCached)

    /** 随机流派（无 onlyCached：流派本身不可缓存）。 */
    suspend fun randomGenres(count: Int = 20): List<Genre> =
        libraryLocalStore.randomGenres(aid, count)
}
