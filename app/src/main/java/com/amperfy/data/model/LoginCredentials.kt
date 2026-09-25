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
 * 登录凭证数据类
 * 对应iOS的LoginCredentials结构
 */
data class LoginCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    val passwordHash: String,
    val backendApi: BackendApiType = BackendApiType.NOT_DETECTED
)

/**
 * 后端API类型枚举
 * 对应iOS的BackenApiType
 */
enum class BackendApiType(val value: Int) {
    NOT_DETECTED(0),      // 自动检测
    AMPACHE(1),           // Ampache API
    SUBSONIC(2),          // Subsonic API
    SUBSONIC_LEGACY(3);   // Subsonic Legacy API
    
    companion object {
        fun fromValue(value: Int): BackendApiType {
            return BackendApiType.entries.find { it.value == value } ?: NOT_DETECTED
        }
    }
    
    fun getDisplayName(): String {
        // 文案对齐 iOS BackenApiType.selectorDescription（BackendProxy.swift:49-56）
        return when (this) {
            NOT_DETECTED -> "Auto-Detect"
            AMPACHE -> "Ampache"
            SUBSONIC -> "Subsonic"
            SUBSONIC_LEGACY -> "Subsonic (legacy login)"
        }
    }
}
