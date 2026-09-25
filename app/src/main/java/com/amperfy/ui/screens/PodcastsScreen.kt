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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.local.PodcastsShowType
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.SwipeContentType
import com.amperfy.data.model.SwipeDisplaySettings
import com.amperfy.ui.components.EntityPreviewCard
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.SheetSystemBarsFix
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSLongPressPreviewMenu
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildPodcastContextMenuItems
import com.amperfy.ui.components.contextmenu.buildPodcastEpisodeContextMenuItems
import com.amperfy.ui.components.contextmenu.episodePreviewInfo
import com.amperfy.ui.components.contextmenu.podcastPreviewInfo
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.PodcastEpisodeListItem
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.components.iosOverscroll
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.sheetBackground
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * PodcastsScreen - 播客列表（Phase 6.4）
 * 对应 iOS: PodcastsVC
 *
 * - 双显示模式（iOS PodcastsShowType，排序菜单切换并持久化）：
 *   Podcasts 按名称 / Episodes 跨播客按发布日期
 * - 搜索 `Search in "Podcasts"` + All/Cached 作用域（Episodes 模式按单集缓存态过滤；
 *   Podcasts 模式的容器级聚合缓存态未接线，Cached 恒空，已知简化）
 * - 进入页面即同步 syncNewestPodcastEpisodes（iOS 无下拉刷新）
 * - Podcasts 模式点击行进详情；Episodes 模式点击行播放，行滑动 = 播客队列动作
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PodcastsScreen(
    onBackClick: () -> Unit,
    onNavigateToPodcastDetail: (String) -> Unit,
    viewModel: PodcastsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val showType by viewModel.showType.collectAsState()
    val podcasts by viewModel.filteredPodcasts.collectAsState()
    val episodes by viewModel.filteredEpisodes.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    // 删除单集缓存确认（Batch 4；长按菜单/滑动 Delete Cache 的 NeedsEpisodeCacheConfirmation 承接点）
    val pendingDeleteCacheEpisodes by viewModel.swipeCoordinator.pendingDeleteCacheEpisodes.collectAsState()
    pendingDeleteCacheEpisodes?.let { pendingEpisodes ->
        com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog(
            songCount = pendingEpisodes.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteEpisodeCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteEpisodeCache() }
        )
    }

    // 滑动动作（Episodes 行；播客内容类型过滤，单集不可收藏/加播放列表）
    val swipeSettings by viewModel.swipeActionSettings.collectAsState()
    val leadingSwipeActions = remember(swipeSettings, isOfflineMode) {
        filterEpisodeSwipeActions(swipeSettings.leading, isOfflineMode)
    }
    val trailingSwipeActions = remember(swipeSettings, isOfflineMode) {
        filterEpisodeSwipeActions(swipeSettings.trailing, isOfflineMode)
    }
    val swipeController = rememberSwipeController()
    val swipeScope = rememberCoroutineScope()
    val nestedScrollConnection = remember(swipeController) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (available.y != 0f) {
                    swipeController.closeCurrentItem(swipeScope)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    var showSortMenu by remember { mutableStateOf(false) }

    // 搜索栏行为全在 LibrarySearchState 内（进入隐藏/下拉出现/上滑收起/短内容常驻/搜索激活常显），
    // 本页只给数据；显隐与 iOS prefersLargeTitles 无关（后者只管标题形态）。
    val searchState = rememberLibrarySearchState(listState)

    // 作用域行仅在搜索激活时显示（iOS scope buttons 默认行为）；隐藏时重置回 All
    val showScopeBar = searchState.isPinned || uiState.searchText.isNotEmpty()
    LaunchedEffect(showScopeBar) {
        if (!showScopeBar) viewModel.setCachedScope(false)
    }

    // All/Cached 作用域段（钉顶头与列表内共用同一渲染）
    val scopeRow: @Composable () -> Unit = {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            listOf(false, true).forEachIndexed { index, cached ->
                SegmentedButton(
                    selected = uiState.isCachedScope == cached,
                    onClick = { viewModel.setCachedScope(cached) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    label = { Text(if (cached) "Cached" else "All") }
                )
            }
        }
    }

    val showCollapsedTitle by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    Scaffold(
        topBar = {
            // 钉顶态：导航栏收起，钉顶搜索头贴页面最顶（列表经 innerPadding 落其下方）
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Podcasts\"",
                    scopeContent = scopeRow
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Library",
                title = if (showCollapsedTitle) "Podcasts" else null,
                actions = {
                    // 排序菜单（iOS SortBarButton：两个显示模式切换，PodcastsVC.swift:251-279）
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(AmperfyIcons.ellipsis, contentDescription = "More")
                        }
                        IOSStyleContextMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(-24, 48),
                            items = listOf(
                                IOSContextMenuItem.Submenu(
                                    text = "Sort",
                                    icon = AmperfyIcons.sort,
                                    items = PodcastsShowType.entries.map { type ->
                                        IOSContextMenuItem.Action(
                                            text = type.displayName,
                                            icon = if (type == showType) AmperfyIcons.check else null,
                                            onClick = {
                                                viewModel.setShowType(type)
                                                showSortMenu = false
                                            }
                                        )
                                    }
                                )
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
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
                    .nestedScroll(nestedScrollConnection)
                    .nestedScroll(searchState.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = miniPlayerHeight)
            ) {
                // 钉顶时收起大标题内容但保留 item 占位
                item {
                    if (!searchState.isPinned) {
                        IOSLargeTitle(
                            text = "Podcasts",
                            modifier = Modifier.padding( start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp )
                        )
                    }
                }

                // 搜索栏 + All/Cached 作用域（iOS configureSearchController，
                // PodcastsVC.swift:57，scopeButtonTitles ["All","Cached"]）——恰好一个 item，
                // 显隐/钉顶/焦点行为全在 LibrarySearchState 内
                librarySearchBarItem(
                    state = searchState,
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Podcasts\"",
                    scopeContent = scopeRow,
                    searchBarModifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )

                // 列表区与上方控件（标题/搜索栏）的交界线 = iOS .grouped 表的
                // section 顶边界线，恒全宽（0..0），不吃 separatorInset
                val hasRows = when (showType) {
                    PodcastsShowType.PODCASTS -> podcasts.isNotEmpty()
                    PodcastsShowType.EPISODES_SORTED_BY_RELEASE_DATE -> episodes.isNotEmpty()
                }
                if (hasRows) {
                    item {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator
                        )
                    }
                }

                when (showType) {
                    PodcastsShowType.PODCASTS -> {
                        itemsIndexed(
                            podcasts,
                            key = { _, podcast -> "podcast_${podcast.id}" }
                        ) { index, podcast ->
                            PodcastListItem(
                                podcast = podcast,
                                onClick = { onNavigateToPodcastDetail(podcast.id) },
                                onAction = { action ->
                                    viewModel.handlePodcastSwipeAction(podcast, action)
                                }
                            )
                            // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
                            // separatorInset = cell layoutMargins 左右值，
                            // CommonScreenOperations.swift:41-47），不跟随封面左缘；
                            // 末行不画，让位给下方全宽 section 底边界线
                            if (index < podcasts.lastIndex) {
                                HairlineDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    color = MaterialTheme.colorScheme.separator
                                )
                            }
                        }
                    }
                    PodcastsShowType.EPISODES_SORTED_BY_RELEASE_DATE -> {
                        itemsIndexed(
                            episodes,
                            key = { _, episode -> "episode_${episode.id}" }
                        ) { index, episode ->
                            SwipeableItem(
                                key = "episode_${episode.id}",
                                swipeController = swipeController,
                                leadingActions = leadingSwipeActions,
                                trailingActions = trailingSwipeActions,
                                onSwipeAction = { viewModel.handleEpisodeSwipeAction(episode, it) }
                            ) {
                                PodcastEpisodeRow(
                                    episode = episode,
                                    isPlaying = currentSong?.id == episode.id && isPlaying,
                                    showPodcastTitle = true,
                                    // Episodes 模式不在播客详情页内，Show Podcast 显示
                                    showPodcastInMenu = true,
                                    onClick = { viewModel.playEpisode(episode) },
                                    onAction = { action ->
                                        viewModel.handleEpisodeSwipeAction(episode, action)
                                    },
                                    onShowPodcast = { onNavigateToPodcastDetail(episode.podcastId) },
                                    onDeleteOnServer = { viewModel.deleteEpisodeOnServer(episode) },
                                    downloadProgressMap = downloadProgressMap
                                )
                            }
                            // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（同上）；
                            // 末行不画，让位给下方全宽 section 底边界线
                            if (index < episodes.lastIndex) {
                                HairlineDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    color = MaterialTheme.colorScheme.separator
                                )
                            }
                        }
                    }
                }

                // 列表末尾 = iOS .grouped 表的 section 底边界线，恒全宽（0..0）
                //（iOS 实机对照 2026-08-10）
                if (hasRows) {
                    item {
                        HairlineDivider(
                            color = MaterialTheme.colorScheme.separator
                        )
                    }
                }
            }

            // 空状态
            val isEmpty = when (showType) {
                PodcastsShowType.PODCASTS -> podcasts.isEmpty()
                PodcastsShowType.EPISODES_SORTED_BY_RELEASE_DATE -> episodes.isEmpty()
            }
            if (isEmpty) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.searchText.isNotEmpty() || uiState.isCachedScope) {
                            "No results"
                        } else {
                            "No podcasts"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 播客行 - 对应 iOS GenericTableCell（标题 + "N Episode(s)"，subtitle 为空）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastListItem(
    podcast: Podcast,
    onClick: () -> Unit,
    onAction: (SwipeActionType) -> Unit
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val settingsManager = LocalSettingsManager.current
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()

    var showPreviewMenu by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }

    val menuItems = buildPodcastContextMenuItems(
        env = MenuEnv(
            isOfflineMode = isOfflineMode,
            isShuffleActionEnabled = isShuffleActionEnabled,
            isShowDetailedInfo = isShowDetailedInfo
        ),
        // 容器（播客）级聚合缓存态未接线，恒为 false——Batch 4 只落地单集级
        // Download/Delete Cache，播客整体的下载/删缓存仍为已知简化
        hasCachedEpisodes = false,
        onAction = onAction,
        onShowDescription = { showDescription = true },
        onCopyId = {
            if (podcast.id.isNotEmpty()) {
                clipboardManager.setText(AnnotatedString(podcast.id))
            }
        }
    )

    // 行内封面默认图（iOS GenericTableCell.entityImage = LibraryEntityImage）：真实封面优先，
    // 取不到才回落主题化默认图（iOS ArtworkType.podcast，Podcast.swift:131）。
    // **本实例只给行内这一个绘制目标用**，预览卡自己按类型建实例（见 EntityPreviewCard 注释）
    val defaultArtwork = rememberDefaultArtworkPainter(DefaultArtworkType.PODCAST)

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
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showPreviewMenu = true
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = buildCoverArtUrl(podcast.coverArt, credentialsManager, musicRepository),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
            placeholder = defaultArtwork,
            error = defaultArtwork,
            fallback = defaultArtwork
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = podcast.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = podcast.info,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            AmperfyIcons.chevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }

        // 长按弹出：预览卡片 + 上下文菜单
        IOSLongPressPreviewMenu(
            expanded = showPreviewMenu,
            onDismissRequest = { showPreviewMenu = false },
            anchorBoundsOnScreen = rowBoundsOnScreen,
            items = menuItems
        ) {
            EntityPreviewCard(
                coverArtModel = buildCoverArtUrl(podcast.coverArt, credentialsManager, musicRepository),
                defaultArtworkType = DefaultArtworkType.PODCAST,
                title = podcast.title,
                // iOS Podcast subtitle 为空（Podcast.swift:88-89）
                subtitle = null,
                info = podcastPreviewInfo(podcast, isShowDetailedInfo),
                // 对应 iOS performPreviewTransition 的 podcast 分支：点卡片进播客详情
                showChevron = true,
                onClick = {
                    showPreviewMenu = false
                    onClick()
                }
            )
        }

        // 描述弹层 - 对应 iOS PlainDetailsVC.display(podcast:)
        if (showDescription) {
            PodcastDescriptionSheet(
                title = podcast.title,
                description = podcast.depiction,
                onDismiss = { showDescription = false }
            )
        }
    }
}

/**
 * 单集行（含 More 下拉 + 长按预览菜单 + 描述弹层）
 * 对应 iOS PodcastEpisodeTableCell + contextMenuConfigurationForRowAt
 *
 * More 与长按共用同一份 items（集中构建器 buildPodcastEpisodeContextMenuItems），
 * 对齐 iOS 两处同为 EntityPreviewActionBuilder 产出。
 *
 * @param showPodcastInMenu Show Podcast 门控，对应 iOS `!(rootView is PodcastDetailVC)`
 * @param onAction 动作执行入口（PLAY / INSERT_PODCAST_QUEUE / APPEND_PODCAST_QUEUE），
 *   回落各 VM 的 handleEpisodeSwipeAction（与滑动手势同一路径）
 */
@Composable
internal fun PodcastEpisodeRow(
    episode: PodcastEpisode,
    isPlaying: Boolean,
    showPodcastTitle: Boolean,
    showPodcastInMenu: Boolean,
    onClick: () -> Unit,
    onAction: (SwipeActionType) -> Unit,
    onShowPodcast: () -> Unit,
    onDeleteOnServer: () -> Unit,
    /** 下载进度映射（Batch 4，行内缓存图标/进度环） */
    downloadProgressMap: Map<String, com.amperfy.data.download.DownloadManager.DownloadProgress> = emptyMap(),
    /** 右侧附件：非空时替代 More 按钮（Downloads 页状态附件），长按菜单仍可用 */
    trailingContent: (@Composable () -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val settingsManager = LocalSettingsManager.current
    val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
    val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()
    val credentialsManager = LocalCredentialsManager.current
    val mediaUrlRepository = LocalMediaUrlRepository.current

    var showMenu by remember { mutableStateOf(false) }
    var showPreviewMenu by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var rowBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }

    val menuItems = buildPodcastEpisodeContextMenuItems(
        episode = episode,
        env = MenuEnv(
            isOfflineMode = isOfflineMode,
            isShuffleActionEnabled = isShuffleActionEnabled,
            isShowDetailedInfo = isShowDetailedInfo
        ),
        showPodcast = showPodcastInMenu,
        onAction = onAction,
        onShowPodcast = onShowPodcast,
        onShowDescription = { showDescription = true },
        onDeleteOnServer = onDeleteOnServer,
        onCopyId = {
            if (episode.id.isNotEmpty()) {
                clipboardManager.setText(AnnotatedString(episode.id))
            }
        }
    )

    Box {
        PodcastEpisodeListItem(
            episode = episode,
            isPlaying = isPlaying,
            onClick = onClick,
            // More（⋯）与右侧附件互不排斥：iOS optionsButton 的判据是
            // `(playContextCb != nil) && (playerIndexCb == nil)`（PlayableTableCell.swift:408/412），
            // 与 accessoryView 无关——Downloads 页（DownloadsVC.swift:122-127 传 playContextCb
            // 且带 download）两者同现。本行组件的三个宿主页（Podcasts / PodcastDetail /
            // Downloads）都是普通浏览态，故只要有菜单项就显示 ⋯
            onMenuClick = if (menuItems.isEmpty()) null else ({ showMenu = true }),
            showPodcastTitle = showPodcastTitle,
            onLongClick = { showPreviewMenu = true },
            onRowBoundsChanged = { rowBoundsOnScreen = it },
            downloadProgressMap = downloadProgressMap,
            trailingContent = trailingContent
        )
        if (menuItems.isNotEmpty()) {
            IOSStyleContextMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                alignment = Alignment.TopEnd,
                offset = IntOffset(-24, 0),
                items = menuItems
            )
        }
    }

    // 长按弹出：预览卡片 + 上下文菜单
    IOSLongPressPreviewMenu(
        expanded = showPreviewMenu,
        onDismissRequest = { showPreviewMenu = false },
        anchorBoundsOnScreen = rowBoundsOnScreen,
        items = menuItems
    ) {
        EntityPreviewCard(
            coverArtModel = buildCoverArtUrl(episode.coverArt, credentialsManager, mediaUrlRepository),
            // iOS ArtworkType.podcastEpisode（AbstractPlayable.swift:395-396）
            defaultArtworkType = DefaultArtworkType.PODCAST_EPISODE,
            title = episode.title,
            // iOS 单集 subtitle = creatorName = 播客名
            subtitle = episode.podcastTitle.takeIf { it.isNotBlank() },
            info = episodePreviewInfo(episode, isShowDetailedInfo),
            // iOS isNavigationDisallowed：播客详情页内的单集不可导航（隐藏箭头且点卡片无跳转）
            showChevron = showPodcastInMenu,
            onClick = if (showPodcastInMenu) {
                {
                    showPreviewMenu = false
                    onShowPodcast()
                }
            } else {
                null
            }
        )
    }

    // 描述弹层 - 对应 iOS PlainDetailsVC.display(podcastEpisode:)
    if (showDescription) {
        PodcastDescriptionSheet(
            title = episode.title,
            description = episode.depiction,
            onDismiss = { showDescription = false }
        )
    }
}

/**
 * 描述弹层 - 对应 iOS PlainDetailsVC（Show Podcast/Episode Description 的展示形态）
 * 自下而上模态，标题 + 正文；无描述时显示占位文案
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodcastDescriptionSheet(
    title: String,
    description: String?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 模态弹层底色取 elevated 提升层的 systemBackground
        containerColor = MaterialTheme.colorScheme.sheetBackground,
        // 顶圆角 10dp（对齐 iOS pageSheet 规格）；本弹层非全高，不涉顶边避让
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
    ) {
        // sheet 独立窗口的系统栏图标明暗归位（否则状态栏在弹出瞬间变黑）
        SheetSystemBarsFix()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                // iOS 式过滚阻尼：必须排在 verticalScroll **之前**（作其祖先节点）
                .iosOverscroll()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description?.takeIf { it.isNotBlank() } ?: "No Description",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 单集行滑动动作过滤：播客内容类型 + 不可收藏。
 *
 * Batch 4 起 DOWNLOAD/REMOVE_FROM_CACHE 不再额外剔除（单集下载管线已落地），
 * 离线时 DOWNLOAD 由 [SwipeDisplaySettings.filter] 统一隐藏。
 */
internal fun filterEpisodeSwipeActions(
    actions: List<com.amperfy.data.model.SwipeActionType>,
    isOfflineMode: Boolean
): List<com.amperfy.data.model.SwipeActionType> {
    return SwipeDisplaySettings.filter(
        actions, SwipeContentType.PODCAST, isOfflineMode, isFavoritable = false
    )
}
