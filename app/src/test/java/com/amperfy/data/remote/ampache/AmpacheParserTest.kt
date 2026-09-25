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

import com.amperfy.data.remote.ampache.parser.AmpacheAlbumParser
import com.amperfy.data.remote.ampache.parser.AmpacheArtistParser
import com.amperfy.data.remote.ampache.parser.AmpacheAuthParser
import com.amperfy.data.remote.ampache.parser.AmpacheCatalogParser
import com.amperfy.data.remote.ampache.parser.AmpacheErrorParser
import com.amperfy.data.remote.ampache.parser.AmpacheGenreParser
import com.amperfy.data.remote.ampache.parser.AmpacheIdsParser
import com.amperfy.data.remote.ampache.parser.AmpachePlaylistParser
import com.amperfy.data.remote.ampache.parser.AmpachePlaylistSongsParser
import com.amperfy.data.remote.ampache.parser.AmpachePodcastEpisodeParser
import com.amperfy.data.remote.ampache.parser.AmpachePodcastParser
import com.amperfy.data.remote.ampache.parser.AmpacheRadioParser
import com.amperfy.data.remote.ampache.parser.AmpacheSongParser
import com.amperfy.data.remote.ampache.parser.AmpacheSuccessParser
import com.amperfy.data.remote.ampache.parser.AmpacheXmlParser
import com.amperfy.data.remote.ampache.parser.AmpacheXmlParsing
import com.amperfy.data.remote.ampache.parser.AmpacheXmlValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ampache SAX 解析器族合同（Batch 1）。
 *
 * 样例来源：`app/src/test/resources/ampache/`（ampache/python3-ampache 官方仓库 api5 分支的
 * 真实响应，来源记录见同目录 README.md）——**改样例即改合同**。
 *
 * 断言口径：只锁「iOS 各 ParserDelegate 实际消费的字段」+ Android 侧超集字段的取值，
 * 不锁样例里未被消费的元素。
 */
class AmpacheParserTest {

    // ==================== handshake ====================

    @Test
    fun authParser_parsesTokenExpireAndCounts() {
        val parser = AmpacheAuthParser()
        val handshake = parse("handshake.xml", parser)
        assertNotNull(handshake)
        requireNotNull(handshake)

        assertEquals("5f8fdddd612635070f90f833b8bd5ff9", handshake.token)
        assertEquals("5.5.6", handshake.serverApiVersion)
        assertEquals("5.5.6", parser.serverApiVersion)
        // <session_expire>2022-08-17T04:34:55+00:00</session_expire>
        assertEquals(1_660_710_895_000L, handshake.sessionExpireMillis)
        // 提前重握手 = 过期前 5 分钟（AuthParserDelegate.swift:30）
        assertEquals(
            handshake.sessionExpireMillis - 5 * 60 * 1000L,
            handshake.reauthenticateTimeMillis,
        )
        assertEquals(75, handshake.songCount)
        assertEquals(18, handshake.artistCount)
        assertEquals(9, handshake.albumCount)
        assertEquals(7, handshake.genreCount)
        assertEquals(4, handshake.playlistCount)
        assertEquals(2, handshake.podcastCount)
        assertEquals(3, handshake.videoCount)
        assertTrue(handshake.libraryChangeDates.lastAddMillis > 0)
        assertTrue(handshake.libraryChangeDates.lastUpdateMillis > 0)
        assertTrue(handshake.libraryChangeDates.lastCleanMillis > 0)
    }

    /** `<auth>` 为空 = 握手失败（AuthParserDelegate.swift:69-70） */
    @Test
    fun authParser_emptyAuthTokenYieldsNull() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
              <auth><![CDATA[]]></auth>
              <api><![CDATA[5.5.6]]></api>
            </root>
        """.trimIndent()
        assertNull(AmpacheXmlParsing.parse(xml, AmpacheAuthParser()))
    }

    // ==================== catalogs / genres ====================

    @Test
    fun catalogParser_parsesIdAndName() {
        val parser = AmpacheCatalogParser()
        val catalogs = parse("catalogs.xml", parser)
        assertEquals(4, catalogs.size)
        assertEquals(4, parser.totalCount)
        assertEquals("1", catalogs[0].id)
        assertEquals("music", catalogs[0].name)
        assertEquals("podcast", catalogs[1].name)
    }

    @Test
    fun genreParser_parsesNameAndCounts() {
        val parser = AmpacheGenreParser()
        val genres = parse("genres.xml", parser)
        assertEquals(2, genres.size)
        assertEquals(7, parser.totalCount)
        val dance = genres[0]
        assertEquals("6", dance.id)
        assertEquals("Dance", dance.name)
        assertEquals(1, dance.albumCount)
        assertEquals(1, dance.artistCount)
        assertEquals(11, dance.songCount)
    }

    // ==================== artists ====================

    @Test
    fun artistParser_parsesArtistsListWithArtworkAndCounts() {
        val parser = AmpacheArtistParser()
        val artists = parse("artists.xml", parser)
        assertEquals(4, artists.size)
        assertEquals(18, parser.totalCount)

        val first = artists[0]
        assertEquals("16", first.id)
        assertEquals("CARNÚN", first.name)
        assertEquals(1, first.albumCount)
        assertEquals(9, first.songCount)
        assertEquals(3873, first.duration)
        assertEquals(0, first.rating) // <rating/> 空元素
        assertEquals(false, first.isFavorite)
        assertNull(first.genreId)
        assertEquals(AmpacheArtworkInfo("16", "artist"), first.artworkInfo)

        // 带 genre 的那条
        val withGenre = artists.first { it.id == "36" }
        assertEquals("8", withGenre.genreId)
        assertEquals("Hip-Hop", withGenre.genreName)
    }

    /** 单艺术家详情：多个 `<genre>` 时取最后一个（与 iOS 覆盖式赋值一致），rating 非空 */
    @Test
    fun artistParser_parsesSingleArtistAndLastGenreWins() {
        val artists = parse("artist.xml", AmpacheArtistParser())
        assertEquals(1, artists.size)
        val artist = artists[0]
        assertEquals("14", artist.id)
        assertEquals("Nofi/found.", artist.name)
        assertEquals(2, artist.rating)
        assertEquals(11, artist.songCount)
        assertEquals(4423, artist.duration)
        assertEquals("6", artist.genreId)
        assertEquals("Dance", artist.genreName)
    }

    @Test
    fun artistParser_parsesAdvancedSearchArtists() {
        val artists = parse("advanced_search-artist.xml", AmpacheArtistParser())
        assertEquals(4, artists.size)
        assertEquals("16", artists[0].id)
    }

    // ==================== albums ====================

    @Test
    fun albumParser_parsesAlbumsListWithArtistAndGenre() {
        val parser = AmpacheAlbumParser()
        val albums = parse("albums.xml", parser)
        assertEquals(1, albums.size)
        assertEquals(9, parser.totalCount)

        val album = albums[0]
        assertEquals("12", album.id)
        assertEquals("Buried in Nausea", album.name)
        assertEquals("19", album.artistId)
        assertEquals("Various Artists", album.artistName)
        assertEquals(2012, album.year)
        assertEquals(1879, album.duration)
        assertEquals(9, album.songCount)
        assertEquals("7", album.genreId)
        assertEquals("Punk", album.genreName)
        assertEquals(false, album.isFavorite)
        assertEquals(AmpacheArtworkInfo("12", "album"), album.artworkInfo)
    }

    @Test
    fun albumParser_parsesSingleAlbumArtistAlbumsAndStats() {
        val single = parse("album.xml", AmpacheAlbumParser())
        assertEquals(1, single.size)
        assertEquals("10", single[0].id)
        assertEquals(1996, single[0].year)
        assertEquals(9, single[0].songCount)

        val artistAlbums = parse("artist_albums.xml", AmpacheAlbumParser())
        assertEquals(1, artistAlbums.size)
        assertEquals("8", artistAlbums[0].id)
        assertEquals(2, artistAlbums[0].rating)
        assertEquals("6", artistAlbums[0].genreId) // 两个 genre 取最后一个

        val stats = parse("stats-album.xml", AmpacheAlbumParser())
        assertEquals(2, stats.size)
        assertEquals("21", stats[0].id)
        assertEquals("Forget and Remember", stats[0].name)

        val favorites = parse("advanced_search-album.xml", AmpacheAlbumParser())
        assertEquals(2, favorites.size)
    }

    // ==================== songs ====================

    @Test
    fun songParser_parsesAllConsumedFields() {
        val parser = AmpacheSongParser()
        val songs = parse("song.xml", parser)
        assertEquals(1, songs.size)
        assertEquals(75, parser.totalCount)

        val song = songs[0]
        assertEquals("77", song.id)
        assertEquals("inominavel p02", song.title)
        assertEquals("11", song.artistId)
        assertEquals("structura", song.artistName)
        assertEquals("18", song.albumId)
        assertEquals("Inominavel (live.v.01)", song.albumName)
        assertEquals("11", song.albumArtistId)
        assertEquals("3", song.genreId)
        assertEquals("Noise", song.genreName)
        assertEquals(2, song.track)
        assertEquals("1", song.disk)
        assertEquals(2007, song.year)
        assertEquals(237, song.duration)
        assertEquals(5_723_290L, song.size)
        assertEquals(192_000, song.bitrate)
        assertEquals("audio/mpeg", song.contentType)
        assertEquals(5, song.rating)
        assertEquals(true, song.isFavorite) // <flag>1</flag>
        assertEquals(AmpacheArtworkInfo("18", "album"), song.artworkInfo)
        assertTrue(song.url!!.startsWith("https://music.com.au/play/index.php"))
        assertTrue(song.filename!!.endsWith(".mp3"))
        assertEquals(0.0f, song.replayGainTrackGain!!, 0.0001f)
        assertEquals(0.0f, song.replayGainTrackPeak!!, 0.0001f)
        // <replaygain_album_gain/> 空元素 → null（服务器没给）
        assertNull(song.replayGainAlbumGain)
        assertNull(song.replayGainAlbumPeak)
    }

    @Test
    fun songParser_parsesAlbumArtistAndSearchSongLists() {
        val albumSongs = parse("album_songs.xml", AmpacheSongParser())
        assertEquals(4, albumSongs.size)
        assertEquals(listOf("106", "100", "98", "104"), albumSongs.map { it.id })
        assertEquals(1.98f, albumSongs[0].replayGainTrackGain!!, 0.0001f)

        val artistSongs = parse("artist_songs.xml", AmpacheSongParser())
        assertEquals(1, artistSongs.size)
        assertEquals("110", artistSongs[0].id)

        val searchSongs = parse("search_songs.xml", AmpacheSongParser())
        assertEquals(4, searchSongs.size)
        assertEquals("83", searchSongs[0].id)
        assertEquals("Sensorisk Deprivation", searchSongs[0].title)
        assertEquals(385_823, searchSongs[0].bitrate)
        assertEquals(39_473_388L, searchSongs[0].size)
        assertEquals("audio/flac", searchSongs[0].contentType)

        val randomSongs = parse("playlist_generate-song.xml", AmpacheSongParser())
        assertEquals(4, randomSongs.size)
        assertEquals("88", randomSongs[0].id)

        val favoriteSongs = parse("advanced_search-song.xml", AmpacheSongParser())
        assertEquals(4, favoriteSongs.size)
    }

    // ==================== playlists ====================

    @Test
    fun playlistParser_parsesPlaylistsAndCreateResponse() {
        val parser = AmpachePlaylistParser()
        val playlists = parse("playlists.xml", parser)
        assertEquals(1, playlists.size)
        assertEquals(4, parser.totalCount)

        val playlist = playlists[0]
        assertEquals("4", playlist.id)
        assertEquals("random - user - private", playlist.name)
        assertEquals("user", playlist.owner)
        assertEquals(43, playlist.songCount)
        assertEquals("private", playlist.type)
        assertEquals(AmpacheArtworkInfo("4", "playlist"), playlist.artworkInfo)

        val single = parse("playlist.xml", AmpachePlaylistParser())
        assertEquals("150", single[0].id)
        assertEquals("renamexml", single[0].name)
        assertEquals(0, single[0].songCount)

        // playlist_create 回吐的就是新建的 playlist——Batch 2 靠它拿服务器分配的 id
        val created = parse("playlist_create.xml", AmpachePlaylistParser())
        assertEquals(1, created.size)
        assertEquals("150", created[0].id)
    }

    /** 官方 playlist_songs 样例是空列表形态；解析器须给出空结果而非报错 */
    @Test
    fun playlistSongsParser_handlesEmptyResponse() {
        val parser = AmpachePlaylistSongsParser()
        val songs = parse("playlist_songs.xml", parser)
        assertEquals(0, songs.size)
        assertEquals(0, parser.collectionDurationSeconds)
        assertNull(parser.totalCount)
    }

    /** 顺序即列表顺序 + 时长合计（用 album_songs 样例代替：结构与 playlist_songs 相同，都是 `<song>` 串） */
    @Test
    fun playlistSongsParser_keepsOrderAndSumsDuration() {
        val parser = AmpachePlaylistSongsParser()
        val songs = parse("album_songs.xml", parser)
        assertEquals(listOf("106", "100", "98", "104"), songs.map { it.id })
        assertEquals(songs.sumOf { it.duration }, parser.collectionDurationSeconds)
        assertTrue(parser.collectionDurationSeconds > 0)
    }

    // ==================== 播客 / 电台 ====================

    @Test
    fun podcastParser_keepsRawHtmlEscapedText() {
        val parser = AmpachePodcastParser()
        val podcasts = parse("podcasts.xml", parser)
        assertEquals(2, podcasts.size)
        assertEquals(2, parser.totalCount)

        val first = podcasts[0]
        assertEquals("1", first.id)
        assertEquals("60-Second Science", first.title)
        assertEquals(AmpacheArtworkInfo("1", "podcast"), first.artworkInfo)
        // HTML 转义原文原样保留（反转义在 Batch 2 映射层，见 AmpacheDto 文件头）
        assertTrue(first.description.contains("&mdash;"))
    }

    @Test
    fun podcastEpisodeParser_parsesDatesStatusAndSizes() {
        val parser = AmpachePodcastEpisodeParser()
        val episodes = parse("podcast_episodes.xml", parser)
        assertEquals(4, episodes.size)
        assertEquals(13, parser.totalCount)

        val first = episodes[0]
        assertEquals("82857", first.id)
        assertEquals("Ebola update, World Cup heat risks, dad brains", first.title)
        // <pubdate>2026-06-22T09:50:00+00:00</pubdate>
        assertEquals(1_782_121_800_000L, first.publishDateMillis)
        assertEquals(AmpachePodcastEpisodeRemoteStatus.COMPLETED, first.status)
        // <filelength>00:12:25</filelength> 与 <time>745</time> 一致；<time> 在后生效
        assertEquals(745, first.duration)
        // <filesize>17.33 MB</filesize> 被其后的 <size>18170164</size> 覆盖
        assertEquals(18_170_164L, first.size)
        assertEquals("audio/mpeg", first.contentType)
        assertEquals(AmpacheArtworkInfo("1", "podcast"), first.artworkInfo)
        assertTrue(first.description!!.isNotEmpty())
    }

    @Test
    fun radioParser_parsesLiveStreams() {
        val parser = AmpacheRadioParser()
        val radios = parse("live_streams.xml", parser)
        assertEquals(2, radios.size)
        assertEquals(2, parser.totalCount)
        assertEquals("1", radios[0].id)
        assertEquals("HBR1.com - Dream Factory", radios[0].name)
        assertEquals("http://ubuntu.hbr1.com:19800/ambient.aac", radios[0].url)
        assertEquals("http://www.hbr1.com/", radios[0].siteUrl)
    }

    // ==================== IDs / success ====================

    @Test
    fun idsParser_collectsIdsAndArtworkInfos() {
        val ids = parse("album_songs.xml", AmpacheIdsParser())
        assertEquals(4, ids.songIds.size)
        assertTrue(ids.songIds.contains("106"))
        assertTrue(ids.albumIds.contains("10"))
        assertTrue(ids.artistIds.contains("16"))
        assertTrue(ids.artworkInfos.contains(AmpacheArtworkInfo("10", "album")))

        val podcastIds = parse("podcast_episodes.xml", AmpacheIdsParser())
        assertEquals(4, podcastIds.podcastEpisodeIds.size)

        val radioIds = parse("live_streams.xml", AmpacheIdsParser())
        assertEquals(setOf("1", "2"), radioIds.radioIds)
    }

    @Test
    fun successParser_parsesWriteActionResponses() {
        val flag = parse("flag.xml", AmpacheSuccessParser())
        assertNotNull(flag)
        assertEquals(1, flag!!.code)
        assertEquals("flag ADDED to 77", flag.message)

        assertEquals("rating set to 5 for 77", parse("rate.xml", AmpacheSuccessParser())!!.message)
        assertEquals(
            "playlist deleted",
            parse("playlist_delete.xml", AmpacheSuccessParser())!!.message,
        )
        assertEquals(
            "podcast_episode 41040 deleted",
            parse("podcast_episode_delete.xml", AmpacheSuccessParser())!!.message,
        )
        assertNotNull(parse("record_play.xml", AmpacheSuccessParser()))
        assertNotNull(parse("playlist_add_song.xml", AmpacheSuccessParser()))
        assertNotNull(parse("playlist_remove_song.xml", AmpacheSuccessParser()))
        assertNotNull(parse("playlist_edit.xml", AmpacheSuccessParser()))
    }

    // ==================== 错误响应 ====================

    /** 4701 会话失效：四段全解出，且属「应展示给用户」的错误 */
    @Test
    fun errorParser_parses4701SessionExpired() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <root>
                <error errorCode="4701">
                    <errorAction><![CDATA[playlist_create]]></errorAction>
                    <errorType><![CDATA[account]]></errorType>
                    <errorMessage><![CDATA[Session Expired]]></errorMessage>
                </error>
            </root>
        """.trimIndent()
        val error = AmpacheXmlParsing.parse(xml, AmpacheErrorParser())
        assertNotNull(error)
        requireNotNull(error)
        assertEquals(4701, error.statusCode)
        assertEquals("playlist_create", error.errorAction)
        assertEquals("account", error.errorType)
        assertEquals("Session Expired", error.message)
        assertEquals(AmpacheErrorCode.RECEIVED_INVALID_HANDSHAKE, error.errorCode)
        assertTrue(error.shouldErrorBeDisplayedToUser)
        assertTrue(error.isRemoteAvailable)
    }

    /** 4704 Not Found：不展示给用户、也不判为远端不可用（iOS :79-85） */
    @Test
    fun errorParser_parses4704NotFoundWithIosSemantics() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <root>
                <error errorCode="4704">
                    <errorAction><![CDATA[song]]></errorAction>
                    <errorType><![CDATA[filter]]></errorType>
                    <errorMessage><![CDATA[Not Found]]></errorMessage>
                </error>
            </root>
        """.trimIndent()
        val error = AmpacheXmlParsing.parse(xml, AmpacheErrorParser())!!
        assertEquals(AmpacheErrorCode.NOT_FOUND, error.errorCode)
        assertEquals(false, error.shouldErrorBeDisplayedToUser)
        assertEquals(false, error.isRemoteAvailable)
    }

    /** 错误体也能被业务解析器识别（基类统一处理 → 单遍解析即可判错） */
    @Test
    fun businessParser_alsoSurfacesErrorNode() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <root>
                <error errorCode="4710">
                    <errorAction><![CDATA[albums]]></errorAction>
                    <errorType><![CDATA[offset]]></errorType>
                    <errorMessage><![CDATA[Bad Request]]></errorMessage>
                </error>
            </root>
        """.trimIndent()
        val parser = AmpacheAlbumParser()
        val albums = AmpacheXmlParsing.parse(xml, parser)
        assertEquals(0, albums.size)
        assertEquals(4710, parser.error?.statusCode)
        assertEquals(AmpacheErrorCode.BAD_REQUEST, parser.error?.errorCode)
    }

    /** 正常响应不得留下 error（避免基类误判） */
    @Test
    fun businessParser_hasNoErrorOnNormalResponse() {
        val parser = AmpacheSongParser()
        parse("song.xml", parser)
        assertNull(parser.error)
    }

    // ==================== 值转换（含刻意照抄的 iOS 怪癖） ====================

    @Test
    fun values_parseByteCountMatchesIosFloatChain() {
        assertEquals(17_330_000L, AmpacheXmlValues.parseByteCount("17.33 MB"))
        assertEquals(1_000L, AmpacheXmlValues.parseByteCount("1 KB"))
        assertEquals(2_500_000_000L, AmpacheXmlValues.parseByteCount("2.5 GB"))
        assertEquals(42L, AmpacheXmlValues.parseByteCount("42 B"))
        assertNull(AmpacheXmlValues.parseByteCount("17.33MB"))
        assertNull(AmpacheXmlValues.parseByteCount(""))
    }

    /**
     * iOS `asDurationInSeconds` 首段乘的是 60*24（=1440）而非 3600——照抄该怪癖，
     * 见 AmpacheXmlValues.parseDurationInSeconds 注释。
     */
    @Test
    fun values_parseDurationKeepsIosQuirk() {
        assertEquals(745, AmpacheXmlValues.parseDurationInSeconds("00:12:25"))
        assertEquals(1440, AmpacheXmlValues.parseDurationInSeconds("01:00:00"))
        assertNull(AmpacheXmlValues.parseDurationInSeconds("12:25"))
    }

    @Test
    fun values_artworkInfoExtraction() {
        val info = AmpacheArtworkInfo.fromUrl(
            "https://music.com.au/image.php?object_id=16&object_type=artist&id=134&name=art.jpg",
        )
        assertEquals(AmpacheArtworkInfo("16", "artist"), info)
        assertNull(AmpacheArtworkInfo.fromUrl("https://music.com.au/image.php?object_id=16"))
        assertNull(AmpacheArtworkInfo.fromUrl("not-a-url"))
    }

    @Test
    fun values_podcastEpisodeStatusMapping() {
        assertEquals(
            AmpachePodcastEpisodeRemoteStatus.COMPLETED,
            AmpachePodcastEpisodeRemoteStatus.from("completed"),
        )
        // Ampache 的首字母大写变体（iOS PodcastEpisode.swift:63-64）
        assertEquals(
            AmpachePodcastEpisodeRemoteStatus.DOWNLOADING,
            AmpachePodcastEpisodeRemoteStatus.from("Pending"),
        )
        assertEquals(
            AmpachePodcastEpisodeRemoteStatus.UNDEFINED,
            AmpachePodcastEpisodeRemoteStatus.from("whatever"),
        )
        assertEquals(5, AmpachePodcastEpisodeRemoteStatus.DELETED.raw)
    }

    // ==================== 工具 ====================

    private fun <T> parse(fixture: String, parser: AmpacheXmlParser<T>): T =
        AmpacheXmlParsing.parse(readFixture(fixture), parser)

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("ampache/$name")) {
            "缺少测试样例 app/src/test/resources/ampache/$name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
