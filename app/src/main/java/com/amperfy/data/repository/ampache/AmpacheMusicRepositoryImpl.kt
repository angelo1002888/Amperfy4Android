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

package com.amperfy.data.repository.ampache

import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.local.store.PlaylistLocalStore
import com.amperfy.data.local.store.SearchHistoryStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.repository.DirectoryRepository
import com.amperfy.data.repository.LibraryRepository
import com.amperfy.data.repository.MediaUrlRepository
import com.amperfy.data.repository.MusicRepository
import com.amperfy.data.repository.PlaylistRepository
import com.amperfy.data.repository.PodcastRepository
import com.amperfy.data.repository.SearchRepository

/**
 * Ampache 侧的薄组合门面——与 Subsonic 侧 [com.amperfy.data.repository.MusicRepositoryImpl]
 * 完全同构：六个域实现各一实例，全部成员经 Kotlin 接口委托（by）转发，自身无方法体。
 *
 * 装配点唯一：[com.amperfy.core.AccountComponentsRegistry]（该账户
 * `backendApi == AMPACHE` 时）。构造签名相对 Subsonic 门面的差别：
 * - 传 [ampacheApi] + [authSession] 取代 `subsonicApi`（会话制认证要一个有状态管理器）；
 * - **无 filesDir**：歌词落盘只有 Subsonic 侧用得上，Ampache 不提供歌词
 *   （iOS AmpacheLibrarySyncer.parseLyrics 直接 throw），Library 域无落盘需求。
 *
 * 出处：Ampache API 移植 Batch 2。
 */
class AmpacheMusicRepositoryImpl(
    ampacheApi: AmpacheApi,
    authSession: AmpacheAuthSession,
    credentialsManager: CredentialsManager,
    eventLogger: com.amperfy.core.EventLogger,
    settingsManager: SettingsManager,
    networkMonitor: com.amperfy.core.NetworkMonitor,
    searchHistoryStore: SearchHistoryStore,
    libraryLocalStore: LibraryLocalStore,
    playlistLocalStore: PlaylistLocalStore,
    boundAccountInfo: AccountInfo? = null,
) : MusicRepository,
    LibraryRepository by AmpacheLibraryRepositoryImpl(
        ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor,
        libraryLocalStore, boundAccountInfo
    ),
    PlaylistRepository by AmpachePlaylistRepositoryImpl(
        ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor,
        playlistLocalStore, libraryLocalStore, boundAccountInfo
    ),
    PodcastRepository by AmpachePodcastRepositoryImpl(
        ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor,
        libraryLocalStore, boundAccountInfo
    ),
    DirectoryRepository by AmpacheDirectoryRepositoryImpl(
        ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor,
        libraryLocalStore, boundAccountInfo
    ),
    SearchRepository by AmpacheSearchRepositoryImpl(
        ampacheApi, authSession, credentialsManager, eventLogger, networkMonitor,
        searchHistoryStore, boundAccountInfo
    ),
    MediaUrlRepository by AmpacheMediaUrlRepositoryImpl(
        ampacheApi, authSession, credentialsManager, eventLogger, settingsManager,
        networkMonitor, boundAccountInfo
    )
