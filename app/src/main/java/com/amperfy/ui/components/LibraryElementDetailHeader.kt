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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amperfy.ui.navigation.LocalSettingsManager
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.secondarySystemFill

/*
 * 资料库各列表页头部的 Play / Shuffle 按钮对。
 *
 * 对应 iOS `LibraryElementDetailTableHeaderView`
 * （`Amperfy/Screens/View/AlbumDetailHeaderView.swift:24` 定义、同名 .xib 布局）。
 *
 * **为什么没有 info（计数）槽**：iOS 该视图的计数行受
 * `infoContainerView.isHidden = config.isInfoAlwaysHidden || (traitCollection.horizontalSizeClass == .compact)`
 * 控制（LibraryElementDetailTableHeaderView.swift:119-120）——iPhone 竖屏恒为 compact，
 * 故这行在 iPhone 上**永远不显示**，Android 侧无需实现。
 * 注意别和各**详情页**（Album / Artist / Playlist / Podcast Detail）的信息行混淆：
 * 那是另一套 `GenericDetailTableHeader.infoLabel`（规则 `isHidden = infoText.isEmpty`），
 * 在 iPhone 上照常显示，不归本组件管。
 *
 * **只渲染按钮行本身**，不含外层容器与页面留白——各页的外层 `Column` / padding /
 * 对齐方式差异较大（有的还要按内容有无整块隐藏），留在各页自己控制，避免把页面布局塞进本组件。
 */

/**
 * Play / Shuffle 按钮对（等宽各占一半、40dp 高、10dp 圆角、iOS `.secondarySystemFill` 底
 * + 主题色内容）。**全部详情页/列表页的唯一实现**——iOS 侧同样只有
 * `LibraryElementDetailTableHeaderView` 一处，各页只给配置不自绘按钮。
 *
 * Shuffle 键受设置项 **Disable Player Shuffle Button**（Settings → Display）控制：
 * 关闭时按钮**仍显示但不可交互**（对齐 iOS LibraryElementDetailTableHeaderView.swift:173，
 * 而非隐藏——隐藏是上下文菜单里 Shuffle 项的处置）。
 * 但该门控**只对 [isShuffleOnContextNecessary] = true 的页生效**（同文件 :176），见下。
 *
 * **Shuffle 键文案按页动态（[isShuffleOnContextNecessary]）**，命名镜像 iOS 同名配置项。
 *
 * 判据沿革（两轮反转，务必读完再改）：
 * 1. `LibraryElementDetailTableHeaderView.xib` 里两个按钮的 title 是 `"Play"` / `"Shuffle"`，
 *    据此曾把三页的 "Random" 统一改成 "Shuffle"；
 * 2. **实机表现推翻**——xib 那两个 title 只是**占位**，运行时被 `prepare(configuration:)` 覆盖
 *    （LibraryElementDetailTableHeaderView.swift:157-161）：
 *    `playShuffledButton.setTitle(configuration.isShuffleOnContextNeccessary ? "Shuffle" : "Random")`。
 *    故两种文案在 iOS 上都存在，取哪个由各 VC 的配置决定。
 *
 * 该标志的语义（iOS :154 原注释「In AlbumsVC the albums are shuffled, keep the order when
 * shuffle button is pressed」+ :140-151 的 `shuffle()`）：
 * - `true`（默认）→ 按下走 `player.playShuffled(context:)`，**开启播放器 shuffle**，文案 "Shuffle"；
 * - `false` → 上下文**本身已经是随机序**，按下只 `player.play(context:)` 保持该序、
 *   **不开播放器 shuffle**，文案 "Random"。
 *
 * 逐 VC 配置真值：
 *
 * | 页面 | iOS 出处 | 取值 | 文案 |
 * |---|---|---|---|
 * | Albums（全部过滤态）| `AlbumsCommonVCInteractions.swift:560` | `false` | Random |
 * | Radios | `RadiosVC.swift:77` | `false` | Random |
 * | Songs / FavoriteSongs / GenreDetail / Directories | 未传，走默认（`PlayShuffleInfoConfiguration:36`）| `true` | Shuffle |
 *
 * 其余度量（8dp 间距 / SemiBold / 默认 contentPadding）六页统一，不随本标志变化。
 *
 * 图标不设 `contentDescription`：按钮内已有同名文字，再给图标加描述会让 TalkBack 读两遍。
 *
 * @param isShuffleOnContextNecessary 见上表；`false` 时文案作 "Random"
 * @param enabled 整块可用性，对应 iOS `LibraryElementDetailTableHeaderView.activate()` /
 *   `deactivate()`（同文件 :168-180）——上下文里没有可播条目时两键**禁用而非隐藏**
 *   （当前唯一非默认调用点：DirectoriesVC 的目录页无歌曲时，DirectoriesVC.swift:193-200）。
 *   Shuffle 键的最终可用性 = 本参数与既有 Disable Player Shuffle Button 门控的**与**。
 * @param customPlayName Play 键自定义文案，对应 iOS `PlayShuffleInfoConfiguration.customPlayName`
 *   （LibraryElementDetailTableHeaderView.swift:34；取用在 :157
 *   `playAllButton.setTitle(config?.customPlayName ?? "Play")`）。全仓唯一非 null 的用例是
 *   PodcastDetail 的 "Newest Episode"。
 * @param isShuffleHidden Shuffle 键整键隐藏（**非禁用**），对应 iOS 同名配置项（同文件 :35，
 *   取用在 :164 `playShuffledButton.isHidden = configuration.isShuffleHidden`）。隐藏后 Play
 *   独占整行宽——iOS 侧同为 UIStackView 抽走一个 arrangedSubview 后的宽度重分布。
 */
@Composable
fun PlayShuffleButtons(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    isShuffleOnContextNecessary: Boolean = true,
    enabled: Boolean = true,
    customPlayName: String? = null,
    isShuffleHidden: Boolean = false
) {
    // Disable Player Shuffle Button（Settings→Display→Shuffle）：显示但不可交互
    // （iOS LibraryElementDetailTableHeaderView.swift:173）
    val isShuffleButtonEnabled by LocalSettingsManager.current
        .isPlayerShuffleButtonEnabled.collectAsState()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onPlay,
            // 整块 deactivate（iOS :168-180）：Play 键也随之禁用
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            // iOS LibraryElementDetailTableHeaderView.swift:158 `layer.cornerRadius = 10.0`
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondarySystemFill,  // iOS .secondarySystemFill
                contentColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.secondarySystemFill,
                disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            )
        ) {
            Icon(AmperfyIcons.play, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(8.dp))
            // iOS :157 `config?.customPlayName ?? "Play"`
            Text(
                text = customPlayName ?: "Play",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Shuffle 键整键隐藏（iOS :164 `isHidden = configuration.isShuffleHidden`）：
        // 不发射本键，Play 独占整行宽（iOS 侧同为 UIStackView 的宽度重分布）
        if (!isShuffleHidden) {
            Button(
                onClick = onShuffle,
                // 门控对齐 iOS activate()（LibraryElementDetailTableHeaderView.swift:176）：
                // `isEnabled = !isShuffleOnContextNeccessary || isPlayerShuffleButtonEnabled`
                // ——「Random」两页（Albums / Radios）**恒可用**，因为按下并不开启播放器 shuffle，
                // 用 Disable Player Shuffle Button 去禁它没有道理；
                // 再与整块 [enabled]（deactivate 语义）取与
                enabled = enabled && (!isShuffleOnContextNecessary || isShuffleButtonEnabled),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                // iOS LibraryElementDetailTableHeaderView.swift:163 `layer.cornerRadius = 10.0`
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondarySystemFill,
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.secondarySystemFill,
                    disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                )
            ) {
                Icon(AmperfyIcons.shuffle, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isShuffleOnContextNecessary) "Shuffle" else "Random",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
