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

package com.amperfy.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.HomeSection
import com.amperfy.ui.components.SheetSystemBarsFix
import com.amperfy.ui.components.iosOverscroll
import com.amperfy.ui.navigation.LocalMiniPlayerHeight
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.sheetGroupedBackground
import com.amperfy.ui.theme.sheetSecondaryGroupedBackground

/**
 * Home 偏好设置（W3，iOS: HomeEditorVC）
 *
 * 自下而上模态（对齐 iOS pageSheet）：ModalBottomSheet 完全展开，顶边止于状态栏下缘、顶圆角 10dp。
 * - 顶部栏：居中标题 "Home Preferences" + 右侧对号（iOS .done 系统项在真机渲染为对号图标）
 * - 内容为两段（"Visible"/"Hidden"）**跨段拖拽**列表（HomeEditorVC.swift:63-152）：
 *   iOS `isEditing = true` 下每行右侧是系统 reorder 控件（三横线），`editingStyle = .none` 无左侧附件，
 *   且未开 allowsSelectionDuringEditing 故行不可点选——**改显隐的唯一方式就是把行拖过段界**
 *   （moveRowAt :105-138：跨段即 show/hide，段内即重排）
 * - Visible 段顺序 = 用户拖出的顺序；Hidden 段顺序恒为 HomeSection 全集 rawValue 升序
 *   （iOS numberOfRows/sectionFor 均用 allCases.filter 现算，拖入 Hidden 的插入位置不生效，:73-87）
 * - Done → saveSections(Visible 段顺序)（对齐 iOS doneTapped :53-59 只持久化可见段）+ onDismiss
 * - 下滑关闭（onDismissRequest）= 放弃修改，不保存（对齐 iOS 模态下滑取消语义）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePreferencesSheet(
    onDismiss: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val visibleSections by viewModel.visibleSections.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 编辑工作副本：只在 Done 时写回持久化（对齐 iOS sections/visibility 内存态 + doneTapped 一次性回调）
    val editList = remember { mutableStateListOf<HomeSection>().apply { addAll(visibleSections) } }
    val drag = remember { HomeEditDragState() }

    // 隐藏段 = 全集差集按 rawValue 升序（对齐 iOS allCases.filter，HomeEditorVC.swift:77/85）
    val hidden = hiddenOf(editList)

    // 单一 items 区间的行模型：段头也是行（跨段拖动时被拖行始终留在同一 items 区间内，
    // key 不变 → 组合节点存活、手势不中断；对齐 Batch 3 播放器队列跨段拖动的同源做法）
    val rows = buildList {
        add(HomeEditRow.Header("Visible", "homeedit_header_visible"))
        editList.forEachIndexed { i, s -> add(HomeEditRow.Item(s, i, editList.lastIndex)) }
        add(HomeEditRow.Header("Hidden", "homeedit_header_hidden"))
        hidden.forEachIndexed { i, s -> add(HomeEditRow.Item(s, i, hidden.lastIndex)) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        // 顶边止于状态栏下缘 + 顶圆角 10dp（对齐 iOS pageSheet 模态外观）：
        // statusBarsPadding 加在 sheet 自身 modifier 上（M3 把它作用于 Surface 节点），
        // 故变矮的是**弹层本体**而非内容留白；同时它消费掉状态栏 inset，
        // 内层 contentWindowInsets 的默认 padding 自然归零，不会双重留白
        modifier = Modifier.statusBarsPadding(),
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        // 灰底（iOS HomeEditorVC style: .insetGrouped 的 systemGroupedBackground，:38）；
        // 模态呈现故取 elevated 提升层值（深色 #1C1C1E），行卡为
        // secondarySystemGroupedBackground @elevated（深色 #2C2C2E）→ 底上浮出分组卡
        containerColor = MaterialTheme.colorScheme.sheetGroupedBackground
    ) {
        // sheet 独立窗口的系统栏图标明暗归位（否则状态栏在弹出瞬间变黑）
        SheetSystemBarsFix()
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏：居中标题 + 右侧对号（对齐 iOS 模态导航栏 title + barButtonSystemItem .done）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Home Preferences",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center)
                )
                // iOS: `UIBarButtonItem(barButtonSystemItem: .done, …)`
                // （HomeEditorVC.swift:45-46）——系统 .done 渲染为**纯文字** "Done"（加粗），
                // 不是对号图标
                TextButton(
                    onClick = {
                        viewModel.saveSections(editList.toList())
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            }

            LazyColumn(
                // iOS 式过滚阻尼（弹层内滚动容器统一接入）
                modifier = Modifier.fillMaxSize().iosOverscroll(),
                contentPadding = PaddingValues(bottom = miniPlayerHeight)
            ) {
                itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                    when (row) {
                        is HomeEditRow.Header -> HomeEditSectionHeader(
                            title = row.title,
                            // 隐藏段头高度参与跨段位移补偿（见 resolveDrag）
                            modifier = if (row.key == "homeedit_header_hidden") {
                                Modifier
                                    .animateItem()
                                    .onSizeChanged {
                                        if (it.height > 0) drag.hiddenHeaderHeightPx = it.height
                                    }
                            } else Modifier.animateItem()
                        )

                        is HomeEditRow.Item -> HomeEditSectionRow(
                            section = row.section,
                            isFirst = row.indexInSegment == 0,
                            isLast = row.indexInSegment == row.lastIndexInSegment,
                            editList = editList,
                            drag = drag,
                            // 拖拽中的行不参与 placement 动画（手动 translationY，换位即时补偿）
                            modifier = if (drag.dragging == row.section) Modifier
                            else Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

/** 编辑列表的行模型（段头 + 实体行同处一个 items 区间，见 HomePreferencesSheet 注释） */
private sealed class HomeEditRow(val key: String) {
    class Header(val title: String, key: String) : HomeEditRow(key)

    class Item(
        val section: HomeSection,
        val indexInSegment: Int,
        val lastIndexInSegment: Int
    ) : HomeEditRow("homeedit_row_${section.name}")
}

/**
 * 跨段拖拽瞬态（Screen 级 remember 后由各行共享）
 *
 * 位置真相源只有 [HomePreferencesSheet] 的 editList：某 section 在 editList 内即 Visible 段、
 * 否则落 Hidden 段（顺序由 rawValue 现算）。拖动过程中只累计 [offsetY]，达阈值即就地改 editList
 * 并按「行版式位置变化量」反向补偿 offsetY，使被拖行在屏幕上连续跟手（与 EditableSectionList /
 * PlaylistEditSheet 的同款增量换位手法一致，此处多出跨段一步）。
 */
private class HomeEditDragState {
    /** 正在拖动的 section（null = 未拖动） */
    var dragging by mutableStateOf<HomeSection?>(null)

    /** 相对被拖行版式位置的累计位移（px） */
    var offsetY by mutableStateOf(0f)

    /** 行高（px，任一行测得即可——两段行版式相同） */
    var rowHeightPx by mutableStateOf(0)

    /** "Hidden" 段头高度（px），跨段位移补偿要跨过它 */
    var hiddenHeaderHeightPx by mutableStateOf(0)

    /** 拖动开始时的 editList 快照：手势被取消（多指/来电/组合销毁）时整体回滚 */
    var startSnapshot: List<HomeSection> = emptyList()
}

/** 隐藏段派生（全集差集按 rawValue 升序，对齐 iOS allCases.filter） */
private fun hiddenOf(visible: List<HomeSection>): List<HomeSection> =
    HomeSection.entries.filter { it !in visible }.sortedBy { it.rawValue }

/**
 * 拖动位移落点解析：按累计 [HomeEditDragState.offsetY] 就地改 editList
 *
 * - Visible 段内上/下越过半行 → 段内换位（对齐 iOS moveRowAt 同段分支 :131-135）
 * - Visible 末行继续下拖越过「隐藏段头 + 半行」→ 移出 editList = 隐藏（:120-125）；
 *   落点由 rawValue 现算，故补偿量 = 段头高 + 它在 Hidden 段的位置 p 行高
 * - Hidden 行上拖越过「它到段界的距离 + 半行」→ 加到 editList 末尾 = 显示（:115-119）；
 *   落到末尾后可继续上拖在 Visible 段内逐行上移到目标位置
 * - Hidden 段内拖动不换位：iOS 虽允许拖动，但 numberOfRows/sectionFor 用 allCases.filter 现算，
 *   插入位置随即被重排覆盖（:126-130 的 hidden 重排结果不参与 sections 可见段），等价于不可重排
 */
private fun HomeEditDragState.resolveDrag(editList: SnapshotStateList<HomeSection>) {
    val section = dragging ?: return
    val h = rowHeightPx
    if (h <= 0) return
    val hh = hiddenHeaderHeightPx

    val idx = editList.indexOf(section)
    if (idx >= 0) {
        // —— 位于 Visible 段 ——
        if (offsetY > h / 2 && idx < editList.lastIndex) {
            editList.add(idx + 1, editList.removeAt(idx))
            offsetY -= h
        } else if (offsetY < -h / 2 && idx > 0) {
            editList.add(idx - 1, editList.removeAt(idx))
            offsetY += h
        } else if (offsetY > hh + h / 2 && idx == editList.lastIndex) {
            // 跨段：隐藏。移除后它在 Hidden 段的实际落点 p 由 rawValue 决定
            editList.removeAt(idx)
            val p = hiddenOf(editList).indexOf(section).coerceAtLeast(0)
            offsetY -= (hh + p * h)
        }
    } else {
        // —— 位于 Hidden 段 ——
        val p = hiddenOf(editList).indexOf(section).coerceAtLeast(0)
        if (offsetY < -(hh + p * h + h / 2)) {
            // 跨段：显示。落到 Visible 段末尾（对齐 iOS 从下方拖入时的最近落点）
            editList.add(section)
            offsetY += (hh + p * h)
        }
    }
}

/**
 * 段头（iOS insetGrouped 分节标题样式）
 * 与 EditableSectionList 的分组头保持同一视觉规格（13sp SemiBold 灰字），
 * start padding 16dp 对齐本页行内容起点（行卡片 16 + 行内 16，无左侧附件）
 */
@Composable
private fun HomeEditSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.08).sp
        ),
        color = MaterialTheme.colorScheme.secondaryLabel,
        modifier = modifier.padding(start = 32.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/**
 * 编辑行：标题 + 右侧三横线拖拽手柄
 *
 * 对齐 iOS HomeEditorVC cellForRowAt（:89-99）：只有 textLabel，accessoryType = .none；
 * 编辑态右侧为系统 reorder 控件（三横线）。**行不可点击**——iOS 未开
 * allowsSelectionDuringEditing，didSelectRowAt 在编辑态是死路径（:154-165）。
 */
@Composable
private fun HomeEditSectionRow(
    section: HomeSection,
    isFirst: Boolean,
    isLast: Boolean,
    editList: SnapshotStateList<HomeSection>,
    drag: HomeEditDragState,
    modifier: Modifier = Modifier
) {
    val isDragging = drag.dragging == section
    // 各行独立为 lazy item 后拼合出整卡观感：圆角只保留在段内首末行
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(10.dp)
        isFirst -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
        isLast -> RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
        else -> RectangleShape
    }

    // 手柄按住即抬起行（对应 iOS reorder 控件的系统 lift 动画）
    var isHandlePressed by remember { mutableStateOf(false) }
    val liftProgress by animateFloatAsState(
        targetValue = if (isHandlePressed || isDragging) 1f else 0f,
        animationSpec = tween(200),
        label = "homeEditLift"
    )

    Column(
        modifier = modifier
            .zIndex(if (isDragging || liftProgress > 0f) 1f else 0f)
            .graphicsLayer {
                translationY = if (isDragging) drag.offsetY else 0f
                shadowElevation = 8.dp.toPx() * liftProgress
            }
            .onSizeChanged { if (it.height > 0) drag.rowHeightPx = it.height }
            .padding(horizontal = 16.dp)
            .clip(shape)
            // insetGrouped 分组卡底（iOS .secondarySystemGroupedBackground @elevated）
            .background(MaterialTheme.colorScheme.sheetSecondaryGroupedBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = section.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                AmperfyIcons.bars,
                contentDescription = "Reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    // 手柄独占触摸：消费按下事件并驱动 lift 动画
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            isHandlePressed = true
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                }
                            } finally {
                                isHandlePressed = false
                            }
                        }
                    }
                    .pointerInput(section) {
                        detectDragGestures(
                            onDragStart = {
                                drag.dragging = section
                                drag.offsetY = 0f
                                drag.startSnapshot = editList.toList()
                            },
                            onDragEnd = {
                                drag.dragging = null
                                drag.offsetY = 0f
                            },
                            onDragCancel = {
                                // 手势被取消：回滚到本次拖动开始时的工作副本
                                // （不回退到已持久化值——同一次编辑内的其他改动应保留）
                                val snapshot = drag.startSnapshot
                                drag.dragging = null
                                drag.offsetY = 0f
                                editList.clear()
                                editList.addAll(snapshot)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                drag.offsetY += dragAmount.y
                                drag.resolveDrag(editList)
                            }
                        )
                    }
            )
        }
        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }
    }
}
