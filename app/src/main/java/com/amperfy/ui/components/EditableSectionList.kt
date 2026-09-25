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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel

/**
 * 编辑态两段式列表（泛型，从 LibraryScreen 的编辑模式抽取，供 Library / Home 编辑器共用）
 *
 * 对应 iOS 编辑态单 section 两段结构（LibraryNavigatorConfigurator.swift:265-268）：
 * - 显示区（[editList]）= 左侧选中标记 + 右侧拖拽把手（.reorder() accessory）
 * - 隐藏区（[hidden]，由调用方派生：全集差集，通常按某种稳定顺序排序）= 左侧未选中标记，无把手
 * - 点击切换显隐（didSelectItemAt）：选中 → 移到显示区末尾；取消 → 从显示区移除（回落隐藏区，
 *   隐藏区排序由调用方 [hidden] 的派生规则决定）
 * - 拖拽仅限显示区内（iOS canReorderItem 仅 isSelected）
 * - 两段共用同一 key 命名前缀，check/uncheck 跨段移动经 animateItem 位移动画
 *   （对应 iOS dataSource.apply(snapshot, animatingDifferences: true)）
 *
 * 行为等价保证：check/uncheck 直接改动传入的 [editList]（SnapshotStateList），拖拽在
 * [editList] 内换位——与 LibraryScreen 原实现逐条一致。Done 时由调用方一次性持久化 editList。
 *
 * @param editList 显示区工作副本（SnapshotStateList，就地改动；调用方在 Done 时读取快照持久化）
 * @param hidden 隐藏区列表（调用方派生：全集 - editList，按需排序）
 * @param drag 拖拽瞬态（调用方在 Screen 级 remember 后传入，对齐原 LibraryScreen 的 hoist 方式）
 * @param keyPrefix LazyColumn item key 前缀（不同调用方隔离，避免复用同页多列表冲突）
 * @param stableId 稳定标识（key 用，需在 editList/hidden 间对同一逻辑项返回同值以触发跨段动画）
 * @param label 行标题
 * @param leadingIcon 行左侧实体图标（可空：Home 等无图标场景传 null，仅显示选中标记 + 标题）
 * @param visibleHeader 显示区分组头文案（可空：非空时在显示区行前渲染 iOS insetGrouped 分节小标题，
 *   对齐 iOS HomeEditorVC 的 "Visible" 分组；默认 null 时不渲染——LibraryScreen 调用点渲染完全不变）
 * @param hiddenHeader 隐藏区分组头文案（可空：语义同上，对齐 iOS "Hidden" 分组）
 */
fun <T> LazyListScope.editableSectionList(
    editList: SnapshotStateList<T>,
    hidden: List<T>,
    drag: EditableSectionDragState,
    keyPrefix: String,
    stableId: (T) -> String,
    label: (T) -> String,
    leadingIcon: ((T) -> ImageVector)? = null,
    visibleHeader: String? = null,
    hiddenHeader: String? = null,
) {
    val totalCount = editList.size + hidden.size
    // 分组头启用时（任一 header 非空）显示区/隐藏区各自当作独立 insetGrouped 卡片计算圆角：
    // 显示区末行圆下、隐藏区首行圆上；null/null（LibraryScreen）保持「整体一张连续卡片」旧语义不变。
    val sectioned = visibleHeader != null || hiddenHeader != null

    // 显示区分组头（对齐 iOS HomeEditorVC numberOfSections==2 固定两段，非空即渲染）
    if (visibleHeader != null) {
        item(key = "${keyPrefix}_header_visible") { EditableSectionHeader(visibleHeader) }
    }

    itemsIndexed(editList, key = { _, item -> "${keyPrefix}_${stableId(item)}" }) { index, item ->
        val isDragging = index == drag.draggingIndex
        EditableSectionRow(
            label = label(item),
            leadingIcon = leadingIcon?.invoke(item),
            checked = true,
            isFirst = index == 0,
            // 分组模式下显示区独立成卡：末行 = editList 末行；否则沿用跨两段的连续卡片判定
            isLast = if (sectioned) index == editList.lastIndex else index == totalCount - 1,
            editList = editList,
            item = item,
            drag = drag,
            index = index,
            // 拖拽中的行不参与 placement 动画（手动 translationY，换位即时补偿）
            modifier = if (isDragging) Modifier else Modifier.animateItem()
        )
    }

    // 隐藏区分组头（同上，非空即渲染于隐藏区行前）
    if (hiddenHeader != null) {
        item(key = "${keyPrefix}_header_hidden") { EditableSectionHeader(hiddenHeader) }
    }

    itemsIndexed(hidden, key = { _, item -> "${keyPrefix}_${stableId(item)}" }) { index, item ->
        EditableSectionRow(
            label = label(item),
            leadingIcon = leadingIcon?.invoke(item),
            checked = false,
            // 分组模式下隐藏区独立成卡：首行 = 本段首行、末行 = hidden 末行；否则沿用连续卡片判定
            isFirst = if (sectioned) index == 0 else editList.isEmpty() && index == 0,
            isLast = if (sectioned) index == hidden.lastIndex else editList.size + index == totalCount - 1,
            editList = editList,
            item = item,
            drag = drag,
            index = index,
            modifier = Modifier.animateItem()
        )
    }
}

/**
 * 编辑态拖拽状态（调用方 Screen 级 remember 后传入各行共享，对齐原 LibraryEditDragState）。
 */
class EditableSectionDragState {
    var draggingIndex by mutableStateOf<Int?>(null)
    var dragOffsetY by mutableStateOf(0f)
    var rowHeightPx by mutableStateOf(0)
}

/**
 * 编辑态分组头（iOS insetGrouped 分节标题样式，供显示区/隐藏区分段使用）。
 * 小号灰字、SemiBold、紧凑字距，与项目内既有 SectionHeader 惯例一致；
 * start padding 32dp 对齐行内容起点（行卡片 16 + 行内 16 = 选中标记左缘）。
 */
@Composable
private fun EditableSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.08).sp
        ),
        color = MaterialTheme.colorScheme.secondaryLabel,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun <T> EditableSectionRow(
    label: String,
    leadingIcon: ImageVector?,
    checked: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    editList: SnapshotStateList<T>,
    item: T,
    drag: EditableSectionDragState,
    index: Int,
    modifier: Modifier = Modifier
) {
    val isDragging = checked && index == drag.draggingIndex
    // pointerInput 手势块不随重组更新捕获值，用 rememberUpdatedState 取最新行索引
    val currentIndex by rememberUpdatedState(index)
    // 各行独立为 lazy item 后拼合出整卡观感：圆角只保留在首末行
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(10.dp)
        isFirst -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
        isLast -> RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
        else -> RectangleShape
    }
    // 分隔线缩进对齐文字起点：有图标 = 16+22(标记)+12+28(图标)+16 = 94；无图标 = 16+22+12 = 50
    val dividerStart = if (leadingIcon != null) 94.dp else 50.dp

    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = if (isDragging) drag.dragOffsetY else 0f }
            .onSizeChanged { if (it.height > 0) drag.rowHeightPx = it.height }
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                // 点击切换显隐（iOS didSelectItemAt）：选中 → 移到显示区末尾；取消 → 从显示区移除
                if (checked) editList.remove(item) else editList.add(item)
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 选中/未选中标记在最左（iOS placement .leading，SelectionAccessory.swift:27-41）：
            // 已选 = .isSelected（"checkmark.circle.fill" 实心圆反挖勾，SelectionAccessory.swift:36），
            // 未选 = .unSelected（"circle"）；由 LibraryNavigatorConfigurator.swift:332-334 装配。
            // 注：列表行选择/添加模式（PlayableTableCell.swift:339-351）虽写作 `.checkmark`，
            // 但那是 UIKit 内建 UIImage.checkmark，渲染同为本圆徽形——两处同形不同出处
            Icon(
                if (checked) AmperfyIcons.isSelected else AmperfyIcons.circle,
                contentDescription = if (checked) "Visible" else "Hidden",
                tint = if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = label,
                    tint = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = if (checked) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (checked) {
                // 拖拽把手（iOS 仅已选项加 .reorder() accessory；拖拽仅限显示区内）
                Icon(
                    AmperfyIcons.bars,
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        // 手柄独占触摸：消费按下，避免误触发行点击（隐藏该项）
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false).consume()
                            }
                        }
                        .pointerInput(item) {
                            detectDragGestures(
                                onDragStart = {
                                    drag.draggingIndex = currentIndex
                                    drag.dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    drag.draggingIndex = null
                                    drag.dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    drag.draggingIndex = null
                                    drag.dragOffsetY = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    drag.dragOffsetY += dragAmount.y
                                    val cur = drag.draggingIndex
                                    val h = drag.rowHeightPx
                                    if (cur != null && h > 0) {
                                        if (drag.dragOffsetY > h / 2 && cur < editList.lastIndex) {
                                            editList.add(cur + 1, editList.removeAt(cur))
                                            drag.draggingIndex = cur + 1
                                            drag.dragOffsetY -= h
                                        } else if (drag.dragOffsetY < -h / 2 && cur > 0) {
                                            editList.add(cur - 1, editList.removeAt(cur))
                                            drag.draggingIndex = cur - 1
                                            drag.dragOffsetY += h
                                        }
                                    }
                                }
                            )
                        }
                )
            }
        }
        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = dividerStart),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }
    }
}
