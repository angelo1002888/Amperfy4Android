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
 * 均衡器档位（C0 合同，冻结；iOS: AmperfyKit/Storage/SettingEnumerations.swift EqualizerSetting）
 *
 * 持久化（SettingsManager，键名冻结）：
 * - `equalizerSettings`：Gson JSON 数组 `[{id, name, gains[10]}]`，id 为 UUID 字符串
 * - `activeEqualizerSettingId`：String，空 = Off
 * - `isEqualizerEnabled`：Boolean（键已存在）
 */
data class EqualizerSetting(
    val id: String,
    val name: String,
    val gains: List<Float>,   // 10 段，单位 dB，范围 ±GAIN_LIMIT_DB
) {
    /**
     * 正增益补偿（对齐 iOS）：档位含正增益时整体过响，输出端按
     * 「正增益平均值的一半（上限 6 dB）」做反向衰减——由 GainProcessor 消费。
     */
    val gainCompensation: Float
        get() {
            val positiveGains = gains.filter { it > 0f }
            if (positiveGains.isEmpty()) return 0f
            return (positiveGains.average().toFloat() / 2f).coerceAtMost(GAIN_LIMIT_DB)
        }

    companion object {
        /** 10 段中心频率（Hz），对齐 iOS AVAudioUnitEQ 配置 */
        val FREQUENCIES = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        const val BAND_COUNT = 10
        const val GAIN_LIMIT_DB = 6f

        val FLAT_GAINS: List<Float> = List(BAND_COUNT) { 0f }
    }
}
