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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amperfy.R
import com.amperfy.ui.theme.label

/**
 * 启动屏文字层（对应 iOS `Amperfy/Screens/LaunchScreen.storyboard`）。
 *
 * **两段式结构与其由来**：Android 12+ 的系统启动屏（`Theme.SplashScreen`，见
 * `res/values(-night)/themes.xml`）只支持「背景色 + 居中圆区图标 + 底部 branding 图」，**画不了任意文本**，
 * 而 iOS LaunchScreen 除 Logo 外还有居中的 "Amperfy" 大标题与底部版权行。故 Android 侧拆成两段：
 * 系统 splash 先画背景 + Logo，进入 Compose 后由本层**原样复刻整屏**（含文字）顶上，
 * 停留片刻后淡出（三个时长常量都在 `MainActivity`）。
 *
 * **两处过渡都做了柔化**（2026-08-13 因实机上两处切换突兀而加）：系统 splash 退场经
 * `setOnExitAnimationListener` 交叉淡入本层（两层背景同色、Logo 同尺寸同位置，可见效果只是文字浮现），
 * 本层退场则淡出到真实 UI。
 *
 * **规格逐项照 LaunchScreen.storyboard**：
 * - 背景 systemBackground（浅白 / 深黑）= [MaterialTheme.colorScheme].background
 * - Logo：Icon-monocolor，高 200pt、aspectFit、centerY 对齐屏幕中心
 * - "Amperfy"：boldSystem 50pt、水平居中、默认 label 色；storyboard frame 高 60，
 *   其**底边 = Logo 顶边 − 8pt**
 * - 版权行：system 17pt、水平居中、底边 = safeArea 底 + 20pt
 *
 * **刻意差异（Android 系统能力所限）**：
 * ① iOS 是单屏、显示时长即进程启动耗时，Android 为两段式且本层有固定停留时长，
 *    整体启动观感被拉长（交叉淡入 300 + 停留 700 + 退出淡出 400ms）；
 * ② 两段的 Logo 尺寸此前不一致（系统 splash 图标画布 288dp、旧 inset 28dp 得 232dp，
 *    与本层 200dp 交接时缩约 14%）——已由 `splash_icon.xml` 的 inset 改 44dp 消除
 *    （288 − 44×2 = 200 = [LOGO_SIZE]），两段同尺寸同位置；
 * ③ 两处过渡（系统 splash 交叉淡入本层、本层淡出到真实 UI）都是 Android 两段式的衔接手段，
 *    iOS 均无。
 */
@Composable
fun LaunchScreenOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 不透明底：本层期间下方 UI 已在组合中，须完全遮住（= iOS 启动屏独占整屏）
            .background(MaterialTheme.colorScheme.background)
            // 吞掉本层期间的全部触摸：下方 UI 虽不可见但可点，防误触
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
    ) {
        // Logo：storyboard 中高 200 且 centerY = superview.centerY（水平亦居中）。
        // splash_logo.png 为**方形画布的黑字形**（自 iOS Icon-monocolor 原图缩制），
        // 故 200dp 方形 + Fit 与 iOS「高 200 aspectFit」视觉等价；
        // tint 走 colorScheme.label（浅色黑 / 深色白）= 该 imageset 的 universal/dark 双变体
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.label),
            modifier = Modifier
                .align(Alignment.Center)
                .size(LOGO_SIZE)
        )

        // "Amperfy" 大标题：storyboard 约束为「标题底边 = Logo 顶边 − 8」，
        // 即标题中心在屏幕中心上方 = Logo 半高 100 + 间距 8 + 标题半高 30（frame 高 60）= 138
        Text(
            text = "Amperfy",
            fontSize = 50.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.label,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -TITLE_CENTER_OFFSET)
        )

        // 版权行：storyboard 中底边 = safeArea 底 + 20、左右 = safeArea ± 20。
        // Android 的 safeArea 底对应导航栏 inset（本 Activity 为 edge-to-edge）
        Text(
            text = "© 2026 angelo · Based on Amperfy by Maximilian Bauer",
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.label,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
        )
    }
}

/** Logo 边长：iOS storyboard 的 `height = 200`（方形画布，aspectFit 后宽高同值） */
private val LOGO_SIZE = 200.dp

/**
 * 标题中心相对屏幕中心的上移量。
 *
 * = Logo 半高 100 + 标题与 Logo 的间距 8 + 标题 frame 半高 30（storyboard 标题 frame 高 60），
 * 复刻「标题底边 = Logo 顶边 − 8」这条约束。
 */
private val TITLE_CENTER_OFFSET = 138.dp
