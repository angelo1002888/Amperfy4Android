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

package com.amperfy.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.amperfy.data.local.db.entity.AccountScopeEntity

/**
 * account_scope 租户根 DAO。
 *
 * 登出时 deleteByAccountId 触发 ON DELETE CASCADE，级联清理该账户全部账户级表数据。
 */
@Dao
interface AccountScopeDao {
    @Upsert
    suspend fun upsert(scope: AccountScopeEntity)

    @Query("SELECT * FROM account_scope")
    suspend fun getAll(): List<AccountScopeEntity>

    @Query("DELETE FROM account_scope WHERE account_id = :accountId")
    suspend fun deleteByAccountId(accountId: String)
}
