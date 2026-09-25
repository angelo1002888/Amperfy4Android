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

package com.amperfy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.model.Playlist
import com.amperfy.ui.screens.PlaylistSelectorViewModel
import com.amperfy.ui.screens.PlaylistSortType
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.placeholderText
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.sheetBackground
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * PlaylistSelectorDialog - 添加到播放列表选择器
 *
 * 对应 iOS: PlaylistSelectorVC.swift（模态 sheet + 导航栏），逐项对齐：
 * - 全高 ModalBottomSheet（iOS pageSheet；同 PlaylistEditSheet 先例）
 * - 居中标题："Add to Playlist"，多于 1 首时 "Add N Songs to Playlist"
 * - 右上：Sort 菜单（Name/Last time played/Change date/Duration，不持久化）+ Close(X)
 * - 搜索栏 `Search in "Playlists"`
 * - New Playlist 表头：内联输入框 + Create（仅创建不加歌不关闭，对齐 NewPlaylistTableHeader）
 * - 行 = 封面 + 名称 + "N Songs"（同 PlaylistsScreen 行样式，无 chevron）
 * - 底部工具栏：Select（切多选）+ "+"（把歌加入全部选中列表，iOS toolbarItems）
 * - 单选：点列表 → 去重确认 → 加入并关闭；多选：行尾勾选圈，"+" 提交
 * - 去重："Some Songs are already in this Playlist." Add/Skip Duplicates/Cancel
 *
 * @param songIds 待添加的歌曲 ID 列表
 * @param onDismiss 关闭回调（完成或取消）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSelectorDialog(
    songIds: List<String>,
    onDismiss: () -> Unit,
    viewModel: PlaylistSelectorViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val sortType by viewModel.sortType.collectAsState()
    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsState()
    val selectedPlaylists by viewModel.selectedPlaylists.collectAsState()
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsState()

    var newPlaylistName by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本弹层只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    // 搜索栏虽是 sheet Column 的直接子项，但显隐由下方播放列表的滚动驱动，故仍接其 listState
    //（播放列表少于一屏时 → 搜索栏常驻，否则弹层内将无从唤出搜索栏）
    val listState = rememberLazyListState()
    val searchState = rememberLibrarySearchState(listState)

    // 去重确认（iOS: didSelectRowAt 的 UIAlertController）
    duplicatePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicatePrompt() },
            text = { Text("Some Songs are already in this Playlist.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.confirmAddDuplicates(onDismiss) }) {
                        Text("Add Duplicates")
                    }
                    TextButton(onClick = { viewModel.skipDuplicates(onDismiss) }) {
                        Text("Skip Duplicates")
                    }
                    TextButton(onClick = { viewModel.dismissDuplicatePrompt() }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        // 顶边止于状态栏下缘 + 顶圆角 10dp（对齐 iOS pageSheet）：
        // statusBarsPadding 作用于 sheet 的 Surface 节点（变矮的是弹层本体），
        // 并消费状态栏 inset 使内层默认 contentWindowInsets 归零，无双重留白
        modifier = Modifier.statusBarsPadding(),
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        // iOS PlaylistSelectorVC.swift:106 tableView.backgroundColor = systemBackground
        //（模态呈现取 elevated 提升层值）
        containerColor = MaterialTheme.colorScheme.sheetBackground
    ) {
        // sheet 独立窗口的系统栏图标明暗归位（否则状态栏在弹出瞬间变黑）
        SheetSystemBarsFix()
        Column(modifier = Modifier.fillMaxSize()) {
            // 钉顶态：钉顶搜索头贴 sheet 内容最顶（顶边已止于状态栏下缘，无需再加），
            // 标题/Sort 行隐藏（对齐 iOS 导航栏收起）
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Playlists\"",
                    // 钉顶头与 sheet 同底（elevated），滚动内容经其下方时无色差
                    backgroundColor = MaterialTheme.colorScheme.sheetBackground,
                    // sheet 顶边已止于状态栏下缘，钉顶头不再避让状态栏
                    withStatusBarPadding = false
                )
            }
            // 顶部导航行（iOS: navBar 标题顶部左侧 + rightBarButtonItems [Close, Sort]；
            // 标题带容器全部歌曲计数："Add N Songs to Playlist"，单曲为 "Add to Playlist"）；
            // 钉顶时隐藏（导航栏收起）
            if (!searchState.isPinned) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (songIds.size > 1) {
                        "Add ${songIds.size} Songs to Playlist"
                    } else {
                        "Add to Playlist"
                    },
                    // iOS 模态导航栏标题字号（17pt semibold），非大标题
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    // More 菜单（Sort 排序项，本地不持久化——iOS 注释 differs from PlaylistsVC）
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                AmperfyIcons.ellipsis,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IOSStyleContextMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(-24, 48),
                            items = PlaylistSortType.entries.map { type ->
                                IOSContextMenuItem.Action(
                                    text = type.displayName,
                                    // iOS: AmperfyImage.check（SF 裸勾），菜单单选打勾
                                    // （PlaylistSelectorVC.swift:246 `image: .check`）——
                                    // 与本页多选附件的 UIKit 内建圆徽 [AmperfyIcons.isSelected] 不同源
                                    icon = if (type == sortType) AmperfyIcons.check else null,
                                    onClick = { viewModel.changeSortType(type) }
                                )
                            }
                        )
                    }
                    // Close(X)（iOS: CloseBarButton）
                    IconButton(onClick = onDismiss) {
                        Icon(
                            AmperfyIcons.xmark,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 搜索栏（iOS PlaylistSelectorVC.swift:98 configureSearchController，
            // placeholder "Search in \"Playlists\""）；搜索栏是 sheet Column 的直接子项
            // （不在 LazyColumn 内），故不用列表版发射器，直接调其内核 SearchBarSlot
            searchState.SearchBarSlot(
                searchText = searchText,
                onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                placeholder = "Search in \"Playlists\""
            )

            // New Playlist 表头（iOS NewPlaylistTableHeader：输入框 + Create，
            // 仅创建播放列表，新列表出现在下方列表中供选择）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 紧凑输入框（iOS NewPlaylistTableHeader 高约 30pt；样式同 LibrarySearchBar）
                BasicTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (newPlaylistName.isEmpty()) {
                                Text(
                                    text = "New playlist name",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.placeholderText,
                                    maxLines = 1
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                TextButton(
                    onClick = {
                        viewModel.createPlaylist(newPlaylistName)
                        newPlaylistName = ""
                    },
                    enabled = newPlaylistName.isNotBlank(),
                    // 主题色（iOS UIKit 控件 tint 语义，随账户主题色变化），禁用态灰
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.secondaryLabel
                    )
                ) {
                    Text("Create")
                }
            }

            // 列表区与上方控件（New Playlist 表头）的交界线 = iOS .grouped 表的
            // section 顶边界线，恒全宽（0..0）
            HairlineDivider(
                color = MaterialTheme.colorScheme.separator  // iOS .separator
            )

            // 播放列表（占剩余高度；底部工具栏常驻）
            Box(modifier = Modifier.weight(1f)) {
                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchText.isNotEmpty()) "No results found" else "No playlists yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            // iOS 式过滚阻尼（弹层内滚动容器统一接入）
                            .iosOverscroll()
                            // 只观察不消费，与 sheet 拖拽手势并存（LibrarySearchState 机制）
                            .nestedScroll(searchState.nestedScrollConnection)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            SelectorPlaylistRow(
                                playlist = playlist,
                                coverArtUrl = viewModel.getCoverArtUrl(playlist.coverArt),
                                // 多选模式行尾勾选圈（iOS: accessoryView checkmark/circle）
                                accessory = if (isMultiSelectMode) {
                                    if (selectedPlaylists.containsKey(playlist.id)) {
                                        SelectorAccessory.SELECTED
                                    } else {
                                        SelectorAccessory.UNSELECTED
                                    }
                                } else {
                                    SelectorAccessory.NONE
                                },
                                onClick = {
                                    viewModel.onPlaylistTap(playlist, songIds, onDone = onDismiss)
                                }
                            )
                        }
                    }
                }
            }

            // 底部工具栏（iOS: toolbarItems = [Select, flexible, +]）
            // 顶部 hairline 全宽（iOS UIToolbar 与内容之间的系统细线）
            HairlineDivider(
                color = MaterialTheme.colorScheme.separator  // iOS .separator
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.toggleSelectMode() }) {
                    Text(
                        text = "Select",
                        fontWeight = if (isMultiSelectMode) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { viewModel.addToSelectedPlaylists(onDismiss) },
                    enabled = selectedPlaylists.isNotEmpty()
                ) {
                    Icon(
                        AmperfyIcons.plus,
                        contentDescription = "Add to selected playlists",
                        // 主题色（iOS UIKit 控件 tint 语义，随账户主题色变化）
                        tint = if (selectedPlaylists.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondaryLabel
                        }
                    )
                }
            }
        }
    }
}

private enum class SelectorAccessory { NONE, SELECTED, UNSELECTED }

/**
 * 选择器播放列表行（对齐 iOS PlaylistTableCell：封面 + 名称 + "N Songs"，
 * 无 chevron；多选时行尾勾选圈）
 */
@Composable
private fun SelectorPlaylistRow(
    playlist: Playlist,
    coverArtUrl: String?,
    accessory: SelectorAccessory,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 默认图按主题色现画（iOS ArtworkType.playlist，Playlist.swift:482-484，反色）
            val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.PLAYLIST)
            AsyncImage(
                model = coverArtUrl,
                contentDescription = playlist.name,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
                placeholder = defaultArtwork,
                error = defaultArtwork,
                fallback = defaultArtwork
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${playlist.songCount} Song${if (playlist.songCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondaryLabel
                    ),
                    maxLines = 1
                )
            }

            // 多选附件（iOS PlaylistSelectorVC.swift:305-312：选中 `.checkmark` tint 账户主题色、
            // 未选 `.circle` tint .secondaryLabelColor）。
            // **注意**：`.checkmark` 是 **UIKit 内建 `UIImage.checkmark`**（全仓无自定义重载），
            // 渲染形态为**填充圆内反挖白钩**的圆徽，不是 SF 字符串 "checkmark" 的裸勾——后者是
            // AmperfyImage.check（UIImageAssetsExtension.swift:158,301），只用于菜单单选打勾。
            // 故已选态取 [AmperfyIcons.isSelected]（checkmark.circle.fill 同形）。
            when (accessory) {
                SelectorAccessory.SELECTED -> Icon(
                    AmperfyIcons.isSelected,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
                SelectorAccessory.UNSELECTED -> Icon(
                    AmperfyIcons.circle,
                    contentDescription = "Not selected",
                    tint = MaterialTheme.colorScheme.secondaryLabel
                )
                SelectorAccessory.NONE -> {}
            }
        }
        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
        // separatorInset = cell layoutMargins 左右值，CommonScreenOperations.swift:41-47）
        HairlineDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.separator  // iOS .separator
        )
    }
}
