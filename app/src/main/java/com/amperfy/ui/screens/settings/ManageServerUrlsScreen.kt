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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator

/**
 * 管理服务器URLs页面
 * 对应iOS的ServerURLsSettingsView.swift
 * 
 * 功能:
 * - 显示所有服务器URL列表
 * - 切换活动的服务器URL
 * - 添加新的服务器URL
 * - 删除备用服务器URL
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageServerUrlsScreen(
    onBackClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: ManageServerUrlsViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as android.app.Application
        )
    )
    
    val serverUrls by viewModel.serverUrls.collectAsState()
    val activeServerUrl by viewModel.activeServerUrl.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }

    // 获取MiniPlayer高度
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    Scaffold(
        topBar = {
            IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Server",
                title = "Server URLs",
                centered = true,
                // 顶栏底色走 IOSNavTopBar 默认的 colorScheme.background：Settings 子树在
                // elevated 覆盖主题下即 sheetGroupedBackground，与其余设置页顶栏一致
                //（此前显式取 surface，会让本页顶栏变成分组卡片色 #2C2C2E）
                actions = {
                    // Edit按钮 - 对应iOS代码第110行
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            if (editMode) AmperfyIcons.check else AmperfyIcons.pencil,
                            contentDescription = if (editMode) "Done" else "Edit"
                        )
                    }
                    // 添加按钮 - 对应iOS代码第111-115行
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(AmperfyIcons.plus, contentDescription = "Add")
                    }
                }
            )
        }
    ) { paddingValues ->
        // URL列表
        // 对应iOS代码第75-98行
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = miniPlayerHeight) // 添加底部padding避免被MiniPlayer遮挡
        ) {
            items(serverUrls, key = { it }) { url ->
                ServerUrlItem(
                    url = url,
                    isActive = url == activeServerUrl,
                    editMode = editMode,
                    onSetActive = { viewModel.setAsActiveUrl(url) },
                    onDelete = { viewModel.deleteUrl(url) }
                )
            }
        }
    }
    
    // 添加URL对话框
    // 对应iOS代码第100-106行
    if (showAddDialog) {
        AddServerUrlDialog(
            existingUrls = serverUrls,
            onDismiss = { showAddDialog = false },
            onAdd = { newUrl ->
                viewModel.addUrl(newUrl)
                showAddDialog = false
            }
        )
    }
}

/**
 * 服务器URL列表项
 * 对应iOS代码第76-94行的ForEach内容
 */
@Composable
fun ServerUrlItem(
    url: String,
    isActive: Boolean,
    editMode: Boolean,
    onSetActive: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !editMode) { onSetActive() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // URL文本
        Text(
            text = url,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        
        // 删除按钮（编辑模式）或选中标记
        if (editMode && !isActive) {
            // 对应iOS代码第90-94行的onDelete
            IconButton(onClick = onDelete) {
                Icon(
                    AmperfyIcons.trash,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else if (isActive) {
            // 对应iOS代码第80-82行的checkmark
            Icon(
                AmperfyIcons.check,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
    // separatorInset = cell layoutMargins 左右值，CommonScreenOperations.swift:41-47）
    HairlineDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.separator  // iOS .separator
    )
}

/**
 * 添加服务器URL对话框
 * 对应iOS的AlternativeURLAddDialogView
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerUrlDialog(
    existingUrls: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Server URL") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (errorMsg.isNotEmpty()) {
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { 
                        urlInput = it
                        errorMsg = ""
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://music.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        urlInput.isEmpty() -> {
                            errorMsg = "Please enter a URL"
                        }
                        !urlInput.startsWith("http://") && !urlInput.startsWith("https://") -> {
                            errorMsg = "URL must start with http:// or https://"
                        }
                        existingUrls.contains(urlInput) -> {
                            errorMsg = "This URL already exists"
                        }
                        else -> {
                            onAdd(urlInput)
                        }
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
