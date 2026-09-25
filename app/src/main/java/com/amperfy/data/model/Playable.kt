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

package com.amperfy.data.model

data class Playable(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val coverArt: String?,
    val streamUrl: String,
    val isDownloaded: Boolean = false,
    val downloadPath: String? = null,
    /**
     * 电台标志（Phase 6.3，对应 iOS AbstractPlayable.isRadio）
     * 电台用原始 streamUrl 直接播放，排除在 scrobble/下载/自动缓存之外
     */
    val isRadio: Boolean = false,
    /**
     * 播客单集标志（Phase 6.4，对应 iOS AbstractPlayable.isPodcastEpisode）
     * 排除在 scrobble 之外（iOS ScrobbleSyncer 仅对 Song 提交）；
     * Batch 4 起**不再**排除自动缓存——单集与歌曲共用下载管线（iOS 亦只排除 radio）
     */
    val isPodcastEpisode: Boolean = false,
    /**
     * 曲目所属账户 ident（C0 合同）：
     * 播放队列跨账户共享，流/封面 URL 必须按曲目所属账户构建（W1 经
     * registry.getByIdent 接线，fail-closed）。C0 阶段恒为当前单账户 ident。
     */
    val accountId: String = "",
    /**
     * ReplayGain（C0 合同透传，W2 消费；null = 无标签）
     * GainProcessor 只用 track 侧，album 侧备用（对齐 iOS 现状）
     */
    val replayGainTrackGain: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumGain: Float? = null,
    val replayGainAlbumPeak: Float? = null
)
