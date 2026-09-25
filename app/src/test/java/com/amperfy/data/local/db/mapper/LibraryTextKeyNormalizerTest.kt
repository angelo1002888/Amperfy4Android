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

package com.amperfy.data.local.db.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LibraryTextKeyNormalizer] 三派生键固定语料合同。
 *
 * 语料预期值推导依据：
 * - section_key 直接委托 [com.amperfy.utils.AlphabetIndexUtils.getIndexLetter]，其现有行为已由
 *   `AlphabetIndexContractTest` 锁定（纯 JVM，pinyin4j 2.5.1 直接可用）。本测试的中文/多音字预期值
 *   与该合同一致，不重复推导 pinyin4j：
 *     · 张学友 → 张 U+5F20 (zhang1) → Z；
 *     · 长城 → 长 U+957F = (zhang3,chang2)，pinyin4j **首读音** zhang3 → Z
 *       （口语「长城 Changcheng」应为 C，但当前实现不做词级消歧——行为刻画，非正确性背书，
 *        与 AlphabetIndexContractTest.polyphonicCharacters_lockedToPinyin4jFirstReading 一致）。
 * - AlphabetIndexUtils 只特判 ASCII a-z/A-Z 与 CJK 统一表意区 (0x4E00..0x9FA5)，其余归 `#`：
 *     · 数字开头 "1989" → #；符号开头 "~test" → #；空串 → #；
 *     · 组合字符：预组合 NFC U+00E9 落入 #（>'z'，非 CJK）；分解 NFD 首字符是 ASCII 'e' → E。
 * - sort_key = 两位分区序号（A=00…Z=25、#=99）+ ' ' + 原始 text；按 COLLATE BINARY 排序时
 *   99 段严格晚于 25 段，同段内按原始 text 二进制序。
 * - search_key = lowercase(Locale.ROOT)；escapeLikePattern 先转义 `\` 再转义 `%`/`_`。
 */
class LibraryTextKeyNormalizerTest {

    private val n = LibraryTextKeyNormalizer

    // ==================== sectionKey ====================

    @Test
    fun sectionKey_asciiMixedCase_mapsToUppercaseLetter() {
        assertEquals("A", n.sectionKey("aBcD"))
        assertEquals("A", n.sectionKey("Apple"))
        assertEquals("Z", n.sectionKey("zebra"))
    }

    @Test
    fun sectionKey_chineseAndPolyphonic_matchAlphabetIndexContract() {
        assertEquals("Z", n.sectionKey("张学友")) // 张学友
        // 多音字 长城：pinyin4j 首读音（长 → zhang3），与 AlphabetIndexContractTest 锁定一致
        assertEquals("Z", n.sectionKey("长城")) // 长城
    }

    @Test
    fun sectionKey_digitSymbolEmpty_mapToHash() {
        assertEquals("#", n.sectionKey("1989"))
        assertEquals("#", n.sectionKey("~test"))
        assertEquals("#", n.sectionKey(""))
    }

    @Test
    fun sectionKey_combiningChar_nfcVsNfdDiffer() {
        // 用显式转义区分 NFC/NFD（源文件直接键入的字符无法保证规范形式）
        // 预组合 é (U+00E9)：不在 ASCII a-z、也非 CJK → #
        assertEquals("#", n.sectionKey("\u00e9"))
        // 分解 é = 'e' + U+0301：首字符是 ASCII 'e' → E
        assertEquals("E", n.sectionKey("e\u0301"))
    }

    // ==================== sortKey ====================

    @Test
    fun sortKey_partitionPrefix_isTwoDigitSectionOrdinal() {
        assertEquals("00 aBcD", n.sortKey("aBcD"))
        assertEquals("25 张学友", n.sortKey("张学友")) // 张学友
        assertEquals("25 长城", n.sortKey("长城")) // 长城
        assertEquals("99 1989", n.sortKey("1989"))
        assertEquals("99 ~test", n.sortKey("~test"))
        // 空串：分区 # → 99，分隔空格 + 空原文
        assertEquals("99 ", n.sortKey(""))
    }

    @Test
    fun sortKey_hashGroupSortsStrictlyAfterZ() {
        // 99 段严格晚于 25 段（'9' 0x39 > '2' 0x32）
        assertTrue(n.sortKey("1989") > n.sortKey("Zebra"))
        assertTrue(n.sortKey("~test") > n.sortKey("Zebra"))
    }

    @Test
    fun sortKey_sameGroupPreservesOriginalStringOrder() {
        // 同分区（A=00）内按原始 text 二进制序："Ant" < "Apple"
        assertTrue(n.sortKey("Ant") < n.sortKey("Apple"))
    }

    // ==================== searchKey ====================

    @Test
    fun searchKey_normalizesCase() {
        assertEquals("hello", n.searchKey("HeLLo"))
        assertEquals("hello world", n.searchKey("Hello World"))
        // CJK 无大小写，原样保留
        assertEquals("张学友", n.searchKey("张学友")) // 张学友
        assertEquals("", n.searchKey(""))
    }

    // ==================== escapeLikePattern ====================

    @Test
    fun escapeLikePattern_escapesWildcardsAndEscapeChar() {
        assertEquals("\\%", n.escapeLikePattern("%"))
        assertEquals("\\_", n.escapeLikePattern("_"))
        assertEquals("\\\\", n.escapeLikePattern("\\"))
        // 组合：转义符先转义，避免二次转义已插入的 '\'
        assertEquals("50\\%\\_", n.escapeLikePattern("50%_"))
        assertEquals("a\\\\b\\%c", n.escapeLikePattern("a\\b%c"))
    }
}
