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

package com.amperfy.data.local.db.mapper

import com.amperfy.data.local.db.entity.AlbumEntity
import com.amperfy.data.local.db.entity.ArtistEntity
import com.amperfy.data.local.db.entity.DirectoryEntity
import com.amperfy.data.local.db.entity.GenreEntity
import com.amperfy.data.local.db.entity.MusicFolderEntity
import com.amperfy.data.local.db.entity.PlaylistEntity
import com.amperfy.data.local.db.entity.PodcastEntity
import com.amperfy.data.local.db.entity.PodcastEpisodeEntity
import com.amperfy.data.local.db.entity.RadioEntity
import com.amperfy.data.local.db.entity.SongEntity
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

/**
 * 领域模型 → 远端快照实体行。
 *
 * 派生键 search_key/section_key/sort_key 一律经 [LibraryTextKeyNormalizer] 与源 title/name
 * 原子生成（Album 另生成 artist_sort_key），保证「显示值与搜索/索引键」不脱节。
 *
 * **本地状态字段不出现在这些实体**（isDownloaded/downloadPath/playProgress/playCount 归
 * song_local_state；isCached/isSongsSynced/newest/recentIndex 归 album_sync_state）——远端
 * upsert 物理上写不到本地状态，故映射器无需也无法保留它们。
 */

/** Artist → artist 行。 */
fun Artist.toEntity(accountId: String): ArtistEntity = ArtistEntity(
    accountId = accountId,
    serverId = id,
    name = name,
    coverArt = coverArt,
    starredAt = starred,
    rating = rating,
    searchKey = LibraryTextKeyNormalizer.searchKey(name),
    sectionKey = LibraryTextKeyNormalizer.sectionKey(name),
    sortKey = LibraryTextKeyNormalizer.sortKey(name),
)

/**
 * Album → album 行。
 *
 * @param artistIdOverride syncArtistDetails 场景固定关联到父艺术家；
 *   null 时用领域模型自身 artistId。
 * remote_play_count 取域模型 playCount（album 播放统计仅表示 Subsonic 远端统计，强制 remote_ 前缀）。
 */
fun Album.toEntity(accountId: String, artistIdOverride: String? = null): AlbumEntity = AlbumEntity(
    accountId = accountId,
    serverId = id,
    name = name,
    artistId = artistIdOverride ?: artistId,
    artistName = artist,
    genre = genre,
    year = year,
    songCount = songCount,
    duration = duration,
    coverArt = coverArt,
    createdAt = created,
    remotePlayCount = playCount,
    starredAt = starred,
    rating = rating,
    searchKey = LibraryTextKeyNormalizer.searchKey(name),
    sectionKey = LibraryTextKeyNormalizer.sectionKey(name),
    sortKey = LibraryTextKeyNormalizer.sortKey(name),
    artistSortKey = LibraryTextKeyNormalizer.sortKey(artist),
)

/**
 * Song → song 行。
 *
 * @param albumIdOverride upsertAlbumSongs 场景固定关联到父专辑；
 *   null 时用领域模型自身 albumId。
 * disc 对应域模型 discNumber、bitrate 对应 bitRate、created_at 对应 created（服务端 created / 按添加日期）。
 */
fun Song.toEntity(accountId: String, albumIdOverride: String? = null): SongEntity = SongEntity(
    accountId = accountId,
    serverId = id,
    title = title,
    albumId = albumIdOverride ?: albumId,
    albumName = album,
    artistId = artistId,
    artistName = artist,
    track = track,
    disc = discNumber,
    year = year,
    genre = genre,
    duration = duration,
    bitrate = bitRate,
    contentType = contentType,
    size = size,
    coverArt = coverArt,
    suffix = suffix,
    path = path,
    isVideo = isVideo,
    type = type,
    albumArtistId = albumArtistId,
    streamUrl = streamUrl,
    createdAt = created,
    starredAt = starred,
    rating = rating,
    replayGainTrackGain = replayGainTrackGain,
    replayGainTrackPeak = replayGainTrackPeak,
    replayGainAlbumGain = replayGainAlbumGain,
    replayGainAlbumPeak = replayGainAlbumPeak,
    searchKey = LibraryTextKeyNormalizer.searchKey(title),
    sectionKey = LibraryTextKeyNormalizer.sectionKey(title),
    sortKey = LibraryTextKeyNormalizer.sortKey(title),
)

/**
 * Playlist → playlist 行。
 *
 * search_key 经 [LibraryTextKeyNormalizer] 与 name 原子生成（播放列表无字母索引故无
 * section_key/sort_key）；created→created_at、changed→changed_at。
 * **lastPlayed 不写入本实体**——最近播放时间是纯本地状态，归 playlist_local_state，远端整对象
 * 同步物理上写不到。
 */
fun Playlist.toEntity(accountId: String): PlaylistEntity = PlaylistEntity(
    accountId = accountId,
    serverId = id,
    name = name,
    comment = comment,
    owner = owner,
    isPublic = isPublic,
    songCount = songCount,
    duration = duration,
    coverArt = coverArt,
    createdAt = created,
    changedAt = changed,
    searchKey = LibraryTextKeyNormalizer.searchKey(name),
)

/** Genre → genre 行（name 为身份键，原样存储不规范化；album/song 计数为服务器快照）。 */
fun Genre.toEntity(accountId: String): GenreEntity = GenreEntity(
    accountId = accountId,
    name = name,
    albumCount = albumCount,
    songCount = songCount,
    searchKey = LibraryTextKeyNormalizer.searchKey(name),
    sectionKey = LibraryTextKeyNormalizer.sectionKey(name),
    sortKey = LibraryTextKeyNormalizer.sortKey(name),
)

/**
 * Radio → radio 行（RadiosScreen 有字母索引，三派生键经 [LibraryTextKeyNormalizer] 与 title 生成）。
 *
 * 字段对照：title→name、streamUrl→stream_url、siteUrl→home_page_url。
 * cover_art 置 null——Radio 领域模型无该字段，实体列预留待后续接线。
 */
fun Radio.toEntity(accountId: String): RadioEntity = RadioEntity(
    accountId = accountId,
    serverId = id,
    name = title,
    streamUrl = streamUrl,
    homePageUrl = siteUrl,
    coverArt = null,
    searchKey = LibraryTextKeyNormalizer.searchKey(title),
    sectionKey = LibraryTextKeyNormalizer.sectionKey(title),
    sortKey = LibraryTextKeyNormalizer.sortKey(title),
)

/**
 * Podcast → podcast 行（PodcastsScreen 有搜索、无字母索引，只生成 search_key/sort_key，无 section_key）。
 *
 * 字段对照：title→title、depiction→description、coverArt→cover_art、episodeCount→episode_count。
 * remote_status 固定 0（存活）——两步 upsert 中新建行由此路径写入；软删除由 markRemoteDeletedMissing
 * 单独标 1，不经本映射。
 * 注：保留 episodeCount 语义由 DAO 两步 upsert（insertIgnore + updateChannelMetadata）保障，
 * updateChannelMetadata 不触碰 episode_count；本行的 episode_count 仅对 insertIgnore 的**新行**生效。
 */
fun Podcast.toEntity(accountId: String): PodcastEntity = PodcastEntity(
    accountId = accountId,
    serverId = id,
    title = title,
    description = depiction,
    coverArt = coverArt,
    remoteStatus = 0,
    episodeCount = episodeCount,
    searchKey = LibraryTextKeyNormalizer.searchKey(title),
    sortKey = LibraryTextKeyNormalizer.sortKey(title),
)

/**
 * PodcastEpisode → podcast_episode 行。
 *
 * 字段对照：title、depiction→description、publishDate→publish_date、status.raw→status、
 * streamId→stream_id、duration、coverArt→cover_art。
 *
 * @param podcastIdOverride 该单集的父频道 server_id，**由 Store 层判频道是否在本地后传入**：
 *   podcast_episode 对 podcast(account_id, podcast_id→server_id) 有强 FK CASCADE，
 *   频道不在本地时**必须传 null**（否则 FK 拒插）；此时该单集仍靠 account_scope FK 参与账户级联清理。
 *   parentPodcast 为空时按 podcastId 查频道、查不到关联置 null，
 *   podcastId 空串与 null 等义均不建关联（Store 负责把空串归一为 null 后传入）。
 */
fun PodcastEpisode.toEntity(accountId: String, podcastIdOverride: String?): PodcastEpisodeEntity =
    PodcastEpisodeEntity(
        accountId = accountId,
        serverId = id,
        title = title,
        description = depiction,
        publishDate = publishDate,
        status = status.raw,
        streamId = streamId,
        duration = duration,
        coverArt = coverArt,
        podcastId = podcastIdOverride,
    )

/**
 * MusicFolder → music_folder 行（只写 serverId/name）。
 * 该表无派生键（列表按 server_id 排序、无搜索/字母索引），故不经 [LibraryTextKeyNormalizer]。
 */
fun MusicFolder.toEntity(accountId: String): MusicFolderEntity = MusicFolderEntity(
    accountId = accountId,
    serverId = id,
    name = name,
)

/**
 * Directory → directory 行。sort_key 经 [LibraryTextKeyNormalizer] 与 name 原子生成。
 *
 * 两个挂载列由**调用路径**决定，各只填自己那一列（默认 null）：
 * - 顶层目录同步（getIndexes）传 musicFolderId，parentId 留 null；
 * - 子目录同步（getMusicDirectory）传 parentId，musicFolderId 留 null。
 *
 * 本行只用于 [com.amperfy.data.local.db.dao.DirectoryDao.insertIgnoreAll] 建**新行**——
 * 已存在行的更新走定向 UPDATE（updateTopLevelMetadata / updateChildMetadata），否则整行覆盖
 * 会把另一路径写入的挂载列清成 null（见 DirectoryDao 类 KDoc）。
 */
fun Directory.toEntity(
    accountId: String,
    parentId: String? = null,
    musicFolderId: String? = null,
): DirectoryEntity = DirectoryEntity(
    accountId = accountId,
    serverId = id,
    name = name,
    coverArt = coverArt,
    parentId = parentId,
    musicFolderId = musicFolderId,
    sortKey = LibraryTextKeyNormalizer.sortKey(name),
)
