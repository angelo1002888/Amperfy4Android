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

/**
 * 全局有序播放队列项（有序表形态，不存队列 JSON）。
 *
 * **全局表**：account_id 只作数据列（用于恢复时按账户 JOIN 实体），**非** FK、**非**账户纪律约束
 * 对象（豁免账户纪律；Player 队列允许跨账户曲目）。主键 (queue_type, position)。
 *
 * 以**稳定身份**为主：
 * - queue_type：PLAYLIST / USER / CONTEXT / CONTEXT_SHUFFLED / PODCAST
 *   （后两段 Batch 2 新增：打乱后的上下文副本、播客独立队列，对应 iOS
 *   shuffledContextPlaylist / podcastPlaylist）；
 * - item_type：SONG / RADIO / PODCAST_EPISODE；
 * - account_id + entity_id：恢复时按 item_type + accountId + entityId JOIN 最新实体与 song_local_state。
 *
 * **非权威 fallback 列**：在线 Search 返回的 Song 不写入本地资料库却允许直接播放，
 * 为保证这类队列能恢复，额外保存 title/artist/album/duration/ReplayGain 作为回退显示字段；
 * 恢复时优先用 JOIN 到的最新实体，仅在实体不在库中时用 fallback。
 *
 * **严禁保存 stream_url / cache_path / 已拼接账户 URL 的 coverArt**：这些值会在歌曲
 * 下载完成、服务器 URL 切换或元数据更新后过时，尤其旧 cache_path 会导致重启恢复时尝试播放不存在
 * 文件——stream/cover URL 一律恢复时从 CredentialsManager 当前账户 URL 重新生成，cache_path 从
 * song_local_state 现读并验证文件存在。
 *
 * (queue_type, position) 主键不支持安全逐行换位；只允许经 PlaybackDao.replaceAllQueues 整队列替换，
 * DAO 禁止逐行 UPDATE position。
 */
@Entity(
    tableName = "playback_queue_item",
    primaryKeys = ["queue_type", "position"],
)
data class PlaybackQueueItemEntity(
    /** 队列类型：PLAYLIST / USER / CONTEXT / CONTEXT_SHUFFLED / PODCAST */
    @ColumnInfo(name = "queue_type") val queueType: String,
    /** 队列内 0 基有序位置（整队列替换时连续） */
    @ColumnInfo(name = "position") val position: Int,
    /** 条目类型：SONG / RADIO / PODCAST_EPISODE */
    @ColumnInfo(name = "item_type") val itemType: String,
    /** 所属账户 id（数据列，恢复时按账户 JOIN；非 FK、非账户纪律约束） */
    @ColumnInfo(name = "account_id") val accountId: String,
    /** 实体服务端 id */
    @ColumnInfo(name = "entity_id") val entityId: String,
    // 非权威 fallback 显示字段（仅实体不在库中时使用）
    @ColumnInfo(name = "fallback_title") val fallbackTitle: String? = null,
    @ColumnInfo(name = "fallback_artist") val fallbackArtist: String? = null,
    @ColumnInfo(name = "fallback_album") val fallbackAlbum: String? = null,
    @ColumnInfo(name = "fallback_duration") val fallbackDuration: Int? = null,
    @ColumnInfo(name = "fallback_replay_gain_track_gain") val fallbackReplayGainTrackGain: Float? = null,
    @ColumnInfo(name = "fallback_replay_gain_track_peak") val fallbackReplayGainTrackPeak: Float? = null,
    @ColumnInfo(name = "fallback_replay_gain_album_gain") val fallbackReplayGainAlbumGain: Float? = null,
    @ColumnInfo(name = "fallback_replay_gain_album_peak") val fallbackReplayGainAlbumPeak: Float? = null,
)
