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
 * 队列循环模式（对应 iOS RepeatMode，PlayerUtil.swift:56-100）。
 *
 * rawValue 与 iOS 完全一致（off=0 / all=1 / single=2），直接落库到
 * playback_state.repeat_setting（对照 iOS PlayerMO.repeatSetting）。
 *
 * 语义（iOS AudioPlayer.swift:127-143 / 195-224）：
 * - [OFF]：队列播完即停；
 * - [ALL]：队尾自然播完绕回队首；手动上一曲在队首时跳到队尾；
 * - [SINGLE]：当前曲自然播完重播自身（手动切歌不受影响）。
 */
enum class RepeatMode(val rawValue: Int) {
    OFF(0),
    ALL(1),
    SINGLE(2);

    /** 按钮点击的循环顺序（iOS: RepeatMode.nextMode，off → all → single → off） */
    val nextMode: RepeatMode
        get() = when (this) {
            OFF -> ALL
            ALL -> SINGLE
            SINGLE -> OFF
        }

    companion object {
        /** 由持久化 rawValue 还原；越界值回退 [OFF]（对照 iOS `RepeatMode(rawValue:) ?? .off`） */
        fun fromRaw(raw: Int): RepeatMode = entries.firstOrNull { it.rawValue == raw } ?: OFF
    }
}
