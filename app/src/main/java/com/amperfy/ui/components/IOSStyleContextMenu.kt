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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.gold
import com.amperfy.ui.theme.pressHighlight

/**
 * iOS风格的动画规格
 * iOS使用的是类似于CASpringAnimation的弹性动画
 */
private val IOSSpringSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,  // 0.6f - iOS的弹性效果
    stiffness = Spring.StiffnessMedium,  // 400f
    visibilityThreshold = 0.001f
)

/**
 * iOS的快速缓动曲线,类似UIView.animate的easeOut
 */
private val IOSEaseOutSpec = tween<Float>(
    durationMillis = 250,  // iOS默认动画时长
    easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)  // ease-out曲线
)

/**
 * iOS菜单的出现动画 - 快速弹出效果
 */
private val IOSMenuEnterSpec = spring<Float>(
    dampingRatio = 0.7f,  // 轻微弹性
    stiffness = 500f,  // 较快的弹出速度
    visibilityThreshold = 0.001f
)

/**
 * iOS的淡入淡出规格
 */
private val IOSFadeSpec = tween<Float>(
    durationMillis = 200,
    easing = LinearEasing
)

/**
 * iOS风格的Context Menu项
 */
sealed class IOSContextMenuItem {
    data class Action(
        val text: String,
        val icon: ImageVector? = null,
        // 副标题（对齐 iOS UIAction.subtitle）：非空时在 text 下方渲染灰色小字第二行；
        // 位置紧随 icon 之后，保证既有 Action("text", icon) { } 位置参数调用零改动
        val subtitle: String? = null,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        // 选中态（对齐 iOS UIAction.state）：UIKit 在 image 列**之前**另起一个 state 列，
        // .on 画系统小勾、.off 留同宽空位（保证同组内 image 列左对齐）。
        // null = 该菜单不使用 state 列（既有全部调用方的默认，渲染逐像素不变）；
        // false = .off 留空位；true = .on 画勾。
        val state: Boolean? = null,
        // 图标尺寸覆盖（对齐 iOS UIAction image 的 SymbolConfiguration pointSize）：
        // null = 走常规菜单图标尺寸 22dp（既有全部调用方的默认，渲染逐像素不变）。
        // 账户行等 iOS 侧显式放大 pointSize 的场合才传值
        val iconSize: Dp? = null,
        val onClick: () -> Unit
    ) : IOSContextMenuItem()

    data class Submenu(
        val text: String,
        val icon: ImageVector? = null,
        // 副标题（对齐 iOS UIMenu.subtitle）：非空时在 text 下方渲染灰色小字第二行。
        // 只在**父菜单里的子菜单行**渲染；点进去之后的叠加卡头部行不渲染（对齐 iOS——
        // 头部是导航返回行，不重复 subtitle）
        val subtitle: String? = null,
        val items: List<IOSContextMenuItem>
    ) : IOSContextMenuItem()

    /**
     * iOS风格的Rating调色板菜单 - 对应iOS UIMenu.Options.displayAsPalette
     * 横向排列图标，无文字，点击选择评分
     */
    data class RatingPalette(
        val text: String,  // 显示的标题，如 "Rating: 3 Stars"
        val icon: ImageVector? = null,
        val currentRating: Int,  // 当前评分 0-5
        val onRatingSelected: (Int) -> Unit
    ) : IOSContextMenuItem()

    object Divider : IOSContextMenuItem()
}

/**
 * iOS风格的Context Menu
 */
@Composable
fun IOSStyleContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: IntOffset = IntOffset(0, 0),
    alignment: Alignment = Alignment.TopEnd,  // 默认在按钮右上角展开
    items: List<IOSContextMenuItem>,
    // 菜单标题（对齐 iOS `UIMenu.lazyMenu(title:)`）：非空时在菜单卡顶部渲染一行居中灰色小字 +
    // 下方 hairline 分隔线。null = 无标题（既有全部调用方的默认，渲染逐像素不变）
    title: String? = null,
    // 「贴锚点上方」定位（对齐 iOS 底部按钮上弹的 UIMenu：菜单**右下角贴住按钮**）：
    // 开启后菜单底边 = 锚点顶边 − [IOS_MENU_ANCHOR_GAP]，右缘仍由 alignment 决定（须传 Bottom*End）。
    // false = 现行 alignment/offset 语义（既有全部调用方的默认，定位逐像素不变）
    placeAboveAnchor: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (expanded) {
        // 根据alignment判断展开方向
        val expandUp = alignment == Alignment.BottomStart || alignment == Alignment.BottomEnd || alignment == Alignment.BottomCenter
        val expandLeft = alignment == Alignment.TopEnd || alignment == Alignment.BottomEnd || alignment == Alignment.CenterEnd

        IOSMenuPopup(
            alignment = alignment,
            offset = offset,
            onDismissRequest = onDismissRequest,
            items = items,
            title = title,
            placeAboveAnchor = placeAboveAnchor,
            expandUp = expandUp,
            expandLeft = expandLeft,
            modifier = modifier
        )
    }
}

/**
 * 智能定位的iOS风格Context Menu
 * 根据触摸点位置自动决定菜单展开方向
 *
 * @param expanded 是否展开
 * @param onDismissRequest 关闭请求回调
 * @param anchorBounds 触发组件的位置信息
 * @param touchPosition 触摸点在组件内的相对位置
 * @param items 菜单项列表
 * @param modifier Modifier
 */
@Composable
fun IOSStyleContextMenuAuto(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: androidx.compose.ui.geometry.Rect?,
    touchPosition: androidx.compose.ui.geometry.Offset? = null,
    items: List<IOSContextMenuItem>,
    modifier: Modifier = Modifier
) {
    if (!expanded || anchorBounds == null) return

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // 屏幕尺寸（像素）
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // 触摸点的绝对坐标（如果没有触摸点信息，使用组件中心）
    val touchX = touchPosition?.x ?: (anchorBounds.width / 2)
    val touchY = touchPosition?.y ?: (anchorBounds.height / 2)

    // 触摸点在屏幕中的绝对位置
    val absoluteTouchX = anchorBounds.left + touchX
    val absoluteTouchY = anchorBounds.top + touchY

    // 计算触摸点到屏幕边缘的距离
    val distanceToTop = absoluteTouchY
    val distanceToBottom = screenHeightPx - absoluteTouchY
    val distanceToLeft = absoluteTouchX
    val distanceToRight = screenWidthPx - absoluteTouchX

    // 估算菜单大小
    val estimatedMenuHeight = with(density) { (items.size * 44 + 16).dp.toPx() }
    // 菜单卡已是固定宽（IOS_MENU_WIDTH），估算值直接用真值，不再猜 220
    val estimatedMenuWidth = with(density) { IOS_MENU_WIDTH.toPx() }

    // 决定垂直方向：如果下方空间不够，就向上展开
    val expandUp = distanceToBottom < estimatedMenuHeight && distanceToTop > distanceToBottom

    // 决定水平方向：如果右方空间不够，就向左展开
    val expandLeft = distanceToRight < estimatedMenuWidth && distanceToLeft > distanceToRight

    // 根据方向决定alignment
    val alignment = when {
        expandUp && expandLeft -> Alignment.BottomEnd
        expandUp && !expandLeft -> Alignment.BottomStart
        !expandUp && expandLeft -> Alignment.TopEnd
        else -> Alignment.TopStart
    }

    // 计算offset，让菜单从触摸点展开
    // offset是相对于alignment锚点的偏移
    val offsetX = when {
        expandLeft -> {
            // 向左展开：菜单右边缘对齐触摸点
            // alignment是End，所以需要计算触摸点相对于组件右边缘的偏移
            -(anchorBounds.width - touchX).toInt() - 8
        }
        else -> {
            // 向右展开：菜单左边缘对齐触摸点
            // alignment是Start，所以需要计算触摸点相对于组件左边缘的偏移
            touchX.toInt() - 8
        }
    }

    val offsetY = when {
        expandUp -> {
            // 向上展开：菜单底边对齐触摸点上方
            -(anchorBounds.height - touchY).toInt() - 8
        }
        else -> {
            // 向下展开：菜单顶边对齐触摸点下方
            touchY.toInt() + 8
        }
    }

    IOSMenuPopup(
        alignment = alignment,
        offset = IntOffset(offsetX, offsetY),
        onDismissRequest = onDismissRequest,
        items = items,
        expandUp = expandUp,
        expandLeft = expandLeft,
        modifier = modifier
    )
}

/**
 * 弹出式菜单的公共弹层（IOSStyleContextMenu / IOSStyleContextMenuAuto 共用）
 *
 * 相对旧实现（`Popup(alignment = , offset = )`）只多做两件事，水平定位语义原样保留：
 * 1. 自定义 [IOSMenuPositionProvider] 在复刻 alignment+offset 定位后**垂直钳制**——
 *    菜单底边不越过「屏幕底 − 底部净空」（净空 = max(导航栏 inset, [IOS_MENU_NAV_REGION_MIN_HEIGHT])
 *    + [IOS_MENU_SCREEN_BOTTOM_MARGIN]，即整个导航区域之上恒留 16dp），
 *    顶边不越过状态栏 inset（对齐 iOS 菜单恒约束在安全区内）。
 * 2. 菜单卡外包滚动容器（与长按弹层同款）：超高菜单内部滚动，不顶穿屏幕。
 *
 * 安全区 inset 必须在 Popup **外**读取（Popup 内是独立窗口，其内 WindowInsets 可能解析为 0），
 * 且须取整窗 root insets 而非 Compose 消费后的剩余值——见 [rememberRootSystemBarInsetsPx]。
 */
@Composable
private fun IOSMenuPopup(
    alignment: Alignment,
    offset: IntOffset,
    onDismissRequest: () -> Unit,
    items: List<IOSContextMenuItem>,
    title: String? = null,
    placeAboveAnchor: Boolean = false,
    expandUp: Boolean,
    expandLeft: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val (topInsetPx, bottomInsetPx) = rememberRootSystemBarInsetsPx()
    val topMarginPx = with(density) { IOS_MENU_SCREEN_TOP_MARGIN.roundToPx() }
    val bottomMarginPx = with(density) { IOS_MENU_SCREEN_BOTTOM_MARGIN.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    // 底部净空 = max(导航栏 inset, 导航区域保底高) + 留白——先取整个导航区域高度（inset 报 0 的
    // 设备用 48dp 兜底），再在其**之上**叠 16dp 留白，菜单底边恒不贴导航区域顶边（净空 ≥ 64dp）
    val navRegionMinPx = with(density) { IOS_MENU_NAV_REGION_MIN_HEIGHT.roundToPx() }
    val bottomClearancePx = maxOf(bottomInsetPx, navRegionMinPx) + bottomMarginPx
    // 菜单卡最大高 = 屏高 − 顶部 inset+留白 − 底部净空（与位置钳制同口径，保证钳制恒可满足）
    val maxMenuHeight = with(density) {
        (screenHeightPx - topInsetPx - topMarginPx - bottomClearancePx)
            .coerceAtLeast(0f)
            .toDp()
    }

    val anchorGapPx = with(density) { IOS_MENU_ANCHOR_GAP.roundToPx() }
    val positionProvider = remember(
        alignment, offset, topInsetPx, bottomClearancePx, placeAboveAnchor, anchorGapPx
    ) {
        IOSMenuPositionProvider(
            alignment = alignment,
            offset = offset,
            topInsetPx = topInsetPx,
            bottomClearancePx = bottomClearancePx,
            placeAboveAnchor = placeAboveAnchor,
            anchorGapPx = anchorGapPx
        )
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            modifier = Modifier
                .width(IOS_MENU_WIDTH)
                .heightIn(max = maxMenuHeight)
                .verticalScroll(rememberScrollState())
        ) {
            IOSContextMenuContent(
                items = items,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                title = title,
                expandUp = expandUp,
                expandLeft = expandLeft
            )
        }
    }
}

/**
 * 菜单弹层定位：复刻 Compose 内部 `AlignmentOffsetPositionProvider`
 * （即 `Popup(alignment = , offset = )` 的定位实现——alignment 作用于 anchorBounds，
 * offset 的 x 按 LayoutDirection 取向、y 恒向下），随后仅对 **y** 做安全区钳制：
 *
 * - 底边 ≤ windowSize.height − [bottomClearancePx]
 * - 顶边 ≥ [topInsetPx]（可用高不足时以顶边优先，避免钳到负值）
 *
 * x 一律保持原语义不变（各详情页 More 菜单的右缘锚定观感不得回归）。
 * 坐标系：anchorBounds / windowSize / 返回值同为**父窗口**坐标；App 为 edge-to-edge，
 * 父窗口即整块屏幕，故 windowSize 与 systemBars inset 同系可直接相减。
 *
 * [placeAboveAnchor] = true 时**只改 y**：底边 = 锚点顶边 − [anchorGapPx]（x 仍走 alignment，
 * 故 RTL 语义不受影响）。该模式下**不做底部净空上拽**——菜单底边由构造即在锚点之上，
 * 再上拽只会把菜单从按钮上扯开（正是要修的现象）；仅保留顶部安全区钳制与整体不出屏。
 */
private class IOSMenuPositionProvider(
    private val alignment: Alignment,
    private val offset: IntOffset,
    private val topInsetPx: Int,
    private val bottomClearancePx: Int,
    private val placeAboveAnchor: Boolean = false,
    private val anchorGapPx: Int = 0
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // 锚点内的对齐点 − 菜单内的对齐点 + 锚点原点 + 用户 offset（原 Popup 语义逐句复刻）
        val parentAlignmentPoint = alignment.align(
            IntSize.Zero,
            IntSize(anchorBounds.width, anchorBounds.height),
            layoutDirection
        )
        val relativePopupPos = alignment.align(
            IntSize.Zero,
            IntSize(popupContentSize.width, popupContentSize.height),
            layoutDirection
        )
        val resolvedOffsetX = offset.x * (if (layoutDirection == LayoutDirection.Ltr) 1 else -1)
        val x = anchorBounds.left + parentAlignmentPoint.x - relativePopupPos.x + resolvedOffsetX

        if (placeAboveAnchor) {
            // 菜单底边贴锚点顶边上方 gap 处；空间不够时以顶边优先（菜单本身可滚动）
            val rawY = anchorBounds.top - anchorGapPx - popupContentSize.height + offset.y
            val bottomLimit = windowSize.height - popupContentSize.height
            val y = if (bottomLimit <= topInsetPx) topInsetPx
                    else rawY.coerceIn(topInsetPx, bottomLimit)
            return IntOffset(x, y)
        }

        val rawY = anchorBounds.top + parentAlignmentPoint.y - relativePopupPos.y + offset.y
        val maxY = windowSize.height - bottomClearancePx - popupContentSize.height
        val y = if (maxY <= topInsetPx) topInsetPx else rawY.coerceIn(topInsetPx, maxY)
        return IntOffset(x, y)
    }
}

/**
 * 整窗真实 system bars insets（px，返回 top to bottom）——不走 Compose 的
 * `WindowInsets.systemBars`：那是「未被消费的剩余值」，Tab 页内容位于 M3
 * Scaffold(bottomBar)/NavigationBar 的消费链下游，读到的 bottom 恒为 0
 * （菜单贴屏幕底的根因）。View 层的 rootWindowInsets 不经过消费机制，
 * 永远是真实值；View 未 attach 拿不到时回退 Compose 值兜底。
 */
@Composable
private fun rememberRootSystemBarInsetsPx(): Pair<Int, Int> {
    val view = LocalView.current
    val density = LocalDensity.current
    val fallback = WindowInsets.systemBars
    val fallbackTop = fallback.getTop(density)
    val fallbackBottom = fallback.getBottom(density)
    return remember(view, fallbackTop, fallbackBottom) {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        val resolved =
            if (insets != null) insets.top to insets.bottom else fallbackTop to fallbackBottom
        resolved
    }
}

/**
 * 长按弹出：预览卡片 + 上下文菜单
 * 对应 iOS UIContextMenuConfiguration(previewProvider: + actionProvider:)
 * （BasicTableViewController.swift:246-288 contextMenuConfigurationForRowAt）
 *
 * - 全屏 Popup：背景暗化（iOS 为系统模糊，以暗化近似），点空白处/返回键关闭
 * - 就地展开：预览卡片顶边恒对齐被长按行的顶边，从行矩形原地 morph 成形
 *   （UIKit 系统行为；BasicTableViewController 未提供 targeted preview，
 *   故不存在"卡片悬在触点上方"的形态）；卡片+菜单总高放不下时整体上移钳制在屏内
 * - 内容过高时整体可滚动（对应 iOS 菜单超屏可滚动）
 * - 菜单复用 IOSContextMenuContent：动作点击执行并关闭、子菜单/Rating 覆盖式叠加
 *
 * @param anchorBoundsOnScreen 被长按行的屏幕坐标（positionOnScreen；null 时显示在
 *   屏幕上三分之一处）。用屏幕坐标是因为 Popup 窗口与 App 窗口的原点可能不一致
 *   （状态栏偏移），窗口坐标会造成卡片相对行的错位
 * @param menuAlignment 菜单水平对齐：默认对齐卡片左侧（普通列表），
 *   全屏播放器内传 CenterHorizontally（对齐 iOS 真机表现）
 * @param preview 预览卡片内容（通常为 EntityPreviewCard）
 */
@Composable
fun IOSLongPressPreviewMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBoundsOnScreen: androidx.compose.ui.geometry.Rect?,
    items: List<IOSContextMenuItem>,
    menuAlignment: Alignment.Horizontal = Alignment.Start,
    preview: @Composable () -> Unit
) {
    if (!expanded) return

    // 钉在窗口原点：Popup 默认锚定在调用处（被长按行）的位置，
    // 会导致"全屏"容器从行位置开始向下延伸出屏（行偏下时菜单显示不全的根因）
    val fullScreenPositionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset = IntOffset.Zero
        }
    }

    // 安全区 inset：必须在 Popup **外**读取——Popup 内是独立窗口，
    // 其内 `systemBarsPadding()` 可能解析为 0（旧实现的避让因此可能完全失效），
    // 故改为在此取到 Dp 后于弹层内用显式 padding 施加；
    // 且须取整窗 root insets 而非 Compose 消费后的剩余值（Tab 页内容在 Scaffold(bottomBar)/
    // NavigationBar 的消费链下游，直读 WindowInsets.systemBars 的 bottom 恒为 0）
    val density = LocalDensity.current
    val (topInsetPx, bottomInsetPx) = rememberRootSystemBarInsetsPx()
    val topInsetDp = with(density) { topInsetPx.toDp() }
    val bottomInsetDp = with(density) { bottomInsetPx.toDp() }

    // 出入场动画状态（对应 iOS UITargetedPreview 的 morph 行为）：
    // 进入 = 预览卡片从被长按行的矩形扩展成形；退出 = 缩回行矩形、播完再真正移除弹层
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visibleState.targetState = true }
    val dismissWithAnimation = { visibleState.targetState = false }
    // 退出动画完成后通知调用方翻转 expanded（真正卸载 Popup）
    LaunchedEffect(visibleState.isIdle, visibleState.currentState) {
        if (visibleState.isIdle && !visibleState.currentState && !visibleState.targetState) {
            onDismissRequest()
        }
    }

    Popup(
        popupPositionProvider = fullScreenPositionProvider,
        onDismissRequest = dismissWithAnimation,
        properties = PopupProperties(focusable = true)
    ) {
        val transition = rememberTransition(visibleState, label = "previewMenu")
        // 卡片几何 morph 进度：0 = 被长按行的矩形，1 = 最终位置与尺寸
        val geometryProgress by transition.animateFloat(
            transitionSpec = {
                spring(dampingRatio = 0.85f, stiffness = 400f, visibilityThreshold = 0.001f)
            },
            label = "geometry"
        ) { state -> if (state) 1f else 0f }
        val menuProgress by transition.animateFloat(
            transitionSpec = { IOSMenuEnterSpec },
            label = "menu"
        ) { state -> if (state) 1f else 0f }
        val scrimAlpha by transition.animateFloat(
            transitionSpec = { tween(durationMillis = 200, easing = FastOutSlowInEasing) },
            label = "scrim"
        ) { state -> if (state) 1f else 0f }

        // 安全区容器的屏幕原点（换算锚点用，实测保证与锚点同一坐标系）
        var boundsOriginYPx by remember { mutableStateOf(0f) }

        // 子菜单开/关信号（由 IOSContextMenuContent 提升）：预览卡与父菜单同步
        // 「变暗 + 向内收缩」（iOS 系统行为——父级后退一层的纵深感）
        var submenuOpen by remember { mutableStateOf(false) }
        val previewShrink by animateFloatAsState(
            targetValue = if (submenuOpen) IOS_SUBMENU_PARENT_SHRINK else 1f,
            animationSpec = tween(durationMillis = IOS_SUBMENU_PARENT_ANIM_MS),
            label = "previewShrink"
        )
        val previewDimAlpha by animateFloatAsState(
            targetValue = if (submenuOpen) IOS_SUBMENU_PARENT_DIM_ALPHA else 0f,
            animationSpec = tween(durationMillis = IOS_SUBMENU_PARENT_ANIM_MS),
            label = "previewDimAlpha"
        )
        // 收缩基准取「靠屏幕近侧」的那条边（由菜单水平对齐推出），纵向恒取底边——
        // 向与菜单的交界处收
        val previewOriginX = when (menuAlignment) {
            Alignment.CenterHorizontally -> 0.5f
            Alignment.End -> 1f
            else -> 0f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f * scrimAlpha))
                .clickable(
                    onClick = dismissWithAnimation,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            // 安全区容器：避让状态栏/导航栏（inset 由 Popup 外读入，见上方注释），
            // 再叠加上下留白常量，clipToBounds 兜底裁剪——菜单底部不可能画到
            // 屏幕底缘/手势导航条区域；底部先取整个导航区域高度（inset 报 0 的设备用保底值兜底），
            // 再在其**之上**叠留白，菜单底边恒不贴导航区域顶边（净空 ≥ 64dp）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = topInsetDp + IOS_MENU_SCREEN_TOP_MARGIN,
                        bottom = maxOf(bottomInsetDp, IOS_MENU_NAV_REGION_MIN_HEIGHT) +
                            IOS_MENU_SCREEN_BOTTOM_MARGIN
                    )
                    .clipToBounds()
                    .onGloballyPositioned {
                        boundsOriginYPx = it.positionOnScreen().y
                    }
            ) {
                // 单遍自定义布局：同一测量趟内完成"预览/菜单分别测量（菜单超高时
                // 自身滚动、预览保持可见，对齐 iOS）+ 放置位置钳制在容器内"。
                // 放置策略 = 就地展开（对应 iOS UIKit 系统行为）：卡片顶边恒对齐被长按行顶边，
                // 卡片+菜单总高越界时整体上移钳制在容器内。
                // （此前用 offset{} 平移：不参与测量也不被裁剪，状态竞态时会越出容器贴底）
                Layout(
                    content = {
                        // 子 0：预览卡片
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            // 子菜单打开时的收缩用**独立** graphicsLayer：不能并入下方
                            // placeRelativeWithLayer 的 morph layerBlock——那层
                            // transformOrigin 为 (0.5f, 0f) 服务于 morph，
                            // 混入第二种 origin 的缩放会互相打架
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = previewShrink
                                        scaleY = previewShrink
                                        transformOrigin = TransformOrigin(previewOriginX, 1f)
                                    }
                            ) {
                                preview()
                                // 变暗遮罩：圆角与预览卡一致（EntityPreviewCard 13dp）；
                                // 不加 clickable —— 不拦截点击，预览卡本身仍可点进详情
                                if (previewDimAlpha > 0.01f) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(IOS_PREVIEW_CARD_CORNER_RADIUS))
                                            .background(Color.Black.copy(alpha = previewDimAlpha))
                                    )
                                }
                            }
                        }
                        // 子 1：菜单（水平对齐由 menuAlignment 决定：普通列表对齐
                        // 卡片左侧、全屏播放器居中；宽度上限对齐 iOS UIMenu 约 250pt；
                        // 空菜单如电台等无可用动作时仅显示预览卡片）
                        if (items.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(
                                            when (menuAlignment) {
                                                Alignment.CenterHorizontally -> Alignment.TopCenter
                                                Alignment.End -> Alignment.TopEnd
                                                else -> Alignment.TopStart
                                            }
                                        )
                                        // 与菜单卡同口径的固定宽（iOS UIMenu 250pt）；
                                        // 此处是菜单卡的外层滚动容器，宽度须与卡片一致，
                                        // 否则 align 的水平定位会按容器而非卡片计算
                                        .width(IOS_MENU_WIDTH)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    IOSContextMenuContent(
                                        items = items,
                                        onDismissRequest = dismissWithAnimation,
                                        expandUp = false,
                                        expandLeft = false,
                                        onSubmenuOpenChange = { submenuOpen = it }
                                    )
                                }
                            }
                        }
                    }
                ) { measurables, constraints ->
                    val spacingPx = 12.dp.roundToPx()
                    val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                    val previewPlaceable = measurables[0].measure(childConstraints)
                    // 菜单最大高度 = 容器剩余空间（超高时菜单内部滚动，预览始终完整可见）
                    val menuPlaceable = measurables.getOrNull(1)?.measure(
                        childConstraints.copy(
                            maxHeight = (constraints.maxHeight - previewPlaceable.height - spacingPx)
                                .coerceAtLeast(0)
                        )
                    )
                    val totalHeight = previewPlaceable.height +
                        (menuPlaceable?.let { spacingPx + it.height } ?: 0)
                    val anchorTopLocalPx = anchorBoundsOnScreen
                        ?.let { it.top - boundsOriginYPx }
                        ?: (constraints.maxHeight / 3f)
                    // 就地展开：卡片顶边 = 被长按行顶边（iOS 无"卡片悬在触点上方"的形态），
                    // 总高越界时由下方 coerceIn 整体上移钳制
                    val preferredTop = anchorTopLocalPx
                    val y = preferredTop.toInt()
                        .coerceIn(0, (constraints.maxHeight - totalHeight).coerceAtLeast(0))
                    // morph 起点 = 被长按行的矩形（本地坐标）
                    val morphStartY = anchorTopLocalPx
                    val anchorHeightPx = anchorBoundsOnScreen?.height
                        ?: previewPlaceable.height.toFloat()
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // 预览卡片：从行矩形（位置+高度）morph 到最终位置（iOS 长按展开；
                        // 退出反向缩回）。layerBlock 读动画状态，仅更新图层不触发重排
                        previewPlaceable.placeRelativeWithLayer(0, y) {
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            translationY = (morphStartY - y) * (1f - geometryProgress)
                            scaleY = lerp(
                                (anchorHeightPx / previewPlaceable.height).coerceAtMost(1f),
                                1f,
                                geometryProgress
                            )
                            alpha = (geometryProgress * 1.6f).coerceAtMost(1f)
                        }
                        // 菜单：跟随卡片 morph 平移，同时自顶部缩放淡入
                        // （iOS 菜单从预览下方弹出/收回）
                        menuPlaceable?.placeRelativeWithLayer(
                            0,
                            y + previewPlaceable.height + spacingPx
                        ) {
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            translationY = (morphStartY - y) * (1f - geometryProgress)
                            alpha = menuProgress
                            scaleX = lerp(0.8f, 1f, menuProgress)
                            scaleY = lerp(0.8f, 1f, menuProgress)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 用于包裹触发组件并自动获取位置信息的容器
 * 会记录触摸点位置，实现精确的菜单定位
 */
@Composable
fun IOSContextMenuBox(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    items: List<IOSContextMenuItem>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var anchorBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var touchPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val size = coordinates.size
                anchorBounds = androidx.compose.ui.geometry.Rect(
                    left = position.x,
                    top = position.y,
                    right = position.x + size.width,
                    bottom = position.y + size.height
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.pressed }) {
                            // 记录触摸点位置（相对于组件）
                            touchPosition = event.changes.first().position
                        }
                    }
                }
            }
    ) {
        content()

        IOSStyleContextMenuAuto(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            anchorBounds = anchorBounds,
            touchPosition = touchPosition,
            items = items
        )
    }
}

/**
 * 简化版本的IOSContextMenuBox，不需要onExpandedChange
 */
@Composable
fun IOSContextMenuBox(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<IOSContextMenuItem>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    IOSContextMenuBox(
        expanded = expanded,
        onExpandedChange = { },
        onDismissRequest = onDismissRequest,
        items = items,
        modifier = modifier,
        content = content
    )
}

/**
 * 屏幕底部安全边距：子菜单自然向下展开时，判定其底边是否越过「屏幕底部 - 该边距」。
 * 经验值 16dp（避让手势导航条/圆角，可按机型微调）——仅用于「子菜单高于父菜单」时
 * 决定走「上扩覆盖留底边」还是「正常叠加」。
 */
private val SCREEN_BOTTOM_MARGIN = 16.dp

/**
 * 子菜单叠加层级 - 对应 iOS UIMenu 子菜单语义：
 * 子菜单卡片叠加覆盖父菜单，头行标题与起始位置与父菜单中被点行一致，点头行返回上级
 * （参考原生 UIContextMenu / react-native-ios-context-menu 的表现）
 */
private data class SubmenuLevel(
    /** IOSContextMenuItem.Submenu 或 RatingPalette（iOS displayAsPalette 同为覆盖式子菜单） */
    val item: IOSContextMenuItem,
    /** 被点行顶部相对菜单容器顶部的像素偏移（子菜单头行对位用） */
    val anchorY: Int
)

@Composable
private fun IOSContextMenuContent(
    items: List<IOSContextMenuItem>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    // 菜单标题（对齐 iOS UIMenu.title，见 IOSStyleContextMenu 的同名参数）；
    // 长按预览路径（IOSLongPressPreviewMenu）不传，恒为 null
    title: String? = null,
    expandUp: Boolean = false,  // 是否向上展开
    expandLeft: Boolean = false,  // 是否向左展开
    // 子菜单开/关信号提升给调用方：长按弹层据此让预览卡与父菜单同步变暗+内缩
    // （menuStack 为内部状态，外层无从感知）。弹出式菜单路径不需要，默认空实现
    onSubmenuOpenChange: (Boolean) -> Unit = {}
) {
    val density = LocalDensity.current

    // 外层进入动画（iOS 弹出效果，原逻辑保留）
    val animationState = remember { MutableTransitionState(false) }

    LaunchedEffect(Unit) {
        animationState.targetState = true
    }

    val transition = rememberTransition(animationState, label = "menu")

    // 缩放动画 - 从0.7缩放到1.0,模拟iOS的滑入效果
    val scale by transition.animateFloat(
        transitionSpec = { IOSMenuEnterSpec },
        label = "scale"
    ) { state ->
        if (state) 1f else 0.7f
    }

    // 透明度动画 - 配合滑入效果
    val alpha by transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = 150,
                easing = FastOutSlowInEasing
            )
        },
        label = "alpha"
    ) { state ->
        if (state) 1f else 0f
    }

    // Y轴偏移动画 - 根据展开方向决定滑入方向
    val offsetY by transition.animateDp(
        transitionSpec = {
            spring(
                dampingRatio = 0.8f,
                stiffness = 400f
            )
        },
        label = "offsetY"
    ) { state ->
        if (state) 0.dp else if (expandUp) 16.dp else (-16).dp
    }

    // X轴偏移动画 - 根据展开方向决定滑入方向
    val offsetX by transition.animateDp(
        transitionSpec = {
            spring(
                dampingRatio = 0.8f,
                stiffness = 400f
            )
        },
        label = "offsetX"
    ) { state ->
        if (state) 0.dp else if (expandLeft) 16.dp else (-16).dp
    }

    val transformOriginX = if (expandLeft) 1f else 0f
    val transformOriginY = if (expandUp) 1f else 0f

    // 子菜单叠加栈（iOS：点子菜单行入栈叠加显示；点子菜单头行出栈返回；支持嵌套）
    val menuStack = remember { mutableStateListOf<SubmenuLevel>() }
    // containerWindowY：容器在弹窗窗口内的相对 Y（anchorY 换算用，保持窗口坐标系不变）
    var containerWindowY by remember { mutableStateOf(0f) }
    // containerScreenY：容器在整块屏幕上的 Y（positionOnScreen）——弹窗窗口原点未必是屏幕原点
    // （状态栏偏移等），出屏判定必须用屏幕坐标，不能用窗口内相对坐标
    var containerScreenY by remember { mutableStateOf(0f) }

    // 子菜单叠放几何测量（仅在「子菜单高于父菜单且出屏」情形据此上扩覆盖，见下方分派）：
    // P = 父菜单卡片高、C = 子菜单卡片渲染高，均为 graphicsLayer 缩放前的布局像素
    var parentCardHeightPx by remember { mutableIntStateOf(0) }
    var childCardHeightPx by remember { mutableIntStateOf(0) }

    val topLevel = menuStack.lastOrNull()
    // 出栈的淡出动画期间保留最后层级内容
    var renderedLevel by remember { mutableStateOf<SubmenuLevel?>(null) }
    LaunchedEffect(topLevel) {
        if (topLevel != null) renderedLevel = topLevel
    }
    val levelToRender = topLevel ?: renderedLevel

    val overlayVisible = topLevel != null
    // 子菜单开/关只在**变化时**回调一次（长按弹层据此驱动预览卡的变暗+内缩）
    LaunchedEffect(overlayVisible) {
        onSubmenuOpenChange(overlayVisible)
    }
    // 父菜单：子菜单打开时保持显示，露出部分加暗色遮罩（iOS 父菜单变暗、部分被遮挡）
    val parentDimAlpha by animateFloatAsState(
        targetValue = if (overlayVisible) IOS_SUBMENU_PARENT_DIM_ALPHA else 0f,
        animationSpec = tween(durationMillis = IOS_SUBMENU_PARENT_ANIM_MS),
        label = "parentDimAlpha"
    )
    // 父菜单卡的内缩比例：子菜单打开时整体向内收（iOS 系统行为，与变暗同 spec）
    val parentShrink by animateFloatAsState(
        targetValue = if (overlayVisible) IOS_SUBMENU_PARENT_SHRINK else 1f,
        animationSpec = tween(durationMillis = IOS_SUBMENU_PARENT_ANIM_MS),
        label = "parentShrink"
    )
    // 子菜单：从头行位置向下缩放淡入（transformOrigin 顶部）
    val overlayAlpha by animateFloatAsState(
        targetValue = if (overlayVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "overlayAlpha"
    )
    val overlayScale by animateFloatAsState(
        targetValue = if (overlayVisible) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "overlayScale"
    )

    // ── 子菜单叠放几何（对齐 iOS 真机 UIMenu 分派，用户实测为规格；UIMenu 私有实现无源码可引）──
    // 三分派逻辑：
    //   情形 1（子菜单 ≤ 父菜单）：正常叠加——子菜单对位被点行向下伸展（childTop = anchorY），
    //     不做任何底边钳制/父菜单下移，辨识叠加靠父菜单顶部露出区域。
    //   情形 2.1（子菜单 > 父菜单，且自然向下展开会超出屏幕底部）：走「父菜单下移 + 底部留 SLIVER
    //     一条边、子菜单上扩覆盖」（复用既有几何），保证子菜单不出屏。
    //   情形 2.2（子菜单 > 父菜单，但自然向下展开不出屏）：仍正常叠加（childTop = anchorY），
    //     父菜单顶部露出区域已足够辨识，不保留底边。
    // SLIVER = 情形 2.1 下父菜单底部保留可见的一条边高度（让用户感知子菜单叠加在父菜单之上）
    val sliverPx = with(density) { 12.dp.roundToPx() }
    // 被点行相对容器顶部的偏移（正常叠加时头行对位用；首帧 C 未测得时也用它对位，即现状行为）
    val anchorYPx = levelToRender?.anchorY ?: 0
    // 屏幕底部界限（屏幕坐标系）：子菜单自然向下展开时其底边不得越过此线，越过即判定出屏
    val screenBottomLimitPx =
        with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() } -
            with(density) { SCREEN_BOTTOM_MARGIN.toPx() }
    // 子菜单按被点行自然向下展开时，其底边在屏幕上的位置是否越过底部界限（C 未测得时不判定出屏）
    val overflowsScreen = childCardHeightPx > 0 &&
        (containerScreenY + anchorYPx + childCardHeightPx) > screenBottomLimitPx
    // parentTopPad：仅情形 2.1（高于父菜单且出屏）时父菜单卡片下移，使其底边落在子菜单底边下方
    // SLIVER 处 —— 视觉即「子菜单覆盖父菜单、父菜单仅底部露一条边」；其余情形及出栈时为 0（父菜单不动）
    val parentTopPadTargetPx =
        if (overlayVisible && overflowsScreen) (childCardHeightPx + sliverPx - parentCardHeightPx).coerceAtLeast(0)
        else 0
    // childTop：情形 1 / 2.2 一律 = anchorY（正常叠加，无底边钳制）；仅情形 2.1 向上钳制，
    // 保证子菜单底边 ≤ 父菜单底边 - SLIVER。首帧 C 未测得时按 anchorY 对位（现状）。
    val childTopTargetPx =
        if (childCardHeightPx == 0) anchorYPx                       // 未测得：先按对位渲染
        else if (overflowsScreen) minOf(                            // 情形 2.1：上扩覆盖留底边
            anchorYPx,
            (parentCardHeightPx + parentTopPadTargetPx - sliverPx - childCardHeightPx)
                .coerceAtLeast(0)
        )
        else anchorYPx                                              // 情形 1 / 2.2：正常叠加，无底边钳制
    // 两值用 spring 过渡（风格对齐 overlayScale 的 spring(0.8f, 500f)），子菜单出栈时 parentTopPad 回 0
    val parentTopPad by animateDpAsState(
        targetValue = with(density) { parentTopPadTargetPx.toDp() },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "parentTopPad"
    )
    val childTop by animateDpAsState(
        targetValue = with(density) { childTopTargetPx.toDp() },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "childTop"
    )

    val openSubmenu: (IOSContextMenuItem, Float) -> Unit = { item, rowWindowY ->
        menuStack.add(
            SubmenuLevel(item, (rowWindowY - containerWindowY).toInt().coerceAtLeast(0))
        )
    }

    Box(
        modifier = modifier
            // iOS UIMenu 固定宽 250pt：此前按 IntrinsicSize.Max 收缩到最宽内容，
            // 导致 SpaceBetween 的子菜单行无富余空间、尾部 "›" 紧贴文本。
            // 固定宽后父/子卡片同宽不变，且箭头恒贴右边界（2026-08-01 真机校准）
            .width(IOS_MENU_WIDTH)
            .offset(x = offsetX, y = offsetY)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = TransformOrigin(transformOriginX, transformOriginY)
            }
            .onGloballyPositioned {
                containerWindowY = it.positionInWindow().y
                containerScreenY = it.positionOnScreen().y
            }
    ) {
        // 父菜单卡片（子菜单打开时保持显示，露出部分变暗）；
        // parentTopPad 顶部内边距在子菜单覆盖时将父菜单整体下移，使其仅底部露一条边（对齐 iOS 2.1）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // spring 过冲会产生负值，padding 前必须钳制（负 Dp 传 padding 会 IllegalArgumentException 崩溃）
                .padding(top = parentTopPad.coerceAtLeast(0.dp))
                // 子菜单打开时父菜单整体内缩：**只能挂在父菜单这一层**——外层容器 Box
                // 同时包着子菜单叠加卡，挂那里会把子菜单一起缩掉。
                // 收缩基准与入场 transformOrigin 同口径（靠屏幕近侧的那条边向内收）
                .graphicsLayer {
                    scaleX = parentShrink
                    scaleY = parentShrink
                    transformOrigin = TransformOrigin(if (expandLeft) 1f else 0f, 0f)
                }
        ) {
            IOSMenuCard(
                modifier = Modifier
                    .fillMaxWidth()
                    // 记录父菜单卡片高 P（onSizeChanged 在 animateContentSize 之前，读到的是当前渲染高）
                    .onSizeChanged { parentCardHeightPx = it.height }
                    .animateContentSize(  // RatingPalette 内联展开时的尺寸动画
                        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                    )
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    if (title != null) {
                        IOSMenuTitleRow(title)
                        IOSMenuGroupDivider()
                    }
                    IOSMenuItems(
                        items = items,
                        onDismissRequest = onDismissRequest,
                        onOpenSubmenu = openSubmenu
                    )
                }
            }
            // 暗色遮罩：拦截父菜单点击（子菜单期间父菜单不可交互），点遮罩返回上级
            if (parentDimAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = parentDimAlpha))
                        .clickable(
                            onClick = { menuStack.removeLastOrNull() },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        )
                )
            }
        }

        // 子菜单叠加卡片：childTop 顶部 padding 定位（撑大容器与 Popup 窗口，避免被裁剪，不用负 offset）。
        // 仅「子菜单高于父菜单且出屏」时上扩覆盖父菜单、留底边；其余情形正常叠加对位被点行——对齐 iOS 真机分派
        if (levelToRender != null && (overlayVisible || overlayAlpha > 0.01f)) {
            val level = levelToRender
            IOSMenuCard(
                modifier = Modifier
                    .fillMaxWidth()
                    // spring 过冲会产生负值，padding 前必须钳制（负 Dp 传 padding 会 IllegalArgumentException 崩溃）
                    .padding(top = childTop.coerceAtLeast(0.dp))
                    // 记录子菜单卡片渲染高 C（padding 在前，onSizeChanged 读到的是不含顶部 padding 的卡片本体高）
                    .onSizeChanged { childCardHeightPx = it.height }
                    .graphicsLayer {
                        this.alpha = overlayAlpha
                        scaleX = overlayScale
                        scaleY = overlayScale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
            ) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    when (val overlayItem = level.item) {
                        is IOSContextMenuItem.Submenu -> {
                            // 头行：与父菜单被点行同标题同起始位置；点击返回上级
                            IOSContextMenuSubmenuHeaderRow(
                                text = overlayItem.text,
                                icon = overlayItem.icon,
                                onBack = { menuStack.removeLastOrNull() }
                            )
                            // 头行与子项之间为组间关系：与父菜单同款分隔线
                            IOSMenuGroupDivider()
                            IOSMenuItems(
                                items = overlayItem.items,
                                onDismissRequest = onDismissRequest,
                                onOpenSubmenu = openSubmenu
                            )
                        }
                        is IOSContextMenuItem.RatingPalette -> {
                            // iOS displayAsPalette：同为覆盖式子菜单，子项为横向星星调色板；
                            // 选择评分只更新本地状态与回调，不关闭菜单（对齐 iOS）
                            var localRating by remember(level) {
                                mutableStateOf(overlayItem.currentRating)
                            }
                            IOSContextMenuSubmenuHeaderRow(
                                text = ratingDisplayText(localRating),
                                icon = if (overlayItem.icon != null) {
                                    if (localRating == 0) AmperfyIcons.starEmpty else AmperfyIcons.starFill
                                } else {
                                    null
                                },
                                onBack = { menuStack.removeLastOrNull() }
                            )
                            // 头行与调色板之间为组间关系：与父菜单同款分隔线
                            IOSMenuGroupDivider()
                            IOSRatingPaletteRow(
                                rating = localRating,
                                onRatingSelected = { rating ->
                                    localRating = rating
                                    overlayItem.onRatingSelected(rating)
                                }
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun IOSContextMenuActionItem(
    text: String,
    icon: ImageVector?,
    destructive: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,  // 副标题（对齐 iOS UIAction.subtitle），null 时不渲染第二行
    state: Boolean? = null,    // 选中态（对齐 iOS UIAction.state），null 时不渲染 state 列
    iconSize: Dp? = null,      // 图标尺寸覆盖，null 时用常规 22dp
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 点击时的缩放动画和背景高亮
    var isPressed by remember { mutableStateOf(false) }

    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pressedScale"
    )

    // iOS风格的背景颜色动画
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed && enabled) {
            MaterialTheme.colorScheme.pressHighlight  // iOS .systemGray4 按压高亮
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 100),
        label = "backgroundColor"
    )

    // 禁用状态使用灰色
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error  // iOS .systemRed
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(pressedScale)
            .background(backgroundColor)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        onClick = onClick,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = IOS_MENU_ROW_HORIZONTAL_PADDING, vertical = 11.dp),  // iOS的padding
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // state 列（在 image 列之前，对齐 UIKit）：.on 画小勾、.off 留同宽空位。
            // state 与 image 两列包进**同一个子 Row**，作为外层 spacedBy(12.dp) 的单个 child：
            // 勾↔图标收紧为 IOS_MENU_STATE_ICON_SPACING、图标↔文字仍为外层的 12dp
            // （对齐 iOS 真机上勾紧贴图标的观感）。state == null 时不包子 Row 走原路径，
            // 既有菜单（全项目绝大多数调用方）渲染逐像素不变
            if (state != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(IOS_MENU_STATE_ICON_SPACING),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state) {
                        Icon(
                            imageVector = AmperfyIcons.check,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(IOS_MENU_STATE_SLOT_SIZE)
                        )
                    } else {
                        Spacer(modifier = Modifier.size(IOS_MENU_STATE_SLOT_SIZE))
                    }

                    // icon == null 时子 Row 只含 state 槽，仍成立
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(iconSize ?: IOS_MENU_ICON_SIZE)
                        )
                    }
                }
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(iconSize ?: IOS_MENU_ICON_SIZE)  // iOS图标尺寸
                )
            }

            // 无副标题：渲染与旧版逐字节一致的单行标题（该组件被全项目大量复用）
            if (subtitle == null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.95  // iOS字体稍小
                    ),
                    color = textColor
                )
            } else {
                // 有副标题：标题下方叠一行灰色小字（对齐 iOS UIAction title/subtitle 两行结构）
                Column {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.95  // iOS字体稍小
                        ),
                        color = textColor
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp  // iOS subtitle 小号灰字
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 菜单卡片外观（父菜单与子菜单叠加卡片共用）
 */
@Composable
private fun IOSMenuCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp,
        tonalElevation = 0.dp
    ) {
        content()
    }
}

/**
 * 菜单项列表渲染（父菜单与子菜单共用；子菜单行点击经 onOpenSubmenu 入栈叠加）
 */
@Composable
private fun IOSMenuItems(
    items: List<IOSContextMenuItem>,
    onDismissRequest: () -> Unit,
    onOpenSubmenu: (IOSContextMenuItem, Float) -> Unit
) {
    items.forEachIndexed { index, item ->
        // 组内相邻项之间不画任何线（iOS 真机就是无线，见 IOSMenuGroupDivider 注释）
        when (item) {
            is IOSContextMenuItem.Action -> {
                IOSContextMenuActionItem(
                    text = item.text,
                    icon = item.icon,
                    destructive = item.destructive,
                    enabled = item.enabled,
                    subtitle = item.subtitle,
                    state = item.state,
                    iconSize = item.iconSize,
                    onClick = {
                        item.onClick()
                        onDismissRequest()
                    },
                )
            }

            is IOSContextMenuItem.Submenu -> {
                IOSContextMenuSubmenuRow(
                    text = item.text,
                    icon = item.icon,
                    subtitle = item.subtitle,
                    onOpen = { rowWindowY -> onOpenSubmenu(item, rowWindowY) }
                )
            }

            is IOSContextMenuItem.RatingPalette -> {
                // 覆盖式子菜单行（iOS displayAsPalette 的父行）：标题动态显示当前评分
                IOSContextMenuSubmenuRow(
                    text = ratingDisplayText(item.currentRating),
                    icon = if (item.icon != null) {
                        if (item.currentRating == 0) AmperfyIcons.starEmpty else AmperfyIcons.starFill
                    } else {
                        null
                    },
                    onOpen = { rowWindowY -> onOpenSubmenu(item, rowWindowY) }
                )
            }

            is IOSContextMenuItem.Divider -> {
                // 首尾位置与紧邻另一个 Divider 时跳过，避免菜单顶/底悬空或连续双线
                if (index > 0 &&
                    index < items.size - 1 &&
                    items[index - 1] !is IOSContextMenuItem.Divider
                ) {
                    IOSMenuGroupDivider()
                }
            }
        }
    }
}

/**
 * 菜单分隔线 - 对应 iOS UIMenu 各 displayInline 分组之间的分隔
 *
 * iOS 实机观感（2026-08-01 三轮校准定型）：**组内无线、组间一条内缩 hairline**。
 * 形态：hairline 厚（恒 1 物理像素），左右内缩到行内容边界（与菜单行共用 IOS_MENU_ROW_HORIZONTAL_PADDING，
 * 左端对齐行内图标左缘、右端对齐子菜单 "›" 右缘），颜色用 onSurface 半透明——
 * 浅色主题 onSurface 为黑呈灰线、深色主题为白呈亮线，两边自适应。
 * （此前试过 12dp 内缩 30% 透明细线 / 8dp 实色带 / 6dp 半透明暗缝 + 组内 hairline，
 * 均与真机不符，已废弃。）
 */
/**
 * 菜单标题行（对齐 iOS `UIMenu.lazyMenu(title:)` 的标题观感）——
 * 居中、footnote 级小字、secondaryLabel 色；下方分隔线由调用处补 [IOSMenuGroupDivider]
 */
@Composable
private fun IOSMenuTitleRow(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IOS_MENU_ROW_HORIZONTAL_PADDING, vertical = 8.dp)
    )
}

@Composable
private fun IOSMenuGroupDivider() {
    HairlineDivider(
        modifier = Modifier.padding(horizontal = IOS_MENU_ROW_HORIZONTAL_PADDING),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = IOS_MENU_DIVIDER_ALPHA)
    )
}

/**
 * 「贴锚点上方」模式下菜单底边与锚点顶边的间隙——对齐 iOS UIMenu 从按钮上弹时
 * 菜单与按钮之间的那道细缝观感
 */
private val IOS_MENU_ANCHOR_GAP = 8.dp

// 分隔线厚度常量已删除：组间线改用 HairlineDivider（恒 1 物理像素实绘，
// 修 Dp.Hairline 的 0 高 + 半像素摊薄导致 dark 下看不见，见 HairlineDivider 的 KDoc）

/** 分隔线的 onSurface 不透明度；真机偏淡/偏重时只调这一个值 */
private const val IOS_MENU_DIVIDER_ALPHA = 0.18f

/**
 * 菜单行左右内边距（iOS UIMenu 行留白）——行内容与分隔线共用同一值，
 * 保证分隔线两端恒与行内图标左缘 / 尾部 "›" 右缘对齐，不会各改各的而错位
 */
private val IOS_MENU_ROW_HORIZONTAL_PADDING = 16.dp

/**
 * 常规菜单行图标尺寸（iOS UIAction image 默认 pointSize 观感）——
 * 行可经 [IOSContextMenuItem.Action.iconSize] 单独覆盖（如账户行的放大头像）
 */
private val IOS_MENU_ICON_SIZE = 22.dp

/**
 * state 列（iOS UIAction.state 的系统选中勾）尺寸——约为图标列的 1/3，
 * 对齐真机上「勾在头像左侧、约头像 1/3 高」的观感；.off 行以同宽 Spacer 占位保持列对齐。
 * 账户行头像随 iOS pointSize 30 加大到 30dp 后，勾按同一 ~1/3 比例取 10dp
 */
private val IOS_MENU_STATE_SLOT_SIZE = 10.dp

/**
 * state 勾与其右侧图标之间的间距——小于外层列间距 12dp，
 * 对齐 iOS 真机上选中勾紧贴行图标（而非等距散开）的观感
 */
private val IOS_MENU_STATE_ICON_SPACING = 6.dp

/**
 * 菜单卡宽度 - 对应 iOS UIMenu 固定宽 250pt
 * 父菜单卡、子菜单叠加卡、长按弹层内的菜单容器三处共用同一口径
 */
private val IOS_MENU_WIDTH = 250.dp

/**
 * 菜单与屏幕**底部**的额外留白（叠加在导航栏 inset 之上）——对齐 iOS 菜单恒约束在安全区内。
 * 弹出式菜单（位置钳制 + 最大高）与长按弹层（安全区容器下留白）共用；观感微调只调这一个值。
 */
private val IOS_MENU_SCREEN_BOTTOM_MARGIN = 16.dp

/**
 * 导航区域高度的保底值：部分设备（手势导航隐藏提示条等）navigationBars inset 报 0，
 * 此时以 48dp（三键导航栏高度）兜底代表导航区域；真实 inset 更大时用真实值。
 * 与 [IOS_MENU_SCREEN_BOTTOM_MARGIN] 相加构成底部净空——菜单底边恒在整个导航区域**之上**再留留白。
 */
private val IOS_MENU_NAV_REGION_MIN_HEIGHT = 48.dp

/** 菜单与屏幕**顶部**的额外留白（叠加在状态栏 inset 之上），口径同底部留白 */
private val IOS_MENU_SCREEN_TOP_MARGIN = 12.dp

/**
 * 子菜单打开时父级（父菜单卡 / 长按弹层的预览卡）的收缩比例 —— iOS 系统行为：
 * 父级后退一层产生纵深感。1f = 不缩；真机偏轻/偏重时只调这一个值
 */
private const val IOS_SUBMENU_PARENT_SHRINK = 0.95f

/** 子菜单打开时父级上方黑色遮罩的不透明度（父菜单卡与预览卡共用） */
private const val IOS_SUBMENU_PARENT_DIM_ALPHA = 0.35f

/** 父级「变暗 + 内缩」的动画时长（毫秒），两者必须同 spec 才不会错拍 */
private const val IOS_SUBMENU_PARENT_ANIM_MS = 150

/**
 * 长按弹层预览卡的圆角 —— 与 EntityPreviewCard 的 Surface 圆角（13dp）同值，
 * 仅用于变暗遮罩的裁剪；改动时两处须同步
 */
private val IOS_PREVIEW_CARD_CORNER_RADIUS = 13.dp

/**
 * 子菜单行（父菜单中显示，尾部 "›"；点击后子菜单卡片叠加，头行与本行同位同名）
 */
@Composable
private fun IOSContextMenuSubmenuRow(
    text: String,
    icon: ImageVector?,
    onOpen: (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null   // 对齐 iOS UIMenu.subtitle，非空时渲染灰色小字第二行
) {
    var rowWindowY by remember { mutableStateOf(0f) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowWindowY = it.positionInWindow().y }
            .clickable(
                onClick = { onOpen(rowWindowY) },
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(horizontal = IOS_MENU_ROW_HORIZONTAL_PADDING, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 与 Action 行的 subtitle 同规格（12sp 灰字第二行）
            if (subtitle == null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.95
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Column {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.95
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Icon(
            imageVector = AmperfyIcons.chevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * 子菜单头行（叠加卡片首行）：标题/图标与父菜单被点行一致（同 16dp 起始位置），
 * 尾部 chevron **指向下**表示"已展开"（iOS 真机行为：父项箭头收起指右、展开转下）；
 * 点击返回上级
 */
@Composable
private fun IOSContextMenuSubmenuHeaderRow(
    text: String,
    icon: ImageVector?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClick = onBack,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(horizontal = IOS_MENU_ROW_HORIZONTAL_PADDING, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.95
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Icon(
            imageVector = AmperfyIcons.chevronDown,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * 评分标题文本："Rating: Not rated" / "Rating: X Star(s)"（父行与覆盖头行共用）
 */
private fun ratingDisplayText(rating: Int): String {
    val ratingText = if (rating == 0) "Not rated" else "$rating Star${if (rating > 1) "s" else ""}"
    return "Rating: $ratingText"
}

/**
 * 星星调色板行 - 对应iOS UIMenu.Options.displayAsPalette 的子项渲染：
 * 横向排列（禁止图标 = No Rating + 5 颗星），点击只回调不关闭菜单
 */
@Composable
private fun IOSRatingPaletteRow(
    rating: Int,
    onRatingSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // No Rating (禁止图标)
        IconButton(
            onClick = { onRatingSelected(0) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = AmperfyIcons.ban,
                contentDescription = "No Rating",
                tint = if (rating == 0)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }

        // 5颗星星
        (1..5).forEach { starIndex ->
            IconButton(
                onClick = { onRatingSelected(starIndex) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (rating >= starIndex)
                        AmperfyIcons.starFill
                    else
                        AmperfyIcons.starEmpty,
                    contentDescription = "$starIndex Star",
                    tint = if (rating >= starIndex)
                        MaterialTheme.colorScheme.gold  // iOS Utilities.gold (#F1C242)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
