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

import java.security.MessageDigest

/**
 * 账户身份模型（C0 合同，iOS: AmperfyKit/Storage/EntityWrappers/Account.swift createInfo）
 *
 * - 相等性只看 serverHash + userHash（data class 默认语义），apiType 不参与——
 *   同一 server + user 不允许重复添加
 * - [ident] 是跨数据库（accountId）、SharedPreferences（键前缀）、缓存目录、
 *   下载记录的统一账户键
 * - 哈希不可逆：目录/键名中不出现明文 serverUrl/userName
 */
data class AccountInfo(
    val serverHash: String,   // SHA256(serverUrl) 前 8 字节 hex（16 字符）
    val userHash: String,     // SHA256(userName) 前 8 字节 hex（16 字符）
) {
    val ident: String get() = "$serverHash-$userHash"

    companion object {
        fun create(serverUrl: String, userName: String): AccountInfo =
            AccountInfo(
                serverHash = sha256Prefix8(serverUrl),
                userHash = sha256Prefix8(userName)
            )

        private fun sha256Prefix8(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * 账户领域模型（iOS: Account 实体 = url + user + apiType）
 * W5 落地多账户存储后由 AccountManager 从凭证层构建
 */
data class Account(
    val info: AccountInfo,
    val serverUrl: String,
    val userName: String,
    val apiType: BackendApiType,
)
