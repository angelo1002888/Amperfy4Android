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

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.amperfy.ui.theme.separator

/**
 * 骨架屏加载器组件
 * 提供shimmer动画效果的占位符
 */

/**
 * 基础的骨架屏Box组件，带有shimmer动画
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(4.dp)
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = LinearEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, translateAnim - 200f),
        end = Offset(translateAnim, translateAnim)
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}

/**
 * 专辑列表项骨架屏 (Table View)
 * 对应 AlbumListItem 的骨架版本
 */
@Composable
fun AlbumListItemSkeleton(
    showDivider: Boolean = true
) {
    // 与真实 AlbumListItem 同步避让右侧索引条（end 内边距总值，替代 16dp），
    // 避免真数据到位时内容横向跳动
    val trailingInset = LocalListRowTrailingInset.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 16.dp, end = maxOf(16.dp, trailingInset), top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧收藏图标占位
            Box(modifier = Modifier.width(18.dp))

            Spacer(modifier = Modifier.width(4.dp))

            // 封面图片骨架：48dp 与真实 AlbumListItem 的 EntityImage 同尺寸
            // （iOS GenericTableCell.xib 封面 48pt），避免真数据到位时行高跳动
            SkeletonBox(
                modifier = Modifier
                    .size(48.dp),
                shape = RoundedCornerShape(6.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 中间文本区域
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 专辑名称骨架
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                )

                // 艺术家名称骨架
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(14.dp)
                )

                // 信息行骨架
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(12.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右侧图标区域
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 下载图标占位
                Box(modifier = Modifier.width(20.dp))

                // 箭头图标占位
                Box(modifier = Modifier.width(24.dp))
            }
        }

        if (showDivider) {
            // 行间分隔线：左端距屏幕左缘 16dp、右端画到屏幕右缘（UITableView 默认
            // separatorInset = cell layoutMargins 左右值，CommonScreenOperations.swift:41-47）
            HairlineDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.separator  // iOS .separator
            )
        }
    }
}

/**
 * 专辑Grid项骨架屏
 * 对应 AlbumGridItem 的骨架版本
 */
@Composable
fun AlbumGridItemSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 封面图片骨架 - 方形
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 专辑名称骨架
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(16.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 艺术家名称骨架
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(14.dp)
        )
    }
}
