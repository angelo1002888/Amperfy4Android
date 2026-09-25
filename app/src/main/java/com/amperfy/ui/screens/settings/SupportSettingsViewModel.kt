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
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.AmperfyLogStatusCode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * SupportSettingsViewModel - 支持页 ViewModel（Phase 5.5）
 *
 * 对应 iOS: SupportSettingsView.swift + AmperfyKit/Common/LogData.swift
 * buildLogFile 对应 LogData.collectInformation().asJSONData()：
 * BasicInfo/DeviceInfo/ServerInfo/LibraryInfo/PlayerInfo/UserSettings/EventInfo（最新 30 条事件）。
 * 注：需要 Context 组装设备信息与写缓存文件，故在 AppDelegate 之外额外注入 ApplicationContext。
 */
@HiltViewModel
class SupportSettingsViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * 组装支持日志 JSON 并写入缓存文件（AmperfyLog.json，随支持邮件作为附件发送）
     * 对应 iOS: LogData.collectInformation（LogData.swift:40-95）
     */
    suspend fun buildLogFile(): File = withContext(Dispatchers.IO) {
        val json = JSONObject()

        // BasicInfo（appName/appVersion/appBuildNumber）
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        json.put("basicInfo", JSONObject().apply {
            put("appName", "Amperfy")
            put("appVersion", packageInfo.versionName ?: "Unknown")
            // longVersionCode 需 API 28，minSdk 26 用 compat
            put("appBuildNumber", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo).toString())
        })

        // DeviceInfo（model/os 版本/磁盘容量；对应 iOS totalDiskCapacity/availableDiskCapacity）
        val dataDir = Environment.getDataDirectory()
        val statFs = StatFs(dataDir.absolutePath)
        json.put("deviceInfo", JSONObject().apply {
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("totalDiskCapacity", statFs.totalBytes)
            put("availableDiskCapacity", statFs.availableBytes)
        })

        // ServerInfo（apiType；不含服务器地址与账号，避免泄露隐私）
        json.put("serverInfo", JSONObject().apply {
            put("apiType", "Subsonic")
        })

        // LibraryInfo（各实体计数）
        val library = appDelegate.library
        json.put("libraryInfo", JSONObject().apply {
            put("artistCount", library.observeArtistCount().first())
            put("albumCount", library.observeAlbumCount().first())
            put("songCount", library.observeSongCount().first())
            put("playlistCount", appDelegate.playlists.observePlaylistCount().first())
            put("cachedSongCount", library.observeCachedSongCount().first())
        })

        // PlayerInfo（对应 iOS isPlaying/songIndex/playlistItemCount 等）
        val player = appDelegate.player
        json.put("playerInfo", JSONObject().apply {
            put("isPlaying", player.isPlaying.value)
            put("songIndex", player.currentIndex.value)
            put("playlistItemCount", player.playlist.value.size)
            put("userQueueItemCount", player.userQueue.value.size)
            put("playerDisplayStyle", player.displayMode.value)
            put("playbackRate", player.playbackRate.value.toDouble())
        })

        // UserSettings（对应 iOS swipe 动作名/playerDisplayStyle/isOfflineMode）
        val settings = appDelegate.settings
        json.put("userSettings", JSONObject().apply {
            put("isOfflineMode", settings.isOfflineMode.value)
            // 主题色为账户级设置（Settings→Account），须读 active 账户的 AccountSettingsStore；
            // 全局旧键仅为缺省源，日志读它会恒为默认值
            put(
                "themePreference",
                appDelegate.accountSettings
                    .settings(appDelegate.accounts.activeAccountId ?: "")
                    .value.themePreference.displayName
            )
            put("swipeLeadingActionSettings", JSONArray(
                settings.swipeActionSettings.value.leading.map { it.displayName }
            ))
            put("swipeTrailingActionSettings", JSONArray(
                settings.swipeActionSettings.value.trailing.map { it.displayName }
            ))
        })

        // EventInfo（totalEventCount/attachedEventCount/最新 30 条事件，iOS latestEventsCount=30）
        val eventLogger = appDelegate.eventLogger
        val events = eventLogger.getLatestLogEntries(LATEST_EVENTS_COUNT)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        json.put("eventInfo", JSONObject().apply {
            put("totalEventCount", eventLogger.getTotalEventCount())
            put("attachedEventCount", events.size)
            put("events", JSONArray(events.map { entry ->
                JSONObject().apply {
                    put("creationDate", isoFormat.format(Date(entry.creationDate)))
                    put("message", entry.message)
                    put("statusCode", entry.statusCode)
                    put("type", entry.type.displayName)
                }
            }))
        })

        val dir = File(context.cacheDir, "support").apply { mkdirs() }
        File(dir, "AmperfyLog.json").apply { writeText(json.toString(2)) }
    }

    /**
     * 无可用邮件应用时记录事件
     * 对应 iOS: MFMailComposeViewController.canSendMail() == false 分支（SupportSettingsView.swift:56-61）
     */
    fun reportEmailNotConfigured() {
        appDelegate.eventLogger.info(
            "Email Info",
            AmperfyLogStatusCode.EMAIL_ERROR,
            "Email is not configured in settings app or Amperfy is not able to send an email."
        )
    }

    companion object {
        const val GITHUB_ISSUES_URL = "https://github.com/angelo1002888/Amperfy4Android/issues"
        const val SUPPORT_EMAIL = "angelo1002888@gmail.com"
        const val MAIL_SUBJECT = "Amperfy support"
        // 对应 iOS MailView messageBody（SupportSettingsView.swift:86-92）
        const val MAIL_BODY = "\nPlease describe your issue." +
            "\nFeedback is always welcome too.\n\n\n" +
            "--- Please don't remove the attachment ---"
        private const val LATEST_EVENTS_COUNT = 30
    }
}
