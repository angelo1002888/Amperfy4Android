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

package com.amperfy.data.local.store

import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Directory
import com.amperfy.data.model.Genre
import com.amperfy.data.model.MusicFolder
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Radio
import com.amperfy.data.model.Song
import kotlinx.coroutines.flow.Flow

/** getAlbumList2 列表类别，决定序号写入 newestIndex 还是 recentIndex。 */
enum class AlbumListKind { NEWEST, RECENT }

/**
 * 库读路径的持久化边界（专题 15 P1 批次 3）
 *
 * 批次 3 首批入驻 Home 首页（HomeRepository）所需 10 方法（recent/newest/random 系列）；
 * 批次 4 承接 MusicRepositoryImpl 全部公开读路径（Song/Album/Artist/搜索/缓存推导/计数/
 * Genre/Radio/Podcast/Directory）。全部方法带 accountId 隔离键，只出入领域模型/标量/Flow，
 * 不出现任何数据库类型。
 *
 * P3 批次 1b-4b 起为 Room 全量实现
 * （data/local/db/store/RoomLibraryLocalStore）。
 */
interface LibraryLocalStore {

    /** 最近播放专辑（recentIndex > 0，按 recentIndex 升序，最多 20） */
    fun recentAlbums(accountId: String): Flow<List<Album>>

    /** 最新专辑（newestIndex > 0，按 newestIndex 升序，最多 20） */
    fun newestAlbums(accountId: String): Flow<List<Album>>

    /** 最近播放播放列表（按 lastPlayed 倒序、name 升序，最多 20） */
    fun recentPlaylists(accountId: String): Flow<List<Playlist>>

    /** 最新播客单集（所属播客未软删除，按发布日期倒序，最多 20） */
    fun newestPodcastEpisodes(accountId: String): Flow<List<PodcastEpisode>>

    /** 播客列表（未软删除，按标题升序，最多 20） */
    fun podcasts(accountId: String): Flow<List<Podcast>>

    /** 电台列表（按标题升序，最多 20） */
    fun radios(accountId: String): Flow<List<Radio>>

    /** 随机专辑（onlyCached=true 时仅取名下有已下载歌曲的专辑，SUBQUERY 推导） */
    suspend fun randomAlbums(accountId: String, count: Int, onlyCached: Boolean): List<Album>

    /** 随机艺术家（onlyCached=true 时仅取名下有已缓存歌曲的艺术家，SUBQUERY 推导） */
    suspend fun randomArtists(accountId: String, count: Int, onlyCached: Boolean): List<Artist>

    /** 随机歌曲（onlyCached=true 时仅取已下载歌曲） */
    suspend fun randomSongs(accountId: String, count: Int, onlyCached: Boolean): List<Song>

    /** 随机流派（无 onlyCached：流派本身不可缓存） */
    suspend fun randomGenres(accountId: String, count: Int): List<Genre>

    // ==================== Song/Album/Artist（批次 4） ====================

    /** 全部歌曲（accountId 隔离，数据变化自动发射） */
    fun getAllSongs(accountId: String): Flow<List<Song>>

    /** 全部专辑 */
    fun getAllAlbums(accountId: String): Flow<List<Album>>

    /** 全部艺术家 */
    fun getAllArtists(accountId: String): Flow<List<Artist>>

    /** 收藏歌曲（starred != null） */
    fun getFavoriteSongs(accountId: String): Flow<List<Song>>

    /** 收藏专辑 */
    fun getFavoriteAlbums(accountId: String): Flow<List<Album>>

    /** 收藏艺术家 */
    fun getFavoriteArtists(accountId: String): Flow<List<Artist>>

    /** Album Artists（名下有直接关联专辑的艺术家，@links 谓词） */
    fun getAlbumArtists(accountId: String): Flow<List<Artist>>

    /** 观察单个艺术家（主键直查） */
    fun observeArtistById(accountId: String, artistId: String): Flow<Artist?>

    /** 观察单个专辑（主键直查） */
    fun observeAlbumById(accountId: String, albumId: String): Flow<Album?>

    /** 观察单首歌曲（主键直查） */
    fun observeSongById(accountId: String, songId: String): Flow<Song?>

    /** 艺术家的歌曲（直接关联 OR 经 album 关联的并集，去重） */
    fun getArtistSongs(accountId: String, artistId: String): Flow<List<Song>>

    /** 艺术家的专辑（album.artist 与含该 artist 歌曲的专辑两支合并去重） */
    fun getArtistAlbums(accountId: String, artistId: String): Flow<List<Album>>

    /** 单首歌曲（一次性查询） */
    suspend fun getSongById(accountId: String, songId: String): Song?

    /** 单个专辑（一次性查询） */
    suspend fun getAlbumById(accountId: String, albumId: String): Album?

    /** 单个艺术家（一次性查询） */
    suspend fun getArtistById(accountId: String, artistId: String): Artist?

    /** 专辑内歌曲 */
    fun getSongsByAlbum(accountId: String, albumId: String): Flow<List<Song>>

    /** 艺术家的歌曲（仅直接关联，无 album 支） */
    fun getSongsByArtist(accountId: String, artistId: String): Flow<List<Song>>

    /** 艺术家的专辑（仅 album.artist 直接关联） */
    fun getAlbumsByArtist(accountId: String, artistId: String): Flow<List<Album>>

    // ==================== 搜索（批次 4） ====================

    /** 本地搜索歌曲（title CONTAINS[c]） */
    fun searchSongs(accountId: String, query: String): Flow<List<Song>>

    /** 本地搜索专辑（name CONTAINS[c]） */
    fun searchAlbums(accountId: String, query: String): Flow<List<Album>>

    /** 本地搜索艺术家（name CONTAINS[c]） */
    fun searchArtists(accountId: String, query: String): Flow<List<Artist>>

    /** 本地搜索收藏艺术家（name CONTAINS[c] AND starred != null） */
    fun searchFavoriteArtists(accountId: String, query: String): Flow<List<Artist>>

    /** 本地搜索 Album Artists（name CONTAINS[c] AND @links 谓词） */
    fun searchAlbumArtists(accountId: String, query: String): Flow<List<Artist>>

    // ==================== 缓存推导 / 下载（批次 4） ====================

    /** 含缓存歌曲的专辑 id 集合（经已下载歌曲反查 album.serverId） */
    fun getCachedAlbumIds(accountId: String): Flow<Set<String>>

    /** 含缓存歌曲的艺术家 id 集合（经已下载歌曲反查 artist/album.artist serverId） */
    fun getCachedArtistIds(accountId: String): Flow<Set<String>>

    /** 已下载（缓存）歌曲列表 */
    fun getDownloadedSongs(accountId: String): Flow<List<Song>>

    // ==================== 计数（批次 4，实时） ====================

    fun observeArtistCount(accountId: String): Flow<Long>
    fun observeAlbumCount(accountId: String): Flow<Long>
    fun observeSongCount(accountId: String): Flow<Long>
    fun observePlaylistCount(accountId: String): Flow<Long>

    /** 播客计数（只计 remoteStatus=available） */
    fun observePodcastCount(accountId: String): Flow<Long>
    fun observeCachedSongCount(accountId: String): Flow<Long>

    /** 已同步歌曲元数据的专辑数（后台同步进度显示用） */
    fun observeSyncedAlbumCount(accountId: String): Flow<Long>

    /**
     * 封面总数（album/artist/podcast 三表 cover_art 去重计数，一次性）。
     * 供 Settings→Artwork 统计行；对应 iOS LibraryStorage.getArtworkCount(for:)。
     */
    suspend fun getArtworkCount(accountId: String): Long

    /**
     * 全部歌曲元数据未同步的专辑 id（BackgroundLibrarySyncer 渐进补齐用）。
     * suspend 一次性查询——走 DAO 挂起查询，不阻塞主线程。
     */
    suspend fun getAlbumIdsWithoutSyncedSongs(accountId: String): List<String>

    // ==================== 流派（批次 4，Phase 6.1） ====================

    /** 全部流派（按 name 排序） */
    fun getAllGenres(accountId: String): Flow<List<Genre>>

    /** 观察单个流派（name 为标识，主键直查） */
    fun observeGenreByName(accountId: String, name: String): Flow<Genre?>

    /** 流派下歌曲（本地 genre 字段匹配，按 title 排序） */
    fun getGenreSongs(accountId: String, genreName: String): Flow<List<Song>>

    /** 流派下专辑（按 name 排序） */
    fun getGenreAlbums(accountId: String, genreName: String): Flow<List<Album>>

    /** 流派下艺术家（经 artist 对象链接的反向查询） */
    fun getGenreArtists(accountId: String, genreName: String): Flow<List<Artist>>

    /** 含缓存歌曲的流派名集合（Cached 作用域过滤用） */
    fun getCachedGenreNames(accountId: String): Flow<Set<String>>

    /** 全部歌曲均已缓存的专辑 id 集合（对应 iOS isCachedCompletely，菜单隐藏 Download 用）。 */
    fun getFullyCachedAlbumIds(accountId: String): Flow<Set<String>>

    /** 全部歌曲均已缓存的艺术家 id 集合。 */
    fun getFullyCachedArtistIds(accountId: String): Flow<Set<String>>

    /** 全部歌曲均已缓存的流派名集合。 */
    fun getFullyCachedGenreNames(accountId: String): Flow<Set<String>>

    // ==================== 电台（批次 4，Phase 6.3） ====================

    /** 全部电台（按 title 排序）——与 Home 用 radios()（limit 20）不同，无上限 */
    fun getAllRadios(accountId: String): Flow<List<Radio>>

    // ==================== 播客（批次 4，Phase 6.4） ====================

    /** 全部播客（未软删除，按 title 排序） */
    fun getAllPodcasts(accountId: String): Flow<List<Podcast>>

    /** 观察单个播客（主键直查） */
    fun observePodcastById(accountId: String, podcastId: String): Flow<Podcast?>

    /** 某播客的单集（publishDate 降序） */
    fun getPodcastEpisodes(accountId: String, podcastId: String): Flow<List<PodcastEpisode>>

    /** 跨播客全部单集（所属播客未软删除，publishDate 降序） */
    fun getAllPodcastEpisodes(accountId: String): Flow<List<PodcastEpisode>>

    // ==================== 目录浏览（批次 4，Phase 6.5） ====================

    /** 全部音乐文件夹（按 serverId 排序） */
    fun getMusicFolders(accountId: String): Flow<List<MusicFolder>>

    /** 观察单个音乐文件夹（主键直查） */
    fun observeMusicFolderById(accountId: String, folderId: String): Flow<MusicFolder?>

    /** 音乐文件夹的顶层目录（按 name 排序） */
    fun getMusicFolderDirectories(accountId: String, folderId: String): Flow<List<Directory>>

    /** 观察单个目录（主键直查） */
    fun observeDirectoryById(accountId: String, directoryId: String): Flow<Directory?>

    /** 目录的子目录（按 name 排序） */
    fun getSubdirectories(accountId: String, directoryId: String): Flow<List<Directory>>

    /** 目录内歌曲（按 track 排序） */
    fun getDirectorySongs(accountId: String, directoryId: String): Flow<List<Song>>

    // ==================== 本地状态写（批次 5a） ====================
    // 语义：目标实体不存在时静默跳过（与迁移前 MusicRepositoryImpl 写块行为一致）。
    // 时间源留在调用方（Store 不调 System.currentTimeMillis()），值由 repo 传入。

    /** 置艺术家收藏时间戳（starredAt 为 null 表示取消收藏；实体不存在时跳过） */
    suspend fun setArtistStarred(accountId: String, artistId: String, starredAt: Long?)

    /** 置专辑收藏时间戳（starredAt 为 null 表示取消收藏；实体不存在时跳过） */
    suspend fun setAlbumStarred(accountId: String, albumId: String, starredAt: Long?)

    /** 置歌曲收藏时间戳（歌曲不在本地库时跳过，如在线搜索结果） */
    suspend fun setSongStarred(accountId: String, songId: String, starredAt: Long?)

    /** 置艺术家评分（rating 已由 repo coerceIn 到 0-5，Store 只落值；实体不存在时跳过） */
    suspend fun setArtistRating(accountId: String, artistId: String, rating: Int)

    /** 置专辑评分（rating 已由 repo coerceIn 到 0-5，Store 只落值；实体不存在时跳过） */
    suspend fun setAlbumRating(accountId: String, albumId: String, rating: Int)

    /** 置歌曲评分（rating 已由 repo coerceIn 到 0-5，Store 只落值；实体不存在时跳过） */
    suspend fun setSongRating(accountId: String, songId: String, rating: Int)

    /** 标记专辑歌曲元数据已同步（isSongsSynced = true；实体不存在时跳过） */
    suspend fun markAlbumSongsSynced(accountId: String, albumId: String)

    // ==================== 全量替换 sync 写（批次 5a） ====================
    // 差集语义整块平移自 MusicRepositoryImpl：差集删除服务器已不存在的实体（限本账户），
    // 再逐个 upsert（UpdatePolicy.ALL）。

    /**
     * 整表替换流派。差集规则：以 name 为键（对齐 iOS 以 name 去重），删除本账户下
     * 服务器已不存在的流派后逐个 upsert。
     */
    suspend fun replaceGenres(accountId: String, genres: List<Genre>)

    /**
     * 整表替换电台。差集规则：删除本账户下服务器已删除的电台（iOS 标记
     * remoteStatus=.deleted 后查询过滤，Android 直接删除，UI 语义一致）后逐个 upsert。
     */
    suspend fun replaceRadios(accountId: String, radios: List<Radio>)

    /**
     * 整表替换音乐文件夹。差集规则：删除本账户下服务器已不存在的文件夹，并级联删除
     * 该文件夹下的顶层目录（iOS deleteMusicFolder 级联）后逐个 upsert。
     */
    suspend fun replaceMusicFolders(accountId: String, folders: List<MusicFolder>)

    /**
     * 替换某文件夹的顶层目录。差集规则：在 musicFolderId 范围内删除本账户下服务器已不存在
     * 的目录后，existing-or-create 写 name/coverArt/musicFolderId。
     */
    suspend fun replaceTopDirectories(accountId: String, folderId: String, directories: List<Directory>)

    /**
     * 替换目录的子内容（子目录 + 歌曲，单事务）。差集规则：
     * - 子目录：删除本账户下服务器已不存在的子目录后 upsert（写 name/coverArt/parentId）；
     * - 歌曲：消失的仅解除 directoryId 关联（iOS removeFromSongs，不删除歌曲实体），
     *   存在的逐首 upsert 并保留本地所有权字段（下载态/下载路径/播放进度/播放计数）。
     */
    suspend fun replaceDirectoryChildren(
        accountId: String,
        directoryId: String,
        subdirectories: List<Directory>,
        songs: List<Song>
    )

    // ==================== library 核心 sync 写（批次 5b） ====================
    // 语义整块平移自 MusicRepositoryImpl 写块：查询串/保留字段语义/UpdatePolicy.ALL/
    // findLatest/事务边界不变；DTO→domain 映射、requireOk、日志、Result 包装留在 repo。

    /** upsert 艺术家（整对象覆盖写，保留本地维护字段；syncArtists/syncArtistDetails 共用） */
    suspend fun upsertArtists(accountId: String, artists: List<Artist>)

    /** upsert 专辑（逐个走 upsertAlbum 助手，保留本地维护字段；syncAlbums/syncAlbumDetails 共用） */
    suspend fun upsertAlbums(accountId: String, albums: List<Album>)

    /**
     * 收藏快照全量应用（单事务）。artist/album/song 三类逐个 upsert（保留本地统计/下载/
     * 所有权字段），再反向清理本账户下服务器已不在收藏列表中的三类实体（starred=null）。
     * 对应 iOS SubsonicLibrarySyncer.syncFavoriteLibraryElements 的差集处理（限本账户）。
     */
    suspend fun applyFavoriteSnapshot(
        accountId: String,
        artists: List<Artist>,
        albums: List<Album>,
        songs: List<Song>
    )

    /**
     * 应用一页 getAlbumList2 结果并写保序序号。offset==0 时先清对应 index 字段，
     * 再逐个 upsertAlbum 后写 newestIndex/recentIndex = offset + i + 1。
     */
    suspend fun applyAlbumListPage(
        accountId: String,
        kind: AlbumListKind,
        offset: Int,
        albums: List<Album>
    )

    /**
     * syncArtistDetails 专用：upsert 艺术家的专辑并固定关联到 artistId。
     * 与 upsertAlbums 不同，不保留本地维护字段（现行为原样平移）。
     */
    suspend fun upsertArtistAlbums(accountId: String, artistId: String, albums: List<Album>)

    /**
     * upsert 专辑的歌曲（单事务）。逐首保留本地下载状态/目录归属/播放进度/播放计数
     * + artist/album 关系挂接，末尾标记该专辑 isSongsSynced = true。
     */
    suspend fun upsertAlbumSongs(accountId: String, albumId: String, songs: List<Song>)

    // ==================== sync 支撑读（批次 5b，一次性） ====================

    /** 当前 newest 专辑 id 集合（newestIndex > 0，serverId 集合） */
    suspend fun getNewestAlbumIds(accountId: String): Set<String>

    /** newest 列表中歌曲未同步的专辑 id（newestIndex > 0 AND isSongsSynced == false） */
    suspend fun getNewestAlbumIdsWithoutSyncedSongs(accountId: String): List<String>

    /** 专辑内歌曲（一次性 find，非 Flow；供 syncNewestLibraryElements 收集新歌） */
    suspend fun getAlbumSongsOnce(accountId: String, albumId: String): List<Song>

    /** 流派下专辑 id（一次性 find；供 syncGenreDetails 扇出 syncAlbumDetails） */
    suspend fun getGenreAlbumIdsOnce(accountId: String, genreName: String): List<String>

    // ==================== podcast sync 写（批次 5c） ====================
    // 语义整块平移自 MusicRepositoryImpl 写块：差集/软删除语义/事务边界不变；
    // DTO→domain 映射、parsePodcastPublishDate 解析、requireOk、日志留在 repo。

    /**
     * 整表替换播客（单事务）：服务器已删项软删除（remoteStatus=1，iOS
     * syncDownPodcastsWithoutEpisodes；限本账户），再 existing-or-create upsert 写
     * title/depiction/coverArt/remoteStatus=0。不写 episodeCount（由单集同步重算）。
     */
    suspend fun replacePodcasts(accountId: String, podcasts: List<Podcast>)

    /**
     * 应用播客详情（单事务）。channelUpdate 非空时更新播客 title/depiction/coverArt
     * （channel 缺失或 status=error 时 repo 收敛为 channelUpdate=null）；episodes 做 diff——
     * 服务器已删单集标 status=DELETED（不物理删除；限本账户），逐个 upsert 单集并重算
     * episodeCount（channelUpdate=null 不影响 episodes 处理）。
     */
    suspend fun applyPodcastDetails(
        accountId: String,
        podcastId: String,
        channelUpdate: Podcast?,
        episodes: List<PodcastEpisode>
    )

    /**
     * upsert 一批跨播客最新单集（单事务，parentPodcast=null 按 podcastId 关联），并对涉及的
     * 非空 podcastId 逐个重算 episodeCount。
     */
    suspend fun upsertNewestPodcastEpisodes(accountId: String, episodes: List<PodcastEpisode>)
}
