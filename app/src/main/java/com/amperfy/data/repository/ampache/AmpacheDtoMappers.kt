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

package com.amperfy.data.repository.ampache

import com.amperfy.data.model.Album
import com.amperfy.data.model.Artist
import com.amperfy.data.model.Genre
import com.amperfy.data.model.MusicFolder
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisode
import com.amperfy.data.model.PodcastEpisodeRemoteStatus
import com.amperfy.data.model.Radio
import com.amperfy.data.model.Song
import com.amperfy.data.remote.ampache.AmpacheAlbumDto
import com.amperfy.data.remote.ampache.AmpacheArtistDto
import com.amperfy.data.remote.ampache.AmpacheArtworkInfo
import com.amperfy.data.remote.ampache.AmpacheCatalogDto
import com.amperfy.data.remote.ampache.AmpacheGenreDto
import com.amperfy.data.remote.ampache.AmpachePlaylistDto
import com.amperfy.data.remote.ampache.AmpachePodcastDto
import com.amperfy.data.remote.ampache.AmpachePodcastEpisodeDto
import com.amperfy.data.remote.ampache.AmpacheRadioDto
import com.amperfy.data.remote.ampache.AmpacheSongDto
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Ampache DTO → 领域模型映射（对应 Subsonic 侧 SubsonicDtoMappers.kt 的位置）。
// 字段口径逐条对照 iOS `AmperfyKit/Api/Ampache/*ParserDelegate.swift`（各函数注释里给出处），
// 缺省值口径对照 Android 既有 Subsonic 映射，保证同一领域模型两后端语义一致。
// 出处：Ampache API 移植 Batch 2（本批定下的映射约定）。

/**
 * Ampache 收藏时间戳占位值（约定）。
 *
 * Ampache 的 `<flag>` 只有 0/1、**没有收藏日期**（iOS Ampache 侧 `starredDate` 同样恒 nil，
 * ArtistParserDelegate.swift:81 等只写 isFavorite）。Android 的领域模型用
 * `starred: Long?` 同时承载「是否收藏」与「收藏时间」，故对 Ampache 存占位 0L
 * ——`starred != null` 即收藏，语义正确；Favorite 页「Starred date」排序在两端都退化为
 * 次序不定（与 iOS 现状一致）。
 *
 * 刻意**不用** `System.currentTimeMillis()`：那会让每次同步都改写时间戳，
 * 排序结果随同步顺序乱跳，且看着像真实收藏时间，误导性更强。
 */
internal const val AMPACHE_FLAGGED_STARRED_AT: Long = 0L

/**
 * `<art>` URL → `cover_art` 列存的封面标识（约定）。
 *
 * Ampache 的封面不是 id 而是一条完整的 `image.php?auth=…&object_id=…&object_type=…` URL。
 * 会话 token 会轮换，故**去掉 `auth`/`ssid` 后**再落库：
 * - 信息保全：`AmpacheArtworkInfo.fromUrl` 可从中反提取 object_id/object_type，
 *   MediaUrl 域的 getCoverArtUrl 按当前会话 token 现拼；
 * - 去 token：落库的字符串不含会话密钥，Coil 缓存键也不会因 token 轮换而全废。
 *
 * 反提取不出 object_id/object_type（非 image.php 形态）时返回 null——存了也没用。
 */
internal fun ampacheCoverArtKey(artworkUrl: String?): String? {
    val trimmed = artworkUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (AmpacheArtworkInfo.fromUrl(trimmed) == null) return null
    return url.newBuilder()
        .removeAllQueryParameters("auth")
        .removeAllQueryParameters("ssid")
        .build()
        .toString()
}

/**
 * HTML 反转义（对应 iOS `String.html2String`，Utilities.swift:231-233）。
 *
 * **两遍**是照抄 iOS：Ampache 对播客标题/描述做了二次转义（`&amp;lt;` 这类），
 * 一遍解不干净。[HtmlCompat.FROM_HTML_MODE_LEGACY] 与 iOS 的
 * `NSAttributedString(documentType: .html)` 定位一致。
 *
 * 本函数依赖 Android framework（Html 解析），故映射层各播客函数收 `htmlDecoder` 形参、
 * 由生产侧传本函数引用——mapper 才能做纯 JVM 单测。
 */
internal fun String.ampacheHtml2String(): String {
    val once = androidx.core.text.HtmlCompat
        .fromHtml(this, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    return androidx.core.text.HtmlCompat
        .fromHtml(once, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
}

/** catalog → MusicFolder（iOS CatalogParserDelegate：只消费 id + name） */
internal fun AmpacheCatalogDto.toMusicFolder() = MusicFolder(
    id = id,
    name = name,
)

/**
 * genre → Genre（iOS GenreParserDelegate 只取 name；Android 的 Genre 另带两个计数列）。
 *
 * Ampache 的 genre 有服务器 id，但 Android 领域模型以 **name 为标识**
 * （Subsonic 无流派 id 时定的口径，两后端共用同一套 UI 与本地查询），故 id 不落库。
 */
internal fun AmpacheGenreDto.toGenre() = Genre(
    name = name,
    albumCount = albumCount,
    songCount = songCount,
)

/** artist → Artist（iOS ArtistParserDelegate.swift:78-101） */
internal fun AmpacheArtistDto.toArtist() = Artist(
    id = id,
    name = name,
    coverArt = ampacheCoverArtKey(artworkUrl),
    albumCount = albumCount,
    songCount = songCount,
    duration = duration,
    starred = if (isFavorite) AMPACHE_FLAGGED_STARRED_AT else null,
    rating = rating,
)

/** album → Album（iOS AlbumParserDelegate.swift:88-140） */
internal fun AmpacheAlbumDto.toAlbum() = Album(
    id = id,
    name = name,
    // iOS 用 <artist> 子元素建关系；Android 的 Album.artist 是显示名，缺失归空串（同 Subsonic 侧）
    artist = artistName ?: "",
    artistId = artistId,
    coverArt = ampacheCoverArtKey(artworkUrl),
    songCount = songCount,
    duration = duration,
    // Ampache 的 <year> 缺省 0，Android 语义 null = 未知（0 会被列表按年份分段当成真实年份）
    year = year.takeIf { it > 0 },
    genre = genreName,
    // Ampache 无「加入库时间」元素，created 留空（Subsonic 侧取 <created>）
    created = null,
    starred = if (isFavorite) AMPACHE_FLAGGED_STARRED_AT else null,
    rating = rating,
)

/**
 * song → Song（iOS SongParserDelegate + PlayableParserDelegate 两层的合并产物）。
 *
 * 三处 Android 特有口径（本批定下的映射约定）：
 * - [Song.bitRate] 为 **kbps**（Subsonic 口径），Ampache `<bitrate>` 是 bps → 除 1000；
 * - [Song.streamUrl] 恒 null：**不用**服务器给的 `<url>`（其中含会话 ssid，会过期），
 *   播放 URL 由 MediaUrl 域按 `type=song` 现拼（= iOS generateUrl(forStreamingPlayableId:)）；
 * - [Song.path] / [Song.suffix] 取自 `<filename>`（iOS 不解析该元素，Android 的
 *   SongEntity 有 path 列、下载文件名推导要用扩展名）。
 */
internal fun AmpacheSongDto.toSong(accountId: String) = Song(
    id = id,
    title = title,
    artist = artistName ?: "",
    artistId = artistId,
    album = albumName ?: "",
    albumId = albumId,
    duration = duration,
    // Ampache 缺省 0，Android 语义 null = 无音轨号
    track = track.takeIf { it > 0 },
    year = year.takeIf { it > 0 },
    genre = genreName,
    coverArt = ampacheCoverArtKey(artworkUrl),
    size = size.takeIf { it > 0 },
    contentType = contentType,
    suffix = ampacheFileSuffix(filename),
    path = filename ?: "",
    // iOS AbstractPlayable.disk 同为 String，Android 的 disc 列是 Int
    discNumber = disk?.trim()?.toIntOrNull(),
    starred = if (isFavorite) AMPACHE_FLAGGED_STARRED_AT else null,
    albumArtistId = albumArtistId,
    // Ampache 无 Subsonic 的 <song type> 概念（music/podcast/audiobook），留空
    type = null,
    bitRate = (bitrate / 1000).takeIf { bitrate > 0 },
    streamUrl = null,
    rating = rating,
    accountId = accountId,
    replayGainTrackGain = replayGainTrackGain,
    replayGainTrackPeak = replayGainTrackPeak,
    replayGainAlbumGain = replayGainAlbumGain,
    replayGainAlbumPeak = replayGainAlbumPeak,
)

/**
 * 从 `<filename>` 取扩展名（无扩展名/无 filename → null）。
 * 先切末段路径再看点号，避免目录名里的点被当成扩展名。
 */
internal fun ampacheFileSuffix(filename: String?): String? {
    val lastSegment = filename?.trim()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        ?: return null
    val suffix = lastSegment.substringAfterLast('.', "")
    return suffix.takeIf { it.isNotEmpty() && it != lastSegment }
}

/**
 * playlist → Playlist（iOS PlaylistParserDelegate.swift:105-112：只消费 name + items）。
 *
 * 超集：owner / isPublic——Android 的 PlaylistEntity 有这两列（Subsonic 侧有值），
 * Ampache 的 `<type>` 值域为 private/public。
 * 刻意**不写** coverArt：iOS Ampache 侧的 playlist 解析器不取 `<art>`，
 * 播放列表封面在两端都由成员歌曲拼贴生成。
 */
internal fun AmpachePlaylistDto.toPlaylist() = Playlist(
    id = id,
    name = name,
    owner = owner,
    isPublic = type == PLAYLIST_TYPE_PUBLIC,
    songCount = songCount,
)

/** Ampache 播放列表的公开类型值（`playlist_create` 时 iOS 恒传 private，见 AmpacheApi） */
private const val PLAYLIST_TYPE_PUBLIC = "public"

/**
 * podcast → Podcast（iOS PodcastParserDelegate.swift:71-97 + performPostParseOperations）。
 *
 * [htmlDecoder] 生产侧传 [ampacheHtml2String]（Android framework 依赖），
 * 单测传 identity——见本文件 [ampacheHtml2String] 注释。
 * episodeCount 不写：由单集同步时按本地实际单集数重算（Store 侧语义）。
 */
internal fun AmpachePodcastDto.toPodcast(htmlDecoder: (String) -> String) = Podcast(
    id = id,
    title = htmlDecoder(title),
    depiction = htmlDecoder(description),
    coverArt = ampacheCoverArtKey(artworkUrl),
)

/**
 * podcast_episode → PodcastEpisode（iOS PodcastEpisodeParserDelegate.swift:86-135）。
 *
 * [podcastId] 由调用方传入：Ampache 的 `podcast_episodes` 响应里**不含所属播客 id**
 * （iOS 靠 delegate 构造时注入 podcast 对象，:39-46），故按请求的 filter id 回填。
 * streamId 恒 null：Ampache 没有 Subsonic 的 streamId 概念，播放直接用单集 id。
 */
internal fun AmpachePodcastEpisodeDto.toPodcastEpisode(
    podcastId: String,
    htmlDecoder: (String) -> String,
) = PodcastEpisode(
    id = id,
    title = htmlDecoder(title),
    depiction = description?.let(htmlDecoder),
    publishDate = publishDateMillis,
    status = PodcastEpisodeRemoteStatus.fromRaw(status.raw),
    streamId = null,
    duration = duration,
    coverArt = ampacheCoverArtKey(artworkUrl),
    podcastId = podcastId,
)

/** live_stream → Radio（iOS RadioParserDelegate.swift:69-76） */
internal fun AmpacheRadioDto.toRadio() = Radio(
    id = id,
    title = name,
    // 电台走原始 streamUrl 直连（Radio.toPlayable），缺省归空串与 Subsonic 侧一致
    streamUrl = url ?: "",
    siteUrl = siteUrl,
)
