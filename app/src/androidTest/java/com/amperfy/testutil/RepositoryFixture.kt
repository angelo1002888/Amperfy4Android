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

package com.amperfy.testutil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amperfy.core.EventLogger
import com.amperfy.core.NetworkMonitor
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.DbTestFixture
import com.amperfy.data.local.db.entity.AccountScopeEntity
import com.amperfy.data.local.db.store.RoomLibraryLocalStore
import com.amperfy.data.local.db.store.RoomPlaylistLocalStore
import com.amperfy.data.local.db.store.RoomSearchHistoryStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.remote.SubsonicApi
import com.amperfy.data.repository.MusicRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Repository 边界合同测试共用夹具（专题 15 P0 批次 2，纯 Room）。
 *
 * 职责：
 * - 开一个 [DbTestFixture.openDatabase] 内存 Room 库，create 时预插账户 A/B 两租户根
 *   account_scope（fixture 账户 ident 由 [AccountInfo.create] 推导，非 DbTestFixture.ACCT_A/B 常量）；
 * - 构造绑定账户 A / 账户 B 的两个 [MusicRepositoryImpl]（共享同一测试库）：
 *   - 账户 ident 经真实 [AccountInfo.create] 推导（SHA256 前 8 字节 hex），不手写；
 *   - `boundAccountInfo` 非空时 `currentAccountId = boundAccountInfo.ident`（生产实现），
 *     `currentCredentials()` 经 `credentialsManager.getCredentials(ident)` 解析——
 *     这里 stub 出对应账户的固定凭证；
 *   - SubsonicApi/EventLogger/SettingsManager/NetworkMonitor 用 relaxed mock；
 *     批次 3 起 SubsonicApi mock 经 [subsonicApiA]/[subsonicApiB] 外露——同步路径合同
 *     测试必须 coEvery 显式 stub 真实 DTO（relaxed 默认值会让解析静默得到空数据）；
 * - [close]：关内存库。
 *
 * 被测 repository 的三个 Store（library/playlist/searchHistory）全部为 Room 全量实现，
 * 镜像生产 DbModule 的接线（以该 Module 为准）。实体 seed 直接用
 * `DbTestFixture.artist/album/song/genre(accountId = accountA.ident, …)` 行构造器 + [db]
 * 各 DAO（runBlocking）。
 */
class RepositoryFixture private constructor(
    /** 合同测试的 Room 内存库（被测 repository 的全部持久化都落在这里） */
    val db: AmperfyDatabase,
    val accountA: AccountInfo,
    val accountB: AccountInfo,
    /** 绑定账户 A 的 repository 实例 */
    val repositoryA: MusicRepositoryImpl,
    /** 绑定账户 B 的 repository 实例（共享同一测试库） */
    val repositoryB: MusicRepositoryImpl,
    /**
     * repositoryA 持有的 SubsonicApi mock（批次 3 起暴露引用：同步路径合同测试
     * 需要 coEvery 显式 stub 真实 DTO——relaxed 默认返回的空 mock DTO 会让解析
     * 静默得到空数据、测试假绿）
     */
    val subsonicApiA: SubsonicApi,
    /** repositoryB 持有的 SubsonicApi mock */
    val subsonicApiB: SubsonicApi,
) {

    fun close() {
        db.close()
    }

    companion object {
        const val SERVER_A_URL = "https://server-a.example.com"
        const val SERVER_B_URL = "https://server-b.example.com"
        const val USER_A = "userA"
        const val USER_B = "userB"

        /** 创建夹具（每个测试类一份独立内存库，天然互不干扰）。 */
        fun create(): RepositoryFixture {
            // 账户 ident 经真实 AccountInfo.create 推导，与生产一致
            val accountA = AccountInfo.create(SERVER_A_URL, USER_A)
            val accountB = AccountInfo.create(SERVER_B_URL, USER_B)

            // Room 内存库 + 预插两租户根（账户级表 FK→account_scope，缺父行会令 sync 写被 FK 拒绝）。
            // 注意：ident 是 AccountInfo.create 推导值，不是 DbTestFixture.ACCT_A/B 常量。
            val db = DbTestFixture.openDatabase()
            runBlocking {
                db.accountScopeDao().upsert(AccountScopeEntity(accountA.ident))
                db.accountScopeDao().upsert(AccountScopeEntity(accountB.ident))
            }

            val credentialsManager = mockk<CredentialsManager>(relaxed = true)
            every { credentialsManager.getCredentials(accountA.ident) } returns
                credentialsOf(SERVER_A_URL, USER_A)
            every { credentialsManager.getCredentials(accountB.ident) } returns
                credentialsOf(SERVER_B_URL, USER_B)
            // active 无绑定路径兜底（本批次实例均绑定账户，正常不走到）
            every { credentialsManager.getCredentials() } returns
                credentialsOf(SERVER_A_URL, USER_A)

            // 每账户独立 SubsonicApi mock，引用外露供测试 coEvery 显式 stub（批次 3）
            val subsonicApiA = mockk<SubsonicApi>(relaxed = true)
            val subsonicApiB = mockk<SubsonicApi>(relaxed = true)

            // 连通性必须显式 stub 为 true：BaseSubsonicRepository.isSyncAllowed 读它，
            // relaxed mock 的 Boolean 默认值 false 会让全部 sync 方法静默早退（合同测试假绿）。
            // isWifiOrEthernet 保持 relaxed 默认（MediaUrl 域的比特率选择不在合同测试范围内）。
            val networkMonitor = mockk<NetworkMonitor>(relaxed = true)
            every { networkMonitor.isConnectedToNetwork } returns true

            // 被测边界的 LocalStore：三个域均为 Room 全量实现，镜像生产 DbModule 的接线
            val searchHistoryStore = RoomSearchHistoryStore(db)
            val libraryLocalStore = RoomLibraryLocalStore(db)
            val playlistLocalStore = RoomPlaylistLocalStore(db)

            fun boundRepository(account: AccountInfo, api: SubsonicApi) = MusicRepositoryImpl(
                subsonicApi = api,
                credentialsManager = credentialsManager,
                eventLogger = mockk<EventLogger>(relaxed = true),
                settingsManager = mockk<SettingsManager>(relaxed = true),
                networkMonitor = networkMonitor,
                searchHistoryStore = searchHistoryStore,
                libraryLocalStore = libraryLocalStore,
                playlistLocalStore = playlistLocalStore,
                // 歌词落盘缓存根：测试用 cacheDir 下的沙箱子目录（生产为 context.filesDir），
                // 避免合同测试写进真实 files/accounts 树
                filesDir = File(
                    ApplicationProvider.getApplicationContext<Context>().cacheDir,
                    "repository-fixture"
                ),
                boundAccountInfo = account,
            )

            return RepositoryFixture(
                db = db,
                accountA = accountA,
                accountB = accountB,
                repositoryA = boundRepository(accountA, subsonicApiA),
                repositoryB = boundRepository(accountB, subsonicApiB),
                subsonicApiA = subsonicApiA,
                subsonicApiB = subsonicApiB,
            )
        }

        private fun credentialsOf(serverUrl: String, username: String) =
            LoginCredentials(
                serverUrl = serverUrl,
                username = username,
                password = "test-password",
                passwordHash = "",
            )
    }
}
