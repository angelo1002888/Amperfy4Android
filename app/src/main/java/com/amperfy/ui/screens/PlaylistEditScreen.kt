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

package com.amperfy.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.data.model.Song
import com.amperfy.ui.components.HairlineDivider
import com.amperfy.ui.components.SongListItem
import com.amperfy.ui.components.SongListItemCallbacks
import com.amperfy.ui.components.SongListItemStyle
import com.amperfy.ui.components.iosOverscroll
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.theme.separator
import com.amperfy.ui.theme.sheetBackground
import kotlin.math.hypot

/**
 * 编辑模式：reorder（拖拽重排）/ select（勾选删除）
 * 对应 iOS: PlaylistEditMode
 */
private enum class PlaylistEditMode { REORDER, SELECT }

/**
 * PlaylistEditSheet - 播放列表编辑（底部模态内容）
 *
 * 对应 iOS: PlaylistEditVC.swift（自下而上弹出的模态界面）
 *
 * - 单一 LazyColumn：头部（封面 + 名称 + 计数）是列表的第一个 item，**随列表滚动**
 *   （对应 iOS 详情头是 tableView.tableHeaderView，PlaylistEditVC.swift:102-109）。
 *   固定不动的只有顶部 Done 栏（navigationItem）与底部工具栏（toolbarItems）。
 * - 名称位置**恒为可编辑文本框**（对应 iOS GenericDetailTableHeader.startEditing :213-216 →
 *   refresh :168-175 隐藏 titleLabel、显示 nameTextField；点击即聚焦，无长按门控）。
 *   名称提交由宿主 PlaylistDetailScreen 在 Done/dismiss 时统一调 rename
 *   （对应 iOS viewDidDisappear → endEditing :218-233 才上传改名）。
 * - 歌曲行复用 [SongListItem]（自带左侧收藏、右侧缓存图标/时长），
 *   右侧附件随模式切换：REORDER 显示三横线手柄、SELECT 显示勾选/空心圆
 *   （对应 iOS PlayableTableCell.refresh :339-355 的 accessoryView）。
 * - 底部工具栏：Select/Reorder 切换、Add(+)、垃圾桶（对应 iOS toolbarItems :143）。
 * - REORDER 模式**整行长按抬起后拖动**重排（对应 iOS PlaylistEditVC.swift:114-116
 *   dragInteractionEnabled 的整行 lift，行尾三横线仅为装饰附件）；
 *   SELECT 模式勾选后由垃圾桶删除（无每行删除图标）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistEditSheet(
    name: String,
    onNameChange: (String) -> Unit,
    onDone: () -> Unit,
    onAddSongs: () -> Unit,
    viewModel: PlaylistEditViewModel = hiltViewModel()
) {
    val playlist by viewModel.playlist.collectAsState()
    val songs by viewModel.songs.collectAsState()

    var editMode by remember { mutableStateOf(PlaylistEditMode.REORDER) }
    // 按索引勾选（播放列表允许同一首歌重复出现，按 id 勾选会连带全部重复条目）
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 拖拽工作副本：非拖拽状态下与 songs 同步。重排本地先行——失败也已持久化新序，songs Flow
    // 必发射（成功新序 / 失败仍是新序），故只需依赖 songs，无需失败回退信号。
    // ReorderEntry 包装：key 随条目移动保持稳定（见 ReorderEntry.kt 根因说明）
    val items = remember { mutableStateListOf<com.amperfy.ui.util.ReorderEntry<Song>>() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(songs) {
        if (draggingIndex == null) {
            items.clear()
            items.addAll(com.amperfy.ui.util.buildReorderEntries(songs) { it.id })
        }
    }

    val lazyListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // 整行长按抬起（lift）时的触觉反馈：对齐 iOS UITableView drag 的系统 lift 轻触觉；
    // 调用与播放器队列（PopupPlayerQueueView）同源——同走 isHapticsEnabled 设置门控
    val haptics = LocalHapticFeedback.current
    val settingsManager = com.amperfy.ui.navigation.LocalSettingsManager.current
    val isHapticsEnabled by settingsManager.isHapticsEnabled.collectAsState()
    val hapticsCallback = remember(isHapticsEnabled) {
        { if (isHapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    /**
     * 拖动位移落点解析：累计位移越过半行高即就地换位并按行高补偿位移。
     *
     * 根级声明、各行共用——闭包捕获的 items / draggingIndex / dragOffsetY / rowHeightPx /
     * lazyListState 全部是 remember 出来的稳定引用，故被 `pointerInput(Unit)` 捕获首次实例后
     * 仍能读写最新值（行手势不因重组重启，见下方行 modifier 注释）。
     */
    val resolveReorder: () -> Unit = {
        val cur = draggingIndex
        val h = rowHeightPx
        if (cur != null && h > 0) {
            if (dragOffsetY > h / 2 && cur < items.lastIndex) {
                items.add(cur + 1, items.removeAt(cur))
                draggingIndex = cur + 1
                dragOffsetY -= h
                // 防 LazyColumn key 锚定跳变：交换涉及视口首行时，被拖条目会被布局到视口上方并遭懒回收，
                // 手势随组合销毁被取消（onDragCancel），重排提交丢失。以 index 重新锚定使视口保持稳定。
                // 未涉及首行时本调用等价 no-op。
                lazyListState.requestScrollToItem(
                    lazyListState.firstVisibleItemIndex,
                    lazyListState.firstVisibleItemScrollOffset
                )
            } else if (dragOffsetY < -h / 2 && cur > 0) {
                items.add(cur - 1, items.removeAt(cur))
                draggingIndex = cur - 1
                dragOffsetY += h
                // 同上：交换后以 index 重新锚定视口，防被拖条目出视口被回收
                lazyListState.requestScrollToItem(
                    lazyListState.firstVisibleItemIndex,
                    lazyListState.firstVisibleItemScrollOffset
                )
            }
        }
    }

    // 头部拼贴封面 URL（优先播放列表封面，否则前 4 首歌封面）
    val coverUrls = remember(playlist?.coverArt, songs) {
        buildList {
            val pc = playlist?.coverArt
            if (!pc.isNullOrBlank()) viewModel.getCoverArtUrl(pc)?.let { add(it) }
            if (isEmpty()) {
                addAll(
                    songs.mapNotNull { it.coverArt }.distinct().take(4)
                        .mapNotNull { viewModel.getCoverArtUrl(it) }
                )
            }
        }
    }

    // 状态栏避让由宿主 sheet 的 modifier 承担（顶边止于状态栏下缘，见 PlaylistDetailScreen
    // 的 Edit ModalBottomSheet），此处不再重复 statusBarsPadding
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部 Done 栏（对应 iOS refreshBarButtons :226-237：此处是**文字** "Done" 非对号图标）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        }

        // 头部 + 歌曲行同处一个 LazyColumn（头部随列表滚动，对齐 iOS tableHeaderView）
        LazyColumn(
            // iOS 式过滚阻尼（弹层内滚动容器统一接入）
            modifier = Modifier.weight(1f).fillMaxWidth().iosOverscroll(),
            state = lazyListState
        ) {
            item(key = "playlist_edit_header") {
                // 外层全宽（无横向 padding）：尾部的「头部与列表交界线」须画满两端，
                // 故把它抬出 padding(horizontal = 16) 的层级
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PlaylistMosaicCover(
                            coverUrls = coverUrls,
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // 名称恒为可编辑文本框（iOS UITextField borderStyle none：无边框、无占位符、
                        // compact 尺寸类居中，GenericDetailTableHeader.swift:161-166/168-175）
                        BasicTextField(
                            value = name,
                            onValueChange = onNameChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            // 键盘 Done 只收起键盘；改名上传由宿主在 Done/dismiss 时统一提交
                            // （对齐 iOS endEditing 时机）
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${songs.size} Song${if (songs.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondaryLabel
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 头部与歌曲列表的交界线 = iOS .grouped 表的 section 顶边界线，恒全宽（0..0）
                    HairlineDivider(
                        color = MaterialTheme.colorScheme.separator  // iOS .separator
                    )
                }
            }

            // key 用条目自带的稳定唯一 key（id#出现序号）：重复歌曲不冲突，且拖拽换位
            // 不改变 key——此前含索引的复合 key 会在换位时销毁重建节点、杀死进行中的
            // 拖拽手势（行悬停留空隙、重排不提交），见 ReorderEntry.kt
            //
            // 注意：itemsIndexed 给出的 index 是 [items] 内的下标（**不含**上方头部 item），
            // 故下方全部重排换算（draggingIndex / selectedIndices / items.add/removeAt）
            // 与头部并入 LazyColumn 之前完全一致，无需任何偏移补偿；
            // 唯一涉及 LazyColumn 下标的是 requestScrollToItem，它直接回读
            // firstVisibleItemIndex 重新锚定，同样与头部无关。
            itemsIndexed(items, key = { _, entry -> entry.key }) { index, entry ->
                val song = entry.item
                val isDragging = index == draggingIndex
                val reorder = editMode == PlaylistEditMode.REORDER
                val selected = index in selectedIndices
                // pointerInput 的手势块不随重组更新捕获值，用 rememberUpdatedState 取最新行索引
                val currentIndex by rememberUpdatedState(index)

                // 整行长按抬起（lift）——对应 iOS UITableView drag 的系统 lift 动画
                // （轻微放大 + 投影，松手落回）+ 系统 lift 轻触觉，
                // 触觉调用与播放器队列同源（见根级 hapticsCallback）
                var isLifted by remember { mutableStateOf(false) }
                val liftProgress by animateFloatAsState(
                    targetValue = if (isLifted || isDragging) 1f else 0f,
                    animationSpec = tween(200),
                    label = "reorderLift"
                )

                // 行级修饰：整行长按拖拽 + 拖拽时的位移与层级。
                //
                // 拖拽触发方式为**整行长按抬起后拖动**，对齐 iOS PlaylistEditVC
                // （`dragDelegate` + `dropDelegate` + `dragInteractionEnabled = true`，
                //  PlaylistEditVC.swift:114-116 + 数据源 moveRowAt）：行尾三横线只是
                // PlayableTableCell 的 accessoryView 装饰、不是拖拽把手
                // （PlayableTableCell.swift:352-355）。此前 Android 用的「手柄即时拖拽」
                // iOS 并不存在，已删除。手法与播放器队列（PopupPlayerQueueView）同源，
                // 区别是本页行**没有**上下文菜单（PlaylistEditVC 未设
                // containableAtIndexPathCallback），故 lift 后没有「按住不动出菜单」的计时，
                // 直接进入拖拽。
                //
                // 三个刻意选择（同 PopupPlayerQueueView）：
                // 1) 手势节点排在 graphicsLayer **之前**——排在其后会落入本行的变换层，
                //    指针局部坐标被 translationY 反向抵消，位移测量与实际手指位移不符；
                // 2) 手势不能以行索引为 key——换位会重启手势并杀死本次拖动，故固定 Unit，
                //    行索引一律经 rememberUpdatedState 现读；
                // 3) 长按前的移动不消费任何事件，列表纵向滚动照常抢占（它一旦消费事件，
                //    detectDragGesturesAfterLongPress 的长按判定即取消）。
                // 行内单击与本手势共存：SongListItem 在有 trailingContent 时
                // isLongPressMenuEnabled = false，其 combinedClickable 的 onLongClick 传 null
                // （非 null 会 consumeUntilUp 吞掉后续事件、抢死行级长按），单击不受影响。
                // SELECT 模式不挂手势（对齐 iOS isMoveAllowed 仅 reorder 态为真）。
                val rowModifier = Modifier
                    .zIndex(if (isDragging || liftProgress > 0f) 1f else 0f)
                    .then(
                        if (!reorder) Modifier else Modifier.pointerInput(Unit) {
                            // 越过 touchSlop 前的累计位移（px）：lift 后手指微颤不应立刻触发重排
                            var pendingX = 0f
                            var pendingY = 0f
                            var reordering = false
                            var startOrder: List<String> = emptyList()
                            val slop = viewConfiguration.touchSlop
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    pendingX = 0f
                                    pendingY = 0f
                                    reordering = false
                                    startOrder = items.map { it.item.id }
                                    isLifted = true
                                    hapticsCallback()
                                    draggingIndex = currentIndex
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    val order = items.map { it.item.id }
                                    draggingIndex = null
                                    dragOffsetY = 0f
                                    isLifted = false
                                    // 只 lift 未拖动、或顺序未变：不提交（对齐 iOS lift 松手仅落回）
                                    if (reordering && order != startOrder) viewModel.reorder(order)
                                    reordering = false
                                },
                                onDragCancel = {
                                    draggingIndex = null
                                    dragOffsetY = 0f
                                    isLifted = false
                                    reordering = false
                                    // 手势被取消（如组合销毁/父级拦截）：回退工作副本到数据源当前顺序，
                                    // 避免 UI 显示未提交的顺序与 DB 分叉
                                    items.clear()
                                    items.addAll(
                                        com.amperfy.ui.util.buildReorderEntries(songs) { it.id }
                                    )
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (!reordering) {
                                        pendingX += dragAmount.x
                                        pendingY += dragAmount.y
                                        if (hypot(pendingX, pendingY) > slop) {
                                            reordering = true
                                            dragOffsetY += pendingY
                                            resolveReorder()
                                        }
                                    } else {
                                        dragOffsetY += dragAmount.y
                                        resolveReorder()
                                    }
                                }
                            )
                        }
                    )
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffsetY else 0f
                        val liftScale = 1f + 0.03f * liftProgress
                        scaleX = liftScale
                        scaleY = liftScale
                        shadowElevation = 8.dp.toPx() * liftProgress
                    }
                    .onSizeChanged { if (it.height > 0) rowHeightPx = it.height }

                SongListItem(
                    song = song,
                    style = SongListItemStyle.ARTWORK,
                    modifier = rowModifier,
                    // 行底与宿主 sheet 同底（iOS cell backgroundColor 恒等于
                    // tableView.backgroundColor = systemBackground @elevated）
                    backgroundColor = MaterialTheme.colorScheme.sheetBackground,
                    trailingContent = {
                        if (reorder) {
                            // 行尾三横线为**纯装饰**附件（对应 iOS PlayableTableCell.swift:352-355
                            // 图像 .bars、tint .labelColor）——不是拖拽触发器，拖拽一律由整行
                            // 长按抬起发起（见上方 rowModifier）。它属于行的一部分，
                            // 从其上长按同样能起拖。
                            Icon(
                                AmperfyIcons.bars,
                                contentDescription = "Reorder",
                                tint = MaterialTheme.colorScheme.label
                            )
                        } else {
                            // 右侧勾选/空心圆（对应 iOS selection 模式
                            // PlayableTableCell.swift:339-345：选中 `.checkmark` tint 账户主题色、
                            // 未选 `.circle` tint .secondaryLabelColor）。
                            // **注意**：此处的 `.checkmark` 是 **UIKit 内建 `UIImage.checkmark`**
                            // （全仓无自定义重载），渲染形态为**填充圆内反挖白钩**的圆徽，
                            // 不是 SF 字符串 "checkmark" 的裸勾——后者是 AmperfyImage.check
                            // （UIImageAssetsExtension.swift:158,301），只用于菜单单选打勾。
                            // 故已选态取 [AmperfyIcons.isSelected]（checkmark.circle.fill 同形）。
                            Icon(
                                imageVector = if (selected) AmperfyIcons.isSelected else AmperfyIcons.circle,
                                contentDescription = if (selected) "Selected" else "Not selected",
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondaryLabel
                            )
                        }
                    },
                    callbacks = SongListItemCallbacks(
                        onClick = {
                            // REORDER 模式点击行无动作（对应 iOS didSelectRowAt :258-260
                            // guard editMode == .delete，且 .reorder 时行单击手势被禁用）
                            if (!reorder) {
                                selectedIndices = selectedIndices.toMutableSet().apply {
                                    if (!add(index)) remove(index)
                                }
                            }
                        }
                    )
                )
            }
        }

        // 底部工具栏：Select/Reorder | + | 垃圾桶（对应 iOS toolbarItems
        // [selectBarButton, flexible, addBarButton, flexible, deleteBarButton]，:143）
        // 顶部 hairline（iOS UIToolbar 与内容之间的系统细线）
        HairlineDivider(
            color = MaterialTheme.colorScheme.separator
        )
        // 底色与 sheet 同底（iOS UIToolbar 在模态内与页底同色，仅靠顶部 hairline 分隔），
        // 不再用 tonalElevation 抬色——否则在 elevated 底色上会显出一条异色条带
        Surface(color = MaterialTheme.colorScheme.sheetBackground) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 标题在 Select ↔ Reorder 间切换（对应 iOS selectBarButtonPressed :174-179）
                TextButton(onClick = {
                    editMode = if (editMode == PlaylistEditMode.REORDER) PlaylistEditMode.SELECT
                    else PlaylistEditMode.REORDER
                    selectedIndices = emptySet()
                }) {
                    Text(if (editMode == PlaylistEditMode.REORDER) "Select" else "Reorder")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onAddSongs) {
                    Icon(AmperfyIcons.plus, contentDescription = "Add Songs")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        // 删除后停留在 SELECT 模式，仅清空选中（对应 iOS PlaylistEditVC）
                        viewModel.removeSongs(selectedIndices)
                        selectedIndices = emptySet()
                    },
                    // 无选中项时禁用（对应 iOS refreshDeleteButton :181-183；
                    // 切回 REORDER 时 iOS 也清空 selectedItems，等价于同样禁用）
                    enabled = editMode == PlaylistEditMode.SELECT && selectedIndices.isNotEmpty()
                ) {
                    Icon(
                        AmperfyIcons.trash,
                        contentDescription = "Delete Selected",
                        // 可用态为账户主题色：iOS deleteBarButton 是 .plain 无自定 tintColor
                        // 的 UIBarButtonItem，跟随 toolbar tint（= 主题色）而非红色
                        // （PlaylistEditVC.swift:131-136）
                        tint = if (editMode == PlaylistEditMode.SELECT && selectedIndices.isNotEmpty())
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}
