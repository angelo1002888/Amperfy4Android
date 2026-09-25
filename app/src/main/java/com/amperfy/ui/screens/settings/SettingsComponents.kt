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

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSContextMenuBox
import com.amperfy.ui.components.IOSSwitch
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel

/**
 * Settings列表容器
 * 对应iOS的SettingsList
 */
@Composable
fun SettingsList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 8.dp)
    ) {
        content()
    }
}

/**
 * Settings分组
 * 对应iOS的SettingsSection
 */
@Composable
fun SettingsSection(
    title: String? = null,
    footer: String? = null,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Section标题
        // 颜色为 secondaryLabel 灰字：对齐 iOS insetGrouped List 的 section header
        //（SwiftUI Section(header:) 系统样式为次级标签灰，非 tintColor 主题色；
        // 此前用 primary 是既有偏差，2026-08-06 修正）
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondaryLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Section内容 - 使用Card包裹，左右留出margin
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp), // ← 添加左右padding
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                content()
            }
        }

        // Section底部说明文字
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondaryLabel,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Settings行
 * 对应iOS的SettingsRow
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Settings次要文本
 * 对应iOS的SecondaryText
 */
@Composable
fun SecondaryText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Settings复选框行
 * 对应iOS的SettingsCheckBoxRow
 * 使用iOS风格的Switch控件
 */
@Composable
fun SettingsCheckBoxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        IOSSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * Settings菜单选择行
 * 对应iOS的Menu组件
 * 使用智能定位，根据控件在屏幕中的位置自动决定菜单展开方向
 */
@Composable
fun SettingsMenuRow(
    title: String,
    selectedValue: String,
    options: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    IOSContextMenuBox(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        items = options.map { (optionText, onSelect) ->
            IOSContextMenuItem.Action(
                text = optionText,
                onClick = onSelect
            )
        },
        modifier = modifier.fillMaxWidth()
    ) {
        SettingsRow(
            title = title,
            onClick = { expanded = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

/**
 * Settings导航行
 * 对应iOS的NavigationLink
 */
@Composable
fun SettingsNavigationRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SettingsRow(
        title = title,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Icon(
                imageVector = AmperfyIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/**
 * Settings分隔线
 */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
