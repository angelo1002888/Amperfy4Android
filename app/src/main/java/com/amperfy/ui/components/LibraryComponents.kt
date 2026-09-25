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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextOverflow
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.gray4
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.placeholderText
import com.amperfy.ui.theme.secondaryLabel

/**
 * iOS 风格页面大标题（对应 UINavigationBar largeTitle）
 *
 * iOS 大标题超长时**单行自动缩小字号**而非换行（如 "Recently Played Albums"）；
 * 这里从 [fontSize]（默认 28sp）起步逐级缩小至最小 22sp，仍放不下以省略号截断。
 * 缩放收敛前不绘制（drawWithContent 守卫），避免可见的字号跳变。
 *
 * [fontSize]：起始字号（全站统一 28sp，各调用点沿用默认值）。
 */
@Composable
fun IOSLargeTitle(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 28.sp,
    // 默认 Unspecified：沿用 displaySmall 原行高（44sp），各调用点行为不变；
    // 仅需收紧字形下方行高富余的页面（如 Home）显式传入
    lineHeight: TextUnit = TextUnit.Unspecified
) {
    var currentFontSize by remember(text, fontSize) { mutableStateOf(fontSize) }
    var readyToDraw by remember(text, fontSize) { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.displaySmall.copy(
            fontSize = currentFontSize,
            fontWeight = FontWeight.Bold
        ).let { if (lineHeight != TextUnit.Unspecified) it.copy(lineHeight = lineHeight) else it },
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.didOverflowWidth && currentFontSize.value > MIN_LARGE_TITLE_FONT_SP) {
                currentFontSize = (currentFontSize.value * 0.92f).coerceAtLeast(MIN_LARGE_TITLE_FONT_SP).sp
            } else {
                readyToDraw = true
            }
        },
        modifier = modifier.drawWithContent { if (readyToDraw) drawContent() }
    )
}

private const val MIN_LARGE_TITLE_FONT_SP = 22f

/**
 * 通用搜索栏组件
 *
 * 用于 Artists, Albums, Songs 等页面
 * iOS: UISearchController
 * 使用BasicTextField以支持自定义内部padding
 *
 * @param searchText 当前搜索文本
 * @param onSearchTextChanged 搜索文本变化回调
 * @param placeholder 占位符文本
 * @param modifier 修饰符
 * @param horizontalPadding 外部水平padding
 * @param verticalPadding 外部垂直padding
 * @param contentHorizontalPadding 内部水平padding
 * @param contentVerticalPadding 内部垂直padding
 * @param cornerRadius 圆角半径；**null（默认）= 胶囊形**（两端半圆），仅在调用方另有自身
 *   设计时才显式传值（当前只有 FloatingTabBar 的玻璃搜索条传 24dp）
 * @param backgroundColor 背景颜色
 * @param textStyle 文本样式
 * @param leadingIcon 前置图标
 * @param leadingIconSize 前置图标大小
 * @param trailingIconSize 清除按钮图标大小
 * @param showClearButton 是否显示清除按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySearchBar(
    searchText: String,
    onSearchTextChanged: (String) -> Unit,
    placeholder: String = "Search",
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 6.dp,
    contentHorizontalPadding: Dp = 12.dp,
    contentVerticalPadding: Dp = 10.dp,
    cornerRadius: Dp? = null,
    backgroundColor: Color? = null,
    textStyle: TextStyle? = null,
    leadingIcon: ImageVector = AmperfyIcons.search,
    leadingIconSize: Dp = 20.dp,
    trailingIconSize: Dp = 16.dp,
    showClearButton: Boolean = true,
    /**
     * 焦点变化回调（iOS scope buttons 仅在搜索激活时显示——
     * 调用方据此控制 All/Cached 作用域行的显隐）
     */
    onFocusChanged: ((Boolean) -> Unit)? = null,
    /**
     * 可选焦点请求器：非 null 时挂到输入框，供外部主动聚焦。
     * 对应 iOS TabBarVC.automaticallyActivatesSearch —— 点击 Search tab 自动激活搜索框
     * （SearchVC.activateSearchBar() = searchBar.becomeFirstResponder()）。
     * 默认 null，其余调用点无需变动。
     */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val defaultBackgroundColor = backgroundColor ?: MaterialTheme.colorScheme.surfaceVariant
    val defaultTextStyle = textStyle ?: MaterialTheme.typography.bodyMedium
    // 形状：iOS configureSearchController（BasicTableViewController.swift:213-238）对搜索框
    // 无任何外观定制，形状是 UIKit 默认渲染、源码给不出数值——依据为 iOS 实机截图
    //（2026-08-09，浅色/深色两模式均为两端半圆的完整胶囊）。故默认胶囊；
    // 只有另有自身设计的调用方（FloatingTabBar 玻璃搜索条）才显式传圆角半径
    val searchBarShape = cornerRadius?.let { RoundedCornerShape(it) }
        ?: RoundedCornerShape(percent = 50)

    BasicTextField(
        value = searchText,
        onValueChange = onSearchTextChanged,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .then(
                // 挂焦点请求器（非 null 时）——供外部主动 requestFocus 激活搜索框
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .then(
                if (onFocusChanged != null) {
                    Modifier.onFocusChanged { onFocusChanged(it.isFocused) }
                } else {
                    Modifier
                }
            ),
        textStyle = defaultTextStyle.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = defaultBackgroundColor,
                        shape = searchBarShape
                    )
                    .padding(horizontal = contentHorizontalPadding, vertical = contentVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 前置图标
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = "Search",
                    modifier = Modifier.size(leadingIconSize),
                    // iOS .label（动态色）——iOS 实机截图实证 2026-08-09：放大镜浅色模式纯黑、
                    // 深色模式纯白，非灰（占位文字才是灰的 .placeholderText）
                    tint = MaterialTheme.colorScheme.label
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 输入框
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchText.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = defaultTextStyle,
                            color = MaterialTheme.colorScheme.placeholderText,  // iOS .placeholderText - 占位符文字
                            maxLines = 1,  // 超长占位符（如 Search in "Recently Played Albums"）单行截断，不换行
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }

                // 清除按钮
                if (showClearButton && searchText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { onSearchTextChanged("") },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            // iOS UISearchBar 的内联清空钮为 xmark.circle.fill（非裸叉）
                            AmperfyIcons.xmarkCircleFill,
                            contentDescription = "Clear",
                            modifier = Modifier.size(trailingIconSize),
                            tint = MaterialTheme.colorScheme.secondaryLabel  // iOS .secondaryLabel - 次要图标
                        )
                    }
                }
            }
        }
    )
}

/**
 * 列表行「内容尾部」的 end 内边距**总值**（右侧字母索引条可见时的避让值），默认 0.dp。
 *
 * **语义（2026-08-13 真机复核校准）：值 = 内容 Row 的 end 内边距总值，用于「替代」默认的
 * 16dp，而非叠加在 16dp 之上**；0.dp = 无索引条的页面，走默认 16dp。行组件一律写
 * `end = maxOf(16.dp, LocalListRowTrailingInset.current)`。
 *
 * 对齐 iOS：UITableView 在 `sectionIndexTitles` 非空时**由系统自动把 cell.contentView
 * 收窄一个索引条宽度**（行内容整体避让，分割线仍由表视图自绘、长度不受影响）——
 * iOS 2.1.0 应用代码零处理，属 UIKit 内建行为。**收窄后的具体余量属闭源行为**，
 * 判据取 iOS 实机观测（2026-08-13）：行最右元素（GenericTableCell 的「>」等）到索引条的
 * 间距**不超过 1 个字符宽**——即收窄后**不再保留 16pt 满额 trailingMargin**；同日第三轮
 * 判据进一步定标：Albums **GRID** 的现状（网格行 `end = 20.dp`，未做任何避让改动）与 iOS
 * 真机间距几乎一致，即为参照答案，LIST 行按同一「字形间隙」对齐
 * （公式见 [rememberIndexBarRowEndPadding]）。
 *
 * Android 的 [AlphabetIndex] 是叠在列表右缘的悬浮 Box，行内容不会自动避让，Year/Duration
 * 排序档的宽标签（"1995"/"1:20:00"）把条宽撑到 30-40dp 后会压住行右侧的 More(⋯)/时长。
 * 故本 Local 作为「系统收窄 contentView」的对应物：**索引条可见的页面**在列表外围经
 * `CompositionLocalProvider` 提供 [rememberIndexBarRowEndPadding]，行组件读取后只作用于
 * **内容 Row 的 end padding**；行容器（点击/长按 bounds、滑动背景）与分割线一律不动，
 * 保持全宽。
 *
 * 无索引条的页面（详情页 / 播放器队列 / Downloads 等）读到默认 0.dp，行为不变——
 * 这是选 CompositionLocal 而非逐调用点传参的原因。
 */
val LocalListRowTrailingInset = compositionLocalOf { 0.dp }

/**
 * 索引条可见时行内容的 end 内边距总值（[LocalListRowTrailingInset] 的取值口径）。
 *
 * 公式 = [indexBarEndPadding] + (条宽 + 最宽字形宽) / 2 + [INDEX_BAR_CONTENT_GAP]，
 * **替代**默认 16dp。
 *
 * **为什么是「(条宽 + 字形宽)/2」而不是整个条宽**：[AlphabetIndex] 的标签在条内**水平居中**
 * 绘制（Canvas 内 `x = (size.width - layout.size.width) / 2`），故最宽字形的左缘距屏幕右缘
 * = 条 end 偏移 + (条宽 + 该字形宽)/2；条带两侧的空白只是触摸区，iOS 侧内容同样滑入这段空白。
 * 按整条宽留隙会比 iOS 多让约 8dp（A-Z 档实测），即第二版「距离仍偏大」的根因。
 *
 * **各档实际值**（A-Z + # 标签宽约 8dp、条宽 20dp）：
 * - A-Z 档 + 条 end 偏移 4dp（Songs / FavoriteSongs / Albums LIST）≈ **20dp**
 *   —— 与 Albums GRID 网格行 `end = 20.dp` 的参照值一致
 * - A-Z 档 + 条贴屏幕右缘（Artists / Genres / Radios / AddSongs）≈ **16-17dp**
 *   —— 约等于默认 16dp，行组件的 `maxOf(16.dp, …)` 护栏兜住下限
 * - year / duration 宽标签档：字形几乎撑满条宽，故 ≈ 条宽 + 偏移 −1dp 上下，
 *   对最宽字形恒留 [INDEX_BAR_CONTENT_GAP] 净空
 *
 * @param letters 索引条标签全集（null = A-Z + #）；必须与该页 [AlphabetIndex] 的同名实参一致
 * @param indexBarEndPadding 该页 [AlphabetIndex] 自身相对屏幕右缘的 `padding(end = …)` 偏移
 *   （多数页为 0；Songs / FavoriteSongs / Albums LIST 为 4dp）
 */
@Composable
fun rememberIndexBarRowEndPadding(
    letters: List<String>? = null,
    indexBarEndPadding: Dp = 0.dp
): Dp {
    val metrics = rememberIndexBarMetrics(letters)
    return indexBarEndPadding +
        (metrics.barWidth + metrics.widestLabelWidth) / 2 +
        INDEX_BAR_CONTENT_GAP
}

/**
 * 计算 [AlphabetIndex] 的条宽（索引条自身与 [rememberIndexBarRowEndPadding] 的**单一真相源**）。
 *
 * @param letters 索引条标签全集；**null（默认）= A-Z + #**，语义与 [AlphabetIndex] 的
 *   同名形参一致。调用方提供避让宽度时必须传与 [AlphabetIndex] **完全相同**的实参，
 *   否则避让宽度与实际条宽对不上。
 */
@Composable
fun rememberAlphabetIndexBarWidth(letters: List<String>? = null): Dp =
    rememberIndexBarMetrics(letters).barWidth

/**
 * 索引条的量测结果（[barWidth] 与 [widestLabelWidth] 的**唯一量测点**）。
 *
 * @property barWidth 条宽 = max([INDEX_BAR_MIN_WIDTH], 最宽字形宽 + 左右各 [INDEX_BAR_HORIZONTAL_PADDING])
 * @property widestLabelWidth 最宽标签的字形宽（**不含**条内 padding），供避让公式换算字形左缘位置
 */
private data class IndexBarMetrics(val barWidth: Dp, val widestLabelWidth: Dp)

/**
 * 量测索引条标签：默认 A-Z 单字符使条宽维持 [INDEX_BAR_MIN_WIDTH]；
 * Year/Duration 档的 "1995"/"1:20:00" 据实测宽度撑开，避免截断。
 */
@Composable
private fun rememberIndexBarMetrics(letters: List<String>?): IndexBarMetrics {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val indexLabels = remember(letters) {
        letters ?: com.amperfy.utils.AlphabetIndexUtils.getAllIndexLetters()
    }
    return remember(indexLabels, density) {
        val widestPx = indexLabels.maxOfOrNull { label ->
            textMeasurer.measure(
                text = label,
                style = TextStyle(fontSize = INDEX_BAR_FONT_SIZE, fontWeight = FontWeight.Bold)
            ).size.width
        } ?: 0
        val widestDp = with(density) { widestPx.toDp() }
        IndexBarMetrics(
            barWidth = maxOf(INDEX_BAR_MIN_WIDTH, widestDp + INDEX_BAR_HORIZONTAL_PADDING * 2),
            widestLabelWidth = widestDp
        )
    }
}

/**
 * iOS风格字母索引组件
 *
 * 功能特性：
 * - 支持点击跳转
 * - 支持拖动跳转（drag-to-jump）
 * - 手指滑动高亮字母
 * - 悬浮大字母提示（带淡入淡出动画）
 * - 索引标签默认 A-Z + #（可经 [letters] 覆盖，如 Rating 排序档的 "5".."1"/"#"）
 * - 三态显示：高亮/可用/不可用
 *
 * 索引定位根因修复（2026-07-24，真机目测）：
 * - D1：旧实现遗留「LazyColumn index 1 为钉顶 stickyHeader」结构的负偏移补偿
 *   （scrollOffset = -stickyHeaderHeight，未测到时 -280 估算）。各屏对齐 iOS
 *   hidesSearchBarWhenScrolling 重构成普通 item 后已无钉顶元素，负偏移会让目标行上方
 *   漏出约一行上一字母内容。本次删除整套 sticky 补偿，滚动一律 offset 0。
 * - D2：旧实现把「内容从 lazy index 2 起」写死，但各屏前置 item 数不同
 *   （Songs/Albums=2，Artists/Genres=3，Radios=4），导致滚动目标与反查错位。
 *   改由调用方经 [leadingItemCount] 声明前置 item 数。
 *
 * @param T 列表项类型（泛型）
 * @param items 列表项数据
 * @param listState LazyColumn/LazyList的状态
 * @param getIndexLetter 从列表项提取索引字母的函数
 * @param modifier 修饰符
 * @param isGridView 是否为Grid视图模式（Grid模式需要特殊的索引映射）
 * @param letterToIndexMap Grid模式下的**索引标签**到 item 的映射表（绝对 lazy index）。
 *   键为完整标签字符串（非首字符）——时长桶标签 "1:00:00"/"1:40:00" 首字符会撞车
 * @param leadingItemCount LazyColumn 中内容项之前的前置 item 数（大标题/搜索栏/分隔线等）。
 *   调用方必须与所在屏的 LazyColumn 结构保持同步——增删前置 item 时同步改此值，否则索引定位错位。
 * @param letters 索引条要画的标签全集；**null（默认）= A-Z + #**。
 *   非字母排序档传各自标签集（Rating 为固定 "5".."1"/"#"，Year/Duration 由数据现算），
 *   对应 iOS 索引来自 FRC 的 `sectionIndexType`
 *   （`IndexHeaderNameGenerator`，BasicFetchedResultsController.swift:62-200）而非固定字母表。
 *
 * 布局（2026-08-10 因实机上「星数字离得太远」重做）：iOS `sectionIndexTitles` 索引条是
 * **每项固定紧凑高度、整条在表格高度内垂直居中**，不是把标签摊满整条容器。故此处
 * 项高 = min([INDEX_BAR_ITEM_HEIGHT], 容器高/项数)、绘制带总高 = 项高×项数并垂直居中，
 * 命中区与绘制带同步偏移；条宽按最长标签量测自适应（≥[INDEX_BAR_MIN_WIDTH]），
 * 以完整显示 "1995"/"1:20:00" 类多字符标签。
 *
 * **组件级 key 约定（2026-08-10 真机报障「year 排序索引条滑动/点击完全无反应」的根因修复）**：
 * `produceState(availableLetters)` / `remember(currentLetter)` / 两个 `pointerInput` 的 key
 * **必须统一含 `items`、`indexLabels`、`letterToIndexMap`**，缺一不可。
 *
 * 根因：这三处持有的 lambda 都捕获**创建那一轮组合**的局部函数与数据
 * （`getLetterAtPosition`/`activateLetter`/`getIndexLetter`/`items`/`letterToIndexMap`）。
 * 原实现用 `pointerInput(Unit)` 与无 key 的 `remember`，闭包只建一次；本组件此前标签集恒为
 * A-Z + #、切排序不换标签，故一直没暴露。索引标签改为随排序档切换后：切到 year 档，
 * 触摸算出的年份标签过不了 `activateLetter` 开头的 `availableLetters.contains(letter)` 守卫
 * （守卫读到的是旧档的字母集合），直接 return —— 悬浮提示不出、点击拖动全部失效。
 * 三处 key 保持一致，可保证「标签集 / 数据 / 手势闭包」同批换代，不会出现半新半旧。
 *
 * **残余边角**（已知、暂不处理）：key 走结构相等判定，若切档后 `items` 与 `indexLabels`
 * 都与切档前相等（如 Albums 的 Name↔Artist 两档都用 A-Z 标签，且排序结果恰好同序），
 * 闭包不会重建，`getIndexLetter` 仍是旧档的取字段方式。把 `getIndexLetter` 也列为 key 可根治，
 * 但它每次重组都是新 lambda 实例，会让 `produceState` 的 O(n) 拼音计算每帧重跑，得不偿失。
 */
@Composable
fun <T> AlphabetIndex(
    items: List<T>,
    listState: LazyListState,
    getIndexLetter: (T) -> String,
    modifier: Modifier = Modifier,
    isGridView: Boolean = false,
    letterToIndexMap: Map<String, Int>? = null,
    leadingItemCount: Int = 2,
    letters: List<String>? = null
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // 当前触摸/拖动的字母
    var activeLetter by remember { mutableStateOf<String?>(null) }

    // 自动隐藏悬浮提示的任务
    var hideJob by remember { mutableStateOf<Job?>(null) }

    // 记录索引栏的高度（用于计算字母间距）
    var indexBarHeight by remember { mutableStateOf(0f) }

    // 提前获取主题颜色（在Composable上下文中）
    val primaryColor = MaterialTheme.colorScheme.primary  // iOS .systemBlue
    val secondaryLabelColor = MaterialTheme.colorScheme.secondaryLabel  // iOS .secondaryLabel - 可用字母
    val gray4Color = MaterialTheme.colorScheme.gray4  // iOS .systemGray4 - 不可用字母

    // 索引条标签：调用方未指定（[letters] = null）时为固定的 A-Z + #
    val indexLabels = remember(letters) {
        letters ?: com.amperfy.utils.AlphabetIndexUtils.getAllIndexLetters()
    }

    // 计算哪些字母在items中有对应的内容。
    // 必须在后台计算：逐项 getIndexLetter 含拼音转换，大列表（数千条）在主线程
    // 一帧内完成会造成进入页面时转场动画掉帧（开销正比于 item 数量）。
    // 计算完成前为空集 → 字母全灰，随后刷新，视觉可接受。
    // key 含 [indexLabels]（见下方「组件级 key 约定」）：切排序档时标签语义随之变化，
    // 只按 items 判定会在「新旧列表结构相等」时不重算，留下旧档标签集
    val availableLetters by produceState(initialValue = emptySet<String>(), items, indexLabels) {
        value = withContext(Dispatchers.Default) {
            items.map { item ->
                getIndexLetter(item)
            }.toSet()
        }
    }

    // 计算当前高亮的字母（基于滚动位置或激活状态）
    // key 见下方「组件级 key 约定」：本 lambda 捕获 items/letterToIndexMap/getIndexLetter，
    // 原先的无 key `remember` 会永久留住首次组合的那份，切排序档后高亮按旧档数据算
    val currentLetter by remember(items, indexLabels, letterToIndexMap) {
        derivedStateOf {
            // 如果有激活的字母，优先显示激活的
            if (activeLetter != null) {
                activeLetter
            } else {
                // 否则基于滚动位置计算：无钉顶元素，首个 index >= leadingItemCount 的可见项即是内容项
                val firstContentItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index >= leadingItemCount }

                if (firstContentItem == null) {
                    null
                } else if (isGridView && letterToIndexMap != null) {
                    // Grid模式：使用「索引标签 → lazy index」映射表
                    val effectiveIndex = firstContentItem.index
                    letterToIndexMap.entries
                        .sortedByDescending { it.value }
                        .firstOrNull { it.value <= effectiveIndex }
                        ?.key
                } else if (!isGridView) {
                    // Table模式：直接从items中获取（扣除前置 item 数换算回 items 下标）
                    val itemIndex = firstContentItem.index - leadingItemCount
                    if (itemIndex >= 0 && itemIndex < items.size) {
                        getIndexLetter(items[itemIndex])
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }
    }

    // 绘制带几何：项高取「紧凑固定值」与「容器高/项数」的较小者，整条带在 Canvas 内垂直居中
    //（iOS sectionIndex 的排布方式，见函数 KDoc）。返回 (带顶偏移, 项高)。
    // 刻意做成按容器高现算的函数而非 composition 里的 val：indexBarHeight 是绘制期写入的
    // state，若在 composition 中读会多触发一轮重组
    fun bandTopAndSpacing(containerHeight: Float): Pair<Float, Float> {
        if (indexLabels.isEmpty() || containerHeight <= 0f) return 0f to 0f
        val itemHeightPx = with(density) { INDEX_BAR_ITEM_HEIGHT.toPx() }
        val bandHeight = minOf(itemHeightPx * indexLabels.size, containerHeight)
        return ((containerHeight - bandHeight) / 2f) to (bandHeight / indexLabels.size)
    }

    // 根据Y坐标获取对应的字母（坐标相对 Canvas；绘制带以外不响应，手势落回列表自身）
    fun getLetterAtPosition(offsetY: Float): String? {
        if (indexBarHeight == 0f) return null
        val (bandTop, itemSpacing) = bandTopAndSpacing(indexBarHeight)
        if (itemSpacing == 0f) return null

        val yInBand = offsetY - bandTop
        if (yInBand < 0 || yInBand > itemSpacing * indexLabels.size) return null

        val index = (yInBand / itemSpacing).toInt()
            .coerceIn(0, indexLabels.size - 1)
        return indexLabels[index]
    }

    // 滚动到指定字母
    fun scrollToLetter(letter: String) {
        if (!availableLetters.contains(letter)) return

        scope.launch {
            if (isGridView && letterToIndexMap != null) {
                // Grid模式：使用letterToIndexMap（键=完整标签，值为绝对 lazy index）
                val targetIndex = letterToIndexMap[letter]
                if (targetIndex != null) {
                    // 落在前置 item 区间内 → 回列表顶部露出大标题；否则直接定位到 section header，无偏移
                    if (targetIndex <= leadingItemCount) {
                        listState.scrollToItem(0, scrollOffset = 0)
                    } else {
                        listState.scrollToItem(targetIndex, scrollOffset = 0)
                    }
                }
            } else {
                // Table模式：在items中查找
                val targetIndex = items.indexOfFirst { item ->
                    getIndexLetter(item) == letter
                }
                if (targetIndex >= 0) {
                    val actualIndex = targetIndex + leadingItemCount  // 加上前置 item 数换算为 lazy index
                    // 首个字母回列表顶部露出大标题（语义不随 leading 数变化）；否则定位到目标行，无偏移
                    if (targetIndex == 0) {
                        listState.scrollToItem(0, scrollOffset = 0)
                    } else {
                        listState.scrollToItem(actualIndex, scrollOffset = 0)
                    }
                }
            }
        }
    }

    // 激活字母并设置自动隐藏
    fun activateLetter(letter: String) {
        if (availableLetters.contains(letter)) {
            activeLetter = letter
            scrollToLetter(letter)

            // 取消之前的隐藏任务
            hideJob?.cancel()
            // 800ms后自动隐藏悬浮提示
            hideJob = scope.launch {
                delay(800)
                activeLetter = null
            }
        }
    }

    // 条宽自适应最长标签：与行内容避让宽度（[LocalListRowTrailingInset]）同源，
    // 计算收敛在 [rememberAlphabetIndexBarWidth]
    val barWidth = rememberAlphabetIndexBarWidth(letters)

    Box(modifier = modifier) {
        // 使用Canvas绘制字母索引栏
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .width(barWidth)
                .fillMaxHeight(0.7f)
                // key 必须含 indexLabels / letterToIndexMap / items（见「组件级 key 约定」）——
                // 手势 block 只在 key 变化时重启，其闭包捕获的是**启动那一轮组合**的
                // getLetterAtPosition/activateLetter 及其闭包数据
                .pointerInput(indexLabels, letterToIndexMap, items) {
                    // 处理点击事件
                    detectTapGestures { offset ->
                        getLetterAtPosition(offset.y)?.let { letter ->
                            activateLetter(letter)
                        }
                    }
                }
                .pointerInput(indexLabels, letterToIndexMap, items) {
                    // 处理拖动事件
                    detectDragGestures(
                        onDragStart = { offset ->
                            getLetterAtPosition(offset.y)?.let { letter ->
                                activateLetter(letter)
                            }
                        },
                        onDrag = { change, _ ->
                            getLetterAtPosition(change.position.y)?.let { letter ->
                                if (activeLetter != letter) {
                                    activateLetter(letter)
                                }
                            }
                        },
                        onDragEnd = {
                            // 拖动结束，启动自动隐藏
                            hideJob?.cancel()
                            hideJob = scope.launch {
                                delay(800)
                                activeLetter = null
                            }
                        }
                    )
                }
        ) {
            // 记录索引栏高度（供命中区几何换算复用同一套公式）
            indexBarHeight = size.height

            // 绘制带：项高固定、整带垂直居中（与 getLetterAtPosition 同一函数，保证命中区对齐）
            val (bandTop, itemSpacing) = bandTopAndSpacing(size.height)

            indexLabels.forEachIndexed { index, letter ->
                val isHighlighted = letter == currentLetter
                val isAvailable = availableLetters.contains(letter)

                val textColor = when {
                    isHighlighted -> primaryColor  // iOS .systemBlue
                    isAvailable -> secondaryLabelColor  // iOS .secondaryLabel - 可用字母
                    else -> gray4Color  // iOS .systemGray4 - 不可用字母
                }

                val layout = textMeasurer.measure(
                    text = letter,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = INDEX_BAR_FONT_SIZE,
                        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                        color = textColor,
                        textAlign = TextAlign.Center
                    )
                )

                val x = (size.width - layout.size.width) / 2
                // 绘制带垂直居中：起点 bandTop + 项内再居中
                val y = bandTop + index * itemSpacing + (itemSpacing - layout.size.height) / 2

                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x, y)
                )
            }
        }
    }

    // 悬浮大字母提示（带动画）- 放在Box外部，完全独立，不影响索引栏布局
    val showFloatingLetter = activeLetter != null && availableLetters.contains(activeLetter!!)
    if (showFloatingLetter) {
        Box(
            modifier = modifier
                .wrapContentSize(Alignment.CenterEnd, unbounded = true)
                .offset(x = (-50).dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shadowElevation = 4.dp,
                // 多字符标签（年份/时长桶）需要横向撑开，故由 64dp 定尺改为「最小 64dp + 内边距」
                modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 64.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    val label = activeLetter ?: ""
                    Text(
                        text = label,
                        style = MaterialTheme.typography.displayMedium.copy(
                            // 字号随标签长度降级：单字符沿用 48sp，"1995" 用 32sp，"1:20:00" 用 24sp
                            fontSize = when {
                                label.length <= 1 -> 48.sp
                                label.length <= 4 -> 32.sp
                                else -> 24.sp
                            },
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 索引条单项的**紧凑固定高度**（绘制带 = 项高 × 项数，在容器内垂直居中）。
 *
 * 取值依据：改造前 A-Z + # 共 27 项摊满 `fillMaxHeight(0.7f)` 的容器，在常见机型上
 * 每项约 20dp——取 20dp 可让**字母页观感与改造前基本一致**（高屏略紧 3-5%、
 * 矮屏仍走「容器高/项数」分支不变），同时让 Rating（6 项）等少标签场景收成居中一小段，
 * 修「星数字离得太远」（2026-08-10）。要整体更紧凑只需改本常量。
 */
private val INDEX_BAR_ITEM_HEIGHT = 20.dp

/** 索引条最小宽度（单字符标签沿用改造前的 20dp 条宽） */
private val INDEX_BAR_MIN_WIDTH = 20.dp

/** 索引条标签左右各留的内边距（多字符标签撑宽时用） */
private val INDEX_BAR_HORIZONTAL_PADDING = 3.dp

/**
 * 行内容右缘到索引条**最宽字形左缘**的间隙（[rememberIndexBarRowEndPadding] 的组成项）。
 *
 * **间隙基准是字形左缘、不是条带左缘**：标签在条内水平居中绘制，条带两侧空白本就只是触摸区，
 * iOS 侧内容同样滑进这段空白带。取值依据 = iOS 实机观测（2026-08-13 第三轮）：
 * Albums **GRID** 的现状（网格行 `end = 20.dp`）与 iOS 间距几乎一致，反推 A-Z 档 + 4dp 条偏移
 * 的页面应得 ≈20dp，本常量取 2dp 即与之吻合。
 *
 * **演进留档**（三版真机迭代）：①「16dp 之外再叠一个条宽」→ 实测 23dp+，判为过远；
 * ②「条带左缘 + 4dp」→ 因字形居中比 GRID 参照多让约 8dp，仍偏远；
 * ③ 本版「最宽字形左缘 + 2dp」→ 与 GRID 参照一致。
 */
private val INDEX_BAR_CONTENT_GAP = 2.dp

/** 索引条字号（改造前后一致） */
private val INDEX_BAR_FONT_SIZE = 10.sp

/**
 * 短时长格式："Xm" / "Xh Ym"（超 24 小时累计小时数）
 * 对应 iOS: asDurationShortString（Utilities.swift:118-123，仅 hour+minute 无「天」）
 * 用于列表行 Album/Artist Duration 显示（Settings→Display）
 */
fun formatDurationShortString(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
