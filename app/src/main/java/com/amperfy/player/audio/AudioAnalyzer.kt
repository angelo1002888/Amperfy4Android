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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 频谱数据快照（覆盖式发布；W2 用预分配数组避免 GC，UI 只读）
 * 刻意不用 data class：FloatArray 的 equals/hashCode 语义无意义，快照按引用区分。
 */
class Spectrum(
    val magnitudes: FloatArray,   // BIN_COUNT 个 FFT 幅值（0..1 归一化）
    val rms: Float,               // 整体响度（0..1）
)

/**
 * 音频分析器（W2 实现）
 *
 * 写入方：[VisualizerTapProcessor]（音频线程调 [feed]）；
 * 读取方：Compose 可视化视图（W7）。isActive 由 UI 侧门控
 * （可视化开启 && PopupPlayer 可见 && LARGE && isPlaying），
 * inactive 时 processor 直通、分析协程取消——CPU 归零。
 *
 * 线程模型：
 * - 环形缓冲（4096 samples）+ 单调写指针，写入方为音频线程、读取方为 30fps 分析协程。
 *   同步选 synchronized 块而非 lock-free：临界区只有 ≤4096 floats 的 arraycopy（微秒级），
 *   读方每 33ms 才拿一次锁，争用概率可忽略；seqlock/双缓冲换来的无锁收益撑不起
 *   其复杂度与撕裂读校验成本。
 * - Spectrum 发布用**双缓冲幅值数组**交替 + 每帧新建轻量 Spectrum 壳：
 *   Spectrum 故意是普通 class（引用判等），MutableStateFlow 复用同一实例不会发射——
 *   而 rms 是 val 无法原地改，所以壳必须新建（16 字节/帧，30fps 下可忽略）；
 *   两个 128-float 大数组交替复用，既躲开每帧数组分配，又保证 UI 在下一帧
 *   覆写另一块缓冲前有整整 33ms 读走当前快照，不会撕裂。
 */
@Singleton
class AudioAnalyzer @Inject constructor() {

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _spectrum = MutableStateFlow(Spectrum(FloatArray(BIN_COUNT), 0f))
    val spectrum: StateFlow<Spectrum> = _spectrum.asStateFlow()

    fun setActive(active: Boolean) {
        _isActive.value = active
    }

    // ========== W2 实现：环形缓冲 + 30fps FFT 分析协程 ==========

    private val lock = Any()

    /** 单声道 float 环形缓冲（写入方：音频线程） */
    private val ring = FloatArray(RING_SIZE)

    /** 单调写指针（写入的总样本数；% RING_SIZE 得到环内位置） */
    private var totalWritten = 0L

    // Singleton 与进程同生命周期，scope 不需要外部取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var analysisJob: Job? = null

    // ---- FFT 预分配（256 点 radix-2，自实现无三方依赖）----
    private val timeSnapshot = FloatArray(FFT_SIZE)
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)

    /** Hann 窗表 */
    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
    }

    /** 位反转索引表（N=256 → 8 bit 反转；置换是自逆的） */
    private val bitReversal = IntArray(FFT_SIZE) { i ->
        Integer.reverse(i) ushr (Integer.SIZE - FFT_BITS)
    }

    /** 旋转因子表：e^{-j2πk/N}，k ∈ [0, N/2) */
    private val cosTable = FloatArray(FFT_SIZE / 2) { k ->
        cos(2.0 * Math.PI * k / FFT_SIZE).toFloat()
    }
    private val sinTable = FloatArray(FFT_SIZE / 2) { k ->
        kotlin.math.sin(2.0 * Math.PI * k / FFT_SIZE).toFloat()
    }

    /** 双缓冲幅值数组（交替发布，见类注释线程模型） */
    private val magnitudeBuffers = arrayOf(FloatArray(BIN_COUNT), FloatArray(BIN_COUNT))
    private var magnitudeBufferIndex = 0

    init {
        // isActive 变化 → 启停分析协程（启停都在同一 collector 协程内串行执行，无竞态）
        scope.launch {
            _isActive.collect { active ->
                if (active) startAnalysis() else stopAnalysis()
            }
        }
    }

    /**
     * tap 写入（音频线程调用，W2 对 C0 合同的追加公开方法）。
     * count > RING_SIZE 时只保留最后 RING_SIZE 个样本（旧数据本就该被覆盖）。
     */
    fun feed(samples: FloatArray, count: Int) {
        if (count <= 0 || !_isActive.value) return
        val n = min(count, RING_SIZE)
        val srcOffset = count - n
        synchronized(lock) {
            var dst = (totalWritten % RING_SIZE).toInt()
            var copied = 0
            while (copied < n) {
                val len = min(n - copied, RING_SIZE - dst)
                System.arraycopy(samples, srcOffset + copied, ring, dst, len)
                copied += len
                dst = (dst + len) % RING_SIZE
            }
            totalWritten += n
        }
    }

    private fun startAnalysis() {
        if (analysisJob?.isActive == true) return
        // 激活时清掉上一次会话的陈旧样本
        synchronized(lock) {
            ring.fill(0f)
            totalWritten = 0L
        }
        analysisJob = scope.launch {
            // delay 是取消点：stopAnalysis 的 cancel() 在此退出循环
            while (true) {
                analyzeOnce()
                delay(FRAME_INTERVAL_MS)   // ≈30fps
            }
        }
    }

    private fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        // 发布零频谱让 UI 归零（一次性小分配，无 30fps 压力）
        _spectrum.value = Spectrum(FloatArray(BIN_COUNT), 0f)
    }

    /** 取最近 FFT_SIZE 个样本 → Hann 窗 → 256 点 FFT → 128 bin 幅值 + RMS → 覆盖式发布 */
    private fun analyzeOnce() {
        // 快照最近 256 samples（锁内只做两段 arraycopy）
        synchronized(lock) {
            if (totalWritten < FFT_SIZE) return   // 数据不足，本帧跳过
            val start = ((totalWritten - FFT_SIZE) % RING_SIZE).toInt()
            val firstLen = min(FFT_SIZE, RING_SIZE - start)
            System.arraycopy(ring, start, timeSnapshot, 0, firstLen)
            if (firstLen < FFT_SIZE) {
                System.arraycopy(ring, 0, timeSnapshot, firstLen, FFT_SIZE - firstLen)
            }
        }

        // RMS（加窗前的原始时域信号）
        var sumSquares = 0f
        for (i in 0 until FFT_SIZE) {
            val v = timeSnapshot[i]
            sumSquares += v * v
        }
        val rms = sqrt(sumSquares / FFT_SIZE).coerceIn(0f, 1f)

        // Hann 窗 + 位反转序装载实部，虚部清零
        for (i in 0 until FFT_SIZE) {
            re[bitReversal[i]] = timeSnapshot[i] * window[i]
        }
        im.fill(0f)
        fftInPlace()

        // 128 bin 幅值，0..1 归一化：满幅正弦 |X| = N/2 × Hann 相干增益 0.5 = N/4
        val magnitudes = magnitudeBuffers[magnitudeBufferIndex]
        magnitudeBufferIndex = 1 - magnitudeBufferIndex
        val norm = 4f / FFT_SIZE
        for (k in 0 until BIN_COUNT) {
            magnitudes[k] = (sqrt(re[k] * re[k] + im[k] * im[k]) * norm).coerceIn(0f, 1f)
        }
        _spectrum.value = Spectrum(magnitudes, rms)
    }

    /** 迭代式 radix-2 Cooley-Tukey（输入已按位反转序装载） */
    private fun fftInPlace() {
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size shr 1
            val tableStep = FFT_SIZE / size
            var base = 0
            while (base < FFT_SIZE) {
                var k = 0
                for (j in base until base + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    // (re[l] + j·im[l]) × e^{-jθ}
                    val tRe = re[l] * c + im[l] * s
                    val tIm = im[l] * c - re[l] * s
                    re[l] = re[j] - tRe
                    im[l] = im[j] - tIm
                    re[j] += tRe
                    im[j] += tIm
                    k += tableStep
                }
                base += size
            }
            size = size shl 1
        }
    }

    companion object {
        /** FFT 输出 bin 数（256 点 radix-2 FFT → 128 bin，W2 实现） */
        const val BIN_COUNT = 128

        /** 环形缓冲容量（samples，≈93ms @44.1kHz） */
        private const val RING_SIZE = 4096

        /** FFT 点数（2^FFT_BITS） */
        private const val FFT_SIZE = 256
        private const val FFT_BITS = 8

        /** 分析帧间隔（≈30fps） */
        private const val FRAME_INTERVAL_MS = 33L
    }
}
