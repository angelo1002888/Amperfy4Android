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
 * 服务器目录快照。
 *
 * 复合主键 (account_id, server_id)；仅对 account_scope 建 FK 并级联删除。
 * 字段：name、parent_id、music_folder_id、cover_art。
 * parent_id/music_folder_id 为标量 ID 无 FK（iOS 用 parent/musicFolder 双重挂载区分层级，
 * Android 以字符串外键实现同等查询）。cover_art 为服务器快照字段
 * （目录行可显示封面）。
 *
 * 目录内子目录按名排序，故加 sort_key（**不加** section_key，无字母索引）。
 * 索引：(account_id, parent_id)、(account_id, music_folder_id)。
 */
@Entity(
    tableName = "directory",
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
        Index("account_id", "parent_id"),
        Index("account_id", "music_folder_id"),
    ],
)
data class DirectoryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    /** 深层子目录的父目录服务端 id（getMusicDirectory 结果），null = 顶层 */
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    /** 顶层目录所属音乐文件夹服务端 id（getIndexes 结果），null = 非顶层 */
    @ColumnInfo(name = "music_folder_id") val musicFolderId: String? = null,
    /** 组内按名排序键，由 LibraryTextKeyNormalizer 与 name 原子生成 */
    @ColumnInfo(name = "sort_key") val sortKey: String,
)
