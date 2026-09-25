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
import com.amperfy.data.model.EqualizerSetting
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10 段均衡器 AudioProcessor（W2）
 *
 * - biquad peaking filter（RBJ Audio EQ Cookbook 公式），中心频率取
 *   [EqualizerSetting.FREQUENCIES]，带宽 1.0 倍频程（Q≈1.41），增益 ±6dB（对齐 iOS AVAudioUnitEQ 配置）
 * - 主路径 16-bit PCM 入出（DefaultAudioSink 默认管线在自定义 processor 前统一转 Int16），
 *   内部 Float 运算；防御性支持 PCM_FLOAT 输入（float 输出管线实际会绕过自定义 processor，
 *   此分支仅为格式合同完整），其他编码抛 UnhandledAudioFormatException
 * - 从 [EqualizerController] 读 isEnabled/gains：StateFlow.value 在音频线程直接读（无挂起、无锁）；
 *   增益引用变化时在音频线程重算系数——10 段三角函数开销可忽略
 *
 * isActive 取舍（重要）：media3 的 AudioProcessingPipeline 只在 configure 时查询 isActive
 * 决定 processor 是否进链，运行期开关均衡器不会触发重新 configure——因此本 processor
 * 只要格式受支持就恒定 active（沿用 BaseAudioProcessor 默认实现），运行期 enabled=false
 * 时在 queueInput 内直通（一次 buffer 拷贝，无滤波运算）。真正的"链路旁路零开销"
 * 只在 configure 时机生效，直通拷贝的代价可接受。
 *
 * 档位切换防爆音：新系数立即生效（滤波器 z 状态保留，系数突变的瞬态极小）；
 * 整体响度跳变由 GainProcessor 侧的 20ms 线性 ramp 兜底，此处不做系数插值。
 */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class EqualizerProcessor @Inject constructor(
    private val controller: EqualizerController,
) : BaseAudioProcessor() {

    /**
     * 一组编译好的滤波器系数（不可变，整体替换）。
     * 只包含增益非零且中心频率低于 Nyquist 的"活跃"频段；
     * [stateSlots] 记录每个活跃频段对应的原始 band 序号，保证增益变化后
     * z 状态仍归属同一频段（状态连续，避免重置引入的瞬态）。
     */
    private class Coefficients(
        @JvmField val b0: FloatArray,
        @JvmField val b1: FloatArray,
        @JvmField val b2: FloatArray,
        @JvmField val a1: FloatArray,
        @JvmField val a2: FloatArray,
        @JvmField val stateSlots: IntArray,
    ) {
        @JvmField val count: Int = stateSlots.size
    }

    // @Volatile 整体替换（合同要求）：写入方与读取方目前都在播放线程，
    // 但 onFlush/queueInput 的调用线程是 media3 内部实现细节，volatile 引用替换
    // 保证任何线程组合下读到的都是一组自洽的系数（绝不逐字段修改）
    @Volatile
    private var coefficients: Coefficients? = null

    /** 上次编译系数时的 gains 引用（仅音频线程读写；引用不同即重算） */
    private var lastGains: List<Float>? = null

    // 当前生效格式（onFlush 时从 inputAudioFormat 刷新；仅音频线程读写）
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    // 每声道×每段的滤波器状态（Transposed Direct Form II），索引 [ch * BAND_COUNT + band]
    private var z1 = FloatArray(0)
    private var z2 = FloatArray(0)

    /** 上一个 buffer 是否处于直通状态——直通→滤波 转换时清零 z 状态，避免陈旧状态引入瞬态 */
    private var wasBypassed = true

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 入出同格式（EQ 不改变采样率/声道/编码）
        return inputAudioFormat
    }

    override fun onFlush() {
        // BaseAudioProcessor.flush() 已把 pending 格式提升为 inputAudioFormat，此时刷新缓存。
        // seek/换格式都会走到这里：重置滤波器状态（丢弃旧音频的余韵），
        // 采样率可能变化 → 置空 lastGains 强制 queueInput 首次调用时重算系数
        if (inputAudioFormat.sampleRate <= 0) return
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        val stateSize = channelCount * EqualizerSetting.BAND_COUNT
        if (z1.size != stateSize) {
            z1 = FloatArray(stateSize)
            z2 = FloatArray(stateSize)
        } else {
            z1.fill(0f)
            z2.fill(0f)
        }
        lastGains = null
        wasBypassed = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // 音频线程直接读 StateFlow.value；增益引用变化 → 重算系数
        val gains = controller.gains.value
        if (gains !== lastGains) {
            coefficients = buildCoefficients(gains, sampleRate)
            lastGains = gains
        }
        val coeffs = coefficients
        val enabled = controller.isEnabled.value

        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining)
        inputBuffer.order(ByteOrder.nativeOrder())

        if (!enabled || coeffs == null || coeffs.count == 0) {
            // 直通：拷贝一次，无滤波运算（isActive 恒 true 的取舍，见类注释）
            output.put(inputBuffer)
            wasBypassed = true
        } else {
            if (wasBypassed) {
                // 直通期间信号未经过滤波器，z 状态与当前信号脱节——清零重来
                z1.fill(0f)
                z2.fill(0f)
                wasBypassed = false
            }
            when (encoding) {
                C.ENCODING_PCM_16BIT -> process16Bit(inputBuffer, output, coeffs)
                C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output, coeffs)
                else -> output.put(inputBuffer) // onConfigure 已挡住，防御性直通
            }
        }
        output.flip()
    }

    /** 16-bit PCM 路径：little-endian short 逐样本读 → Float 滤波 → clamp 回 short */
    private fun process16Bit(input: ByteBuffer, output: ByteBuffer, coeffs: Coefficients) {
        var pos = input.position()
        val limit = input.limit()
        var ch = 0
        while (pos + 1 < limit) {
            var x = input.getShort(pos) * SHORT_TO_FLOAT
            x = applyBands(x, ch, coeffs)
            val scaled = x * FLOAT_TO_SHORT
            val clamped = if (scaled > 32767f) 32767 else if (scaled < -32768f) -32768 else scaled.toInt()
            output.putShort(clamped.toShort())
            pos += 2
            ch++
            if (ch == channelCount) ch = 0
        }
        input.position(limit)
    }

    /** PCM_FLOAT 路径：直接 float 运算（4 字节/样本） */
    private fun processFloat(input: ByteBuffer, output: ByteBuffer, coeffs: Coefficients) {
        var pos = input.position()
        val limit = input.limit()
        var ch = 0
        while (pos + 3 < limit) {
            val x = applyBands(input.getFloat(pos), ch, coeffs)
            output.putFloat(x)
            pos += 4
            ch++
            if (ch == channelCount) ch = 0
        }
        input.position(limit)
    }

    /** 级联所有活跃频段（Transposed Direct Form II biquad） */
    private fun applyBands(sample: Float, ch: Int, coeffs: Coefficients): Float {
        var x = sample
        val chBase = ch * EqualizerSetting.BAND_COUNT
        for (j in 0 until coeffs.count) {
            val s = chBase + coeffs.stateSlots[j]
            val y = coeffs.b0[j] * x + z1[s]
            z1[s] = coeffs.b1[j] * x - coeffs.a1[j] * y + z2[s]
            z2[s] = coeffs.b2[j] * x - coeffs.a2[j] * y
            x = y
        }
        return x
    }

    /**
     * 按 RBJ Audio EQ Cookbook 的 peaking EQ 公式编译系数（Double 精度计算，Float 存储）。
     * 跳过增益≈0 的频段（省级联开销）与中心频率逼近/超过 Nyquist 的频段（低采样率防失稳）。
     */
    private fun buildCoefficients(gains: List<Float>, sampleRate: Int): Coefficients? {
        if (sampleRate <= 0 || gains.size != EqualizerSetting.BAND_COUNT) return null
        val nyquistGuard = sampleRate * 0.45
        val b0 = ArrayList<Float>(EqualizerSetting.BAND_COUNT)
        val b1 = ArrayList<Float>(EqualizerSetting.BAND_COUNT)
        val b2 = ArrayList<Float>(EqualizerSetting.BAND_COUNT)
        val a1 = ArrayList<Float>(EqualizerSetting.BAND_COUNT)
        val a2 = ArrayList<Float>(EqualizerSetting.BAND_COUNT)
        val slots = ArrayList<Int>(EqualizerSetting.BAND_COUNT)
        for (band in 0 until EqualizerSetting.BAND_COUNT) {
            val gainDb = gains[band]
                .coerceIn(-EqualizerSetting.GAIN_LIMIT_DB, EqualizerSetting.GAIN_LIMIT_DB)
                .toDouble()
            if (abs(gainDb) < 0.01) continue
            val f0 = EqualizerSetting.FREQUENCIES[band].toDouble()
            if (f0 >= nyquistGuard) continue
            // RBJ peaking：A = 10^(dB/40)，alpha = sin(w0)/(2Q)
            val bigA = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * f0 / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * Q_FACTOR)
            val a0 = 1.0 + alpha / bigA
            b0.add(((1.0 + alpha * bigA) / a0).toFloat())
            b1.add((-2.0 * cosW0 / a0).toFloat())
            b2.add(((1.0 - alpha * bigA) / a0).toFloat())
            a1.add((-2.0 * cosW0 / a0).toFloat())
            a2.add(((1.0 - alpha / bigA) / a0).toFloat())
            slots.add(band)
        }
        return Coefficients(
            b0.toFloatArray(),
            b1.toFloatArray(),
            b2.toFloatArray(),
            a1.toFloatArray(),
            a2.toFloatArray(),
            slots.toIntArray(),
        )
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        z1 = FloatArray(0)
        z2 = FloatArray(0)
        coefficients = null
        lastGains = null
        wasBypassed = true
    }

    companion object {
        /** 带宽 1.0 倍频程 ≈ Q 1.41（对齐 iOS AVAudioUnitEQ bandwidth=1.0） */
        private const val Q_FACTOR = 1.41

        private const val SHORT_TO_FLOAT = 1f / 32768f
        private const val FLOAT_TO_SHORT = 32768f
    }
}
