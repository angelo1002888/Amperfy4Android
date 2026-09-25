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

package com.amperfy.player

/**
 * 播放器队列的三个 section（iOS: PlayerQueueType，PlayerUtil.swift:177-191）
 *
 * iOS 的 rawValue 是 TableView 的 section 序号（prev=0 / user=2 / next=3，
 * 1 号 section 是 Currently Playing 行，不可作为拖拽落点），Android 侧不需要该序号，
 * 仅保留三个语义常量。
 */
enum class PlayerQueueType {
    /** "Previous" 段 */
    PREV,

    /** "Next in Queue" 段（User Queue） */
    USER,

    /** "Next From" 段（生效上下文队列的剩余部分） */
    NEXT
}

/**
 * 播放器队列内的「段 + 段内索引」定位（iOS: PlayerIndex，PlayerUtil.swift:195-212）
 *
 * 对应 iOS 的 IndexPath(row: index, section: queueType.rawValue)——
 * 跨 section 拖动提交（[PlayerManager.movePlayable]）的唯一坐标形式。
 */
data class PlayerIndex(
    val queueType: PlayerQueueType,
    val index: Int
)
