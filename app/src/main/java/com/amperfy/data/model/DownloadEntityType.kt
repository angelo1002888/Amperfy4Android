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
 * 下载对象类型（Batch 4）——对应 download_entry.entity_type 列的取值域。
 *
 * iOS 侧 DownloadMO 的下载对象为 AbstractPlayable（Song / PodcastEpisode 共用一条下载管线，
 * 见 CacheFileManager.createRelPath:727-751 按 isSong 分叉 songs//episodes/ 目录）；
 * Android 的 download_entry 主键含 entity_type 以区分两个 id 命名空间。
 *
 * **枚举 name 即持久化字符串**（"SONG" / "PODCAST_EPISODE"），与实体列默认值 "SONG" 一致，
 * 故重命名常量等价于改库内数据，禁止。
 */
enum class DownloadEntityType {
    SONG,
    PODCAST_EPISODE;

    companion object {
        /** 列值 → 枚举（未知值回落 SONG，与列默认值同口径） */
        fun fromRaw(raw: String?): DownloadEntityType =
            entries.firstOrNull { it.name == raw } ?: SONG
    }
}

/**
 * 下载记录的类型化身份引用（启动恢复 / Retry failed 的查询结果元素）。
 *
 * download_entry 的 song_id 列在两个类型下分别是歌曲 id 与单集 id，
 * 单独一个 id 不足以定位实体，故一律与 [entityType] 成对流转。
 */
data class DownloadRef(
    val entityType: DownloadEntityType,
    val id: String,
)
