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

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.repository.MediaUrlRepository

/**
 * 播客领域模型 - 对应 iOS: Podcast（AmperfyKit/Storage/EntityWrappers/Podcast.swift）
 *
 * - subtitle/subsubtitle 为空（iOS Podcast.swift:88-89），列表行信息为 "N Episode(s)"
 * - 播客不可收藏（iOS remoteToggleFavorite 抛 notSupported）
 */
data class Podcast(
    val id: String,
    val title: String,
    val depiction: String = "",
    val coverArt: String? = null,
    val episodeCount: Int = 0
) {
    /** "N Episode(s)"（iOS Podcast.infoDetails，单复数对齐） */
    val info: String
        get() = if (episodeCount == 1) "1 Episode" else "$episodeCount Episodes"
}

/**
 * 单集服务器状态 - 对应 iOS: PodcastEpisodeRemoteStatus（PodcastEpisode.swift:28-68）
 * raw 值持久化到单集表的 status 列
 */
enum class PodcastEpisodeRemoteStatus(val raw: Int) {
    UNDEFINED(0),
    NEW(1),
    DOWNLOADING(2),
    COMPLETED(3),
    ERROR(4),
    DELETED(5),
    SKIPPED(6);

    companion object {
        fun fromRaw(raw: Int): PodcastEpisodeRemoteStatus =
            entries.firstOrNull { it.raw == raw } ?: UNDEFINED

        /** iOS PodcastEpisodeRemoteStatus.create(from:)：Subsonic status 字符串映射 */
        fun fromSubsonicString(value: String?): PodcastEpisodeRemoteStatus = when (value) {
            "new" -> NEW
            "downloading" -> DOWNLOADING
            "completed" -> COMPLETED
            "error" -> ERROR
            "deleted" -> DELETED
            "skipped" -> SKIPPED
            else -> UNDEFINED
        }
    }
}

/**
 * 播客单集领域模型 - 对应 iOS: PodcastEpisode（AbstractPlayable 子类）
 *
 * Batch 4 起单集有独立下载缓存管线（缓存路径落 podcast_episode_local_state.cache_path，
 * 文件落 accounts/<server>/<user>/episodes/，对应 iOS CacheFileManager:727-751），
 * 故 iOS PodcastEpisodeUserStatus 的 cached 态在 Android 同样成立
 * （PodcastEpisode.swift:125-139：cached 优先于服务器状态）。
 */
data class PodcastEpisode(
    val id: String,
    val title: String,
    val depiction: String? = null,
    val publishDate: Long = 0,
    val status: PodcastEpisodeRemoteStatus = PodcastEpisodeRemoteStatus.UNDEFINED,
    val streamId: String? = null,
    val duration: Int = 0,
    val coverArt: String? = null,
    val podcastId: String = "",
    val podcastTitle: String = "",
    /** 是否已缓存到本地（= cachePath != null，对应 iOS AbstractPlayable.isCached） */
    val isDownloaded: Boolean = false,
    /** 缓存文件相对 filesDir 的路径，null = 未缓存（唯一缓存判据，与 Song.downloadPath 同口径） */
    val cachePath: String? = null
) {
    /**
     * iOS isAvailableToUser()（PodcastEpisode.swift:125-139）：cached || availableOnServer
     * ——已缓存单集即便服务器侧已删除仍可播放（userStatus 的 cached 态优先）。
     */
    val isAvailableToUser: Boolean
        get() = isDownloaded || status == PodcastEpisodeRemoteStatus.COMPLETED

    /**
     * 不可用时的状态描述（iOS PodcastEpisodeTableCell:100-102 仅在 !isAvailableToUser
     * 时追加 " · <userStatus.description>"）
     */
    val unavailableDescription: String?
        get() = when {
            isAvailableToUser -> null
            status == PodcastEpisodeRemoteStatus.DELETED -> "Deleted on server"
            else -> "Server syncing"
        }
}

/**
 * 转为可播放对象：流 URL 用 streamId ?? id 生成（iOS SubsonicApi.swift:79
 * `let apiId = playableInfo.streamId ?? playableInfo.id`——Subsonic 播客单集专用）；
 * artist 显示播客名（iOS creatorName = podcast?.title）。
 * isPodcastEpisode=true 使 Scrobble 短路（iOS 仅对 Song scrobble）；
 * Batch 4 起自动缓存**不再**短路（对齐 iOS 只排除 radio）。
 * 已缓存单集透传 isDownloaded/downloadPath，PlayerManager.createMediaItem 走本地文件分支。
 */
fun PodcastEpisode.toPlayableWithCredentials(
    credentialsManager: CredentialsManager,
    musicRepository: MediaUrlRepository
): Playable {
    val credentials = credentialsManager.getCredentials()
    val apiId = streamId ?: id
    return Playable(
        id = id,
        title = title,
        artist = podcastTitle,
        album = "",
        duration = duration,
        coverArt = credentials?.let { c ->
            coverArt?.let { musicRepository.getCoverArtUrl(it, c.username, c.password, c.serverUrl) }
        },
        // 单集专用取 URL（Ampache 移植 Batch 2）：Subsonic 侧与歌曲同端点、行为不变；
        // Ampache 侧必须 type=podcast_episode，故走 MediaUrlRepository 的单集方法
        streamUrl = credentials?.let { c ->
            musicRepository.getPodcastEpisodeStreamUrl(apiId, c.username, c.password, c.serverUrl)
        } ?: "",
        isDownloaded = isDownloaded,
        downloadPath = cachePath,
        isPodcastEpisode = true,
        accountId = credentials?.let { AccountInfo.create(it.serverUrl, it.username).ident } ?: ""
    )
}
