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

package com.amperfy.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [AmperfyIcons] 全字形画廊 —— **仅供 Android Studio @Preview 目视**，无运行时使用点
 *
 * XML drawable 路线取消后，目视手段由 drawable 预览改为本画廊。用途：
 * - 逐批核对新增字形的形状、光学重心与画布内的相对大小（光学尺寸）；
 * - 浅色 / 深色两个 @Preview 确认 tint 生效（pathData 内恒黑，靠 tint 着色）。
 *
 * 新增图标属性后，在 [amperfyIconGallery] 追加一行即可。
 */
private data class IconEntry(val name: String, val icon: ImageVector)

/**
 * 画廊条目表：Cupertino 字形在前（按 [AmperfyIcons] 内的分区顺序），
 * Material 保留位在后（B6 自绘后从此段迁出）。
 */
private fun amperfyIconGallery(): List<IconEntry> = listOf(
    // 通用操作
    IconEntry("ellipsis", AmperfyIcons.ellipsis),
    IconEntry("check", AmperfyIcons.check),
    IconEntry("plus", AmperfyIcons.plus),
    IconEntry("xmark", AmperfyIcons.xmark),
    IconEntry("xmarkCircleFill", AmperfyIcons.xmarkCircleFill),
    IconEntry("minusCircleFill", AmperfyIcons.minusCircleFill),
    IconEntry("pencil", AmperfyIcons.pencil),
    IconEntry("trash", AmperfyIcons.trash),
    IconEntry("info", AmperfyIcons.info),
    IconEntry("clipboard", AmperfyIcons.clipboard),
    IconEntry("ban", AmperfyIcons.ban),
    IconEntry("exclamation", AmperfyIcons.exclamation),
    IconEntry("bars", AmperfyIcons.bars),
    IconEntry("filter", AmperfyIcons.filter),
    IconEntry("sort", AmperfyIcons.sort),
    IconEntry("grid", AmperfyIcons.grid),
    IconEntry("refresh", AmperfyIcons.refresh),
    IconEntry("redo", AmperfyIcons.redo),
    IconEntry("resize", AmperfyIcons.resize),
    IconEntry("followLink", AmperfyIcons.followLink),
    IconEntry("squareArrow", AmperfyIcons.squareArrow),
    IconEntry("chevronRight", AmperfyIcons.chevronRight),
    IconEntry("chevronLeft", AmperfyIcons.chevronLeft),
    IconEntry("chevronDown", AmperfyIcons.chevronDown),
    IconEntry("chevronUp", AmperfyIcons.chevronUp),
    // 导航与账户
    IconEntry("home", AmperfyIcons.home),
    IconEntry("musicLibrary", AmperfyIcons.musicLibrary),
    IconEntry("search", AmperfyIcons.search),
    IconEntry("account", AmperfyIcons.account),
    IconEntry("userCirclePlus", AmperfyIcons.userCirclePlus),
    IconEntry("userPerson", AmperfyIcons.userPerson),
    IconEntry("serverUrl", AmperfyIcons.serverUrl),
    IconEntry("password", AmperfyIcons.password),
    IconEntry("eyeFill", AmperfyIcons.eyeFill),
    IconEntry("eyeSlashFill", AmperfyIcons.eyeSlashFill),
    IconEntry("login", AmperfyIcons.login),
    IconEntry("settings", AmperfyIcons.settings),
    // 播放与队列
    IconEntry("play", AmperfyIcons.play),
    IconEntry("shuffle", AmperfyIcons.shuffle),
    IconEntry("listBullet", AmperfyIcons.listBullet),
    IconEntry("playlist", AmperfyIcons.playlist),
    IconEntry("playlistPlus", AmperfyIcons.playlistPlus),
    IconEntry("playlistX", AmperfyIcons.playlistX),
    IconEntry("repeatAll", AmperfyIcons.repeatAll),
    IconEntry("repeatOne", AmperfyIcons.repeatOne),
    IconEntry("skipBackward10", AmperfyIcons.skipBackward10),
    IconEntry("skipForward10", AmperfyIcons.skipForward10),
    // 实体语义
    IconEntry("album", AmperfyIcons.album),
    IconEntry("artist", AmperfyIcons.artist),
    IconEntry("musicalNotes", AmperfyIcons.musicalNotes),
    IconEntry("genre", AmperfyIcons.genre),
    IconEntry("folder", AmperfyIcons.folder),
    IconEntry("radio", AmperfyIcons.radio),
    // 播放器
    IconEntry("lyrics", AmperfyIcons.lyrics),
    IconEntry("sparkles", AmperfyIcons.sparkles),
    IconEntry("playbackRate", AmperfyIcons.playbackRate),
    IconEntry("sleep", AmperfyIcons.sleep),
    IconEntry("volumeMax", AmperfyIcons.volumeMax),
    IconEntry("antenna", AmperfyIcons.antenna),
    // 收藏与评分
    IconEntry("heartFill", AmperfyIcons.heartFill),
    IconEntry("heartEmpty", AmperfyIcons.heartEmpty),
    IconEntry("heartSlash", AmperfyIcons.heartSlash),
    IconEntry("suitHeartFill", AmperfyIcons.suitHeartFill),
    IconEntry("starFill", AmperfyIcons.starFill),
    IconEntry("starEmpty", AmperfyIcons.starEmpty),
    // 下载与选择态
    IconEntry("download", AmperfyIcons.download),
    IconEntry("circle", AmperfyIcons.circle),
    IconEntry("plusCircle", AmperfyIcons.plusCircle),
    IconEntry("isSelected", AmperfyIcons.isSelected),
    IconEntry("clear", AmperfyIcons.clear),
    // B6 自绘（AmperfyIconPathsCustom）
    IconEntry("audioVisualizer", AmperfyIcons.audioVisualizer),
    IconEntry("podcast", AmperfyIcons.podcast),
    IconEntry("albumNewest", AmperfyIcons.albumNewest),
    IconEntry("albumRecent", AmperfyIcons.albumRecent),
    IconEntry("userQueueInsert", AmperfyIcons.userQueueInsert),
    IconEntry("userQueueAppend", AmperfyIcons.userQueueAppend),
    IconEntry("contextQueueInsert", AmperfyIcons.contextQueueInsert),
    IconEntry("contextQueueAppend", AmperfyIcons.contextQueueAppend),
    IconEntry("podcastQueueInsert", AmperfyIcons.podcastQueueInsert),
    IconEntry("podcastQueueAppend", AmperfyIcons.podcastQueueAppend),
    // Material 永久保留位（cupertino 无对应字形且不值得自绘）
    IconEntry("cloudX*", AmperfyIcons.cloudX),
    IconEntry("airplayaudio*", AmperfyIcons.airplayaudio),
)

/** 图标网格（名称 + 24dp 图标），带 * 的为 Material 永久保留位 */
@Composable
private fun AmperfyIconGrid(modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 88.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(amperfyIconGallery()) { entry ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = entry.name,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = entry.name,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// heightDp 须容纳全部条目：81 条 ÷ 4 列（400dp/Adaptive 88dp）≈ 21 行；视口装不下的部分
// 在静态 @Preview 中直接被截断（LazyVerticalGrid 不可滚动），新增字形后不够就继续调大
@Preview(name = "AmperfyIcons - Light", showBackground = true, widthDp = 400, heightDp = 1700)
@Composable
private fun AmperfyIconsPreviewLight() {
    AmperfyTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.background(MaterialTheme.colorScheme.background)
        ) {
            AmperfyIconGrid(modifier = Modifier.padding(4.dp))
        }
    }
}

@Preview(name = "AmperfyIcons - Dark", showBackground = true, widthDp = 400, heightDp = 1700)
@Composable
private fun AmperfyIconsPreviewDark() {
    AmperfyTheme(darkTheme = true) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.background(MaterialTheme.colorScheme.background)
        ) {
            AmperfyIconGrid(modifier = Modifier.padding(4.dp))
        }
    }
}
