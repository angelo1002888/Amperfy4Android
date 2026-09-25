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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.amperfy.data.local.CredentialsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ManageServerUrlsViewModel（W5：存储源迁至凭证层 per-ident）
 *
 * 对应 iOS ServerURLsSettingsView。多服务器 URL 的唯一存储源为凭证层（冻结）：
 * `<ident>.active_server_url`（当前选中）/ `<ident>.alternative_server_urls`（备用 JSON 数组）。
 * 切换即改写该账户 activeServerUrl，账户级 [com.amperfy.data.remote.AccountBaseUrlInterceptor]
 * 每请求实时读取，保存即生效（无需另行通知 API 层）。
 */
class ManageServerUrlsViewModel(application: Application) : AndroidViewModel(application) {

    private val credentialsManager = CredentialsManager(application)
    // active 账户 ident（本页只管理 active 账户的 URL 列表）
    private val ident: String = credentialsManager.getActiveAccountIdent() ?: ""

    private val _serverUrls = MutableStateFlow<List<String>>(emptyList())
    val serverUrls: StateFlow<List<String>> = _serverUrls

    private val _activeServerUrl = MutableStateFlow("")
    val activeServerUrl: StateFlow<String> = _activeServerUrl

    init {
        reload()
    }

    /** 重新加载：备用 URL + 当前活动 URL（活动 URL 并入展示列表） */
    fun reload() {
        val alternativeUrls = altUrls().toMutableList()
        val currentUrl = credentialsManager.getActiveServerUrl(ident) ?: ""
        if (currentUrl.isNotEmpty() && !alternativeUrls.contains(currentUrl)) {
            alternativeUrls.add(currentUrl)
        }
        _serverUrls.value = alternativeUrls
        _activeServerUrl.value = currentUrl
    }

    /** 设为活动 URL：旧活动 URL 进备用列表，新活动 URL 出备用列表 + 写 active_server_url */
    fun setAsActiveUrl(url: String) {
        val currentActiveUrl = _activeServerUrl.value
        if (url == currentActiveUrl || ident.isEmpty()) return

        val alternativeUrls = altUrls().toMutableList()
        val index = alternativeUrls.indexOf(url)
        if (index != -1) {
            alternativeUrls.removeAt(index)
            if (currentActiveUrl.isNotEmpty()) alternativeUrls.add(currentActiveUrl)
            credentialsManager.setAlternativeServerUrls(ident, alternativeUrls)
            credentialsManager.setActiveServerUrl(ident, url)
            reload()
        }
    }

    /** 删除备用 URL（不能删活动 URL） */
    fun deleteUrl(url: String) {
        if (url == _activeServerUrl.value || ident.isEmpty()) return
        val alternativeUrls = altUrls().toMutableList()
        val index = alternativeUrls.indexOf(url)
        if (index != -1) {
            alternativeUrls.removeAt(index)
            credentialsManager.setAlternativeServerUrls(ident, alternativeUrls)
            reload()
        }
    }

    /** 添加新备用 URL */
    fun addUrl(url: String) {
        if (ident.isEmpty()) return
        val alternativeUrls = altUrls().toMutableList()
        if (!alternativeUrls.contains(url) && url != _activeServerUrl.value) {
            alternativeUrls.add(url)
            credentialsManager.setAlternativeServerUrls(ident, alternativeUrls)
            reload()
        }
    }

    private fun altUrls(): List<String> =
        if (ident.isEmpty()) emptyList() else credentialsManager.getAlternativeServerUrls(ident)
}
