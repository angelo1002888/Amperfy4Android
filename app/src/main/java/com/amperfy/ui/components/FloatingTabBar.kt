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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.amperfy.ui.navigation.BottomTab
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label

// 底部栏三态（expanded / minimized / searchActive）的 UI 均归本文件：
// 前两态由 [FloatingTabBar] 承担，searchActive 激活态底部搜索条由 [SearchBottomBar] 承担。

/**
 * iOS 26 悬浮式底部标签栏（对齐 TabBarVC：searchTab 独立圆形按钮 + homeTab/libraryGroup 胶囊，
 * `tabBarMinimizeBehavior = .onScrollDown` 的最小化观感）。
 *
 * 布局（expanded 态，[minimizeProgress] = 0）：
 * - 左侧胶囊（weight 1f，高 56dp，全圆角 RoundedCornerShape(50) + 8dp 阴影 + surface 0.92 玻璃背景）
 *   内含 Home / Library 两项均分（图标 26dp + 紧凑 10sp 标签），选中项带完整胶囊高亮底并随选中态滑动，
 *   支持在两项区域内横向滑动切换选中（对齐 iOS 26 UITabBar 液态玻璃选中胶囊 + 滑动选中行为）
 * - 12dp 间距
 * - 右侧独立 56dp 圆形 Search 按钮（同款玻璃 + 阴影）
 *
 * 最小化（[minimizeProgress] → 1，对齐 TabBarVC.tabBarMinimizeBehavior = .onScrollDown）：
 * - 左侧 Home/Library 胶囊淡出并轻微缩放消失
 * - Search 圆常驻不消失（对齐 iOS UISearchTab 独立搜索钮始终可见），尺寸随收缩从 56dp 收到 48dp，
 *   全程可点击（minimized 态点它即激活搜索 = onTabSelected(SEARCH)）
 * - 左下角 48dp 小圆钮淡入（对齐 iOS 收缩态最左侧当前 tab 圆钮，显示当前选中 tab 的图标），
 *   点击 [onExpand] 恢复展开
 *
 * 搜索激活（[searchActive] = true，对齐 iOS UISearchTab 激活态）：本组件不渲染任何可见图层
 * （底部搜索条由 MainScreen 浮层承接），但**保持固定占位高度 [TAB_BAR_HEIGHT]** 使 Scaffold 布局不动。
 *
 * 容器高度固定为 expanded 占位高度（[TAB_BAR_HEIGHT]），最小化只在容器内部做视觉动画，
 * 不改变 Scaffold content padding，避免列表 reflow（对齐 UITabAccessory 固定占位）。
 */
@Composable
fun FloatingTabBar(
    selectedTab: BottomTab,
    onTabSelected: (BottomTab) -> Unit,
    minimizeProgress: Float,
    onExpand: () -> Unit,
    // 搜索激活态（selectedTab == SEARCH）：为 true 时隐藏全部图层，底部搜索条改由 MainScreen 浮层渲染，
    // 但仍占据 TAB_BAR_HEIGHT 保持 Scaffold 布局稳定（对齐 iOS UISearchTab 激活态）
    searchActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    // expanded/minimized 的交互门限：过渡到一半后切换可点击的图层，避免叠放图层误触
    val expandedInteractive = minimizeProgress < 0.5f
    val minimizedInteractive = minimizeProgress >= 0.5f

    val glassColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TAB_BAR_HEIGHT)
    ) {
        // 搜索激活时本组件让位给 MainScreen 底部搜索条浮层，仅保留占位高度
        if (searchActive) return@Box

        // ===== 左侧 Home/Library 胶囊层（仅此层随 minimize 淡出缩放；Search 圆已拆为常驻元素）=====
        // end 让位常驻 Search 圆（20dp 右边距 + 56dp 圆 + 12dp 间距 = 88dp）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 88.dp, bottom = 6.dp)
                .graphicsLayer {
                    // 淡出 + 轻微缩放消失
                    alpha = 1f - minimizeProgress
                    val s = 1f - 0.15f * minimizeProgress
                    scaleX = s
                    scaleY = s
                }
        ) {
            // 左侧胶囊（Home/Library 两项）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CAPSULE_HEIGHT)
                    .shadow(8.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(glassColor)
            ) {
                // 内层留 4dp 内边距：选中高亮胶囊铺满单项区域但四周略留边（对齐 iOS 选中态）
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(CAPSULE_INNER_PADDING)
                        // 统一的滑动/点按选中手势：按下即选中所在半区，拖动跨中线即切换（去抖），
                        // 抬手确定——对齐 iOS 26 tab bar 手指横滑选中行为；minimized 时不响应
                        .pointerInput(expandedInteractive) {
                            if (!expandedInteractive) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val half = size.width / 2f
                                var current =
                                    if (down.position.x < half) BottomTab.HOME else BottomTab.LIBRARY
                                onTabSelected(current)
                                // 拖动过程中每次移动重算半区，跨越中线即切换（仅当目标不同才回调）
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    val target =
                                        if (change.position.x < half) BottomTab.HOME else BottomTab.LIBRARY
                                    if (target != current) {
                                        current = target
                                        onTabSelected(target)
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    val slotWidth = maxWidth / 2f
                    // 选中指示条：完整胶囊高亮底（半圆+矩形+半圆），x 偏移随选中态弹性滑动
                    val indicatorX by animateDpAsState(
                        targetValue = if (selectedTab == BottomTab.HOME) 0.dp else slotWidth,
                        animationSpec = spring(
                            dampingRatio = 0.8f,
                            stiffness = 500f
                        ),
                        label = "capsuleIndicatorX"
                    )
                    // 仅在 Home/Library 之一被选中时才画高亮底（其余 tab 选中时不显示）
                    if (selectedTab == BottomTab.HOME || selectedTab == BottomTab.LIBRARY) {
                        Box(
                            modifier = Modifier
                                .offset(x = indicatorX)
                                .width(slotWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                )
                        )
                    }
                    // 两项内容叠放在高亮底之上（纯展示，无各自点击——由容器手势统一处理）
                    Row(modifier = Modifier.fillMaxSize()) {
                        CapsuleTabItem(
                            tab = BottomTab.HOME,
                            selected = selectedTab == BottomTab.HOME,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        CapsuleTabItem(
                            tab = BottomTab.LIBRARY,
                            selected = selectedTab == BottomTab.LIBRARY,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }

        // ===== 常驻 Search 圆（对齐 iOS UISearchTab 独立搜索钮，不随 minimize 消失）=====
        // 尺寸随 minimize 从 56dp 收到 48dp（与收缩行高一致），全程可点击；minimized 态点它即激活搜索。
        val searchSize = lerp(CAPSULE_HEIGHT, MINIMIZED_BUTTON_SIZE, minimizeProgress)
        val searchSelected = selectedTab == BottomTab.SEARCH
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 6.dp)
                .size(searchSize)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(glassColor)
                .clickable { onTabSelected(BottomTab.SEARCH) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = BottomTab.SEARCH.icon,
                contentDescription = BottomTab.SEARCH.title,
                // 未选中态用 .label（浅色黑/深色纯白），与 LibrarySearchBar 放大镜同一动态色，
                // 非次级灰（iOS 实机实证 2026-08-09）；选中态仍为主题色。
                tint = if (searchSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.label,
                modifier = Modifier.size(24.dp)
            )
        }

        // ===== minimized 图层：左下角 48dp 小圆钮（对齐 iOS 收缩态最左侧当前 tab 圆钮，
        // 显示当前选中 tab 图标），点击恢复 =====
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 6.dp)
                .size(MINIMIZED_BUTTON_SIZE)
                .graphicsLayer {
                    alpha = minimizeProgress
                    val s = 0.85f + 0.15f * minimizeProgress
                    scaleX = s
                    scaleY = s
                }
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(glassColor)
                .clickable(enabled = minimizedInteractive) { onExpand() },
            contentAlignment = Alignment.Center
        ) {
            // 图标加大到 26dp（对齐用户反馈：圆内图标太小空隙太大）
            Icon(
                imageVector = selectedTab.icon,
                contentDescription = selectedTab.title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * 搜索激活态底部搜索条（对齐 iOS UISearchTab 激活态：searchBar 承载输入框）。
 *
 * 由 MainScreen 在 selectedTab == SEARCH 时以浮层渲染（[FloatingTabBar] 此时让位仅保留占位高度）。
 *
 * 注：作用域选择（All / Cached）已迁至 SearchScreen 页面顶部渲染（对齐 iOS 实机：
 * searchBar.scopeButtonTitles 的 scope 栏位于页面顶部而非底部输入条内），本组件只承载输入条一行。
 *
 * 单行（Row），从左到右「来源 tab 圆钮 + 搜索输入条 + 关闭钮」：
 *   - 左：48dp 玻璃圆钮（surface 0.92 + 8dp 阴影 CircleShape），显示 [returnTab] 图标（26dp，tint primary），
 *     点击 [onReturnToTab] 返回来源 tab；
 *   - 中：weight(1f) 搜索输入条，复用 [LibrarySearchBar]（玻璃胶囊 + 8dp 阴影 RoundedCornerShape(24)）；
 *   - 右：48dp 玻璃圆钮，关闭图标（24dp，tint primary），点击 [onClose] 清空并退出搜索。
 *
 * 键盘态联动（对齐 iOS：键盘弹出时输入条浮在键盘上、其左侧无任何按钮）：
 * - 经 imePadding 浮在键盘上方；
 * - 键盘可见（[WindowInsets.isImeVisible]）时**隐藏左侧来源 tab 圆钮**（含其后 12dp 间距，
 *   AnimatedVisibility 淡入淡出 + 横向展开/收起），键盘上方只剩输入条 + X；
 * - 键盘收起时恢复「返回圆钮 + 输入条 + X」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchBottomBar(
    returnTab: BottomTab,
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onReturnToTab: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glassColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    // 键盘可见时隐藏左侧返回圆钮（对齐 iOS：键盘上方输入条左侧无按钮）
    val imeVisible = WindowInsets.isImeVisible
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 6.dp)
    ) {
        // 单行：返回圆钮（键盘态隐藏） + 输入条 + X
        // （作用域 All/Cached 已迁至 SearchScreen 页面顶部渲染，见 SearchScreen 的 SearchScopeSelector）
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左：来源 tab 小圆钮（点击返回该 tab，退出搜索）；键盘弹起时连同其后 12dp 间距一起收起
            AnimatedVisibility(
                visible = !imeVisible,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(glassColor)
                            .clickable { onReturnToTab() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = returnTab.icon,
                            contentDescription = returnTab.title,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }
            // 中：搜索输入条（占剩余宽度，复用 LibrarySearchBar，玻璃胶囊 + 8dp 阴影，约 48dp 高）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .shadow(8.dp, RoundedCornerShape(24.dp))
            ) {
                LibrarySearchBar(
                    searchText = query,
                    onSearchTextChanged = onQueryChange,
                    placeholder = "Search in \"Library\"",
                    backgroundColor = glassColor,
                    cornerRadius = 24.dp,
                    horizontalPadding = 0.dp,
                    verticalPadding = 0.dp,
                    // 内边距抬高至 14dp 使输入条高度贴近左右 48dp 圆钮
                    contentVerticalPadding = 14.dp,
                    focusRequester = focusRequester
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // 右：X 关闭钮（清空查询并返回来源 tab）
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(glassColor)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AmperfyIcons.xmark,
                    contentDescription = "Close Search",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * 胶囊内单个标签项（纯展示，无点击——由外层胶囊统一手势处理）：图标 26dp + 下方紧凑 10sp 标签；
 * 选中 tint = primary + SemiBold，未选中 onSurfaceVariant；整体垂直居中紧凑排布。
 */
@Composable
private fun CapsuleTabItem(
    tab: BottomTab,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.title,
            tint = tint,
            modifier = Modifier.size(26.dp)
        )
        // 收紧图标与标签间距（对齐 iOS 26 紧凑排布）
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = tab.title,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint
        )
    }
}

// 胶囊高度（iOS 26 悬浮胶囊）
private val CAPSULE_HEIGHT = 56.dp
// 胶囊内边距：选中高亮胶囊四周留边（高亮底高 = 56 - 2*4 = 48dp）
private val CAPSULE_INNER_PADDING = 4.dp
// 底部栏容器固定占位高度 = 胶囊 56dp + 底部 6dp（最小化仅内部动画，占位不变）
private val TAB_BAR_HEIGHT = 62.dp
// 最小化态左下小圆钮尺寸
private val MINIMIZED_BUTTON_SIZE = 48.dp
