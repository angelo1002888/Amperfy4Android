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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.amperfy.data.model.SearchEntityType
import com.amperfy.data.model.SearchHistoryEntry
import com.amperfy.data.model.Song
import com.amperfy.ui.components.GroupedSectionHeader
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.PlaylistSelectorDialog
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.ui.navigation.LocalCredentialsManager
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.navigation.LocalMediaUrlRepository
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.systemRed
import com.amperfy.ui.theme.tertiaryLabel
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.buildCoverArtUrl
import com.amperfy.ui.util.rememberDefaultArtworkPainter
import java.util.Locale

/**
 * SearchScreen - 对应 iOS: SearchVC
 *
 * - 对齐 iOS：搜索激活时导航栏隐藏——页面顶端直接是结果/历史列表，无大标题与顶栏。
 * - 查询为空时首段即「Recently Searched」历史（段头右侧 Clear 迁自 iOS 导航栏 options 菜单）；
 *   否则展示 Artists / Albums / Playlists / Songs 四分类（每类最多 10）。
 * - 搜索输入框已移至 MainScreen 底部搜索条浮层（对齐 iOS UISearchTab 激活态：searchBar 承载输入框）；
 *   作用域切换（All / Cached）栏则位于本页顶部（对齐 iOS searchBar.scopeButtonTitles 在页面顶部渲染）；
 *   本页只消费共享 SearchViewModel 的 query 驱动结果，[viewModel] 由调用方显式传入该共享实例。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val scope by viewModel.scope.collectAsState()
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val history by viewModel.searchHistory.collectAsState()
    val currentPlayingSong by viewModel.currentPlayingSong.collectAsState()
    val downloadProgressMap by viewModel.downloadProgressMap.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    val listState = rememberLazyListState()

    // 加入播放列表选择器（歌曲 More 菜单触发）
    val pendingPlaylistSongIds by viewModel.swipeCoordinator.pendingPlaylistSongIds.collectAsState()
    pendingPlaylistSongIds?.let { ids ->
        PlaylistSelectorDialog(
            songIds = ids,
            onDismiss = { viewModel.swipeCoordinator.dismissPlaylistSelector() }
        )
    }

    fun songCallbacks(song: Song) = SongListItemCallbacks(
        onClick = { viewModel.playSong(song) },
        onShuffle = { viewModel.shuffleSong(song) },
        onToggleFavorite = { viewModel.toggleFavorite(song) },
        onSetRating = { rating -> viewModel.setSongRating(song, rating) },
        onInsertContextQueue = { viewModel.insertContextQueue(song) },
        onAppendContextQueue = { viewModel.appendContextQueue(song) },
        onAddToQueueNext = { viewModel.addToQueueNext(song) },
        onAddToQueueLater = { viewModel.addToQueueLater(song) },
        onShowAlbum = { song.albumId?.let(onNavigateToAlbum) },
        onShowArtist = { song.artistId?.let(onNavigateToArtist) },
        onAddToPlaylist = { viewModel.addToPlaylist(song) },
        onDownload = { viewModel.downloadSong(song) },
        onDeleteCache = { viewModel.deleteCache(song) }
    )

    // 对齐 iOS：搜索激活时导航栏隐藏，页面顶端直接是 scope 栏 + 列表；
    // 输入框由 MainScreen 底部搜索条承载（见 SearchBottomBar），而 All/Cached 作用域栏
    // 位于本页顶部（对齐 iOS searchBar.scopeButtonTitles 在页面顶部渲染）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // scope 栏（All / Cached）——对齐 iOS：位于页面顶部（searchBar.scopeButtonTitles），
        // scopeBarActivation = .automatic（iOS 16+ 默认）：有输入文字才显示，清空即隐藏。
        // 顶部 inset 已由 MainScreen 内容区 windowInsetsPadding(systemBars) 统一处理，此处勿加 statusBarsPadding。
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            SearchScopeSelector(
                scope = scope,
                onScopeChange = { viewModel.setScope(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = miniPlayerHeight + 16.dp)
        ) {
            if (query.isBlank()) {
                // ===== 搜索历史 =====
                item(key = "history_header") {
                    // 段头行：左标题 + 右侧 Clear（仅历史非空时显示）。
                    // 说明：iOS 的 Clear Search History 挂在导航栏 options 菜单里；本页导航栏已随
                    // 「搜索激活隐藏导航栏」移除，故务实迁移到段头行右侧小号 TextButton
                    // （功能等价 iOS SearchVC 的 clearSearchHistory 动作）。
                    //
                    // 规格与 [GroupedSectionHeader] 完全一致（40dp 高、13sp 全大写灰字、
                    // 左缘 16dp、文字靠底）——因为要在右侧嵌按钮，这里内联排布而非直接调用组件。
                    // 标题：历史为空时为**空串**（段头仍占位），对齐 iOS
                    // SearchVC.swift:47-53 的 `if searchHistory.isEmpty { return "" }`；
                    // 段头高度恒 40（SearchVC.swift:409-411 History 段 !isSearchActive 时恒为
                    // tableSectionHeightLarge）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            // 文案为 iOS 原文，大写由 UIKit grouped 段头自动施加
                            // （与 GroupedSectionHeader 同一处理，Locale.ROOT 避免 i/İ 变换）
                            text = if (history.isEmpty()) "" else "Recently Searched".uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.08).sp
                            ),
                            color = MaterialTheme.colorScheme.secondaryLabel, // iOS .secondaryLabel
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp, bottom = 7.dp)
                        )
                        if (history.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearHistory() },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("Clear", fontSize = 13.sp)
                            }
                        }
                    }
                }
                items(history, key = { "history_${it.type}_${it.entityId}" }) { entry ->
                    SearchHistoryRow(
                        entry = entry,
                        onClick = {
                            when (entry.type) {
                                // 点击历史刷新时间戳置顶（对应 iOS createOrUpdateSearchHistory）
                                SearchEntityType.ARTIST -> {
                                    viewModel.onHistoryTapped(entry); onNavigateToArtist(entry.entityId)
                                }
                                SearchEntityType.ALBUM -> {
                                    viewModel.onHistoryTapped(entry); onNavigateToAlbum(entry.entityId)
                                }
                                SearchEntityType.PLAYLIST -> {
                                    viewModel.onHistoryTapped(entry); onNavigateToPlaylist(entry.entityId)
                                }
                                // 歌曲历史：单击播放（对应 iOS PlayableTableCell），无详情页可导航
                                SearchEntityType.SONG -> viewModel.playHistorySong(entry)
                            }
                        }
                    )
                }
            } else if (results.isEmpty && !isSearching) {
                item(key = "no_results") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No Results", color = MaterialTheme.colorScheme.secondaryLabel)
                    }
                }
            } else {
                // ===== Artists =====
                if (results.artists.isNotEmpty()) {
                    item(key = "artists_header") { SearchSectionHeader("Artists") }
                    items(results.artists, key = { "artist_${it.id}" }) { artist ->
                        SearchEntityRow(
                            title = artist.name,
                            subtitle = artist.getSubtitle() ?: "",
                            coverArt = artist.coverArt,
                            circular = false, // iOS 2.1 搜索结果艺术家行为 GenericTableCell 圆角方形，非圆形
                            isFavorite = artist.isFavorite,
                            defaultArtworkType = DefaultArtworkType.ARTIST,
                            onClick = {
                                viewModel.onArtistTapped(artist)
                                onNavigateToArtist(artist.id)
                            }
                        )
                    }
                }

                // ===== Albums =====
                if (results.albums.isNotEmpty()) {
                    item(key = "albums_header") { SearchSectionHeader("Albums") }
                    items(results.albums, key = { "album_${it.id}" }) { album ->
                        SearchEntityRow(
                            title = album.name,
                            subtitle = album.artist,
                            coverArt = album.coverArt,
                            isFavorite = album.isFavorite,
                            onClick = {
                                viewModel.onAlbumTapped(album)
                                onNavigateToAlbum(album.id)
                            }
                        )
                    }
                }

                // ===== Playlists =====
                if (results.playlists.isNotEmpty()) {
                    item(key = "playlists_header") { SearchSectionHeader("Playlists") }
                    items(results.playlists, key = { "playlist_${it.id}" }) { playlist ->
                        SearchEntityRow(
                            title = playlist.name,
                            subtitle = "${playlist.songCount} Song${if (playlist.songCount == 1) "" else "s"}",
                            coverArt = playlist.coverArt,
                            defaultArtworkType = DefaultArtworkType.PLAYLIST,
                            onClick = {
                                viewModel.onPlaylistTapped(playlist)
                                onNavigateToPlaylist(playlist.id)
                            }
                        )
                    }
                }

                // ===== Songs =====
                if (results.songs.isNotEmpty()) {
                    item(key = "songs_header") { SearchSectionHeader("Songs") }
                    items(results.songs, key = { "song_${it.id}" }) { song ->
                        SongListItem(
                            song = song,
                            style = SongListItemStyle.ARTWORK,
                            isPlaying = currentPlayingSong?.id == song.id,
                            showShuffleInMenu = false,
                            downloadProgressMap = downloadProgressMap,
                            callbacks = songCallbacks(song)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 搜索作用域选择器（All / Cached）——对齐 iOS searchBar.scopeButtonTitles ["All", "Cached"]，
 * 由本页顶部渲染（iOS 实机 scope 栏位于页面顶部而非底部输入条内）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScopeSelector(
    scope: SearchViewModel.SearchScope,
    onScopeChange: (SearchViewModel.SearchScope) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        SearchViewModel.SearchScope.entries.forEachIndexed { index, value ->
            SegmentedButton(
                selected = scope == value,
                onClick = { onScopeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, SearchViewModel.SearchScope.entries.size),
                label = { Text(if (value == SearchViewModel.SearchScope.ALL) "All" else "Cached") }
            )
        }
    }
}

/**
 * 结果分段段头 —— 规格为标准 grouped 段头（40pt + 13sp 全大写灰字）：
 * iOS SearchVC 是 `.grouped` 表，五段（Recently Searched / Playlists / Artists /
 * Albums / Songs）的段头统一走 `CommonScreenOperations.tableSectionHeightLarge = 40`
 * （SearchVC.swift:400-420 heightForHeaderInSection），文案由
 * titleForHeaderInSection 给出（SearchVC.swift:43-60）。此前为 20sp SemiBold 自创规格。
 */
@Composable
private fun SearchSectionHeader(title: String) {
    GroupedSectionHeader(
        title = title,
        modifier = Modifier.background(MaterialTheme.colorScheme.background)
    )
}

/**
 * 通用搜索结果行（艺术家/专辑/播放列表）：封面 + 标题 + 副标题 + chevron
 */
@Composable
private fun SearchEntityRow(
    title: String,
    subtitle: String,
    coverArt: String?,
    onClick: () -> Unit,
    circular: Boolean = false, // iOS 2.1 无圆形头像场景，保留参数以便最小改动；当前所有调用点均为 false（圆角方形）
    isFavorite: Boolean = false,
    showChevron: Boolean = true,
    // 封面缺失/加载中/失败时的默认艺术图类型（按主题色现画，见 ui/util/DefaultArtwork.kt）
    defaultArtworkType: DefaultArtworkType = DefaultArtworkType.ALBUM
) {
    val credentialsManager = LocalCredentialsManager.current
    val musicRepository = LocalMediaUrlRepository.current
    val defaultArtwork = rememberDefaultArtworkPainter(defaultArtworkType)

    // 行容器全宽：横向 16dp 落到行内 padding（iOS cell 全宽高亮：layoutMargins 在 cell
    // 内部，BasicTableCell 覆盖为 (9,16,9,16)，CommonScreenOperations.swift:41-47）
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = buildCoverArtUrl(coverArt, credentialsManager, musicRepository),
                contentDescription = title,
                modifier = Modifier
                    .size(48.dp)
                    .clip(if (circular) CircleShape else RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
                // 对齐 iOS LibraryEntityImage.refresh()：先显示生成占位图，真图加载完成才替换，UI 不等网络；
                // placeholder 覆盖加载中（含滑动回来重新发起请求期间），error/fallback 兜底失败与空模型，均避免露出底色成「白图」
                placeholder = defaultArtwork,
                error = defaultArtwork,
                fallback = defaultArtwork
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 收藏图标 - 对应 iOS: GenericTableCell.favoriteIconImage
            if (isFavorite) {
                Icon(
                    AmperfyIcons.suitHeartFill,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.systemRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            // 右尖括号 - 对应 iOS: accessoryType = .disclosureIndicator（可导航行才显示）
            if (showChevron) {
                Icon(
                    AmperfyIcons.chevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiaryLabel,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
        // separatorInset = cell layoutMargins 左右值，CommonScreenOperations.swift:41-47）
        HairlineDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.separator
        )
    }
}

/**
 * 搜索历史行：与结果行同样式，左侧叠加历史图标语义（用封面 + 副标题区分类型）
 */
@Composable
private fun SearchHistoryRow(
    entry: SearchHistoryEntry,
    onClick: () -> Unit
) {
    SearchEntityRow(
        title = entry.name,
        subtitle = entry.subtitle,
        coverArt = entry.coverArt,
        circular = false, // iOS 2.1 无圆形头像场景，艺术家历史行同为圆角方形
        // 歌曲历史单击是播放而非导航，不显示 chevron（对应 iOS PlayableTableCell 无 disclosure）
        showChevron = entry.type != SearchEntityType.SONG,
        // 默认图逐类分派（SearchEntityType 四值穷举，逐条对应 iOS 各实体的
        // getDefaultArtworkType()：Artist.swift:197 / AbstractPlayable.swift:391-400 /
        // Album.swift:238 / Playlist.swift:482-484）
        defaultArtworkType = when (entry.type) {
            SearchEntityType.ARTIST -> DefaultArtworkType.ARTIST
            SearchEntityType.ALBUM -> DefaultArtworkType.ALBUM
            SearchEntityType.PLAYLIST -> DefaultArtworkType.PLAYLIST
            SearchEntityType.SONG -> DefaultArtworkType.SONG
        },
        onClick = onClick
    )
}
