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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import kotlinx.coroutines.launch

/**
 * SupportSettingsScreen - 支持页（Phase 5.5）
 *
 * 对应 iOS: SupportSettingsView.swift
 * - Contact：Report an issue on GitHub（打开 issues 页）、
 *   Send issue or feedback to developer（邮件 + AmperfyLog.json 附件，经 FileProvider 共享）
 * - Event Log：跳转事件日志页
 * 差异：iOS 用应用内 MFMailComposeViewController；Android 经 ACTION_SEND 交给系统邮件应用，
 * 无可用应用时记录 emailError 事件（对齐 iOS canSendMail==false 分支）并 Toast 提示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSettingsScreen(
    onBackClick: () -> Unit = {},
    onNavigateToEventLog: () -> Unit = {},
    viewModel: SupportSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Settings",
                title = "Support",
                centered = true
            )
        }
    ) { paddingValues ->
        SettingsList(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = miniPlayerHeight)
        ) {
            // Contact（对应 iOS SupportSettingsView.swift:39-64）
            SettingsSection(title = "Contact") {
                SettingsRow(
                    title = "Report an issue on GitHub",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(SupportSettingsViewModel.GITHUB_ISSUES_URL))
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    title = "Send issue or feedback to developer",
                    onClick = {
                        scope.launch {
                            try {
                                val logFile = viewModel.buildLogFile()
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    logFile
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "message/rfc822"
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf(SupportSettingsViewModel.SUPPORT_EMAIL))
                                    putExtra(Intent.EXTRA_SUBJECT, SupportSettingsViewModel.MAIL_SUBJECT)
                                    putExtra(Intent.EXTRA_TEXT, SupportSettingsViewModel.MAIL_BODY)
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            } catch (e: ActivityNotFoundException) {
                                viewModel.reportEmailNotConfigured()
                                Toast.makeText(
                                    context,
                                    "Email is not configured in settings app or Amperfy is not able to send an email.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            }

            // Event Log（对应 iOS SupportSettingsView.swift:66-80）
            SettingsSection {
                SettingsNavigationRow(
                    title = "Event Log",
                    onClick = onNavigateToEventLog
                )
            }
        }
    }
}
