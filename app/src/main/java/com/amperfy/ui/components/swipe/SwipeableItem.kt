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

package com.amperfy.ui.components.swipe

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.amperfy.data.model.SwipeActionType
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 通用 iOS 风格滑动列表项
 *
 * 对应 iOS: BasicTableViewController leading/trailingSwipeActionsConfigurationForRowAt
 * （UIKit 原生 UISwipeActionsConfiguration + UIContextualAction 行为的 Compose 复刻）
 *
 * 对齐的 iOS 原生行为：
 * - 按钮为全行高彩色矩形，无间距无圆角；白色图标居中、白色标题在图标下方（都在按钮内）
 * - 按钮宽度按标题自适应（BasicTableViewController.swift:316 title = displayName）
 * - 按钮从行边缘随内容揭示展开，内容保持自然尺寸被裁剪（不压缩）
 * - actions[0] 紧贴屏幕边缘；颜色按创建序号取 [蓝, 橙, 紫, 灰]，超出 4 个全部用灰
 *   （BasicTableViewController.swift:104-109 swipeButtonColors + element(at:) ?? .last）
 * - 全程滑动：拖过阈值后第一个动作按钮扩展铺满，松手自动触发
 *   （iOS performsFirstActionWithFullSwipe 默认 true，Amperfy 未关闭）
 * - 点击动作按钮 / 全程滑动触发时成功触觉反馈（对应 iOS Haptics.success，受设置开关控制）
 * - Favorite 按钮图标随收藏状态变化（对应 iOS heartFill / heartEmpty）
 * - REMOVE_FROM_QUEUE 对应 iOS 队列行系统默认 delete：红底纯文字 "Delete"
 *   （PopupPlayer+TableViewExtension.swift commit editingStyle:.delete）
 * - 同时只允许一个 item 处于滑出状态（SwipeController 协调）；
 *   有 item 滑出时，点击任意位置仅关闭它，不触发内容点击
 *
 * @param key 列表项唯一标识（供 SwipeController 协调）
 * @param swipeController 跨列表项滑动状态协调器
 * @param leadingActions 右滑显示的动作（已过滤）
 * @param trailingActions 左滑显示的动作（已过滤）
 * @param onSwipeAction 动作点击/全程滑动触发回调
 * @param isFavorite 当前内容是否已收藏（控制 FAVORITE 图标）
 * @param content 列表项内容（保留自身的点击处理）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableItem(
    key: Any,
    swipeController: SwipeController,
    leadingActions: List<SwipeActionType> = emptyList(),
    trailingActions: List<SwipeActionType> = emptyList(),
    onSwipeAction: (SwipeActionType) -> Unit = {},
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    content: @Composable () -> Unit
) {
    // 无任何动作时直接渲染内容，不挂载拖拽
    if (leadingActions.isEmpty() && trailingActions.isEmpty()) {
        Box(modifier = modifier) { content() }
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val settingsManager = LocalSettingsManager.current
    val isHapticsEnabled by settingsManager.isHapticsEnabled.collectAsState()
    val textMeasurer = rememberTextMeasurer()

    // 每个按钮的自然宽度 = max(最小宽度, 标题宽度 + 两侧留白)
    // 对应 iOS UIContextualAction 按 title 自适应宽度
    val minButtonPx = with(density) { SwipeButtonMinWidth.toPx() }
    val labelPaddingPx = with(density) { SwipeLabelHorizontalPadding.toPx() }
    fun naturalWidths(actions: List<SwipeActionType>): List<Float> = actions.map { action ->
        val labelWidth = textMeasurer.measure(action.swipeLabel, action.swipeLabelStyle).size.width
        max(minButtonPx, labelWidth + labelPaddingPx)
    }
    val leadingWidthsPx = remember(leadingActions) { naturalWidths(leadingActions) }
    val trailingWidthsPx = remember(trailingActions) { naturalWidths(trailingActions) }

    // 打开锚点距离 = 各按钮自然宽度之和（按钮延伸到屏幕边缘，iOS 无额外边距）
    val maxRightSwipePx = leadingWidthsPx.sum()
    val maxLeftSwipePx = trailingWidthsPx.sum()

    var rowWidthPx by remember { mutableStateOf(0) }
    // 全程滑动锚点：内容完全滑出行宽；若动作总宽已接近行宽则外扩，保证锚点在打开锚点之外
    val fullLeadingAnchorPx = max(rowWidthPx.toFloat(), maxRightSwipePx * 1.3f)
    val fullTrailingAnchorPx = max(rowWidthPx.toFloat(), maxLeftSwipePx * 1.3f)

    // 全程滑动落位回调经此转发：state 的 confirmValueChange 在构造期固定，
    // 而回调需要引用 state 自身与最新的 actions/onSwipeAction，故经 holder 间接引用解环
    val onFullSwipeRef = remember { mutableStateOf<(DragAnchors) -> Unit>({}) }

    val state = remember(key) {
        AnchoredDraggableState(
            initialValue = DragAnchors.Center,
            positionalThreshold = { distance -> distance * 0.5f },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            snapAnimationSpec = SpringSpec(
                dampingRatio = 1f,
                stiffness = 700f
            ),
            decayAnimationSpec = exponentialDecay(frictionMultiplier = 1.5f),
            confirmValueChange = { anchor ->
                if (anchor == DragAnchors.FullStart || anchor == DragAnchors.FullEnd) {
                    // 否决全滑锚点落位（iOS 松手即触发动作并弹回，不会停留在滑出状态）
                    onFullSwipeRef.value(anchor)
                    false
                } else true
            }
        )
    }

    SideEffect {
        onFullSwipeRef.value = { anchor ->
            val isTrailingSide = anchor == DragAnchors.FullEnd
            val openAnchorPx = if (isTrailingSide) maxLeftSwipePx else maxRightSwipePx
            val fullAnchorPx = if (isTrailingSide) fullTrailingAnchorPx else fullLeadingAnchorPx
            // 仅当实际拖过展开阈值（打开锚点与全滑锚点中点）才触发，
            // 防止从打开状态轻甩（velocityThreshold）误触第一个动作
            val engaged = state.requireOffset().absoluteValue >=
                openAnchorPx + (fullAnchorPx - openAnchorPx) * 0.5f
            val action = if (isTrailingSide) trailingActions.firstOrNull()
            else leadingActions.firstOrNull()
            if (engaged && action != null) {
                if (isHapticsEnabled) {
                    // 对应 iOS: Haptics.success.vibrate(isHapticsEnabled:)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onSwipeAction(action)
                coroutineScope.launch { state.animateTo(DragAnchors.Center) }
            } else {
                coroutineScope.launch {
                    state.animateTo(if (isTrailingSide) DragAnchors.End else DragAnchors.Start)
                }
            }
        }
    }

    // 监听滑动状态变化，通知 controller
    LaunchedEffect(state.currentValue, state.targetValue) {
        if (state.currentValue != DragAnchors.Center || state.targetValue != DragAnchors.Center) {
            swipeController.onSwipeStart(key, state, coroutineScope)
        } else {
            swipeController.onSwipeEnd(key)
        }
    }

    // 全程滑动展开进度：拖过中点阈值后 targetValue 变为 Full*，
    // 第一个动作按钮动画扩展铺满（iOS 的按钮"弹开"效果）
    val trailingExpansion by animateFloatAsState(
        targetValue = if (state.targetValue == DragAnchors.FullEnd) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "trailingFullSwipe"
    )
    val leadingExpansion by animateFloatAsState(
        targetValue = if (state.targetValue == DragAnchors.FullStart) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "leadingFullSwipe"
    )

    // 进入/退出全滑区域时轻触觉提示（对应 iOS 按钮弹开时的 tick）
    LaunchedEffect(state.targetValue) {
        if ((state.targetValue == DragAnchors.FullEnd ||
                state.targetValue == DragAnchors.FullStart) && isHapticsEnabled
        ) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val isAnyItemSwiped = swipeController.isAnyItemOpenState()
    val isThisItemSwiped = swipeController.isItemOpenState(key)

    fun closeAndDispatch(action: SwipeActionType) {
        if (isHapticsEnabled) {
            // 对应 iOS: Haptics.success.vibrate(isHapticsEnabled:)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        coroutineScope.launch {
            state.animateTo(DragAnchors.Center)
        }
        onSwipeAction(action)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .onSizeChanged { rowWidthPx = it.width }
    ) {
        // 更新锚点：仅为有动作的方向添加锚点；行宽已知后追加全滑锚点
        val anchors = DraggableAnchors {
            DragAnchors.Center at 0f
            if (leadingActions.isNotEmpty()) {
                DragAnchors.Start at maxRightSwipePx
                if (rowWidthPx > 0) DragAnchors.FullStart at fullLeadingAnchorPx
            }
            if (trailingActions.isNotEmpty()) {
                DragAnchors.End at -maxLeftSwipePx
                if (rowWidthPx > 0) DragAnchors.FullEnd at -fullTrailingAnchorPx
            }
        }
        state.updateAnchors(anchors)

        val offset = state.requireOffset()

        // 背景层 - iOS 原生样式动作按钮（全行高彩色矩形、无间距），从边缘随内容揭示
        if (offset < -0.5f && trailingActions.isNotEmpty()) {
            SwipeActionsBackground(
                actions = trailingActions,
                naturalWidthsPx = trailingWidthsPx,
                revealedPx = -offset,
                fullSwipeExpansion = trailingExpansion,
                isTrailing = true,
                isFavorite = isFavorite,
                colorScheme = colorScheme,
                onActionClick = ::closeAndDispatch,
                modifier = Modifier.matchParentSize()
            )
        } else if (offset > 0.5f && leadingActions.isNotEmpty()) {
            SwipeActionsBackground(
                actions = leadingActions,
                naturalWidthsPx = leadingWidthsPx,
                revealedPx = offset,
                fullSwipeExpansion = leadingExpansion,
                isTrailing = false,
                isFavorite = isFavorite,
                colorScheme = colorScheme,
                onActionClick = ::closeAndDispatch,
                modifier = Modifier.matchParentSize()
            )
        }

        // 内容层 - 可拖动
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background)
                .anchoredDraggable(state, Orientation.Horizontal)
        ) {
            content()

            // 有 item 滑出时拦截点击：仅关闭滑出项，不触发内容点击
            if (isAnyItemSwiped || isThisItemSwiped) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (isThisItemSwiped) {
                                coroutineScope.launch {
                                    state.animateTo(DragAnchors.Center)
                                }
                            } else {
                                swipeController.closeCurrentItem(coroutineScope)
                            }
                        }
                )
            }
        }
    }
}

// iOS UIContextualAction 按钮规格
private val SwipeButtonMinWidth = 74.dp
private val SwipeLabelHorizontalPadding = 22.dp
private val SwipeLabelStyle = TextStyle(fontSize = 12.sp)
private val SwipeDeleteLabelStyle = TextStyle(fontSize = 16.sp)

/**
 * 按钮标题：REMOVE_FROM_QUEUE 对齐 iOS 队列行系统默认 delete 的 "Delete"；
 * 其余动作对齐 iOS UIContextualAction(title: actionType.displayName)
 */
private val SwipeActionType.swipeLabel: String
    get() = if (this == SwipeActionType.REMOVE_FROM_QUEUE) "Delete" else displayName

private val SwipeActionType.swipeLabelStyle: TextStyle
    get() = if (this == SwipeActionType.REMOVE_FROM_QUEUE) SwipeDeleteLabelStyle else SwipeLabelStyle

/**
 * 滑动按钮背景区 - iOS 原生揭示布局
 *
 * - 揭示总宽 = |offset|，各按钮按自然宽度等比分摊
 * - iOS 显示顺序：actions[0] 紧贴屏幕边缘（trailing 在最右、leading 在最左）
 * - 全程滑动展开时 actions[0] 铺满揭示区、其余收缩为 0
 */
@Composable
private fun SwipeActionsBackground(
    actions: List<SwipeActionType>,
    naturalWidthsPx: List<Float>,
    revealedPx: Float,
    fullSwipeExpansion: Float,
    isTrailing: Boolean,
    isFavorite: Boolean,
    colorScheme: ColorScheme,
    onActionClick: (SwipeActionType) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val totalNaturalPx = naturalWidthsPx.sum().coerceAtLeast(1f)
    Box(
        modifier = modifier,
        contentAlignment = if (isTrailing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .width(with(density) { revealedPx.toDp() })
        ) {
            val order = if (isTrailing) actions.indices.reversed().toList()
            else actions.indices.toList()
            order.forEach { i ->
                val share = revealedPx * (naturalWidthsPx[i] / totalNaturalPx)
                val slicePx = lerp(share, if (i == 0) revealedPx else 0f, fullSwipeExpansion)
                SwipeActionButton(
                    action = actions[i],
                    backgroundColor = getSwipeButtonColor(actions[i], i, colorScheme),
                    naturalWidthPx = naturalWidthsPx[i],
                    isEdgeButton = i == 0,
                    isTrailing = isTrailing,
                    isFavorite = isFavorite,
                    onClick = { onActionClick(actions[i]) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(with(density) { slicePx.toDp() })
                )
            }
        }
    }
}

/**
 * 单个滑动按钮 - iOS UIContextualAction 原生样式
 *
 * 全行高纯色矩形，白色图标居中、白色标题在图标下方；
 * REMOVE_FROM_QUEUE 为红底纯文字 "Delete"（iOS 系统 delete 无图标）
 */
@Composable
private fun SwipeActionButton(
    action: SwipeActionType,
    backgroundColor: Color,
    naturalWidthPx: Float,
    isEdgeButton: Boolean,
    isTrailing: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // actions[0] 内容锚在屏幕边缘一侧（iOS 揭示时从边缘滑入、全程滑动展开时标题保持贴边），
    // 其余按钮内容在各自分区内居中
    val contentAlignment = when {
        isEdgeButton && isTrailing -> Alignment.CenterEnd
        isEdgeButton -> Alignment.CenterStart
        else -> Alignment.Center
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = contentAlignment
    ) {
        // requiredWidth 保持内容自然尺寸：揭示过程中内容被裁剪而非压缩（对齐 iOS）
        Box(
            modifier = Modifier.requiredWidth(with(density) { naturalWidthPx.toDp() }),
            contentAlignment = Alignment.Center
        ) {
            if (action == SwipeActionType.REMOVE_FROM_QUEUE) {
                Text(
                    text = action.swipeLabel,
                    color = Color.White,
                    style = SwipeDeleteLabelStyle,
                    maxLines = 1,
                    softWrap = false
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = action.getIcon(isFavorite),
                        contentDescription = action.swipeLabel,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = action.swipeLabel,
                        color = Color.White,
                        style = SwipeLabelStyle,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * 获取滑动按钮颜色 - iOS 风格
 *
 * 对应 iOS: BasicTableViewController.swipeButtonColors
 * [.defaultBlue, .systemOrange, .systemPurple, .systemGray]
 * 颜色按显示位置（createdActionsIndex）分配，超出 4 个全部用最后的灰色
 * （iOS element(at:) ?? .last，不循环）；REMOVE_FROM_QUEUE 固定红色（iOS 原生 delete）
 */
@Composable
internal fun getSwipeButtonColor(
    action: SwipeActionType,
    index: Int,
    colorScheme: ColorScheme
): Color {
    if (action == SwipeActionType.REMOVE_FROM_QUEUE) {
        return colorScheme.systemRed
    }
    val colors = listOf(
        colorScheme.systemBlue,
        colorScheme.systemOrange,
        colorScheme.systemPurple,
        colorScheme.systemGray
    )
    return colors.getOrElse(index) { colors.last() }
}
