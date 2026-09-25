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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 增益控制器（C0 骨架，W2 实现 GainProcessor 消费）
 *
 * 写入方：W2 内部协程订阅 PlayerManager.currentSong StateFlow（不改 PlayerManager.kt）
 * 喂 ReplayGain；EQ 档位变化喂 eqCompensationDb。读取方：GainProcessor
 * （linearGain = (RG 开启 ? 10^(trackGainDb/20) : 1) × 10^(-eqCompensationDb/20)，
 * trackPeak 软限幅——公式实现归 W2）。
 */
@Singleton
class GainController @Inject constructor() {

    private val _isReplayGainEnabled = MutableStateFlow(false)
    val isReplayGainEnabled: StateFlow<Boolean> = _isReplayGainEnabled.asStateFlow()

    private val _trackGainDb = MutableStateFlow<Float?>(null)
    /** 当前曲目 ReplayGain track gain（dB）；null = 无标签，不调整 */
    val trackGainDb: StateFlow<Float?> = _trackGainDb.asStateFlow()

    private val _trackPeak = MutableStateFlow<Float?>(null)
    /** 当前曲目 track peak（线性 0..1+）；用于软限幅 */
    val trackPeak: StateFlow<Float?> = _trackPeak.asStateFlow()

    private val _eqCompensationDb = MutableStateFlow(0f)
    /** 均衡器正增益补偿（dB，反向衰减；见 EqualizerSetting.gainCompensation） */
    val eqCompensationDb: StateFlow<Float> = _eqCompensationDb.asStateFlow()

    fun setReplayGainEnabled(enabled: Boolean) {
        _isReplayGainEnabled.value = enabled
    }

    fun setTrackGain(gainDb: Float?, peak: Float?) {
        _trackGainDb.value = gainDb
        _trackPeak.value = peak
    }

    fun setEqCompensation(db: Float) {
        _eqCompensationDb.value = db
    }
}
