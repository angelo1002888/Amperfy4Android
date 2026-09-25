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

package com.amperfy.data.local

/**
 * Settings相关的枚举类型定义
 * 对应iOS: Settings.swift中的各种Preference枚举
 */

/**
 * 屏幕锁定防止选项
 * 对应iOS: ScreenLockPreventionPreference
 */
enum class ScreenLockPreventionPreference(val value: String, val displayName: String) {
    NEVER("never", "Never"),
    ALWAYS("always", "Always"),
    // 文案对齐 iOS 2.1.0 SettingEnumerations.swift:88（"When connected to charger"）
    ONLY_IF_CHARGING("only_if_charging", "When connected to charger");

    companion object {
        fun fromString(value: String): ScreenLockPreventionPreference {
            return entries.find { it.value == value } ?: NEVER
        }
    }
}

/**
 * 主题颜色选项
 * 对应iOS: ThemePreference（SettingEnumerations.swift:264-289，6 色 blue/green/red/
 * yellow/orange/purple，默认 blue；无 Pink——原多出的 PINK 已随 2.1.0 对齐删除，
 * 旧值 "pink" 经 fromString 回退 BLUE）
 */
enum class ThemePreference(val value: String, val displayName: String) {
    BLUE("blue", "Blue"),
    GREEN("green", "Green"),
    RED("red", "Red"),
    YELLOW("yellow", "Yellow"),
    ORANGE("orange", "Orange"),
    PURPLE("purple", "Purple");

    companion object {
        fun fromString(value: String): ThemePreference {
            return entries.find { it.value == value } ?: BLUE
        }
    }
}

/**
 * 外观模式
 * 对应iOS: UIUserInterfaceStyle
 */
enum class AppearanceMode(val value: String, val displayName: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromString(value: String): AppearanceMode {
            return entries.find { it.value == value } ?: SYSTEM
        }
    }
}

/**
 * 流媒体最大比特率选项
 * 对应iOS: StreamingMaxBitratePreference（PersistentStorage.swift:94-118，
 * 档位 noLimit/32/64/96/128/192/256/320，默认 noLimit）
 */
enum class StreamingMaxBitratePreference(val value: String, val displayName: String, val kbps: Int?) {
    NO_LIMIT("noLimit", "No Limit (default)", null),
    KBPS_32("32", "32 kbps", 32),
    KBPS_64("64", "64 kbps", 64),
    KBPS_96("96", "96 kbps", 96),
    KBPS_128("128", "128 kbps", 128),
    KBPS_192("192", "192 kbps", 192),
    KBPS_256("256", "256 kbps", 256),
    KBPS_320("320", "320 kbps", 320);

    companion object {
        fun fromString(value: String): StreamingMaxBitratePreference {
            return entries.find { it.value == value } ?: NO_LIMIT
        }
    }
}

/**
 * 流媒体格式选项（单一偏好，不分 WiFi/蜂窝）
 * 对应iOS: StreamingFormatPreference（PersistentStorage.swift:122-139，
 * mp3/raw/serverConfig，默认 mp3；生效于 stream URL 的 format 参数）
 */
enum class StreamingFormatPreference(val value: String, val displayName: String) {
    MP3("mp3", "mp3 (default)"),
    RAW("raw", "Raw/Original"),
    SERVER_CONFIG("serverConfig", "Server chooses Codec");

    companion object {
        fun fromString(value: String): StreamingFormatPreference {
            return entries.find { it.value == value } ?: MP3
        }
    }
}

/**
 * 封面下载策略
 * 对应iOS: ArtworkDownloadSetting（PersistentStorage.swift:28-45，默认 onlyOnce）
 * Android 经 Coil 拦截器生效：NEVER 禁网络仅走缓存；
 * UPDATE_ONCE_PER_SESSION 本会话首次请求强制拉取最新；ONLY_ONCE 缓存优先永不重新校验
 */
enum class ArtworkDownloadSetting(val value: String, val displayName: String) {
    UPDATE_ONCE_PER_SESSION("update_once_per_session", "Download once per session (change detection)"),
    ONLY_ONCE("only_once", "Download only once"),
    NEVER("never", "Never");

    companion object {
        fun fromString(value: String): ArtworkDownloadSetting {
            return entries.find { it.value == value } ?: ONLY_ONCE
        }
    }
}

/**
 * 封面显示偏好（ID3 内嵌封面 vs 服务器封面）
 * 对应iOS: ArtworkDisplayPreference（SettingEnumerations.swift:50-70，默认 preferId3Tag）；
 * 枚举顺序 = iOS allCases 顺序（id3TagOnly/serverArtworkOnly/preferServerArtwork/preferId3Tag）
 *
 * **已知简化**：本设置在 Android 暂无消费方——Android 只使用服务器封面（未实现从
 * 歌曲/播客单集文件提取 ID3 内嵌封面）。将来 ID3 封面提取接上后由封面加载链消费；
 * 现阶段仅按 iOS 结构落地设置项本身（Settings→Artwork→Artwork Display Settings）。
 */
enum class ArtworkDisplayPreference(val value: String, val displayName: String) {
    ID3_TAG_ONLY("id3_tag_only", "Only ID3 tag artworks"),
    SERVER_ARTWORK_ONLY("server_artwork_only", "Only server artworks"),
    PREFER_SERVER_ARTWORK("prefer_server_artwork", "Prefer server artwork over ID3 tag"),
    PREFER_ID3_TAG("prefer_id3_tag", "Prefer ID3 tag over server artwork");

    companion object {
        fun fromString(value: String): ArtworkDisplayPreference {
            return entries.find { it.value == value } ?: PREFER_ID3_TAG
        }
    }
}

/**
 * 缓存转码格式选项
 * 对应iOS: CacheTranscodingFormatPreference（PersistentStorage.swift:216-233，
 * raw/mp3/serverConfig，默认 mp3）
 * RAW 走 Subsonic 'download' 端点（跳过转码），其余走 'stream' 端点
 * （SubsonicServerApi.generateUrl(forDownloadingPlayableId:)，:318-343）
 */
enum class CacheTranscodingFormatPreference(val value: String, val displayName: String) {
    MP3("mp3", "mp3 (default)"),
    RAW("raw", "Raw/Original"),
    SERVER_CONFIG("serverConfig", "Server chooses Codec");

    companion object {
        fun fromString(value: String): CacheTranscodingFormatPreference {
            return entries.find { it.value == value } ?: MP3
        }
    }
}

/**
 * 播客列表显示模式
 * 对应iOS: PodcastsShowType（settings.podcastsShowSetting，PodcastsVC 排序菜单切换）
 */
enum class PodcastsShowType(val value: String, val displayName: String) {
    PODCASTS("podcasts", "Podcasts sorted by name"),
    EPISODES_SORTED_BY_RELEASE_DATE("episodes", "Episodes sorted by release date");

    companion object {
        fun fromString(value: String): PodcastsShowType {
            return entries.find { it.value == value } ?: PODCASTS
        }
    }
}

/**
 * 收藏歌曲排序方式
 * 对应iOS: SongElementSortType（settings.favoriteSongSortSetting）；
 * **声明顺序 = 菜单顺序**，逐项对齐 SongsVC displayFilter=.favorites 的 Sort 菜单
 * （SongsVC.swift:440-446：Name / Rating / Duration / Starred date / Date Added）。
 * 默认值见 SettingsManager.favoriteSongsSortSetting（iOS defaultValueForFavorite = .starredDate）
 */
enum class FavoriteSongSortType(val value: String, val displayName: String) {
    NAME("name", "Name"),
    RATING("rating", "Rating"),
    DURATION("duration", "Duration"),
    STARRED_DATE("starredDate", "Starred date"),
    DATE_ADDED("dateAdded", "Date Added");

    companion object {
        fun fromString(value: String): FavoriteSongSortType {
            return entries.find { it.value == value } ?: NAME
        }
    }
}
