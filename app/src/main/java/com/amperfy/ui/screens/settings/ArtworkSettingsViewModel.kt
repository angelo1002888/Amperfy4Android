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

package com.amperfy.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.request.ImageRequest
import com.amperfy.core.AppDelegate
import com.amperfy.data.local.ArtworkDisplayPreference
import com.amperfy.data.local.ArtworkDownloadSetting
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 封面统计（对应 iOS ArtworkSettingsView 的三条统计行）
 *
 * @param artworkCount 封面总数（album/artist/podcast 三表 cover_art 去重）
 * @param notCheckedCount 未校验封面数——Android 恒为 0（无 artwork 校验状态，见 VM 注释）
 * @param cachedArtworkCount 已缓存封面条目数（Coil 磁盘缓存条目计数，近似）
 */
data class ArtworkStats(
    val artworkCount: Long = 0L,
    val notCheckedCount: Long = 0L,
    val cachedArtworkCount: Long = 0L,
)

/**
 * ArtworkSettingsViewModel - 封面设置 ViewModel（Phase 5.3；2026-08-01 对齐 iOS 2.1.0 三级结构）
 *
 * 对应 iOS: ArtworkSettingsView.swift + ArtworkDownloadSettingsView.swift +
 * ArtworkDisplaySettings.swift——三页共用本 VM（三处均只读写 active 账户的封面相关设置
 * 与统计，无各自私有状态）。
 *
 * Android 差异：封面经 Coil 加载/缓存（无 iOS 的 Artwork 实体），故
 * - Artworks 计数以「实体表引用到的不同 cover_art 标识数」近似（DAO 三表 UNION 去重）；
 * - Not checked Artworks 恒为 0——Android 无封面「已校验/未校验」状态机
 *   （iOS 在未校验数 ≤ artworkNotCheckedThreshold=10 时同样显示 0，
 *   见 ArtworkSettingsView.swift:28、66-67）；
 * - Cached Artworks 为 Coil 磁盘缓存条目计数（见 [countCachedArtworks] 的近似性说明）。
 *
 * 注：需要 Context 操作 Coil ImageLoader，故在 AppDelegate 之外额外注入 ApplicationContext。
 */
@OptIn(ExperimentalCoilApi::class) // Coil diskCache 访问为实验性 API
@HiltViewModel
class ArtworkSettingsViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** 三条统计行（2 秒轮询；iOS 用 1s Timer 刷新计数） */
    val artworkStats: StateFlow<ArtworkStats> = flow {
        while (true) {
            emit(
                ArtworkStats(
                    // 无 active 账户或查询异常时按 0 呈现，不让设置页崩溃
                    artworkCount = runCatching { appDelegate.library.getArtworkCount() }.getOrDefault(0L),
                    notCheckedCount = 0L,
                    cachedArtworkCount = countCachedArtworks(),
                )
            )
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ArtworkStats())

    /** 是否存在 active 账户（无账户时隐藏两个操作按钮，对齐 iOS ArtworkSettingsView.swift:94） */
    val hasActiveAccount: StateFlow<Boolean> = appDelegate.accounts.activeAccount
        .map { it != null }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            appDelegate.accounts.activeAccount.value != null
        )

    // W5：封面下载策略为账户级设置（消费方 ArtworkPolicyInterceptor 已改读账户层），
    // UI 读/写须同走 active 账户的 AccountSettingsStore，否则迁移后切换对当前账户不生效。
    // 账户切换时 UI 整体重建，VM 重构造即读到新 active 账户设置，故按当前 ident 一次性绑定。
    // ident 为空（未登录，正常不可达设置页）时：settings("") 回退缺省、update("") 空操作。
    private val accountIdent: String get() = appDelegate.accounts.activeAccountId ?: ""

    val artworkDownloadSetting: StateFlow<ArtworkDownloadSetting> =
        appDelegate.accountSettings.settings(accountIdent)
            .map { it.artworkDownloadSetting }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                appDelegate.accountSettings.settings(accountIdent).value.artworkDownloadSetting
            )

    fun setArtworkDownloadSetting(setting: ArtworkDownloadSetting) =
        appDelegate.accountSettings.update(accountIdent) { it.copy(artworkDownloadSetting = setting) }

    /** 封面显示偏好（账户级，同 iOS；Android 暂无消费方，见 [ArtworkDisplayPreference] KDoc） */
    val artworkDisplayPreference: StateFlow<ArtworkDisplayPreference> =
        appDelegate.accountSettings.settings(accountIdent)
            .map { it.artworkDisplay }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                appDelegate.accountSettings.settings(accountIdent).value.artworkDisplay
            )

    fun setArtworkDisplayPreference(preference: ArtworkDisplayPreference) =
        appDelegate.accountSettings.update(accountIdent) { it.copy(artworkDisplay = preference) }

    /**
     * 下载资料库全部封面（专辑 + 艺术家封面 URL 逐个入 Coil 队列，写入磁盘缓存）
     * 对应 iOS: getArtworksForCompleteLibraryDownload -> artworkDownloadManager.download
     */
    fun downloadAllArtworks() {
        viewModelScope.launch {
            val creds = appDelegate.credentials.getCredentials() ?: return@launch
            val albums = appDelegate.library.getAllAlbums().first()
            val artists = appDelegate.library.getAllArtists().first()
            val coverIds = (albums.mapNotNull { it.coverArt } + artists.mapNotNull { it.coverArt })
                .distinct()
            android.util.Log.d(TAG, "Enqueue ${coverIds.size} artworks for download")
            val loader = context.imageLoader
            coverIds.forEach { id ->
                val url = appDelegate.mediaUrls.getCoverArtUrl(
                    coverArtId = id,
                    username = creds.username,
                    password = creds.password,
                    baseUrl = creds.serverUrl
                )
                loader.enqueue(ImageRequest.Builder(context).data(url).build())
            }
        }
    }

    /**
     * 删除全部已下载封面（清空 Coil 磁盘 + 内存缓存）
     * 对应 iOS: deleteRemoteArtworkCache
     */
    fun deleteArtworkCache() {
        viewModelScope.launch(Dispatchers.IO) {
            context.imageLoader.diskCache?.clear()
            launch(Dispatchers.Main) { context.imageLoader.memoryCache?.clear() }
        }
    }

    /**
     * 已缓存封面条目数（Coil 磁盘缓存目录条目计数）。
     *
     * Coil 2.x 的 DiskCache 底层是 DiskLruCache：每个条目落两个文件——`<key>.0`（元数据）
     * 与 `<key>.1`（图片数据），写入中的临时文件为 `<key>.<i>.tmp`，另有 `journal*` 日志文件。
     * 故「以 `.1` 结尾的文件数」= 已落盘的缓存条目数（`.1.tmp` 不以 `.1` 结尾，自动排除；
     * journal 无后缀，自动排除）。
     *
     * **近似性说明**：① Coil 磁盘缓存是全应用全局共享的，不按账户分区，多账户时该数值为
     * 全部账户合计；② 计数含本 App 经 Coil 加载的任意图片——本项目 Coil 仅用于封面加载，
     * 故实际近似等价于封面条目数；③ 条目可能被 Coil 按 LRU 容量自动淘汰，数值随时间变化。
     */
    private fun countCachedArtworks(): Long {
        // diskCache.directory 为 okio.Path，用其字符串形式构造 java.io.File
        // （Coil 磁盘缓存必落本地文件系统），避免依赖 okio 的 Path/File 互转扩展
        val dirPath = context.imageLoader.diskCache?.directory?.toString() ?: return 0L
        val files = File(dirPath).listFiles() ?: return 0L
        return files.count { it.isFile && it.name.endsWith(DISK_CACHE_DATA_SUFFIX) }.toLong()
    }

    companion object {
        private const val TAG = "ArtworkSettingsVM"

        /** 统计刷新间隔（iOS 为 1s Timer；Android 放宽到 2s 降低查询频率） */
        private const val POLL_INTERVAL_MS = 2000L

        /** DiskLruCache 数据文件后缀（ENTRY_DATA 索引为 1） */
        private const val DISK_CACHE_DATA_SUFFIX = ".1"
    }
}
