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

import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.entity.AccountScopeEntity
import com.amperfy.data.local.store.AccountLocalStore
import com.amperfy.data.model.Account

/**
 * AccountLocalStore 的 Room 全量实现（专题 15 P4 批次 1）。
 *
 * **账户元数据不落库**：Room 侧没有账户元数据表，也不新建——冻结设计：租户根 account_scope
 * 只存 account_id，不保存 serverUrl/userName/serverHash/userHash/apiType，账户资料唯一来源仍是
 * CredentialsManager（AccountManager 对账户实体本就只写不读）。
 * 本实现把两个写方法收敛为**账户生命周期维护租户根行**：
 *
 * - [upsertAccount]：建 account_scope 行（幂等，重复登录不产生重复行）；
 * - [deleteAccount]：删该行，经全部账户级表的 FK `ON DELETE CASCADE` **级联清光该账户数据**
 *   （artist/album/song/song_local_state/playlist/podcast/directory/download_entry/search_history…）。
 *
 * 级联即「P4 logout 级联清理」目标：登出时该账户库数据即时清空，
 * 而非把库实体清理延迟到下次启动。全局表（event_log/playback_state/playback_queue_item）
 * 不带 account_id 故不受级联影响，由各自路径管理。
 *
 * 各 sync 写入口既有的事务内 `ensureScope` 幂等防御保留不拆——与本生命周期维护并存，
 * 覆盖「组件先于 login 落地写入」等时序缝隙。
 */
class RoomAccountLocalStore(
    private val db: AmperfyDatabase,
) : AccountLocalStore {

    private val accountScopeDao get() = db.accountScopeDao()

    /** 登录：建/保租户根行（@Upsert 幂等，同 ident 重复登录不新增行，也不覆盖任何数据）。 */
    override suspend fun upsertAccount(account: Account) {
        accountScopeDao.upsert(AccountScopeEntity(account.info.ident))
    }

    /** 登出：删租户根行，FK CASCADE 级联清理该账户全部账户级表数据；ident 不存在则静默无操作。 */
    override suspend fun deleteAccount(ident: String) {
        accountScopeDao.deleteByAccountId(ident)
    }
}
