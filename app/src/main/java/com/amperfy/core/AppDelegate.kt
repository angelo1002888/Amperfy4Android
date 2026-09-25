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

package com.amperfy.core

import com.amperfy.data.download.DownloadManager
import com.amperfy.data.local.AccountSettingsStore
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.repository.DirectoryRepository
import com.amperfy.data.repository.HomeRepository
import com.amperfy.data.repository.LibraryRepository
import com.amperfy.data.repository.MediaUrlRepository
import com.amperfy.data.repository.MusicRepository
import com.amperfy.data.repository.PlaylistRepository
import com.amperfy.data.repository.PodcastRepository
import com.amperfy.data.repository.SearchRepository
import com.amperfy.player.PlayerManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AppDelegate - 统一的全局访问点（完全对齐 iOS AppDelegate）
 *
 * 设计理念：依赖聚合器（Dependency Container），只聚合各 Manager/Repository，不包装方法。
 *
 * ## W5 多账户改造
 * - `music`/`downloader`/`backgroundLibrarySyncer` 改为「active 账户组件」的代理属性
 *   （经 [registry].get(active)），ViewModel 层 `appDelegate.xxx` 用法零改动。
 * - 新增 `accounts`（AccountManager，冻结，W6 依赖）与 `accountSettings`（账户级设置）。
 * - 无 active 账户（未登录）时 `music` 回退到 Hilt 提供的 compat 实例（仅 URL 构建等场景，
 *   实际发生在登录前，ViewModel 不会在此态触碰 downloader/backgroundLibrarySyncer）。
 */
@Singleton
class AppDelegate @Inject constructor(
    val home: HomeRepository,   // Home 首页专用查询（W3）；纯聚合
    val player: PlayerManager,
    /** W7 音频可视化引擎；@Singleton，P3 回收——恢复「ViewModel 仅注入 AppDelegate」原则 */
    val audioAnalyzer: com.amperfy.player.audio.AudioAnalyzer,
    val credentials: CredentialsManager,
    val settings: SettingsManager,
    val eventLogger: EventLogger,
    /**
     * 网络连通性（对应 iOS AppDelegate.networkMonitor）。
     * 业务侧的远程同步门控在 Repository 层（BaseSubsonicRepository.isSyncAllowed）统一完成，
     * 此入口只供**初始同步例外**使用——iOS syncInitial 刻意不带 isSyncAllowed 守卫
     * （SubsonicLibrarySyncer.swift:50），离线须报错走 "Sync Failed" + Retry，
     * 故 [com.amperfy.ui.screens.InitialSyncViewModel] 需自行预检连通性。
     */
    val networkMonitor: NetworkMonitor,
    val accounts: AccountManager,
    val accountSettings: AccountSettingsStore,
    private val registry: AccountComponentsRegistry,
    private val compatMusic: MusicRepository,   // 未登录兜底（AppModule 提供的 active 动态实例）
) {
    private fun activeComponents(): AccountComponents? =
        accounts.activeAccount.value?.let { registry.get(it.info) }

    /** active 账户的数据仓库；未登录时回退 compat 实例。仅供装配与窄入口转发，业务调用请用下面的域入口 */
    val music: MusicRepository
        get() = activeComponents()?.music ?: compatMusic

    // ==================== 域窄入口（Repository 拆分批次 1） ====================
    // 六个属性均返回同一个 active 账户 Repository 实例，仅收窄到各自域接口（ISP）。

    /** ISP 窄入口：资料库域（歌曲/专辑/艺术家/流派/电台/统计/歌词） */
    val library: LibraryRepository get() = music

    /** ISP 窄入口：播放列表域 */
    val playlists: PlaylistRepository get() = music

    /** ISP 窄入口：播客域 */
    val podcasts: PodcastRepository get() = music

    /** ISP 窄入口：目录浏览域 */
    val directories: DirectoryRepository get() = music

    /** ISP 窄入口：搜索域（远程搜索 + 搜索历史） */
    val search: SearchRepository get() = music

    /** ISP 窄入口：媒体 URL 构建域（流媒体/封面 URL） */
    val mediaUrls: MediaUrlRepository get() = music

    /** active 账户的下载器（登录后访问，无 active 时抛错——正常流程不会命中） */
    val downloader: DownloadManager
        get() = activeComponents()?.downloader
            ?: error("AppDelegate.downloader accessed with no active account")

    /** active 账户的后台渐进同步器 */
    val backgroundLibrarySyncer: BackgroundLibrarySyncer
        get() = activeComponents()?.backgroundLibrarySyncer
            ?: error("AppDelegate.backgroundLibrarySyncer accessed with no active account")

    // ⚠️ AppDelegate 只聚合依赖，不添加业务方法。
}
