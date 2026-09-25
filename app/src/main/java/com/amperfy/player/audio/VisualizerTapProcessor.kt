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

/**
 * 可视化 tap AudioProcessor（W2）
 *
 * 纯 tap：输入 PCM 永远原样直通输出，不做任何修改。
 * [AudioAnalyzer.isActive] == true 时把输入混为单声道 float 喂给 analyzer 的
 * 环形缓冲（4096 samples，缓冲区归 AudioAnalyzer 所有，本 processor 只调 feed()）；
 * inactive 时只做一次直通 buffer 拷贝、无混音无分析开销。
 *
 * 位于链路末端（EQ → Gain → 本 tap），采到的是均衡+增益之后的最终信号（对齐 iOS：
 * 可视化反映均衡后的声音）。
 *
 * 直通说明：BaseAudioProcessor 契约要求把输出写入自有 buffer，"零拷贝"在此框架下
 * 实际为一次 ByteBuffer 块拷贝（无逐样本运算）；真正把 processor 摘出链路只能靠
 * configure 时 isActive=false，而 pipeline 不会因运行期开关可视化而重新 configure，
 * 故与 EQ/Gain 相同取舍——恒定 active + 内部直通。
 */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class VisualizerTapProcessor @Inject constructor(
    private val analyzer: AudioAnalyzer,
) : BaseAudioProcessor() {

    // 当前生效格式（onFlush 时从 inputAudioFormat 刷新；仅音频线程读写）
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    /** 混单声道复用暂存（按需扩容，避免每 buffer 分配） */
    private var monoScratch = FloatArray(0)

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 纯 tap，入出同格式
        return inputAudioFormat
    }

    override fun onFlush() {
        if (inputAudioFormat.sampleRate <= 0) return
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining)

        // 采样在直通拷贝之前用绝对索引读取（不动 position），随后整块 put 直通
        if (analyzer.isActive.value && channelCount > 0) {
            inputBuffer.order(ByteOrder.nativeOrder())
            when (encoding) {
                C.ENCODING_PCM_16BIT -> tap16Bit(inputBuffer)
                C.ENCODING_PCM_FLOAT -> tapFloat(inputBuffer)
            }
        }
        output.put(inputBuffer)
        output.flip()
    }

    /** 16-bit：逐帧各声道求平均混为单声道 float（[-1,1]），写入复用暂存后喂 analyzer */
    private fun tap16Bit(input: ByteBuffer) {
        val frameBytes = channelCount * 2
        val frames = input.remaining() / frameBytes
        if (frames == 0) return
        ensureScratch(frames)
        var pos = input.position()
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channelCount) {
                sum += input.getShort(pos) * SHORT_TO_FLOAT
                pos += 2
            }
            monoScratch[i] = sum / channelCount
        }
        analyzer.feed(monoScratch, frames)
    }

    private fun tapFloat(input: ByteBuffer) {
        val frameBytes = channelCount * 4
        val frames = input.remaining() / frameBytes
        if (frames == 0) return
        ensureScratch(frames)
        var pos = input.position()
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channelCount) {
                sum += input.getFloat(pos)
                pos += 4
            }
            monoScratch[i] = sum / channelCount
        }
        analyzer.feed(monoScratch, frames)
    }

    private fun ensureScratch(frames: Int) {
        if (monoScratch.size < frames) monoScratch = FloatArray(frames)
    }

    override fun onReset() {
        channelCount = 0
        encoding = C.ENCODING_INVALID
        monoScratch = FloatArray(0)
    }

    companion object {
        private const val SHORT_TO_FLOAT = 1f / 32768f
    }
}
