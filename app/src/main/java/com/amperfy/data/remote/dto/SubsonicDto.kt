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

package com.amperfy.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SubsonicResponse<T>(
    @SerializedName("subsonic-response")
    val subsonicResponse: T
)

/**
 * Subsonic 业务状态载体：出错时服务器返回 HTTP 200 + status="failed" + error。
 * 对应 iOS: SsXmlParser.parseForError；写操作/搜索响应均须检查 status。
 */
interface SubsonicStatusResponse {
    val status: String
    val error: SubsonicError?
        get() = null
}

data class PingResponse(
    val status: String,
    val version: String
)

data class ArtistsResponse(
    override val status: String,
    val artists: ArtistsIndexContainer,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class ArtistsIndexContainer(
    val index: List<ArtistIndex>
)

data class ArtistIndex(
    val name: String,
    val artist: List<ArtistDto>
)

data class ArtistDto(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
    val userRating: Int? = null  // Subsonic API field name for rating (0-5)
)

data class ArtistDetailResponse(
    override val status: String,
    val artist: ArtistDetailDto,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class ArtistDetailDto(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
    val album: List<AlbumDto>? = null,
    val userRating: Int? = null  // Subsonic API field name for rating (0-5)
)

data class AlbumDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val playCount: Int? = null,
    val created: String? = null,
    val starred: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val userRating: Int? = null  // Subsonic API field name for rating (0-5)
)

data class AlbumDetailResponse(
    override val status: String,
    val album: AlbumDetailDto,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class AlbumDetailDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val playCount: Int? = null,
    val created: String? = null,
    val starred: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val song: List<SongDto>? = null,
    val userRating: Int? = null  // Subsonic API field name for rating (0-5)
)

data class SongDto(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val bitRate: Int? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val size: Long? = null,
    val discNumber: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val isVideo: Boolean = false,
    val path: String? = null,
    val playCount: Int? = null,
    val created: String? = null,
    val starred: String? = null,
    val albumArtistId: String? = null,
    val type: String? = null,
    val userRating: Int? = null,  // Subsonic API field name for rating (0-5)
    /**
     * 目录浏览专用（Phase 6.5）：getIndexes/getMusicDirectory 的 <child> 元素
     * isDir=true 为子目录、false 为歌曲（iOS SsDirectoryParserDelegate 语义）
     */
    val isDir: Boolean = false,
    /**
     * ReplayGain（OpenSubsonic 扩展，C0 合同；iOS SsPlayableParserDelegate 解析
     * <replayGain> 子元素——JSON 下为 song 节点的 replayGain 对象）
     */
    val replayGain: ReplayGainDto? = null
)

/** OpenSubsonic replayGain 节点（4 字段均可缺省；gain 单位 dB，peak 为线性值） */
data class ReplayGainDto(
    val trackGain: Float? = null,
    val trackPeak: Float? = null,
    val albumGain: Float? = null,
    val albumPeak: Float? = null
)

data class AlbumListResponse(
    override val status: String,
    val albumList2: AlbumList2Container?,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class SubsonicError(
    val code: Int,
    val message: String
)

data class AlbumList2Container(
    val album: List<AlbumDto>
)

data class SearchResponse(
    override val status: String,
    val searchResult3: SearchResult3Container? = null,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class SearchResult3Container(
    val artist: List<ArtistDto>? = null,
    val album: List<AlbumDto>? = null,
    val song: List<SongDto>? = null
)

data class PlaylistsResponse(
    override val status: String,
    val playlists: PlaylistsContainer,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class PlaylistsContainer(
    val playlist: List<PlaylistDto>
)

data class PlaylistDto(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    @SerializedName("public")
    val isPublic: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val created: String? = null,
    val changed: String? = null
)

data class PlaylistDetailResponse(
    override val status: String,
    val playlist: PlaylistDetailDto,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class PlaylistDetailDto(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    @SerializedName("public")
    val isPublic: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val created: String? = null,
    val changed: String? = null,
    val entry: List<SongDto>? = null
)

// ==================== 新增DTO ====================

data class LicenseResponse(
    val status: String,
    val license: LicenseDto
)

data class LicenseDto(
    val valid: Boolean,
    val email: String? = null,
    val licenseExpires: String? = null
)

data class MusicFoldersResponse(
    override val status: String,
    val musicFolders: MusicFoldersContainer? = null,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class MusicFoldersContainer(
    val musicFolder: List<MusicFolderDto>? = null
)

data class MusicFolderDto(
    val id: String,
    val name: String
)

data class IndexesResponse(
    override val status: String,
    val indexes: IndexesContainer? = null,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class IndexesContainer(
    val lastModified: Long = 0,
    val ignoredArticles: String? = null,
    val index: List<ArtistIndex>? = null,
    val child: List<SongDto>? = null
)

data class DirectoryResponse(
    override val status: String,
    val directory: DirectoryDto? = null,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class DirectoryDto(
    val id: String,
    val name: String,
    val parent: String? = null,
    val starred: String? = null,
    val child: List<SongDto>? = null
)

data class GenresResponse(
    override val status: String,
    override val error: SubsonicError? = null,
    val genres: GenresContainer? = null
) : SubsonicStatusResponse

data class GenresContainer(
    val genre: List<GenreDto>? = null
)

data class GenreDto(
    val songCount: Int,
    val albumCount: Int,
    val value: String
)

data class SongResponse(
    val status: String,
    val song: SongDto
)

data class RandomSongsResponse(
    val status: String,
    val randomSongs: RandomSongsContainer
)

data class RandomSongsContainer(
    val song: List<SongDto>
)

data class SongsByGenreResponse(
    val status: String,
    val songsByGenre: SongsByGenreContainer
)

data class SongsByGenreContainer(
    val song: List<SongDto>
)

data class NowPlayingResponse(
    val status: String,
    val nowPlaying: NowPlayingContainer
)

data class NowPlayingContainer(
    val entry: List<NowPlayingEntry>
)

data class NowPlayingEntry(
    val username: String,
    val minutesAgo: Int,
    val playerId: String,
    val playerName: String? = null,
    val song: SongDto
)

data class StarredResponse(
    override val status: String,
    // 部分服务器在无收藏时省略该字段，声明可空防 Gson 注入 null 后 NPE
    val starred2: Starred2Container? = null,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class Starred2Container(
    val artist: List<ArtistDto>? = null,
    val album: List<AlbumDto>? = null,
    val song: List<SongDto>? = null
)

data class PlaylistResponse(
    override val status: String,
    // 部分服务器（老版本 Subsonic）创建播放列表时不回传 playlist 对象
    val playlist: PlaylistDto? = null,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class PodcastsResponse(
    override val status: String,
    val podcasts: PodcastsContainer? = null,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class PodcastsContainer(
    val channel: List<PodcastChannelDto>? = null
)

/**
 * getNewestPodcasts 响应 - 对应 iOS: requestNewestPodcasts()（跨播客最新单集，count=20）
 */
data class NewestPodcastsResponse(
    override val status: String,
    val newestPodcasts: NewestPodcastsContainer? = null,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

data class NewestPodcastsContainer(
    val episode: List<PodcastEpisodeDto>? = null
)

data class PodcastChannelDto(
    val id: String,
    val url: String,
    val title: String,
    val description: String? = null,
    val coverArt: String? = null,
    val originalImageUrl: String? = null,
    val status: String,
    val errorMessage: String? = null,
    val episode: List<PodcastEpisodeDto>? = null
)

data class PodcastEpisodeDto(
    val id: String,
    val parent: String? = null,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val path: String? = null,
    val isVideo: Boolean = false,
    val playCount: Int? = null,
    val created: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val type: String? = null,
    val streamId: String? = null,
    val channelId: String? = null,
    val description: String? = null,
    val status: String,
    val publishDate: String? = null
)

data class LyricsResponse(
    val status: String,
    val lyrics: LyricsDto
)

data class LyricsDto(
    val artist: String? = null,
    val title: String? = null,
    val value: String? = null
)

data class EmptyResponse(
    override val status: String,
    override val error: SubsonicError? = null
) : SubsonicStatusResponse

// ==================== 电台（Phase 6.3） ====================

/**
 * getInternetRadioStations 响应
 * 对应 iOS: SsRadioParserDelegate 解析的 internetRadioStation 元素
 */
data class InternetRadioStationsResponse(
    override val status: String,
    override val error: SubsonicError? = null,
    val internetRadioStations: InternetRadioStationsContainer? = null
) : SubsonicStatusResponse

data class InternetRadioStationsContainer(
    val internetRadioStation: List<InternetRadioStationDto>? = null
)

data class InternetRadioStationDto(
    val id: String,
    val name: String? = null,
    val streamUrl: String? = null,
    val homePageUrl: String? = null
)

// ==================== OpenSubsonic 扩展（Phase 6.2 歌词） ====================

/**
 * getOpenSubsonicExtensions 响应
 * 对应 iOS: SsOpenSubsonicExtensionsParserDelegate（检测 "songLyrics" 扩展支持）
 */
data class OpenSubsonicExtensionsResponse(
    override val status: String,
    override val error: SubsonicError? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtensionDto>? = null
) : SubsonicStatusResponse

data class OpenSubsonicExtensionDto(
    val name: String,
    val versions: List<Int>? = null
)

/**
 * getLyricsBySongId 响应（OpenSubsonic 结构化/同步歌词）
 * 对应 iOS: SsLyricsParserDelegate 解析的 lyricsList/structuredLyrics/line 结构
 */
data class LyricsBySongIdResponse(
    override val status: String,
    override val error: SubsonicError? = null,
    val lyricsList: LyricsListDto? = null
) : SubsonicStatusResponse

data class LyricsListDto(
    val structuredLyrics: List<StructuredLyricsDto>? = null
)

data class StructuredLyricsDto(
    val displayArtist: String? = null,
    val displayTitle: String? = null,
    val lang: String? = null,
    val offset: Int? = null,
    val synced: Boolean? = null,
    val line: List<LyricsLineDto>? = null
)

data class LyricsLineDto(
    val start: Long? = null,   // 毫秒，unsynced 时缺省
    val value: String? = null
)
