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

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.ui.components.IOSPullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.Radio
import com.amperfy.ui.components.EntityPreviewCard
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSLongPressPreviewMenu
import com.amperfy.ui.components.contextmenu.buildRadioContextMenuItems
import com.amperfy.ui.components.contextmenu.radioPreviewInfo
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.PlayingIndicator
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * RadiosScreen - 网络电台列表（Phase 6.3）
 * 对应 iOS: RadiosVC
 *
 * - 行 = 电台图标占位 + 标题（副标题为空，iOS creatorName=""；无时长，直播流）
 * - 搜索 `Search in "Radios"`，无 All/Cached 作用域（iOS 无 scope buttons）
 * - 右侧字母索引复用 AlphabetIndex 组件
 * - 头部 Play/Shuffle + "N Radios" 信息（iOS PlayShuffleInfoConfiguration）
 * - 进入/下拉刷新同步 getInternetRadioStations
 * - 点击行以全部电台为上下文播放
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RadiosScreen(
    onBackClick: () -> Unit,
    viewModel: RadiosViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val radios by viewModel.filteredRadios.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
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
            // 钉顶态：导航栏收起，钉顶搜索头贴页面最顶（列表经 innerPadding 落其下方）
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Radios\""
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) "Radios" else null
            )
        }
    ) { padding ->
        IOSPullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.handleRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 索引条可见性（与下方 AlphabetIndex 的渲染条件同一个布尔）与行内容避让宽度：
                // 索引条可见时行内容 end 内边距改为「(条宽+最宽字形宽)/2+间隙」替代 16dp，分割线/行容器仍全宽
                // （= iOS UITableView 在 sectionIndexTitles 非空时收窄 cell.contentView，
                //   iOS 实机观测 2026-08-13，见 LocalListRowTrailingInset）
                // 该页索引条无 end 偏移（贴屏幕右缘）
                val indexBarRowEndPadding =
                    com.amperfy.ui.components.rememberIndexBarRowEndPadding()
                val rowTrailingInset = if (radios.isNotEmpty()) indexBarRowEndPadding else 0.dp

                CompositionLocalProvider(
                    com.amperfy.ui.components.LocalListRowTrailingInset provides rowTrailingInset
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(searchState.nestedScrollConnection),
                    contentPadding = PaddingValues(bottom = miniPlayerHeight)
                ) {
                    // 前置 item 1/4：大标题（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                    // iOS 风格大标题；钉顶时收起内容但保留 item 占位（字母索引 index 约定）
                    item {
                        if (!searchState.isPinned) {
                            IOSLargeTitle(
                                text = "Radios",
                                modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                            )
                        }
                    }

                    // 前置 item 2/4：搜索栏（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                    // 搜索栏（对应 iOS configureSearchController，无 scope buttons）
                    // 普通 item（不钉住）+ AnimatedVisibility：进入隐藏、下拉出现、上滑收起，搜索激活常显
                    librarySearchBarItem(
                        state = searchState,
                        searchText = uiState.searchText,
                        onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                        placeholder = "Search in \"Radios\"",
                        searchBarModifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )

                    // 前置 item 3/4：头部 Play/Shuffle（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount；
                    // 索引栏亦仅在 radios 非空时渲染，故此 item 与索引栏同进退，恒计入前置数）
                    // 头部 Play/Shuffle + 数量信息（iOS PlayShuffleInfoConfiguration，
                    // "N Radio(s)"；Shuffle 为随机挑台开始，非乱序队列）
                    if (radios.isNotEmpty()) {
                        item {
                            RadiosHeader(
                                onPlay = { viewModel.playAll() },
                                onShuffle = { viewModel.shufflePlay() }
                            )
                        }
                    }

                    // 前置 item 4/4：分隔线（增删前置 item 必须同步 AlphabetIndex 的 leadingItemCount）
                    // 列表区与上方控件的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）
                    item {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator
                        )
                    }

                    itemsIndexed(radios, key = { _, radio -> "radio_${radio.id}" }) { index, radio ->
                        RadioListItem(
                            radio = radio,
                            isPlaying = currentSong?.id == radio.id && isPlaying,
                            onClick = { viewModel.playRadio(radio) },
                            onInsertContextQueue = { viewModel.insertContextQueue(radio) },
                            onAppendContextQueue = { viewModel.appendContextQueue(radio) },
                            onAddToQueueNext = { viewModel.addToQueueNext(radio) },
                            onAddToQueueLater = { viewModel.addToQueueLater(radio) }
                        )
                        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
                        // separatorInset = cell layoutMargins 左右值，
                        // CommonScreenOperations.swift:41-47），不跟随封面左缘；
                        // 末行不画，让位给下方全宽 section 底边界线
                        if (index < radios.lastIndex) {
                            HairlineDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }

                    // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                    //（iOS 实机对照 2026-08-10）
                    if (radios.isNotEmpty()) {
                        item {
                            HairlineDivider(
                                color = MaterialTheme.colorScheme.separator
                            )
                        }
                    }
                }
                }

                // 空状态
                if (radios.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.searchText.isNotEmpty()) "No results" else "No radios",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 右侧字母索引（复用既有组件）——与 RadiosHeader 同为 radios.isNotEmpty() 条件，
                // 故索引栏渲染时 RadiosHeader 必然在场，前置 item 恒为 4
                if (radios.isNotEmpty()) {
                    com.amperfy.ui.components.AlphabetIndex(
                        items = radios,
                        listState = listState,
                        getIndexLetter = { radio ->
                            com.amperfy.utils.AlphabetIndexUtils.getIndexLetter(radio.title)
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        // 前置 item：title / searchBar / RadiosHeader / divider = 4（须与上方 LazyColumn 结构同步）
                        leadingItemCount = 4
                    )
                }
            }
        }
    }
}

/**
 * 头部 Play/Shuffle 按钮 + 数量信息
 */
@Composable
private fun RadiosHeader(
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 计数信息行**不渲染**：iOS `LibraryElementDetailTableHeaderView` 的
        // `infoContainerView.isHidden = isInfoAlwaysHidden || horizontalSizeClass == .compact`
        //（LibraryElementDetailTableHeaderView.swift:119-120）——iPhone 竖屏恒为 compact，
        // 故该行在 iPhone 上永远看不到（只在 iPad/regular 宽度出现）。
        // 注意：各**详情页**（Album/Artist/Playlist/Podcast Detail）的 info 行是另一套
        // `GenericDetailTableHeader.infoLabel`（规则为 isHidden = infoText.isEmpty），
        // 在 iPhone 上照常显示，不在本清扫范围内
        PlayShuffleButtons(
            onPlay = onPlay,
            onShuffle = onShuffle,
            // iOS RadiosVC.swift:77 传 false → 文案 "Random"
            isShuffleOnContextNecessary = false
        )
    }
}

/**
 * 电台行 - 对应 iOS PlayableTableCell（副标题空、无时长、默认电台艺术图）
 *
 * 右侧 More 菜单对应 iOS EntityPreviewVC.configureFor(radio:)（EntityPreviewVC.swift:286-299）：
 * 仅 Play + Music Queue 子菜单 + Go to Site（siteURL 非空时），
 * 无 Shuffle / 收藏 / 评分 / Add to Playlist / 下载 / Delete on Server；
 * Detailed Information 设置开启时追加 Copy ID to Clipboard
 * 离线模式（Settings→Offline Mode）下 Play 与 Music Queue 不显示（iOS isPlay/isMusicQueue
 * 受 isOfflineMode 门控，为省略而非禁灰）；Go to Site / Copy ID 不受影响
 * 长按弹预览卡 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt，菜单项与 More 完全相同）；
 * 预览卡 = 默认电台艺术图 + 标题 + 空副标题行（iOS creatorName 为空串，标签可见但为空）
 * + info 行（Site / Stream URL）+ 箭头（画但不可点，iOS 无 radio 跳转分支）
 * 行的容器结构与 modifier 顺序对齐 SongListItem（外层 Column 吃横向 16dp、内层 Row 先捕获
 * 几何再上 background/padding），以保证长按 morph 起始矩形与预览卡宽度和歌曲行一致
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RadioListItem(
    radio: Radio,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onInsertContextQueue: () -> Unit,
    onAppendContextQueue: () -> Unit,
    onAddToQueueNext: () -> Unit,
    onAddToQueueLater: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val settingsManager = com.amperfy.ui.navigation.LocalSettingsManager.current
    // Detailed Information（Settings→Display）：菜单追加 Copy ID to Clipboard
    // （对应 iOS EntityPreviewVC.swift:165-167、793-802）
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    // 离线模式：Play / Music Queue 省略（对应 iOS configureFor(radio:) 的 isOfflineMode 门控）
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    // 长按预览菜单（对应 iOS contextMenuConfigurationForRowAt）
    var showPreviewMenu by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }

    // 电台默认艺术图：按主题色现画（iOS ArtworkType.radio）。
    // **本实例只给行内这一个绘制目标用**，预览卡自己按类型建实例——2026-08-09 真机报障
    // 正是「卡片与预览卡共用一个 Painter」导致的绘制缓存串扰（见 EntityPreviewCard 注释）
    val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.RADIO)

    // 索引条可见时内容 Row 的 end 内边距总值（**替代** 16dp 而非叠加），行容器与分割线保持全宽
    // （= iOS UITableView 收窄 cell.contentView，见 LocalListRowTrailingInset）
    val trailingInset = com.amperfy.ui.components.LocalListRowTrailingInset.current

    val siteUrl = radio.siteUrl?.takeIf { it.isNotBlank() }
    val onGoToSite: () -> Unit = {
        siteUrl?.let { url ->
            // URL 非法或无浏览器时静默失败（不崩溃）
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        Unit
    }
    val onCopyId: () -> Unit = {
        if (radio.id.isNotEmpty()) {
            clipboardManager.setText(AnnotatedString(radio.id))
        }
    }
    // More 按钮菜单与长按预览菜单共用同一份菜单项（对应 iOS 同一 createMenuActions()）
    val menuItems = buildRadioContextMenuItems(
        isOfflineMode = isOfflineMode,
        siteUrl = siteUrl,
        isShowDetailedInfo = isShowDetailedInfo,
        onPlay = onClick,
        onInsertContextQueue = onInsertContextQueue,
        onAppendContextQueue = onAppendContextQueue,
        onAddToQueueNext = onAddToQueueNext,
        onAddToQueueLater = onAddToQueueLater,
        onGoToSite = onGoToSite,
        onCopyId = onCopyId
    )

    // 行容器全宽：横向 16dp 落到行内 padding（iOS cell 全宽高亮：layoutMargins 在 cell
    // 内部，BasicTableCell 覆盖为 (9,16,9,16)，CommonScreenOperations.swift:41-47）
    // ——与 SongListItem 几何一致，长按 morph 的起始矩形与预览卡宽度才与歌曲行相同
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
                // 行底色：长按 morph 时行矩形据此着色，缺省会透明（同 SongListItem）；
                // 置于点击之前，点按水波纹仍绘于其上
                .background(MaterialTheme.colorScheme.background)
                // 长按弹出预览卡片 + 上下文菜单（iOS 系统长按触觉由 UIKit 提供，此处手动触发）
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showPreviewMenu = true
                    }
                )
                // 索引条可见时 end 侧改用避让值替代 16
                .padding(start = 16.dp, end = maxOf(16.dp, trailingInset), top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 电台默认艺术图（iOS ArtworkType.radio；电台无服务器封面，恒用该图）
            Image(
                painter = defaultArtwork,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = radio.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isPlaying) {
                Spacer(modifier = Modifier.width(8.dp))
                PlayingIndicator()
            }

            // More 按钮 - 对应 iOS PlayableTableCell.optionsButton（与歌曲行视觉一致）
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        AmperfyIcons.ellipsis,
                        contentDescription = "More options",
                        // 常态 tint = .label（PlayableTableCell.swift:589）；
                        // :575 那支主题色是 Mac/iPad 指针 hover 态，触摸端不适用
                        tint = MaterialTheme.colorScheme.label
                    )
                }

                IOSStyleContextMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(-72, 48),
                    items = menuItems
                )
            }
        }

        // 长按弹出：预览卡片 + 上下文菜单（对应 iOS contextMenuConfigurationForRowAt：
        // previewProvider: EntityPreviewVC + actionProvider）
        IOSLongPressPreviewMenu(
            expanded = showPreviewMenu,
            onDismissRequest = { showPreviewMenu = false },
            anchorBoundsOnScreen = rowBoundsOnScreen,
            items = menuItems
        ) {
            EntityPreviewCard(
                // 电台无封面：用与列表行相同的默认艺术图（iOS ArtworkType.radio）
                coverArtModel = null,
                defaultArtworkType = DefaultArtworkType.RADIO,
                title = radio.title,
                // iOS Radio.creatorName 恒为空串（非 nil），artistLabel 可见但为空 →
                // 卡片保持「标题 / 空行 / info」三行，故传空串并开启占行
                subtitle = "",
                reserveSubtitleLine = true,
                info = radioPreviewInfo(radio),
                // 箭头对齐 iOS：电台不在 gotoDetailsSymbol 的隐藏条件内（只有专辑详情页里的歌曲、
                // 播客详情页里的单集才隐藏），故画箭头；但点击无跳转（iOS performPreviewTransition
                // 无 radio 分支），onClick 保持 null
                showChevron = true,
                onClick = null
            )
        }
    }
}

