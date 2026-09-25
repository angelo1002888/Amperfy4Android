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

package com.amperfy.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.amperfy.data.local.db.dao.AccountScopeDao
import com.amperfy.data.local.db.dao.AlbumDao
import com.amperfy.data.local.db.dao.AlbumSyncStateDao
import com.amperfy.data.local.db.dao.ArtistDao
import com.amperfy.data.local.db.dao.DirectoryDao
import com.amperfy.data.local.db.dao.DownloadEntryDao
import com.amperfy.data.local.db.dao.EventLogDao
import com.amperfy.data.local.db.dao.GenreDao
import com.amperfy.data.local.db.dao.MusicFolderDao
import com.amperfy.data.local.db.dao.PlaybackDao
import com.amperfy.data.local.db.dao.PlaylistDao
import com.amperfy.data.local.db.dao.PlaylistLocalStateDao
import com.amperfy.data.local.db.dao.PlaylistSongDao
import com.amperfy.data.local.db.dao.PodcastDao
import com.amperfy.data.local.db.dao.PodcastEpisodeDao
import com.amperfy.data.local.db.dao.PodcastEpisodeLocalStateDao
import com.amperfy.data.local.db.dao.RadioDao
import com.amperfy.data.local.db.dao.SearchHistoryDao
import com.amperfy.data.local.db.dao.SongDao
import com.amperfy.data.local.db.dao.SongDirectoryDao
import com.amperfy.data.local.db.dao.SongLocalStateDao
import com.amperfy.data.local.db.entity.AccountScopeEntity
import com.amperfy.data.local.db.entity.AlbumEntity
import com.amperfy.data.local.db.entity.AlbumSyncStateEntity
import com.amperfy.data.local.db.entity.ArtistEntity
import com.amperfy.data.local.db.entity.DirectoryEntity
import com.amperfy.data.local.db.entity.DownloadEntryEntity
import com.amperfy.data.local.db.entity.EventLogEntity
import com.amperfy.data.local.db.entity.GenreEntity
import com.amperfy.data.local.db.entity.MusicFolderEntity
import com.amperfy.data.local.db.entity.PlaybackQueueItemEntity
import com.amperfy.data.local.db.entity.PlaybackStateEntity
import com.amperfy.data.local.db.entity.PlaylistEntity
import com.amperfy.data.local.db.entity.PlaylistLocalStateEntity
import com.amperfy.data.local.db.entity.PlaylistSongEntity
import com.amperfy.data.local.db.entity.PodcastEntity
import com.amperfy.data.local.db.entity.PodcastEpisodeEntity
import com.amperfy.data.local.db.entity.PodcastEpisodeLocalStateEntity
import com.amperfy.data.local.db.entity.RadioEntity
import com.amperfy.data.local.db.entity.SearchHistoryEntity
import com.amperfy.data.local.db.entity.SongDirectoryEntity
import com.amperfy.data.local.db.entity.SongEntity
import com.amperfy.data.local.db.entity.SongLocalStateEntity

/**
 * Amperfy Room 数据库。
 *
 * 单一全局数据库、所有已登录账户共享：账户隔离靠每张账户表的 account_id 与
 * account_scope 租户根级联，而非按账户创建/关闭多个数据库实例。
 *
 * 表数 22（P2 批次 2 的 21 表 + Batch 2 新增 podcast_episode_local_state）：核心 library 7 表（批次 1）+ 其余——
 * playlist 三表（playlist_song 强 FK 级联 playlist + 整表替换唯一写入口 replacePlaylistSongs、
 * playlist_local_state 本地 last_played）、podcast 两表（podcast_episode 强 FK podcast + 软删除
 * 差集 UPDATE markDeletedMissing）、directory 三表（directory/music_folder/song_directory）、
 * radio/download_entry/search_history 账户表 + event_log/playback_state/playback_queue_item 三全局表。
 * playback_queue_item 仅身份引用 + 非权威 fallback 列，无 stream_url/cache_path；replaceAllQueues/
 * replacePlaylistSongs 为事务型唯一写入口，全库无逐行 position 更新方法。
 *
 * version 仍为 1（v1 未发布，Schema JSON app/schemas/ 就地重生成——Batch 2 的列/表/主键变更同样
 * 走「就地重生成 v1」，不 bump 版本、不写 Migration，装机需清数据）；DAO instrumentation 测试与
 * 基准在批次 3。DI 接线（RoomDatabase 构建、Migration）在 P5，不在本批；伴生对象仅提供文件名常量。
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        // 核心 library（批次 1）
        AccountScopeEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        SongEntity::class,
        GenreEntity::class,
        SongLocalStateEntity::class,
        AlbumSyncStateEntity::class,
        // 播放列表（批次 2）
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlaylistLocalStateEntity::class,
        // 电台 / 播客（批次 2）
        RadioEntity::class,
        PodcastEntity::class,
        PodcastEpisodeEntity::class,
        // 播客单集本地状态（Batch 2 建表、Batch 4 起由 PodcastEpisodeLocalStateDao 消费；
        // 结构对称 song_local_state）
        PodcastEpisodeLocalStateEntity::class,
        // 目录（批次 2）
        MusicFolderEntity::class,
        DirectoryEntity::class,
        SongDirectoryEntity::class,
        // 纯本地状态账户表（批次 2）
        DownloadEntryEntity::class,
        SearchHistoryEntity::class,
        // 全局表（批次 2）
        EventLogEntity::class,
        PlaybackStateEntity::class,
        PlaybackQueueItemEntity::class,
    ],
)
abstract class AmperfyDatabase : RoomDatabase() {
    abstract fun accountScopeDao(): AccountScopeDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun songDao(): SongDao
    abstract fun genreDao(): GenreDao
    abstract fun songLocalStateDao(): SongLocalStateDao
    abstract fun albumSyncStateDao(): AlbumSyncStateDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistSongDao(): PlaylistSongDao
    abstract fun playlistLocalStateDao(): PlaylistLocalStateDao
    abstract fun radioDao(): RadioDao
    abstract fun podcastDao(): PodcastDao
    abstract fun podcastEpisodeDao(): PodcastEpisodeDao
    abstract fun podcastEpisodeLocalStateDao(): PodcastEpisodeLocalStateDao
    abstract fun musicFolderDao(): MusicFolderDao
    abstract fun directoryDao(): DirectoryDao
    abstract fun songDirectoryDao(): SongDirectoryDao
    abstract fun downloadEntryDao(): DownloadEntryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun playbackDao(): PlaybackDao

    companion object {
        const val DATABASE_NAME = "amperfy.db"
    }
}
