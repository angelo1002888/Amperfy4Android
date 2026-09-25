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

import com.amperfy.utils.AlphabetIndexUtils
import java.util.Locale

/**
 * 资料库文本派生键唯一维护者。
 *
 * 三派生键 search_key / section_key / sort_key 必须与源 title/name 在同一 row upsert 中
 * **原子生成**：DTO/领域输入 → 待写 row 必须经同一工厂同时产出原字段与三键；DAO 不提供
 * 绕过本 mapper 的局部标题更新（否则会出现“显示已更新但搜索/索引仍命中旧值”的静默不一致）。
 *
 * 纯 Kotlin（JVM 可测），无 Android 依赖，便于固定语料单测。
 */
object LibraryTextKeyNormalizer {

    /** LIKE 查询统一转义字符（查询侧 P3 使用：`... LIKE :pattern ESCAPE '\'`）。 */
    const val LIKE_ESCAPE_CHAR: Char = '\\'

    /**
     * 搜索键：`lowercase(Locale.ROOT)`。
     * 查询参数须用相同标准化后再匹配。
     */
    fun searchKey(text: String): String = text.lowercase(Locale.ROOT)

    /**
     * 分区键：复用 [AlphabetIndexUtils.getIndexLetter]（单一规则源，禁止复制实现）。
     * 英文/中文拼音首字母映射 A–Z，其余为 `#`。
     */
    fun sectionKey(text: String): String = AlphabetIndexUtils.getIndexLetter(text)

    /**
     * 排序键（规范格式）：两位分区序号（A=00 … Z=25、#=99）+ `' '` + 原始 text。
     *
     * 最终查询按 `sort_key COLLATE BINARY` 再加实体身份列排序，保证：
     * - `#` 组（99）经二进制排序严格晚于 Z 组（25）；
     * - 同一分区内按原始 text 二进制序稳定排列。
     *
     * 分隔符用空格 `' '`（0x20）而非设计文档原写的 `\u0000`：SQLite TEXT 列在部分路径会把
     * 内嵌 NUL 当作字符串终止符导致截断；两位定长前缀已保证跨组顺序，分隔符为常量不影响相对序，
     * 故换成空格既安全又等价。
     */
    fun sortKey(text: String): String {
        val section = sectionKey(text)
        val partition = partitionNumber(section)
        return "$partition $text"
    }

    /**
     * LIKE 模式转义：先转义转义符自身，再转义 `%`、`_`，
     * 避免用户输入的通配符改变语义。配合 `ESCAPE '\'` 使用。
     */
    fun escapeLikePattern(text: String): String =
        text.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /** 分区序号：A=00 … Z=25，其余（`#`）=99。 */
    private fun partitionNumber(section: String): String {
        val c = section.firstOrNull()
        return if (c != null && c in 'A'..'Z') {
            "%02d".format(c - 'A')
        } else {
            "99"
        }
    }
}
