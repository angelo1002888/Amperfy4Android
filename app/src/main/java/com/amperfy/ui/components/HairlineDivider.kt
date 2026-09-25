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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.amperfy.ui.theme.separator

/**
 * 全仓唯一的「细线分割线」——恒 **1 物理像素实绘**，对齐 iOS UITableView separator
 *
 * 对应 iOS：`separatorStyle = .singleLine` 的系统分割线（Amperfy iOS 2.1 全仓未自定义
 * `separatorColor`，故色值取系统 `.separator`；见 `ColorExtensions.separator`）。
 *
 * ## 为什么不用 material3 的 `HorizontalDivider(thickness = Dp.Hairline)`（2026-08-10 真机报障根因）
 * 现象：dark 模式下所有分割线基本不可见，而 iOS light/dark 都清晰。**色值无错**——
 * Android `colorScheme.separator`（light `#3C3C43` @29%、dark `#545458` @60%）与 iOS 系统
 * `.separator` 一字不差。问题出在**绘制**：material3 1.3.1 的 `HorizontalDivider` 实现是
 * `Canvas(Modifier.height(thickness))` + `drawLine(strokeWidth = thickness.toPx(),
 * y = thickness.toPx() / 2)`；传 `Dp.Hairline`（= 0.dp）时该 item 占高 **0**、strokeWidth 也是
 * **0**，落入 Skia 的 hairline 语义——线画在两行交界处 y=0，抗锯齿把它上下各摊 0.5 物理像素，
 * 而相邻行容器带不透明底色（`.background(backgroundColor)`），后绘制的行盖掉线的一半，
 * 实际只剩约半强度。light 下灰线叠白底尚可辨认，dark 下 60% 灰再砍半就淹没在黑底里。
 * iOS 的 separator 是**整像素实绘**（高 1/scale pt = 1 物理像素的实体 view），同色值不同强度。
 *
 * 本组件的修法：Box 占**实体高度 1 物理像素**（不再是 0 高、不会被邻行覆盖）+ `background`
 * 整像素填充（无中心线偏移、无抗锯齿摊薄）。**修的是绘制，不是颜色**——色值仍是 iOS 系统
 * `.separator`，未做任何加深补偿。
 *
 * 用法与被替换的 `HorizontalDivider` 完全一致：inset 由调用方经 modifier 承担，例如行间线
 * `HairlineDivider(modifier = Modifier.padding(start = 16.dp))`（UITableView 默认
 * `separatorInset` = cell layoutMargins 左右值，`CommonScreenOperations.swift:41-47`）；
 * grouped 段顶/段底边界线不传 modifier，恒全宽 0/0。
 *
 * @param modifier 外部修饰符（inset padding 等）；宽度恒 `fillMaxWidth`
 * @param color 线色，默认 iOS 系统 `.separator`；个别非列表场景（如长按菜单组间线）传自有色值
 */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.separator
) {
    // 1 物理像素换算为 dp：3x 屏 ≈0.33dp、2x 屏 0.5dp——保证落到整像素而非亚像素
    val hairlineThickness = with(LocalDensity.current) { 1f.toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(hairlineThickness)
            .background(color)
    )
}
