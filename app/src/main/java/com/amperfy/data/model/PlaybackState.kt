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

/**
 * PlaybackState - 保存完整的播放器状态用于应用重启后恢复
 *
 * 对应iOS: PlayerData (在Core Data中保存的PlayerMO)
 * iOS文件: AmperfyKit/Storage/ManagedObjects/PlayerMO+CoreDataProperties.swift
 *
 * 保存的信息包括:
 * - 当前播放列表和歌曲
 * - 播放进度
 * - 播放上下文（来源类型：album/artist/playlist等）
 * - 播放模式（音乐/播客）
 *
 */
data class PlaybackState(
    val id: Int = 1, // 单例，只有一条记录

    // 当前播放列表（Media3使用的底层列表）
    val playlist: List<Playable> = emptyList(),

    // 当前播放索引
    val currentIndex: Int = 0,

    // 播放进度（毫秒）
    val playProgress: Long = 0,

    // 歌曲总时长（毫秒）- 用于验证
    val playDuration: Long = 0,

    // 播放上下文信息
    val contextType: PlayContextType = PlayContextType.NONE,
    val contextId: String? = null,  // Album ID, Artist ID, Playlist ID等
    val contextName: String = "",    // 上下文名称用于显示

    // 播放模式
    val playerMode: PlayerMode = PlayerMode.MUSIC,

    // 播放状态（不自动恢复播放，但记住是否在播放中）
    val wasPlaying: Boolean = false,

    // ========== 三层队列数据 (对应iOS: PlayQueueHandler) ==========

    /**
     * User Queue - 用户手动添加的队列
     * 对应iOS: PlayQueueHandler.userQueuePlaylist
     */
    val userQueue: List<Playable> = emptyList(),

    /**
     * Context Queue - 播放上下文队列
     * 对应iOS: PlayQueueHandler.activeQueue
     *
     * Previous Queue 不需要单独存储，iOS中是动态计算的：
     * prevQueue = contextQueue[0 until currentContextIndex]
     */
    val contextQueue: List<Playable> = emptyList(),

    /**
     * 当前在 contextQueue 中的索引
     * 对应iOS: PlayQueueHandler.currentIndex
     */
    val currentContextIndex: Int = 0,

    /**
     * 打乱后的上下文队列副本（Batch 2，对应 iOS PlayerData.shuffledContextPlaylist）
     * 与 [contextQueue] 元素相同、顺序不同；shuffle 开启时它才是生效的活动队列
     */
    val shuffledContextQueue: List<Playable> = emptyList(),

    /**
     * 播客队列（Batch 2，对应 iOS PlayerData.podcastPlaylist）——与音乐上下文队列完全独立，
     * 切换播放模式时两侧队列与索引都原样保留
     */
    val podcastQueue: List<Playable> = emptyList(),

    /** 当前在 [podcastQueue] 中的索引（对应 iOS PlayerMO.podcastIndex） */
    val podcastIndex: Int = 0,

    /** 队列循环模式（Batch 2，对应 iOS PlayerMO.repeatSetting） */
    val repeatMode: RepeatMode = RepeatMode.OFF,

    /** 队列随机模式（Batch 2，对应 iOS PlayerMO.shuffleSetting） */
    val isShuffle: Boolean = false,

    /**
     * 播放来源标记
     * "CONTEXT" - 从 contextQueue 播放
     * "USER" - 从 userQueue 播放
     * "SINGLE" - 单曲播放
     */
    val playSource: String = "SINGLE",

    /**
     * 播放器显示模式
     * "LARGE" - 大图模式（显示封面）
     * "COMPACT" - 列表模式（显示队列）
     * 对应iOS: PopupPlayerVC.displayStyle
     */
    val displayMode: String = "LARGE",

    // 保存时间戳
    val savedAt: Long = System.currentTimeMillis()
)

/**
 * 播放上下文类型
 * 对应iOS中的不同播放来源
 */
enum class PlayContextType {
    NONE,           // 无上下文（单曲播放）
    ALBUM,          // 从专辑播放
    ARTIST,         // 从艺术家页面播放
    PLAYLIST,       // 从播放列表播放
    SEARCH,         // 从搜索结果播放
    SONGS,          // 从歌曲列表播放
    GENRE,          // 从流派播放
    FOLDER,         // 从文件夹播放
    RECENTLY_PLAYED,// 从最近播放
    PODCAST         // 从播客播放（Batch 2 起走独立播客队列 + PlayerMode.PODCAST，对齐 iOS）
}
