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
 * 播客单集纯本地状态（结构对称 [SongLocalStateEntity]）。
 *
 * 与 podcast_episode 表按所有权拆分：远端快照写 podcast_episode 表、本地状态写本表，
 * 服务器同步物理上无法覆盖本地缓存路径。主键 (account_id, episode_id)。
 * 仅 FK account_scope 并级联删除——**不** FK podcast_episode：远端差集删除/软删除不得连带
 * 删除本地缓存记录（与 song_local_state 同口径）。
 *
 * - cache_path：缓存文件相对路径，null = 未缓存；
 * - play_progress_ms / play_progress_updated_at：单集播放进度及写入时刻，null = 无保存进度
 *   （与 song_local_state 的同名两列同口径，成对写入——进度持久化现只打在
 *   song/song_local_state 上，单集不在 song 表被静默丢弃，Batch 4 改为按 Playable 类型
 *   分派后消费本列）。
 *
 * **本批仅建表，无 DAO、无写入方**：单集下载缓存管线在 Batch 4 落地并消费本表；
 * 本批一次性完成全部 schema 变更，避免后续再动 Room 结构。
 */
@Entity(
    tableName = "podcast_episode_local_state",
    primaryKeys = ["account_id", "episode_id"],
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
    ],
)
data class PodcastEpisodeLocalStateEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "cache_path") val cachePath: String? = null,
    @ColumnInfo(name = "play_progress_ms") val playProgressMs: Long? = null,
    @ColumnInfo(name = "play_progress_updated_at") val playProgressUpdatedAt: Long? = null,
)
