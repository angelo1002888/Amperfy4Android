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

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.LibraryDisplayType
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.EditableSectionDragState
import com.amperfy.ui.components.editableSectionList
import com.amperfy.ui.theme.AmperfyIcons

/**
 * Library主屏幕
 *
 * iOS风格的Library首页:
 * - 顶栏对齐 iOS LibraryVC（prefersLargeTitles = true）导航栏布局：
 *   左上账号圆形按钮（CommonScreenOperations.setupUserNavButton → leftBarButtonItem）、
 *   右上 Edit/Done（LibraryNavigatorConfigurator → rightBarButtonItems）；
 *   大标题 IOSLargeTitle("Library") 作为 LazyColumn 第一项随内容滚动，
 *   滚出后（firstVisibleItemIndex > 0）按钮行中间淡入 17sp 居中小标题（对齐 large-title 折叠）
 * - 分组列表按 LibraryDisplaySettings.inUse 动态渲染（对应 iOS LibraryVC +
 *   LibraryNavigatorConfigurator，从 settings.libraryDisplaySettings 读取）
 * - 编辑态：单区上下两段（iOS 同一 section 追加未使用项，:265-268）——
 *   显示区可拖拽重排 + 点击取消显示；隐藏区点击恢复显示（插到显示区末尾）
 * - Done 时一次性保存（iOS editingPressed :288-300，非实时）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryMainScreen(
    onNavigateToArtists: () -> Unit,
    onNavigateToAlbums: () -> Unit,
    onNavigateToSongs: () -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onNavigateToFavoriteSongs: () -> Unit = {},
    onNavigateToFavoriteAlbums: () -> Unit = {},
    onNavigateToFavoriteArtists: () -> Unit = {},
    onNavigateToNewestAlbums: () -> Unit = {},
    onNavigateToRecentAlbums: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToGenres: () -> Unit = {},
    onNavigateToDirectories: () -> Unit = {},
    onNavigateToPodcasts: () -> Unit = {},
    onNavigateToRadios: () -> Unit = {},
    // 顶栏右上用户按钮插槽（W6 挂载 AccountMenuButton；由 MainScreen 下传，未挂时为空）
    accountMenu: @Composable () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    // 获取MiniPlayer高度
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val displaySettings by viewModel.libraryDisplaySettings.collectAsState()

    // 导航项 → 路由回调映射
    val onClickFor: (LibraryDisplayType) -> (() -> Unit) = { type ->
        when (type) {
            LibraryDisplayType.ARTISTS -> onNavigateToArtists
            LibraryDisplayType.ALBUMS -> onNavigateToAlbums
            LibraryDisplayType.SONGS -> onNavigateToSongs
            LibraryDisplayType.GENRES -> onNavigateToGenres
            LibraryDisplayType.DIRECTORIES -> onNavigateToDirectories
            LibraryDisplayType.PLAYLISTS -> onNavigateToPlaylists
            LibraryDisplayType.PODCASTS -> onNavigateToPodcasts
            LibraryDisplayType.DOWNLOADS -> onNavigateToDownloads
            LibraryDisplayType.FAVORITE_SONGS -> onNavigateToFavoriteSongs
            LibraryDisplayType.FAVORITE_ALBUMS -> onNavigateToFavoriteAlbums
            LibraryDisplayType.FAVORITE_ARTISTS -> onNavigateToFavoriteArtists
            LibraryDisplayType.NEWEST_ALBUMS -> onNavigateToNewestAlbums
            LibraryDisplayType.RECENT_ALBUMS -> onNavigateToRecentAlbums
            LibraryDisplayType.RADIOS -> onNavigateToRadios
        }
    }

    // 编辑态（对应 iOS 导航栏 Edit/Done 按钮，LibraryNavigatorConfigurator.swift:220-228）
    var isEditing by remember { mutableStateOf(false) }
    // 编辑工作副本：只在 Done 时写回持久化
    val editList = remember { mutableStateListOf<LibraryDisplayType>() }
    // 编辑态拖拽状态（行为独立 lazy item 后 hoist 到 Screen 级共享）
    val editDrag = remember { EditableSectionDragState() }

    // 滚动状态 + 折叠判定：大标题作为第一项，滚出（firstVisibleItemIndex > 0）后按钮行中间淡入小标题
    // （对齐 iOS large-title 导航栏——大标题滚出后行内小标题淡入）
    val listState = rememberLazyListState()
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶栏按钮行（固定）：账号按钮靠左、Edit/Done 靠右、折叠后中间淡入小标题
        // （对齐 iOS LibraryVC large-title 导航栏——leftBarButtonItem 账号 + rightBarButtonItems Edit + 行内标题）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // 中间小标题：大标题滚出后淡入（17sp SemiBold，对齐 iOS 导航栏行内标题）
            // 全限定 AnimatedVisibility 调用，绕开 ColumnScope 成员扩展抢占（本处在 BoxScope）
            androidx.compose.animation.AnimatedVisibility(
                visible = collapsed,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            // 账号按钮（左上，对齐 iOS leftBarButtonItem，W6）
            Box(modifier = Modifier.align(Alignment.CenterStart)) {
                accountMenu()
            }
            // Edit/Done（右上，对齐 iOS rightBarButtonItems，编辑态常驻右侧）
            TextButton(
                onClick = {
                    if (isEditing) {
                        // Done：一次性保存当前顺序与显隐（iOS :288-300）
                        viewModel.saveLibraryDisplaySettings(editList.toList())
                        isEditing = false
                    } else {
                        editList.clear()
                        editList.addAll(displaySettings.inUse)
                        isEditing = true
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Text(
                    text = if (isEditing) "Done" else "Edit",
                    fontWeight = if (isEditing) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = miniPlayerHeight) // 使用MiniPlayer高度
        ) {
            // 大标题作为第一项随内容滚动（iOS large title，start padding 16dp 与卡片对齐；字号沿用默认 28sp）
            item(key = "lib_large_title") {
                IOSLargeTitle(
                    text = "Library",
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }

            if (!isEditing) {
                // 分组卡片容器 - 对应iOS: LibraryDisplayType列表
                item(key = "lib_card") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        val inUse = displaySettings.inUse
                        inUse.forEachIndexed { index, type ->
                            key(type) {
                                LibraryNavigationItem(
                                    icon = type.icon,
                                    title = type.displayName,
                                    onClick = onClickFor(type),
                                    showDivider = index != inUse.lastIndex
                                )
                            }
                        }
                    }
                }
            } else {
                // 编辑态：行为独立 lazy item，check/uncheck 归位与拖拽让位经
                // animateItem 产生移动动画（对应 iOS dataSource.apply(snapshot,
                // animatingDifferences: true)，LibraryNavigatorConfigurator.swift:536-561）
                // 隐藏区 = 全集差集，按 rawValue 升序（iOS notUsed 排列规则）
                val hidden = LibraryDisplayType.entries
                    .filter { it !in editList }
                    .sortedBy { it.rawValue }
                editableSectionList(
                    editList = editList,
                    hidden = hidden,
                    drag = editDrag,
                    keyPrefix = "libedit",
                    stableId = { it.name },
                    label = { it.displayName },
                    leadingIcon = { it.icon }
                )
            }
        }
    }
}

/**
 * iOS风格的Library导航项
 * 每个项目有图标、标题和右箭头
 */
@Composable
fun LibraryNavigationItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    showDivider: Boolean
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // iOS风格的图标 - 使用primary color
            Icon(
                icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // 标题文字 - iOS风格
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp
                ),
                modifier = Modifier.weight(1f)
            )

            // iOS风格的chevron指示器
            Icon(
                AmperfyIcons.chevronRight,
                contentDescription = "Navigate",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        // iOS风格的分隔线
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }
    }
}
