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

package com.amperfy.utils

import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import com.amperfy.data.local.AccountSettingsStore
import com.amperfy.data.local.ArtworkDownloadSetting
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面下载策略拦截器（Phase 5.3）
 *
 * 对应 iOS: AmperfyKit.swift:266-298 的 PreDownloadIsValidCB（过滤封面下载队列）。
 * Android 封面经 Coil 加载，策略在 ImageLoader 拦截器层全局生效：
 * - NEVER：禁用网络，仅使用已缓存封面（未缓存显示占位图）
 * - UPDATE_ONCE_PER_SESSION：本会话内每个 URL 首次请求跳过磁盘缓存读取，
 *   强制拉取最新并写回缓存（对应 iOS change detection 语义）
 * - ONLY_ONCE（默认）：缓存优先，缓存过即不再请求网络
 */
class ArtworkPolicyInterceptor(
    // W5：封面策略读 active 账户的账户级设置（AccountSettingsStore.activeSettings，随 active 切换）
    private val accountSettingsStore: AccountSettingsStore
) : Interceptor {

    /** 本会话内已请求过的封面 URL（UPDATE_ONCE_PER_SESSION 用） */
    private val requestedThisSession = ConcurrentHashMap.newKeySet<String>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val newRequest = when (accountSettingsStore.activeSettings().value.artworkDownloadSetting) {
            ArtworkDownloadSetting.NEVER ->
                request.newBuilder()
                    .networkCachePolicy(CachePolicy.DISABLED)
                    .build()
            ArtworkDownloadSetting.UPDATE_ONCE_PER_SESSION -> {
                // 会话去重键取规范化后的缓存键（[ArtworkCacheKeyInterceptor] 已写入 diskCacheKey）：
                // MD5 token+salt 每请求换盐使原始 URL 每次都变，用 request.data 会导致本模式
                // 每次都当「首次」跳过磁盘缓存 → 每次都重下
                val key = request.diskCacheKey ?: request.data.toString()
                if (requestedThisSession.add(key)) {
                    request.newBuilder()
                        .diskCachePolicy(CachePolicy.WRITE_ONLY)
                        .build()
                } else {
                    request
                }
            }
            ArtworkDownloadSetting.ONLY_ONCE -> request
        }
        return chain.proceed(newRequest)
    }
}
