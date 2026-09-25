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

package com.amperfy.ui.screens.home

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.model.HomeSection
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.EntityPreviewCard
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.IOSLargeTitle
import com.amperfy.ui.components.IOSLongPressPreviewMenu
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.contextmenu.MenuEnv
import com.amperfy.ui.components.contextmenu.albumPreviewInfo
import com.amperfy.ui.components.contextmenu.artistPreviewInfo
import com.amperfy.ui.components.contextmenu.buildAlbumContextMenuItems
import com.amperfy.ui.components.contextmenu.buildArtistContextMenuItems
import com.amperfy.ui.components.contextmenu.buildGenreContextMenuItems
import com.amperfy.ui.components.contextmenu.buildPlaylistContextMenuItems
import com.amperfy.ui.components.contextmenu.buildPodcastContextMenuItems
import com.amperfy.ui.components.contextmenu.buildPodcastEpisodeContextMenuItems
import com.amperfy.ui.components.contextmenu.buildRadioContextMenuItems
import com.amperfy.ui.components.contextmenu.buildSongContextMenuItems
import com.amperfy.ui.components.contextmenu.episodePreviewInfo
import com.amperfy.ui.components.contextmenu.genrePreviewInfo
import com.amperfy.ui.components.contextmenu.playlistPreviewInfo
import com.amperfy.ui.components.contextmenu.podcastPreviewInfo
import com.amperfy.ui.components.contextmenu.radioPreviewInfo
import com.amperfy.ui.components.contextmenu.songPreviewInfo
import com.amperfy.ui.components.swipe.DeleteCacheConfirmDialog
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.screens.PodcastDescriptionSheet
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter

private val CARD_WIDTH = 160.dp

/**
 * 各实体类型的默认艺术图类型——**逐类**对应 iOS 各实体的 `getDefaultArtworkType()`
 * （Album.swift:238 / Artist.swift:197 / Playlist.swift:482-484 /
 * AbstractPlayable.swift:391-400[song·podcastEpisode·radio] / Podcast.swift:131 /
 * Genre.swift:137），与对应列表行完全同源。图按主题色现画，见 ui/util/DefaultArtwork.kt。
 *
 * `when` 对 sealed class 八个子类穷举，**不留 else 合并分支**——曾把播放列表/播客/
 * 单集/流派一并近似成专辑图，2026-08-09 起接回各自真实类型。
 */
private fun HomeItem.defaultArtworkType(): DefaultArtworkType = when (this) {
    is HomeItem.AlbumItem -> DefaultArtworkType.ALBUM
    is HomeItem.ArtistItem -> DefaultArtworkType.ARTIST
    is HomeItem.PlaylistItem -> DefaultArtworkType.PLAYLIST
    is HomeItem.SongItem -> DefaultArtworkType.SONG
    is HomeItem.PodcastItem -> DefaultArtworkType.PODCAST
    is HomeItem.EpisodeItem -> DefaultArtworkType.PODCAST_EPISODE
    is HomeItem.RadioItem -> DefaultArtworkType.RADIO
    is HomeItem.GenreItem -> DefaultArtworkType.GENRE
}

/**
 * 长按预览卡的副标题（artistLabel）——**逐类**对应 iOS 各实体的 `subtitle`：
 * EntityPreviewVC.swift:895-897 `artistLabel.text = entityContainer.subtitle`
 * （`isHidden = subtitle == nil`），各实体真值为
 * AbstractPlayable.swift:416 = creatorName（歌曲=艺术家、单集=播客名、电台=空串）、
 * Album.swift:182 = artist?.name，而
 * Artist.swift:122 / Playlist.swift:490 / Genre.swift:88 / Podcast.swift:88 = **nil**。
 *
 * 与卡片本体副标题（[CardDisplay.subtitle]）刻意不同源：后者是 Home 卡自身的展示信息
 * （艺术家「X Albums」、流派「X Albums · Y Songs」等），若照搬进预览卡会与 info 行重复。
 *
 * `when` 对 sealed class 八个子类穷举，**不留 else 合并分支**。
 */
private fun HomeItem.previewSubtitle(): String? = when (this) {
    is HomeItem.SongItem -> song.artist.ifBlank { null }
    is HomeItem.AlbumItem -> album.artist.ifBlank { null }
    is HomeItem.EpisodeItem -> episode.podcastTitle.ifBlank { null }
    // iOS Radio.creatorName 恒为空串（非 nil），artistLabel 可见但为空 → 保留空行，
    // 与 RadiosScreen 行预览卡同源
    is HomeItem.RadioItem -> ""
    is HomeItem.ArtistItem -> null
    is HomeItem.PlaylistItem -> null
    is HomeItem.GenreItem -> null
    is HomeItem.PodcastItem -> null
}

/**
 * Home 首页（W3，iOS: HomeVC）
 *
 * - 顶栏对齐 iOS large-title 导航栏折叠行为（HomeVC.navigationBar.prefersLargeTitles = true）：
 *   按钮行固定（左上用户按钮 + 右上 Edit），大标题 IOSLargeTitle("Home") 作为 LazyColumn 第一项随内容滚动；
 *   大标题滚出后（firstVisibleItemIndex > 0），按钮行中间淡入 17sp 小号居中标题（AnimatedVisibility）
 * - Edit 弹自下而上模态 HomePreferencesSheet（对齐 iOS HomeEditorVC formSheet），本地状态控制显隐
 * - LazyColumn 逐 section 渲染：SectionHeader（标题 title3 semibold + 随机类右侧刷新图标）+ LazyRow 卡片
 * - 卡片 160dp 宽、方形封面 + 两行文字；点击走详情回调（album/artist/playlist/podcast/genre），
 *   Song/Radio/Episode 单击直接播放；长按 IOSLongPressPreviewMenu
 * - 底部 LocalMiniPlayerHeight 留白
 */
@Composable
fun HomeScreen(
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {},
    onNavigateToPodcast: (String) -> Unit = {},
    onNavigateToGenre: (String) -> Unit = {},
    // 顶栏左上用户按钮插槽（W6 挂载 AccountMenuButton；由 MainScreen 下传，未挂时为空）
    accountMenu: @Composable () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val visibleSections by viewModel.visibleSections.collectAsState()
    val sectionData by viewModel.sectionData.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()

    // Edit 弹出的 Home Preferences 模态显隐（本地态；sheet 复用本 Screen 的 viewModel 实例）
    var showEditor by remember { mutableStateOf(false) }

    // 删除缓存确认对话框（长按菜单 Delete Cache 走滑动执行路径，结果在此承接）
    val pendingDeleteCacheSongs by viewModel.swipeCoordinator.pendingDeleteCacheSongs.collectAsState()
    pendingDeleteCacheSongs?.let { songs ->
        DeleteCacheConfirmDialog(
            songCount = songs.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteCache() }
        )
    }

    // 删除单集缓存确认（Batch 4：单集卡片长按菜单 Delete Cache 的承接点）
    val pendingDeleteCacheEpisodes by viewModel.swipeCoordinator.pendingDeleteCacheEpisodes.collectAsState()
    pendingDeleteCacheEpisodes?.let { episodes ->
        DeleteCacheConfirmDialog(
            songCount = episodes.size,
            onConfirm = { viewModel.swipeCoordinator.confirmDeleteEpisodeCache() },
            onDismiss = { viewModel.swipeCoordinator.dismissDeleteEpisodeCache() }
        )
    }

    // 添加到播放列表选择器（长按菜单 Add to Playlist）
    val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
    pendingPlaylistSongIds?.let { ids ->
        PlaylistSelectorDialog(
            songIds = ids,
            onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
        )
    }

    // 进入页面：在线远端同步（30s 防抖，VM 内部处理；不重抽随机 section）
    LaunchedEffect(Unit) { viewModel.onEnterScreen() }

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
        // 顶栏按钮行（固定）：用户按钮靠左、Edit 靠右、折叠后中间淡入小标题
        // （对齐 iOS large-title 导航栏 barButtonItems + 行内标题）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // 中间小标题：大标题滚出后淡入（17sp SemiBold，对齐 iOS 导航栏行内标题）
            androidx.compose.animation.AnimatedVisibility(
                visible = collapsed,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                Text(
                    text = "Home",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            // 用户按钮（左上，冻结位置，W6）
            Box(modifier = Modifier.align(Alignment.CenterStart)) {
                accountMenu()
            }
            // Edit（右上）
            TextButton(
                onClick = { showEditor = true },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Text("Edit")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = miniPlayerHeight)
        ) {
            // 大标题作为第一项随内容滚动（iOS large title，start padding 16dp 与 section 头对齐；字号沿用默认 28sp）
            item(key = "home_large_title") {
                IOSLargeTitle(
                    text = "Home",
                    lineHeight = 34.sp,   // 收紧 displaySmall 默认 44sp 行高的下方富余（对齐 iOS 大标题紧凑间距）
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 0.dp)
                )
            }
            // 本地首读完成前不渲染 section（sectionData 为 null）；对齐 iOS 同步 fetch 首帧即有内容，避免标题堆叠
            val data = sectionData
            if (data != null) {
                items(visibleSections, key = { it.rawValue }) { section ->
                    val items = data[section].orEmpty()
                    HomeSectionView(
                        section = section,
                        items = items,
                        currentSongId = currentSong?.id,
                        onRefreshRandom = { viewModel.refreshRandomSection(section) },
                        onNavigateToArtist = onNavigateToArtist,
                        onNavigateToAlbum = onNavigateToAlbum,
                        onNavigateToPlaylist = onNavigateToPlaylist,
                        onNavigateToPodcast = onNavigateToPodcast,
                        onNavigateToGenre = onNavigateToGenre,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    // Edit 弹出的自下而上模态（对齐 iOS HomeEditorVC formSheet）；复用本 Screen 的 viewModel
    if (showEditor) {
        HomePreferencesSheet(
            onDismiss = { showEditor = false },
            viewModel = viewModel
        )
    }
}

/**
 * 单个 section：标题行（随机类附刷新图标）+ 横向卡片列表。
 * 空数据时仍渲染标题（对齐 iOS——section 存在即显示，内容随同步补齐）。
 */
@Composable
private fun HomeSectionView(
    section: HomeSection,
    items: List<HomeItem>,
    currentSongId: String?,
    onRefreshRandom: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToPodcast: (String) -> Unit,
    onNavigateToGenre: (String) -> Unit,
    viewModel: HomeViewModel
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        // SectionHeader（对齐 iOS SectionHeaderView：title3 semibold + leading 16pt）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = section.displayName,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (section.isRandom) {
                IconButton(modifier =  Modifier.size(28.dp), onClick = onRefreshRandom) {
                    Icon(
                        AmperfyIcons.refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 卡片横向列表；section 内歌曲/电台用于点击播放上下文
        val sectionSongs = remember(items) {
            items.mapNotNull { (it as? HomeItem.SongItem)?.song }
        }
        val sectionRadios = remember(items) {
            items.mapNotNull { (it as? HomeItem.RadioItem)?.radio }
        }

        // 菜单环境三开关：在 section 层 collect 一次下传（勿在 LazyRow item 内重复 collectAsState）
        val settingsManager = LocalSettingsManager.current
        val isOfflineMode by settingsManager.isOfflineMode.collectAsState()
        val isShowDetailedInfo by settingsManager.isShowDetailedInfo.collectAsState()
        val isShuffleActionEnabled by settingsManager.isPlayerShuffleButtonEnabled.collectAsState()
        val env = MenuEnv(
            isOfflineMode = isOfflineMode,
            isShuffleActionEnabled = isShuffleActionEnabled,
            isShowDetailedInfo = isShowDetailedInfo
        )
        val cachedAlbumIds by viewModel.cachedAlbumIds.collectAsState()
        val cachedArtistIds by viewModel.cachedArtistIds.collectAsState()
        val cachedPlaylistIds by viewModel.cachedPlaylistIds.collectAsState()
        val cachedGenreNames by viewModel.cachedGenreNames.collectAsState()
        // 全缓存容器集合（对应 iOS isCachedCompletely）：菜单据此隐藏 Download
        val fullyCachedAlbumIds by viewModel.fullyCachedAlbumIds.collectAsState()
        val fullyCachedArtistIds by viewModel.fullyCachedArtistIds.collectAsState()
        val fullyCachedPlaylistIds by viewModel.fullyCachedPlaylistIds.collectAsState()
        val fullyCachedGenreNames by viewModel.fullyCachedGenreNames.collectAsState()
        val clipboardManager = LocalClipboardManager.current
        val uriHandler = LocalUriHandler.current
        // Show Podcast/Episode Description 弹层（对应 iOS PlainDetailsVC）；section 内共用一份状态
        var descriptionSheet by remember { mutableStateOf<Pair<String, String?>?>(null) }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.key }) { item ->
                HomeCard(
                    item = item,
                    currentSongId = currentSongId,
                    onTap = {
                        when (item) {
                            is HomeItem.AlbumItem -> onNavigateToAlbum(item.album.id)
                            is HomeItem.ArtistItem -> onNavigateToArtist(item.artist.id)
                            is HomeItem.PlaylistItem -> onNavigateToPlaylist(item.playlist.id)
                            is HomeItem.PodcastItem -> onNavigateToPodcast(item.podcast.id)
                            is HomeItem.EpisodeItem -> viewModel.playEpisode(item.episode)
                            is HomeItem.GenreItem -> onNavigateToGenre(item.genre.name)
                            is HomeItem.SongItem -> viewModel.playSong(item.song, sectionSongs)
                            is HomeItem.RadioItem -> viewModel.playRadio(item.radio, sectionRadios)
                        }
                    },
                    previewInfo = previewInfoFor(item, env, cachedAlbumIds),
                    // 对应 iOS performPreviewTransition：点预览卡进详情
                    // （电台无 radio 分支，故箭头显示但不可点）
                    showChevron = item !is HomeItem.SongItem || item.song.albumId != null,
                    onPreviewNavigate = when (item) {
                        is HomeItem.AlbumItem -> ({ onNavigateToAlbum(item.album.id) })
                        is HomeItem.ArtistItem -> ({ onNavigateToArtist(item.artist.id) })
                        is HomeItem.PlaylistItem -> ({ onNavigateToPlaylist(item.playlist.id) })
                        is HomeItem.PodcastItem -> ({ onNavigateToPodcast(item.podcast.id) })
                        is HomeItem.GenreItem -> ({ onNavigateToGenre(item.genre.name) })
                        is HomeItem.SongItem -> item.song.albumId?.let { id -> { onNavigateToAlbum(id) } }
                        is HomeItem.EpisodeItem -> ({ onNavigateToPodcast(item.episode.podcastId) })
                        is HomeItem.RadioItem -> null
                    },
                    menuItems = buildMenuItems(
                        item = item,
                        env = env,
                        sectionSongs = sectionSongs,
                        sectionRadios = sectionRadios,
                        cachedAlbumIds = cachedAlbumIds,
                        cachedArtistIds = cachedArtistIds,
                        cachedPlaylistIds = cachedPlaylistIds,
                        cachedGenreNames = cachedGenreNames,
                        fullyCachedAlbumIds = fullyCachedAlbumIds,
                        fullyCachedArtistIds = fullyCachedArtistIds,
                        fullyCachedPlaylistIds = fullyCachedPlaylistIds,
                        fullyCachedGenreNames = fullyCachedGenreNames,
                        viewModel = viewModel,
                        onCopyId = { id ->
                            if (id.isNotEmpty()) clipboardManager.setText(AnnotatedString(id))
                        },
                        onOpenUrl = { url -> uriHandler.openUri(url) },
                        onShowDescription = { title, text -> descriptionSheet = title to text },
                        onNavigateToArtist = onNavigateToArtist,
                        onNavigateToAlbum = onNavigateToAlbum,
                        onNavigateToPlaylist = onNavigateToPlaylist,
                        onNavigateToPodcast = onNavigateToPodcast,
                        onNavigateToGenre = onNavigateToGenre
                    )
                )
            }
        }

        // 描述弹层 - 对应 iOS PlainDetailsVC.display(podcast:/podcastEpisode:)
        descriptionSheet?.let { (title, text) ->
            PodcastDescriptionSheet(
                title = title,
                description = text,
                onDismiss = { descriptionSheet = null }
            )
        }
    }
}

/**
 * 预览卡信息行：一律取集中 xxxPreviewInfo，与对应列表行的预览卡逐字一致
 * （卡片本体下方的小字仍用 toCardDisplay().info，那是 Home 卡片自身的展示信息）
 *
 * 预览卡副标题同理走集中语义（[previewSubtitle] = iOS entityContainer.subtitle），
 * 与卡片本体的 display.subtitle 分离。
 */
private fun previewInfoFor(
    item: HomeItem,
    env: MenuEnv,
    cachedAlbumIds: Set<String>
): String = when (item) {
    is HomeItem.SongItem -> songPreviewInfo(item.song, env.isShowDetailedInfo)
    is HomeItem.AlbumItem -> albumPreviewInfo(
        item.album, item.album.id in cachedAlbumIds, env.isShowDetailedInfo
    )
    is HomeItem.ArtistItem -> artistPreviewInfo(item.artist, env.isShowDetailedInfo)
    is HomeItem.PlaylistItem -> playlistPreviewInfo(item.playlist, env.isShowDetailedInfo)
    is HomeItem.GenreItem -> genrePreviewInfo(item.genre, env.isShowDetailedInfo)
    is HomeItem.PodcastItem -> podcastPreviewInfo(item.podcast, env.isShowDetailedInfo)
    is HomeItem.EpisodeItem -> episodePreviewInfo(item.episode, env.isShowDetailedInfo)
    is HomeItem.RadioItem -> radioPreviewInfo(item.radio)
}

/**
 * Home 卡片：160dp 宽、方形封面 + 两行文字。长按弹 IOSLongPressPreviewMenu（复用现有语义）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCard(
    item: HomeItem,
    currentSongId: String?,
    onTap: () -> Unit,
    previewInfo: String,
    showChevron: Boolean,
    onPreviewNavigate: (() -> Unit)?,
    menuItems: List<IOSContextMenuItem>
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val haptic = LocalHapticFeedback.current

    val display = item.toCardDisplay()
    // 卡片封面的默认艺术图按实体类型取（对应 iOS defaultArtworkType），与对应列表行同源。
    // **本实例只给卡片这一个绘制目标用，禁止与预览卡共享**：Painter 内包的 VectorPainter
    // 有按尺寸缓存的绘制状态，同一实例被 160dp 卡片与 64dp 预览卡同帧交替驱动会串扰
    //（2026-08-09 真机报障：Radio 卡长按时卡面多出一个小号电台图标，重组后才消失）。
    // 预览卡改为只收类型、自持实例，见 EntityPreviewCard 的 defaultArtworkType 参数注释
    val defaultArtwork = rememberDefaultArtworkPainter(item.defaultArtworkType())
    val coverModel = buildCoverArtUrl(display.coverArt, credentialsManager, musicRepository)
    val isPlaying = display.playableId != null && display.playableId == currentSongId

    var showPreviewMenu by remember { mutableStateOf(false) }
    var cardBoundsOnScreen by remember { mutableStateOf<Rect?>(null) }

    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .onGloballyPositioned { coords ->
                val pos = coords.positionOnScreen()
                cardBoundsOnScreen = Rect(
                    left = pos.x,
                    top = pos.y,
                    right = pos.x + coords.size.width,
                    bottom = pos.y + coords.size.height
                )
            }
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    if (menuItems.isNotEmpty() || display.hasPreview) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showPreviewMenu = true
                    }
                }
            )
    ) {
        AsyncImage(
            model = coverModel,
            contentDescription = display.title,
            modifier = Modifier
                .width(CARD_WIDTH)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
            // 对齐 iOS LibraryEntityImage.refresh()：placeholder 覆盖加载中，
            // error/fallback 兜底加载失败与空模型（艺术家常见「coverArt id 有但服务器无图」），
            // 均避免露出底色成空方块
            placeholder = defaultArtwork,
            error = defaultArtwork,
            fallback = defaultArtwork
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = display.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!display.subtitle.isNullOrBlank()) {
            Text(
                text = display.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    // 长按预览 + 上下文菜单（复用 SongListItem 同款组件）
    IOSLongPressPreviewMenu(
        expanded = showPreviewMenu,
        onDismissRequest = { showPreviewMenu = false },
        anchorBoundsOnScreen = cardBoundsOnScreen,
        items = menuItems
    ) {
        EntityPreviewCard(
            coverArtModel = coverModel,
            defaultArtworkType = item.defaultArtworkType(),
            title = display.title,
            // 副标题按 iOS entityContainer.subtitle 语义取（见 previewSubtitle），
            // 非卡片本体的 display.subtitle——后者会与 info 行重复
            subtitle = item.previewSubtitle(),
            reserveSubtitleLine = item is HomeItem.RadioItem,
            // info 行与对应列表行的预览卡同源（集中 xxxPreviewInfo）
            info = previewInfo,
            showChevron = showChevron,
            onClick = onPreviewNavigate?.let { navigate ->
                {
                    showPreviewMenu = false
                    navigate()
                }
            }
        )
    }
}

/** 卡片展示信息（封面/标题/副标题/预览信息 + 播放态判定 id） */
private data class CardDisplay(
    val coverArt: String?,
    val title: String,
    val subtitle: String?,
    val info: String?,
    /** 可播放项（Song/Radio/Episode）的 id，用于当前播放高亮；容器项为 null */
    val playableId: String?,
    val hasPreview: Boolean = true
)

private fun HomeItem.toCardDisplay(): CardDisplay = when (this) {
    is HomeItem.AlbumItem -> CardDisplay(
        coverArt = album.coverArt,
        title = album.name,
        subtitle = album.artist.ifBlank { null },
        info = if (album.songCount > 0) "${album.songCount} Songs" else null,
        playableId = null
    )
    is HomeItem.ArtistItem -> CardDisplay(
        coverArt = artist.coverArt,
        title = artist.name,
        subtitle = if (artist.albumCount > 0) "${artist.albumCount} Albums" else null,
        info = null,
        playableId = null
    )
    is HomeItem.PlaylistItem -> CardDisplay(
        coverArt = playlist.coverArt,
        title = playlist.name,
        subtitle = if (playlist.songCount > 0) "${playlist.songCount} Songs" else null,
        info = null,
        playableId = null
    )
    is HomeItem.SongItem -> CardDisplay(
        coverArt = song.coverArt,
        title = song.title,
        subtitle = song.artist.ifBlank { null },
        info = song.album.ifBlank { null },
        playableId = song.id
    )
    is HomeItem.PodcastItem -> CardDisplay(
        coverArt = podcast.coverArt,
        title = podcast.title,
        subtitle = if (podcast.episodeCount > 0) "${podcast.episodeCount} Episodes" else null,
        info = null,
        playableId = null
    )
    is HomeItem.EpisodeItem -> CardDisplay(
        coverArt = episode.coverArt,
        title = episode.title,
        subtitle = episode.podcastTitle.ifBlank { null },
        info = null,
        playableId = episode.id
    )
    is HomeItem.RadioItem -> CardDisplay(
        coverArt = null,
        title = radio.title,
        subtitle = null,
        info = null,
        playableId = radio.id
    )
    is HomeItem.GenreItem -> CardDisplay(
        coverArt = null,
        title = genre.name,
        subtitle = genre.info,
        info = genre.info,
        playableId = null
    )
}

/**
 * Home 卡片长按上下文菜单 —— 按 HomeItem 类型分派到集中构建器
 * （com.amperfy.ui.components.contextmenu.EntityPreviewActionBuilder）
 *
 * 与对应列表行「同项同序同条件」：iOS 两处同为 EntityPreviewActionBuilder 产出，
 * Android 亦一律走同一份构建器 + 同一份 xxx.handleSwipeAction 执行路径。
 *
 * 唯一刻意差异：Song 卡的 Play 保留 `viewModel.playSong(song, sectionSongs)`
 * ——Home 卡片的播放上下文是所在 section（对应 iOS playContextCb），
 * 而非列表行的「整页列表」。
 */
private fun buildMenuItems(
    item: HomeItem,
    env: MenuEnv,
    sectionSongs: List<Song>,
    sectionRadios: List<com.amperfy.data.model.Radio>,
    cachedAlbumIds: Set<String>,
    cachedArtistIds: Set<String>,
    cachedPlaylistIds: Set<String>,
    cachedGenreNames: Set<String>,
    fullyCachedAlbumIds: Set<String>,
    fullyCachedArtistIds: Set<String>,
    fullyCachedPlaylistIds: Set<String>,
    fullyCachedGenreNames: Set<String>,
    viewModel: HomeViewModel,
    onCopyId: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onShowDescription: (String, String?) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    onNavigateToPodcast: (String) -> Unit,
    onNavigateToGenre: (String) -> Unit
): List<IOSContextMenuItem> = when (item) {
    is HomeItem.SongItem -> buildSongContextMenuItems(
        song = item.song,
        env = env,
        isCached = item.song.isCached,
        // Home 无下载进度映射，下载中态不在此展示（Delete Cache 不置灰）
        isDownloading = false,
        showShuffle = true,
        showAlbum = true,
        showArtist = true,
        onCopyId = { onCopyId(item.song.id) },
        callbacks = SongListItemCallbacks(
            // Play：保留 section 上下文（见函数注释）
            onClick = { viewModel.playSong(item.song, sectionSongs) },
            onShuffle = { viewModel.handleSongSwipeAction(item.song, SwipeActionType.PLAY_SHUFFLED) },
            onToggleFavorite = { viewModel.handleSongSwipeAction(item.song, SwipeActionType.FAVORITE) },
            onSetRating = { rating -> viewModel.setSongRating(item.song, rating) },
            onInsertContextQueue = { viewModel.songInsertContextQueue(item.song) },
            onAppendContextQueue = { viewModel.songAppendContextQueue(item.song) },
            onAddToQueueNext = { viewModel.songAddToQueueNext(item.song) },
            onAddToQueueLater = { viewModel.songAddToQueueLater(item.song) },
            onShowAlbum = { item.song.albumId?.let(onNavigateToAlbum) },
            onShowArtist = { item.song.artistId?.let(onNavigateToArtist) },
            onAddToPlaylist = { viewModel.handleSongSwipeAction(item.song, SwipeActionType.ADD_TO_PLAYLIST) },
            onDownload = { viewModel.handleSongSwipeAction(item.song, SwipeActionType.DOWNLOAD) },
            onDeleteCache = { viewModel.handleSongSwipeAction(item.song, SwipeActionType.REMOVE_FROM_CACHE) }
        )
    )

    is HomeItem.AlbumItem -> buildAlbumContextMenuItems(
        album = item.album,
        env = env,
        hasCachedSongs = item.album.id in cachedAlbumIds,
        onAction = { action -> viewModel.handleAlbumSwipeAction(item.album, action) },
        onSetRating = { rating -> viewModel.setAlbumRating(item.album, rating) },
        // 对应 iOS isShowArtist = !(rootView is ArtistDetailVC)：Home 非艺术家详情页，显示
        onShowArtist = item.album.artistId?.let { artistId -> { onNavigateToArtist(artistId) } },
        onCopyId = { onCopyId(item.album.id) },
        isFullyCached = item.album.id in fullyCachedAlbumIds
    )

    is HomeItem.ArtistItem -> buildArtistContextMenuItems(
        artist = item.artist,
        env = env,
        hasCachedSongs = item.artist.id in cachedArtistIds,
        onAction = { action -> viewModel.handleArtistSwipeAction(item.artist, action) },
        onSetRating = { rating -> viewModel.setArtistRating(item.artist, rating) },
        onCopyId = { onCopyId(item.artist.id) },
        isFullyCached = item.artist.id in fullyCachedArtistIds
    )

    is HomeItem.PlaylistItem -> buildPlaylistContextMenuItems(
        env = env,
        hasCachedSongs = item.playlist.id in cachedPlaylistIds,
        onAction = { action -> viewModel.handlePlaylistSwipeAction(item.playlist, action) },
        onCopyId = { onCopyId(item.playlist.id) },
        isFullyCached = item.playlist.id in fullyCachedPlaylistIds
    )

    is HomeItem.GenreItem -> buildGenreContextMenuItems(
        env = env,
        hasCachedSongs = item.genre.name in cachedGenreNames,
        onAction = { action -> viewModel.handleGenreSwipeAction(item.genre, action) },
        // Subsonic 流派无 id，Copy ID 复制 name（唯一标识）
        onCopyId = { onCopyId(item.genre.name) },
        isFullyCached = item.genre.name in fullyCachedGenreNames
    )

    is HomeItem.PodcastItem -> buildPodcastContextMenuItems(
        env = env,
        // 容器（播客）级聚合缓存态未接线（Batch 4 只落地单集级）
        hasCachedEpisodes = false,
        onAction = { action -> viewModel.handlePodcastSwipeAction(item.podcast, action) },
        onShowDescription = { onShowDescription(item.podcast.title, item.podcast.depiction) },
        onCopyId = { onCopyId(item.podcast.id) }
    )

    is HomeItem.EpisodeItem -> buildPodcastEpisodeContextMenuItems(
        episode = item.episode,
        env = env,
        // 对应 iOS isShowArtist = !(rootView is PodcastDetailVC)：Home 非播客详情页，显示
        showPodcast = true,
        onAction = { action -> viewModel.handleEpisodeSwipeAction(item.episode, action) },
        onShowPodcast = { onNavigateToPodcast(item.episode.podcastId) },
        onShowDescription = { onShowDescription(item.episode.title, item.episode.depiction) },
        onDeleteOnServer = { viewModel.deleteEpisodeOnServer(item.episode) },
        onCopyId = { onCopyId(item.episode.id) }
    )

    is HomeItem.RadioItem -> buildRadioContextMenuItems(
        isOfflineMode = env.isOfflineMode,
        siteUrl = item.radio.siteUrl?.takeIf { it.isNotBlank() },
        isShowDetailedInfo = env.isShowDetailedInfo,
        onPlay = { viewModel.playRadio(item.radio, sectionRadios) },
        onInsertContextQueue = { viewModel.radioInsertContextQueue(item.radio) },
        onAppendContextQueue = { viewModel.radioAppendContextQueue(item.radio) },
        onAddToQueueNext = { viewModel.radioAddToQueueNext(item.radio) },
        onAddToQueueLater = { viewModel.radioAddToQueueLater(item.radio) },
        onGoToSite = { item.radio.siteUrl?.takeIf { it.isNotBlank() }?.let(onOpenUrl) },
        onCopyId = { onCopyId(item.radio.id) }
    )
}
