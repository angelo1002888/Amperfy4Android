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

package com.amperfy.ui.util

import com.amperfy.utils.AlphabetIndexUtils

/**
 * 列表分段模式 —— 对应 iOS `SectionIndexType`
 * （BasicFetchedResultsController.swift:44-58；各排序枚举经 `asSectionIndexType` 映射，
 * FetchedResultsControllers.swift:35-49[Artist]/:62-78[Album]/:118-131[Song]）。
 *
 * iOS 七值中 Android 落地六类：
 * - [ALPHABET] = `.alphabet`（按首字母/拼音首字母分段）
 * - [RATING] = `.rating`（按星级分段，5→1→未评分）
 * - [YEAR] = `.year`（按年份分段，降序→无年份）
 * - [DURATION_SONG] / [DURATION_ALBUM] = `.durationSong`/`.durationAlbum`（按时长桶分段，升序）
 * - [NONE] = `.none` / `.newestOrRecent`（不分段、不给索引）
 *
 * 未落地：`.durationArtist`（Artists 页当前无 Duration 排序档）。
 */
enum class SortSectionMode { NONE, ALPHABET, RATING, YEAR, DURATION_SONG, DURATION_ALBUM }

/**
 * 排序分段的**真值单点**：段头标题与右侧索引条标签的文案生成。
 *
 * 各实体「哪个排序档用哪种分段模式、从哪个字段取段键」的映射就近放在各自排序枚举旁
 * （`AlbumSortType.sectionMode` + `albumSectionTitle/albumIndexLabel`、
 * `SongSortType.sectionMode` + `songSectionTitle/songIndexLabel`），
 * 以免 `ui.util` 反向依赖 `ui.screens`；文案格式一律回到本对象，只此一处。
 */
object SortSectionUtils {

    /** 无评分/无年份/无时长段的索引符号 —— iOS `SectionIndexType.noRatingIndexSymbol`/`noYearSymbol`/`noDurationSymbol` = "#" */
    const val NO_INDEX_SYMBOL = "#"

    /** 评分分段的索引条标签（段序 = iOS rating 排序 `ascending: false`，5 星在前、未评分殿后） */
    val RATING_INDEX_LABELS: List<String> = listOf("5", "4", "3", "2", "1", NO_INDEX_SYMBOL)

    /**
     * 字母段头/索引标签（拼音首字母，A-Z 或 "#"）
     * 对应 iOS `sectionTitleToIndexTitle` 的 name/artist 分支取 `prefix(1).uppercased()`
     * （AlbumsCollectionVC.swift:109-131、AlbumsVC.swift:76-106）
     */
    fun alphabetSectionTitle(text: String): String = AlphabetIndexUtils.getIndexLetter(text)

    /**
     * 评分段头标题 —— `"\(rating) Star\(rating != 1 ? "s" : "")"`，未评分为 "Not rated"
     * （AlbumsVC.swift:54-60、AlbumsCollectionVC.swift:140-146、SongsVC.swift:260-265 三处同构）
     */
    fun ratingSectionTitle(rating: Int?): String {
        val value = rating ?: 0
        return if (value > 0) "$value Star${if (value != 1) "s" else ""}" else "Not rated"
    }

    /**
     * 评分索引标签 —— iOS `IndexHeaderNameGenerator.sortByRating`
     * （BasicFetchedResultsController.swift:65-76：1-5 原样，其余 "#"）
     */
    fun ratingIndexLabel(rating: Int?): String {
        val value = rating ?: 0
        return if (value in 1..5) value.toString() else NO_INDEX_SYMBOL
    }

    /**
     * 年份段头/索引标签 —— iOS `IndexHeaderNameGenerator.sortByYear`
     * （BasicFetchedResultsController.swift:150-156：year > 0 原样，其余 "#"）
     */
    fun yearSectionTitle(year: Int?): String =
        if (year != null && year > 0) year.toString() else NO_INDEX_SYMBOL

    /**
     * 时长字符串 —— iOS `Int.asColonDurationString`（Utilities.swift:103-116）：
     * 不足 1 小时为 "M:SS"（分钟无前导零），否则 "H:MM:SS"。
     * 与列表行时长显示的 `formatSongDuration`（SongListItem.kt）同构，此处为分段独立实现
     * 以免 `ui.util` 依赖 `ui.components`。
     */
    fun asColonDurationString(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%d:%02d".format(minutes, secs)
    }

    /** iOS `Int.roundDownToFractionOf(_:)`（Utilities.swift:99-101）：向下取整到 [value] 的整数倍 */
    private fun Int.roundDownToFractionOf(value: Int): Int = (this / value) * value

    /**
     * **歌曲**时长桶标签 —— 逐条照抄 iOS `IndexHeaderNameGenerator.sortByDurationSong`
     * （BasicFetchedResultsController.swift:78-98）
     */
    fun durationSongIndexLabel(durationInSec: Int): String = when {
        durationInSec <= 0 -> NO_INDEX_SYMBOL
        durationInSec >= 3 * 60 * 60 -> asColonDurationString(180 * 60)
        durationInSec >= 1 * 60 * 60 -> asColonDurationString(durationInSec.roundDownToFractionOf(30 * 60))
        durationInSec >= 30 * 60 -> asColonDurationString(durationInSec.roundDownToFractionOf(10 * 60))
        durationInSec >= 10 * 60 -> asColonDurationString(durationInSec.roundDownToFractionOf(5 * 60))
        durationInSec >= 5 * 60 -> asColonDurationString(durationInSec.roundDownToFractionOf(1 * 60))
        else -> asColonDurationString(durationInSec.roundDownToFractionOf(30))
    }

    /**
     * **专辑**时长桶标签 —— 逐条照抄 iOS `IndexHeaderNameGenerator.sortByDurationAlbum`
     * （BasicFetchedResultsController.swift:100-127）
     */
    fun durationAlbumIndexLabel(durationInSec: Int): String = when {
        durationInSec <= 0 -> NO_INDEX_SYMBOL
        durationInSec >= 5 * 60 * 60 -> asColonDurationString(5 * 60 * 60)
        durationInSec >= 100 * 60 ->
            if (durationInSec < 2 * 60 * 60) asColonDurationString(100 * 60)
            else asColonDurationString(durationInSec.roundDownToFractionOf(60 * 60))
        durationInSec >= 70 * 60 -> asColonDurationString(durationInSec.roundDownToFractionOf(10 * 60))
        durationInSec >= 30 * 60 -> asColonDurationString(durationInSec.roundDownToFractionOf(5 * 60))
        durationInSec >= 10 * 60 -> asColonDurationString(durationInSec.roundDownToFractionOf(10 * 60))
        durationInSec >= 1 * 60 -> asColonDurationString(durationInSec.roundDownToFractionOf(2 * 60))
        else -> asColonDurationString(0)
    }

    /**
     * 该分段模式下索引条要画的标签全集；null = 沿用默认的 A-Z + "#"
     * （[com.amperfy.ui.components.AlphabetIndex] 的 `letters` 形参语义）。
     *
     * - 字母档：null（固定 A-Z + #，缺内容的字母置灰，本项目既有约定）
     * - rating 档：固定 5..1 + #（同上，缺内容置灰）
     * - year / duration 档：标签集**由数据现算**（值域无穷，不存在固定表）——
     *   对应 iOS `sectionIndexTitles` 逐 section 取 `sectionIndexTitle(forSectionName:)`
     *   （AlbumsVC.swift:76-106、BasicFetchedResultsController.swift:182-200），
     *   即「有哪些段就有哪些索引项」；列表已按该档排好序，故 distinct() 的首现序即索引序
     */
    fun <T> indexLabels(
        mode: SortSectionMode,
        items: List<T>,
        labelOf: (T) -> String
    ): List<String>? = when (mode) {
        SortSectionMode.ALPHABET, SortSectionMode.NONE -> null
        SortSectionMode.RATING -> RATING_INDEX_LABELS
        SortSectionMode.YEAR, SortSectionMode.DURATION_SONG, SortSectionMode.DURATION_ALBUM ->
            items.map(labelOf).distinct()
    }
}
