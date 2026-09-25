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
import androidx.room.PrimaryKey

/**
 * 全局播放器标量状态（对应 iOS PlayerMO）。
 *
 * **全局表**：无 account_id、无 account_scope FK（豁免账户纪律）。单行主键 id=1。
 * 字段：
 * musicIndex、playProgress、playDuration、contextType、contextId、contextName、playerMode、
 * wasPlaying、isUserQueuePlaying（playSource 的现行语义）、currentContextIndex、displayMode、savedAt。
 *
 * Batch 2（队列 Repeat/Shuffle/播客独立队列）新增四列：repeatSetting、shuffleSetting、podcastIndex
 * （逐项对照 iOS PlayerMO 同名字段）与权威 playSource 字符串列。
 *
 * **不存队列 JSON**——五条队列（PLAYLIST/USER/CONTEXT/CONTEXT_SHUFFLED/PODCAST）归 playback_queue_item 有序表，
 * 避免 Gson/TypeToken/R8 泛型签名风险。
 * 保存时与 playback_queue_item 在同一事务（PlaybackDao.replaceAllQueues）。
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Int = 1,
    /** 当前播放索引 */
    @ColumnInfo(name = "music_index") val musicIndex: Int = 0,
    /** 播放进度（毫秒） */
    @ColumnInfo(name = "play_progress") val playProgress: Long = 0,
    /** 歌曲总时长（毫秒，用于校验） */
    @ColumnInfo(name = "play_duration") val playDuration: Long = 0,
    /** 播放上下文类型（PlayContextType.name） */
    @ColumnInfo(name = "context_type") val contextType: String = "NONE",
    @ColumnInfo(name = "context_id") val contextId: String? = null,
    @ColumnInfo(name = "context_name") val contextName: String = "",
    /** 播放模式（PlayerMode.name） */
    @ColumnInfo(name = "player_mode") val playerMode: String = "MUSIC",
    @ColumnInfo(name = "was_playing") val wasPlaying: Boolean = false,
    /**
     * 对应 playSource 概念——现行语义为「当前播放是否来自 User Queue」布尔，
     * 不使用 "SINGLE"/"CONTEXT"/"USER" 字符串域。
     */
    @ColumnInfo(name = "is_user_queue_playing") val isUserQueuePlaying: Boolean = false,
    /** 当前在 contextQueue 中的索引 */
    @ColumnInfo(name = "current_context_index") val currentContextIndex: Int = 0,
    /**
     * 队列循环模式 rawValue（对照 iOS PlayerMO.repeatSetting：off=0 / all=1 / single=2，
     * 见 [com.amperfy.data.model.RepeatMode]）。仅承载音乐侧——播客模式对外恒读 off。
     */
    @ColumnInfo(name = "repeat_setting", defaultValue = "0") val repeatSetting: Int = 0,
    /**
     * 队列随机模式（对照 iOS PlayerMO.shuffleSetting：0 = 关 / 1 = 开）。同样仅承载音乐侧。
     * 打乱后的上下文副本本身存在 playback_queue_item 的 CONTEXT_SHUFFLED 段。
     */
    @ColumnInfo(name = "shuffle_setting", defaultValue = "0") val shuffleSetting: Int = 0,
    /**
     * 播客队列当前索引（对照 iOS PlayerMO.podcastIndex）。music_index 继续承载音乐侧索引，
     * 两侧索引各自独立保存，切换播放模式时互不影响（iOS stopButRemainIndex 语义）。
     */
    @ColumnInfo(name = "podcast_index", defaultValue = "0") val podcastIndex: Int = 0,
    /**
     * 播放来源枚举名（PlayerManager.PlaySource：CONTEXT / USER / SINGLE），null = 旧行无该列。
     *
     * 修既有持久化损耗：此前只存 [isUserQueuePlaying] 布尔，SINGLE 读回时被吞成 CONTEXT。
     * 新列为权威值，为 null 时才回退旧布尔推导。
     */
    @ColumnInfo(name = "play_source") val playSource: String? = null,
    /** 播放器显示模式：LARGE / COMPACT */
    @ColumnInfo(name = "display_mode") val displayMode: String = "LARGE",
    /** 保存时间戳，由调用方传入 */
    @ColumnInfo(name = "saved_at") val savedAt: Long = 0,
)
