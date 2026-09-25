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

package com.amperfy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 初始同步屏幕
 * 对应iOS: SyncVC
 *
 * 功能：
 * 1. 显示同步进度条
 * 2. 显示当前同步步骤
 * 3. 显示错误信息并提供重试按钮
 * 4. 同步完成后通知上层导航
 */
@Composable
fun InitialSyncScreen(
    onSyncCompleted: () -> Unit,
    viewModel: InitialSyncViewModel = hiltViewModel()
) {
    val syncState by viewModel.syncState.collectAsState()

    // 自动开始同步
    LaunchedEffect(Unit) {
        viewModel.startInitialSync()
    }

    // 监听同步完成状态（Completed 为一次性事件，消费即复位——InitialSyncViewModel 是
    // Activity 作用域（本屏在导航根部条件组合、Add Account 切账户后复用同一实例），
    // 不复位则残留的 Completed 会让下一个账户的初始同步屏首帧就被跳过
    // （同步转入后台继续跑，Home 随机 section 因 init 时库还空而为空）
    LaunchedEffect(syncState) {
        if (syncState is InitialSyncState.Completed) {
            onSyncCompleted()
            viewModel.resetSyncState()
        }
    }

    // 销毁兜底复位：onSyncCompleted 置位后本屏随 needsInitialSync=false 被移出组合，
    // 上面的 LaunchedEffect 协程可能未跑即被取消（Completed 残留），离开组合时无条件复位。
    DisposableEffect(Unit) {
        onDispose { viewModel.resetSyncState() }
    }

    // iOS风格的同步界面
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val state = syncState) {
                is InitialSyncState.Idle -> {
                    Text(
                        text = "Preparing to sync...",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                is InitialSyncState.Syncing -> {
                    // iOS风格的同步UI
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // 标题
                        Text(
                            text = "Syncing Library",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // 当前步骤
                        Text(
                            text = state.currentStep.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 进度条 - iOS风格
                        Column(
                            modifier = Modifier.fillMaxWidth(0.8f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { state.progress / 100f }, // lambda instead of direct Float
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )


                            Spacer(modifier = Modifier.height(8.dp))

                            // 百分比
                            Text(
                                text = "${state.progress}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 提示文本
                        Text(
                            text = "Please wait while we sync your music library.\nThis may take a few moments.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }

                is InitialSyncState.Completed -> {
                    // 完成状态（应该很快就会导航到主界面）
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontSize = 64.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "Sync Completed",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                is InitialSyncState.Error -> {
                    // 错误状态
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Sync Failed",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.error
                        )

                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // iOS风格的重试按钮
                        Button(
                            onClick = { viewModel.retrySync() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(50.dp)
                        ) {
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
