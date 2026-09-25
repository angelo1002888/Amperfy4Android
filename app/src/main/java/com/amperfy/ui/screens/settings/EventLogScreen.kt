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

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.LogEntry
import com.amperfy.data.model.LogEntryType
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.separator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EventLogScreen - 事件日志页（Phase 5.5）
 *
 * 对应 iOS: EventLogSettingsView.swift + EventLogCellView.swift
 * - 按时间倒序列表；每条：message（正文）+ 类型 · ISO8601 时间（说明行）
 * - error 且 statusCode > 1 时追加 " · Status code N"（EventLogCellView.swift:29-35）
 * - 长按复制 message 到剪贴板（iOS 上下文菜单 "Copy to Clipboard"）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventLogScreen(
    onBackClick: () -> Unit = {},
    viewModel: EventLogViewModel = hiltViewModel()
) {
    val logEntries by viewModel.logEntries.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Support",
                title = "Event Log",
                centered = true
            )
        }
    ) { paddingValues ->
        if (logEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No log entries",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = miniPlayerHeight)
            ) {
                itemsIndexed(logEntries) { index, entry ->
                    EventLogCell(entry = entry)
                    if (index < logEntries.lastIndex) {
                        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
                        // separatorInset = cell layoutMargins 左右值，
                        // CommonScreenOperations.swift:41-47）
                        HairlineDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.separator  // iOS .separator
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条日志 Cell - 对应 iOS EventLogCellView
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventLogCell(entry: LogEntry) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    // 对应 iOS 上下文菜单 "Copy to Clipboard"（EventLogCellView.swift:51-58）
                    clipboardManager.setText(AnnotatedString(entry.message))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = entry.captionText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 说明行文本："TypeText · ISO8601 时间[ · Status code N]"
 * 对应 iOS EventLogCellView.swift:29-49（CommonString.oneMiddleDot 分隔）
 */
private fun LogEntry.captionText(): String {
    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    var caption = "${type.displayName} · ${isoFormat.format(Date(creationDate))}"
    if (type == LogEntryType.ERROR && statusCode > 1) {
        caption += " · Status code $statusCode"
    }
    return caption
}
