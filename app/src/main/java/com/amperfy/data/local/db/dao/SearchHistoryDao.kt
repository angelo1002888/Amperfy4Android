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
import com.amperfy.data.local.db.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * search_history DAO。
 *
 * upsert 覆写同一 (account_id, type, entity_id)（再次点击更新 searched_at）；observeAll 按
 * searched_at 倒序；deleteAll 供“清空历史”，带 account_id。
 */
@Dao
interface SearchHistoryDao {
    /** 写入/更新一条搜索历史（再次点击更新时间）。 */
    @Upsert
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history WHERE account_id = :accountId ORDER BY searched_at DESC")
    fun observeAll(accountId: String): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE account_id = :accountId")
    suspend fun deleteAll(accountId: String)
}
