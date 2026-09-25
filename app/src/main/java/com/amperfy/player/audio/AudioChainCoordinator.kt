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

import com.amperfy.data.local.SettingsManager
import com.amperfy.data.model.EqualizerSetting
import com.amperfy.player.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音频链接线协调器（W2）
 *
 * 唯一职责：把设置层（SettingsManager）与播放层（PlayerManager.currentSong）的状态
 * 翻译成 EqualizerController / GainController 的输入——processor 与外界的唯一通信面。
 * 只订阅 PlayerManager 的 StateFlow，绝不反向触碰播放逻辑。
 *
 * 由 AmperfyApplication.onCreate 注入并 start()（对应 iOS AppDelegate 启动期装配音频节点链）。
 */
@Singleton
class AudioChainCoordinator @Inject constructor(
    private val settingsManager: SettingsManager,
    private val playerManager: PlayerManager,
    private val equalizerController: EqualizerController,
    private val gainController: GainController,
) {

    // Singleton 与进程同生命周期，scope 不需要外部取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var started = false

    fun start() {
        if (started) return
        started = true

        // a) EQ 设置 → EqualizerController + GainController.eqCompensation
        //    三个设置流 combine：任一变化（总开关/切档/档位列表编辑）都重新解析当前档位
        scope.launch {
            combine(
                settingsManager.isEqualizerEnabled,
                settingsManager.activeEqualizerSettingId,
                settingsManager.equalizerSettings,
            ) { enabled, activeId, settings ->
                Triple(enabled, activeId, settings)
            }.collect { (enabled, activeId, settings) ->
                val active = settings.firstOrNull { it.id == activeId }
                    // 防御：持久化 JSON 异常导致段数不对时按无档位处理
                    ?.takeIf { it.gains.size == EqualizerSetting.BAND_COUNT }
                // activeId 为空（Off）或找不到档位时，即使总开关开着也视为 EQ 关闭
                val effectiveEnabled = enabled && active != null
                equalizerController.setGains(active?.gains ?: EqualizerSetting.FLAT_GAINS)
                equalizerController.setEnabled(effectiveEnabled)
                // EQ 正增益补偿（对齐 iOS）：EQ 关或无档位时归零
                gainController.setEqCompensation(if (effectiveEnabled) active!!.gainCompensation else 0f)
            }
        }

        // b) ReplayGain 总开关 → GainController
        scope.launch {
            settingsManager.isReplayGainEnabled.collect { enabled ->
                gainController.setReplayGainEnabled(enabled)
            }
        }

        // c) 当前曲目 → ReplayGain 标签（C0 已在 Playable 透传 4 字段；只用 trackGain/trackPeak，
        //    albumGain 入库备用，对齐 iOS 现状）。切歌/清空播放器都会走到这里，
        //    null 曲目 → 增益归 1（GainProcessor 侧 20ms ramp 防爆音）
        scope.launch {
            playerManager.currentSong.collect { song ->
                gainController.setTrackGain(song?.replayGainTrackGain, song?.replayGainTrackPeak)
            }
        }
    }
}
