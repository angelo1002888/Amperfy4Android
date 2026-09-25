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

package com.amperfy.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 本地数据库租户根节点。
 *
 * 仅作为账户级数据的租户根，不保存 URL、用户名或 API 类型，不参与账户列表展示——
 * 账户资料唯一来源仍是 CredentialsManager。所有账户级表通过 account_id 关联本表并
 * 使用 ON DELETE CASCADE，登出时删除一个 scope 即可级联清理该账户全部数据。
 */
@Entity(tableName = "account_scope")
data class AccountScopeEntity(
    @PrimaryKey
    @ColumnInfo(name = "account_id")
    val accountId: String,
)
