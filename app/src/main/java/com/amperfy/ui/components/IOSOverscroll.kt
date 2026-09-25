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

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.min

/*
 * iOS 式过滚阻尼（rubber-band）—— 弹层内滚动容器专用（入口见文件末 Modifier.iosOverscroll）
 *
 * iOS UIScrollView 的 `alwaysBounceVertical` 行为：内容滚到边界后继续拖动，内容仍跟手但位移
 * **递减**（越拉越沉），松手以 spring 弹回原位；Android 12+ 平台默认是 stretch 拉伸，观感不同。
 * 本组件把弹层内滚动容器的过滚换成 iOS 口径的位移 + 回弹。
 *
 * **两侧越界都接管，但顶部一侧带门控**（2026-08-06 修复「弹层滚动中途下滑误关」）：
 * - 底部越界（内容已到底、手指继续上滑）：一律收编为 rubber-band。
 * - 顶部越界（内容已到顶、手指继续下滑）：只有**本次手势内容真滚动过**时才收编；
 *   手势起点即在顶部（本次手势内容一格没滚）则原样放行给 ModalBottomSheet ——
 *   那是弹层的拖拽关闭手势，不能修死。
 *
 * 顶部门控的依据是 iOS UISheetPresentationController 的语义：**手势开始时列表已在顶部**
 * 才进入弹层拖拽；滚动中途才到顶只触发内容自身的 rubber-band，惯性 fling 撞顶的残余速度
 * 同样只弹跳、不关层。Android 侧 M3 ModalBottomSheet 的
 * `consumeSwipeWithinBottomSheetBoundsNestedScrollConnection` 无此区分——它在 onPostScroll
 * 把**全部**剩余下拉量、在 onPostFling 把**全部**残余速度都灌给 anchoredDraggable，
 * 于是「列表滚到顶的同一手势里继续下滑 / 惯性撞顶」就会越过锚点阈值 settle 到 Hidden，
 * 即用户报障的「滚动中途下滑，弹层被拖走」。本连接排在 sheet 连接之内侧，
 * 门控命中时先行全额消费，sheet 便拿不到量。
 *
 * 实现要点：
 * - 位移经 [rubberBand] 由「手指累计位移」换算（与 iOS 经典 rubber-band 公式同形），
 *   松手用 spring 归零；容器自身的默认 overscroll（stretch）拿不到剩余量，自然不再出现
 *   （剩余量已被本连接在 onPostScroll 全额消费）。
 * - 放行给 sheet 的那条路径另加跟手阻尼 [SHEET_DRAG_DAMPING]，补偿 M3 过低的 settle 阈值。
 *   **已知限制**：从非滚动区域直接拖动弹层（PlaylistEdit 底部工具栏、Settings 收起态小标题栏、
 *   AddSongs 顶栏等）走的是 M3 Surface 自身的 anchoredDraggable，不经嵌套滚动链，
 *   吃不到本阻尼，该路径维持 M3 原生手感。
 * - 弹簧参数与阻尼系数为 UIKit 私有实现，只能按实机观感近似标定。
 */
// ========== 可调常量（按实机观感近似，iOS 曲线私有） ==========

/** rubber-band 系数：越大越"跟手"，iOS 经验值 0.55 */
private const val RUBBER_BAND_COEFFICIENT = 0.55f

/** 回弹弹簧阻尼比（略欠阻尼，落位时有极轻微收束感） */
private const val RELEASE_DAMPING_RATIO = 0.85f

/** 回弹弹簧刚度 */
private const val RELEASE_STIFFNESS = 400f

/** 惯性到底后的弹跳：允许的最大过冲比例（相对视口高度） */
private const val FLING_MAX_OVERSHOOT_RATIO = 0.25f

/** 视口高度未知时的兜底值（px），仅首帧可能用到 */
private const val FALLBACK_VIEWPORT_PX = 1000f

/**
 * 「放行给 ModalBottomSheet」路径上的跟手比例（阻尼系数 k）：只把 k 份位移/速度交给 sheet，
 * 其余 (1-k) 在本连接吸收掉。
 *
 * 缘由：M3 sheet 的 settle 阈值（位置 56dp / 速度 125dp/s）写死在 AnchoredDraggable 内部、
 * 无公开参数，远低于 iOS pageSheet 的关闭手感（用户实测「一碰就没」）。既然改不了阈值，
 * 就降低跟手比例——等效把阈值抬到 56dp/k 与 125dp/s/k。属近似手法，k 按实机观感调定。
 */
private const val SHEET_DRAG_DAMPING = 0.45f

/**
 * rubber-band 位移换算：给定手指累计位移 [pull]（>=0）与容器尺寸 [dim]，返回实际位移（>=0）。
 *
 * `offset = (1 - 1 / (pull * c / dim + 1)) * dim`：pull → 0 时近似线性跟手，
 * pull → ∞ 时渐近于 dim（拉不动），即 iOS 的「越拉越沉」。
 */
private fun rubberBand(pull: Float, dim: Float): Float =
    (1f - (1f / (pull * RUBBER_BAND_COEFFICIENT / dim + 1f))) * dim

/** [rubberBand] 的反函数：由当前位移反推手指累计位移，用于中途接手（回弹动画被新手势打断） */
private fun rubberBandInverse(offset: Float, dim: Float): Float {
    if (offset <= 0f) return 0f
    val clamped = min(offset, dim * 0.999f)
    return (dim / (dim - clamped) - 1f) * dim / RUBBER_BAND_COEFFICIENT
}

/**
 * 过滚位移状态：[offset] **带符号**——负 = 内容被向上拉出底边（底部越界），
 * 正 = 内容被向下拉出顶边（顶部越界），0 = 未过滚。
 *
 * 手指累计位移不单独存字段——每次事件由 [offset] 反推（[rubberBandInverse]），
 * 因此回弹动画被新手势打断时能从当前视觉位置无缝接手，不会跳变。
 */
@Stable
class IOSOverscrollState internal constructor() {
    var offset by mutableFloatStateOf(0f)
        private set

    /** 容器视口高度（px），由 onSizeChanged 实测 */
    internal var viewportPx = 0f

    private fun dim(): Float = if (viewportPx > 0f) viewportPx else FALLBACK_VIEWPORT_PX

    /** 当前位移对应的带符号手指累计位移（rubber-band 反函数，两侧对称） */
    private fun signedPull(dim: Float): Float =
        if (offset >= 0f) rubberBandInverse(offset, dim) else -rubberBandInverse(-offset, dim)

    /** 由带符号累计位移写回位移（两侧对称） */
    private fun applyPull(pull: Float, dim: Float) {
        offset = if (pull >= 0f) rubberBand(pull, dim) else -rubberBand(-pull, dim)
    }

    /**
     * 继续过滚：[delta] < 0 = 手指上滑（内容已到底），[delta] > 0 = 手指下滑（内容已到顶）。
     * 累计位移带符号相加，故同一手势内跨过零点也连续。
     */
    internal fun pull(delta: Float) {
        val dim = dim()
        applyPull(signedPull(dim) + delta, dim)
    }

    /**
     * 反向手势归位：[delta] 与当前 [offset] 反号。返回本次实际消费的量（带符号）——
     * 归位阶段同样走阻尼曲线，故手指回到起点时位移恰好归零（对齐 iOS）；
     * 只消费"回到零"所需的量，多余部分留给列表照常滚动。
     */
    internal fun returnBy(delta: Float): Float {
        val dim = dim()
        val pull = signedPull(dim)
        // |delta| 与 |pull| 取小；符号跟随 delta
        val used = min(abs(delta), abs(pull))
        applyPull(if (pull >= 0f) pull - used else pull + used, dim)
        return if (delta >= 0f) used else -used
    }

    /** 松手回弹到 0（[velocity] 为松手瞬时速度，px/s；两侧通用） */
    internal suspend fun settle(velocity: Float) {
        if (offset == 0f) return
        animate(
            initialValue = offset,
            targetValue = 0f,
            initialVelocity = velocity,
            animationSpec = spring(
                dampingRatio = RELEASE_DAMPING_RATIO,
                stiffness = RELEASE_STIFFNESS
            )
        ) { value, _ -> offset = value }
        offset = 0f
    }

    /**
     * 惯性撞边后的弹跳：以剩余速度过冲再弹回（对齐 iOS fling 撞顶/撞底的 bounce）。
     * 两侧通用——过冲量按视口比例双向钳制。
     */
    internal suspend fun bounce(velocity: Float) {
        val limit = dim() * FLING_MAX_OVERSHOOT_RATIO
        animate(
            initialValue = offset,
            targetValue = 0f,
            initialVelocity = velocity,
            animationSpec = spring(
                dampingRatio = RELEASE_DAMPING_RATIO,
                stiffness = RELEASE_STIFFNESS
            )
        ) { value, _ -> offset = value.coerceIn(-limit, limit) }
        offset = 0f
    }
}

/**
 * 过滚连接：底部越界一律收编；顶部越界只在「本次手势内容真滚动过」时收编，
 * 否则原样放行给上级（= ModalBottomSheet 的拖拽关闭）。
 */
private class IOSOverscrollConnection(
    private val state: IOSOverscrollState
) : NestedScrollConnection {

    /**
     * 本次手势是否已由**内容侧**持有——内容真滚动过（consumed≠0）**或**参与过 rubber-band
     * （任一侧 pull / 归位）即置位，手势结束（onPostFling 走完）复位。
     *
     * 它就是「手势起点是否已在顶部且全程未动过内容」的判据：起点即在顶部的下拉手势里，
     * 列表一格也滚不动、也不产生任何过滚，标记不置位 → 顶部剩余量放行给 sheet，
     * 弹层拖拽关闭原样保留。fling 阶段的 onPostScroll 源是 SideEffect，不参与置位也不清位，
     * 故惯性撞顶时本标记仍代表拖动阶段的事实。
     *
     * **rubber-band 也算持有**（2026-08-06 修复用户报障「Settings 上滑后马上下滑整页消失」）：
     * Settings 根页内容仅约一屏，上滑迅速滚尽后走的是底部 rubber-band，该路径 consumed 恒 0；
     * 若不置位，随后下滑把位移拉回 0、剩余下拉量便被放行给 sheet 而拖走弹层。
     * iOS 语义同样是「参与过 bounce 即由滚动视图持有手势，反向越零转顶部 bounce」。
     */
    private var contentScrolledInGesture = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 仍有过滚位移时，反向手势先把内容拉回原位，之后才轮到列表滚动（两侧对称）
        if (source == NestedScrollSource.UserInput && state.offset != 0f &&
            available.y != 0f && (state.offset > 0f) != (available.y > 0f)
        ) {
            // 归位同样算内容侧持有手势：正常路径下 offset≠0 必然已在收编分支置过位，
            // 此处是兜底——惯性弹跳动画被新手势打断时（offset 停在非零、标记已随上一手势
            // 复位），若该容器内容不足一屏（列表吃不掉剩余量），漏置位会让接下来的下拉
            // 落到放行分支拖走 sheet。补置位对「起点在顶部直接下拉」无影响：那条路径
            // offset 恒为 0，本分支根本进不来
            contentScrolledInGesture = true
            return Offset(0f, state.returnBy(available.y))
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (consumed.y != 0f) contentScrolledInGesture = true
        // available 非零 = 子容器已滚到边界。全额消费使容器默认 stretch 拿不到剩余量。
        val takeOver = when {
            available.y < 0f -> true                              // 底部越界：一律收编
            available.y > 0f -> contentScrolledInGesture          // 顶部越界：仅内容侧持有才收编
            else -> false
        }
        if (takeOver) {
            // 参与 rubber-band 即由内容侧持有本次手势（见字段注释的报障根因）
            contentScrolledInGesture = true
            state.pull(available.y)
            return Offset(0f, available.y)
        }
        // 放行给 sheet 的顶部下拉：只交出 k 份，其余吸收掉 —— 等效抬高 M3 的 settle 阈值，
        // 逼近 iOS pageSheet 的关闭手感（见 SHEET_DRAG_DAMPING）。
        // 向上（available.y < 0）不加阻尼：sheet 已全展开，本就无处可上移
        if (available.y > 0f) {
            return Offset(0f, available.y * (1f - SHEET_DRAG_DAMPING))
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // 松手时仍有过滚位移：吞掉速度并弹回（iOS 松手一律回弹，不再抛掷）。
        // 此处**不复位**手势标记——onPostFling 仍要据它判定顶部残余速度的归属
        if (state.offset != 0f) {
            state.settle(available.y)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // 惯性撞边仍有剩余速度：过冲一小段再弹回。
        // 顶部方向同样带门控——起点即在顶部的下拉甩动要把速度留给 sheet 完成 settle 关闭
        val takeOver = when {
            available.y < 0f -> true
            available.y > 0f -> contentScrolledInGesture
            else -> false
        }
        contentScrolledInGesture = false    // 手势到此结束，复位
        if (takeOver) {
            state.bounce(available.y)
            return available
        }
        // 放行给 sheet 的下拉残余速度：同样只交出 k 份（阻尼口径与 onPostScroll 一致，
        // 否则位移被阻尼、速度未阻尼，仍会被 125dp/s 的速度阈值一甩即关）
        if (available.y > 0f) {
            return Velocity(0f, available.y * (1f - SHEET_DRAG_DAMPING))
        }
        return Velocity.Zero
    }
}

/**
 * 给滚动容器接上 iOS 式过滚阻尼。
 *
 * 用法（本修饰符必须是滚动节点的**祖先**——同一条 modifier 链上排在其之前即可）：
 * ```
 * LazyColumn(modifier = Modifier.fillMaxSize().iosOverscroll()) { ... }
 * Column(modifier = Modifier.iosOverscroll().verticalScroll(state)) { ... }
 * ```
 * 也可挂在包含多个滚动容器的祖先节点上（nested scroll 沿树上传，一处覆盖全部子容器），
 * 代价是位移作用于整个子树而非仅列表内容。
 *
 * clipToBounds 排在 graphicsLayer **之前**：位移后的内容被裁回容器原始边界，
 * 底部越界不会溢出到下方工具栏、顶部越界不会盖到上方搜索栏 / 导航栏上。
 */
@Composable
fun Modifier.iosOverscroll(): Modifier {
    val state = remember { IOSOverscrollState() }
    val connection = remember(state) { IOSOverscrollConnection(state) }
    return this
        .clipToBounds()
        .onSizeChanged { state.viewportPx = it.height.toFloat() }
        .graphicsLayer { translationY = state.offset }
        .nestedScroll(connection)
}
