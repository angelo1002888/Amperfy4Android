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

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow

/**
 * 增益 AudioProcessor（W2）
 *
 * 对每个 sample 乘 linearGain：
 *   linearGain = (RG 开启且曲目带标签 ? 10^(trackGainDb/20) : 1) × 10^(-eqCompensationDb/20)
 * 从 [GainController] 的 StateFlow.value 在音频线程直接读。
 *
 * - 目标增益变化（切歌 / RG 开关 / EQ 档位切换）时做 20ms 线性 ramp（按 sampleRate
 *   折算帧数、逐帧插值，帧内各声道用同一增益）——防爆音，同时为 EqualizerProcessor
 *   的系数突变兜底整体响度跳变
 * - 软限幅（iOS 未做，Android 补上）：trackPeak 非空且 linearGain × trackPeak > 1 时
 *   把增益压到 0.98/trackPeak，避免正增益 ReplayGain 标签导致削波
 * - 16-bit 与 PCM_FLOAT 双支持（主路径 16-bit，见 EqualizerProcessor 类注释）；
 *   16-bit 出口 clamp 到 Short 范围
 *
 * isActive 取舍同 EqualizerProcessor：pipeline 只在 configure 时查询 isActive，
 * 运行期增益=1 无法把 processor 摘出链路——恒定 active，增益为 1 且无 ramp 时
 * queueInput 内直通拷贝。
 */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class GainProcessor @Inject constructor(
    private val controller: GainController,
) : BaseAudioProcessor() {

    // 当前生效格式（onFlush 时从 inputAudioFormat 刷新；仅音频线程读写）
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    /** 20ms ramp 对应的帧数（onFlush 按 sampleRate 折算） */
    private var rampTotalFrames = 1

    private var currentGain = 1f
    private var targetGain = 1f
    private var rampStep = 0f
    private var rampRemainingFrames = 0

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        if (inputAudioFormat.sampleRate <= 0) return
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        rampTotalFrames = (inputAudioFormat.sampleRate * RAMP_DURATION_MS / 1000).coerceAtLeast(1)
        // seek/换轨起点：直接跳到目标增益，不 ramp（避免从上一曲的增益滑过来）
        currentGain = computeTargetGain()
        targetGain = currentGain
        rampRemainingFrames = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // 每个 buffer 读一次目标增益（StateFlow.value，音频线程无锁读）
        val target = computeTargetGain()
        if (target != targetGain) {
            targetGain = target
            rampRemainingFrames = rampTotalFrames
            rampStep = (target - currentGain) / rampTotalFrames
        }

        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining)
        inputBuffer.order(ByteOrder.nativeOrder())

        // channelCount 守卫为防御性代码：契约上 queueInput 只会在 configure+flush 之后调用
        if (channelCount <= 0 ||
            (rampRemainingFrames == 0 && abs(currentGain - 1f) < GAIN_EPSILON)
        ) {
            // 单位增益且无 ramp 在途：直通拷贝（isActive 恒 true 的取舍，见类注释）
            output.put(inputBuffer)
        } else {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> process16Bit(inputBuffer, output)
                C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output)
                else -> output.put(inputBuffer) // onConfigure 已挡住，防御性直通
            }
        }
        output.flip()
    }

    /** 逐帧推进 ramp，帧内各声道乘同一增益；16-bit 出口 clamp 到 Short 范围 */
    private fun process16Bit(input: ByteBuffer, output: ByteBuffer) {
        var pos = input.position()
        val limit = input.limit()
        val frameBytes = channelCount * 2
        while (pos + frameBytes <= limit) {
            advanceRamp()
            for (c in 0 until channelCount) {
                val scaled = input.getShort(pos) * currentGain
                val clamped = if (scaled > 32767f) 32767 else if (scaled < -32768f) -32768 else scaled.toInt()
                output.putShort(clamped.toShort())
                pos += 2
            }
        }
        input.position(limit)
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer) {
        var pos = input.position()
        val limit = input.limit()
        val frameBytes = channelCount * 4
        while (pos + frameBytes <= limit) {
            advanceRamp()
            for (c in 0 until channelCount) {
                output.putFloat(input.getFloat(pos) * currentGain)
                pos += 4
            }
        }
        input.position(limit)
    }

    /** 每帧一步线性插值；走完 ramp 后精确落到目标值（消除浮点累计误差） */
    private fun advanceRamp() {
        if (rampRemainingFrames > 0) {
            currentGain += rampStep
            rampRemainingFrames--
            if (rampRemainingFrames == 0) currentGain = targetGain
        }
    }

    /**
     * 目标线性增益：ReplayGain（开启且有标签）× EQ 正增益补偿（反向衰减）＋trackPeak 软限幅。
     * 全部从 GainController 读——写入方是 AudioChainCoordinator（主/Default 线程），
     * StateFlow.value 的 volatile 语义保证音频线程读到最新值。
     */
    private fun computeTargetGain(): Float {
        val trackGainDb = if (controller.isReplayGainEnabled.value) controller.trackGainDb.value else null
        var gain = if (trackGainDb != null) 10f.pow(trackGainDb / 20f) else 1f
        gain *= 10f.pow(-controller.eqCompensationDb.value / 20f)
        // 软限幅（iOS 未做）：防正增益把峰值推过 0dBFS 削波
        val peak = controller.trackPeak.value
        if (peak != null && peak > 0f && gain * peak > 1f) {
            gain = SOFT_LIMIT_CEILING / peak
        }
        return gain.coerceAtLeast(0f)
    }

    override fun onReset() {
        channelCount = 0
        encoding = C.ENCODING_INVALID
        currentGain = 1f
        targetGain = 1f
        rampRemainingFrames = 0
    }

    companion object {
        /** 目标增益变化时的线性 ramp 时长（防爆音） */
        private const val RAMP_DURATION_MS = 20

        /** 软限幅目标峰值（留 ~0.2dB 余量） */
        private const val SOFT_LIMIT_CEILING = 0.98f

        /** 增益与 1.0 的差小于该值视为单位增益（直通） */
        private const val GAIN_EPSILON = 1e-4f
    }
}
