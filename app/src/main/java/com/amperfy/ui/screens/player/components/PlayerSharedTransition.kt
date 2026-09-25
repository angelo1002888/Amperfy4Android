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

package com.amperfy.ui.screens.player.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 播放器 LARGE/COMPACT 显示模式切换的共享元素转场
 * 对应 iOS: PopupPlayer+Animations.swift changeDisplayStyleVisually——
 * 封面/红心/More 按钮以浮动替身（fake view）从源 frame 位移缩放到目标 frame（0.2s），
 * 同时两个视图交叉淡入淡出。
 *
 * Compose 侧用 SharedTransitionLayout + AnimatedContent 实现：
 * PopupPlayerScreen 提供两个 CompositionLocal，LargePlayerView 与 QueueListView
 * 的当前播放行经 [playerSharedElement] 标记同 key 的共享元素。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalPlayerSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalPlayerAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** iOS: PopupPlayerVC.displaStyleAnimationDuration = 0.2s */
const val DISPLAY_STYLE_ANIMATION_MS = 200

/** 共享元素 key：大封面 ↔ 当前播放行缩略图 */
const val SHARED_KEY_PLAYER_ARTWORK = "player-artwork"

/** 共享元素 key：红心按钮（iOS animateFavorite） */
const val SHARED_KEY_PLAYER_FAVORITE = "player-favorite"

/** 共享元素 key：More 按钮（iOS animateOptions） */
const val SHARED_KEY_PLAYER_OPTIONS = "player-options"

/**
 * 将本节点标记为播放器显示模式切换的共享元素。
 * 不在播放器转场上下文中（两个 Local 任一缺失）时为 no-op，
 * 保证组件在其他场景复用不受影响。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.playerSharedElement(key: String): Modifier {
    val sharedTransitionScope = LocalPlayerSharedTransitionScope.current ?: return this
    val animatedVisibilityScope = LocalPlayerAnimatedVisibilityScope.current ?: return this
    return with(sharedTransitionScope) {
        this@playerSharedElement.sharedElement(
            state = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = { _, _ -> tween(DISPLAY_STYLE_ANIMATION_MS) }
        )
    }
}
