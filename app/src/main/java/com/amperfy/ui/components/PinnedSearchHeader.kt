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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.AmperfyIcons

/**
 * 钉顶搜索头（对齐 iOS UISearchController `hidesNavigationBarDuringPresentation = true`，Amperfy 未改此默认值）：
 *
 * iOS 依据：点击页内搜索栏激活 UISearchController 时，导航栏（含大标题）随之收起，搜索栏 + 取消钮
 * （iOS 26 为圆形 X）被钉在页面最顶部，列表在其下方滚动；点 X 退出后导航栏恢复，搜索栏再按
 * `hidesSearchBarWhenScrolling` 规则回到隐藏态。本组件负责「激活期间钉在页面最顶」的那一行 UI。
 *
 * 布局：
 * - Row（垂直居中，水平 16dp / 竖直 6dp 内边距）：
 *   - 左：[LibrarySearchBar]（weight 1f，挂 [focusRequester] / [onFocusChanged]）；
 *   - 12dp 间距；
 *   - 右：48dp 玻璃圆钮 X（样式参照 FloatingTabBar.kt 中 SearchBottomBar 的关闭钮：
 *     shadow 8dp CircleShape + surface 0.92 背景 + Close 图标 24dp tint primary），点击 [onClose]。
 * - 可选 [scopeContent]（All/Cached 作用域段）渲染于搜索行下方。
 *
 * 顶部 inset（状态栏）由外层内容区统一处理——列表页在 MainScreen 内容区经
 * windowInsetsPadding(systemBars) 承担；Scaffold 详情页于隐藏 topBar 后由各页自行处理（见接入说明）。
 * 因此组件内不加 statusBarsPadding。
 */
@Composable
fun PinnedSearchHeader(
    searchText: String,
    onSearchTextChanged: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    // 可选：All/Cached scope 段，渲染于搜索行下方（有作用域的页面把 scope 组件挪进此插槽）
    scopeContent: (@Composable () -> Unit)? = null,
    // 钉顶头底色：默认页面底色；弹层内的调用点须传所在 sheet 的 elevated 底色，
    // 否则滚动内容从钉顶头下方经过时会露出色差
    backgroundColor: Color = MaterialTheme.colorScheme.background
) {
    // 页面底色，保证钉顶头不透出其下滚动内容（对齐 iOS 导航栏收起后搜索栏铺满顶部）
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 中：搜索输入条（复用 LibrarySearchBar，占剩余宽度）
            LibrarySearchBar(
                searchText = searchText,
                onSearchTextChanged = onSearchTextChanged,
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
                horizontalPadding = 0.dp,
                verticalPadding = 0.dp,
                focusRequester = focusRequester,
                onFocusChanged = onFocusChanged
            )
            Spacer(modifier = Modifier.width(12.dp))
            // 右：X 关闭钮（样式参照 SearchBottomBar 关闭钮）——点击退出搜索
            val glassColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(glassColor)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    // 与搜索栏内联清空钮同族（收敛「形异 + 内部不一致」）
                    imageVector = AmperfyIcons.xmarkCircleFill,
                    contentDescription = "Close Search",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        // 可选作用域段（All/Cached），显示条件由调用方在传入 scopeContent 前决定
        scopeContent?.invoke()
    }
}
