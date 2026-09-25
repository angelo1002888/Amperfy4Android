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

package com.amperfy.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 服务器歌曲快照。
 *
 * 复合主键 (account_id, server_id)。album_id/artist_id 为标量 ID（eventually-consistent），
 * 不建 FK；仅对 account_scope 建 FK 并级联删除。
 *
 * **只保存服务器快照字段**：
 * **不含** isDownloaded/downloadPath/playProgressMs/playProgressUpdatedAt/playCount（归 song_local_state）
 * 与 directoryId（归批次 2 的 song_directory）。远端 upsert 物理上无法触碰本地状态表。
 *
 * created_at 对应 Subsonic created（用于“按添加日期”排序；
 * 不记录本地插入时刻，不创建 first_seen_at）。
 *
 * 派生键 search_key/section_key/sort_key 由 LibraryTextKeyNormalizer 与 title 原子生成。
 */
@Entity(
    tableName = "song",
    primaryKeys = ["account_id", "server_id"],
    foreignKeys = [
        ForeignKey(
            entity = AccountScopeEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("account_id"),
        Index("account_id", "album_id"),
        Index("account_id", "artist_id"),
        Index("account_id", "genre"),
        Index("account_id", "sort_key", "server_id"),
        Index("account_id", "section_key", "sort_key", "server_id"),
    ],
)
data class SongEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "title") val title: String,
    /** 所属专辑服务端 id（标量，可能父实体后到达；null = 未知） */
    @ColumnInfo(name = "album_id") val albumId: String? = null,
    @ColumnInfo(name = "album_name") val albumName: String = "",
    /** 所属艺术家服务端 id（标量；null = 未知） */
    @ColumnInfo(name = "artist_id") val artistId: String? = null,
    @ColumnInfo(name = "artist_name") val artistName: String = "",
    @ColumnInfo(name = "track") val track: Int? = null,
    /** 碟号 */
    @ColumnInfo(name = "disc") val disc: Int? = null,
    @ColumnInfo(name = "year") val year: Int? = null,
    @ColumnInfo(name = "genre") val genre: String? = null,
    @ColumnInfo(name = "duration") val duration: Int = 0,
    /** 比特率 */
    @ColumnInfo(name = "bitrate") val bitrate: Int? = null,
    @ColumnInfo(name = "content_type") val contentType: String? = null,
    @ColumnInfo(name = "size") val size: Long? = null,
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    @ColumnInfo(name = "suffix") val suffix: String? = null,
    /** 服务器返回的媒体路径 */
    @ColumnInfo(name = "path") val path: String = "",
    @ColumnInfo(name = "is_video") val isVideo: Boolean = false,
    /** 服务端 type（如 music/podcast） */
    @ColumnInfo(name = "type") val type: String? = null,
    /** albumArtist 服务端 id */
    @ColumnInfo(name = "album_artist_id") val albumArtistId: String? = null,
    /** 服务器下发的 stream 引用（播放构建 URL 时的回退，见 P3 mapper） */
    @ColumnInfo(name = "stream_url") val streamUrl: String? = null,
    /** 服务器 created 时间戳（对应 Subsonic created / iOS Song.addedDate） */
    @ColumnInfo(name = "created_at") val createdAt: Long? = null,
    /** 收藏时间戳；null = 未收藏 */
    @ColumnInfo(name = "starred_at") val starredAt: Long? = null,
    /** 评分 0-5，null = 未评分（可空） */
    @ColumnInfo(name = "rating") val rating: Int? = null,
    // ReplayGain（服务器 <song replayGain> 下发，null = 无标签）
    @ColumnInfo(name = "replay_gain_track_gain") val replayGainTrackGain: Float? = null,
    @ColumnInfo(name = "replay_gain_track_peak") val replayGainTrackPeak: Float? = null,
    @ColumnInfo(name = "replay_gain_album_gain") val replayGainAlbumGain: Float? = null,
    @ColumnInfo(name = "replay_gain_album_peak") val replayGainAlbumPeak: Float? = null,
    // 派生键
    @ColumnInfo(name = "search_key") val searchKey: String,
    @ColumnInfo(name = "section_key") val sectionKey: String,
    @ColumnInfo(name = "sort_key") val sortKey: String,
)
