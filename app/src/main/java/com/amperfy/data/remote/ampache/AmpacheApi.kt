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

package com.amperfy.data.remote.ampache

import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.remote.ampache.parser.AmpacheAlbumParser
import com.amperfy.data.remote.ampache.parser.AmpacheArtistParser
import com.amperfy.data.remote.ampache.parser.AmpacheCatalogParser
import com.amperfy.data.remote.ampache.parser.AmpacheGenreParser
import com.amperfy.data.remote.ampache.parser.AmpachePlaylistParser
import com.amperfy.data.remote.ampache.parser.AmpachePlaylistSongsParser
import com.amperfy.data.remote.ampache.parser.AmpachePodcastEpisodeParser
import com.amperfy.data.remote.ampache.parser.AmpachePodcastParser
import com.amperfy.data.remote.ampache.parser.AmpacheRadioParser
import com.amperfy.data.remote.ampache.parser.AmpacheSongParser
import com.amperfy.data.remote.ampache.parser.AmpacheSuccessParser
import com.amperfy.data.remote.ampache.parser.AmpacheXmlParser
import com.amperfy.data.remote.ampache.parser.AmpacheXmlParsing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Ampache API 5 端点全集 + URL 构建（对应 iOS `AmpacheXmlServerApi.swift` 的请求那一半；
 * 认证/会话那一半在 [AmpacheAuthSession]）。
 *
 * 与 Subsonic 栈的结构差异：
 * - **不走 Retrofit**：响应是 XML 而非 JSON，Retrofit+Gson 链路不可复用；本类直接用 OkHttp 发
 *   GET、交 SAX 解析器族（`parser/`）出 DTO，与 iOS「AmpacheXmlServerApi 发请求 +
 *   ParserDelegate 解析」的两段结构 1:1 对应；
 * - **不用 Base URL 拦截器**：每请求从 [credentialsProvider] 现取该账户的 active server URL
 *   拼**绝对 URL**（对齐 iOS createApiUrl 每请求现建，AmpacheXmlServerApi.swift:146-153）；
 * - **每请求只带 `auth=<token>`**：签名式认证参数（u/t/s/v/c）在 Ampache 不存在。
 *
 * 刻意的 Kotlin 侧命名偏差：iOS 靠 Swift 的实参标签重载出三组同名方法
 * （`requestRate(songId:)` / `(albumId:)` / `(artistId:)`），JVM 无此能力，
 * 故拆成 requestRateSong / requestRateAlbum / requestRateArtist（favorite 同理）。
 *
 * 返回值：iOS 各 request 方法回吐原始 Data、由 AmpacheLibrarySyncer 挑 delegate 解析；
 * 由于 action 与解析器是 1:1 固定搭配（见 AmpacheLibrarySyncer 全文），此处直接返回解析后的
 * DTO，让 Batch 2 的 Repository 只剩「DTO → Room 实体」映射。
 */
class AmpacheApi(
    private val okHttpClient: OkHttpClient,
    private val credentialsProvider: () -> LoginCredentials?,
    private val authSession: AmpacheAuthSession,
) {

    // ==================== 库同步（只读） ====================

    /** catalogs：音乐目录（Android 的 Directories 一级，iOS requestCatalogs，:284-290） */
    suspend fun requestCatalogs(): AmpachePage<AmpacheCatalogDto> =
        requestPage(AmpacheCatalogParser()) { addQueryParameter("action", "catalogs") }

    /** genres：流派全量（iOS requestGenres，:292-298） */
    suspend fun requestGenres(): AmpachePage<AmpacheGenreDto> =
        requestPage(AmpacheGenreParser()) { addQueryParameter("action", "genres") }

    /**
     * artists：艺术家分页全量（iOS requestArtists，:300-313）。
     * offset 按握手响应里的 artistCount 钳制（见 [clampOffset]）。
     */
    suspend fun requestArtists(
        startIndex: Int,
        pollCount: Int = MAX_ITEM_COUNT_TO_POLL_AT_ONCE,
    ): AmpachePage<AmpacheArtistDto> = requestPage(AmpacheArtistParser()) { auth ->
        addQueryParameter("action", "artists")
        addQueryParameter("offset", clampOffset(startIndex, auth.artistCount).toString())
        addQueryParameter("limit", pollCount.toString())
    }

    /** albums：专辑分页全量（iOS requestAlbums，:381-394） */
    suspend fun requestAlbums(
        startIndex: Int,
        pollCount: Int = MAX_ITEM_COUNT_TO_POLL_AT_ONCE,
    ): AmpachePage<AmpacheAlbumDto> = requestPage(AmpacheAlbumParser()) { auth ->
        addQueryParameter("action", "albums")
        addQueryParameter("offset", clampOffset(startIndex, auth.albumCount).toString())
        addQueryParameter("limit", pollCount.toString())
    }

    /** artist：单个艺术家详情（iOS requestArtistInfo，:327-334） */
    suspend fun requestArtistInfo(id: String): AmpachePage<AmpacheArtistDto> =
        requestPage(AmpacheArtistParser()) {
            addQueryParameter("action", "artist")
            addQueryParameter("filter", id)
        }

    /** artist_albums：艺术家的专辑（iOS requestArtistAlbums，:336-343） */
    suspend fun requestArtistAlbums(id: String): AmpachePage<AmpacheAlbumDto> =
        requestPage(AmpacheAlbumParser()) {
            addQueryParameter("action", "artist_albums")
            addQueryParameter("filter", id)
        }

    /** artist_songs：艺术家的歌曲（iOS requestArtistSongs，:345-352） */
    suspend fun requestArtistSongs(id: String): AmpachePage<AmpacheSongDto> =
        requestPage(AmpacheSongParser()) {
            addQueryParameter("action", "artist_songs")
            addQueryParameter("filter", id)
        }

    /** album：单个专辑详情（iOS requestAlbumInfo，:354-361） */
    suspend fun requestAlbumInfo(id: String): AmpachePage<AmpacheAlbumDto> =
        requestPage(AmpacheAlbumParser()) {
            addQueryParameter("action", "album")
            addQueryParameter("filter", id)
        }

    /** album_songs：专辑曲目（iOS requestAlbumSongs，:363-370） */
    suspend fun requestAlbumSongs(id: String): AmpachePage<AmpacheSongDto> =
        requestPage(AmpacheSongParser()) {
            addQueryParameter("action", "album_songs")
            addQueryParameter("filter", id)
        }

    /** song：单曲详情（iOS requestSongInfo，:372-379） */
    suspend fun requestSongInfo(id: String): AmpachePage<AmpacheSongDto> =
        requestPage(AmpacheSongParser()) {
            addQueryParameter("action", "song")
            addQueryParameter("filter", id)
        }

    /** playlist_generate(mode=random)：随机歌曲（iOS requestRandomSongs，:396-405） */
    suspend fun requestRandomSongs(count: Int): AmpachePage<AmpacheSongDto> =
        requestPage(AmpacheSongParser()) {
            addQueryParameter("action", "playlist_generate")
            addQueryParameter("mode", "random")
            addQueryParameter("format", "song")
            addQueryParameter("limit", count.toString())
        }

    /** stats(type=album, filter=newest)：最新专辑（iOS requestNewestAlbums，:452-462） */
    suspend fun requestNewestAlbums(offset: Int, count: Int): AmpachePage<AmpacheAlbumDto> =
        requestStatsAlbums(filter = "newest", offset = offset, count = count)

    /** stats(type=album, filter=recent)：最近播放专辑（iOS requestRecentAlbums，:464-474） */
    suspend fun requestRecentAlbums(offset: Int, count: Int): AmpachePage<AmpacheAlbumDto> =
        requestStatsAlbums(filter = "recent", offset = offset, count = count)

    private suspend fun requestStatsAlbums(
        filter: String,
        offset: Int,
        count: Int,
    ): AmpachePage<AmpacheAlbumDto> = requestPage(AmpacheAlbumParser()) {
        // 参数顺序照 iOS：action → type → filter → limit → offset
        addQueryParameter("action", "stats")
        addQueryParameter("type", "album")
        addQueryParameter("filter", filter)
        addQueryParameter("limit", count.toString())
        addQueryParameter("offset", offset.toString())
    }

    // ==================== advanced_search（三种固定用法） ====================

    /**
     * advanced_search：某 catalog 下的艺术家（iOS requestArtistWithinCatalog，:315-325）。
     * Directories 的第二层就靠它（Ampache 无 by-folder 端点）。
     */
    suspend fun requestArtistWithinCatalog(id: String): AmpachePage<AmpacheArtistDto> =
        requestPage(AmpacheArtistParser()) {
            addQueryParameter("action", "advanced_search")
            addQueryParameter("rule_1", "catalog")
            addQueryParameter("rule_1_operator", "0")
            // iOS 用 `Int(id) ?? 0`——非数字 catalog id 退化成 0
            addQueryParameter("rule_1_input", (id.toIntOrNull() ?: 0).toString())
            addQueryParameter("type", "artist")
        }

    /** advanced_search(favorite, type=artist)：收藏艺术家（iOS requestFavoriteArtists，:416-426） */
    suspend fun requestFavoriteArtists(): AmpachePage<AmpacheArtistDto> =
        requestPage(AmpacheArtistParser()) { favoriteSearchParams("artist") }

    /** advanced_search(favorite, type=album)：收藏专辑（iOS requestFavoriteAlbums，:428-438） */
    suspend fun requestFavoriteAlbums(): AmpachePage<AmpacheAlbumDto> =
        requestPage(AmpacheAlbumParser()) { favoriteSearchParams("album") }

    /** advanced_search(favorite, type=song)：收藏歌曲（iOS requestFavoriteSongs，:440-450） */
    suspend fun requestFavoriteSongs(): AmpachePage<AmpacheSongDto> =
        requestPage(AmpacheSongParser()) { favoriteSearchParams("song") }

    private fun HttpUrl.Builder.favoriteSearchParams(type: String) {
        addQueryParameter("action", "advanced_search")
        addQueryParameter("rule_1", "favorite")
        addQueryParameter("rule_1_operator", "0")
        // 空 input 即「是收藏」，iOS 同样显式传空串
        addQueryParameter("rule_1_input", "")
        addQueryParameter("type", type)
    }

    // ==================== 播放列表（读 + 写） ====================

    /** playlists：播放列表全量（iOS requestPlaylists，:476-482） */
    suspend fun requestPlaylists(): AmpachePage<AmpachePlaylistDto> =
        requestPage(AmpachePlaylistParser()) { addQueryParameter("action", "playlists") }

    /** playlist：单个播放列表（iOS requestPlaylist，:484-491；也用于校验 id 是否还在） */
    suspend fun requestPlaylist(id: String): AmpachePage<AmpachePlaylistDto> =
        requestPage(AmpachePlaylistParser()) {
            addQueryParameter("action", "playlist")
            addQueryParameter("filter", id)
        }

    /**
     * playlist_songs：播放列表曲目（iOS requestPlaylistSongs，:493-500）。
     * **响应顺序即列表顺序**；时长合计可直接对结果求和（见 AmpachePlaylistSongsParser）。
     */
    suspend fun requestPlaylistSongs(id: String): AmpachePage<AmpacheSongDto> =
        requestPage(AmpachePlaylistSongsParser()) {
            addQueryParameter("action", "playlist_songs")
            addQueryParameter("filter", id)
        }

    /**
     * playlist_create：新建播放列表（iOS requestPlaylistCreate，:502-510）。
     * 响应回吐新建的 playlist，其 id 即服务器分配的 id。type 恒 private（与 iOS 同）。
     */
    suspend fun requestPlaylistCreate(name: String): AmpachePage<AmpachePlaylistDto> =
        requestPage(AmpachePlaylistParser()) {
            addQueryParameter("action", "playlist_create")
            addQueryParameter("name", name)
            addQueryParameter("type", "private")
        }

    /** playlist_delete（iOS requestPlaylistDelete，:512-519） */
    suspend fun requestPlaylistDelete(id: String): AmpacheSuccessDto? = requestSuccess {
        addQueryParameter("action", "playlist_delete")
        addQueryParameter("filter", id)
    }

    /** playlist_add_song（iOS requestPlaylistAddSong，:521-533） */
    suspend fun requestPlaylistAddSong(playlistId: String, songId: String): AmpacheSuccessDto? =
        requestSuccess {
            addQueryParameter("action", "playlist_add_song")
            addQueryParameter("filter", playlistId)
            addQueryParameter("song", songId)
        }

    /**
     * playlist_remove_song：按位置删一首（iOS requestPlaylistDeleteItem，:535-543）。
     * **track 是 1-based**：传入的 [index] 为 0-based 位置，这里 +1。
     */
    suspend fun requestPlaylistDeleteItem(id: String, index: Int): AmpacheSuccessDto? =
        requestSuccess {
            addQueryParameter("action", "playlist_remove_song")
            addQueryParameter("filter", id)
            addQueryParameter("track", (index + 1).toString())
        }

    /** playlist_edit（仅改名，iOS requestPlaylistEditOnlyName，:545-557） */
    suspend fun requestPlaylistEditOnlyName(id: String, name: String): AmpacheSuccessDto? =
        requestSuccess {
            addQueryParameter("action", "playlist_edit")
            addQueryParameter("filter", id)
            addQueryParameter("name", name)
        }

    /**
     * playlist_edit（整表重排/替换，iOS requestPlaylistEdit，:559-571）：
     * `items` = 按新顺序的歌曲 id 逗号串，`tracks` = `1..n` 自然序列。
     *
     * 空列表防御：iOS 的 `Array(1...0)` 在 songsIds 为空时会崩，这里传空串
     * （Android 侧刻意加固；调用方本就不该拿空列表调它）。
     */
    suspend fun requestPlaylistEdit(id: String, songIds: List<String>): AmpacheSuccessDto? =
        requestSuccess {
            addQueryParameter("action", "playlist_edit")
            addQueryParameter("filter", id)
            addQueryParameter("items", songIds.joinToString(","))
            addQueryParameter(
                "tracks",
                if (songIds.isEmpty()) "" else (1..songIds.size).joinToString(","),
            )
        }

    // ==================== 播客 / 电台 ====================

    /** live_streams：电台（iOS requestRadios，:573-579） */
    suspend fun requestRadios(): AmpachePage<AmpacheRadioDto> =
        requestPage(AmpacheRadioParser()) { addQueryParameter("action", "live_streams") }

    /** podcasts：播客（iOS requestPodcasts，:581-587） */
    suspend fun requestPodcasts(): AmpachePage<AmpachePodcastDto> =
        requestPage(AmpachePodcastParser()) { addQueryParameter("action", "podcasts") }

    /** podcast_episodes：某播客的单集（iOS requestPodcastEpisodes，:589-603；limit 可选） */
    suspend fun requestPodcastEpisodes(
        id: String,
        limit: Int? = null,
    ): AmpachePage<AmpachePodcastEpisodeDto> = requestPage(AmpachePodcastEpisodeParser()) {
        addQueryParameter("action", "podcast_episodes")
        addQueryParameter("filter", id)
        limit?.let { addQueryParameter("limit", it.toString()) }
    }

    /** podcast_episode_delete：服务器侧删单集（iOS requestPodcastEpisodeDelete，:407-414） */
    suspend fun requestPodcastEpisodeDelete(id: String): AmpacheSuccessDto? = requestSuccess {
        addQueryParameter("action", "podcast_episode_delete")
        addQueryParameter("filter", id)
    }

    // ==================== 收藏 / 评分 / Scrobble（写） ====================

    /**
     * record_play：听毕上报（iOS requestRecordPlay，:605-618）。
     *
     * **Ampache 没有 nowPlaying 概念**——Subsonic 的 scrobble(submission=false) 在此无对应物，
     * Scrobble 只有这一步。[playedAtMillis] 供离线补传历史播放。
     */
    suspend fun requestRecordPlay(
        songId: String,
        playedAtMillis: Long? = null,
    ): AmpacheSuccessDto? = requestSuccess {
        addQueryParameter("action", "record_play")
        credentialsProvider()?.username?.let { addQueryParameter("user", it) }
        playedAtMillis?.let { addQueryParameter("date", (it / 1000L).toString()) }
        addQueryParameter("id", songId)
    }

    /** rate(type=song)（iOS requestRate(songId:rating:)，:620-629） */
    suspend fun requestRateSong(songId: String, rating: Int): AmpacheSuccessDto? =
        requestRate("song", songId, rating)

    /** rate(type=album)（iOS requestRate(albumId:rating:)，:631-640） */
    suspend fun requestRateAlbum(albumId: String, rating: Int): AmpacheSuccessDto? =
        requestRate("album", albumId, rating)

    /** rate(type=artist)（iOS requestRate(artistId:rating:)，:642-651） */
    suspend fun requestRateArtist(artistId: String, rating: Int): AmpacheSuccessDto? =
        requestRate("artist", artistId, rating)

    private suspend fun requestRate(type: String, id: String, rating: Int): AmpacheSuccessDto? =
        requestSuccess {
            addQueryParameter("action", "rate")
            addQueryParameter("type", type)
            addQueryParameter("id", id)
            addQueryParameter("rating", rating.toString())
        }

    /** flag(type=song)（iOS requestSetFavorite(songId:isFavorite:)，:653-662） */
    suspend fun requestSetFavoriteSong(songId: String, isFavorite: Boolean): AmpacheSuccessDto? =
        requestSetFavorite("song", songId, isFavorite)

    /** flag(type=album)（iOS requestSetFavorite(albumId:isFavorite:)，:664-677） */
    suspend fun requestSetFavoriteAlbum(albumId: String, isFavorite: Boolean): AmpacheSuccessDto? =
        requestSetFavorite("album", albumId, isFavorite)

    /** flag(type=artist)（iOS requestSetFavorite(artistId:isFavorite:)，:679-692） */
    suspend fun requestSetFavoriteArtist(
        artistId: String,
        isFavorite: Boolean,
    ): AmpacheSuccessDto? = requestSetFavorite("artist", artistId, isFavorite)

    private suspend fun requestSetFavorite(
        type: String,
        id: String,
        isFavorite: Boolean,
    ): AmpacheSuccessDto? = requestSuccess {
        addQueryParameter("action", "flag")
        addQueryParameter("type", type)
        addQueryParameter("id", id)
        addQueryParameter("flag", if (isFavorite) "1" else "0")
    }

    // ==================== 搜索（三端点分立） ====================

    /** artists?filter=…&limit=40：艺术家搜索（iOS requestSearchArtists，:694-702） */
    suspend fun requestSearchArtists(searchText: String): AmpachePage<AmpacheArtistDto> =
        requestPage(AmpacheArtistParser()) {
            addQueryParameter("action", "artists")
            addQueryParameter("filter", searchText)
            addQueryParameter("limit", SEARCH_RESULT_LIMIT.toString())
        }

    /** albums?filter=…&limit=40：专辑搜索（iOS requestSearchAlbums，:704-712） */
    suspend fun requestSearchAlbums(searchText: String): AmpachePage<AmpacheAlbumDto> =
        requestPage(AmpacheAlbumParser()) {
            addQueryParameter("action", "albums")
            addQueryParameter("filter", searchText)
            addQueryParameter("limit", SEARCH_RESULT_LIMIT.toString())
        }

    /** search_songs?filter=…&limit=40：歌曲搜索（iOS requestSearchSongs，:714-722） */
    suspend fun requestSearchSongs(searchText: String): AmpachePage<AmpacheSongDto> =
        requestPage(AmpacheSongParser()) {
            addQueryParameter("action", "search_songs")
            addQueryParameter("filter", searchText)
            addQueryParameter("limit", SEARCH_RESULT_LIMIT.toString())
        }

    // ==================== 媒体 URL 构建 ====================

    /**
     * 流媒体 URL（iOS generateUrlForStreamingPlayable，:769-800）。
     *
     * @param isSong true → `type=song`，false → `type=podcast_episode`
     * @param format [FORMAT_MP3] / [FORMAT_RAW]；null = serverConfig 档（不传该参数）
     * @param maxBitrateKbps null = noLimit 档（不传该参数）
     *
     * `length=1` 恒传（iOS :797）。API 层刻意不依赖 settings 类型（format/bitrate 由调用方翻译）。
     *
     * **当前无调用方**：Android 的 MediaUrl 域按「显式传入的 baseUrl + 内存 token」自建同名 URL
     * （`MediaUrlRepository` 的三个 URL 方法是非 suspend 的冻结签名，用不了本方法的
     * `reauthenticate()`）。保留本方法与下面两个 URL 方法，是为与 iOS
     * `AmpacheXmlServerApi` 的三个 generateUrl 保持 API 层 1:1 对齐。
     */
    suspend fun generateUrlForStreamingPlayable(
        isSong: Boolean,
        id: String,
        format: String? = null,
        maxBitrateKbps: Int? = null,
    ): String {
        val auth = authSession.reauthenticate()
        val builder = AmpacheUrl.authApiUrlBuilder(requireServerUrl(), auth.token)
        builder.addQueryParameter("action", "stream")
        builder.addQueryParameter("type", playableType(isSong))
        builder.addQueryParameter("id", id)
        format?.let { builder.addQueryParameter("format", it) }
        maxBitrateKbps?.let { builder.addQueryParameter("bitrate", it.toString()) }
        builder.addQueryParameter("length", "1")
        return builder.build().toString()
    }

    /**
     * 下载 URL（iOS generateUrlForDownloadingPlayable，:753-767）。
     * format 按 cacheTranscodingFormatPreference：mp3 档传 [FORMAT_MP3]，其余一律 [FORMAT_RAW]
     * （iOS 的 default 分支，**没有** serverConfig 的「不传」形态）。
     */
    suspend fun generateUrlForDownloadingPlayable(
        isSong: Boolean,
        id: String,
        format: String = FORMAT_RAW,
    ): String {
        val auth = authSession.reauthenticate()
        val builder = AmpacheUrl.authApiUrlBuilder(requireServerUrl(), auth.token)
        builder.addQueryParameter("action", "download")
        builder.addQueryParameter("type", playableType(isSong))
        builder.addQueryParameter("id", id)
        builder.addQueryParameter("format", format)
        return builder.build().toString()
    }

    /**
     * 封面 URL（iOS generateUrlForArtwork，:802-818）：
     * `<server>/image.php?auth=<token>&object_id=…&object_type=…`
     * ——**不在 `/server/` 路径下**，与 API 端点不同源。
     *
     * token 会随会话轮换，故这类 URL 每次现拼；Coil 缓存键须剔除 auth
     * （`utils/ArtworkCacheKey.kt` 已扩 image.php 形态，与 Subsonic 侧剔除 t/s 同理）。
     */
    suspend fun generateUrlForArtwork(artworkInfo: AmpacheArtworkInfo): String {
        val auth = authSession.reauthenticate()
        return AmpacheUrl.rootUrlBuilder(requireServerUrl(), AmpacheUrl.ARTWORK_PATH)
            .addQueryParameter("auth", auth.token)
            .addQueryParameter("object_id", artworkInfo.objectId)
            .addQueryParameter("object_type", artworkInfo.objectType)
            .build()
            .toString()
    }

    /**
     * 从实体 XML 里的 `<art>` URL 反提取封面标识
     * （iOS extractArtworkInfoFromURL，:124-131）。
     */
    fun extractArtworkInfoFromUrl(urlString: String): AmpacheArtworkInfo? =
        AmpacheArtworkInfo.fromUrl(urlString)

    /** 日志脱敏（iOS cleanse，:220-244）：host → SERVERURL，auth/ssid/user 三参数打码 */
    fun cleanse(urlString: String?): String = AmpacheUrl.cleanse(urlString)

    // ==================== 内部：请求执行 ====================

    /**
     * 发一次请求并解析：会话保障 → 拼 URL → GET → 先判错误、再出 DTO。
     *
     * 错误判定对齐 iOS `AmpacheLibrarySyncer.parse(response:delegate:)`（:1396-1420）：
     * 只有 `shouldErrorBeDisplayedToUser`（即非 empty、非 4704）或显式要求时才抛；
     * **4704 Not Found 默认不抛**——继续返回空解析结果，与 iOS 行为一致。
     *
     * 与 iOS 的一点差异：iOS 对每个响应解析两遍（先 AmpacheXmlParser 查错，再业务 delegate），
     * 此处只解析一遍——错误分支已下沉到解析器基类，任何解析器解完都带 `error`，语义等价、少一遍开销。
     */
    private suspend fun <T> requestParsed(
        parser: AmpacheXmlParser<T>,
        throwForNotFoundErrors: Boolean = false,
        configure: HttpUrl.Builder.(AmpacheAuthHandshake) -> Unit,
    ): T {
        val auth = authSession.reauthenticate()
        val builder = AmpacheUrl.authApiUrlBuilder(requireServerUrl(), auth.token)
        builder.configure(auth)
        val url = builder.build().toString()

        val body = ampacheHttpGetString(okHttpClient, url)
        val result = AmpacheXmlParsing.parse(body, parser)
        parser.error?.let { error ->
            if (error.shouldErrorBeDisplayedToUser || throwForNotFoundErrors) {
                throw AmpacheApiException(error, AmpacheUrl.cleanse(url))
            }
        }
        return result
    }

    private suspend fun <E> requestPage(
        parser: AmpacheXmlParser<List<E>>,
        configure: HttpUrl.Builder.(AmpacheAuthHandshake) -> Unit,
    ): AmpachePage<E> {
        val items = requestParsed(parser = parser, configure = configure)
        return AmpachePage(items = items, totalCount = parser.totalCount)
    }

    private suspend fun requestSuccess(
        configure: HttpUrl.Builder.(AmpacheAuthHandshake) -> Unit,
    ): AmpacheSuccessDto? = requestParsed(parser = AmpacheSuccessParser(), configure = configure)

    private fun requireServerUrl(): String =
        credentialsProvider()?.serverUrl
            ?: throw AmpacheAuthenticationException("Ampache: 无可用凭证")

    private fun playableType(isSong: Boolean): String =
        if (isSong) TYPE_SONG else TYPE_PODCAST_EPISODE

    /**
     * 分页 offset 钳制（iOS :306/:387）：`offset = min(startIndex, count − 1)`，
     * count 取握手响应里的实体计数。
     *
     * 额外下限 0（Android 加固）：count 为 0 时 iOS 会算出 -1 并原样发出，
     * 这里收敛到 0，避免服务器对负 offset 的未定义行为。
     */
    private fun clampOffset(startIndex: Int, count: Int): Int =
        minOf(startIndex, count - 1).coerceAtLeast(0)

    companion object {
        /** 单页拉取上限（iOS maxItemCountToPollAtOnce，:88） */
        const val MAX_ITEM_COUNT_TO_POLL_AT_ONCE: Int = 500

        /** 搜索三端点各自的条数上限（iOS 三处硬编码 40） */
        const val SEARCH_RESULT_LIMIT: Int = 40

        const val TYPE_SONG: String = "song"
        const val TYPE_PODCAST_EPISODE: String = "podcast_episode"
        const val FORMAT_MP3: String = "mp3"
        const val FORMAT_RAW: String = "raw"
    }
}

/**
 * Ampache URL 构建（对应 iOS createApiUrl / createAuthApiUrlComponent / cleanse，
 * AmpacheXmlServerApi.swift:89、146-161、220-244）。
 *
 * 内部件：[AmpacheApi] 与 [AmpacheAuthSession] 共用（握手 URL 不带 auth，其余都带）。
 */
internal object AmpacheUrl {

    /** API 端点路径（iOS apiPathComponents，:89） */
    const val API_PATH: String = "server/xml.server.php"

    /** 封面端点路径——服务器根下，不在 API 路径里（iOS :806） */
    const val ARTWORK_PATH: String = "image.php"

    /** `<server>/server/xml.server.php` 的 Builder（无 auth，握手用） */
    fun apiUrlBuilder(serverUrl: String): HttpUrl.Builder = rootUrlBuilder(serverUrl, API_PATH)

    /** `<server>/server/xml.server.php?auth=<token>`（iOS createAuthApiUrlComponent，:155-161） */
    fun authApiUrlBuilder(serverUrl: String, token: String): HttpUrl.Builder =
        apiUrlBuilder(serverUrl).addQueryParameter("auth", token)

    /**
     * 该 URL 是否为「带会话 token 的 Ampache URL」（API 端点或封面端点）。
     *
     * 用途（Batch 2）：ExoPlayer / Coil 两条加载链在装载时刻判断要不要换新 token
     * （[com.amperfy.core.AmpacheUrlAuthRefresher]）。判定只看路径尾段，不看 host
     * ——host 归属由调用方按账户 serverUrl 匹配。
     */
    fun isApiOrArtworkUrl(urlString: String?): Boolean {
        val url = urlString?.toHttpUrlOrNull() ?: return false
        val path = url.encodedPath
        return path.endsWith("/$API_PATH") || path.endsWith("/$ARTWORK_PATH")
    }

    /**
     * 剔除会话相关查询参数（`auth`/`ssid`）——用于 Coil 缓存键规范化：
     * token 每次握手都变，不剔则同一封面每次都是新键、缓存全废
     * （与 Subsonic 侧 `SubsonicAuthParams.stripAuthQueryParams` 同定位）。
     */
    fun stripAuthQueryParams(urlString: String): String {
        val url = urlString.toHttpUrlOrNull() ?: return urlString
        return url.newBuilder()
            .removeAllQueryParameters("auth")
            .removeAllQueryParameters("ssid")
            .build()
            .toString()
    }

    /**
     * 「无 token」的流媒体 URL 模板（播放状态恢复用，Batch 2）。
     *
     * 用在拿不到会话的场合（`RoomPlaybackStateStore` 在 Room 边界内）：先拼出形态正确、
     * `auth` 为空的 URL，装载时刻由 `AmpacheUrlAuthRefresher` 补上新鲜 token。
     * 刻意不带 format/bitrate——那两项要读 settings，本函数只负责形态。
     */
    fun streamUrlWithoutToken(serverUrl: String, id: String, isSong: Boolean): String = try {
        authApiUrlBuilder(serverUrl, "")
            .addQueryParameter("action", "stream")
            .addQueryParameter("type", if (isSong) "song" else "podcast_episode")
            .addQueryParameter("id", id)
            .addQueryParameter("length", "1")
            .build()
            .toString()
    } catch (e: Exception) {
        ""
    }

    /** 「无 token」的封面 URL 模板（用途同 [streamUrlWithoutToken]）；标识非法时返回 null */
    fun artworkUrlWithoutToken(serverUrl: String, coverArtKey: String): String? {
        val info = AmpacheArtworkInfo.fromUrl(coverArtKey) ?: return null
        return try {
            rootUrlBuilder(serverUrl, ARTWORK_PATH)
                .addQueryParameter("auth", "")
                .addQueryParameter("object_id", info.objectId)
                .addQueryParameter("object_type", info.objectType)
                .build()
                .toString()
        } catch (e: Exception) {
            null
        }
    }

    /** 用新 token 替换（或补上）URL 里的 `auth` 参数；URL 非法时原样返回 */
    fun withAuthToken(urlString: String, token: String): String {
        val url = urlString.toHttpUrlOrNull() ?: return urlString
        return url.newBuilder()
            .removeAllQueryParameters("auth")
            .addQueryParameter("auth", token)
            .build()
            .toString()
    }

    /** `<server>/<path>` 的 Builder；服务器 URL 可带子路径（如 https://host/ampache） */
    fun rootUrlBuilder(serverUrl: String, path: String): HttpUrl.Builder {
        val base = serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Ampache: 服务器 URL 无效")
        return base.newBuilder().addPathSegments(path)
    }

    /**
     * 日志脱敏（iOS cleanse，:220-244）：host 换成 SERVERURL、去端口，
     * 查询参数里的 `auth`/`ssid`/`user` 分别换成 AUTH/SSID/USER，其余原样。
     *
     * 只用于写日志/事件（EventLogger），**不可**拿去发请求；参数值不做百分号编码
     * （日志可读性优先，与 iOS 的 CleansedURL 定位一致）。
     */
    fun cleanse(urlString: String?): String {
        val parsed = urlString?.toHttpUrlOrNull() ?: return ""
        val sb = StringBuilder()
        sb.append(parsed.scheme).append("://").append("SERVERURL").append(parsed.encodedPath)
        val size = parsed.querySize
        if (size > 0) {
            sb.append('?')
            for (i in 0 until size) {
                if (i > 0) sb.append('&')
                val name = parsed.queryParameterName(i)
                val value = when (name) {
                    "auth" -> "AUTH"
                    "ssid" -> "SSID"
                    "user" -> "USER"
                    else -> parsed.queryParameterValue(i) ?: ""
                }
                sb.append(name).append('=').append(value)
            }
        }
        return sb.toString()
    }
}

/**
 * 发一次 GET 取字符串响应（对应 iOS 的 Alamofire `AF.request(url).validate().responseData`）。
 *
 * OkHttp 的同步 execute 放在 Dispatchers.IO；非 2xx 按错误处理（对齐 iOS 的 `.validate()`）。
 * 异常信息里只放脱敏 URL，避免 token/用户名进日志。
 */
internal suspend fun ampacheHttpGetString(client: OkHttpClient, url: String): String =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "Ampache HTTP ${response.code} <${AmpacheUrl.cleanse(url)}>",
                )
            }
            body
        }
    }
