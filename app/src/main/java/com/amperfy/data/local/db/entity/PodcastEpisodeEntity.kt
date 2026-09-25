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
 * 服务器播客单集快照。
 *
 * 复合主键 (account_id, server_id)；字段：
 * title、depiction→description、publishDate→publish_date、status、streamId→stream_id、
 * duration、coverArt、podcast 关系→podcast_id 标量。
 *
 * 双 FK：
 * - FK→account_scope ON DELETE CASCADE（账户表通例，覆盖 podcast_id 为 NULL 的孤儿单集）；
 * - **FK→podcast(account_id, podcast_id→server_id) ON DELETE CASCADE**（强所有权，父频道
 *   删除时级联删单集）。podcast_id 为 NULL 时 SQLite 不强制该 podcast FK——**父频道缺失场景由
 *   P3 mapper 处理：置 NULL 并记日志**，此时该单集仍靠 account_scope FK 参与账户级联清理。
 *
 * status 存为 Int（PodcastEpisodeRemoteStatus.raw，取值 0-6：
 * UNDEFINED=0/NEW=1/DOWNLOADING=2/COMPLETED=3/ERROR=4/DELETED=5/SKIPPED=6）：现有唯一持久化形态，
 * 忠实平移零新增映射；软删除 = DELETED.raw=5，差集经 markDeletedMissing(:deletedRaw) 参数化（不写魔法数字）。
 *
 * INDEX(account_id, podcast_id, publish_date)——同时覆盖 podcast FK 子列前缀。
 */
@Entity(
    tableName = "podcast_episode",
    primaryKeys = ["account_id", "server_id"],
    foreignKeys = [
        ForeignKey(
            entity = AccountScopeEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["account_id", "server_id"],
            childColumns = ["account_id", "podcast_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("account_id"),
        Index("account_id", "podcast_id", "publish_date"),
    ],
)
data class PodcastEpisodeEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String? = null,
    /** 发布时间戳（epoch 毫秒） */
    @ColumnInfo(name = "publish_date") val publishDate: Long = 0,
    /** PodcastEpisodeRemoteStatus.raw（0-6）；软删除 = DELETED.raw=5 */
    @ColumnInfo(name = "status") val status: Int = 0,
    /** 播放/下载 URL 用 stream_id ?? server_id */
    @ColumnInfo(name = "stream_id") val streamId: String? = null,
    @ColumnInfo(name = "duration") val duration: Int = 0,
    @ColumnInfo(name = "cover_art") val coverArt: String? = null,
    /** 父频道服务端 id（标量；NULL 时 podcast FK 不强制，见类级 KDoc） */
    @ColumnInfo(name = "podcast_id") val podcastId: String? = null,
)
