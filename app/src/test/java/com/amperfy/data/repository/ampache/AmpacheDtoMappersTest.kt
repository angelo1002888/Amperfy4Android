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

import com.amperfy.data.model.PodcastEpisodeRemoteStatus
import com.amperfy.data.remote.ampache.AmpacheAlbumDto
import com.amperfy.data.remote.ampache.AmpacheArtistDto
import com.amperfy.data.remote.ampache.AmpacheCatalogDto
import com.amperfy.data.remote.ampache.AmpacheGenreDto
import com.amperfy.data.remote.ampache.AmpachePlaylistDto
import com.amperfy.data.remote.ampache.AmpachePodcastDto
import com.amperfy.data.remote.ampache.AmpachePodcastEpisodeDto
import com.amperfy.data.remote.ampache.AmpachePodcastEpisodeRemoteStatus
import com.amperfy.data.remote.ampache.AmpacheRadioDto
import com.amperfy.data.remote.ampache.AmpacheSongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ampache DTO → 领域模型映射的字段口径合同（Batch 2）。
 *
 * 锁的是四类「不看代码就想不到」的映射约定：
 * 封面标识去 token、收藏占位时间戳、bitrate 单位换算、缺省 0 → null。
 * 播客两个映射函数注入 identity 解码器，故本测试是纯 JVM 的（不触 Android 的 HtmlCompat）。
 */
class AmpacheDtoMappersTest {

    /** 反转义器替身：单测只验字段搬运，不验 HTML 解码本身（那是 framework 的事） */
    private val identityDecoder: (String) -> String = { it }

    private val artUrl =
        "https://music.example.com/image.php?object_id=42&object_type=album&auth=abc123&ssid=xyz"

    // ==================== cover_art 标识 ====================

    @Test
    fun coverArtKey_stripsAuthAndSsidButKeepsObjectIdentity() {
        val key = ampacheCoverArtKey(artUrl)
        assertEquals(
            "https://music.example.com/image.php?object_id=42&object_type=album",
            key,
        )
    }

    @Test
    fun coverArtKey_isNullWhenArtworkIdentityMissing() {
        // 非 image.php 形态（无 object_id/object_type）：存了也反提取不出，直接判无封面
        assertNull(ampacheCoverArtKey("https://music.example.com/cover.jpg?auth=abc"))
        assertNull(ampacheCoverArtKey(null))
        assertNull(ampacheCoverArtKey("   "))
        assertNull(ampacheCoverArtKey("not a url"))
    }

    // ==================== starred 占位 ====================

    @Test
    fun favoriteFlag_mapsToPlaceholderStarredTimestamp() {
        val favorite = AmpacheArtistDto(id = "1", name = "A", isFavorite = true).toArtist()
        val plain = AmpacheArtistDto(id = "2", name = "B", isFavorite = false).toArtist()
        // Ampache 的 <flag> 只有 0/1、无日期 → 收藏存占位 0L
        assertEquals(AMPACHE_FLAGGED_STARRED_AT, favorite.starred)
        assertTrue(favorite.isFavorite)
        assertNull(plain.starred)
        assertFalse(plain.isFavorite)
    }

    @Test
    fun placeholderStarredAt_isZeroNotWallClock() {
        // 刻意不是 System.currentTimeMillis()：否则每次同步都改写、排序乱跳
        assertEquals(0L, AMPACHE_FLAGGED_STARRED_AT)
    }

    // ==================== song ====================

    @Test
    fun song_bitrateConvertedFromBpsToKbps() {
        val song = songDto(bitrate = 192_000).toSong("acc")
        assertEquals(192, song.bitRate)
    }

    @Test
    fun song_zeroBitrateBecomesNull() {
        assertNull(songDto(bitrate = 0).toSong("acc").bitRate)
    }

    @Test
    fun song_zeroTrackAndYearBecomeNull() {
        val song = songDto(track = 0, year = 0).toSong("acc")
        assertNull(song.track)
        assertNull(song.year)
    }

    @Test
    fun song_nonZeroTrackAndYearKept() {
        val song = songDto(track = 7, year = 1998).toSong("acc")
        assertEquals(7, song.track)
        assertEquals(1998, song.year)
    }

    @Test
    fun song_pathAndSuffixComeFromFilename() {
        val song = songDto(filename = "/music/AC-DC/Back in Black/01 Hells Bells.flac").toSong("acc")
        assertEquals("/music/AC-DC/Back in Black/01 Hells Bells.flac", song.path)
        assertEquals("flac", song.suffix)
    }

    @Test
    fun song_suffixNullWhenNoExtension() {
        assertNull(songDto(filename = "/music/no-extension").toSong("acc").suffix)
        assertNull(songDto(filename = null).toSong("acc").suffix)
        // 目录名里的点不得被当成扩展名
        assertNull(songDto(filename = "/music/v1.0/track").toSong("acc").suffix)
    }

    @Test
    fun song_streamUrlIsNullAndDiscParsed() {
        val song = songDto(disk = "2").toSong("acc")
        // 服务器 <url> 带会话 ssid、会过期，故不落库；播放 URL 由 MediaUrl 域现拼
        assertNull(song.streamUrl)
        assertEquals(2, song.discNumber)
        assertEquals("acc", song.accountId)
    }

    @Test
    fun song_nonNumericDiscBecomesNull() {
        assertNull(songDto(disk = "A").toSong("acc").discNumber)
        assertNull(songDto(disk = null).toSong("acc").discNumber)
    }

    // ==================== album / genre / playlist / radio / catalog ====================

    @Test
    fun album_zeroYearBecomesNullAndArtistNameDefaultsToEmpty() {
        val album = AmpacheAlbumDto(id = "a1", name = "Album", year = 0).toAlbum()
        assertNull(album.year)
        assertEquals("", album.artist)
        assertNull(album.created)
    }

    @Test
    fun genre_keepsCounts() {
        val genre = AmpacheGenreDto(id = "g1", name = "Rock", albumCount = 3, songCount = 40).toGenre()
        assertEquals("Rock", genre.name)
        assertEquals(3, genre.albumCount)
        assertEquals(40, genre.songCount)
    }

    @Test
    fun playlist_publicFlagFromTypeElement() {
        assertTrue(AmpachePlaylistDto(id = "p", name = "n", type = "public").toPlaylist().isPublic)
        assertFalse(AmpachePlaylistDto(id = "p", name = "n", type = "private").toPlaylist().isPublic)
        assertFalse(AmpachePlaylistDto(id = "p", name = "n", type = null).toPlaylist().isPublic)
    }

    @Test
    fun radio_missingStreamUrlBecomesEmptyString() {
        val radio = AmpacheRadioDto(id = "r", name = "Radio", url = null, siteUrl = "https://s").toRadio()
        assertEquals("Radio", radio.title)
        assertEquals("", radio.streamUrl)
        assertEquals("https://s", radio.siteUrl)
    }

    @Test
    fun catalog_mapsToMusicFolder() {
        val folder = AmpacheCatalogDto(id = "c1", name = "Music").toMusicFolder()
        assertEquals("c1", folder.id)
        assertEquals("Music", folder.name)
    }

    // ==================== podcast ====================

    @Test
    fun podcast_usesInjectedHtmlDecoder() {
        var calls = 0
        val decoder: (String) -> String = { calls++; it.uppercase() }
        val podcast = AmpachePodcastDto(
            id = "pc", title = "title", description = "desc", artworkUrl = artUrl,
        ).toPodcast(decoder)
        assertEquals("TITLE", podcast.title)
        assertEquals("DESC", podcast.depiction)
        assertEquals(2, calls)
        assertEquals(
            "https://music.example.com/image.php?object_id=42&object_type=album",
            podcast.coverArt,
        )
    }

    @Test
    fun podcastEpisode_podcastIdInjectedAndStatusMapped() {
        val episode = AmpachePodcastEpisodeDto(
            id = "e1",
            title = "Ep",
            description = null,
            publishDateMillis = 1_600_000_000_000L,
            status = AmpachePodcastEpisodeRemoteStatus.COMPLETED,
            duration = 1800,
        ).toPodcastEpisode("pc1", identityDecoder)

        // podcast_episodes 响应里没有所属播客 id，按请求的 filter 回填
        assertEquals("pc1", episode.podcastId)
        assertEquals(PodcastEpisodeRemoteStatus.COMPLETED, episode.status)
        assertEquals(1_600_000_000_000L, episode.publishDate)
        assertEquals(1800, episode.duration)
        assertNull(episode.depiction)
        // Ampache 无 Subsonic 的 streamId 概念
        assertNull(episode.streamId)
    }

    private fun songDto(
        bitrate: Int = 128_000,
        track: Int = 1,
        year: Int = 2000,
        disk: String? = "1",
        filename: String? = "/music/song.mp3",
    ) = AmpacheSongDto(
        id = "s1",
        title = "Song",
        artistName = "Artist",
        albumName = "Album",
        track = track,
        disk = disk,
        year = year,
        duration = 200,
        size = 5_000_000,
        bitrate = bitrate,
        contentType = "audio/mpeg",
        filename = filename,
    )
}
