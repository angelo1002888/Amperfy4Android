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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.LibraryDisplayType
import com.amperfy.data.model.Song
import com.amperfy.ui.components.AlphabetIndex
import com.amperfy.ui.components.GroupedSectionHeader
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.IOSNavTopBar
import com.amperfy.ui.components.LibrarySearchState
import com.amperfy.ui.components.PinnedHeader
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.ui.components.iosOverscroll
import com.amperfy.ui.components.librarySearchBarItem
import com.amperfy.ui.components.rememberLibrarySearchState
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.sheetBackground
import com.amperfy.utils.AlphabetIndexUtils

/**
 * PlaylistAddSongsScreen - 向播放列表添加歌曲（资料库分类浏览多选）
 *
 * 对应 iOS: PlaylistAddLibraryVC + PlaylistAdd 系列 VC + AddToPlaylistManager：
 * - 根页 = 分类入口（addToPlaylistSettings 固定 11 项），逐层下钻后勾选歌曲
 * - 导航栏标题随选择数动态变化：「Add N Songs to "<playlist>"」；右上 Done 提交；
 *   根页左上为 Close(X)（iOS: UIBarButtonItem.createCloseBarButton，PlaylistAddLibraryVC.swift:333-338）
 * - 歌曲页底部工具栏「All」批量选择/取消
 * - 与播放列表重复的歌曲弹确认（Add Duplicate(s)/Skip/Cancel）
 * - >100 首提交时弹性能警告（Add Songs Anyway/Abort/Cancel）
 * - 除根页与播放列表详情页外，每级带搜索栏——Add 流程各 VC 全部走基类
 *   `BasicTableViewController.configureSearchController`（`hidesSearchBarWhenScrolling = true`），
 *   即与全仓其它页面完全相同的统一行为，故 Android 侧一律接 [LibrarySearchState] 单点机制：
 *   进入隐藏、到顶下拉唤出、上滑收起、All/Cached 作用域段仅搜索激活时显示、搜索激活时
 *   导航栏收起（本页对应 = 钉顶头 PinnedHeader 顶替 Scaffold 顶栏）。占位文案与作用域有无
 *   逐页对齐各 VC 的 configureSearchController；5 个平铺页带右侧字母索引
 *
 * 形态：本界面由 PlaylistDetailScreen 以 ModalBottomSheet 承载（对齐 iOS 独立模态导航栈），
 * 不再是导航图中的全屏路由；页面栈仍由 ViewModel 维护，栈内切换用 AnimatedContent 做
 * push/pop 水平转场（规格与导航层 iosPushEnter/iosPushExit 一致）。
 *
 * 已知简化：
 * - pop 回父页时列表滚动位置不保留（AnimatedContent 重建页面内容）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistAddSongsScreen(
    onClose: () -> Unit,
    viewModel: PlaylistAddSongsViewModel = hiltViewModel()
) {
    // 会话重置：ViewModel 生命周期随宿主播放列表详情页存续（iOS 每次 present 都是新建的 VC 栈），
    // 故 sheet 每次打开都须清上次残留（选择/页面栈/两个确认弹窗）。
    // 用 rememberSaveable 而非 remember：屏幕旋转等重建不是「新会话」，不应清空已勾选歌曲。
    rememberSaveable { viewModel.resetSession(); true }

    val pageStack by viewModel.pageStack.collectAsState()
    val title by viewModel.title.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val playlist by viewModel.playlist.collectAsState()
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsState()
    val performanceWarningCount by viewModel.performanceWarningCount.collectAsState()
    // sheet 覆盖在 MiniPlayer 之上（iOS pageSheet 同为全覆盖），底部只需避让系统导航栏
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 栈顶页的搜索接线上提：搜索获得焦点即钉顶，此时钉顶头须顶替 Scaffold 的 topBar
    //（对齐 iOS hidesNavigationBarDuringPresentation），而搜索态在 AnimatedContent 各页内部，
    // 故由页面自己注册到这里；无搜索的页（根页/播放列表详情）注册 null
    val topSearchUi = remember { mutableStateOf<AddSearchUi?>(null) }

    // 系统返回：先回退内部页面栈，根页才关闭整个 sheet
    // （宿主 ModalBottomSheet 已置 shouldDismissOnBackPress = false，返回键交由本处理器）
    BackHandler { if (!viewModel.pop()) onClose() }

    // 重复歌曲确认（iOS: AddToPlaylistManager.toggleSelection 的 UIAlertController）
    duplicatePrompt?.let { prompt ->
        val useSingular = !prompt.isBulk
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicatePrompt() },
            text = {
                Text(
                    if (useSingular) "This Song is already in your Playlist."
                    else "Some Songs are already in your Playlist."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.confirmAddDuplicates() }) {
                        Text(if (useSingular) "Add Duplicate" else "Add Duplicates")
                    }
                    if (useSingular) {
                        TextButton(onClick = { viewModel.dismissDuplicatePrompt() }) {
                            Text("Skip")
                        }
                    } else {
                        TextButton(onClick = { viewModel.skipDuplicates() }) {
                            Text("Skip Duplicates")
                        }
                        TextButton(onClick = { viewModel.dismissDuplicatePrompt() }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        )
    }

    // 大批量性能警告（iOS: doneBarButtonPressed，warningElementsToAddCount = 100）
    performanceWarningCount?.let { count ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPerformanceWarning() },
            text = {
                Text(
                    "Adding $count Songs to this Playlist may cause performance issues " +
                        "during server synchronization."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.confirmPerformanceWarning(onClose) }) {
                        Text("Add Songs Anyway")
                    }
                    TextButton(onClick = { viewModel.abortPerformanceWarning(onClose) }) {
                        Text("Abort")
                    }
                    TextButton(onClick = { viewModel.dismissPerformanceWarning() }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    Scaffold(
        // 本页由 PlaylistDetailScreen 以 pageSheet 模态承载，页底取 systemBackground @elevated
        // （iOS PlaylistAdd 全族 VC 的 tableView.backgroundColor = .backgroundColor）
        containerColor = MaterialTheme.colorScheme.sheetBackground,
        topBar = {
            val searchUi = topSearchUi.value
            if (searchUi != null && searchUi.searchState.isPinned) {
                // 钉顶态：钉顶搜索头顶替导航栏（iOS UISearchController
                // hidesNavigationBarDuringPresentation = true）；sheet 顶边已止于状态栏下缘，
                // 故不再避让状态栏，底色与 sheet 同（elevated）
                searchUi.searchState.PinnedHeader(
                    searchText = searchUi.search.text,
                    onSearchTextChanged = { searchUi.search.text = it },
                    placeholder = searchUi.placeholder,
                    scopeContent = searchUi.scopeContent,
                    backgroundColor = MaterialTheme.colorScheme.sheetBackground,
                    withStatusBarPadding = false
                )
            } else if (pageStack.size == 1) {
                // 根页：左上 Close(X)（iOS PlaylistAddLibraryVC.swift:333-338 createCloseBarButton）
                // —— IOSNavTopBar 的导航位固定为 chevron 返回按钮，故根页顶栏在此单独组装
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(AmperfyIcons.xmark, contentDescription = "Close")
                        }
                    },
                    actions = {
                        // iOS: Done 恒可点（空选择 = 直接关闭）
                        TextButton(onClick = { viewModel.done(onClose) }) { Text("Done") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.sheetBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            } else {
                IOSNavTopBar(
                    onBackClick = { if (!viewModel.pop()) onClose() },
                    // 子页返回按钮 = 上一页短标签（根页时显示播放列表名）
                    backTitle = pageStack[pageStack.size - 2].let { prev ->
                        if (prev is AddPage.Root) playlist?.name ?: "Back" else prev.backLabel
                    },
                    title = title,
                    actions = {
                        TextButton(onClick = { viewModel.done(onClose) }) { Text("Done") }
                    },
                    // 顶栏与 sheet 同底（elevated）
                    backgroundColor = MaterialTheme.colorScheme.sheetBackground
                )
            }
        }
    ) { padding ->
        // 页面栈内 push/pop 水平转场（规格复刻 AmperfyNavigation.kt 的 iosPushEnter/iosPushExit：
        // 350ms + FastOutSlowInEasing，push = 新页全宽右→左滑入 + 旧页左移 1/3 视差，pop 反向）。
        // targetState 取整栈：栈变长 = push、变短 = pop；contentKey 取栈顶页，同页刷新不重播动画。
        AnimatedContent(
            targetState = pageStack,
            modifier = Modifier
                .fillMaxSize()
                // 只吃 Scaffold 的顶部内边距（顶栏高度）：底部导航栏留白由各页自己以
                // navigationBars inset 落在「All」工具栏/列表尾，避免两处各留一份
                .padding(top = padding.calculateTopPadding()),
            contentKey = { it.last() },
            transitionSpec = {
                val spec = tween<IntOffset>(NAV_TRANSITION_MS, easing = FastOutSlowInEasing)
                val isPush = targetState.size > initialState.size
                val transform = if (isPush) {
                    slideInHorizontally(spec) { it } togetherWith
                        slideOutHorizontally(spec) { -it / 3 }
                } else {
                    slideInHorizontally(spec) { -it / 3 } togetherWith
                        slideOutHorizontally(spec) { it }
                }
                // 层级：push 时新页盖旧页、pop 时旧页盖父页（栈长即层级，与 iOS 导航栈一致）
                transform.apply { targetContentZIndex = targetState.size.toFloat() }
            },
            label = "AddPagePush"
        ) { stack ->
            // 必须读 lambda 参数而非外层 pageStack：转场期间新旧两帧各自渲染自己的页面
            AddPageContent(
                page = stack.last(),
                viewModel = viewModel,
                selectedIds = selectedIds,
                bottomPadding = bottomPadding,
                // 转场期间新旧两帧并存，只有当前栈那帧是「栈顶」，由它注册顶栏用的搜索接线
                isTop = stack == pageStack,
                registerSearchUi = { topSearchUi.value = it }
            )
        }
    }
}

/** 转场时长（与 AmperfyNavigation.NAV_TRANSITION_MS 同规格） */
private const val NAV_TRANSITION_MS = 350

/**
 * 单个页面的内容（含该页搜索栏/字母索引/勾选列表）
 *
 * 搜索态为页面局部态（[rememberAddSearchUi] 以 page 为 key），切页即重置——
 * 对齐 iOS 每次 push 都是新建 VC、搜索框为空。搜索栏本身走 [LibrarySearchState] 统一机制
 *（列表内 item，进入隐藏、下拉唤出、上滑收起），钉顶时由 [registerSearchUi] 上提给顶栏。
 *
 * @param isTop 本帧是否为当前栈顶（转场期间新旧两帧并存，只有栈顶帧注册搜索接线）
 * @param registerSearchUi 把本页搜索接线注册给 Screen 顶栏；无搜索的页注册 null
 */
@Composable
private fun AddPageContent(
    page: AddPage,
    viewModel: PlaylistAddSongsViewModel,
    selectedIds: Set<String>,
    bottomPadding: Dp,
    isTop: Boolean,
    registerSearchUi: (AddSearchUi?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // 页底与宿主 sheet 同色（elevated），转场期间两页同底无色差
            .background(MaterialTheme.colorScheme.sheetBackground)
    ) {
        when (page) {
            is AddPage.Root -> {
                // 根分类页无搜索（iOS PlaylistAddLibraryVC 无 configureSearchController）
                SideEffect { if (isTop) registerSearchUi(null) }
                AddRootPage(
                    bottomPadding = bottomPadding,
                    onSelect = { type ->
                        when (type) {
                            LibraryDisplayType.GENRES -> viewModel.push(AddPage.GenreList)
                            LibraryDisplayType.ARTISTS ->
                                viewModel.push(AddPage.ArtistList(favoritesOnly = false))
                            LibraryDisplayType.FAVORITE_ARTISTS ->
                                viewModel.push(AddPage.ArtistList(favoritesOnly = true))
                            LibraryDisplayType.ALBUMS ->
                                viewModel.push(AddPage.AlbumList(AddPage.AlbumListKind.ALL))
                            LibraryDisplayType.FAVORITE_ALBUMS ->
                                viewModel.push(AddPage.AlbumList(AddPage.AlbumListKind.FAVORITES))
                            LibraryDisplayType.NEWEST_ALBUMS ->
                                viewModel.push(AddPage.AlbumList(AddPage.AlbumListKind.NEWEST))
                            LibraryDisplayType.RECENT_ALBUMS ->
                                viewModel.push(AddPage.AlbumList(AddPage.AlbumListKind.RECENT))
                            LibraryDisplayType.SONGS ->
                                viewModel.push(AddPage.SongList(favoritesOnly = false))
                            LibraryDisplayType.FAVORITE_SONGS ->
                                viewModel.push(AddPage.SongList(favoritesOnly = true))
                            LibraryDisplayType.DIRECTORIES ->
                                viewModel.push(AddPage.MusicFolderList)
                            LibraryDisplayType.PLAYLISTS -> viewModel.push(AddPage.PlaylistList)
                            else -> {}
                        }
                    }
                )
            }

            AddPage.GenreList -> {
                // iOS PlaylistAddGenresVC.swift:54-56
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Search in \"Genres\""
                )
                val search = ui.search
                val genres by remember(page) { viewModel.genres() }
                    .collectAsState(initial = emptyList())
                val cachedNames by remember(page) { viewModel.cachedGenreNames() }
                    .collectAsState(initial = emptySet())
                val visible = remember(genres, cachedNames, search.text, search.isCached) {
                    genres.filterByName(search, { it.name }) { it.name in cachedNames }
                }
                BrowsePage(
                    entries = visible,
                    key = { it.name },
                    ui = ui,
                    indexLetter = { AlphabetIndexUtils.getIndexLetter(it.name) },
                    bottomPadding = bottomPadding
                ) { genre ->
                    BrowseRow(
                        title = genre.name,
                        subtitle = genre.info,
                        onClick = { viewModel.push(AddPage.GenreDetail(genre.name)) }
                    )
                }
            }

            is AddPage.ArtistList -> {
                // iOS PlaylistAddArtistsVC.swift:79-81（placeholder 插值 sceneTitle）
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = if (page.favoritesOnly) "Search in \"Favorite Artists\""
                    else "Search in \"Artists\""
                )
                val search = ui.search
                val artists by remember(page) { viewModel.artists(page.favoritesOnly) }
                    .collectAsState(initial = emptyList())
                val cachedIds by remember(page) { viewModel.cachedArtistIds() }
                    .collectAsState(initial = emptySet())
                val visible = remember(artists, cachedIds, search.text, search.isCached) {
                    artists.filterByName(search, { it.name }) { it.id in cachedIds }
                }
                BrowsePage(
                    entries = visible,
                    key = { it.id },
                    ui = ui,
                    indexLetter = { AlphabetIndexUtils.getIndexLetter(it.name) },
                    bottomPadding = bottomPadding
                ) { artist ->
                    BrowseRow(
                        title = artist.name,
                        onClick = { viewModel.push(AddPage.ArtistDetail(artist.id, artist.name)) }
                    )
                }
            }

            is AddPage.AlbumList -> {
                // iOS PlaylistAddAlbumsVC.swift:93-95（placeholder 插值 common.filterTitle）
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Search in \"${page.kind.label}\""
                )
                val search = ui.search
                val albums by remember(page) { viewModel.albums(page.kind) }
                    .collectAsState(initial = emptyList())
                val cachedIds by remember(page) { viewModel.cachedAlbumIds() }
                    .collectAsState(initial = emptySet())
                val visible = remember(albums, cachedIds, search.text, search.isCached) {
                    albums.filterByName(search, { it.name }) { it.id in cachedIds }
                }
                // Newest/Recently Played 为服务器给定顺序、非字母序 —— 隐藏字母索引
                // （iOS PlaylistAddAlbumsVC.swift:77-79 common.isIndexTitelsHidden）
                val isIndexTitelsHidden = page.kind == AddPage.AlbumListKind.NEWEST ||
                    page.kind == AddPage.AlbumListKind.RECENT
                BrowsePage(
                    entries = visible,
                    key = { it.id },
                    ui = ui,
                    indexLetter = if (isIndexTitelsHidden) null
                    else ({ album -> AlphabetIndexUtils.getIndexLetter(album.name) }),
                    bottomPadding = bottomPadding
                ) { album ->
                    BrowseRow(
                        title = album.name,
                        subtitle = album.artist,
                        onClick = { viewModel.push(AddPage.AlbumDetail(album.id, album.name)) }
                    )
                }
            }

            is AddPage.SongList -> {
                // iOS PlaylistAddSongsVC.swift:65-67（placeholder 插值 sceneTitle）
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = if (page.favoritesOnly) "Search in \"Favorite Songs\""
                    else "Search in \"Songs\""
                )
                val search = ui.search
                val songs by remember(page) { viewModel.songs(page.favoritesOnly) }
                    .collectAsState(initial = emptyList())
                val visible = remember(songs, search.text, search.isCached) {
                    songs.filterSongs(search)
                }
                SectionedSelectPage(
                    songs = visible,
                    selectedIds = selectedIds,
                    bottomPadding = bottomPadding,
                    onToggle = { viewModel.toggleSong(it) },
                    onToggleAll = { viewModel.toggleAll(visible) },
                    ui = ui,
                    // leadingCount = 1：只有搜索栏 item（歌曲平铺页无段头，
                    // iOS PlaylistAddSongsVC.swift:182-198 name 排序 heightForHeader 恒 0）
                    indexContent = {
                        AddAlphabetIndex(visible, ui.listState, leadingCount = 1) {
                            AlphabetIndexUtils.getIndexLetter(it.title)
                        }
                    },
                    showSongsHeader = false
                )
            }

            AddPage.PlaylistList -> {
                // iOS PlaylistAddPlaylistsVC.swift:103-111（Subsonic 分支作用域 = All/Cached）
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Search in \"Playlists\""
                )
                val search = ui.search
                val playlists by remember(page) { viewModel.playlists() }
                    .collectAsState(initial = emptyList())
                val cachedIds by remember(page) { viewModel.cachedPlaylistIds() }
                    .collectAsState(initial = emptySet())
                val visible = remember(playlists, cachedIds, search.text, search.isCached) {
                    playlists.filterByName(search, { it.name }) { it.id in cachedIds }
                }
                BrowsePage(
                    entries = visible,
                    key = { it.id },
                    ui = ui,
                    indexLetter = { AlphabetIndexUtils.getIndexLetter(it.name) },
                    bottomPadding = bottomPadding
                ) { pl ->
                    BrowseRow(
                        title = pl.name,
                        subtitle = "${pl.songCount} Songs",
                        onClick = { viewModel.push(AddPage.PlaylistDetail(pl.id, pl.name)) }
                    )
                }
            }

            AddPage.MusicFolderList -> {
                // iOS PlaylistAddMusicFoldersVC.swift:55（无 scopeButtonTitles）
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Search in \"Directories\"",
                    hasScope = false
                )
                val search = ui.search
                val folders by remember(page) { viewModel.musicFolders() }
                    .collectAsState(initial = emptyList())
                val visible = remember(folders, search.text) {
                    folders.filterByName(search, { it.name }) { true }
                }
                BrowsePage(
                    entries = visible,
                    key = { it.id },
                    ui = ui,
                    // iOS MusicFoldersVC 无索引（目录层级非字母分段）
                    indexLetter = null,
                    bottomPadding = bottomPadding
                ) { folder ->
                    BrowseRow(
                        title = folder.name,
                        onClick = { viewModel.push(AddPage.FolderIndexes(folder.id, folder.name)) }
                    )
                }
            }

            is AddPage.FolderIndexes -> {
                // iOS PlaylistAddIndexesVC.swift:58（无 scopeButtonTitles）
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Search in \"Directories\"",
                    hasScope = false
                )
                val search = ui.search
                val directories by remember(page) { viewModel.folderDirectories(page.id) }
                    .collectAsState(initial = emptyList())
                val visible = remember(directories, search.text) {
                    directories.filterByName(search, { it.name }) { true }
                }
                BrowsePage(
                    entries = visible,
                    key = { it.id },
                    ui = ui,
                    indexLetter = null,
                    bottomPadding = bottomPadding
                ) { dir ->
                    BrowseRow(
                        title = dir.name,
                        onClick = { viewModel.push(AddPage.DirectoryDetail(dir.id, dir.name)) }
                    )
                }
            }

            is AddPage.DirectoryDetail -> {
                // iOS PlaylistAddDirectoriesVC.swift:64-66
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Directories and Songs"
                )
                val search = ui.search
                val directories by remember(page) { viewModel.subDirectories(page.id) }
                    .collectAsState(initial = emptyList())
                val songs by remember(page) { viewModel.directorySongs(page.id) }
                    .collectAsState(initial = emptyList())
                // Cached 作用域只过滤歌曲，子目录照常列出（对齐主 DirectoryDetailScreen 与 iOS DirectoriesVC）
                val visibleDirs = remember(directories, search.text) {
                    directories.filterByName(search, { it.name }) { true }
                }
                val visibleSongs = remember(songs, search.text, search.isCached) {
                    songs.filterSongs(search)
                }
                SectionedSelectPage(
                    songs = visibleSongs,
                    selectedIds = selectedIds,
                    bottomPadding = bottomPadding,
                    onToggle = { viewModel.toggleSong(it) },
                    onToggleAll = { viewModel.toggleAll(visibleSongs) },
                    ui = ui,
                    // 本页两段（子目录 / 歌曲）**都不画段头**：iOS PlaylistAddDirectoriesVC.swift
                    // 只 override 了 numberOfSections/numberOfRowsInSection/cellForRowAt，
                    // 没有 titleForHeaderInSection 与 heightForHeaderInSection，落到基类
                    // BasicTableViewController.swift:240-246 的 `0.0`（与 PlaylistAddAlbumDetailVC /
                    // PlaylistAddPlaylistDetailVC 同）。此前 Android 自创了 "Directories"/"Songs" 两个段头
                    showSongsHeader = false
                ) {
                    if (visibleDirs.isNotEmpty()) {
                        items(visibleDirs, key = { "dir_${it.id}" }) { dir ->
                            BrowseRow(
                                title = dir.name,
                                onClick = {
                                    viewModel.push(AddPage.DirectoryDetail(dir.id, dir.name))
                                }
                            )
                        }
                    }
                }
            }

            is AddPage.GenreDetail -> {
                // iOS PlaylistAddGenreDetailVC.swift:80-82
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Artists, Albums and Songs"
                )
                val search = ui.search
                val artists by remember(page) { viewModel.genreArtists(page.name) }
                    .collectAsState(initial = emptyList())
                val albums by remember(page) { viewModel.genreAlbums(page.name) }
                    .collectAsState(initial = emptyList())
                val songs by remember(page) { viewModel.genreSongs(page.name) }
                    .collectAsState(initial = emptyList())
                val cachedArtistIds by remember(page) { viewModel.cachedArtistIds() }
                    .collectAsState(initial = emptySet())
                val cachedAlbumIds by remember(page) { viewModel.cachedAlbumIds() }
                    .collectAsState(initial = emptySet())
                val visibleArtists =
                    remember(artists, cachedArtistIds, search.text, search.isCached) {
                        artists.filterByName(search, { it.name }) { it.id in cachedArtistIds }
                    }
                val visibleAlbums =
                    remember(albums, cachedAlbumIds, search.text, search.isCached) {
                        albums.filterByName(search, { it.name }) { it.id in cachedAlbumIds }
                    }
                val visibleSongs = remember(songs, search.text, search.isCached) {
                    songs.filterSongs(search)
                }
                SectionedSelectPage(
                    songs = visibleSongs,
                    selectedIds = selectedIds,
                    bottomPadding = bottomPadding,
                    onToggle = { viewModel.toggleSong(it) },
                    onToggleAll = { viewModel.toggleAll(visibleSongs) },
                    ui = ui
                ) {
                    if (visibleArtists.isNotEmpty()) {
                        item(key = "header_artists") { AddSectionHeader("Artists") }
                        items(visibleArtists, key = { "artist_${it.id}" }) { artist ->
                            BrowseRow(
                                title = artist.name,
                                onClick = {
                                    viewModel.push(AddPage.ArtistDetail(artist.id, artist.name))
                                }
                            )
                        }
                    }
                    if (visibleAlbums.isNotEmpty()) {
                        item(key = "header_albums") { AddSectionHeader("Albums") }
                        items(visibleAlbums, key = { "album_${it.id}" }) { album ->
                            BrowseRow(
                                title = album.name,
                                subtitle = album.artist,
                                onClick = {
                                    viewModel.push(AddPage.AlbumDetail(album.id, album.name))
                                }
                            )
                        }
                    }
                }
            }

            is AddPage.ArtistDetail -> {
                // iOS PlaylistAddArtistDetailVC.swift:73
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Albums and Songs"
                )
                val search = ui.search
                val albums by remember(page) { viewModel.artistAlbums(page.id) }
                    .collectAsState(initial = emptyList())
                val songs by remember(page) { viewModel.artistSongs(page.id) }
                    .collectAsState(initial = emptyList())
                val cachedAlbumIds by remember(page) { viewModel.cachedAlbumIds() }
                    .collectAsState(initial = emptySet())
                val visibleAlbums =
                    remember(albums, cachedAlbumIds, search.text, search.isCached) {
                        albums.filterByName(search, { it.name }) { it.id in cachedAlbumIds }
                    }
                val visibleSongs = remember(songs, search.text, search.isCached) {
                    songs.filterSongs(search)
                }
                SectionedSelectPage(
                    songs = visibleSongs,
                    selectedIds = selectedIds,
                    bottomPadding = bottomPadding,
                    onToggle = { viewModel.toggleSong(it) },
                    onToggleAll = { viewModel.toggleAll(visibleSongs) },
                    ui = ui
                ) {
                    if (visibleAlbums.isNotEmpty()) {
                        item(key = "header_albums") { AddSectionHeader("Albums") }
                        items(visibleAlbums, key = { "album_${it.id}" }) { album ->
                            BrowseRow(
                                title = album.name,
                                onClick = {
                                    viewModel.push(AddPage.AlbumDetail(album.id, album.name))
                                }
                            )
                        }
                    }
                }
            }

            is AddPage.AlbumDetail -> {
                // iOS PlaylistAddAlbumDetailVC.swift:74-76
                val ui = rememberAddSearchUi(
                    page, isTop, registerSearchUi,
                    placeholder = "Search in \"Album\""
                )
                val search = ui.search
                val songs by remember(page) { viewModel.albumSongs(page.id) }
                    .collectAsState(initial = emptyList())
                val visible = remember(songs, search.text, search.isCached) {
                    songs.filterSongs(search)
                }
                SongSelectList(
                    songs = visible,
                    selectedIds = selectedIds,
                    bottomPadding = bottomPadding,
                    onToggle = { viewModel.toggleSong(it) },
                    onToggleAll = { viewModel.toggleAll(visible) },
                    ui = ui
                )
            }

            is AddPage.PlaylistDetail -> {
                // iOS PlaylistAddPlaylistDetailVC 无 configureSearchController —— 本页无搜索栏
                SideEffect { if (isTop) registerSearchUi(null) }
                val songs by remember(page) { viewModel.playlistSongs(page.id) }
                    .collectAsState(initial = emptyList())
                SongSelectList(
                    songs = songs,
                    selectedIds = selectedIds,
                    bottomPadding = bottomPadding,
                    onToggle = { viewModel.toggleSong(it) },
                    onToggleAll = { viewModel.toggleAll(songs) }
                )
            }
        }
    }
}

// ============================================================================
// 搜索 / 作用域
// ============================================================================

/**
 * 每级页面的搜索局部态（对应 iOS 各 VC 的 UISearchController）
 *
 * [hasScope] = 该页是否有 All/Cached 作用域段（逐页对齐 iOS configureSearchController
 * 的 scopeButtonTitles 是否传值）。
 */
@Stable
private class AddSearchState(val hasScope: Boolean) {
    var text by mutableStateOf("")
    var isCached by mutableStateOf(false)
}

/** 以 page 为 key —— 切页即重置搜索词与作用域（iOS 每次 push 都是新建 VC） */
@Composable
private fun rememberAddSearchState(page: AddPage, hasScope: Boolean = true) =
    remember(page) { AddSearchState(hasScope) }

/** 名称匹配（+ Cached 作用域）过滤：搜索词为空时不过滤 */
private inline fun <T> List<T>.filterByName(
    search: AddSearchState,
    nameOf: (T) -> String,
    isCached: (T) -> Boolean
): List<T> {
    val text = search.text
    return filter { item ->
        (text.isEmpty() || nameOf(item).contains(text, ignoreCase = true)) &&
            (!search.isCached || isCached(item))
    }
}

/** 歌曲过滤：标题匹配 + Cached 作用域按已下载判定 */
private fun List<Song>.filterSongs(search: AddSearchState): List<Song> {
    val text = search.text
    return filter { song ->
        (text.isEmpty() || song.title.contains(text, ignoreCase = true)) &&
            (!search.isCached || song.isDownloaded)
    }
}

/**
 * 单级页面的搜索接线捆绑：搜索局部态 + 承载搜索栏的列表状态 + 统一搜索栏行为状态 + 占位文案。
 *
 * 搜索栏本身以 LazyColumn 内首个 item 发射（[librarySearchBarItem]），行为全在
 * [LibrarySearchState] 内（进入隐藏/下拉唤出/上滑收起/scope 仅激活时显示），本页只提供数据。
 */
@Stable
private class AddSearchUi(
    val search: AddSearchState,
    val listState: LazyListState,
    val searchState: LibrarySearchState,
    val placeholder: String
) {
    /**
     * All/Cached 作用域段：仅 iOS 对应 VC 传了 scopeButtonTitles 的页面有
     *（Directories 两级列表页无作用域 → null，不发射）。
     * 同一份同时给列表内搜索栏与钉顶头，两处形态一致。
     */
    val scopeContent: (@Composable () -> Unit)? =
        if (search.hasScope) ({ AddScopeRow(search) }) else null
}

/**
 * 建本页搜索接线（以 page 为 key，切页即重置）
 *
 * @param isTop 本帧是否为当前栈顶——转场期间新旧两帧并存，只有栈顶帧把自己注册给顶栏
 * @param register 注册回调（[AddPageContent] 的 registerSearchUi）
 */
@Composable
private fun rememberAddSearchUi(
    page: AddPage,
    isTop: Boolean,
    register: (AddSearchUi?) -> Unit,
    placeholder: String,
    hasScope: Boolean = true
): AddSearchUi {
    val search = rememberAddSearchState(page, hasScope)
    val listState = rememberLazyListState()
    val searchState = rememberLibrarySearchState(listState)
    val ui = remember(search, listState, searchState, placeholder) {
        AddSearchUi(search, listState, searchState, placeholder)
    }
    // 钉顶时钉顶头须顶替 Scaffold 顶栏，而顶栏在 AnimatedContent 之外 → 上提注册
    SideEffect { if (isTop) register(ui) }
    // 退出搜索即复位作用域：iOS searchController 非激活时列表不过滤；
    // 否则钉顶头的 X 关闭搜索后会残留 Cached 过滤，且界面上无任何可见指示
    LaunchedEffect(searchState.isPinned) {
        if (!searchState.isPinned) search.isCached = false
    }
    return ui
}

/**
 * All/Cached 作用域段（iOS searchController.scopeButtonTitles）
 *
 * 由 [LibrarySearchState] 决定何时显示——列表内搜索栏仅在搜索激活时带出，正是 iOS
 * scope buttons 的默认行为。
 */
@Composable
private fun AddScopeRow(search: AddSearchState) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        listOf(false, true).forEachIndexed { index, cached ->
            SegmentedButton(
                selected = search.isCached == cached,
                onClick = { search.isCached = cached },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                label = { Text(if (cached) "Cached" else "All") }
            )
        }
    }
}

/**
 * 列表内搜索栏 item（各页同一形态，故收在此处）
 *
 * [searchText] 由调用方在**组合期**读出后传入（与全仓其它页的接线一致）：在 LazyListScope
 * 内读 state 不会驱动外层重组，输入的字会显示不出来。
 */
private fun LazyListScope.addSearchBarItem(ui: AddSearchUi, searchText: String) {
    librarySearchBarItem(
        state = ui.searchState,
        searchText = searchText,
        onSearchTextChanged = { ui.search.text = it },
        placeholder = ui.placeholder,
        scopeContent = ui.scopeContent
    )
}

/**
 * 右侧字母索引（仅 5 个平铺页使用）
 *
 * [leadingCount] = 列表内容项之前的前置 item 数：搜索栏为 LazyColumn 内首个 item，故
 * 纯浏览页为 1（搜索栏）、歌曲页为 2（搜索栏 + "Songs" 段头）。
 */
@Composable
private fun <T> BoxScope.AddAlphabetIndex(
    items: List<T>,
    listState: LazyListState,
    leadingCount: Int = 0,
    getIndexLetter: (T) -> String
) {
    if (items.isEmpty()) return
    AlphabetIndex(
        items = items,
        listState = listState,
        getIndexLetter = getIndexLetter,
        modifier = Modifier.align(Alignment.CenterEnd),
        leadingItemCount = leadingCount
    )
}

// ============================================================================
// 页面骨架
// ============================================================================

/**
 * 根分类入口（iOS: PlaylistAddLibraryVC，行序 = addToPlaylistSettings.inUse）
 */
@Composable
private fun AddRootPage(
    bottomPadding: Dp,
    onSelect: (LibraryDisplayType) -> Unit
) {
    // iOS LibraryDisplaySettings.addToPlaylistSettings 固定顺序（11 项）
    val categories = listOf(
        LibraryDisplayType.GENRES,
        LibraryDisplayType.ARTISTS,
        LibraryDisplayType.FAVORITE_ARTISTS,
        LibraryDisplayType.ALBUMS,
        LibraryDisplayType.FAVORITE_ALBUMS,
        LibraryDisplayType.NEWEST_ALBUMS,
        LibraryDisplayType.RECENT_ALBUMS,
        LibraryDisplayType.SONGS,
        LibraryDisplayType.FAVORITE_SONGS,
        LibraryDisplayType.DIRECTORIES,
        LibraryDisplayType.PLAYLISTS
    )
    LazyColumn(
        // iOS 式过滚阻尼（弹层内滚动容器统一接入）
        modifier = Modifier.fillMaxSize().iosOverscroll(),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        items(categories, key = { it.rawValue }) { type ->
            BrowseRow(
                title = type.displayName,
                icon = type.icon,
                onClick = { onSelect(type) }
            )
        }
    }
}

/**
 * 纯浏览页（流派/艺术家/专辑/播放列表/文件夹/目录索引）：
 * 列表（首个 item = 搜索栏）+ 可选右侧字母索引（[indexLetter] 为 null 时不显示）
 */
@Composable
private fun <T> BrowsePage(
    entries: List<T>,
    key: (T) -> Any,
    ui: AddSearchUi,
    indexLetter: ((T) -> String)?,
    bottomPadding: Dp,
    row: @Composable (T) -> Unit
) {
    // 组合期读搜索词（LazyListScope 内读 state 不驱动外层重组）
    val searchText = ui.search.text
    // 行内容 end 内边距（索引条可见时「(条宽+最宽字形宽)/2+间隙」替代 16dp；条件与下方 AddAlphabetIndex 的
    // 渲染条件同源：indexLetter 非 null 且列表非空）；索引条为 A-Z + #，故取默认标签集
    // （= iOS UITableView 收窄 cell.contentView，见 LocalListRowTrailingInset）
    val indexBarRowEndPadding = com.amperfy.ui.components.rememberIndexBarRowEndPadding()
    val rowTrailingInset =
        if (indexLetter != null && entries.isNotEmpty()) indexBarRowEndPadding else 0.dp
    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            com.amperfy.ui.components.LocalListRowTrailingInset provides rowTrailingInset
        ) {
        LazyColumn(
            state = ui.listState,
            modifier = Modifier
                .fillMaxSize()
                // iOS 式过滚阻尼（弹层内滚动容器统一接入）
                .iosOverscroll()
                // 只观察不消费，与 sheet 拖拽手势并存（LibrarySearchState 机制）
                .nestedScroll(ui.searchState.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            addSearchBarItem(ui, searchText)
            items(entries, key = key) { entry -> row(entry) }
        }
        }
        if (indexLetter != null) {
            // 搜索栏为列表内首个 item → leadingCount = 1
            AddAlphabetIndex(entries, ui.listState, leadingCount = 1, getIndexLetter = indexLetter)
        }
    }
}

/** 通用浏览行：名称（+副标题/图标）+ chevron，点击下钻 */
@Composable
private fun BrowseRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    // 索引条可见时内容 Row 的 end 内边距总值（**替代** 16dp 而非叠加），行容器与分割线保持全宽
    // （= iOS UITableView 收窄 cell.contentView，见 LocalListRowTrailingInset）
    val trailingInset = com.amperfy.ui.components.LocalListRowTrailingInset.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = maxOf(16.dp, trailingInset), top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = AmperfyIcons.chevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
    // separatorInset = cell layoutMargins 左右值，CommonScreenOperations.swift:41-47）
    HairlineDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.separator  // iOS .separator
    )
}

/**
 * section 头（仅 ArtistDetail / GenreDetail 两页的 Artists/Albums/Songs 分段——
 * 即 iOS 侧真正 override 了 titleForHeaderInSection 的页；DirectoryDetail 与各歌曲
 * 平铺页无段头，见 [SectionedSelectPage] 的 showSongsHeader 说明）
 *
 * 规格 = 标准 grouped 段头（40pt + 13sp 全大写灰字，`CommonScreenOperations
 * .tableSectionHeightLarge = 40`）——对应 iOS PlaylistAddArtistDetailVC.swift:129-139
 * 的 titleForHeaderInSection + :188-197 的 heightForHeaderInSection
 * （PlaylistAddGenreDetailVC.swift:141/:212-224 同构），故直接复用全仓同一组件。
 * 此前为 20sp SemiBold 自创规格。
 *
 * 段无内容时不发射本组件 = iOS `numberOfObjects > 0 ? 40 : 0` 的高 0 分支。
 */
@Composable
private fun AddSectionHeader(title: String) {
    GroupedSectionHeader(title)
}

/**
 * 可勾选歌曲行（iOS PlayableTableCell.swift:346-351，displayMode == .add：
 * `.checkmark` / `.plusCircle` 附件，两态 tint 恒为账户主题色）
 *
 * **注意**：`.checkmark` 是 **UIKit 内建 `UIImage.checkmark`**（全仓无自定义重载），
 * 渲染形态为**填充圆内反挖白钩**的圆徽，不是 SF 字符串 "checkmark" 的裸勾——后者是
 * AmperfyImage.check（UIImageAssetsExtension.swift:158,301），只用于菜单单选打勾。
 * 故已选态取 [AmperfyIcons.isSelected]（checkmark.circle.fill 同形）。
 */
@Composable
private fun SelectableSongRow(
    song: Song,
    selected: Boolean,
    onToggle: () -> Unit
) {
    SongListItem(
        song = song,
        style = SongListItemStyle.ARTWORK,
        trailingContent = {
            Icon(
                imageVector = if (selected) AmperfyIcons.isSelected else AmperfyIcons.plusCircle,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        // 行底与 sheet 同底（iOS cell backgroundColor 恒等于 tableView.backgroundColor）
        backgroundColor = MaterialTheme.colorScheme.sheetBackground,
        callbacks = SongListItemCallbacks(onClick = onToggle)
    )
}

/** 纯歌曲多选页（Songs/Favorite Songs/AlbumDetail/PlaylistDetail）+ 底部 All 工具栏 */
@Composable
private fun SongSelectList(
    songs: List<Song>,
    selectedIds: Set<String>,
    bottomPadding: Dp,
    onToggle: (Song) -> Unit,
    onToggleAll: () -> Unit,
    ui: AddSearchUi? = null
) {
    SectionedSelectPage(
        songs = songs,
        selectedIds = selectedIds,
        bottomPadding = bottomPadding,
        onToggle = onToggle,
        onToggleAll = onToggleAll,
        ui = ui,
        preSongsContent = {},
        // 平铺歌曲页无段头（AlbumDetail / PlaylistDetail：iOS 对应 VC 无 header override）
        showSongsHeader = false
    )
}

/**
 * 带歌曲勾选段的页面骨架：搜索栏 item（[ui] 非 null 时，列表内首位）+ 可选的前置 section
 *（子目录/专辑等）+ Songs 段 + 可选右侧字母索引 [indexContent] +
 * 底部「All」工具栏（iOS: AddToPlaylistManager.configuteToolbar 的 All 按钮）
 *
 * [ui] 为 null = 本页无搜索（PlaylistDetail，iOS PlaylistAddPlaylistDetailVC 无
 * configureSearchController）：不发射搜索 item、不接搜索用的 nestedScroll。
 */
@Composable
private fun SectionedSelectPage(
    songs: List<Song>,
    selectedIds: Set<String>,
    bottomPadding: Dp,
    onToggle: (Song) -> Unit,
    onToggleAll: () -> Unit,
    ui: AddSearchUi? = null,
    indexContent: (@Composable BoxScope.() -> Unit)? = null,
    // 歌曲段是否画段头：只有 iOS 侧真正 override 了 titleForHeaderInSection 的分段页才有
    // （PlaylistAddArtistDetailVC.swift:129-139 / PlaylistAddGenreDetailVC.swift:141-152）；
    // 歌曲平铺页（PlaylistAddSongsVC.swift:182-198，name 排序恒返回 0.0）与
    // PlaylistAddAlbumDetailVC / PlaylistAddPlaylistDetailVC / PlaylistAddDirectoriesVC
    // （均无 override → 基类 BasicTableViewController.swift:240-246 默认高 0）都没有段头。
    // **须排在 [preSongsContent] 之前**：后者是尾随 lambda 形参，必须留在参数表末位
    showSongsHeader: Boolean = true,
    preSongsContent: LazyListScope.() -> Unit = {}
) {
    // 有搜索的页用 ui 的列表状态（搜索栏显隐要观察它），无搜索的页自建
    val standaloneListState = rememberLazyListState()
    val listState = ui?.listState ?: standaloneListState
    // 组合期读搜索词（LazyListScope 内读 state 不驱动外层重组）
    val searchText = ui?.search?.text.orEmpty()
    // 行内容 end 内边距（索引条可见时「(条宽+最宽字形宽)/2+间隙」替代 16dp；条件与 [indexContent] 内
    // AddAlphabetIndex 的渲染条件同源：只有传了索引条的歌曲平铺页、且列表非空时才让位）
    // （= iOS UITableView 收窄 cell.contentView，见 LocalListRowTrailingInset）
    val indexBarRowEndPadding = com.amperfy.ui.components.rememberIndexBarRowEndPadding()
    val rowTrailingInset =
        if (indexContent != null && songs.isNotEmpty()) indexBarRowEndPadding else 0.dp
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(
                com.amperfy.ui.components.LocalListRowTrailingInset provides rowTrailingInset
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // iOS 式过滚阻尼（弹层内滚动容器统一接入）
                    .iosOverscroll()
                    // 只观察不消费，与 sheet 拖拽手势并存（LibrarySearchState 机制）
                    .then(
                        if (ui != null) Modifier.nestedScroll(ui.searchState.nestedScrollConnection)
                        else Modifier
                    ),
                // 无歌曲时没有底部「All」工具栏承担导航栏留白，改由列表尾补上
                contentPadding = PaddingValues(bottom = if (songs.isEmpty()) bottomPadding else 0.dp)
            ) {
                if (ui != null) addSearchBarItem(ui, searchText)
                preSongsContent()
                if (songs.isNotEmpty()) {
                    if (showSongsHeader) {
                        item(key = "header_songs") { AddSectionHeader("Songs") }
                    }
                    // key 带出现序号：播放列表内同一首歌可出现多次（key 撞车会崩溃）
                    itemsIndexed(songs, key = { index, song -> "song_${song.id}#$index" }) { _, song ->
                        SelectableSongRow(
                            song = song,
                            selected = song.id in selectedIds,
                            onToggle = { onToggle(song) }
                        )
                    }
                }
            }
            }
            indexContent?.invoke(this)
        }
        if (songs.isNotEmpty()) {
            // 列表与下方工具栏的交界线：全宽（0..0）
            HairlineDivider(
                color = MaterialTheme.colorScheme.separator  // iOS .separator
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomPadding)
            ) {
                TextButton(
                    onClick = onToggleAll,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("All") }
            }
        }
    }
}
