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

package com.amperfy.data.repository

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.remote.SubsonicApi

/**
 * 聚合门面：由六个域接口组合而成，自身不新增任何方法。
 *
 * 仅作装配入口（AppModule / AccountComponentsRegistry / RepositoryFixture）与
 * AppDelegate.music 兼容属性之用；业务调用方一律依赖窄接口（LibraryRepository /
 * PlaylistRepository / PodcastRepository / DirectoryRepository / SearchRepository /
 * MediaUrlRepository），遵循接口隔离原则（ISP）。
 *
 * 出处：Repository 拆分批次 1 拆公开接口并迁移全部调用方；
 * 批次 2 完成 MusicRepositoryImpl 物理拆分（见下方薄组合门面）。
 */
interface MusicRepository :
    LibraryRepository,
    PlaylistRepository,
    PodcastRepository,
    DirectoryRepository,
    SearchRepository,
    MediaUrlRepository

/**
 * 薄组合门面：六个域实现各一实例，全部成员经 Kotlin 接口委托（by）转发，自身无方法体。
 *
 * 六个域实例随本类构造一次性创建、与本类同生命周期（每账户一套，或 active 动态一套），
 * 因此域实现内的状态字段（Playlist 域分片锁、Library 域歌词缓存）与拆分前的单体实例
 * 字段语义完全等价。构造签名相对拆分前只多出 [filesDir]（歌词落盘缓存，Batch 5），
 * 三处装配点（AppModule / AccountComponentsRegistry / RepositoryFixture）同步透传。
 *
 * 出处：Repository 拆分批次 2——MusicRepositoryImpl 物理拆分为六个域实现。
 */
// 非 Hilt 单例：由 AppModule.provideMusicRepository（compat/active 动态）与
// AccountComponentsRegistry（每账户绑定实例）分别手工构造（W5）
class MusicRepositoryImpl(
    subsonicApi: SubsonicApi,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    settingsManager: com.amperfy.data.local.SettingsManager,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    searchHistoryStore: com.amperfy.data.local.store.SearchHistoryStore,
    libraryLocalStore: com.amperfy.data.local.store.LibraryLocalStore,
    playlistLocalStore: com.amperfy.data.local.store.PlaylistLocalStore,
    /**
     * App 私有文件根目录（生产为 `context.filesDir`）：目前仅 Library 域用于歌词落盘缓存
     * （`accounts/<sh>/<uh>/lyrics/songs/`，Batch 5），其余五域不需要。
     */
    filesDir: java.io.File,
    /**
     * 绑定账户（W5 每账户组件由 AccountComponentsRegistry 构造时传入）。
     * 为 null 时为「active 动态」实例（AppModule 提供，供 CompositionLocal 的 URL 构建等场景），
     * accountId/凭证均跟随当前 active 账户。
     */
    boundAccountInfo: AccountInfo? = null,
) : MusicRepository,
    LibraryRepository by LibraryRepositoryImpl(
        subsonicApi, credentialsManager, eventLogger, networkMonitor, libraryLocalStore, filesDir,
        boundAccountInfo
    ),
    PlaylistRepository by PlaylistRepositoryImpl(
        subsonicApi, credentialsManager, eventLogger, networkMonitor, playlistLocalStore,
        libraryLocalStore, boundAccountInfo
    ),
    PodcastRepository by PodcastRepositoryImpl(
        subsonicApi, credentialsManager, eventLogger, networkMonitor, libraryLocalStore,
        boundAccountInfo
    ),
    DirectoryRepository by DirectoryRepositoryImpl(
        subsonicApi, credentialsManager, eventLogger, networkMonitor, libraryLocalStore,
        boundAccountInfo
    ),
    SearchRepository by SearchRepositoryImpl(
        subsonicApi, credentialsManager, eventLogger, networkMonitor, searchHistoryStore,
        boundAccountInfo
    ),
    MediaUrlRepository by MediaUrlRepositoryImpl(
        subsonicApi, credentialsManager, eventLogger, settingsManager, networkMonitor,
        boundAccountInfo
    )
