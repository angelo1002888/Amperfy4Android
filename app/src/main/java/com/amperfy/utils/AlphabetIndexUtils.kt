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

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * 字母索引工具类
 * 用于将汉字、英文等字符统一转换为A-Z的索引字母
 */
object AlphabetIndexUtils {

    /**
     * 获取字符串的索引字母
     * - 英文字母：返回大写字母 A-Z
     * - 汉字：返回拼音首字母 A-Z
     * - 其他字符：返回 #
     *
     * @param text 输入字符串
     * @return 索引字母 (A-Z 或 #)
     */
    fun getIndexLetter(text: String): String {
        if (text.isEmpty()) return "#"

        val firstChar = text.first()

        return when {
            // 英文字母 A-Z 或 a-z
            firstChar in 'A'..'Z' -> firstChar.toString()
            firstChar in 'a'..'z' -> firstChar.uppercaseChar().toString()

            // 汉字：转换为拼音首字母
            firstChar.code in 0x4E00..0x9FA5 -> {
                getChinesePinyinFirstLetter(firstChar)
            }

            // 其他字符：返回 #
            else -> "#"
        }
    }

    /**
     * 获取汉字的拼音首字母
     * 使用 pinyin4j 库
     *
     * @param char 汉字字符
     * @return 拼音首字母 (A-Z) 或 # (如果无法转换)
     */
    private fun getChinesePinyinFirstLetter(char: Char): String {
        return try {
            val format = HanyuPinyinOutputFormat().apply {
                caseType = HanyuPinyinCaseType.UPPERCASE
                toneType = HanyuPinyinToneType.WITHOUT_TONE
                vCharType = HanyuPinyinVCharType.WITH_V
            }

            val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(char, format)

            if (pinyinArray != null && pinyinArray.isNotEmpty()) {
                // 取第一个拼音的首字母
                val pinyin = pinyinArray[0]
                if (pinyin.isNotEmpty()) {
                    pinyin.first().toString()
                } else {
                    "#"
                }
            } else {
                "#"
            }
        } catch (e: Exception) {
            "#"
        }
    }

    /**
     * 获取完整的索引字母列表 (A-Z + #)
     * 按字母顺序排列，# 在最后
     */
    fun getAllIndexLetters(): List<String> {
        return ('A'..'Z').map { it.toString() } + "#"
    }
}
