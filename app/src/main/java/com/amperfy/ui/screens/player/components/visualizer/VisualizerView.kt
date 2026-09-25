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

package com.amperfy.ui.screens.player.components.visualizer

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amperfy.data.model.VisualizerType
import com.amperfy.player.audio.Spectrum

/**
 * 音频可视化容器（对应 iOS AudioAnalyzerView）：按 [type] 分派到 4 种视图，
 * 数据来自 [spectrum]（AudioAnalyzer 的 Spectrum 快照，UI 侧 collectAsState 后传入）。
 *
 * iOS 在 AudioAnalyzerView 外层 `.padding()`，此处保留 16dp 内边距对齐。
 * isActive 门控由调用方（PopupPlayerScreen）经 DisposableEffect 管理，此视图只负责绘制。
 */
@Composable
fun VisualizerView(
    type: VisualizerType,
    spectrum: Spectrum,
    modifier: Modifier = Modifier,
) {
    val content = Modifier.padding(16.dp)
    when (type) {
        VisualizerType.RING -> RingVisualizer(
            magnitudes = spectrum.magnitudes,
            rms = spectrum.rms,
            modifier = modifier.then(content),
        )
        VisualizerType.WAVEFORM -> WaveformVisualizer(
            magnitudes = spectrum.magnitudes,
            rms = spectrum.rms,
            modifier = modifier.then(content),
        )
        VisualizerType.SPECTRUM_BARS -> SpectrumBarsVisualizer(
            magnitudes = spectrum.magnitudes,
            modifier = modifier.then(content),
        )
        VisualizerType.GENERATIVE_ART -> GenerativeArtVisualizer(
            magnitudes = spectrum.magnitudes,
            rms = spectrum.rms,
            modifier = modifier.then(content),
        )
    }
}
