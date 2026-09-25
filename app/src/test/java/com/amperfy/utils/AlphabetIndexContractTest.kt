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

package com.amperfy.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 拼音分区/排序固定语料合同。
 *
 * 第一部分锁定 [AlphabetIndexUtils.getIndexLetter] 现有行为（纯 JVM，pinyin4j 直接可用）：
 * - 多音字以 pinyin4j 2.5.1 数据文件（pinyindb/unicode_to_hanyu_pinyin.txt）的**首个读音**为准：
 *   长 U+957F = (zhang3,chang2) → Z；重 U+91CD = (zhong4,chong2) → Z。
 *   注意：地名口语读音应为 C（长城 Changcheng / 重庆 Chongqing），当前实现不做词级消歧，
 *   合同以现有实现输出（Z）为准锁定——这是行为刻画，不是正确性背书。
 *
 * 第二部分锁定各列表页现行排序语义（AlbumsViewModel/ArtistsViewModel/GenresViewModel/
 * RadiosViewModel 同一模式）：
 *   sortKey = if (letter == "#") "ZZZ" else letter；按 (sortKey + name) 字符串序排序。
 * 该方案在纯大写拉丁语料下满足「A 组在前、Z 组次之、# 组最后」；但存在反例（见下方 FIXME）。
 */
class AlphabetIndexContractTest {

    // ==================== getIndexLetter 语料合同 ====================

    @Test
    fun englishLetters_upperAndLowerCase_mapToUppercaseLetter() {
        assertEquals("A", AlphabetIndexUtils.getIndexLetter("apple"))
        assertEquals("A", AlphabetIndexUtils.getIndexLetter("Apple"))
        assertEquals("Z", AlphabetIndexUtils.getIndexLetter("zebra"))
    }

    @Test
    fun commonChineseNames_mapToPinyinFirstLetter() {
        assertEquals("Z", AlphabetIndexUtils.getIndexLetter("周杰伦"))
        assertEquals("C", AlphabetIndexUtils.getIndexLetter("陈奕迅"))
    }

    /**
     * 多音字合同：pinyin4j 首读音，非词级正确读音。
     * 长 = (zhang3,chang2) → Z（"长城" 口语应为 C/chang）；
     * 重 = (zhong4,chong2) → Z（"重庆" 口语应为 C/chong）。
     * 若此测试失败，说明 pinyin4j 版本或实现的读音选择策略变了——须先确认排序规则变更是有意为之，再同步更新本合同。
     */
    @Test
    fun polyphonicCharacters_lockedToPinyin4jFirstReading() {
        assertEquals("Z", AlphabetIndexUtils.getIndexLetter("长城"))
        assertEquals("Z", AlphabetIndexUtils.getIndexLetter("重庆"))
    }

    @Test
    fun digitsSymbolsAndEmptyString_mapToHash() {
        assertEquals("#", AlphabetIndexUtils.getIndexLetter("1989"))
        assertEquals("#", AlphabetIndexUtils.getIndexLetter("~test"))
        assertEquals("#", AlphabetIndexUtils.getIndexLetter(""))
    }

    /**
     * 日文假名不在 CJK 统一表意区（0x4E00..0x9FA5），现有实现归入 #。
     */
    @Test
    fun japaneseKana_mapsToHash() {
        assertEquals("#", AlphabetIndexUtils.getIndexLetter("あいみょん"))
    }

    @Test
    fun allIndexLetters_areAToZFollowedByHash() {
        val letters = AlphabetIndexUtils.getAllIndexLetters()
        assertEquals(27, letters.size)
        assertEquals("A", letters.first())
        assertEquals("#", letters.last())
    }

    // ==================== 排序合同（现行 "ZZZ" 哨兵方案） ====================

    /**
     * 复刻各列表 ViewModel 的现行排序（如 ArtistsViewModel 第 136-140 行）：
     * sortKey = if (letter == "#") "ZZZ" else letter，按 (sortKey + name) 字符串序。
     */
    private fun sortByCurrentContract(names: List<String>): List<String> =
        names
            .map { name ->
                val letter = AlphabetIndexUtils.getIndexLetter(name)
                val sortKey = if (letter == "#") "ZZZ" else letter
                (sortKey + name) to name
            }
            .sortedBy { it.first }
            .map { it.second }

    /**
     * 纯大写拉丁语料下的期望语义：A 组在 Z 组前，# 组最后（# 组内按名称序）。
     */
    @Test
    fun sortContract_uppercaseLatinCorpus_hashGroupIsLast() {
        val sorted = sortByCurrentContract(listOf("~tilde", "ZED", "1989", "BEATLES", "ACDC"))

        assertEquals(
            "With uppercase Latin names the '#' group must sort strictly after Z",
            listOf("ACDC", "BEATLES", "ZED", "1989", "~tilde"),
            sorted
        )
    }

    /**
     * FIXME: 现行 "ZZZ" 哨兵方案下 # 组并非严格最后——反例锁定。
     *
     * 名称以原始大小写拼进排序键：Z 组条目 "Zebra" 的键 "ZZebra" 在第 3 个字符
     * 'e'(0x65) > 'Z'(0x5A)，大于 # 组键前缀 "ZZZ"；中文 Z 组条目（"周杰伦" → 键 "Z周杰伦"，
     * '周' U+5468 > 'Z'）同理。因此 # 组条目 "1989" 排在这两个 Z 组条目**之前**，
     * 违反「# 严格位于 Z 之后」的目标合同。
     *
     * 本测试以现有行为为准锁定（P0 只刻画不修改）；P3 落地 section_key/sort_key
     * 下沉 SQL 时必须改为分区键 + 名称二级排序修复本反例。
     */
    @Test
    fun sortContract_lockedCounterExample_hashSortsBeforeLowercaseAndChineseZNames() {
        val sorted = sortByCurrentContract(listOf("Zebra", "1989", "周杰伦"))

        // 现状行为：ZZZ1989 < ZZebra < Z周杰伦
        assertEquals(
            "Locked current behavior: '#' entry sorts before lowercase/Chinese Z-group names",
            listOf("1989", "Zebra", "周杰伦"),
            sorted
        )
        // 显式记录该现状违反「# 严格最后」目标合同
        assertNotEquals(
            "Documents the deviation from the target contract (# strictly last)",
            "1989",
            sorted.last()
        )
    }
}
