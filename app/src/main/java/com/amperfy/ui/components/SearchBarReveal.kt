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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 资料库/详情页搜索栏的**唯一行为实现点** —— 对应 iOS 基类
 * `BasicTableViewController.configureSearchController(placeholder:scopeButtonTitles:searchResultsUpdater:)`
 * （BasicTableViewController.swift:213-238；集合视图侧为 BasicCollectionViewController.swift:69）。
 *
 * iOS 全仓只有这一处配置，`navigationItem.hidesSearchBarWhenScrolling = true` 恒定
 * （BasicTableViewController.swift:227）。**全部页面同一套行为，各页不写任何行为代码**：
 *
 * 1. **进入即隐藏** —— iOS 侧无任何强制初始展开的代码（全仓唯一 `becomeFirstResponder`
 *    在 SearchVC 的用户主动入口），这是 `hidesSearchBarWhenScrolling = true` 的默认呈现，
 *    并经真机实证（Albums/Artists 等页进入时搜索栏不可见，下拉才出现）。
 *    **与 `prefersLargeTitles` 无关**——后者只决定标题形态（大标题/导航栏小标题），
 *    在本项目由各页自己的 `IOSLargeTitle` / `IOSNavTopBar` 表达。
 * 2. **到顶继续下拉 → 出现；上滑 → 收起**（收起仅在内容可滚动时发生，见 3）。
 * 3. **内容不足一屏时不做任何自动展开** —— iOS 侧因「收起靠滚动位移」，列表滚不动就收不起来，
 *    故短页搜索栏恒可见（真机实证 Directories 一级 1-3 行）。**Android 刻意不复刻这一条**
 *    （2026-08-09 起）：全部页面统一「进入隐藏 → 下拉唤出 → 上滑收起」，短页与
 *    慢加载页一律不自动展开。此前按 iOS 语义实现的「短内容常驻」曾使 Artists/Albums/
 *    AlbumDetail/NewestAlbums/RecentlyPlayed 进入页面搜索栏就展开（首帧数据未从 Room 到位、
 *    LazyColumn 为空，两个方向同样滚不动而被误判为短内容），该路径已整体拆除。
 *    唯一保留的可滚动性用途：**上滑收起加「内容可滚动」门控**——短页
 *    rubber-band 上滑不得收起用户拉出的搜索栏，这条仍对齐 UIKit「收起靠滚动位移」的机制。
 *
 * 搜索激活（钉顶 / 有输入 / 输入框持有焦点）期间搜索栏锁定可见，滚动不收起
 * （UIKit `searchController.isActive` 行为）。
 *
 * 下拉交接机制（2026-07 起）：以 [NestedScrollConnection.onPostScroll] 观察到顶下拉量。
 * 对齐 iOS——搜索栏显现先消费下拉的前一段行程（搜索栏高约 52pt），显现完成后继续下拉才驱动
 * 下拉刷新（两者串行、不并行）。故本连接在「交接阶段」消费这段下拉量，使嵌套链上外层的
 * pullToRefresh 收不到；累计过 [HANDOFF_TRAVEL_PX] 后不再消费。收起（上滑）与搜索激活分支
 * 一律不消费，与底部栏收缩（BottomBarState）联动互不干扰。
 *
 * **页面只提供数据**：占位文案、scope 段内容、搜索词与其回调。行为（显隐、钉顶切换、焦点）
 * 全部在本文件内。
 *
 * @see rememberLibrarySearchState 创建入口
 * @see librarySearchBarItem 列表内搜索栏（LazyColumn 场景，恰好发射一个 item）
 * @see LibrarySearchState.SearchBarSlot 列表外搜索栏（普通 Column 场景，如弹层）
 * @see LibrarySearchState.PinnedHeader 钉顶搜索头（topBar 槽）
 */
@Stable
class LibrarySearchState internal constructor() {
    /**
     * 搜索条当前是否展开可见；只由本类内部的滚动/钉顶逻辑改写。
     * 初始 `false` —— 全部页面「进入即隐藏」（见类 KDoc 第 1 条）。
     */
    var revealed by mutableStateOf(false)
        private set

    /**
     * 钉顶态 —— 对应 iOS `UISearchController.hidesNavigationBarDuringPresentation = true`：
     * 搜索框获得焦点即钉到内容最顶、导航栏（大标题/标题行）收起；只有点 X 才退出。
     */
    var isPinned by mutableStateOf(false)
        private set

    /** 输入框是否持有焦点（激活判定的一支） */
    var isFocused by mutableStateOf(false)
        private set

    /** 钉顶头输入框的焦点请求器：钉顶瞬间自动聚焦（对齐 iOS becomeFirstResponder） */
    val focusRequester = FocusRequester()

    /**
     * 搜索激活态的滚动侧镜像。滚动回调在**手势线程**读取，不需要 snapshot 语义，
     * 故用普通 var（由渲染件每次重组同步，与旧实现同一手法）。
     */
    private var searchActiveForScroll: Boolean = false

    /**
     * 内容是否可滚动的滚动侧镜像（由 [rememberLibrarySearchState] 的 snapshotFlow 写入）。
     * **唯一用途是上滑收起的门控**：内容滚不动的页面（rubber-band 过滚）不允许收起搜索栏——
     * 对齐 UIKit：收起靠滚动位移，滚不动就收不起来。
     * 无 listState 可观察时保持 `true`（退回「可收起」的通用行为）。
     */
    private var contentScrollable: Boolean = true

    // 到顶继续下拉的累计量（越过阈值即显示）
    private var pullAcc = 0f
    // 实际向上滚动的累计量（越过阈值即收起）
    private var hideAcc = 0f
    // 搜索栏显现阶段已消费的下拉行程累计（越过 HANDOFF_TRAVEL_PX 后不再消费、交给下拉刷新）
    private var handoffAcc = 0f

    private fun resetAccumulators() {
        pullAcc = 0f
        hideAcc = 0f
        handoffAcc = 0f
    }

    /** 渲染件每次重组同步：激活 = 钉顶 or 持有焦点 or 有搜索词 */
    internal fun syncActive(searchText: String) {
        searchActiveForScroll = isPinned || isFocused || searchText.isNotEmpty()
    }

    /** 渲染件判定可见性用（与 [syncActive] 同一公式，但走 snapshot 读以驱动重组） */
    internal fun isActive(searchText: String): Boolean =
        isPinned || isFocused || searchText.isNotEmpty()

    /** 输入框焦点变化：获得焦点即钉顶（对齐 iOS 导航栏收起） */
    internal fun onFocusChanged(focused: Boolean) {
        isFocused = focused
        if (focused) isPinned = true
    }

    /**
     * 退出钉顶搜索（钉顶头的 X）：回到隐藏态（与进入态一致，`hidesSearchBarWhenScrolling` 语义），
     * 之后同样只能由下拉重新唤出。
     */
    internal fun close() {
        isPinned = false
        isFocused = false
        revealed = false
        resetAccumulators()
    }

    /**
     * 内容可滚动性变化：只更新 [contentScrollable] 镜像，**不改搜索栏显隐**。
     *
     * 搜索栏的展开一律由用户下拉驱动（见类 KDoc 第 3 条：Android 不复刻 iOS 的短内容常驻）；
     * 本镜像仅供上滑收起的门控使用。
     */
    internal fun onContentScrollableChanged(scrollable: Boolean) {
        contentScrollable = scrollable
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            // 搜索激活时锁定显示，忽略一切滚动信号并清零交接累计；不消费 → 直接驱动下拉刷新
            if (searchActiveForScroll) { resetAccumulators(); return Offset.Zero }
            if (available.y > 0f && source == NestedScrollSource.UserInput) {
                // 列表已到顶仍在下拉（列表消费不了的下拉量）→ 累计过阈值即显示
                hideAcc = 0f
                pullAcc += available.y
                if (pullAcc >= REVEAL_THRESHOLD_PX) { revealed = true; pullAcc = 0f }
                // 与下拉刷新串行交接：交接未完成前消费这段下拉行程（约 52pt），使外层 pullToRefresh
                // 收不到 → 刷新不启动；交接完成后不再消费，后续行程交给刷新。
                if (handoffAcc < HANDOFF_TRAVEL_PX) {
                    handoffAcc += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            } else if (consumed.y < 0f && contentScrollable) {
                // 实际发生了向上滚动 → 累计过阈值即收起，同时清零交接累计。
                // `contentScrollable` 门控：短内容页（滚不动）rubber-band 上滑不得收起搜索栏，
                // 对齐 UIKit「收起靠滚动位移」的机制（真机实证 Directories 一级）。
                pullAcc = 0f
                handoffAcc = 0f
                hideAcc += -consumed.y
                if (hideAcc >= HIDE_THRESHOLD_PX && revealed) { revealed = false; hideAcc = 0f }
            }
            return Offset.Zero  // 收起/其它路径不消费——不干扰下拉刷新与底部栏收缩联动
        }
    }

    companion object {
        private const val REVEAL_THRESHOLD_PX = 50f
        private const val HIDE_THRESHOLD_PX = 10f
        // 交接行程：搜索栏高约 52pt（≈52dp @3x ≈ 150px）；经验值，待真机校准
        private const val HANDOFF_TRAVEL_PX = 150f
    }
}

/**
 * 创建搜索栏状态（每页一份）。**无页面级参数**——全部页面同一套行为（见 [LibrarySearchState] KDoc）。
 *
 * @param listState 承载搜索栏的列表状态；用于维护上滑收起门控所需的「内容可滚动」镜像。
 *   传 null 时该镜像恒为 `true`（上滑一律可收起）。
 */
@Composable
fun rememberLibrarySearchState(
    listState: LazyListState? = null
): LibrarySearchState {
    val state = remember { LibrarySearchState() }

    // 钉顶瞬间自动聚焦（对齐 iOS searchController 激活即 becomeFirstResponder）
    LaunchedEffect(state.isPinned) {
        if (state.isPinned) state.focusRequester.requestFocus()
    }

    // 上滑收起门控用的可滚动性镜像：列表两个方向都滚不动 = 内容不足一屏，此时 rubber-band
    // 上滑不得收起搜索栏（对齐 UIKit「收起靠滚动位移」）。不参与任何自动展开。
    if (listState != null) {
        LaunchedEffect(state, listState) {
            snapshotFlow { listState.canScrollForward || listState.canScrollBackward }
                .distinctUntilChanged()
                .collect { state.onContentScrollableChanged(it) }
        }
    }

    return state
}

/**
 * 列表内搜索栏 —— **恰好发射一个 item**（key = "searchBar"）。
 *
 * 各页 [AlphabetIndex] 的 `leadingItemCount` 契约据此保持不变：本函数占且只占一个前置 item。
 *
 * @param scopeContent All/Cached 作用域段（iOS `scopeButtonTitles`）。仅搜索激活时显示，
 *   对齐 iOS scope buttons 的默认行为；无作用域的页传 null。
 */
fun LazyListScope.librarySearchBarItem(
    state: LibrarySearchState,
    searchText: String,
    onSearchTextChanged: (String) -> Unit,
    placeholder: String,
    scopeContent: (@Composable () -> Unit)? = null,
    searchBarModifier: Modifier = Modifier,
    horizontalPadding: Dp = SEARCH_BAR_DEFAULT_H_PADDING,
    verticalPadding: Dp = SEARCH_BAR_DEFAULT_V_PADDING,
    bottomSpacing: Dp = 0.dp
) {
    item(key = "searchBar") {
        state.SearchBarSlot(
            searchText = searchText,
            onSearchTextChanged = onSearchTextChanged,
            placeholder = placeholder,
            scopeContent = scopeContent,
            searchBarModifier = searchBarModifier,
            horizontalPadding = horizontalPadding,
            verticalPadding = verticalPadding,
            bottomSpacing = bottomSpacing
        )
    }
}

/** LibrarySearchBar 的默认内边距（与其形参默认值一致，此处显式列出以便逐页覆盖） */
val SEARCH_BAR_DEFAULT_H_PADDING = 12.dp
val SEARCH_BAR_DEFAULT_V_PADDING = 6.dp

/**
 * 搜索栏本体（列表外场景直接调用，如弹层内的普通 Column）。
 *
 * 显隐：`(revealed 或 搜索激活) 且 未钉顶`——钉顶时改由 [PinnedHeader] 承载。
 * 首次组合恒为隐藏态（全部页面「进入即隐藏」），故不播放入场动画，只在其后的变化上过渡。
 */
@Composable
fun LibrarySearchState.SearchBarSlot(
    searchText: String,
    onSearchTextChanged: (String) -> Unit,
    placeholder: String,
    scopeContent: (@Composable () -> Unit)? = null,
    searchBarModifier: Modifier = Modifier,
    // 少数页对搜索框内边距/尾部留白有页面级排版要求（属数据不属行为），故开放覆盖
    horizontalPadding: Dp = SEARCH_BAR_DEFAULT_H_PADDING,
    verticalPadding: Dp = SEARCH_BAR_DEFAULT_V_PADDING,
    bottomSpacing: Dp = 0.dp
) {
    val active = isActive(searchText)
    // 同步给滚动回调（普通 var，非 snapshot；写在组合期与旧实现同手法）
    syncActive(searchText)

    AnimatedVisibility(
        visible = (revealed || active) && !isPinned,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LibrarySearchBar(
                searchText = searchText,
                onSearchTextChanged = onSearchTextChanged,
                placeholder = placeholder,
                modifier = searchBarModifier,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
                // 获得焦点即钉顶（导航栏收起）；只有钉顶头的 X 才退出钉顶
                onFocusChanged = { onFocusChanged(it) }
            )
            if (bottomSpacing > 0.dp) Spacer(Modifier.height(bottomSpacing))
            // scope 段仅搜索激活时显示（iOS scope buttons 默认行为）
            if (scopeContent != null && active) scopeContent()
        }
    }
}

/**
 * 钉顶搜索头（页面 topBar 槽）。典型写法：
 * ```
 * topBar = {
 *     if (searchState.isPinned) searchState.PinnedHeader(searchText, onSearchTextChanged, placeholder)
 *     else IOSNavTopBar(...)
 * }
 * ```
 *
 * X 的语义与旧各页实现一致：清空搜索词 + 退出钉顶 + 复位为隐藏（此后只能由下拉重新唤出）。
 *
 * @param withStatusBarPadding 顶到屏幕最上时需避让状态栏（页面场景）；弹层内顶边已止于
 *   状态栏下缘，传 false。
 */
@Composable
fun LibrarySearchState.PinnedHeader(
    searchText: String,
    onSearchTextChanged: (String) -> Unit,
    placeholder: String,
    scopeContent: (@Composable () -> Unit)? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    withStatusBarPadding: Boolean = true
) {
    syncActive(searchText)
    val header = @Composable {
        PinnedSearchHeader(
            searchText = searchText,
            onSearchTextChanged = onSearchTextChanged,
            placeholder = placeholder,
            focusRequester = focusRequester,
            onFocusChanged = { onFocusChanged(it) },
            onClose = {
                onSearchTextChanged("")
                close()
            },
            scopeContent = scopeContent,
            backgroundColor = backgroundColor
        )
    }
    if (withStatusBarPadding) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .statusBarsPadding()
        ) { header() }
    } else {
        header()
    }
}
