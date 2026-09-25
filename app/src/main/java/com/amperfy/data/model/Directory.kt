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
 * 音乐文件夹领域模型 - 对应 iOS: MusicFolder（EntityWrappers/MusicFolder.swift）
 * 列表行仅显示名称（iOS DirectoryTableCell.display(folder:)）
 */
data class MusicFolder(
    val id: String,
    val name: String
)

/**
 * 目录领域模型 - 对应 iOS: Directory（EntityWrappers/Directory.swift，AbstractLibraryEntity 子类）
 * 目录不可收藏/评分（iOS Directory.swift:103-108）
 */
data class Directory(
    val id: String,
    val name: String,
    val coverArt: String? = null
)
