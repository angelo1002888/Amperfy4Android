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

package com.amperfy

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.amperfy.core.AccountComponentsRegistry
import com.amperfy.core.AmpacheUrlAuthRefresher
import com.amperfy.data.local.AccountSettingsStore
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.model.AccountInfo
import com.amperfy.player.audio.AudioChainCoordinator
import com.amperfy.utils.AmpacheArtworkAuthInterceptor
import com.amperfy.utils.ArtworkCacheKeyInterceptor
import com.amperfy.utils.ArtworkCacheKeyer
import com.amperfy.utils.ArtworkPolicyInterceptor
import com.amperfy.utils.FileLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AmperfyApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var credentialsManager: CredentialsManager

    @Inject
    lateinit var accountComponentsRegistry: AccountComponentsRegistry

    @Inject
    lateinit var accountSettingsStore: AccountSettingsStore

    @Inject
    lateinit var audioChainCoordinator: AudioChainCoordinator

    /** Ampache 封面 URL 的会话 token 保鲜（Ampache 移植 Batch 2，见 newImageLoader） */
    @Inject
    lateinit var ampacheUrlAuthRefresher: AmpacheUrlAuthRefresher

    override fun onCreate() {
        super.onCreate()

        // 初始化文件日志系统
        FileLogger.init(this)

        // 音频链接线（W2）：设置/当前曲目 → EQ/Gain 控制器，
        // 与登录态无关（对应 iOS AppDelegate 启动期装配音频节点链）
        audioChainCoordinator.start()

        // W5：为每个已登录账户启动服务栈（对应 iOS startManagerForNormalOperation，遍历所有账户）
        // ——恢复未完成下载（DownloadManager 构造 init）、scrobble 离线队列重传、后台专辑补齐。
        // 初始同步未完成的账户只建组件不 start（BackgroundLibrarySyncer 由 InitialSyncViewModel
        // 完成后触发，对应 iOS startManagerAfterSync）。
        val idents = credentialsManager.getAccountsIndex()
        for (ident in idents) {
            val info = parseIdent(ident) ?: continue
            val comp = accountComponentsRegistry.get(info)
            if (!credentialsManager.needsInitialSync(ident)) {
                comp.startForNormalOperation()
            }
        }
    }

    /** ident = "$serverHash-$userHash"，按首个 '-' 拆回 AccountInfo */
    private fun parseIdent(ident: String): AccountInfo? {
        val idx = ident.indexOf('-')
        if (idx <= 0 || idx >= ident.length - 1) return null
        return AccountInfo(ident.substring(0, idx), ident.substring(idx + 1))
    }

    /**
     * 全局 Coil ImageLoader（Phase 5.3 封面设置）
     * - respectCacheHeaders(false)：缓存过的封面不因 HTTP 头过期
     *   （对应 iOS ArtworkDownloadSetting.onlyOnce 的「只下载一次」语义）
     * - ArtworkPolicyInterceptor：按设置全局控制封面网络加载
     * - ArtworkCacheKeyer / ArtworkCacheKeyInterceptor：封面 URL 的缓存键剔除认证参数
     *   （MD5 token+salt 每请求换盐 → URL 每次都变，不规范化则缓存全废；见 ArtworkCacheKey）
     * - AmpacheArtworkAuthInterceptor：Ampache 封面 URL 在发请求前换成新鲜会话 token
     *   （Ampache 移植 Batch 2；排在缓存键拦截器之后，故缓存键仍取自换 token 前的 URL——
     *   两者规范化结果相同，命中不受影响）
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .respectCacheHeaders(false)
            .components {
                // 缓存键规范化须在策略拦截器之前（后者按键做会话去重）
                add(ArtworkCacheKeyInterceptor())
                add(ArtworkPolicyInterceptor(accountSettingsStore))
                // token 保鲜须在最后（策略拦截器可能直接短路返回，不必白握手一次）
                add(AmpacheArtworkAuthInterceptor(ampacheUrlAuthRefresher))
                add(ArtworkCacheKeyer())
            }
            .build()
    }
}
