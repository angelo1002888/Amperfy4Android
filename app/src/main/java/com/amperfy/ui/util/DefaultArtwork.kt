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

package com.amperfy.ui.util

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.amperfy.data.model.Playable
import com.amperfy.ui.theme.AmperfyIcons

/** iOS ArtworkIconSizeType.big = 20 insets → (200-40)/200（UIImageAssetsExtension.swift:30-33） */
private const val ICON_RATIO_BIG = 0.8f

/** iOS ArtworkIconSizeType.small = 50 insets → (200-100)/200（同上） */
private const val ICON_RATIO_SMALL = 0.5f

/** 浅色模式背景灰阶 0.85（iOS UIImageAssetsExtension.swift:496） */
private val ArtworkBackgroundLight = Color(0xFFD9D9D9)

/** 深色模式背景灰阶 0.15（同上） */
private val ArtworkBackgroundDark = Color(0xFF262626)

/**
 * 默认艺术图（封面缺失时的回退图）的统一出处。
 *
 * 对应 iOS `UIImage.generateArtwork(theme:lightDarkMode:artworkType:)`
 * （AmperfyKit/Assets/UIImageAssetsExtension.swift:439-473）与其底层
 * `UIImage.createArtwork(...)`（同文件 :481-508）——iOS 的默认图不是随包的静态图片，
 * 而是**按账户主题色现画**的：
 *
 * - 画布 200×200（`ArtworkIconSizeType.defaultSize`，:33）；
 * - 背景为灰阶方块，浅色模式 0.85、深色模式 0.15（:496-500）；
 * - 图标着色 = 主题色 `theme.asColor`（:500-502）；
 * - 图标内缩由 `ArtworkIconSizeType` 的 rawValue 决定（:28-31，rawValue 即 insets）：
 *   `big = 20` → 图标占边长 (200-2×20)/200 = **80%**；`small = 50` → 占 **50%**；
 * - 类型分档（:443-457）：album/artist/folder/genre/podcast/radio 用 big；
 *   song/podcastEpisode 用 small；playlist 用 small 且 `switchColors = true`
 *   （:458-466 → 背景取主题色、图标取灰阶，即反色）；
 * - 类型 → 图标映射见 `ArtworkType.image`（:103-113）。
 *
 * 本文件即上述算法的 Compose 对位：[rememberDefaultArtworkPainter] 现画一个
 * [Painter]（不落任何位图资源），主题色取 `colorScheme.primary`
 * （账户级主题色只覆盖 primary/onPrimary），
 * 明暗取系统当前模式，故**切换账户主题色 / 明暗模式即刻跟随**。
 *
 * 历史：B7 之前 Android 用 4 张随包 PNG（`blue_*`）近似，恒为蓝色不随主题色变；
 * 2026-08-09 真机报障后改为本文件的主题化现画，PNG 已删除。
 */
enum class DefaultArtworkType(
    /** 该类型的图标（对应 iOS `ArtworkType.image`，UIImageAssetsExtension.swift:103-113） */
    val icon: ImageVector,
    /** 图标占画布边长的比例（iOS big=0.8 / small=0.5，由 insets 换算，见类注释） */
    val iconRatio: Float,
    /** 是否反色（iOS `switchColors`：背景取主题色、图标取灰阶，仅 playlist） */
    val switchColors: Boolean = false
) {
    /** iOS ArtworkType.song → .musicalNotes，small */
    SONG(AmperfyIcons.musicalNotes, ICON_RATIO_SMALL),

    /** iOS ArtworkType.album → .album，big */
    ALBUM(AmperfyIcons.album, ICON_RATIO_BIG),

    /** iOS ArtworkType.genre → .genre，big */
    GENRE(AmperfyIcons.genre, ICON_RATIO_BIG),

    /** iOS ArtworkType.artist → .artist，big */
    ARTIST(AmperfyIcons.artist, ICON_RATIO_BIG),

    /** iOS ArtworkType.podcast → .podcast，big */
    PODCAST(AmperfyIcons.podcast, ICON_RATIO_BIG),

    /** iOS ArtworkType.podcastEpisode → .podcastEpisode（与 podcast 同一 asset），small */
    PODCAST_EPISODE(AmperfyIcons.podcast, ICON_RATIO_SMALL),

    /** iOS ArtworkType.playlist → .playlist，small + switchColors（唯一反色项） */
    PLAYLIST(AmperfyIcons.playlist, ICON_RATIO_SMALL, switchColors = true),

    /** iOS ArtworkType.folder → .folder，big */
    FOLDER(AmperfyIcons.folder, ICON_RATIO_BIG),

    /** iOS ArtworkType.radio → .radio，big */
    RADIO(AmperfyIcons.radio, ICON_RATIO_BIG)
}

/**
 * 取某实体类型的默认艺术图 [Painter]——所有封面位的唯一回退来源。
 *
 * 用法：AsyncImage 的 `placeholder` / `error` / `fallback` 三参同传本 Painter
 * （对齐 iOS `LibraryEntityImage.refresh()`：先显示生成图，真图加载完才替换），
 * 并**不要**再往 `model` 里塞占位 URI——那会让 Coil 去解码一张固定色图片。
 *
 * **使用纪律（2026-08-09 真机报障后立）：返回的实例只能喂给单一绘制目标。**
 * 有多个封面位（如列表行 + 它的长按预览卡、四宫格的每一格）时，**每个位置各自调用本函数**，
 * 不要建一个实例到处传。原因见 [DefaultArtworkPainter]：它内包的 [VectorPainter] 是有状态的，
 * 矢量合成结果按**上次绘制尺寸**缓存在内部 DrawCache，同一实例被尺寸不同的两个目标在同帧
 * 交替驱动会互相污染缓存（报障现象：Home 页 Radio 卡长按时，160dp 卡片上多画出一个 64dp
 * 预览卡尺寸的小电台图标，列表项回收重组、Painter 重建后才消失）。
 * 组件形参也**应传 [DefaultArtworkType] 而非 Painter**（见 `EntityPreviewCard`），
 * 从签名上杜绝共享。
 */
@Composable
fun rememberDefaultArtworkPainter(type: DefaultArtworkType): Painter {
    val iconPainter = rememberVectorPainter(type.icon)
    val grayColor = if (isSystemInDarkTheme()) ArtworkBackgroundDark else ArtworkBackgroundLight
    // 主题色：账户级 themePreference 只覆盖 primary，
    // 对应 iOS createArtwork 的 theme.asColor
    val themeColor = MaterialTheme.colorScheme.primary
    val backgroundColor = if (type.switchColors) themeColor else grayColor
    val tintColor = if (type.switchColors) grayColor else themeColor
    return remember(iconPainter, backgroundColor, tintColor, type.iconRatio) {
        DefaultArtworkPainter(iconPainter, backgroundColor, tintColor, type.iconRatio)
    }
}

/**
 * 现画的默认艺术图：整块背景色 + 居中着色图标。
 *
 * 对应 iOS `UIImage.createArtwork`（UIImageAssetsExtension.swift:481-508）中
 * `EntityImageView` 的两层（背景色视图 + 按 insets 内缩的 tint 图标）。
 * iOS 画在固定 200×200 画布上，Android 这里按**实际绘制尺寸**等比换算，
 * 故任意封面框尺寸下观感一致。
 *
 * [intrinsicSize] 恒 [Size.Unspecified]：本图无固有尺寸，完全铺满调用方给的框
 * （否则 Coil 会拿 24dp 的矢量固有尺寸去布局占位图）。
 *
 * **有状态、不可跨绘制目标共享**：[iconPainter] 是 [VectorPainter]，其矢量合成结果按上次
 * 绘制尺寸缓存在内部 DrawCache，一个实例同时服务两个不同尺寸的绘制目标会串扰
 *（见 [rememberDefaultArtworkPainter] 的使用纪律）。
 */
private class DefaultArtworkPainter(
    private val iconPainter: VectorPainter,
    private val backgroundColor: Color,
    private val tintColor: Color,
    private val iconRatio: Float
) : Painter() {

    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        if (size.width <= 0f || size.height <= 0f) return
        drawRect(color = backgroundColor)

        // 图标可用方框：短边 × 比例（iOS 是正方形画布，两边同值）
        val box = minOf(size.width, size.height) * iconRatio
        val intrinsic = iconPainter.intrinsicSize
        // 按图标固有长宽比 fit 进该方框（图标非正方时不拉伸）
        val scale = if (intrinsic.width > 0f && intrinsic.height > 0f) {
            minOf(box / intrinsic.width, box / intrinsic.height)
        } else {
            1f
        }
        val iconWidth = if (intrinsic.width > 0f) intrinsic.width * scale else box
        val iconHeight = if (intrinsic.height > 0f) intrinsic.height * scale else box

        translate(
            left = (size.width - iconWidth) / 2f,
            top = (size.height - iconHeight) / 2f
        ) {
            with(iconPainter) {
                draw(
                    size = Size(iconWidth, iconHeight),
                    colorFilter = ColorFilter.tint(tintColor)
                )
            }
        }
    }
}

/**
 * 播放项（[Playable]）的默认艺术图类型——播放器 / MiniPlayer / 队列行共用。
 *
 * [Playable] 是歌曲 / 电台 / 播客单集的合体，故按其自带标志位分派，
 * 逐条对应 iOS `AbstractPlayable.getDefaultArtworkType()`
 * （AmperfyKit/Storage/EntityWrappers/AbstractPlayable.swift:391-400 按 derivedType 三分支）。
 */
fun defaultArtworkTypeFor(playable: Playable): DefaultArtworkType = when {
    playable.isRadio -> DefaultArtworkType.RADIO
    playable.isPodcastEpisode -> DefaultArtworkType.PODCAST_EPISODE
    else -> DefaultArtworkType.SONG
}
