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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络类型监测
 * 对应 iOS: NetworkMonitorFacade（AmperfyKit，NWPathMonitor 封装）
 *
 * 用于流媒体比特率的 WiFi/蜂窝二选一
 * （iOS StreamingMaxBitrates.getActive(networkMonitor:)，PlayerFacade.swift:42-48）
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 当前是否为 WiFi/以太网连接（否则视作蜂窝，取蜂窝比特率上限）
     * 对应 iOS: networkMonitor.isWifiOrEthernet
     */
    val isWifiOrEthernet: Boolean
        get() {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

    /**
     * 当前是否有可用网络连接
     * 对应 iOS: networkMonitor.isConnectedToNetwork（BackgroundLibrarySyncer 等的同步前置检查）
     * 取不到 ConnectivityManager 时默认 true（与 isWifiOrEthernet 的降级策略一致，
     * 让请求自身的失败处理兜底）
     */
    val isConnectedToNetwork: Boolean
        get() {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /** 网络回调所在作用域（进程级单例，随进程存活，无需显式停止） */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 连通性变化流（**只在连通性翻转时发射**）
     * 对应 iOS: NWPathMonitor → `.networkStatusChanged` 通知（NetworkMonitorFacade；
     * iOS 侧同样只在联通状态翻转时发通知，天然去抖）
     *
     * 每个系统回调信号都重新读一次 [isConnectedToNetwork] 的即时值再 distinctUntilChanged
     * ——不直接采信 onAvailable/onLost 的布尔含义：多网络并存（WiFi↔蜂窝切换）时某张网
     * onLost 并不代表整机断网，直接采信会误报。
     * 初值取 [isConnectedToNetwork]，配合 [SharingStarted.Eagerly] 保证订阅方 drop(1) 后
     * 不会被启动瞬间的「首次采样」误触发。
     *
     * 消费方：[com.amperfy.data.download.DownloadManager]（恢复联网自动续传未完成下载）。
     */
    val isConnected: StateFlow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            // 取不到 ConnectivityManager：不监听，保持初值（与两个 getter 的降级策略一致）
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(Unit)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            // 权限缺失等异常场景：降级为不监听，避免整个流崩溃
            android.util.Log.w("NetworkMonitor", "registerDefaultNetworkCallback failed", e)
            awaitClose { }
            return@callbackFlow
        }
        // 注册后先采样一次，保证监听建立时的真实状态被纳入
        trySend(Unit)
        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
        .map { isConnectedToNetwork }
        .distinctUntilChanged()
        .stateIn(monitorScope, SharingStarted.Eagerly, isConnectedToNetwork)
}
