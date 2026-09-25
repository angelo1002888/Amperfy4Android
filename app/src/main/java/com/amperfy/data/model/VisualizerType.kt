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
 * 音频可视化样式（C0 合同，冻结；iOS: SettingEnumerations.swift:366 VisualizerType）
 *
 * iOS 为 String rawValue——持久化必须存显式 [value] 而非 Kotlin enum name
 * （否则存成 "RING" 与 iOS 的 "ring" 不一致）。
 */
enum class VisualizerType(val value: String, val displayName: String) {
    RING("ring", "Ring"),
    WAVEFORM("waveform", "Waveform"),
    SPECTRUM_BARS("spectrumBars", "Spectrum Bars"),
    GENERATIVE_ART("generativeArt", "Generative Art");

    companion object {
        fun fromValue(v: String): VisualizerType = entries.firstOrNull { it.value == v } ?: RING
    }
}
