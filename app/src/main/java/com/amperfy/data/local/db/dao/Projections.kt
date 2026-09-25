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

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.amperfy.data.local.db.entity.AlbumEntity
import com.amperfy.data.local.db.entity.ArtistEntity
import com.amperfy.data.local.db.entity.PlaylistEntity
import com.amperfy.data.local.db.entity.PodcastEpisodeEntity
import com.amperfy.data.local.db.entity.SongEntity

/**
 * DAO 投影模型。
 *
 * 领域模型的本地派生字段（歌曲缓存/播放次数、专辑同步序号/缓存态、艺术家相关计数）不落在远端
 * 快照实体表，而是由查询 JOIN/EXISTS/聚合算出。凡返回歌曲/专辑/艺术家的 DAO 查询一律返回对应
 * 投影（而非裸实体），LocalStore 再映射为领域模型（见 mapper/LibraryDomainMappers）。
 *
 * 投影只在 data/local/db 内流转，不进入领域/UI 层（硬约束）。
 */

/**
 * 歌曲 + 本地状态。song LEFT JOIN song_local_state（account_id + song_id 双列匹配），
 * 本地状态缺行时 cache_path 为 null、play_count 由 SQL `COALESCE(..,0)` 兜底为 0。
 * 领域 Song 的 isDownloaded/downloadPath/playCount 全部来自本投影，不读远端快照。
 */
data class SongWithLocal(
    @Embedded val song: SongEntity,
    /** 缓存文件相对路径，null = 未缓存（唯一缓存判据）。 */
    @ColumnInfo(name = "cache_path") val cachePath: String?,
    /** 纯本地播放次数（COALESCE 兜底 0）。 */
    @ColumnInfo(name = "play_count") val playCount: Int,
)

/**
 * 专辑 + 同步/缓存态。album LEFT JOIN album_sync_state（COALESCE 序号兜底 0）+
 * 计算列 is_cached（EXISTS 属于该专辑且 cache_path 非空的歌曲，即缓存合同的读侧形态）。
 */
data class AlbumWithState(
    @Embedded val album: AlbumEntity,
    @ColumnInfo(name = "newest_index") val newestIndex: Int,
    @ColumnInfo(name = "recent_index") val recentIndex: Int,
    /** 是否含已缓存歌曲（唯一缓存判据；EXISTS 计算列，非 album 陈旧标志）。 */
    @ColumnInfo(name = "is_cached") val isCached: Boolean,
)

/**
 * 艺术家 + 相关计数。albumCount/songCount/duration 不再是陈旧存储字段，
 * 由集合式 CTE 聚合算出（口径见 ArtistDao.COUNTS_CTE），LEFT JOIN artist 后兜底 0。
 */
data class ArtistWithCounts(
    @Embedded val artist: ArtistEntity,
    @ColumnInfo(name = "related_album_count") val relatedAlbumCount: Int,
    @ColumnInfo(name = "related_song_count") val relatedSongCount: Int,
    @ColumnInfo(name = "related_duration") val relatedDuration: Int,
)

/**
 * 播放列表 + 本地状态。playlist LEFT JOIN playlist_local_state
 * （account_id + playlist_id 双列匹配），本地状态缺行时 last_played 为 null。
 * 领域 Playlist 的 lastPlayed 来自本投影列，不落在远端快照 playlist 表（服务器同步物理上
 * 写不到 last_played）。
 *
 * 列名冲突检查：PlaylistEntity 无 last_played 列，@Embedded 与投影列无同名冲突。
 */
data class PlaylistWithState(
    @Embedded val playlist: PlaylistEntity,
    /** 本地最近播放时间戳（毫秒），null = 从未播放。 */
    @ColumnInfo(name = "last_played") val lastPlayed: Long?,
)

/**
 * 单集 + 父频道名（P3 批次 3b）。podcast_episode JOIN podcast 取 `title AS podcast_title`
 * ——父频道名不落在单集实体（podcast 是独立表），领域 PodcastEpisode.podcastTitle 须由本投影列带出
 * （缺失时兜底 "Unknown Podcast"）。
 *
 * 列名冲突检查：PodcastEpisodeEntity 的标题列名为 "title"，投影列别名 "podcast_title" 无冲突。
 * podcast_title 可空：LEFT JOIN（详情页）无匹配频道时为 null，映射层兜底 "Unknown Podcast"；
 * INNER JOIN（Episodes 模式 / Home）实际非空，可空类型无妨。
 *
 * Batch 4 加 [cachePath]：podcast_episode LEFT JOIN podcast_episode_local_state
 * （account_id + episode_id 双列匹配），本地状态缺行时为 null。领域 PodcastEpisode 的
 * isDownloaded/cachePath 全部来自本列，不读远端快照（与 [SongWithLocal] 同口径）。
 */
data class EpisodeWithPodcastTitle(
    @Embedded val episode: PodcastEpisodeEntity,
    @ColumnInfo(name = "podcast_title") val podcastTitle: String?,
    /** 缓存文件相对路径，null = 未缓存（唯一缓存判据）。 */
    @ColumnInfo(name = "cache_path") val cachePath: String? = null,
)

/**
 * 下载记录的类型化身份（Batch 4）。download_entry 的 song_id 在 SONG / PODCAST_EPISODE
 * 两个类型下分属不同 id 命名空间，故启动恢复 / Retry failed 的查询一律带出 entity_type，
 * 由 Store 映射为领域 [com.amperfy.data.model.DownloadRef]。
 */
data class DownloadEntryRefRow(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "song_id") val songId: String,
)
