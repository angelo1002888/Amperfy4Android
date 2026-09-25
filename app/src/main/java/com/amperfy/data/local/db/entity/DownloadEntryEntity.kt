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
 * 下载请求状态机（对应 iOS DownloadMO）。
 *
 * 主键 (account_id, entity_type, song_id)；仅对 account_scope 建 FK 并级联删除。
 * entity_type 为 Batch 2 预置列（默认 "SONG"，取值域 SONG / PODCAST_EPISODE），供 Batch 4
 * 单集下载管线泛化使用；**本批无写入方产生非默认值**，DAO 现有 `WHERE account_id + song_id`
 * 查询亦不加该谓词——song_id 在歌曲域内唯一，Batch 4 泛化时再统一补谓词。
 * **不** FK song——下载请求可先于歌曲元数据到达；Downloads 页只观察本表、再批量 JOIN 装配。
 *
 * 状态由时间列派生（无独立 status 列）：
 * - finish_date != null → 已完成（对勾）；
 * - error_date != null → 失败/已取消（感叹号）；
 * - 两者皆 null → 等待中/下载中（下载中经运行时进度区分）。
 *
 * updated_at 为本批新增：文件写入成功时与
 * song_local_state.cache_path 在同一事务更新，供观察者判定状态变更。所有时间由调用方传入，
 * DAO 不取系统时钟。
 */
@Entity(
    tableName = "download_entry",
    primaryKeys = ["account_id", "entity_type", "song_id"],
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
        Index("account_id", "creation_date"),
        Index("account_id", "finish_date", "error_date"),
    ],
)
data class DownloadEntryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    /**
     * 下载对象类型：SONG（默认）/ PODCAST_EPISODE。Batch 2 预置、Batch 4 消费；
     * 现有写入方一律取默认值。
     */
    @ColumnInfo(name = "entity_type", defaultValue = "SONG") val entityType: String = "SONG",
    @ColumnInfo(name = "song_id") val songId: String,
    /** 下载请求创建时间戳，列表按此升序 */
    @ColumnInfo(name = "creation_date") val creationDate: Long = 0,
    /** 完成时间戳，null = 未完成 */
    @ColumnInfo(name = "finish_date") val finishDate: Long? = null,
    /** 失败/取消时间戳，null = 无错误 */
    @ColumnInfo(name = "error_date") val errorDate: Long? = null,
    /** 记录最近更新时间戳（本批新增） */
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)
