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
import com.amperfy.data.local.db.entity.GenreEntity
import kotlinx.coroutines.flow.Flow

/**
 * genre 远端快照 DAO。
 *
 * 身份键为 name（Subsonic 无流派 id），故 getByName 以 name 定位，
 * 排序 tie-break 亦用 name。
 */
@Dao
interface GenreDao {
    /** 远端快照全行 upsert（Remote 后缀强制）。 */
    @Upsert
    suspend fun upsertRemote(rows: List<GenreEntity>)

    @Query(
        "SELECT * FROM genre WHERE account_id = :accountId " +
            "ORDER BY sort_key COLLATE BINARY, name",
    )
    fun observeAll(accountId: String): Flow<List<GenreEntity>>

    @Query("SELECT * FROM genre WHERE account_id = :accountId AND name = :name")
    suspend fun getByName(accountId: String, name: String): GenreEntity?

    // ==================== P3 批次 1a 扩充 ====================

    /** 观察单个流派（name 为身份键，主键直查）。 */
    @Query("SELECT * FROM genre WHERE account_id = :accountId AND name = :name")
    fun observeByName(accountId: String, name: String): Flow<GenreEntity?>

    /**
     * 整表替换流派的差集删除（对照 PodcastEpisodeDao.markDeletedMissing 的 NOT IN 模式）：
     * 删除本账户下不在 keepNames 内的流派。keepNames 为空 = 全删（NOT IN 空表语义，P2 已验证）。
     */
    @Query("DELETE FROM genre WHERE account_id = :accountId AND name NOT IN (:keepNames)")
    suspend fun deleteMissingByName(accountId: String, keepNames: List<String>)

    /** 随机流派（Home 用）。 */
    @Query("SELECT * FROM genre WHERE account_id = :accountId ORDER BY RANDOM() LIMIT :count")
    suspend fun randomRows(accountId: String, count: Int): List<GenreEntity>
}
