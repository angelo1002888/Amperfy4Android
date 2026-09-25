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

package com.amperfy.data.local

import android.content.Context
import com.amperfy.data.model.HomeSection
import com.amperfy.data.model.LibraryDisplaySettings
import com.amperfy.data.model.LibraryDisplayType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

/**
 * 账户级设置（W5）
 *
 * 每账户一份 [AccountSetting]（非凭证类偏好——URL 唯一存储源为凭证层，本结构不放 URL）。
 * 存储：`amperfy_settings` 内 `account_settings_<ident>` = Gson JSON（模式抄 swipeActionSettings）。
 * 缺省值取自 [SettingsManager] 的旧全局键（迁移后旧键仅作缺省）。
 *
 * 暴露 `settings(ident): StateFlow` + `update(ident) { }`；账户切换时 UI 整体重建，
 * ViewModel 重新构造即读到新 active 账户的设置，无需本类监听 active 变化。
 */
@Singleton
class AccountSettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val credentialsManager: CredentialsManager,
) {
    private val prefs by lazy {
        context.getSharedPreferences("amperfy_settings", Context.MODE_PRIVATE)
    }
    private val gson = Gson()

    /** 每账户一个热流缓存，重复取用同一实例 */
    private val flows = ConcurrentHashMap<String, MutableStateFlow<AccountSetting>>()

    /** 读某账户设置（无值回退旧全局键缺省） */
    fun settings(ident: String): StateFlow<AccountSetting> = flowFor(ident).asStateFlow()

    /** active 账户设置（无 active 时回退缺省） */
    fun activeSettings(): StateFlow<AccountSetting> = settings(activeIdent())

    /** 更新某账户设置（读-改-写，落盘 + 发射） */
    fun update(ident: String, transform: (AccountSetting) -> AccountSetting) {
        if (ident.isEmpty()) return
        val flow = flowFor(ident)
        val updated = transform(flow.value)
        prefs.edit { putString(key(ident), gson.toJson(updated.toDto())) }
        flow.value = updated
    }

    /** 更新 active 账户设置 */
    fun updateActive(transform: (AccountSetting) -> AccountSetting) = update(activeIdent(), transform)

    /** 删除某账户设置键（登出时） */
    fun removeAccount(ident: String) {
        prefs.edit { remove(key(ident)) }
        flows.remove(ident)
    }

    private fun activeIdent(): String = credentialsManager.getActiveAccountIdent() ?: ""

    private fun key(ident: String) = "account_settings_$ident"

    private fun flowFor(ident: String): MutableStateFlow<AccountSetting> =
        flows.getOrPut(ident) { MutableStateFlow(load(ident)) }

    private fun load(ident: String): AccountSetting {
        val json = prefs.getString(key(ident), null) ?: return defaultFromGlobal()
        return try {
            gson.fromJson(json, AccountSettingDto::class.java).toModel()
        } catch (e: Exception) {
            android.util.Log.e("AccountSettingsStore", "parse failed, fallback to global default", e)
            defaultFromGlobal()
        }
    }

    /** 旧全局键作缺省（迁移后旧键仅作缺省） */
    private fun defaultFromGlobal(): AccountSetting = AccountSetting(
        homeSections = settingsManager.homeSections.value.map { it.rawValue },
        libraryDisplaySettings = settingsManager.libraryDisplaySettings.value,
        artworkDownloadSetting = settingsManager.artworkDownloadSetting.value,
        themePreference = settingsManager.themePreference.value,
        isAutoCacheLatestSongs = settingsManager.isAutoCacheLatestSongs.value,
        isAutoCacheLatestPodcastEpisodes = settingsManager.isAutoCacheLatestPodcastEpisodes.value,
        isScrobbleStreamedItems = settingsManager.isScrobbleStreamedItems.value,
    )

    // ===== 序列化 DTO（只存原始可序列化值，避免 Gson 直接序列化枚举/嵌套模型的脆弱性） =====

    private data class AccountSettingDto(
        val homeSections: List<Int> = emptyList(),
        val libraryDisplay: List<Int> = emptyList(),
        val artworkDownload: String = ArtworkDownloadSetting.ONLY_ONCE.value,
        // 主题色（账户级，对齐 iOS AccountSettingsView.swift:139-162 的 Theme Color）。
        // 可空：老账户 JSON 无此字段，Gson 反序列化 Kotlin data class 不走构造默认值
        // （Unsafe 分配），非空 String 会被填 null 而在后续解引用处 NPE；
        // 读到 null 时由 toModel 回退全局旧键（等效迁移，下次 update 落盘即固化）。
        val themePreference: String? = null,
        // 封面显示偏好（账户级，对齐 iOS AccountSettings.artworkDisplayPreference）。
        // 同样可空：老账户 JSON 无此字段，Gson 不走 Kotlin 构造默认值（Unsafe 分配），
        // 非空 String 会被填 null；无全局旧键可迁移，读到 null 时回退枚举默认值。
        val artworkDisplay: String? = null,
        val isAutoCacheLatestSongs: Boolean = false,
        val isAutoCacheLatestPodcastEpisodes: Boolean = false,
        val isScrobbleStreamedItems: Boolean = false,
    )

    private fun AccountSetting.toDto() = AccountSettingDto(
        homeSections = homeSections,
        libraryDisplay = libraryDisplaySettings.inUse.map { it.rawValue },
        artworkDownload = artworkDownloadSetting.value,
        themePreference = themePreference.value,
        artworkDisplay = artworkDisplay.value,
        isAutoCacheLatestSongs = isAutoCacheLatestSongs,
        isAutoCacheLatestPodcastEpisodes = isAutoCacheLatestPodcastEpisodes,
        isScrobbleStreamedItems = isScrobbleStreamedItems,
    )

    private fun AccountSettingDto.toModel() = AccountSetting(
        homeSections = homeSections.ifEmpty { HomeSection.DEFAULT.map { it.rawValue } },
        libraryDisplaySettings = if (libraryDisplay.isEmpty()) LibraryDisplaySettings.DEFAULT
        else LibraryDisplaySettings(inUse = libraryDisplay.mapNotNull { LibraryDisplayType.fromRawValue(it) }),
        artworkDownloadSetting = ArtworkDownloadSetting.fromString(artworkDownload),
        // 缺字段（老账户 JSON）回退全局旧键
        themePreference = themePreference?.let { ThemePreference.fromString(it) }
            ?: settingsManager.themePreference.value,
        // 无全局旧键，缺字段直接回退枚举默认值（PREFER_ID3_TAG，对齐 iOS defaultValue）
        artworkDisplay = artworkDisplay?.let { ArtworkDisplayPreference.fromString(it) }
            ?: ArtworkDisplayPreference.PREFER_ID3_TAG,
        isAutoCacheLatestSongs = isAutoCacheLatestSongs,
        isAutoCacheLatestPodcastEpisodes = isAutoCacheLatestPodcastEpisodes,
        isScrobbleStreamedItems = isScrobbleStreamedItems,
    )

    @Suppress("unused")
    private fun typeToken() = object : TypeToken<List<Int>>() {}.type
}

/**
 * 账户级设置。**不含 URL**（凭证层为唯一存储源）。
 */
data class AccountSetting(
    val homeSections: List<Int> = HomeSection.DEFAULT.map { it.rawValue },
    val libraryDisplaySettings: LibraryDisplaySettings = LibraryDisplaySettings.DEFAULT,
    val artworkDownloadSetting: ArtworkDownloadSetting = ArtworkDownloadSetting.ONLY_ONCE,
    /** 主题色（iOS 2.1.0 中为账户级设置，见 AccountSettingsView.swift:139-162） */
    val themePreference: ThemePreference = ThemePreference.BLUE,
    /** 封面显示偏好（iOS 中同为账户级，见 ArtworkDisplaySettings.swift:37-51；Android 暂无消费方） */
    val artworkDisplay: ArtworkDisplayPreference = ArtworkDisplayPreference.PREFER_ID3_TAG,
    val isAutoCacheLatestSongs: Boolean = false,
    val isAutoCacheLatestPodcastEpisodes: Boolean = false,
    val isScrobbleStreamedItems: Boolean = false,
)
