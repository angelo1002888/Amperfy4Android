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

package com.amperfy.data.local.store

import com.amperfy.data.model.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * 搜索历史的持久化边界（专题 15 P1 批次 3）
 *
 * MusicRepositoryImpl 搜索历史三方法的数据库访问收口于此，只出入领域模型/标量，
 * 不出现数据库类型。全部方法带 accountId 隔离键（复合主键 localId 内含 accountId）。
 *
 * Store 方法直接抛异常——repo 层保留原有 try/catch 吞异常记日志的语义。
 *
 * P3 批次 5 起为 Room 全量实现
 * （data/local/db/store/RoomSearchHistoryStore）。
 */
interface SearchHistoryStore {

    /** 观察搜索历史（按 searchedAt 倒序）；对应 iOS: getSearchHistory() */
    fun observeHistory(accountId: String): Flow<List<SearchHistoryEntry>>

    /** 写入/更新一条搜索历史快照（同 (type, entityId) 复合键 upsert） */
    suspend fun add(accountId: String, entry: SearchHistoryEntry)

    /** 清空该账户全部搜索历史 */
    suspend fun clear(accountId: String)
}
