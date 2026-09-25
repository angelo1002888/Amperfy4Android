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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.label
import com.amperfy.ui.theme.secondaryLabel
import com.amperfy.ui.util.DefaultArtworkType
import com.amperfy.ui.util.rememberDefaultArtworkPainter

/**
 * 长按预览卡片 - 对应 iOS EntityPreviewVC（EntityPreviewVC.swift:819-903 + EntityPreviewVC.xib）
 *
 * 布局参数 = **xib 原值 × ~0.8 观感系数**（2026-08-01 真机比对校准）：
 * iOS 系统对 context-menu 的预览 VC 有整体缩放显示（预览按容器宽度等比缩小，真机观感约 0.8 倍），
 * Android 的 Popup 没有这一层缩放，此前逐值照抄 xib（封面 80 / 字体 19-17-16 / margin 25-20）
 * 导致卡片整体明显大于 iOS 真机。现按 0.8 系数落到下列数值：
 * - 封面 64×64（xib 80）、圆角 5；无封面时占位图标 36（同比例，xib 44）
 * - 内容两侧 margin 20（xib 25）、上下 10（xib 20——上下留白此前约为 iOS 两倍）
 * - 封面与文字区 spacing 4 + 文字区左内边距 8；文字行间距 2（xib 3）
 * - 标题 16sp 主文本色；副标题 14sp tint 色（空则隐藏）；信息 13sp secondaryLabel
 *   三行均显式收紧 lineHeight = fontSize × 1.2：Compose 默认行高会额外撑开行间空隙，
 *   是「行距过大」观感的主因，仅调 fontSize 不够
 * - 三行文字均为跑马灯（MarqueeLabel applyAmperfyStyle）
 * - 尾部 chevron.forward 13×13 secondaryLabel（xib 16），不可导航时隐藏（isNavigationDisallowed）
 * - iOS 背景为 prominent 毛玻璃（setBackgroundBlur），Android 以 surface 色卡片近似
 *
 * @param coverArtModel AsyncImage 的 model（封面 URL；无封面传 null）
 * @param defaultArtworkType 封面缺失 / 加载中 / 失败时回落的默认艺术图**类型**
 *   （随账户主题色现画，对应 iOS `UIImage.generateArtwork`）。默认 null = 只留底色方块。
 *
 *   **传类型而不传 Painter 是刻意的**（2026-08-09 真机报障修复）：Painter 实例必须由本组件
 *   自持，绝不能与宿主行/卡共享——`DefaultArtworkPainter` 内包的 `VectorPainter` 是**有状态的**
 *   （矢量合成结果按上次绘制尺寸缓存在内部 DrawCache），同一实例被两个尺寸不同的绘制目标
 *   在同一帧交替驱动会互相污染缓存。报障现象：Home 页 Radio 卡长按时，卡片（160dp）与本预览卡
 *   （64dp）共用一个实例，卡片上多画出一个小号电台图标；列表项回收重组后 Painter 重建才消失
 * @param title 实体名（entityContainer.name）
 * @param subtitle 副标题（entityContainer.subtitle，歌曲=艺术家名；可空隐藏）
 * @param info 信息行（entityContainer.info(.long)，" · " 中点拼接；可空隐藏）
 * @param showChevron 是否显示尾部导航箭头（对应 iOS !isNavigationDisallowed）
 * @param onClick 点击卡片跳转详情（对应 iOS performPreviewTransition；null 不可点）
 * @param reserveSubtitleLine 副标题为空时是否仍占一个空行。对应 iOS
 *   `artistLabel.isHidden = (subtitle == nil)`——电台 creatorName 是空串而非 nil，
 *   标签可见但为空，故 iOS 卡片仍是「标题 / 空行 / info」三行。
 *   默认 false 保持既有调用方视觉不变：Android 既有调用方以空串表达「无副标题」
 *   （如 Song.artist 为非空类型、可能是 ""），若直接把判空语义改成 `subtitle != null`
 *   会给它们凭空多出一行，故做成按需开启
 */
@Composable
fun EntityPreviewCard(
    coverArtModel: Any?,
    title: String,
    subtitle: String?,
    info: String?,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    defaultArtworkType: DefaultArtworkType? = null,
    reserveSubtitleLine: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 预览卡自持 Painter 实例（见 defaultArtworkType 参数注释：禁止跨绘制目标共享）
    val defaultArtwork = defaultArtworkType?.let { rememberDefaultArtworkPainter(it) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 24.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 封面方块：底色与圆角上提到 Box，图像层铺满
            // （对应 iOS EntityPreviewVC 的 LibraryEntityImage：有真实封面用封面，
            // 取不到才回落 defaultArtworkType 的主题化生成图，LibraryEntityImage.swift:40-50）
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (coverArtModel != null || defaultArtwork != null) {
                    AsyncImage(
                        model = coverArtModel,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        // 三参同传现画的默认艺术图：加载中 / 失败 / model 为空均不露底色
                        placeholder = defaultArtwork,
                        error = defaultArtwork,
                        fallback = defaultArtwork
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MarqueeText(
                    text = title,
                    // lineHeight 显式收紧（fontSize × 1.2）：Compose 默认行高会多出可见空隙
                    style = TextStyle(fontSize = 16.sp, lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.label
                )
                // 副标题：空串时是否保留空行由 reserveSubtitleLine 决定（对齐 iOS
                // artistLabel 可见但为空的三行布局，见参数注释）
                if (!subtitle.isNullOrBlank() || (reserveSubtitleLine && subtitle != null)) {
                    MarqueeText(
                        text = subtitle.orEmpty(),
                        style = TextStyle(fontSize = 14.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (!info.isNullOrBlank()) {
                    MarqueeText(
                        text = info,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.secondaryLabel
                    )
                }
            }

            if (showChevron) {
                Icon(
                    imageVector = AmperfyIcons.chevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondaryLabel,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}
