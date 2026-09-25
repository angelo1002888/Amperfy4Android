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

package com.amperfy.data.model

import com.amperfy.core.AppDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Queue操作扩展函数集合
 *
 * 这些扩展函数统一管理Song添加到播放队列的逻辑，避免每个ViewModel重复实现。
 * 替代了原来的BasePlayableViewModel继承模式。
 *
 * 使用方式：
 * ```kotlin
 * @HiltViewModel
 * class AlbumDetailViewModel @Inject constructor(
 *     private val appDelegate: AppDelegate
 * ) : ViewModel() {
 *     fun addSongNext(song: Song) {
 *         song.addToQueueNext(appDelegate, viewModelScope)
 *     }
 * }
 * ```
 *
 * 设计理念：
 * - 扩展函数模式比继承更灵活（符合"组合优于继承"原则）
 * - 统一管理，避免代码重复
 * - 与AppDelegate模式配合使用
 * - 对齐iOS的队列操作语义
 */

/**
 * 将Song转换为Playable（带凭证）
 * 内部辅助函数
 */
private fun Song.toPlayableWithAppDelegate(appDelegate: AppDelegate): Playable {
    return toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
}

/**
 * Add song to user queue (next to play after current song)
 * iOS: EntityPreviewActionBuilder.addToQueueNext()
 *
 * @param appDelegate 应用代理，提供player访问
 * @param scope 协程作用域（通常是viewModelScope）
 */
fun Song.addToQueueNext(appDelegate: AppDelegate, scope: CoroutineScope) {
    scope.launch {
        appDelegate.player.insertUserQueue(listOf(toPlayableWithAppDelegate(appDelegate)))
    }
}

/**
 * Add song to end of user queue
 * iOS: EntityPreviewActionBuilder.addToQueueLater()
 *
 * @param appDelegate 应用代理，提供player访问
 * @param scope 协程作用域（通常是viewModelScope）
 */
fun Song.addToQueueLater(appDelegate: AppDelegate, scope: CoroutineScope) {
    scope.launch {
        appDelegate.player.appendUserQueue(listOf(toPlayableWithAppDelegate(appDelegate)))
    }
}

/**
 * Insert song at front of context queue
 * iOS: insertContextQueue()
 *
 * @param appDelegate 应用代理，提供player访问
 * @param scope 协程作用域（通常是viewModelScope）
 */
fun Song.insertContextQueue(appDelegate: AppDelegate, scope: CoroutineScope) {
    scope.launch {
        appDelegate.player.insertContextQueue(listOf(toPlayableWithAppDelegate(appDelegate)))
    }
}

/**
 * Append song to end of context queue
 * iOS: appendContextQueue()
 *
 * @param appDelegate 应用代理，提供player访问
 * @param scope 协程作用域（通常是viewModelScope）
 */
fun Song.appendContextQueue(appDelegate: AppDelegate, scope: CoroutineScope) {
    scope.launch {
        appDelegate.player.appendContextQueue(listOf(toPlayableWithAppDelegate(appDelegate)))
    }
}

// ═══════════════════════════════════════════════════════════
// 批量操作扩展函数（用于多首歌曲）
// ═══════════════════════════════════════════════════════════

/**
 * Add multiple songs to user queue (next to play)
 *
 * @param appDelegate 应用代理，提供player访问
 * @param scope 协程作用域（通常是viewModelScope）
 */
fun List<Song>.addMultipleToQueueNext(appDelegate: AppDelegate, scope: CoroutineScope) {
    scope.launch {
        val playables = map { it.toPlayableWithAppDelegate(appDelegate) }
        appDelegate.player.insertUserQueue(playables)
    }
}

/**
 * Add multiple songs to end of user queue
 *
 * @param appDelegate 应用代理，提供player访问
 * @param scope 协程作用域（通常是viewModelScope）
 */
fun List<Song>.addMultipleToQueueLater(appDelegate: AppDelegate, scope: CoroutineScope) {
    scope.launch {
        val playables = map { it.toPlayableWithAppDelegate(appDelegate) }
        appDelegate.player.appendUserQueue(playables)
    }
}

/**
 * Insert multiple songs at front of context queue
 *
 * @param appDelegate 应用代理，提供player访问
 * @param scope 协程作用域（通常是viewModelScope）
 */
fun List<Song>.insertMultipleContextQueue(appDelegate: AppDelegate, scope: CoroutineScope) {
    scope.launch {
        val playables = map { it.toPlayableWithAppDelegate(appDelegate) }
        appDelegate.player.insertContextQueue(playables)
    }
}

/**
 * Append multiple songs to end of context queue
 *
 * @param appDelegate 应用代理，提供player访问
 * @param scope 协程作用域（通常是viewModelScope）
 */
fun List<Song>.appendMultipleContextQueue(appDelegate: AppDelegate, scope: CoroutineScope) {
    scope.launch {
        val playables = map { it.toPlayableWithAppDelegate(appDelegate) }
        appDelegate.player.appendContextQueue(playables)
    }
}
