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

import com.amperfy.data.model.Account

/**
 * 账户实体的持久化边界（专题 15 P1 批次 3）
 *
 * AccountManager 对账户实体只写不读——账户读取一律走 CredentialsManager（凭证层为数据源），
 * 这里只暴露登录 upsert 与登出 delete 两个写方法，只出入领域模型/标量，不出现数据库类型。
 *
 * 账户实体为复合主键豁免实体（复合主键豁免：LogEntry/PlaybackState/Account）：
 * 无 accountId 列，主键为 ident（"$serverHash-$userHash"）。
 *
 * P4 批次 1 起为 Room 全量实现（data/local/db/store/RoomAccountLocalStore）——Room 侧**不存
 * 账户元数据**（租户根 account_scope 只有 account_id，账户资料唯一来源 CredentialsManager），
 * 两方法收敛为账户生命周期维护租户根行：登录建行、登出删行并 FK CASCADE 级联清该账户库数据。
 */
interface AccountLocalStore {

    /**
     * 登录时 upsert 账户（按 ident 定位，幂等）。
     * Room 实现只建租户根行（serverUrl/userName/apiType 等元数据不落库，见上）。
     */
    suspend fun upsertAccount(account: Account)

    /**
     * 登出时按 ident 删除账户（不存在则静默）。
     * Room 实现同时经 ON DELETE CASCADE 级联清理该账户全部账户级表数据。
     */
    suspend fun deleteAccount(ident: String)
}
