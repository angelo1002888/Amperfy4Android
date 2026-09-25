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

/**
 * 拖拽重排行的稳定唯一 key 条目
 *
 * 背景（2026-07-03 修复的拖拽卡滞 bug 根因）：
 * 播放列表/播放队列允许重复歌曲，LazyColumn item key 单用 song.id 会重复崩溃；
 * 此前用「id + 当前索引」复合 key，但拖拽换位时被拖行的 key 随索引变化，
 * Compose 视为新 item 销毁重建节点，挂在拖动柄上的 detectDragGestures 协程
 * 随旧节点静默死亡（onDragEnd/onDragCancel 均不触发）——症状：行悬停在半行高
 * 平移处留出空白间隙、重排不提交，需再次拖动才复位。
 *
 * 方案：key = 「id + 出现序号」（"id#0"、"id#1"…），在从数据源同步工作副本时
 * 一次性计算，随条目移动保持稳定，拖拽换位不再改变 key。
 */
data class ReorderEntry<T>(val key: String, val item: T)

/** 按出现序号为重复 id 生成稳定唯一 key */
fun <T> buildReorderEntries(list: List<T>, idOf: (T) -> String): List<ReorderEntry<T>> {
    val seen = HashMap<String, Int>()
    return list.map { item ->
        val id = idOf(item)
        val n = seen.getOrDefault(id, 0)
        seen[id] = n + 1
        ReorderEntry("$id#$n", item)
    }
}
