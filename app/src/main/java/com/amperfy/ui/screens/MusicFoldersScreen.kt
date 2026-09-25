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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.model.Directory
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.EntityPreviewCard
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSLongPressPreviewMenu
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildDirectoryContextMenuItems
import com.amperfy.ui.components.contextmenu.directoryPreviewInfo
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl

/**
 * MusicFoldersScreen - 目录浏览第一层：音乐文件夹列表（Phase 6.5）
 * 对应 iOS: MusicFoldersVC
 *
 * - 标题固定 "Directories"（iOS setNavBarTitle(title: "Directories")）
 * - 行 = 文件夹图标 + 名称（iOS DirectoryTableCell.display(folder:)）
 * - 搜索 `Search in "Directories"`；下拉刷新同步 getMusicFolders
 * - 点击进入 IndexesScreen（iOS segue toDirectories → IndexesVC）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicFoldersScreen(
    onBackClick: () -> Unit,
    onNavigateToFolder: (String) -> Unit,
    viewModel: MusicFoldersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val folders by viewModel.filteredFolders.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    val showCollapsedTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    Scaffold(
        topBar = {
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Directories\""
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) "Directories" else null
            )
        }
    ) { padding ->
        // iOS MusicFoldersVC 未挂 refreshControl → 无下拉刷新，仅普通容器承载内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(searchState.nestedScrollConnection),
                    contentPadding = PaddingValues(bottom = miniPlayerHeight)
                ) {
                    item {
                        if (!searchState.isPinned) {
                            IOSLargeTitle(
                                text = "Directories",
                                modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                            )
                        }
                    }

                    // 搜索栏（恰好一个 item，行为在 LibrarySearchState 内）
                    librarySearchBarItem(
                        state = searchState,
                        searchText = uiState.searchText,
                        onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                        placeholder = "Search in \"Directories\"",
                        searchBarModifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )

                    // 列表区与上方控件（标题/搜索栏）的交界线 = iOS .grouped 表的
                    // section 顶边界线，恒全宽（0..0），不吃 separatorInset
                    if (folders.isNotEmpty()) {
                        item {
                            HairlineDivider(
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }

                    itemsIndexed(folders, key = { _, folder -> "folder_${folder.id}" }) { index, folder ->
                        DirectoryListRow(
                            name = folder.name,
                            coverArt = null,
                            onClick = { onNavigateToFolder(folder.id) }
                        )
                        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘
                        // （UITableView 默认 separatorInset = cell layoutMargins 左右值，
                        // CommonScreenOperations.swift:41-47），不跟随封面左缘；
                        // 末行不画，让位给下方全宽 section 底边界线
                        if (index < folders.lastIndex) {
                            HairlineDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }

                    // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                    //（iOS 实机对照 2026-08-10）
                    if (folders.isNotEmpty()) {
                        item {
                            HairlineDivider(
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }
                }

                if (folders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.searchText.isNotEmpty()) "No results" else "No directories",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
        }
    }
}

/**
 * 目录/文件夹行 - 对应 iOS DirectoryTableCell（名称 + 文件夹图标/封面 + disclosure）
 * MusicFolders/Indexes/DirectoryDetail 三层共用
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun DirectoryListRow(
    name: String,
    coverArt: String?,
    onClick: () -> Unit,
    directory: Directory? = null,
    hasCachedSongs: Boolean = false,
    onSwipeAction: ((SwipeActionType) -> Unit)? = null
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val settingsManager = LocalSettingsManager.current
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()

    // 长按预览菜单（对应 iOS contextMenuConfigurationForRowAt）；
    // 音乐文件夹行不传 directory/onSwipeAction，不响应长按（iOS MusicFoldersVC 无 context menu）
    var showPreviewMenu by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }
    val enableLongPress = directory != null && onSwipeAction != null

    Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                // 屏幕坐标（Popup 窗口原点与 App 窗口可能不一致，窗口坐标会错位）
                val position = coordinates.positionOnScreen()
                rowBoundsOnScreen = Rect(
                    left = position.x,
                    top = position.y,
                    right = position.x + coordinates.size.width,
                    bottom = position.y + coordinates.size.height
                )
            }
            // 行底色：长按 morph 时行矩形据此着色（同 SongListItem）
            .background(MaterialTheme.colorScheme.background)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (!enableLongPress) null else ({
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showPreviewMenu = true
                })
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 文件夹图标垫底 + 封面叠加：封面加载成功即覆盖图标，失败/无封面露出图标
        // （iOS DirectoryTableCell.refresh()：仅 artwork.imagePath != nil 才显示封面，否则回退图标）
        val coverUrl = buildCoverArtUrl(coverArt, credentialsManager, musicRepository)
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AmperfyIcons.folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // disclosure indicator（iOS :93 始终显示）
        Icon(
            AmperfyIcons.chevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }

        // 长按弹出：预览卡片 + 上下文菜单（仅目录行）
        if (directory != null && onSwipeAction != null) {
            IOSLongPressPreviewMenu(
                expanded = showPreviewMenu,
                onDismissRequest = { showPreviewMenu = false },
                anchorBoundsOnScreen = rowBoundsOnScreen,
                items = buildDirectoryContextMenuItems(
                    env = MenuEnv(
                        isOfflineMode = isOfflineMode,
                        isShuffleActionEnabled = isShuffleActionEnabled,
                        isShowDetailedInfo = isShowDetailedInfo
                    ),
                    hasCachedSongs = hasCachedSongs,
                    // 目录歌曲需下钻同步后才可知，列表行上无可靠的"是否含歌曲"判定，
                    // 一律显示 Add to Playlist；实际为空时 handleSwipeAction 返回
                    // NotSupported，不弹选择器（与 iOS !playables.isEmpty 的净效果一致）
                    hasSongs = true,
                    onAction = onSwipeAction,
                    onCopyId = {
                        if (directory.id.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(directory.id))
                        }
                    }
                )
            ) {
                EntityPreviewCard(
                    coverArtModel = buildCoverArtUrl(coverArt, credentialsManager, musicRepository),
                    // iOS ArtworkType.folder（Directory.swift:121 的 defaultArtworkType: .folder）
                    defaultArtworkType = DefaultArtworkType.FOLDER,
                    title = name,
                    // iOS Directory 无副标题
                    subtitle = null,
                    info = directoryPreviewInfo(directory, isShowDetailedInfo),
                    // 对应 iOS performPreviewTransition 的 directory 分支：点卡片下钻
                    showChevron = true,
                    onClick = {
                        showPreviewMenu = false
                        onClick()
                    }
                )
            }
        }
    }
}
