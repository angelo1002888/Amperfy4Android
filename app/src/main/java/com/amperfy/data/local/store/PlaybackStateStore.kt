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

package com.amperfy.data.local.store

import com.amperfy.data.model.PlaybackState

/**
 * 播放状态与单曲本地统计的持久化边界（专题 15 P1）
 *
 * PlaybackStateManager/ScrobbleSyncer 的数据库访问收口于此，只出入领域模型或标量，
 * 不出现数据库类型。
 *
 * 两组语义：
 * - 播放状态三方法（get/save/clear）操作 playback_state 全局单行——该实体为复合主键
 *   豁免实体（无 accountId，复合主键豁免：LogEntry/PlaybackState/Account）。
 * - song 级三方法（saveSongProgress/getSongProgress/incrementPlayCount）带 accountId，
 *   写 song_local_state 的本地统计列（playProgressMs/playProgressUpdatedAt/playCount，
 *   playCount 为纯本地统计）。
 * - Batch 4 增播客单集进度两方法（saveEpisodeProgress/getEpisodeProgress），落
 *   podcast_episode_local_state——此前单集进度打在 song 表上被静默丢弃，
 *   现由 PlaybackStateManager 按 Playable 类型分派。
 *
 * P3 批次 1a 起 song 级三方法
 * 走 Room，**P4 批次 2 起全部走 Room**（data/local/db/store/RoomPlaybackStateStore：
 * playback_state 单行 + playback_queue_item 身份引用表，恢复时 JOIN 最新实体、按条目账户凭证
 * 现生成 stream/cover URL）。
 */
interface PlaybackStateStore {

    /** 读取保存的播放状态（无记录返回 null） */
    suspend fun getPlaybackState(): PlaybackState?

    /** 保存/覆盖播放状态（单行，id 恒为 1） */
    suspend fun savePlaybackState(state: PlaybackState)

    /** 清除保存的播放状态 */
    suspend fun clearPlaybackState()

    /**
     * 写单曲播放进度：progressMs/updatedAt 同为 null 表示清零
     * （调用方已完成有效性判定，Store 只落值）；歌曲不在库中则静默不写。
     */
    suspend fun saveSongProgress(accountId: String, songId: String, progressMs: Long?, updatedAt: Long?)

    /** 读单曲播放进度（无记录或无有效进度返回 null） */
    suspend fun getSongProgress(accountId: String, songId: String): Long?

    /**
     * 写播客单集播放进度（Batch 4）：语义与 [saveSongProgress] 逐条对称，
     * 落 podcast_episode_local_state 的 play_progress 两列；单集不在库中则静默不写。
     */
    suspend fun saveEpisodeProgress(accountId: String, episodeId: String, progressMs: Long?, updatedAt: Long?)

    /** 读播客单集播放进度（无记录或无有效进度返回 null） */
    suspend fun getEpisodeProgress(accountId: String, episodeId: String): Long?

    /**
     * 本地播放计数 +1（playCount 纯本地统计，与 scrobble 成功与否独立）；
     * 歌曲不在库中则静默不写。
     */
    suspend fun incrementPlayCount(accountId: String, songId: String)
}
