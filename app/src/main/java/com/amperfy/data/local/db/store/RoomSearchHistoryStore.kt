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

package com.amperfy.data.local.db.store

import androidx.room.withTransaction
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.entity.AccountScopeEntity
import com.amperfy.data.local.db.entity.SearchHistoryEntity
import com.amperfy.data.local.store.SearchHistoryStore
import com.amperfy.data.model.SearchEntityType
import com.amperfy.data.model.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * SearchHistoryStore 的 Room 全量实现（专题 15 P3 批次 5，P3 收官批）。
 *
 * 接口 3 方法全属搜索历史域，构造只需 [AmperfyDatabase]。语义：
 * 倒序、复合键 upsert、清空限账户。
 *
 * 组合键：主键三列 (account_id, type, entity_id)，`@Upsert` 覆写同键行即「再次点击更新时间」。
 *
 * Flow override 映射领域模型后 `distinctUntilChanged()`（收敛表级失效带来的等值重发）；
 * 写入口事务用 `db.withTransaction { }`。
 *
 * Store 方法直接抛异常——repo 层保留原有 try/catch 吞异常记日志的语义（接口 KDoc 约定）。
 */
class RoomSearchHistoryStore(
    private val db: AmperfyDatabase,
) : SearchHistoryStore {

    private val searchHistoryDao get() = db.searchHistoryDao()

    /**
     * 保证账户租户根 account_scope 行存在。search_history FK→account_scope 级联，
     * 缺父行时写入会被 FK 拒绝——写入口 [add] 在事务内第一步幂等调用。P4 起改由账户生命周期
     * 维护，届时从写入口移除本调用（与 RoomPlaylistLocalStore.ensureScope 同口径）。
     */
    private suspend fun ensureScope(accountId: String) {
        db.accountScopeDao().upsert(AccountScopeEntity(accountId))
    }

    override fun observeHistory(accountId: String): Flow<List<SearchHistoryEntry>> =
        searchHistoryDao.observeAll(accountId)
            .map { rows -> rows.map { it.toSearchHistoryEntry() } }
            .distinctUntilChanged()

    override suspend fun add(accountId: String, entry: SearchHistoryEntry) {
        db.withTransaction {
            ensureScope(accountId)
            searchHistoryDao.upsert(entry.toEntity(accountId))
        }
    }

    override suspend fun clear(accountId: String) {
        // 纯删除无 FK 父行依赖，单语句天然原子，不需 ensureScope / 显式事务。
        searchHistoryDao.deleteAll(accountId)
    }

    // ==================== 搜索历史 conversions ====================
    // 搜索历史快照仅本 Store 使用，映射留在 Store 内（不进 db/mapper 公共文件）。

    private fun SearchHistoryEntity.toSearchHistoryEntry() = SearchHistoryEntry(
        entityId = entityId,
        // 枚举名兜底：历史行的 type 串若与当前枚举不匹配（改名/降级）退化为 SONG
        type = runCatching { SearchEntityType.valueOf(type) }.getOrDefault(SearchEntityType.SONG),
        name = name,
        subtitle = subtitle,
        coverArt = coverArt,
        searchedAt = searchedAt
    )

    private fun SearchHistoryEntry.toEntity(accountId: String) = SearchHistoryEntity(
        accountId = accountId,
        // 主键列 type 存枚举名
        type = type.name,
        entityId = entityId,
        name = name,
        subtitle = subtitle,
        coverArt = coverArt,
        searchedAt = searchedAt
    )
}
