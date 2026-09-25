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
 * 播放列表有序歌曲关系。
 *
 * 主键 (account_id, playlist_id, position)——**不能**以 (playlist_id, song_id) 为主键，
 * 因为服务端允许同一歌曲在同一播放列表重复出现。
 *
 * **仅** FK→playlist(account_id, server_id) ON DELETE CASCADE（强所有权）：
 * 账户级联经 playlist 传递（playlist 已 FK account_scope），故**刻意不重复** FK account_scope；
 * PK 最左列即 account_id，无需额外 Index("account_id")。
 * **不** FK song 表——歌曲可能后到达（eventually-consistent），关联由查询 JOIN 成立。
 *
 * 唯一写入口是 PlaylistSongDao.replacePlaylistSongs（整表替换）；DAO 禁止逐行 UPDATE position
 * （交换两个以 position 为主键的行会主键冲突）。
 */
@Entity(
    tableName = "playlist_song",
    primaryKeys = ["account_id", "playlist_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["account_id", "server_id"],
            childColumns = ["account_id", "playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("account_id", "playlist_id", "song_id"),
    ],
)
data class PlaylistSongEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    /** 该歌曲在播放列表中的 0 基有序位置（整表替换时连续 0..N-1） */
    @ColumnInfo(name = "position") val position: Int,
    /** 歌曲服务端 id（不 FK song，父歌曲可能后到达） */
    @ColumnInfo(name = "song_id") val songId: String,
)
