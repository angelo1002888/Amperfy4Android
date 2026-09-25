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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.SwipeActionSettings
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.SheetSystemBarsFix
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.systemBlue
import com.amperfy.ui.theme.sheetBackground
import com.amperfy.ui.theme.systemRed

/**
 * 滑动位置（用于添加动作时选择目标侧）
 *
 * 对应 iOS: SwipeSettingsView.swift -> SwipePosition
 */
private enum class SwipePosition {
    LEADING,
    TRAILING
}

/**
 * Swipe设置页面
 *
 * 对应iOS: SwipeSettingsView.swift + AddSwipeActionView.swift
 *
 * Leading（右滑）/ Trailing（左滑）两个分区：
 * - 每行显示动作图标和名称，支持上移/下移排序、移除
 * - 分区标题的 "+" 按钮弹出未使用动作列表（BottomSheet）添加
 * - 配置实时持久化到 SettingsManager（JSON）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeSettingsScreen(
    onBackClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.swipeActionSettings.collectAsState()
    var addPosition by remember { mutableStateOf<SwipePosition?>(null) }

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Settings",
                title = "Swipe",
                centered = true
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsList {
                // Leading（右滑动作）
                SwipeActionSection(
                    title = "Leading",
                    actions = settings.leading,
                    onAddClick = { addPosition = SwipePosition.LEADING },
                    onMove = { from, to ->
                        viewModel.setSwipeActionSettings(
                            settings.withLeading(settings.leading.moved(from, to))
                        )
                    },
                    onRemove = { action ->
                        viewModel.setSwipeActionSettings(settings.removeFromLeading(action))
                    }
                )

                // Trailing（左滑动作）
                SwipeActionSection(
                    title = "Trailing",
                    actions = settings.trailing,
                    onAddClick = { addPosition = SwipePosition.TRAILING },
                    onMove = { from, to ->
                        viewModel.setSwipeActionSettings(
                            settings.withTrailing(settings.trailing.moved(from, to))
                        )
                    },
                    onRemove = { action ->
                        viewModel.setSwipeActionSettings(settings.removeFromTrailing(action))
                    }
                )

                // 恢复默认
                SettingsSection {
                    SettingsRow(
                        title = "Reset to Default",
                        onClick = { viewModel.resetSwipeActionSettings() }
                    )
                }
            }
        }
    }

    // 添加动作 BottomSheet - 对应 iOS: AddSwipeActionView（sheet 弹出）
    addPosition?.let { position ->
        AddSwipeActionSheet(
            notUsedActions = settings.notUsed,
            onAdd = { action ->
                viewModel.setSwipeActionSettings(
                    when (position) {
                        SwipePosition.LEADING -> settings.addToLeading(action)
                        SwipePosition.TRAILING -> settings.addToTrailing(action)
                    }
                )
                addPosition = null
            },
            onDismiss = { addPosition = null }
        )
    }
}

/**
 * 动作分区（Leading / Trailing）
 */
@Composable
private fun SwipeActionSection(
    title: String,
    actions: List<SwipeActionType>,
    onAddClick: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (SwipeActionType) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // 分区标题 + "+" 按钮（对应 iOS Section header 的 plus 按钮）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onAddClick) {
                Icon(
                    AmperfyIcons.plus,
                    contentDescription = "Add to $title",
                    // 主题色（iOS UIKit 控件 tint 语义，随账户主题色变化）
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                if (actions.isEmpty()) {
                    Text(
                        text = "No actions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                actions.forEachIndexed { index, action ->
                    SwipeActionRow(
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < actions.lastIndex,
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) },
                        onRemove = { onRemove(action) }
                    )
                    if (index < actions.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个动作行 - 对应 iOS: SwipeCellView
 */
@Composable
private fun SwipeActionRow(
    action: SwipeActionType,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 移除按钮（对应 iOS 编辑模式的红色 minus）
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                AmperfyIcons.minusCircleFill,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.systemRed
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 动作图标（白色图标 + 蓝色圆形背景，类似 iOS 设置中的图标样式）
        // 蓝圈为 Android 自加的装饰（iOS SwipeCellView 只有图标+文字，无圆底），
        // 该风格底色是固定色不随账户主题色变化，故刻意保留 systemBlue 不改 primary
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.systemBlue,
                shape = CircleShape,
                modifier = Modifier.fillMaxSize()
            ) {}
            Icon(
                imageVector = action.getIcon(),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = action.settingsName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )

        // 上移/下移（替代 iOS 拖拽排序）
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Icon(AmperfyIcons.chevronUp, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Icon(AmperfyIcons.chevronDown, contentDescription = "Move down")
        }
    }
}

/**
 * 添加动作 BottomSheet - 对应 iOS: AddSwipeActionView
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSwipeActionSheet(
    notUsedActions: List<SwipeActionType>,
    onAdd: (SwipeActionType) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 顶圆角 10dp（对齐 iOS pageSheet 规格）；本弹层非全高，不涉顶边避让
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        // iOS 此处为 UIAlertController actionSheet 的系统毛玻璃材质，Compose 无跨版本可用的
        // 实时 blur（RenderEffect 仅 12+ 且开销大，不引第三方）——以 elevated 纯色近似
        containerColor = MaterialTheme.colorScheme.sheetBackground
    ) {
        // sheet 独立窗口的系统栏图标明暗归位（否则状态栏在弹出瞬间变黑）
        SheetSystemBarsFix()
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "Add Swipe Action",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (notUsedActions.isEmpty()) {
                Text(
                    text = "All actions are already in use",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            notUsedActions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAdd(action) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.systemBlue,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxSize()
                        ) {}
                        Icon(
                            imageVector = action.getIcon(),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = action.settingsName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

/**
 * 列表元素移动（上移/下移）
 */
private fun List<SwipeActionType>.moved(from: Int, to: Int): List<SwipeActionType> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply {
        val item = removeAt(from)
        add(to, item)
    }
}
