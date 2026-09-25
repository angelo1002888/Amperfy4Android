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

import android.content.Context
import com.amperfy.data.download.DownloadManager
import com.amperfy.data.local.AccountSettingsStore
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.local.store.DownloadLocalStore
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.local.store.PlaybackStateStore
import com.amperfy.data.local.store.PlaylistLocalStore
import com.amperfy.data.local.store.SearchHistoryStore
import com.amperfy.data.model.AccountInfo
import com.amperfy.data.model.BackendApiType
import com.amperfy.data.remote.AccountBaseUrlInterceptor
import com.amperfy.data.remote.SubsonicApi
import com.amperfy.data.remote.SubsonicAuthInterceptor
import com.amperfy.data.remote.ampache.AmpacheApi
import com.amperfy.data.remote.ampache.AmpacheAuthSession
import com.amperfy.data.repository.MusicRepository
import com.amperfy.data.repository.MusicRepositoryImpl
import com.amperfy.data.repository.ampache.AmpacheMusicRepositoryImpl
import com.amperfy.player.ScrobbleSyncer
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每账户服务栈（对齐 iOS MetaManager 的 Meta）
 *
 * W5：各组件构造时真正绑定 accountInfo（凭证/URL/缓存目录上下文），由 [AccountComponentsRegistry]
 * 的手工工厂创建（不引入自定义 Hilt Scope）。
 */
class AccountComponents(
    val accountInfo: AccountInfo,
    val music: MusicRepository,
    val downloader: DownloadManager,
    val scrobbleSyncer: ScrobbleSyncer,
    val backgroundLibrarySyncer: BackgroundLibrarySyncer,
    /**
     * 该账户的 Ampache 握手会话（仅 backendApi == AMPACHE 时非空）。
     *
     * 对外暴露的唯一用途：媒体加载链在装载时刻换新 token
     * （[AmpacheUrlAuthRefresher] 按 URL host 找到本账户会话后 reauthenticate）。
     */
    val ampacheAuthSession: AmpacheAuthSession? = null,
) {
    /**
     * 生命周期（对齐 iOS MetaManager startForNormalOperation）：
     * - 未完成下载恢复：由 DownloadManager 构造 init 完成（组件懒建即恢复）
     * - scrobble 离线队列重传 + 后台专辑补齐在此触发
     */
    fun startForNormalOperation() {
        scrobbleSyncer.flushPendingScrobbles()
        backgroundLibrarySyncer.start()
    }

    /** 登出时停止该账户的 syncer（对应 iOS resetMeta 前的 stop） */
    fun stop() {
        backgroundLibrarySyncer.stop()
        // 作废 Ampache 会话（对应 iOS provideCredentials 里的 authHandshake = nil）：
        // 只在登出/组件重建时做——改密与切换服务器 URL 不需要（凭证每次现取，见
        // AmpacheAuthSession.invalidateSession 注释）
        ampacheAuthSession?.invalidateSession()
    }
}

/**
 * 每账户服务栈注册表（**接口四方法签名冻结**）
 *
 * 冻结语义：
 * - [get]：按 AccountInfo 懒建并缓存该账户的服务栈；所有已登录账户的服务栈常驻并发运行
 * - [getByIdent]：按 accountId 解析（返回 null = 账户不存在，调用方 fail-closed，禁止回退 active）
 * - [reset]：登出时销毁该账户组件
 * - [allActive]：全部在册账户的服务栈（后台任务遍历用）
 *
 * 网络栈定案：全局共享一个 [okHttpClient]；每账户各 newBuilder() 挂
 * [AccountBaseUrlInterceptor] + [SubsonicAuthInterceptor]（该账户的 base URL 与认证参数），
 * 并各建自己的 Retrofit + SubsonicApi。
 */
@Singleton
class AccountComponentsRegistry @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val credentialsManager: CredentialsManager,
    private val settingsManager: SettingsManager,
    private val networkMonitor: NetworkMonitor,
    private val eventLogger: EventLogger,
    private val accountSettingsStore: AccountSettingsStore,
    private val downloadLocalStore: DownloadLocalStore,
    private val playbackStateStore: PlaybackStateStore,
    private val searchHistoryStore: SearchHistoryStore,
    private val libraryLocalStore: LibraryLocalStore,
    private val playlistLocalStore: PlaylistLocalStore,
) {
    private val components = ConcurrentHashMap<String, AccountComponents>()

    fun get(accountInfo: AccountInfo): AccountComponents =
        components.getOrPut(accountInfo.ident) { build(accountInfo) }

    fun getByIdent(ident: String): AccountComponents? {
        components[ident]?.let { return it }
        // 账户不存在（已登出/迁移异常）→ null，调用方 fail-closed，禁止回退 active
        if (!credentialsManager.hasNamespacedAccount(ident)) return null
        val info = parseIdent(ident) ?: return null
        return get(info)
    }

    fun reset(accountInfo: AccountInfo) {
        components.remove(accountInfo.ident)?.stop()
    }

    fun allActive(): Collection<AccountComponents> = components.values.toList()

    // ===== 手工工厂 =====

    /**
     * 按该账户的后端类型装配服务栈（Ampache 移植 Batch 2）：
     * `backendApi == AMPACHE` 走 Ampache 实现族（会话制认证 + XML，OkHttp 直连，
     * **不挂** Subsonic 的两个拦截器），其余一律 Subsonic 栈（含存量 NOT_DETECTED 账户）。
     * 对应 iOS BackendProxy 按 selectedApi 分派 LibrarySyncer/BackendApi 的位置。
     *
     * 其余三件组件（下载/Scrobble/后台同步）已在本批与后端解耦——
     * 下载 URL 走 `music`（MediaUrlRepository），Scrobble 走 `music`（LibraryRepository），
     * 故两条栈共用同一份构造代码。
     */
    private fun build(accountInfo: AccountInfo): AccountComponents {
        val isAmpache = credentialsManager.getBackendApi(accountInfo.ident) == BackendApiType.AMPACHE
        val ampacheAuthSession = if (isAmpache) {
            AmpacheAuthSession(okHttpClient) { credentialsManager.getCredentials(accountInfo.ident) }
        } else {
            null
        }
        val music: MusicRepository = if (ampacheAuthSession != null) {
            val ampacheApi = AmpacheApi(
                okHttpClient,
                { credentialsManager.getCredentials(accountInfo.ident) },
                ampacheAuthSession,
            )
            AmpacheMusicRepositoryImpl(
                ampacheApi, ampacheAuthSession, credentialsManager, eventLogger, settingsManager,
                networkMonitor, searchHistoryStore, libraryLocalStore, playlistLocalStore,
                boundAccountInfo = accountInfo
            )
        } else {
            MusicRepositoryImpl(
                buildApi(accountInfo), credentialsManager, eventLogger, settingsManager, networkMonitor,
                // filesDir：歌词落盘缓存根（Library 域 accounts/<sh>/<uh>/lyrics/songs/）
                searchHistoryStore, libraryLocalStore, playlistLocalStore, context.filesDir,
                boundAccountInfo = accountInfo
            )
        }
        val downloader = DownloadManager(
            context, music, credentialsManager, settingsManager, downloadLocalStore, eventLogger,
            networkMonitor, boundAccountInfo = accountInfo
        )
        val scrobbleSyncer = ScrobbleSyncer(
            context, music, credentialsManager, settingsManager, playbackStateStore, accountSettingsStore, accountInfo
        )
        val backgroundLibrarySyncer = BackgroundLibrarySyncer(
            // music 同时实现 LibraryRepository 与 PodcastRepository（聚合门面），
            // 两个参数各自类型收窄到所需窄接口（ISP）
            music, music, credentialsManager, settingsManager, networkMonitor, downloader, eventLogger,
            accountSettingsStore, accountInfo
        )
        return AccountComponents(
            accountInfo, music, downloader, scrobbleSyncer, backgroundLibrarySyncer, ampacheAuthSession
        )
    }

    /** 账户级 Retrofit + SubsonicApi（占位 baseUrl 由账户级拦截器按 activeServerUrl 重写） */
    private fun buildApi(accountInfo: AccountInfo): SubsonicApi {
        // 账户级拦截器插到最前（先于全局 DetailedLoggingInterceptor 重写 URL，保证日志记录到真实 URL）：
        // [0] base URL 重写 → [1] 认证参数追加（u/v/c + t/s 或 legacy 明文 p，每请求现生成新盐，
        // 对应 iOS SubsonicServerApi.createAuthApiUrlComponent）
        val client = okHttpClient.newBuilder().apply {
            interceptors().add(0, AccountBaseUrlInterceptor(accountInfo, credentialsManager))
            interceptors().add(
                1,
                SubsonicAuthInterceptor { credentialsManager.getCredentials(accountInfo.ident) }
            )
        }.build()
        return Retrofit.Builder()
            .baseUrl("http://placeholder.example.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SubsonicApi::class.java)
    }

    /** ident = "$serverHash-$userHash"，两段均为 hex（无 '-'），按首个 '-' 拆回 AccountInfo */
    private fun parseIdent(ident: String): AccountInfo? {
        val idx = ident.indexOf('-')
        if (idx <= 0 || idx >= ident.length - 1) return null
        return AccountInfo(ident.substring(0, idx), ident.substring(idx + 1))
    }
}
