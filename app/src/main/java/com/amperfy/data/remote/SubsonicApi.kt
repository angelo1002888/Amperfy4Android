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

package com.amperfy.data.remote

import com.amperfy.data.model.BackendApiType
import com.amperfy.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Subsonic API接口定义
 * 对应iOS的SubsonicServerApi
 *
 * 函数命名与iOS保持一致: requestXxx() / generateUrl()
 * 文档: http://www.subsonic.org/pages/api.jsp
 *
 * 认证参数（u / v / c / t+s 或 legacy 明文 p）**不在端点签名里**——由每账户挂载的
 * [SubsonicAuthInterceptor] 每请求统一追加（对应 iOS 每次构造 URL 都过
 * createAuthApiUrlComponent，SubsonicServerApi.swift:213-240）；客户端 API 版本随认证方式
 * 取 1.13.0 / 1.11.0，故这里不再声明 v=1.16.1。
 */
interface SubsonicApi {

    // ==================== 系统相关 ====================

    /**
     * 测试连接和认证
     * 对应iOS: isAuthenticationValid()
     */
    @GET("rest/ping")
    suspend fun ping(
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<PingResponse>>

    // ==================== 浏览相关 ====================

    /**
     * 获取流派列表
     * 对应iOS: requestGenres()
     */
    @GET("rest/getGenres")
    suspend fun requestGenres(
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<GenresResponse>>

    /**
     * 获取所有艺术家
     * 对应iOS: requestArtists()
     */
    @GET("rest/getArtists")
    suspend fun requestArtists(
        @Query("musicFolderId") musicFolderId: String? = null,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<ArtistsResponse>>

    /**
     * 获取艺术家详情
     * 对应iOS: requestArtist(id:)
     */
    @GET("rest/getArtist")
    suspend fun requestArtist(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<ArtistDetailResponse>>

    /**
     * 获取专辑详情
     * 对应iOS: requestAlbum(id:)
     */
    @GET("rest/getAlbum")
    suspend fun requestAlbum(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<AlbumDetailResponse>>

    /**
     * 获取歌曲详情
     * 对应iOS: requestSongInfo(id:)
     */
    @GET("rest/getSong")
    suspend fun requestSongInfo(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<SongResponse>>

    // ==================== 专辑列表 ====================

    /**
     * 获取最新专辑
     * 对应iOS: requestNewestAlbums(offset:count:)
     */
    @GET("rest/getAlbumList2")
    suspend fun requestNewestAlbums(
        @Query("type") type: String = "newest",
        @Query("size") count: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<AlbumListResponse>>

    /**
     * 获取最近播放的专辑
     * 对应iOS: requestRecentAlbums(offset:count:)
     */
    @GET("rest/getAlbumList2")
    suspend fun requestRecentAlbums(
        @Query("type") type: String = "recent",
        @Query("size") count: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<AlbumListResponse>>

    /**
     * 获取专辑列表(按字母排序)
     * 对应iOS: requestAlbums(offset:count:)
     */
    @GET("rest/getAlbumList2")
    suspend fun requestAlbums(
        @Query("type") type: String = "alphabeticalByName",
        @Query("size") count: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<AlbumListResponse>>

    /**
     * 获取随机歌曲
     * 对应iOS: requestRandomSongs(count:)
     */
    @GET("rest/getRandomSongs")
    suspend fun requestRandomSongs(
        @Query("size") count: Int = 50,
        @Query("genre") genre: String? = null,
        @Query("fromYear") fromYear: Int? = null,
        @Query("toYear") toYear: Int? = null,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<RandomSongsResponse>>

    /**
     * 获取已加星标的项目
     * 对应iOS: requestFavoriteElements()
     */
    @GET("rest/getStarred2")
    suspend fun requestFavoriteElements(
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<StarredResponse>>

    // ==================== 搜索相关 ====================

    /**
     * 搜索艺术家
     * 对应iOS: requestSearchArtists(searchText:)
     */
    @GET("rest/search3")
    suspend fun requestSearchArtists(
        @Query("query") searchText: String,
        @Query("artistCount") artistCount: Int = 20,
        @Query("artistOffset") artistOffset: Int = 0,
        @Query("albumCount") albumCount: Int = 0,
        @Query("songCount") songCount: Int = 0,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<SearchResponse>>

    /**
     * 搜索专辑
     * 对应iOS: requestSearchAlbums(searchText:)
     */
    @GET("rest/search3")
    suspend fun requestSearchAlbums(
        @Query("query") searchText: String,
        @Query("artistCount") artistCount: Int = 0,
        @Query("albumCount") albumCount: Int = 20,
        @Query("albumOffset") albumOffset: Int = 0,
        @Query("songCount") songCount: Int = 0,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<SearchResponse>>

    /**
     * 搜索歌曲
     * 对应iOS: requestSearchSongs(searchText:)
     */
    @GET("rest/search3")
    suspend fun requestSearchSongs(
        @Query("query") searchText: String,
        @Query("artistCount") artistCount: Int = 0,
        @Query("albumCount") albumCount: Int = 0,
        @Query("songCount") songCount: Int = 100,
        @Query("songOffset") songOffset: Int = 0,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<SearchResponse>>

    // ==================== 播放列表相关 ====================

    /**
     * 获取所有播放列表
     * 对应iOS: requestPlaylists()
     */
    @GET("rest/getPlaylists")
    suspend fun requestPlaylists(
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<PlaylistsResponse>>

    /**
     * 获取播放列表详情
     * 对应iOS: requestPlaylistSongs(id:)
     */
    @GET("rest/getPlaylist")
    suspend fun requestPlaylistSongs(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<PlaylistDetailResponse>>

    /**
     * 创建播放列表
     * 对应iOS: requestPlaylistCreate(name:)
     */
    @GET("rest/createPlaylist")
    suspend fun requestPlaylistCreate(
        @Query("name") name: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<PlaylistResponse>>

    /**
     * 更新播放列表
     * 对应iOS: requestPlaylistUpdate()
     */
    @GET("rest/updatePlaylist")
    suspend fun requestPlaylistUpdate(
        @Query("playlistId") playlistId: String,
        @Query("name") name: String? = null,
        @Query("comment") comment: String? = null,
        @Query("public") public: Boolean? = null,
        @Query("songIdToAdd") songIdToAdd: List<String>? = null,
        @Query("songIndexToRemove") songIndexToRemove: List<Int>? = null,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    /**
     * 删除播放列表
     * 对应iOS: requestPlaylistDelete(id:)
     */
    @GET("rest/deletePlaylist")
    suspend fun requestPlaylistDelete(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    // ==================== 目录浏览相关（Phase 6.5） ====================

    /**
     * 获取音乐文件夹列表
     * 对应iOS: requestMusicFolders()（SubsonicServerApi.swift:825-833）
     */
    @GET("rest/getMusicFolders")
    suspend fun requestMusicFolders(
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<MusicFoldersResponse>>

    /**
     * 获取音乐文件夹的索引（顶层目录）
     * 对应iOS: requestIndexes(musicFolderId:)（SubsonicServerApi.swift:835-841）
     */
    @GET("rest/getIndexes")
    suspend fun requestIndexes(
        @Query("musicFolderId") musicFolderId: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<IndexesResponse>>

    /**
     * 获取目录内容（子目录 + 歌曲）
     * 对应iOS: requestMusicDirectory(id:)（SubsonicServerApi.swift:843-852）
     */
    @GET("rest/getMusicDirectory")
    suspend fun requestMusicDirectory(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<DirectoryResponse>>

    // ==================== 播客相关 ====================

    /**
     * 获取所有播客
     * 对应iOS: requestPodcasts()
     */
    @GET("rest/getPodcasts")
    suspend fun requestPodcasts(
        @Query("includeEpisodes") includeEpisodes: Boolean = true,
        @Query("id") id: String? = null,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<PodcastsResponse>>

    /**
     * 获取跨播客的最新单集
     * 对应iOS: requestNewestPodcasts()（getNewestPodcasts + count=20）
     */
    @GET("rest/getNewestPodcasts")
    suspend fun requestNewestPodcasts(
        @Query("count") count: Int = 20,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<NewestPodcastsResponse>>

    /**
     * 删除播客剧集
     * 对应iOS: requestPodcastEpisodeDelete(id:)
     */
    @GET("rest/deletePodcastEpisode")
    suspend fun requestPodcastEpisodeDelete(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    // ==================== 收藏相关 (Star/Unstar) ====================

    /**
     * 收藏歌曲
     * 对应iOS: requestSetFavorite(songId:isFavorite:)
     */
    @GET("rest/star")
    suspend fun starSong(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    /**
     * 取消收藏歌曲
     * 对应iOS: requestSetFavorite(songId:isFavorite:)
     */
    @GET("rest/unstar")
    suspend fun unstarSong(
        @Query("id") id: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    /**
     * 收藏专辑
     * 对应iOS: requestSetFavorite(albumId:isFavorite:)
     */
    @GET("rest/star")
    suspend fun starAlbum(
        @Query("albumId") albumId: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    /**
     * 取消收藏专辑
     * 对应iOS: requestSetFavorite(albumId:isFavorite:)
     */
    @GET("rest/unstar")
    suspend fun unstarAlbum(
        @Query("albumId") albumId: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    /**
     * 收藏艺术家
     * 对应iOS: requestSetFavorite(artistId:isFavorite:)
     */
    @GET("rest/star")
    suspend fun starArtist(
        @Query("artistId") artistId: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    /**
     * 取消收藏艺术家
     * 对应iOS: requestSetFavorite(artistId:isFavorite:)
     */
    @GET("rest/unstar")
    suspend fun unstarArtist(
        @Query("artistId") artistId: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    // ==================== Scrobble ====================

    /**
     * 上报播放记录（submission=true）或正在播放（submission=false）
     * 对应iOS: SubsonicServerApi.requestScrobble(id:submission:date:)
     * 注：time 为毫秒级时间戳（Subsonic 规范）；iOS 传的是秒级 date 参数，此处按规范修正
     */
    @GET("rest/scrobble")
    suspend fun requestScrobble(
        @Query("id") id: String,
        @Query("submission") submission: Boolean,
        @Query("time") time: Long? = null,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    // ==================== 评分相关 (Rating) ====================

    /**
     * 设置评分（歌曲/专辑/艺术家）
     * 对应iOS: SubsonicServerApi.requestRating(id:rating:)
     * 支持 Song, Album, Artist 的评分设置
     * rating: 0-5 (0表示清除评分)
     */
    @GET("rest/setRating")
    suspend fun setRating(
        @Query("id") id: String,
        @Query("rating") rating: Int,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<EmptyResponse>>

    // ==================== 媒体检索相关 ====================

    /**
     * 获取歌词（Subsonic 传统纯文本端点，iOS 未使用，保留备用）
     */
    @GET("rest/getLyrics")
    suspend fun requestPlainLyrics(
        @Query("artist") artist: String? = null,
        @Query("title") title: String? = null,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<LyricsResponse>>

    /**
     * 获取网络电台列表
     * 对应iOS: SubsonicServerApi.requestRadios（action "getInternetRadioStations"）
     */
    @GET("rest/getInternetRadioStations")
    suspend fun requestInternetRadioStations(
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<InternetRadioStationsResponse>>

    /**
     * 获取服务器支持的 OpenSubsonic 扩展列表
     * 对应iOS: SubsonicServerApi.requestOpenSubsonicExtensions（歌词需 "songLyrics" 扩展）
     */
    @GET("rest/getOpenSubsonicExtensions")
    suspend fun requestOpenSubsonicExtensions(
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<OpenSubsonicExtensionsResponse>>

    /**
     * 获取结构化/同步歌词（OpenSubsonic 扩展端点）
     * 对应iOS: requestLyricsBySongId(id:)（SubsonicServerApi.swift:547-557）
     */
    @GET("rest/getLyricsBySongId")
    suspend fun requestLyricsBySongId(
        @Query("id") songId: String,
        @Query("f") format: String = "json"
    ): Response<SubsonicResponse<LyricsBySongIdResponse>>
}

    // ==================== URL生成辅助方法 ====================
/**
 * 自包含 URL 构建器（流/下载/封面三类）
 *
 * 这三类 URL 不经 Retrofit/OkHttp 认证拦截器（交给 ExoPlayer / Coil / 下载器直接取），
 * 故认证参数在此现拼——同样只经 [SubsonicAuthParams.authQueryParams] 这一个真相源，
 * 与 iOS 的 `generateUrl(forStreamingPlayableId:)` / `(forDownloadingPlayableId:)` /
 * `(forArtworkId:)` 复用 createAuthApiUrlComponent 同构（SubsonicServerApi.swift:242-343）。
 * 每次调用现生成新盐，与 iOS 一致。
 */
object SubsonicUrlBuilder {

    /** 认证查询串（`u=…&v=…&c=…&t=…&s=…` 或 legacy 的 `…&p=…`），值经 URL 编码 */
    private fun authQuery(username: String, password: String, apiType: BackendApiType): String =
        SubsonicAuthParams.authQueryParams(username, password, apiType)
            .entries.joinToString("&") { (k, v) ->
                "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }

    /**
     * 生成流媒体URL
     * 对应iOS: generateUrl(forStreamingPlayableId:maxBitrate:formatPreference:)
     */
    fun generateUrlForStreamingPlayable(
        id: String,
        username: String,
        password: String,
        baseUrl: String,
        apiType: BackendApiType,
        maxBitrate: Int? = null,
        format: String? = null,
        estimateContentLength: Boolean = true
    ): String {
        var url = "$baseUrl/rest/stream?id=$id&${authQuery(username, password, apiType)}"
        maxBitrate?.let { url += "&maxBitRate=$it" }
        format?.let { url += "&format=$it" }
        // estimateContentLength（Subsonic API 1.8.0+）：让服务器对转码响应给出估算 Content-Length。
        // 播放路径（默认 true）保留：ExoPlayer 拿到长度后 seek 更精准、总时长也能更早确定；
        // 原始文件（format=raw/未转码）服务器会忽略该参数，无副作用。
        // 注意：iOS（SubsonicServerApi.generateUrl(forStreamingPlayableId:)）不带此参数，因 AVPlayer
        // 不需要——这是 Android 特有的有意偏差。
        // 下载路径必须关闭（传 false）：估算长度与转码实际字节数不符，转码完成关闭连接时 OkHttp
        // 按声明长度判定流提前终止（"unexpected end of stream"），首次下载必败——本案实证：Navidrome
        // 转码首次下载必败，重试因命中转码缓存（精确长度）才成功。
        if (estimateContentLength) url += "&estimateContentLength=true"
        return url
    }

    /**
     * 生成下载URL
     * 对应iOS: generateUrl(forDownloadingPlayableId:)
     */
    fun generateUrlForDownloadingPlayable(
        id: String,
        username: String,
        password: String,
        baseUrl: String,
        apiType: BackendApiType
    ): String {
        return "$baseUrl/rest/download?id=$id&${authQuery(username, password, apiType)}"
    }

    /**
     * 生成专辑封面URL
     * 对应iOS: generateUrl(forArtworkId:)
     */
    fun generateUrlForArtwork(
        id: String,
        username: String,
        password: String,
        baseUrl: String,
        apiType: BackendApiType,
        size: Int = 500
    ): String {
        return "$baseUrl/rest/getCoverArt?id=$id&size=$size&${authQuery(username, password, apiType)}"
    }
}
