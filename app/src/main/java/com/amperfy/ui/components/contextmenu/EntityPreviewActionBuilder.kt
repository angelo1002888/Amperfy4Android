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

package com.amperfy.ui.components.contextmenu

import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Directory
import com.amperfy.data.model.Genre
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.Radio
import com.amperfy.data.model.PodcastEpisodeRemoteStatus
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.components.IOSContextMenuItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.formatDurationShortString
import com.amperfy.ui.components.formatEpisodeDuration
import com.amperfy.ui.components.formatPublishDate
import com.amperfy.ui.components.formatSongDuration
import com.amperfy.ui.theme.AmperfyIcons

/**
 * 实体长按预览卡 + 上下文菜单的集中构建器
 * 对应 iOS EntityPreviewActionBuilder（EntityPreviewVC.swift:30-815，文件名与类名对齐）
 *
 * iOS createMenuActions()（:90-170）的六块结构，本文件全部构建器一律照此顺序装配：
 *   ① Play / Shuffle
 *   ② Music Queue 子菜单（音乐实体）或 Podcast Queue 两项平铺（播客实体）
 *   ③ Goto 组（Show Album / Show Artist / Show Podcast / Show ... Description）
 *   ④ Favorite（行内二态）+ Rating 调色板
 *   ⑤ Add to Playlist / Download / Delete Cache / Delete on Server / Go to Site
 *   ⑥ Copy ID to Clipboard（isShowDetailedInfo 时，独立分组垫底）
 * 块间以 Divider 分隔，一律走 addDividerIfNotEmpty() 避免顶部悬空或连续双 Divider。
 *
 * 通用条件（iOS 各 configureFor 与 createMenuActions 守卫）：
 * - 离线模式抹掉 Favorite / Rating / Add to Playlist / Download
 * - Play / Shuffle 离线时仅在有缓存项时显示
 * - Delete Cache 条件 = hasCachedSongs（与 Download 可同时出现）
 * - Shuffle 受 Disable Player Shuffle Button 门控：开关关闭时**整项不出现**
 *   （iOS 各 configureFor 的 isShuffle 都 `&& isPlayerShuffleButtonEnabled`，真机确认为隐藏而非禁用）。
 *   注意：详情页头部的 Play/Shuffle 大按钮不适用本规则——iOS 那处是 disabled 不隐藏，Android 保持现状
 *
 * 动作一律回落到各实体既有的滑动动作执行路径（xxx.handleSwipeAction），
 * 菜单与滑动共用同一实现，不存在第二份队列/下载逻辑。
 */

/**
 * 菜单构建的环境开关（对应 iOS appDelegate.storage.settings.user 的三项读取）
 *
 * @param isOfflineMode 离线模式（iOS isOfflineMode / isOnlineMode）
 * @param isShuffleActionEnabled Disable Player Shuffle Button 的反面（iOS
 *   isPlayerShuffleButtonEnabled）；false 时上下文菜单整项省略 Shuffle
 * @param isShowDetailedInfo Detailed Information（iOS isShowDetailedInfo）：
 *   控制 Copy ID 项与预览卡 info 行的 ID/Bitrate 追加
 */
data class MenuEnv(
    val isOfflineMode: Boolean,
    val isShuffleActionEnabled: Boolean,
    val isShowDetailedInfo: Boolean,
)

// ═══════════════════════════════════════════════════════════
// 公共段（各实体构建器共用，避免六块结构在每个构建器里重复手写）
// ═══════════════════════════════════════════════════════════

/** 块间分隔线：列表非空才加，避免菜单顶部悬空 Divider（iOS displayInline 分组的等价表现） */
private fun MutableList<IOSContextMenuItem>.addDividerIfNotEmpty() {
    if (isNotEmpty()) add(IOSContextMenuItem.Divider)
}

/**
 * Play / Shuffle 组 - 对应 iOS createPlayAction() / createPlayShuffledAction()（:416-444）
 *
 * @param show 该组是否显示（各实体 configureFor 的 isPlay 门控结果）
 * @param onPlay Play 回调
 * @param onShuffle Shuffle 回调（**不得与 onPlay 共用**）
 */
private fun MutableList<IOSContextMenuItem>.addPlayShuffleGroup(
    show: Boolean,
    isShuffleActionEnabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit
) {
    if (!show) return
    add(IOSContextMenuItem.Action(
        text = "Play",
        icon = AmperfyIcons.play,
        onClick = onPlay
    ))
    // Disable Player Shuffle Button 关闭时整项不出现
    // （iOS isShuffle = ... && isPlayerShuffleButtonEnabled，EntityPreviewVC.swift 各 configureFor）
    if (isShuffleActionEnabled) {
        add(IOSContextMenuItem.Action(
            text = "Shuffle",
            icon = AmperfyIcons.shuffle,
            onClick = onShuffle
        ))
    }
}

/**
 * Music Queue 子菜单 - 对应 iOS createMusicQueueAction()（:446-465）
 * 四项文案逐字对齐 iOS
 */
private fun buildMusicQueueSubmenu(
    onAction: (SwipeActionType) -> Unit
): IOSContextMenuItem.Submenu = IOSContextMenuItem.Submenu(
    text = "Music Queue",
    icon = AmperfyIcons.listBullet,
    items = listOf(
        IOSContextMenuItem.Action(
            text = "Insert Context Queue",
            onClick = { onAction(SwipeActionType.INSERT_CONTEXT_QUEUE) }
        ),
        IOSContextMenuItem.Action(
            text = "Append Context Queue",
            onClick = { onAction(SwipeActionType.APPEND_CONTEXT_QUEUE) }
        ),
        IOSContextMenuItem.Action(
            text = "Insert User Queue",
            onClick = { onAction(SwipeActionType.INSERT_USER_QUEUE) }
        ),
        IOSContextMenuItem.Action(
            text = "Append User Queue",
            onClick = { onAction(SwipeActionType.APPEND_USER_QUEUE) }
        )
    )
)

/**
 * Podcast Queue 两项平铺 - 对应 iOS createPodcastQueueAction()（:467-478）
 * iOS 为 displayInline 且无标题的 UIMenu，故此处不套 Submenu，直接两个 Action 平铺
 */
private fun MutableList<IOSContextMenuItem>.addPodcastQueueGroup(
    show: Boolean,
    onAction: (SwipeActionType) -> Unit
) {
    if (!show) return
    add(IOSContextMenuItem.Action(
        text = "Insert Podcast Queue",
        icon = AmperfyIcons.podcastQueueInsert,
        onClick = { onAction(SwipeActionType.INSERT_PODCAST_QUEUE) }
    ))
    add(IOSContextMenuItem.Action(
        text = "Append Podcast Queue",
        icon = AmperfyIcons.podcastQueueAppend,
        onClick = { onAction(SwipeActionType.APPEND_PODCAST_QUEUE) }
    ))
}

/**
 * Favorite 行内二态 - 对应 iOS createFavoriteMenu()（:480-484）
 *
 * 图标语义对齐 iOS `heart` / `heart.slash`：未收藏时空心心，
 * 已收藏时**斜杠心**（表示"点此取消收藏"）——Android 原为空心/实心互换，与 iOS 相异。
 */
private fun buildFavoriteAction(
    isFavorite: Boolean,
    onToggle: () -> Unit
): IOSContextMenuItem.Action = IOSContextMenuItem.Action(
    text = if (isFavorite) "Unmark favorite" else "Favorite",
    icon = if (isFavorite) AmperfyIcons.heartSlash else AmperfyIcons.heartEmpty,
    onClick = onToggle
)

/** Rating 调色板 - 对应 iOS createRatingMenu()（:503-549，displayAsPalette） */
private fun buildRatingPalette(
    rating: Int,
    onSetRating: (Int) -> Unit
): IOSContextMenuItem.RatingPalette {
    val ratingText = if (rating == 0) "Not rated"
    else "$rating Star${if (rating > 1) "s" else ""}"
    return IOSContextMenuItem.RatingPalette(
        text = "Rating: $ratingText",
        icon = if (rating == 0) AmperfyIcons.starEmpty else AmperfyIcons.starFill,
        currentRating = rating,
        onRatingSelected = onSetRating
    )
}

/**
 * Favorite + Rating 组 - 对应 iOS createMenuActions() 的 ratingFavActions 块（:136-146）
 * 整组由 isOnlineMode 守卫；onSetRating 为 null（页面无评分能力）时省略 Rating
 */
private fun MutableList<IOSContextMenuItem>.addFavoriteRatingGroup(
    isOfflineMode: Boolean,
    isFavorite: Boolean,
    rating: Int,
    onAction: (SwipeActionType) -> Unit,
    onSetRating: ((Int) -> Unit)?
) {
    if (isOfflineMode) return
    addDividerIfNotEmpty()
    add(buildFavoriteAction(isFavorite) { onAction(SwipeActionType.FAVORITE) })
    onSetRating?.let { add(buildRatingPalette(rating, it)) }
}

/**
 * Add to Playlist - 对应 iOS createAddToPlaylistAction()（:590-599）
 * 走 SwipeActionCoordinator 弹 PlaylistSelectorDialog
 */
private fun MutableList<IOSContextMenuItem>.addAddToPlaylistAction(
    show: Boolean,
    onAction: (SwipeActionType) -> Unit
) {
    if (!show) return
    addDividerIfNotEmpty()
    add(IOSContextMenuItem.Action(
        text = "Add to Playlist",
        icon = AmperfyIcons.playlistPlus,
        onClick = { onAction(SwipeActionType.ADD_TO_PLAYLIST) }
    ))
}

/**
 * Download / Delete Cache 组 - 对应 iOS createDownloadAction() / createDeleteCacheAction()
 * （:150-155、673-705）
 *
 * iOS：Download 由 isDownloadPossible 守卫（非全缓存 && 在线），Delete Cache 由
 * playables.hasCachedItems 守卫——二者可同时出现（容器部分缓存）
 *
 * @param isFullyCached 容器全部歌曲已缓存（对应 iOS isCachedCompletely）；true 时隐藏 Download
 */
private fun MutableList<IOSContextMenuItem>.addDownloadDeleteCacheGroup(
    isOfflineMode: Boolean,
    hasCachedSongs: Boolean,
    onAction: (SwipeActionType) -> Unit,
    isFullyCached: Boolean = false
) {
    val showDownload = !isOfflineMode && !isFullyCached
    if (!showDownload && !hasCachedSongs) return
    addDividerIfNotEmpty()
    if (showDownload) {
        add(IOSContextMenuItem.Action(
            text = "Download",
            icon = AmperfyIcons.download,
            onClick = { onAction(SwipeActionType.DOWNLOAD) }
        ))
    }
    if (hasCachedSongs) {
        // 复用滑动路径：结果为 NeedsConfirmation，由 Screen 已挂的确认弹窗承接
        add(IOSContextMenuItem.Action(
            text = "Delete Cache",
            icon = AmperfyIcons.trash,
            destructive = true,
            onClick = { onAction(SwipeActionType.REMOVE_FROM_CACHE) }
        ))
    }
}

/**
 * Copy ID to Clipboard - 对应 iOS createCopyIdToClipboardAction()（:793-802）
 * iOS 为独立 displayInline 分组，垫在菜单最底
 */
private fun MutableList<IOSContextMenuItem>.addCopyId(
    show: Boolean,
    onCopyId: () -> Unit
) {
    if (!show) return
    addDividerIfNotEmpty()
    add(IOSContextMenuItem.Action(
        text = "Copy ID to Clipboard",
        icon = AmperfyIcons.clipboard,
        onClick = onCopyId
    ))
}

// ═══════════════════════════════════════════════════════════
// Song
// ═══════════════════════════════════════════════════════════

/**
 * 歌曲上下文菜单项构建 - 对应 iOS createMenuActions() + configureFor(song:)（:236-260）
 * （行 More 按钮下拉与长按预览菜单共用同一份 items）
 *
 * iOS 门控（离线且未缓存时 Play/Shuffle/Music Queue 整体消失）：
 *   isPlay = !(playContextCb == nil || (!song.isCached && isOfflineMode))
 *   isShuffle 同上 + isPlayerShuffleButtonEnabled（Android 改为置灰，见文件头约定）
 *   isMusicQueue 同 isPlay
 *
 * iOS 此处还有 Show Lyrics（createShowLyricsAction，:771-781）：依赖歌词落盘到
 * lyricsRelFilePath。Android 现为按需拉取 + 内存缓存，落盘缓存待补，
 * 故该项暂缺。
 *
 * @param isCached 已缓存（含"下载刚完成"）
 * @param isDownloading 正在下载（Delete Cache 置灰用）
 * @param showShuffle 页面是否提供 Shuffle（对应 iOS playerIndexCb != nil 时省略）
 * @param showAlbum Show Album 门控，对应 iOS `!(rootView is AlbumDetailVC)`
 * @param showArtist Show Artist 门控，对应 iOS `!(rootView is ArtistDetailVC)`
 */
fun buildSongContextMenuItems(
    song: Song,
    env: MenuEnv,
    isCached: Boolean,
    isDownloading: Boolean,
    showShuffle: Boolean,
    showAlbum: Boolean,
    showArtist: Boolean,
    onCopyId: () -> Unit = {},
    callbacks: SongListItemCallbacks
): List<IOSContextMenuItem> = buildList {
    // 离线且未缓存：Play / Shuffle / Music Queue 整体不可用（iOS isPlay/isMusicQueue 门控）
    val isPlayable = !(env.isOfflineMode && !isCached)

    // ① Play / Shuffle
    if (isPlayable) {
        add(IOSContextMenuItem.Action(
            text = "Play",
            icon = AmperfyIcons.play,
            onClick = callbacks.onClick
        ))
        // Disable Player Shuffle Button 关闭时整项不出现（iOS configureFor(song:) isShuffle 门控）
        if (showShuffle && env.isShuffleActionEnabled) {
            add(IOSContextMenuItem.Action(
                text = "Shuffle",
                icon = AmperfyIcons.shuffle,
                onClick = callbacks.onShuffle
            ))
        }
    }

    // ② Music Queue 子菜单（门控同 Play）
    if (isPlayable) {
        addDividerIfNotEmpty()
        add(IOSContextMenuItem.Submenu(
            text = "Music Queue",
            icon = AmperfyIcons.listBullet,
            items = listOf(
                IOSContextMenuItem.Action(
                    text = "Insert Context Queue",
                    onClick = callbacks.onInsertContextQueue
                ),
                IOSContextMenuItem.Action(
                    text = "Append Context Queue",
                    onClick = callbacks.onAppendContextQueue
                ),
                IOSContextMenuItem.Action(
                    text = "Insert User Queue",
                    onClick = callbacks.onAddToQueueNext
                ),
                IOSContextMenuItem.Action(
                    text = "Append User Queue",
                    onClick = callbacks.onAddToQueueLater
                )
            )
        ))
    }

    // ③ Goto 组：Show Album / Show Artist（无对应 id 时无处可跳，整项省略）
    val gotoAlbum = showAlbum && song.albumId != null
    val gotoArtist = showArtist && song.artistId != null
    if (gotoAlbum || gotoArtist) {
        addDividerIfNotEmpty()
        if (gotoAlbum) {
            add(IOSContextMenuItem.Action(
                text = "Show Album",
                icon = AmperfyIcons.album,
                onClick = callbacks.onShowAlbum
            ))
        }
        if (gotoArtist) {
            add(IOSContextMenuItem.Action(
                text = "Show Artist",
                icon = AmperfyIcons.artist,
                onClick = callbacks.onShowArtist
            ))
        }
    }

    // ④ Favorite / Rating：仅在线
    if (!env.isOfflineMode) {
        addDividerIfNotEmpty()
        add(buildFavoriteAction(song.isFavorite, callbacks.onToggleFavorite))
        add(buildRatingPalette(song.rating ?: 0, callbacks.onSetRating))
    }

    // ⑤ Add to Playlist / Download / Delete Cache
    if (!env.isOfflineMode) {
        addDividerIfNotEmpty()
        add(IOSContextMenuItem.Action(
            text = "Add to Playlist",
            icon = AmperfyIcons.playlistPlus,
            onClick = callbacks.onAddToPlaylist
        ))
    }
    // 单曲的 Download 与 Delete Cache 互斥：已缓存 = isCachedCompletely，
    // iOS isDownloadPossible 恰为 false，语义等价
    if (isCached || isDownloading) {
        addDividerIfNotEmpty()
        add(IOSContextMenuItem.Action(
            text = "Delete Cache",
            icon = AmperfyIcons.trash,
            destructive = true,
            enabled = !isDownloading,  // 正在下载时禁用
            onClick = callbacks.onDeleteCache
        ))
    } else if (!env.isOfflineMode) {
        addDividerIfNotEmpty()
        add(IOSContextMenuItem.Action(
            text = "Download",
            icon = AmperfyIcons.download,
            onClick = callbacks.onDownload
        ))
    }

    // ⑥ Copy ID
    addCopyId(env.isShowDetailedInfo, onCopyId)
}

/**
 * 歌曲预览卡片信息行 - 对应 iOS Song.infoDetails(type: .long)（Song.swift:125-160）
 * "Track N · 时长 · Year N · Genre: x"，" · " 中点拼接（PlayableContainable.info）
 * isShowDetailedInfo 开启时追加 Bitrate/Cache MIME Type/ID（Song.swift:142-158）
 */
fun songPreviewInfo(song: Song, isShowDetailedInfo: Boolean = false): String = buildList {
    song.track?.takeIf { it > 0 }?.let { add("Track $it") }
    if (song.duration > 0) add(formatSongDuration(song.duration))
    song.year?.takeIf { it > 0 }?.let { add("Year $it") }
    song.genre?.takeIf { it.isNotBlank() }?.let { add("Genre: $it") }
    if (isShowDetailedInfo) {
        song.bitRate?.takeIf { it > 0 }?.let { add("Bitrate: $it") }
        if (song.isCached) {
            song.contentType?.takeIf { it.isNotBlank() }?.let { add("Cache MIME Type: $it") }
        }
        add("ID: ${song.id.ifEmpty { "-" }}")
    }
}.joinToString(" · ")

// ═══════════════════════════════════════════════════════════
// Album
// ═══════════════════════════════════════════════════════════

/**
 * 专辑上下文菜单项构建 - 对应 iOS createMenuActions() + configureFor(album:)（:377-394）
 *
 * @param hasCachedSongs 含缓存歌曲（iOS playables.hasCachedItems）
 * @param onAction 动作执行入口，一律回落 Album.handleSwipeAction
 * @param onSetRating 评分回调；null = 菜单不含 Rating 项
 * @param onShowArtist 跳转艺术家详情；null = 整项省略。对应 iOS
 *   `isShowArtist = !(rootView is ArtistDetailVC)`——艺术家详情页内的专辑行不显示该项，
 *   专辑无 artistId 时同样无处可跳，亦传 null
 */
fun buildAlbumContextMenuItems(
    album: Album,
    env: MenuEnv,
    hasCachedSongs: Boolean,
    onAction: (SwipeActionType) -> Unit,
    onSetRating: ((Int) -> Unit)? = null,
    onShowArtist: (() -> Unit)? = null,
    onCopyId: () -> Unit = {},
    isFullyCached: Boolean = false
): List<IOSContextMenuItem> = buildList {
    // ① Play / Shuffle：离线且无缓存歌曲时整组省略（iOS isPlay/isShuffle 门控）
    addPlayShuffleGroup(
        show = !env.isOfflineMode || hasCachedSongs,
        isShuffleActionEnabled = env.isShuffleActionEnabled,
        onPlay = { onAction(SwipeActionType.PLAY) },
        onShuffle = { onAction(SwipeActionType.PLAY_SHUFFLED) }
    )

    // ② Music Queue 子菜单
    addDividerIfNotEmpty()
    add(buildMusicQueueSubmenu(onAction))

    // ③ Goto 组：Show Artist（图标与歌曲行 Show Artist 一致）
    onShowArtist?.let { showArtist ->
        addDividerIfNotEmpty()
        add(IOSContextMenuItem.Action(
            text = "Show Artist",
            icon = AmperfyIcons.artist,
            onClick = showArtist
        ))
    }

    // ④ Favorite / Rating：仅在线
    addFavoriteRatingGroup(env.isOfflineMode, album.isFavorite, album.rating, onAction, onSetRating)

    // ⑤ Add to Playlist / Download / Delete Cache
    addAddToPlaylistAction(!env.isOfflineMode, onAction)
    addDownloadDeleteCacheGroup(env.isOfflineMode, hasCachedSongs, onAction, isFullyCached)

    // ⑥ Copy ID
    addCopyId(env.isShowDetailedInfo, onCopyId)
}

/**
 * 专辑预览卡片信息行 - 对应 iOS Album.infoDetails(type: .long)（Album.swift:184-208）
 * "N Songs · Cached · Year Y · Genre: G · 时长 · ID: x"，" · " 中点拼接
 */
fun albumPreviewInfo(
    album: Album,
    hasCachedSongs: Boolean,
    isShowDetailedInfo: Boolean
): String = buildList {
    if (album.songCount > 0) {
        add("${album.songCount} Song${if (album.songCount != 1) "s" else ""}")
    }
    if (hasCachedSongs) add("Cached")
    album.year?.takeIf { it > 0 }?.let { add("Year $it") }
    album.genre?.takeIf { it.isNotBlank() }?.let { add("Genre: $it") }
    if (album.duration > 0) add(formatDurationShortString(album.duration))
    if (isShowDetailedInfo) add("ID: ${album.id.ifEmpty { "-" }}")
}.joinToString(" · ")

// ═══════════════════════════════════════════════════════════
// Artist
// ═══════════════════════════════════════════════════════════

/**
 * 艺术家上下文菜单项构建 - 对应 iOS createMenuActions() + configureFor(artist:)（:358-375）
 * 装配同专辑，但无 Show Artist 项；动作复用滑动执行路径 Artist.handleSwipeAction
 */
fun buildArtistContextMenuItems(
    artist: Artist,
    env: MenuEnv,
    hasCachedSongs: Boolean,
    onAction: (SwipeActionType) -> Unit,
    onSetRating: ((Int) -> Unit)? = null,
    onCopyId: () -> Unit = {},
    isFullyCached: Boolean = false
): List<IOSContextMenuItem> = buildList {
    // ① Play / Shuffle
    addPlayShuffleGroup(
        show = !env.isOfflineMode || hasCachedSongs,
        isShuffleActionEnabled = env.isShuffleActionEnabled,
        onPlay = { onAction(SwipeActionType.PLAY) },
        onShuffle = { onAction(SwipeActionType.PLAY_SHUFFLED) }
    )

    // ② Music Queue 子菜单
    addDividerIfNotEmpty()
    add(buildMusicQueueSubmenu(onAction))

    // ③ Goto 组：艺术家无（iOS isShowAlbum/isShowArtist 均为 false）

    // ④ Favorite / Rating：仅在线
    addFavoriteRatingGroup(env.isOfflineMode, artist.isFavorite, artist.rating, onAction, onSetRating)

    // ⑤ Add to Playlist / Download / Delete Cache
    addAddToPlaylistAction(!env.isOfflineMode, onAction)
    addDownloadDeleteCacheGroup(env.isOfflineMode, hasCachedSongs, onAction, isFullyCached)

    // ⑥ Copy ID
    addCopyId(env.isShowDetailedInfo, onCopyId)
}

/**
 * 艺术家预览卡片信息行 - 对应 iOS Artist.infoDetails(type: .long)（Artist.swift:150-170）
 * "N Albums · N Songs · 时长 · ID: x"
 * 已知偏差：iOS 含 Genre，Android Artist 域模型无 genre 字段，跳过
 */
fun artistPreviewInfo(artist: Artist, isShowDetailedInfo: Boolean): String = buildList {
    if (artist.albumCount > 0) {
        add("${artist.albumCount} Album${if (artist.albumCount != 1) "s" else ""}")
    }
    if (artist.songCount > 0) {
        add("${artist.songCount} Song${if (artist.songCount != 1) "s" else ""}")
    }
    if (artist.duration > 0) add(formatDurationShortString(artist.duration))
    if (isShowDetailedInfo) add("ID: ${artist.id.ifEmpty { "-" }}")
}.joinToString(" · ")

// ═══════════════════════════════════════════════════════════
// Playlist
// ═══════════════════════════════════════════════════════════

/**
 * 播放列表上下文菜单项构建 - 对应 iOS createMenuActions() + configureFor(playlist:)（:302-319）
 * 无 Favorite/Rating——Playlist 非 AbstractLibraryEntity，评分收藏组整组不出现；
 * 动作复用滑动执行路径 Playlist.handleSwipeAction
 */
fun buildPlaylistContextMenuItems(
    env: MenuEnv,
    hasCachedSongs: Boolean,
    onAction: (SwipeActionType) -> Unit,
    onCopyId: () -> Unit = {},
    isFullyCached: Boolean = false
): List<IOSContextMenuItem> = buildList {
    // ① Play / Shuffle
    addPlayShuffleGroup(
        show = !env.isOfflineMode || hasCachedSongs,
        isShuffleActionEnabled = env.isShuffleActionEnabled,
        onPlay = { onAction(SwipeActionType.PLAY) },
        onShuffle = { onAction(SwipeActionType.PLAY_SHUFFLED) }
    )

    // ② Music Queue 子菜单
    addDividerIfNotEmpty()
    add(buildMusicQueueSubmenu(onAction))

    // ③ Goto 组：播放列表无
    // ④ Favorite / Rating：播放列表无（iOS 非 AbstractLibraryEntity）

    // ⑤ Add to Playlist / Download / Delete Cache
    addAddToPlaylistAction(!env.isOfflineMode, onAction)
    addDownloadDeleteCacheGroup(env.isOfflineMode, hasCachedSongs, onAction, isFullyCached)

    // ⑥ Copy ID
    addCopyId(env.isShowDetailedInfo, onCopyId)
}

// ═══════════════════════════════════════════════════════════
// Radio
// ═══════════════════════════════════════════════════════════

/**
 * 预览卡片信息行 - 对应 iOS Radio.infoDetails(details: .long)（Radio.swift:48-59）
 * "Site <siteUrl> · Stream URL <streamUrl>"，" · " 中点拼接；
 * 电台不含基类的 year/duration/bitrate 等项
 * 注：iOS 原文为笔误 "Steam URL"，此处写正确的 "Stream URL"
 */
fun radioPreviewInfo(radio: Radio): String = buildList {
    radio.siteUrl?.takeIf { it.isNotBlank() }?.let { add("Site $it") }
    radio.streamUrl.takeIf { it.isNotBlank() }?.let { add("Stream URL $it") }
}.joinToString(" · ")

/**
 * 电台菜单项构建 - 对应 iOS EntityPreviewVC.createMenuActions() for Radio
 * （More 按钮菜单与长按预览菜单共用，iOS 亦为同一 createMenuActions）
 *
 * 组间分隔线一律写成 `if (isNotEmpty()) add(Divider)`：前面无条目时不插入，
 * 避免菜单顶部悬空 Divider 或连续双 Divider
 * （对应 iOS 各 displayInline 分组为空时整组不追加的编排）
 */
fun buildRadioContextMenuItems(
    isOfflineMode: Boolean,
    siteUrl: String?,
    isShowDetailedInfo: Boolean,
    onPlay: () -> Unit,
    onInsertContextQueue: () -> Unit,
    onAppendContextQueue: () -> Unit,
    onAddToQueueNext: () -> Unit,
    onAddToQueueLater: () -> Unit,
    onGoToSite: () -> Unit,
    onCopyId: () -> Unit
): List<IOSContextMenuItem> = buildList {
    // Play / Music Queue - 对应 iOS createPlayAction()、createMusicQueueAction()
    // 离线模式下 isPlay/isMusicQueue 均为 false，两项一并省略
    if (!isOfflineMode) {
        // Play（与点击行同路）
        add(IOSContextMenuItem.Action(
            text = "Play",
            icon = AmperfyIcons.play,
            onClick = onPlay
        ))

        add(IOSContextMenuItem.Divider)

        // Music Queue 子菜单（文案与歌曲行逐字一致）
        add(IOSContextMenuItem.Submenu(
            text = "Music Queue",
            icon = AmperfyIcons.listBullet,
            items = listOf(
                IOSContextMenuItem.Action(
                    text = "Insert Context Queue",
                    onClick = onInsertContextQueue
                ),
                IOSContextMenuItem.Action(
                    text = "Append Context Queue",
                    onClick = onAppendContextQueue
                ),
                IOSContextMenuItem.Action(
                    text = "Insert User Queue",
                    onClick = onAddToQueueNext
                ),
                IOSContextMenuItem.Action(
                    text = "Append User Queue",
                    onClick = onAddToQueueLater
                )
            )
        ))
    }

    // Go to Site - 对应 iOS createGoToSiteUrl()（EntityPreviewVC.swift:737-741，
    // 仅 siteURL 非空时显示，外开浏览器；不受离线模式影响）
    if (siteUrl != null) {
        if (isNotEmpty()) add(IOSContextMenuItem.Divider)
        add(IOSContextMenuItem.Action(
            text = "Go to Site",
            icon = AmperfyIcons.followLink,
            onClick = onGoToSite
        ))
    }

    // Copy ID to Clipboard - 对应 iOS createCopyIdToClipboardAction()（不受离线模式影响）
    if (isShowDetailedInfo) {
        if (isNotEmpty()) add(IOSContextMenuItem.Divider)
        add(IOSContextMenuItem.Action(
            text = "Copy ID to Clipboard",
            icon = AmperfyIcons.clipboard,
            onClick = onCopyId
        ))
    }
}

// ═══════════════════════════════════════════════════════════
// Genre
// ═══════════════════════════════════════════════════════════

/**
 * 流派上下文菜单项构建 - 对应 iOS createMenuActions() + configureFor(genre:)（:321-338）
 * 与 playlist 完全同构（无 Favorite/Rating——流派非可收藏实体）；
 * 动作复用滑动执行路径 Genre.handleSwipeAction
 */
fun buildGenreContextMenuItems(
    env: MenuEnv,
    hasCachedSongs: Boolean,
    onAction: (SwipeActionType) -> Unit,
    onCopyId: () -> Unit = {},
    isFullyCached: Boolean = false
): List<IOSContextMenuItem> = buildList {
    addPlayShuffleGroup(
        show = !env.isOfflineMode || hasCachedSongs,
        isShuffleActionEnabled = env.isShuffleActionEnabled,
        onPlay = { onAction(SwipeActionType.PLAY) },
        onShuffle = { onAction(SwipeActionType.PLAY_SHUFFLED) }
    )
    addDividerIfNotEmpty()
    add(buildMusicQueueSubmenu(onAction))
    addAddToPlaylistAction(!env.isOfflineMode, onAction)
    addDownloadDeleteCacheGroup(env.isOfflineMode, hasCachedSongs, onAction, isFullyCached)
    addCopyId(env.isShowDetailedInfo, onCopyId)
}

/**
 * 流派预览卡片信息行 - 对应 iOS Genre.infoDetails(type: .long)
 * "X Albums · Y Songs · ID: x"（Subsonic 无流派 id，Copy ID 复制 name，见 Genre.kt）
 */
fun genrePreviewInfo(genre: Genre, isShowDetailedInfo: Boolean): String = buildList {
    add(genre.info)
    if (isShowDetailedInfo) add("ID: ${genre.name.ifEmpty { "-" }}")
}.joinToString(" · ")

// ═══════════════════════════════════════════════════════════
// Directory
// ═══════════════════════════════════════════════════════════

/**
 * 目录上下文菜单项构建 - 对应 iOS createMenuActions() + configureFor(directory:)（:396-414）
 * 装配同 playlist，另加 iOS :408-409 的 Add to Playlist 额外门控
 * `isOnlineMode && !entityContainer.playables.isEmpty`
 *
 * @param hasSongs 该目录（本层）已知含歌曲；false 时省略 Add to Playlist
 */
fun buildDirectoryContextMenuItems(
    env: MenuEnv,
    hasCachedSongs: Boolean,
    hasSongs: Boolean,
    onAction: (SwipeActionType) -> Unit,
    onCopyId: () -> Unit = {}
): List<IOSContextMenuItem> = buildList {
    addPlayShuffleGroup(
        show = !env.isOfflineMode || hasCachedSongs,
        isShuffleActionEnabled = env.isShuffleActionEnabled,
        onPlay = { onAction(SwipeActionType.PLAY) },
        onShuffle = { onAction(SwipeActionType.PLAY_SHUFFLED) }
    )
    addDividerIfNotEmpty()
    add(buildMusicQueueSubmenu(onAction))
    addAddToPlaylistAction(!env.isOfflineMode && hasSongs, onAction)
    // 目录豁免 A5 的 isCachedCompletely 隐藏：目录歌曲需下钻同步后才可知全貌，
    // 无法在列表行上给出可靠的"全部已缓存"判定（已知简化）
    addDownloadDeleteCacheGroup(env.isOfflineMode, hasCachedSongs, onAction)
    addCopyId(env.isShowDetailedInfo, onCopyId)
}

/**
 * 目录预览卡片信息行 - 对应 iOS Directory.info(for:type:)
 * 已知偏差：Android Directory 域模型无歌曲/子目录计数（列表接口不下发），
 * 故仅在 Detailed Information 开启时显示 ID，其余为空串（卡片 info 行留空）
 */
fun directoryPreviewInfo(directory: Directory, isShowDetailedInfo: Boolean): String = buildList {
    if (isShowDetailedInfo) add("ID: ${directory.id.ifEmpty { "-" }}")
}.joinToString(" · ")

// ═══════════════════════════════════════════════════════════
// Podcast / PodcastEpisode
// ═══════════════════════════════════════════════════════════

/**
 * 播客上下文菜单项构建 - 对应 iOS createMenuActions() + configureFor(podcast:)（:340-356）
 *
 * iOS 该处 `isPlay = (isOnlineMode || hasCachedItems) && isPlayerShuffleButtonEnabled`——
 * Play 被 shuffle 开关门控，与其余实体的 isPlay 定义不一致，**疑似上游 bug**，
 * 此处刻意不复刻：Play 仅按 `isOnlineMode || hasCachedItems` 判定。
 *
 * Podcast Queue 两项平铺（iOS displayInline 无标题子菜单）。
 * Download / Delete Cache 省略：容器（播客）级聚合缓存态未接线（Batch 4 只落地单集级）。
 *
 * @param hasCachedEpisodes 含缓存单集（只影响离线时 Play/Queue 门控）；容器级 Download/Delete Cache 未接线
 */
fun buildPodcastContextMenuItems(
    env: MenuEnv,
    hasCachedEpisodes: Boolean = false,
    onAction: (SwipeActionType) -> Unit,
    onShowDescription: () -> Unit,
    onCopyId: () -> Unit = {}
): List<IOSContextMenuItem> = buildList {
    // ① Play（无 Shuffle：iOS isShuffle = false）
    if (!env.isOfflineMode || hasCachedEpisodes) {
        add(IOSContextMenuItem.Action(
            text = "Play",
            icon = AmperfyIcons.play,
            onClick = { onAction(SwipeActionType.PLAY) }
        ))
    }

    // ② Podcast Queue 两项平铺
    if (!env.isOfflineMode || hasCachedEpisodes) {
        addDividerIfNotEmpty()
        addPodcastQueueGroup(show = true, onAction = onAction)
    }

    // ③ Goto 组：Show Podcast Description
    addDividerIfNotEmpty()
    add(IOSContextMenuItem.Action(
        text = "Show Podcast Description",
        icon = AmperfyIcons.info,
        onClick = onShowDescription
    ))

    // ④⑤ Favorite/Rating、Add to Playlist、Download/Delete Cache：播客均无

    // ⑥ Copy ID
    addCopyId(env.isShowDetailedInfo, onCopyId)
}

/**
 * 播客预览卡片信息行 - 对应 iOS Podcast.infoDetails：'N Episode(s)'（+ ID）
 */
fun podcastPreviewInfo(podcast: Podcast, isShowDetailedInfo: Boolean): String = buildList {
    add(podcast.info)
    if (isShowDetailedInfo) add("ID: ${podcast.id.ifEmpty { "-" }}")
}.joinToString(" · ")

/**
 * 播客单集上下文菜单项构建 - 对应 iOS createMenuActions() + configureFor(podcastEpisode:)
 * （:262-284）
 *
 * iOS 门控：
 *   isPlay = !((!isAvailableToUser && isOnlineMode) || (!isCached && isOfflineMode))
 *   isPodcastQueue 同上
 *   isShowArtist = !(rootView is PodcastDetailVC) → 本文件的 showPodcast 参数
 *   isDeleteOnServer = podcastStatus != .deleted && isOnlineMode
 *   isDownloadPossible = !(isCachedCompletely || isOfflineMode || !isDownloadAvailable)
 *     （:60-66 + AbstractPlayable.swift:471-479，单集 isDownloadAvailable = isAvailableToUser）
 *   Delete Cache = playables.hasCachedItems（:153-155）
 * Batch 4 起 Download / Delete Cache 落地（单集下载管线已实现）。
 *
 * @param showPodcast Show Podcast 门控；PodcastDetail 页内传 false
 */
fun buildPodcastEpisodeContextMenuItems(
    episode: PodcastEpisode,
    env: MenuEnv,
    showPodcast: Boolean,
    onAction: (SwipeActionType) -> Unit,
    onShowPodcast: () -> Unit,
    onShowDescription: () -> Unit,
    onDeleteOnServer: () -> Unit,
    onCopyId: () -> Unit = {}
): List<IOSContextMenuItem> = buildList {
    // iOS :262-284：在线要求 isAvailableToUser、离线要求已缓存
    // （isAvailableToUser 自身已含 cached 优先，故离线分支单判 isDownloaded）
    val isPlayable = if (env.isOfflineMode) episode.isDownloaded else episode.isAvailableToUser

    // ① Play（无 Shuffle：iOS isShuffle = false）
    if (isPlayable) {
        add(IOSContextMenuItem.Action(
            text = "Play",
            icon = AmperfyIcons.play,
            onClick = { onAction(SwipeActionType.PLAY) }
        ))
    }

    // ② Podcast Queue 两项平铺
    if (isPlayable) {
        addDividerIfNotEmpty()
        addPodcastQueueGroup(show = true, onAction = onAction)
    }

    // ③ Goto 组：Show Podcast（iOS createShowArtistAction 的单集变体标题）+ Show Episode Description
    addDividerIfNotEmpty()
    if (showPodcast) {
        add(IOSContextMenuItem.Action(
            text = "Show Podcast",
            icon = AmperfyIcons.squareArrow,
            onClick = onShowPodcast
        ))
    }
    add(IOSContextMenuItem.Action(
        text = "Show Episode Description",
        icon = AmperfyIcons.info,
        onClick = onShowDescription
    ))

    // ⑤ 元素操作块（iOS elementHandlingActions 为**单个** displayInline 分组，:146-158：
    //    Download → Delete Cache → Delete on Server 依次入组），故三项共用一条组间分隔线。
    //    Download：!isCachedCompletely && 在线 && isDownloadAvailable（单集即 isAvailableToUser）
    //    Delete Cache：hasCachedItems
    //    Delete on Server：podcastStatus != .deleted && 在线
    val showDownload = !env.isOfflineMode && !episode.isDownloaded && episode.isAvailableToUser
    val showDeleteCache = episode.isDownloaded
    val showDeleteOnServer = !env.isOfflineMode && episode.status != PodcastEpisodeRemoteStatus.DELETED
    if (showDownload || showDeleteCache || showDeleteOnServer) {
        addDividerIfNotEmpty()
        if (showDownload) {
            add(IOSContextMenuItem.Action(
                text = "Download",
                icon = AmperfyIcons.download,
                onClick = { onAction(SwipeActionType.DOWNLOAD) }
            ))
        }
        if (showDeleteCache) {
            // 复用滑动路径：结果为 NeedsEpisodeCacheConfirmation，由 Screen 已挂的确认弹窗承接
            add(IOSContextMenuItem.Action(
                text = "Delete Cache",
                icon = AmperfyIcons.trash,
                destructive = true,
                onClick = { onAction(SwipeActionType.REMOVE_FROM_CACHE) }
            ))
        }
        if (showDeleteOnServer) {
            add(IOSContextMenuItem.Action(
                text = "Delete on Server",
                icon = AmperfyIcons.cloudX,
                destructive = true,
                onClick = onDeleteOnServer
            ))
        }
    }

    // ⑥ Copy ID
    addCopyId(env.isShowDetailedInfo, onCopyId)
}

/**
 * 单集预览卡片信息行 - 对应 iOS PodcastEpisode.infoDetails(type: .long)
 * "发布日期 · 时长 · 状态 · ID: x"；状态段仅在不可用时出现（同列表行 :100-102）
 */
fun episodePreviewInfo(episode: PodcastEpisode, isShowDetailedInfo: Boolean): String = buildList {
    formatPublishDate(episode.publishDate)?.let { add(it) }
    if (episode.duration > 0) add(formatEpisodeDuration(episode.duration))
    episode.unavailableDescription?.let { add(it) }
    if (isShowDetailedInfo) add("ID: ${episode.id.ifEmpty { "-" }}")
}.joinToString(" · ")

/**
 * 播放列表预览卡片信息行 - 对应 iOS Playlist.info(for:type:)（Playlist.swift）
 * "N Songs · 时长 · ID: x"
 * 已知偏差：iOS 含 Smart Playlist 标记与 Cached 标记，Android Playlist 域模型
 * 无 isSmartPlaylist 字段、行内亦无缓存态，两项跳过
 */
fun playlistPreviewInfo(playlist: Playlist, isShowDetailedInfo: Boolean): String = buildList {
    add("${playlist.songCount} Song${if (playlist.songCount != 1) "s" else ""}")
    if (playlist.duration > 0) add(formatDurationShortString(playlist.duration))
    if (isShowDetailedInfo) add("ID: ${playlist.id.ifEmpty { "-" }}")
}.joinToString(" · ")
