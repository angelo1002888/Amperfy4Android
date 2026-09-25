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

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Ampache API 5 解析产物（DTO）与错误模型。
 *
 * 对应 iOS：`AmperfyKit/Api/Ampache/` 下各 `*ParserDelegate.swift` **直接写进 CoreData 实体**的字段集。
 * Android 侧解析层不碰数据库（Room 写入是 Batch 2 的映射层职责），故先落成一组不可变 DTO。
 *
 * 字段取值范围 = 两个约束的并集：
 * 1. iOS 各 delegate 实际消费的元素（逐个照抄，出处在各字段注释）；
 * 2. Android 既有 Room 实体（`data/local/db/entity/`）映射所需字段——Batch 2 直接吃这些 DTO，
 *    缺字段就得返工，故宁可多解析（多出的部分在注释里标「超集」并写明理由）。
 *
 * 刻意保留的原始形态（不在解析层做二次加工，留给 Batch 2 映射时处理）：
 * - 播客标题/描述为服务器下发的 **HTML 转义原文**（iOS 在 performPostParseOperations 里用
 *   `html2String` 反转义；Android 的等价物 `android.text.Html.fromHtml` 属 framework 类，
 *   放进解析层会让纯 JVM 单测失效，故延后到映射层）；
 * - `disk` 保留字符串（iOS `AbstractPlayable.disk` 同为 String），Room 的 disc 为 Int，
 *   由映射层 toIntOrNull 转换。
 */

// ==================== 通用容器 ====================

/**
 * 列表型响应的解析结果：条目 + 服务器给的 `<total_count>`。
 *
 * 超集：iOS 不解析 `total_count`（它用 parsedCount 判断分页是否到底）。Android 保留该值，
 * 供 Batch 2 的分页/进度显示使用；到底判定仍应按 `items.size < 每页请求量`（与 iOS 同）。
 */
data class AmpachePage<T>(
    val items: List<T>,
    val totalCount: Int? = null,
)

/**
 * 封面标识（对应 iOS `ArtworkRemoteInfo`）。
 *
 * Ampache 的 `<art>` 元素是一条完整的 image.php URL；iOS 从中反提取 object_id/object_type
 * 作为封面身份，用时再按当前会话 token 现拼 URL
 * （`AmpacheXmlServerApi.extractArtworkInfoFromURL`，AmpacheXmlServerApi.swift:124-131）。
 */
data class AmpacheArtworkInfo(
    val objectId: String,
    val objectType: String,
) {
    companion object {
        /**
         * 从 `<art>` URL 反提取封面标识；缺 object_id 或 object_type 时返回 null
         * （与 iOS 的 guard 语义一致）。
         */
        fun fromUrl(urlString: String): AmpacheArtworkInfo? {
            val url = urlString.trim().toHttpUrlOrNull() ?: return null
            val objectId = url.queryParameter("object_id") ?: return null
            val objectType = url.queryParameter("object_type") ?: return null
            return AmpacheArtworkInfo(objectId = objectId, objectType = objectType)
        }
    }
}

// ==================== 实体 DTO ====================

/**
 * catalog（对应 iOS CatalogParserDelegate → MusicFolder；Android MusicFolderEntity）。
 * iOS 只消费 id + name，此处同。
 */
data class AmpacheCatalogDto(
    val id: String,
    val name: String,
)

/**
 * genre（iOS GenreParserDelegate → Genre）。
 *
 * 超集：albumCount/artistCount/songCount——iOS 只取 name（它的 Genre 计数由本地关系推导），
 * Android `GenreEntity` 有 album_count/song_count 两列，故一并解析。
 */
data class AmpacheGenreDto(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val songCount: Int = 0,
)

/** artist（iOS ArtistParserDelegate → Artist） */
data class AmpacheArtistDto(
    val id: String,
    val name: String,
    /** `<albumcount>` → iOS remoteAlbumCount */
    val albumCount: Int = 0,
    /** 超集：`<songcount>`，iOS 不解析；Android 详情页 info 行可用 */
    val songCount: Int = 0,
    /** `<time>` 秒 → iOS remoteDuration */
    val duration: Int = 0,
    /** `<rating>`，空元素记 0 */
    val rating: Int = 0,
    /** `<flag>` == 1 → iOS isFavorite */
    val isFavorite: Boolean = false,
    val genreId: String? = null,
    val genreName: String? = null,
    /** `<art>` 原始 URL（Room cover_art 落的是标识而非 URL，映射层取 [artworkInfo]） */
    val artworkUrl: String? = null,
    val artworkInfo: AmpacheArtworkInfo? = null,
)

/** album（iOS AlbumParserDelegate → Album） */
data class AmpacheAlbumDto(
    val id: String,
    val name: String,
    val artistId: String? = null,
    val artistName: String? = null,
    /** `<year>` */
    val year: Int = 0,
    /** `<time>` 秒 → iOS remoteDuration */
    val duration: Int = 0,
    /** `<songcount>` → iOS remoteSongCount */
    val songCount: Int = 0,
    val rating: Int = 0,
    val isFavorite: Boolean = false,
    val genreId: String? = null,
    val genreName: String? = null,
    val artworkUrl: String? = null,
    val artworkInfo: AmpacheArtworkInfo? = null,
)

/**
 * song（iOS SongParserDelegate + PlayableParserDelegate 两层的合并产物）。
 *
 * Android `SongEntity` 需要的 album_artist_id / path / stream_url 等均在此覆盖。
 */
data class AmpacheSongDto(
    val id: String,
    /** `<title>`（Ampache 另有等值的 `<name>`，iOS 只取 title） */
    val title: String,
    val artistId: String? = null,
    val artistName: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    /** 超集：`<albumartist>`，iOS 不解析；Android SongEntity 有 album_artist_id 列 */
    val albumArtistId: String? = null,
    val albumArtistName: String? = null,
    val genreId: String? = null,
    val genreName: String? = null,
    /** `<track>` */
    val track: Int = 0,
    /** `<disk>`：iOS 同样按字符串保存 */
    val disk: String? = null,
    val year: Int = 0,
    /** `<time>` 秒 */
    val duration: Int = 0,
    /** `<size>` 字节 */
    val size: Long = 0,
    /** `<bitrate>`（Ampache 单位是 bps，如 192000） */
    val bitrate: Int = 0,
    /** `<mime>` → iOS contentType */
    val contentType: String? = null,
    /** `<url>`：服务器给的播放 URL（含 ssid，token 轮换后需替换） */
    val url: String? = null,
    /** 超集：`<filename>`，Android SongEntity 有 path 列 */
    val filename: String? = null,
    val rating: Int = 0,
    val isFavorite: Boolean = false,
    val artworkUrl: String? = null,
    val artworkInfo: AmpacheArtworkInfo? = null,
    val replayGainTrackGain: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumGain: Float? = null,
    val replayGainAlbumPeak: Float? = null,
)

/** playlist（iOS PlaylistParserDelegate → Playlist） */
data class AmpachePlaylistDto(
    val id: String,
    val name: String,
    /** 超集：`<owner>`，Android PlaylistEntity 有 owner 列 */
    val owner: String? = null,
    /** `<items>` → iOS remoteSongCount */
    val songCount: Int = 0,
    /** 超集：`<type>`（private/public），Android PlaylistEntity 有 is_public 列 */
    val type: String? = null,
    val rating: Int = 0,
    val isFavorite: Boolean = false,
    val artworkUrl: String? = null,
    val artworkInfo: AmpacheArtworkInfo? = null,
)

/**
 * podcast（iOS PodcastParserDelegate → Podcast）。
 *
 * [title] / [description] 为 HTML 转义原文（iOS titleRawParsed / depictionRawParsed），
 * 反转义在 Batch 2 映射层做，见本文件头注释。
 */
data class AmpachePodcastDto(
    val id: String,
    val title: String,
    val description: String = "",
    val rating: Int = 0,
    val artworkUrl: String? = null,
    val artworkInfo: AmpacheArtworkInfo? = null,
)

/** podcast_episode 的远端状态（值域与 raw 值逐一对齐 iOS `PodcastEpisodeRemoteStatus`，
 *  PodcastEpisode.swift:28-67；Android PodcastEpisodeEntity.status 存的就是该 raw） */
enum class AmpachePodcastEpisodeRemoteStatus(val raw: Int) {
    UNDEFINED(0),
    NEW(1),
    DOWNLOADING(2),
    COMPLETED(3),
    ERROR(4),
    DELETED(5),
    SKIPPED(6),
    ;

    companion object {
        /** 逐分支照抄 iOS create(from:)——注意 Ampache 的 "Pending"/"Completed" 是首字母大写的另一套 */
        fun from(text: String): AmpachePodcastEpisodeRemoteStatus = when (text) {
            "new" -> NEW
            "downloading" -> DOWNLOADING
            "completed" -> COMPLETED
            "error" -> ERROR
            "deleted" -> DELETED
            "skipped" -> SKIPPED
            "Pending" -> DOWNLOADING
            "Completed" -> COMPLETED
            else -> UNDEFINED
        }
    }
}

/**
 * podcast_episode（iOS PodcastEpisodeParserDelegate + PlayableParserDelegate）。
 *
 * [duration] / [size] 由两组元素先后写入，**后写覆盖先写**（与 iOS 的 delegate 回调顺序一致）：
 * `<filelength>`/`<filesize>` 在前、`<time>`/`<size>` 在后，故实际生效的是后者。
 */
data class AmpachePodcastEpisodeDto(
    val id: String,
    /** HTML 转义原文（iOS titleRawParsed） */
    val title: String,
    /** HTML 转义原文（iOS depictionRawParsed） */
    val description: String? = null,
    /** `<pubdate>` → epoch 毫秒（Android PodcastEpisodeEntity.publish_date 同单位） */
    val publishDateMillis: Long = 0,
    val status: AmpachePodcastEpisodeRemoteStatus = AmpachePodcastEpisodeRemoteStatus.UNDEFINED,
    val duration: Int = 0,
    val size: Long = 0,
    val bitrate: Int = 0,
    val contentType: String? = null,
    val url: String? = null,
    val rating: Int = 0,
    val isFavorite: Boolean = false,
    val artworkUrl: String? = null,
    val artworkInfo: AmpacheArtworkInfo? = null,
)

/** live_stream（iOS RadioParserDelegate → Radio） */
data class AmpacheRadioDto(
    val id: String,
    val name: String,
    /** `<url>`：电台直连流地址 */
    val url: String? = null,
    /** `<site_url>` → iOS siteURL，Android RadioEntity.home_page_url */
    val siteUrl: String? = null,
)

/**
 * IDs 汇总（iOS IDsParserDelegate → LibraryStorage.PrefetchIdContainer）。
 *
 * iOS 用它在解析实体前批量预取 CoreData 对象（性能优化）。Android 走 Room upsert，
 * **没有预取需求**——保留该解析器是为对齐 iOS 结构、并给 Batch 2 的「差集清理」
 * （服务器已删实体的本地清除）留一个现成的 id 快照来源。
 */
data class AmpacheIdsDto(
    val genreIds: Set<String> = emptySet(),
    val catalogIds: Set<String> = emptySet(),
    val artistIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val songIds: Set<String> = emptySet(),
    val podcastIds: Set<String> = emptySet(),
    val podcastEpisodeIds: Set<String> = emptySet(),
    val radioIds: Set<String> = emptySet(),
    val artworkInfos: Set<AmpacheArtworkInfo> = emptySet(),
)

/**
 * 写类 action 的成功响应：`<success code="1"><![CDATA[...]]></success>`。
 *
 * iOS 对写类响应只跑错误解析、不看成功体（AmpacheLibrarySyncer 各写方法只 parseForError）；
 * Android 把 code/message 解出来便于 EventLogger 记录，语义上不做任何判定。
 */
data class AmpacheSuccessDto(
    val code: Int = 0,
    val message: String = "",
)

// ==================== 错误模型 ====================

/**
 * Ampache 错误码（逐项对齐 iOS `AmpacheXmlServerApi.AmpacheError`，
 * AmpacheXmlServerApi.swift:62-86）。
 */
enum class AmpacheErrorCode(val code: Int) {
    /** 无错误（iOS `.empty`） */
    EMPTY(0),

    /** 4700 服务器未开 access_control */
    ACCESS_CONTROL_NOT_ENABLED(4700),

    /** 4701 会话无效 / 握手失败（临时性） */
    RECEIVED_INVALID_HANDSHAKE(4701),

    /** 4703 请求的方法不可用（服务器功能被关） */
    ACCESS_DENIED(4703),

    /** 4704 对象不存在 */
    NOT_FOUND(4704),

    /** 4705 请求了 API 未实现的方法（致命） */
    MISSING(4705),

    /** 4706 方法已下线（致命） */
    DEPRECIATED(4706),

    /** 4710 参数有误——勿原样重试 */
    BAD_REQUEST(4710),

    /** 4742 该用户无权访问对象/功能 */
    FAILED_ACCESS_CHECK(4742),
    ;

    /** 对齐 iOS shouldErrorBeDisplayedToUser（:79-81）：排除 empty 与 notFound */
    val shouldErrorBeDisplayedToUser: Boolean
        get() = this != EMPTY && this != NOT_FOUND

    /** 对齐 iOS isRemoteAvailable（:83-85）：仅 notFound 判为「远端不可用」以外的例外 */
    val isRemoteAvailable: Boolean
        get() = this != NOT_FOUND

    companion object {
        /** 未知码返回 null（iOS `AmpacheError(rawValue:)` 同样是可空构造） */
        fun fromCode(code: Int): AmpacheErrorCode? = entries.find { it.code == code }
    }
}

/**
 * `<error>` 节点的解析产物（对应 iOS `AmpacheResponseError`，AmpacheXmlServerApi.swift:29-36）。
 *
 * 超集：errorAction / errorType——iOS 只留 statusCode + message，
 * Ampache 官方错误结构四段俱全，一并解出便于事件日志定位。
 */
data class AmpacheResponseError(
    val statusCode: Int = 0,
    val errorAction: String? = null,
    val errorType: String? = null,
    val message: String = "",
) {
    val errorCode: AmpacheErrorCode?
        get() = AmpacheErrorCode.fromCode(statusCode)

    val shouldErrorBeDisplayedToUser: Boolean
        get() = errorCode?.shouldErrorBeDisplayedToUser ?: true

    val isRemoteAvailable: Boolean
        get() = errorCode?.isRemoteAvailable ?: true
}

/**
 * 服务器返回 `<error>` 节点时抛出（对应 iOS `ResponseError.createFromAmpacheError`）。
 *
 * [cleansedUrl] 为脱敏后的请求 URL（host/auth/ssid/user 已替换），可直接写进 EventLogger。
 */
class AmpacheApiException(
    val error: AmpacheResponseError,
    val cleansedUrl: String? = null,
) : Exception(
    "Ampache API error ${error.statusCode}" +
        (error.errorAction?.let { " on $it" } ?: "") +
        (if (error.message.isNotEmpty()) ": ${error.message}" else ""),
) {
    val statusCode: Int get() = error.statusCode
    val errorAction: String? get() = error.errorAction
    val errorType: String? get() = error.errorType
    val errorCode: AmpacheErrorCode? get() = error.errorCode
}

/** XML 无法解析（对应 iOS `ResponseError(type: .xml)`） */
class AmpacheXmlParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 握手失败/无凭证（对应 iOS `AuthenticationError.notAbleToLogin` 与 `BackendError.noCredentials`） */
class AmpacheAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
