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

package com.amperfy.data.remote.ampache

/**
 * Ampache 握手响应里 `<api>` 版本串的折算（Batch 2）。
 *
 * **两种历史形态**：
 * - 旧整数串：`"420000"` / `"350001"`——本身就是「客户端 API 版本」量纲；
 * - 语义版本串：`"5.5.6"` / `"6.0.0"`——Ampache 5.x 起服务器回的是这个。
 *
 * 折算口径：`X.Y.Z` → `X*100000 + Y*10000 + Z*1000`
 * （`"5.5.6"`→556000、`"6.0.0"`→600000、`"4.2.0"`→420000），与客户端
 * [AmpacheAuthSession.CLIENT_API_VERSION]（"500000" = 5.0.0）同量纲，可直接比大小。
 *
 * **与 iOS 2.1.0 的刻意差异**：
 * iOS `requestServerPodcastSupport` 直接 `Int(serverApiVersion)`，对 `"5.5.6"` 得 nil
 * → 恒判「不支持播客」，属 iOS 自身缺陷；Android 按协议实现，两种形态都能判。
 */
internal object AmpacheApiVersion {

    /** 一个语义版本段的权重基数：major ×100000、minor ×10000、patch ×1000 */
    private const val MAJOR_WEIGHT = 100_000
    private const val MINOR_WEIGHT = 10_000
    private const val PATCH_WEIGHT = 1_000

    /** 整数形态的最短位数：Ampache 的整数版本恒为 6 位（350001 / 420000 / 500000） */
    private const val RAW_INT_MIN_DIGITS = 6

    /**
     * 把 `<api>` 串折算成可比较的整数；空串/无法识别返回 null（调用方按「不支持」处理）。
     *
     * 判别顺序：
     * 1. 含 `.` → 按语义版本折算（多余段忽略，任一段非数字即 null）；
     * 2. 纯数字且位数 ≥ [RAW_INT_MIN_DIGITS] → 就是旧整数形态，原样取值；
     * 3. 纯数字但位数不足 → 视为只给了主版本号（`"5"` = 5.0.0）→ ×100000；
     * 4. 其余 → null。
     */
    fun parse(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        if (!text.contains('.')) {
            val value = text.toIntOrNull() ?: return null
            if (value < 0) return null
            return if (text.length >= RAW_INT_MIN_DIGITS) value else value * MAJOR_WEIGHT
        }

        val segments = text.split('.')
        val major = segments.getOrNull(0)?.toIntOrNull() ?: return null
        // 缺省段按 0 处理（"5.5" = 5.5.0）；给了却非数字则整体判非法
        val minor = segments.getOrNull(1)?.let { it.toIntOrNull() ?: return null } ?: 0
        val patch = segments.getOrNull(2)?.let { it.toIntOrNull() ?: return null } ?: 0
        if (major < 0 || minor < 0 || patch < 0) return null
        return major * MAJOR_WEIGHT + minor * MINOR_WEIGHT + patch * PATCH_WEIGHT
    }
}
