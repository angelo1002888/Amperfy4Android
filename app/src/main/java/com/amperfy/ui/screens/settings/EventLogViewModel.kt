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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.LogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * EventLogViewModel - 事件日志页 ViewModel（Phase 5.5）
 *
 * 对应 iOS: EventLogSettingsView 的 @FetchRequest(LogEntryMO.creationDateSortedFetchRequest)
 * 按 creationDate 倒序（最新在前）
 */
@HiltViewModel
class EventLogViewModel @Inject constructor(
    appDelegate: AppDelegate
) : ViewModel() {

    val logEntries: StateFlow<List<LogEntry>> = appDelegate.eventLogger.getLogEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
