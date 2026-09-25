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

package com.amperfy.player.audio

import com.amperfy.data.model.EqualizerSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 均衡器控制器（C0 骨架，W2 实现 EqualizerProcessor 消费）
 *
 * 写入方：SettingsManager 订阅（W2 接线）；读取方：EqualizerProcessor。
 * 这是 processor 与外界的唯一通信面——UI/设置层不得直接触碰音频链。
 */
@Singleton
class EqualizerController @Inject constructor() {

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _gains = MutableStateFlow(EqualizerSetting.FLAT_GAINS)
    /** 10 段增益（dB，±[EqualizerSetting.GAIN_LIMIT_DB]） */
    val gains: StateFlow<List<Float>> = _gains.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    fun setGains(gains: List<Float>) {
        require(gains.size == EqualizerSetting.BAND_COUNT) {
            "Expected ${EqualizerSetting.BAND_COUNT} bands, got ${gains.size}"
        }
        _gains.value = gains
    }
}
