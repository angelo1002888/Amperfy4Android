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

package com.amperfy.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * 底部栏收缩状态持有者（对齐 iOS TabBarVC.tabBarMinimizeBehavior = .onScrollDown）。
 *
 * 规则全局统一：任何 tab、任何导航深度的页面，内容实际向下滚动即收缩、向上滚动即展开；
 * 内容滚不动（consumed=0，如不满屏列表）不触发——与 iOS 同构，无任何按页面 / 按 tab 的例外。
 *
 * 机制说明：以 [NestedScrollConnection.onPostScroll] 的 `consumed.y`（列表实际消费的滚动量）
 * 为唯一驱动信号，而非 `available.y`。这样只有内容真的滚动了才会触发收缩 / 展开，
 * 精确复刻 iOS「内容真滚动才收缩、滚不动不反应」的语义。
 */
@Stable
class BottomBarState {
    /** 是否处于收缩态；仅内部滚动逻辑与 [expand] / [minimize] 可改写 */
    var minimized by mutableStateOf(false)
        private set

    /**
     * 搜索激活态锁定：为 true 时忽略一切滚动信号（底部栏此时为独立搜索排，
     * 不参与 onScrollDown 收缩）。简单 Boolean 即可——仅在滚动回调里读取，无需 snapshot state。
     */
    var scrollLocked: Boolean = false

    fun expand() { minimized = false }
    fun minimize() { minimized = true }

    // 累计滚动增量，方向翻转即清零；跨过阈值即翻转收缩态后归零
    private var acc = 0f

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            if (scrollLocked) { acc = 0f; return Offset.Zero }
            // 列表实际滚动量：<0 = 内容下滚（手指上滑浏览），>0 = 内容上滚（手指下滑回顶）
            val dy = consumed.y
            if (dy < 0f) {
                acc = (acc + dy).coerceAtMost(0f)
                if (acc <= -THRESHOLD_PX) { minimize(); acc = 0f }
            } else if (dy > 0f) {
                acc = (acc + dy).coerceAtLeast(0f)
                if (acc >= THRESHOLD_PX) { expand(); acc = 0f }
            }
            return Offset.Zero
        }
    }

    companion object {
        // 收缩 / 展开触发阈值（累计滚动像素，对齐 iOS onScrollDown 的方向翻转手感）
        private const val THRESHOLD_PX = 10f
    }
}

@Composable
fun rememberBottomBarState(): BottomBarState = remember { BottomBarState() }
