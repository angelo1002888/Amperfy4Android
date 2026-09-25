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

import com.amperfy.data.local.db.dao.AlbumWithState
import com.amperfy.data.local.db.dao.ArtistWithCounts
import com.amperfy.data.local.db.dao.EpisodeWithPodcastTitle
import com.amperfy.data.local.db.dao.PlaylistWithState
import com.amperfy.data.local.db.dao.SongWithLocal
import com.amperfy.data.local.db.entity.DirectoryEntity
import com.amperfy.data.local.db.entity.GenreEntity
import com.amperfy.data.local.db.entity.MusicFolderEntity
import com.amperfy.data.local.db.entity.PodcastEntity
import com.amperfy.data.local.db.entity.PodcastEpisodeEntity
import com.amperfy.data.local.db.entity.RadioEntity
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

/**
 * 投影 → 领域模型。
 *
 * 远端字段逐字段直映；本地派生字段
 * 改从投影列取（isDownloaded/downloadPath/playCount、newest/recentIndex/isCached、相关计数）；
 * addedAt 另行处理（Room v1 无本地插入时刻列，见下方各处说明）。
 */

/**
 * SongWithLocal → Song。
 *
 * isDownloaded/downloadPath/playCount 来自本地状态列；其余远端字段逐字段直映。
 * addedAt：Room 无本地插入时刻列（不创建 first_seen_at），
 * 取服务端 created_at 作确定性代理（Song.addedAt 无消费方）。
 */
fun SongWithLocal.toSong(): Song = Song(
    id = song.serverId,
    title = song.title,
    artist = song.artistName,
    artistId = song.artistId,
    album = song.albumName,
    albumId = song.albumId,
    duration = song.duration,
    track = song.track,
    year = song.year,
    genre = song.genre,
    coverArt = song.coverArt,
    size = song.size,
    contentType = song.contentType,
    suffix = song.suffix,
    path = song.path,
    isVideo = song.isVideo,
    playCount = playCount,
    discNumber = song.disc,
    created = song.createdAt,
    starred = song.starredAt,
    albumArtistId = song.albumArtistId,
    type = song.type,
    bitRate = song.bitrate,
    streamUrl = song.streamUrl,
    isDownloaded = cachePath != null,
    downloadPath = cachePath,
    addedAt = song.createdAt ?: 0L,
    rating = song.rating,
    accountId = song.accountId,
    replayGainTrackGain = song.replayGainTrackGain,
    replayGainTrackPeak = song.replayGainTrackPeak,
    replayGainAlbumGain = song.replayGainAlbumGain,
    replayGainAlbumPeak = song.replayGainAlbumPeak,
)

/**
 * AlbumWithState → Album。
 *
 * newestIndex/recentIndex/isCached 来自投影列；playCount = remote_play_count（远端统计）；
 * 其余逐字段直映。
 */
fun AlbumWithState.toAlbum(): Album = Album(
    id = album.serverId,
    name = album.name,
    artist = album.artistName,
    artistId = album.artistId,
    coverArt = album.coverArt,
    songCount = album.songCount,
    duration = album.duration,
    year = album.year,
    genre = album.genre,
    created = album.createdAt,
    starred = album.starredAt,
    playCount = album.remotePlayCount,
    rating = album.rating,
    newestIndex = newestIndex,
    recentIndex = recentIndex,
    isCached = isCached,
)

/**
 * ArtistWithCounts → Artist。
 *
 * albumCount/songCount/duration 来自聚合投影列（不再是陈旧存储字段）；
 * addedAt：Room artist 无本地插入时刻列，固定为 0L —— 影响 ArtistsViewModel 的
 * NEWEST 排序（退化为 DAO 顺序），为已知偏差。
 */
fun ArtistWithCounts.toArtist(): Artist = Artist(
    id = artist.serverId,
    name = artist.name,
    coverArt = artist.coverArt,
    albumCount = relatedAlbumCount,
    songCount = relatedSongCount,
    duration = relatedDuration,
    starred = artist.starredAt,
    rating = artist.rating,
    addedAt = 0L,
)

/**
 * PlaylistWithState → Playlist。
 *
 * lastPlayed 来自本地状态投影列（playlist_local_state LEFT JOIN），其余远端字段逐字段直映
 * （id/name/comment/owner/isPublic/songCount/duration/coverArt/created/changed）。
 */
fun PlaylistWithState.toPlaylist(): Playlist = Playlist(
    id = playlist.serverId,
    name = playlist.name,
    comment = playlist.comment,
    owner = playlist.owner,
    isPublic = playlist.isPublic,
    songCount = playlist.songCount,
    duration = playlist.duration,
    coverArt = playlist.coverArt,
    created = playlist.createdAt,
    changed = playlist.changedAt,
    lastPlayed = lastPlayed,
)

/** GenreEntity → Genre（albumCount/songCount 为服务器快照）。 */
fun GenreEntity.toGenre(): Genre = Genre(
    name = name,
    albumCount = albumCount,
    songCount = songCount,
)

/**
 * RadioEntity → Radio。
 *
 * 字段对照：serverId→id、name→title、stream_url→streamUrl、home_page_url→siteUrl。
 * cover_art 不进领域模型——Radio 无该字段。
 */
fun RadioEntity.toRadio(): Radio = Radio(
    id = serverId,
    title = name,
    streamUrl = streamUrl,
    siteUrl = homePageUrl,
)

/**
 * PodcastEntity → Podcast。
 *
 * 字段对照：serverId→id、title→title、description→depiction、cover_art→coverArt、
 * episode_count→episodeCount。
 */
fun PodcastEntity.toPodcast(): Podcast = Podcast(
    id = serverId,
    title = title,
    depiction = description,
    coverArt = coverArt,
    episodeCount = episodeCount,
)

/**
 * PodcastEpisodeEntity → PodcastEpisode。
 *
 * status Int → 枚举经 [PodcastEpisodeRemoteStatus.fromRaw] 反查；
 * podcast_id 为 NULL 时归一为空串（对齐领域模型 podcastId 默认空串约定）。
 *
 * @param podcastTitle 父频道名——**不在单集实体上**（podcast 是独立表），须由调用方（3b Store）经
 *   JOIN/上下文解析后传入；默认值为 "Unknown Podcast"，作无法解析时的兜底。
 */
fun PodcastEpisodeEntity.toPodcastEpisode(podcastTitle: String = "Unknown Podcast"): PodcastEpisode =
    PodcastEpisode(
        id = serverId,
        title = title,
        depiction = description,
        publishDate = publishDate,
        status = PodcastEpisodeRemoteStatus.fromRaw(status),
        streamId = streamId,
        duration = duration,
        coverArt = coverArt,
        podcastId = podcastId ?: "",
        podcastTitle = podcastTitle,
    )

/**
 * EpisodeWithPodcastTitle → PodcastEpisode（P3 批次 3b）。父频道名经 JOIN 列 podcast_title 带入
 * [PodcastEpisodeEntity.toPodcastEpisode] 的 podcastTitle 参数；podcast_title 为 null（LEFT JOIN
 * 无匹配频道）时兜底 "Unknown Podcast"。
 *
 * Batch 4：缓存态由 JOIN 列 cache_path 派生（isDownloaded = cachePath != null），
 * 与歌曲侧 [SongWithLocal] 同口径——远端快照表内无任何缓存字段。
 */
fun EpisodeWithPodcastTitle.toPodcastEpisode(): PodcastEpisode =
    episode.toPodcastEpisode(podcastTitle = podcastTitle ?: "Unknown Podcast").copy(
        isDownloaded = cachePath != null,
        cachePath = cachePath,
    )

/**
 * MusicFolderEntity → MusicFolder。
 * 领域模型只有 id/name，逐字段等值。
 */
fun MusicFolderEntity.toMusicFolder(): MusicFolder = MusicFolder(
    id = serverId,
    name = name,
)

/**
 * DirectoryEntity → Directory（id/name/coverArt）。
 * parent_id/music_folder_id 是层级挂载列，不进领域模型。
 */
fun DirectoryEntity.toDirectory(): Directory = Directory(
    id = serverId,
    name = name,
    coverArt = coverArt,
)
