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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.EqualizerSetting
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject

/**
 * 均衡器设置页面（W7；对齐 iOS EqualizerSettingsView）
 *
 * 结构对齐 iOS：
 * - Section「Enable Equalizer」总开关；开启后「Active Equalizer」选择激活档位（Off + 用户档位）。
 * - Section「Equalizer Editor」：选择要编辑的档位（或 Create new）；编辑区含 Name 字段、
 *   10 根竖直 Slider（±6dB，2dB 刻度，频率标签）、Save、Delete。
 *
 * 全部经 SettingsManager 键读写（equalizerSettings/activeEqualizerSettingId/isEqualizerEnabled）；
 * AudioChainCoordinator（W2）已订阅这些流，Save 后实时生效，无需重启播放。
 * 编辑采用暂存模型（对齐 iOS：改动只落 Save，未 Save 不影响已保存档位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSettingsScreen(
    onBackClick: () -> Unit = {},
    viewModel: EqualizerViewModel = hiltViewModel()
) {
    val isEnabled by viewModel.isEqualizerEnabled.collectAsState()
    val presets by viewModel.equalizerSettings.collectAsState()
    val activeId by viewModel.activeEqualizerSettingId.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    // 编辑中的档位（暂存态）：id 标识正在编辑的档位，name/gains 为草稿值
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingGains by remember { mutableStateOf(EqualizerSetting.FLAT_GAINS) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 选中的激活档位名（Off = 空 id）
    val activeName = presets.firstOrNull { it.id == activeId }?.name ?: OFF_LABEL

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Settings",
                title = "Equalizer",
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
            // 总开关 + 激活档位
            SettingsSection {
                SettingsCheckBoxRow(
                    title = "Enable Equalizer",
                    checked = isEnabled,
                    onCheckedChange = { viewModel.setEqualizerEnabled(it) }
                )
                if (isEnabled) {
                    SettingsMenuRow(
                        title = "Active Equalizer",
                        selectedValue = activeName,
                        options = buildList {
                            add(OFF_LABEL to { viewModel.setActiveEqualizerSettingId("") })
                            presets.forEach { preset ->
                                add(preset.name to { viewModel.setActiveEqualizerSettingId(preset.id) })
                            }
                        }
                    )
                }
            }

            // 编辑器
            SettingsSection(title = "Equalizer Editor") {
                SettingsMenuRow(
                    title = "Equalizer",
                    selectedValue = editingId?.let { editingName } ?: "Select",
                    options = buildList {
                        presets.forEach { preset ->
                            add(preset.name to {
                                editingId = preset.id
                                editingName = preset.name
                                editingGains = preset.gains
                            })
                        }
                        add("Create new Equalizer" to {
                            val newPreset = EqualizerSetting(
                                id = UUID.randomUUID().toString(),
                                name = "My new Equalizer",
                                gains = EqualizerSetting.FLAT_GAINS
                            )
                            viewModel.setEqualizerSettings(presets + newPreset)
                            editingId = newPreset.id
                            editingName = newPreset.name
                            editingGains = newPreset.gains
                        })
                    }
                )

                if (editingId != null) {
                    // Name 字段（对齐 iOS 内联 TextField；重命名即改此值后 Save）
                    SettingsRow(
                        title = "Name",
                        trailing = {
                            BasicTextField(
                                value = editingName,
                                onValueChange = { editingName = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                    MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.width(180.dp)
                            )
                        }
                    )

                    // 10 根竖直 Slider
                    EqualizerBands(
                        gains = editingGains,
                        onGainChange = { index, value ->
                            editingGains = editingGains.toMutableList().also { it[index] = value }
                        }
                    )

                    // Save
                    SettingsRow(
                        title = "Save",
                        onClick = {
                            val id = editingId ?: return@SettingsRow
                            val updated = EqualizerSetting(id, editingName, editingGains)
                            viewModel.setEqualizerSettings(
                                presets.map { if (it.id == id) updated else it }
                            )
                            // 若正在编辑的是激活档位，重设一次以触发下游重算（AudioChainCoordinator）
                            if (activeId == id) viewModel.setActiveEqualizerSettingId(id)
                        }
                    )

                    // Delete（点击弹确认对话框）
                    SettingsRow(
                        title = "Delete",
                        onClick = { showDeleteDialog = true }
                    )
                }
            }
        }
    }

    // 删除确认（对齐 iOS Delete Equalizer alert）
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Equalizer") },
            text = { Text("Are you sure to delete this equalizer?") },
            confirmButton = {
                TextButton(onClick = {
                    val id = editingId
                    if (id != null) {
                        viewModel.setEqualizerSettings(presets.filterNot { it.id == id })
                        if (activeId == id) viewModel.setActiveEqualizerSettingId("")
                        editingId = null
                    }
                    showDeleteDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/** 10 段竖直 Slider 编辑区（频率标签 + ±6dB） */
@Composable
private fun EqualizerBands(
    gains: List<Float>,
    onGainChange: (Int, Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        EqualizerSetting.FREQUENCIES.forEachIndexed { index, freq ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VerticalEqSlider(
                    value = gains.getOrElse(index) { 0f },
                    onValueChange = { onGainChange(index, it) }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (freq < 1000) "$freq" else "${freq / 1000}k",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 竖直均衡器 Slider（±GAIN_LIMIT_DB，steps 使每格 2dB）。
 * 用 requiredWidth(高度) + rotate(-90) 将水平 Slider 立起来（Compose 无原生竖直 Slider）。
 */
@Composable
private fun VerticalEqSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val trackHeight = 140.dp
    BoxWithConstraints(
        modifier = Modifier
            .width(28.dp)
            .height(trackHeight),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = value.coerceIn(-EqualizerSetting.GAIN_LIMIT_DB, EqualizerSetting.GAIN_LIMIT_DB),
            onValueChange = onValueChange,
            valueRange = -EqualizerSetting.GAIN_LIMIT_DB..EqualizerSetting.GAIN_LIMIT_DB,
            // 12dB 跨度 / 2dB 一格 = 6 段 → 5 个内部刻度
            steps = 5,
            modifier = Modifier
                .requiredWidth(trackHeight)
                .rotate(-90f)
        )
    }
}

private const val OFF_LABEL = "Off"

/**
 * 均衡器设置 ViewModel（W7）——只经 SettingsManager 键读写，
 * 引擎侧由 W2 AudioChainCoordinator 订阅同一批流实时生效。
 */
@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {
    val isEqualizerEnabled: StateFlow<Boolean> = appDelegate.settings.isEqualizerEnabled
    val equalizerSettings: StateFlow<List<EqualizerSetting>> = appDelegate.settings.equalizerSettings
    val activeEqualizerSettingId: StateFlow<String> = appDelegate.settings.activeEqualizerSettingId

    fun setEqualizerEnabled(enabled: Boolean) = appDelegate.settings.setEqualizerEnabled(enabled)
    fun setEqualizerSettings(settings: List<EqualizerSetting>) =
        appDelegate.settings.setEqualizerSettings(settings)
    fun setActiveEqualizerSettingId(id: String) =
        appDelegate.settings.setActiveEqualizerSettingId(id)
}
