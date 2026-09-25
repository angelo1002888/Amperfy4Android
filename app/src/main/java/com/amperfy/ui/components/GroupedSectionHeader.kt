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

package com.amperfy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amperfy.ui.theme.secondaryLabel
import java.util.Locale

/**
 * 分组表（`.grouped`）的段标题 —— 对应 iOS `tableView(_:titleForHeaderInSection:)`
 * 在 `.grouped` 样式下由 UIKit 渲染出的默认段头。
 *
 * iOS 侧真值：
 * - **全大写**：UIKit 对 `.grouped` 表的 `titleForHeaderInSection` 文本自动大写，
 *   各 VC 传入的是普通大小写字面量（如 GenreDetailVC / ArtistDetailVC 的 "Albums"/"Songs"），
 *   屏幕上呈现为 "ALBUMS"/"SONGS"
 * - **段头总高 40pt**：`CommonScreenOperations.tableSectionHeightLarge = 40`
 *   （CommonScreenOperations.swift:54；ArtistDetailVC.swift:300-303、
 *   GenreDetailVC.swift:338-344 均按该常量返回 heightForHeaderInSection，
 *   段为空时返回 0 → Android 侧由宿主页「非空才发射本组件」表达）
 * - 文本**靠段头底部**、左缘对齐 cell layoutMargins 的 16pt
 *   （`defaultMarginCellX = 16`，CommonScreenOperations.swift:41）
 * - 颜色为次级标签灰（iOS `.secondaryLabel`）
 *
 * **不画分割线**：段头与其下首行之间的全宽边界线由宿主页自己发射
 * （见各页「列表与上方控件之间的全宽 0/0 边界线」约定），画在这里会与宿主线重叠成双线。
 *
 * 字重与字距（SemiBold / -0.08sp）沿用本项目两处段头的既有取值，未做改动。
 */
@Composable
fun GroupedSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 段头总高 40dp（iOS tableSectionHeightLarge）
            .height(40.dp),
        // 文本靠底：iOS grouped 段头文字贴近段首行
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            // UIKit 对 grouped 段头文本自动大写；Locale.ROOT 避免土耳其语等区域的 i/İ 变换
            text = title.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.08).sp
            ),
            color = MaterialTheme.colorScheme.secondaryLabel,  // iOS .secondaryLabel
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 左缘 16dp = cell layoutMargins（defaultMarginCellX）；底部留 7dp 呼吸
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp)
        )
    }
}
