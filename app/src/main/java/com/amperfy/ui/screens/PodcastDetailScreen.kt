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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSPullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.IOSStyleContextMenu
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.buildPodcastContextMenuItems
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.PlayShuffleButtons
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.components.swipe.SwipeableItem
import com.amperfy.ui.components.swipe.rememberSwipeController
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * PodcastDetailScreen - 播客详情（Phase 6.4）
 * 对应 iOS: PodcastDetailVC
 *
 * - 头部：封面 + 标题 + 描述（podcast.depiction）+ "N Episode(s)" +
 *   "Newest Episode" 播放按钮（无 Shuffle，iOS DetailHeaderConfiguration:59-76）
 * - 搜索 `Search in "Podcast"` + All/Cached 作用域（Batch 4 起按单集缓存态过滤）
 * - 下拉刷新 sync(podcast:)（iOS handleRefresh）
 * - 单集行：点击播放、滑动 = 播客队列动作、More 菜单含 Delete on Server
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PodcastDetailScreen(
    onBackClick: () -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val podcast by viewModel.podcast.collectAsState()
    val episodes by viewModel.filteredEpisodes.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current

    // 删除单集缓存确认（Batch 4；长按菜单/滑动 Delete Cache 的 NeedsEpisodeCacheConfirmation 承接点）
    val pendingDeleteCacheEpisodes by viewModel.swipeCoordinator.pendingDeleteCacheEpisodes.collectAsState()
    pendingDeleteCacheEpisodes?.let { pendingEpisodes ->
        com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog(
            songCount = pendingEpisodes.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteEpisodeCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteEpisodeCache() }
        )
    }

    // 顶栏 More 菜单（与播客列表行长按同源）所需状态
    var showMoreMenu by remember { mutableStateOf(false) }
    var showPodcastDescription by remember { mutableStateOf(false) }
    val podcastMenuSettings = com.amperfy.ui.navigation.LocalSettingsManager.current
    val isShowDetailedInfo by podcastMenuSettings.isShowDetailedInfo.collectAsState()
    val isShuffleActionEnabled by podcastMenuSettings.isPlayerShuffleButtonEnabled.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    // Show Podcast Description 弹层 - 对应 iOS PlainDetailsVC.display(podcast:)
    if (showPodcastDescription) {
        PodcastDescriptionSheet(
            title = podcast?.title.orEmpty(),
            description = podcast?.depiction,
            onDismiss = { showPodcastDescription = false }
        )
    }

    // 滑动动作（播客内容类型过滤）
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

    Scaffold(
        topBar = {
            // 钉顶态：导航栏收起，钉顶搜索头贴页面最顶（列表经 innerPadding 落其下方）
            if (searchState.isPinned) {
                searchState.PinnedHeader(
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Podcast\"",
                    scopeContent = scopeRow
                )
            } else IOSNavTopBar(
                onBackClick = onBackClick,
                backTitle = "Podcasts",
                // 名称在正文 Header 展示，导航栏不显示居中标题
                // 对齐 iOS PodcastDetailVC：largeTitleDisplayMode=.never 且不调用 setNavBarTitle
                title = null,
                actions = {
                    // 顶栏 More 与播客列表行长按同源 - 对应 iOS PodcastDetailVC.swift:107
                    // `optionsButton.menu = EntityPreviewActionBuilder(container: podcast).createMenuActions()`
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(AmperfyIcons.ellipsis, contentDescription = "More options")
                        }
                        IOSStyleContextMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(-72, 48),
                            items = buildPodcastContextMenuItems(
                                env = MenuEnv(
                                    isOfflineMode = isOfflineMode,
                                    isShuffleActionEnabled = isShuffleActionEnabled,
                                    isShowDetailedInfo = isShowDetailedInfo
                                ),
                                // 播客整体的 Download/Delete Cache 仍未接线（Batch 4 只落地
                                // 单集级）；本参数只影响离线时 Play/Queue 门控，
                                // 故按当前列表是否含已缓存单集如实传入
                                hasCachedEpisodes = episodes.any { it.isDownloaded },
                                onAction = { action -> viewModel.handlePodcastAction(action) },
                                onShowDescription = { showPodcastDescription = true },
                                onCopyId = {
                                    podcast?.id?.takeIf { it.isNotEmpty() }?.let {
                                        clipboardManager.setText(AnnotatedString(it))
                                    }
                                }
                            )
                        )
                    }
                }
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .nestedScroll(nestedScrollConnection)
                    .nestedScroll(searchState.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = miniPlayerHeight)
            ) {
                // 头部（iOS GenericDetailTableHeader：封面/标题/描述 + Newest Episode 按钮）
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 头部大封面（iOS GenericDetailTableHeader 的 LibraryEntityImage）：
                        // 真实封面优先，取不到才回落主题化默认图
                        //（iOS ArtworkType.podcast，Podcast.swift:131）
                        val defaultArtwork =
                            rememberDefaultArtworkPainter(DefaultArtworkType.PODCAST)
                        AsyncImage(
                            model = buildCoverArtUrl(
                                podcast?.coverArt, credentialsManager, musicRepository
                            ),
                            contentDescription = null,
                            modifier = Modifier
                                .size(160.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                            placeholder = defaultArtwork,
                            error = defaultArtwork,
                            fallback = defaultArtwork
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = podcast?.title ?: "",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            textAlign = TextAlign.Center
                        )
                        // "N Episode(s)"（iOS PodcastDetailVC:60）
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = podcast?.info ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 描述（iOS descriptionText: podcast.depiction）
                        if (!podcast?.depiction.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = podcast?.depiction ?: "",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        // "Newest Episode" 单按钮（无 Shuffle）——与全部详情页共用
                        // iOS LibraryElementDetailTableHeaderView 的唯一实现；配置对应
                        // PodcastDetailVC.swift:88-89 `customPlayName: "Newest Episode"` +
                        // `isShuffleHidden: true`（Shuffle 键整键隐藏，Play 独占整行宽）
                        PlayShuffleButtons(
                            onPlay = { viewModel.playNewestEpisode() },
                            onShuffle = {},
                            enabled = episodes.any { it.isAvailableToUser },
                            customPlayName = "Newest Episode",
                            isShuffleHidden = true
                        )
                    }
                }

                // 搜索栏 + All/Cached 作用域（iOS configureSearchController，
                // PodcastDetailVC.swift:60，placeholder "Search in \"Podcast\""）——
                // 恰好一个 item，显隐/钉顶/焦点行为全在 LibrarySearchState 内
                librarySearchBarItem(
                    state = searchState,
                    searchText = uiState.searchText,
                    onSearchTextChanged = { viewModel.onSearchTextChanged(it) },
                    placeholder = "Search in \"Podcast\"",
                    scopeContent = scopeRow,
                    searchBarModifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )

                // 列表区与上方控件（头部/搜索栏）的交界线 = iOS .grouped 表的
                // section 顶边界线，恒全宽（0..0）
                item {
                    HairlineDivider(
                        color = MaterialTheme.colorScheme.separator
                    )
                }

                items(episodes, key = { "episode_${it.id}" }) { episode ->
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
                            showPodcastTitle = false,
                            // 对应 iOS isShowArtist = !(rootView is PodcastDetailVC)：本页内省略
                            showPodcastInMenu = false,
                            onClick = { viewModel.playEpisode(episode) },
                            onAction = { action ->
                                viewModel.handleEpisodeSwipeAction(episode, action)
                            },
                            // 本页即播客详情页，Show Podcast 已省略，回调不会被触发
                            onShowPodcast = {},
                            onDeleteOnServer = { viewModel.deleteEpisodeOnServer(episode) },
                            downloadProgressMap = downloadProgressMap
                        )
                    }
                    // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
                    // separatorInset = cell layoutMargins 左右值，
                    // CommonScreenOperations.swift:41-47），不跟随封面左缘
                    HairlineDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.separator
                    )
                }

                // 空状态
                if (episodes.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.searchText.isNotEmpty() || uiState.isCachedScope) {
                                    "No results"
                                } else {
                                    "No episodes"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
