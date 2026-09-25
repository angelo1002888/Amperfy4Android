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
 * 下载管理页单条记录行（领域模型，专题 15 P1：数据库实体不泄漏到 UI 层）
 *
 * 对应 iOS: PlayableTableCell + Download 状态附件（DownloadMO）。
 * 由 DownloadLocalStore 批量装配（download 条目 + 实体一次 IN 查询），
 * DownloadsViewModel/DownloadsScreen 只消费本类型。
 *
 * Batch 4：下载对象泛化到播客单集（iOS DownloadMO 本就以 AbstractPlayable 为对象）——
 * [song] / [episode] **恰有一个非空**，由装配侧按 download_entry.entity_type 决定。
 */
data class DownloadItem(
    val song: Song? = null,
    val episode: PodcastEpisode? = null,
    /** 下载中/等待中（accessory = 进度环；iOS 为不定 spinner，已知差异） */
    val isDownloading: Boolean,
    /** 已成功完成（accessory = 对勾，iOS download.isFinishedSuccessfully） */
    val isFinished: Boolean,
    /** 失败或已取消（accessory = 感叹号，iOS download.error != nil） */
    val isError: Boolean
) {
    /** 下载对象类型（与 download_entry.entity_type 同源） */
    val entityType: DownloadEntityType
        get() = if (episode != null) DownloadEntityType.PODCAST_EPISODE else DownloadEntityType.SONG

    /** 服务端实体 id（列表 key / 进度查询用） */
    val id: String
        get() = song?.id ?: episode?.id ?: ""

    /** 行标题（日志用；UI 由各自的行组件渲染） */
    val title: String
        get() = song?.title ?: episode?.title ?: ""
}
