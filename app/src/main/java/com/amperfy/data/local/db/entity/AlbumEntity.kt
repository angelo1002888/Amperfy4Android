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
 * 服务器专辑快照。
 *
 * 复合主键 (account_id, server_id)。artist_id 为标量 ID（eventually-consistent，
 * 父艺术家可能后到达），不建 FK；仅对 account_scope 建 FK 并级联删除。
 *
 * 远端播放统计列命名强制 remote_ 前缀：`remote_play_count` 只表示 Subsonic 远端统计，
 * 不得映射为 Song 的本地 playCount。
 *
 * **不含** isCached/isSongsSynced/newestIndex/recentIndex——缓存态由歌曲 cache_path 推导，
 * 同步态归 album_sync_state。
 *
 * 派生键 search_key/section_key/sort_key 及“按艺术家”模式的 artist_sort_key 均由
 * LibraryTextKeyNormalizer 与 name/artist_name 原子生成。
 */
@Entity(
    tableName = "album",
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
        Index("account_id", "artist_id"),
        Index("account_id", "genre"),
        Index("account_id", "sort_key", "server_id"),
        Index("account_id", "section_key", "sort_key", "server_id"),
        Index("account_id", "artist_sort_key", "server_id"),
    ],
)
data class AlbumEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "name") val name: String,
    /** 所属艺术家服务端 id（标量，可能父实体后到达；null = 未知） */
    @ColumnInfo(name = "artist_id") val artistId: String? = null,
    @ColumnInfo(name = "artist_name") val artistName: String = "",
    @ColumnInfo(name = "genre") val genre: String? = null,
    @ColumnInfo(name = "year") val year: Int? = null,
    @ColumnInfo(name = "song_count") val songCount: Int = 0,
    @ColumnInfo(name = "duration") val duration: Int = 0,
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    /** 服务器 created 时间戳（对应 Subsonic created） */
    @ColumnInfo(name = "created_at") val createdAt: Long? = null,
    /** Subsonic 远端播放统计（强制 remote_ 前缀） */
    @ColumnInfo(name = "remote_play_count") val remotePlayCount: Int = 0,
    /** 收藏时间戳；null = 未收藏 */
    @ColumnInfo(name = "starred_at") val starredAt: Long? = null,
    /** 评分 0-5，0 = 未评分 */
    @ColumnInfo(name = "rating") val rating: Int = 0,
    // 派生键
    @ColumnInfo(name = "search_key") val searchKey: String,
    @ColumnInfo(name = "section_key") val sectionKey: String,
    @ColumnInfo(name = "sort_key") val sortKey: String,
    /** “按艺术家”排序模式派生键，由同一 mapper 依 artist display name 生成 */
    @ColumnInfo(name = "artist_sort_key") val artistSortKey: String,
)
