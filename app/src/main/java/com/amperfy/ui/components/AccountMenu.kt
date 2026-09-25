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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.Account
import com.amperfy.ui.theme.AmperfyIconPaths
import com.amperfy.ui.theme.AmperfyIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 账户菜单 ViewModel（W5）——仅注入 AppDelegate。
 */
@HiltViewModel
class AccountMenuViewModel @Inject constructor(
    private val appDelegate: AppDelegate
) : ViewModel() {
    val accounts = appDelegate.accounts.allAccounts
    val activeAccount = appDelegate.accounts.activeAccount

    fun switchAccount(ident: String) {
        viewModelScope.launch { appDelegate.accounts.switchAccount(ident) }
    }
}

/**
 * 用户按钮菜单（W5；冻结签名，W6 挂载到各 Tab 顶栏右上）
 *
 * 头像图标 + IOSStyleContextMenu：账户列表（userName + serverUrl 缩略，active 打勾禁用，
 * 点击切换经 AccountManager.switchAccount）/ Add Account（回调）/ Settings（回调）。
 */
@Composable
fun AccountMenuButton(
    onOpenSettings: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: AccountMenuViewModel = hiltViewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val active by viewModel.activeAccount.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            // 对齐 iOS setupUserNavButton（CommonScreenOperations.swift:124-150）：
            // 头像按 themePreference.asColor 做 template tint。AmperfyTheme 已按 active
            // 账户主题色覆盖 primary，故切账户即自动跟随
            UserCircleIcon(
                tint = MaterialTheme.colorScheme.primary,
                // iOS setupUserNavButton 图标 pointSize 24 的观感对应（点击域仍为 IconButton 默认 48dp）
                modifier = Modifier.size(28.dp)
            )
        }

        val items = buildList {
            accounts.forEach { account ->
                val isActive = account.info.ident == active?.info?.ident
                add(
                    IOSContextMenuItem.Action(
                        // 对齐 iOS UIAction(title: username, subtitle: displayServerUrl)：
                        // 两行结构（用户名 + 服务器地址缩略），不再挤成一长串被动换行
                        text = account.userName,
                        subtitle = shortServerUrl(account),
                        // 对齐 iOS createUserButtonMenu（CommonScreenOperations.swift:61-83）：
                        // 每行 image 恒为 .userCircle（不随选中态变化），选中态由 UIAction 的
                        // state = .on 在 image 列**之前**渲染系统小勾，并同时 disabled
                        icon = AmperfyIcons.account,
                        state = isActive,
                        enabled = !isActive,
                        // iOS 菜单行头像显式取 pointSize 30（CommonScreenOperations.swift:72-75），
                        // 大于常规菜单图标；Add Account / Settings 行不传，保持常规尺寸
                        iconSize = 30.dp,
                        onClick = { viewModel.switchAccount(account.info.ident) }
                    )
                )
            }
            if (accounts.isNotEmpty()) add(IOSContextMenuItem.Divider)
            add(IOSContextMenuItem.Action(text = "Add Account", icon = AmperfyIcons.userCirclePlus, onClick = onAddAccount))
            add(IOSContextMenuItem.Action(text = "Settings", icon = AmperfyIcons.settings, onClick = onOpenSettings))
        }

        IOSStyleContextMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = items
        )
    }
}

// ───────────────────────────────────────────────────────────────────────────
// 顶栏头像的自绘复合件
//
// 比例常量（相对**外圆直径**或**内盘直径**，调整观感只改这三个值）
// ───────────────────────────────────────────────────────────────────────────

/**
 * 外环线宽 / 外圆直径 —— 28dp 下约 1.26dp 的**细**环。
 * B5.1 曾取 0.06f，对照 iOS 实机显得「蓝色边缘太重」，B5.3 收细至此值
 */
private const val USER_CIRCLE_RING_STROKE_RATIO = 0.045f

/**
 * 内盘半径 / 外半径 —— 外环内缘与内盘之间余下的即**白隙**（不绘制，透出底色），
 * 宽度 = outerRadius − ringStroke − innerRadius，28dp 下约 2.7dp。
 * B5.1 白隙仅约 1.7dp 显得环盘几乎相连，B5.3 由 0.78f 收到本值以拉开间隙
 */
private const val USER_CIRCLE_DISC_RATIO = 0.72f

/** 人形宽度 / 内盘直径 —— 越大人形在盘内占比越满 */
private const val USER_CIRCLE_PERSON_WIDTH_RATIO = 0.62f

/**
 * 顶栏账户头像 —— **主题色细外环 + 宽白隙 + 主题色内盘 + 居中反挖人形**的三层复合件
 *
 * 对齐 iOS `person.circle.fill`（AmperfyImage.account，UIImageAssetsExtension.swift:144；
 * 由 setupUserNavButton 以 themePreference.asColor 做 template tint 渲染，
 * CommonScreenOperations.swift:129-146）。尺寸对应关系：iOS 图标 pointSize 24 置于
 * 40pt 圆形按钮内 ↔ Android 28dp 图标置于 IconButton 默认 48dp 点击域内。
 *
 * **构型的代码真值依据**：iOS 侧对整个 glyph 做的是**单色 template tint**
 * （`.withTintColor(themePreference.asColor, renderingMode: .alwaysTemplate)`），
 * 故外环与内盘**必然同为主题色**——中间那圈白不是「白色的环」，而是字形本身的**挖空**
 * （透出底色，深色模式自动跟随暗色）。本处按同一模型自绘：环与盘同 tint，白隙不绘制。
 *
 * **标定沿革（三轮对照 iOS 实机）**：B5.1 环 6% / 隙 6%——环太粗被判为「蓝色边缘」；
 * B5.2 据「外缘无描边」的描述误删外环、改为纯透明环带——真机下环带与页面同色，
 * 外环整体不可见；B5.3 定案回三层，但把环收细到 4.5%、白隙拉宽到约 2.7dp，
 * 既保留可见的主题色外环，又不再显得边缘厚重。
 *
 * **为何自绘而非取字形**：cupertino_icons 1.0.8 的 `person_circle_fill` (U+F747) 经像素级
 * 渲染实证，其盘径几乎占满整个字形框、留不出 iOS 那圈明显的白隙
 * （同族的 `person_crop_circle_fill` 亦然），故本处按几何自绘以便精确调节环宽与隙宽。
 *
 * 人形取 [AmperfyIconPaths.PERSON_FILL] 字形路径，用 [BlendMode.Clear] **真挖空**（而非
 * 填背景色），因此深浅色模式、任意底色下都自动透出底色——与 iOS template 图的挖空行为一致；
 * Clear 需要离屏图层，故整件走 [CompositingStrategy.Offscreen]。人形在内盘内**水平与垂直
 * 双向居中**（按 iOS 实机观测定值），按当前比例完整落在盘内，下方 clipPath 仅作改比例时的保险。
 *
 * 唯一消费方是本文件的顶栏按钮：菜单内的账户行仍用单色小图 [AmperfyIcons.account]
 * （iOS 菜单 image 亦为 label 单色，环带构型只在导航栏按钮上成立）。
 */
@Composable
private fun UserCircleIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    // 字形路径只解析一次（24×24 坐标系，实际尺寸由下方按 bounds 归一）
    val personPath: Path = remember {
        PathParser().parsePathString(AmperfyIconPaths.PERSON_FILL).toPath()
    }

    Canvas(
        modifier = modifier
            .semantics { contentDescription = "Account" }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = diameter / 2f
        val ringStroke = diameter * USER_CIRCLE_RING_STROKE_RATIO
        val innerRadius = outerRadius * USER_CIRCLE_DISC_RATIO
        if (innerRadius <= 0f) return@Canvas

        // ① 外环（Stroke 以半径为中线，故半径取 outerRadius - ringStroke/2 使外缘恰好贴边）
        drawCircle(
            color = tint,
            radius = outerRadius - ringStroke / 2f,
            center = center,
            style = Stroke(width = ringStroke)
        )

        // ② 内实心盘（与外环同为主题色；二者之间余下的白隙不绘制，透出底色）
        drawCircle(color = tint, radius = innerRadius, center = center)

        // ③ 人形反挖：按内盘尺寸缩放后在盘内水平 + 垂直双向居中
        val bounds = personPath.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f) return@Canvas
        val scale = innerRadius * 2f * USER_CIRCLE_PERSON_WIDTH_RATIO / bounds.width
        val personLeft = center.x - bounds.width * scale / 2f
        val personTop = center.y - bounds.height * scale / 2f

        val innerCircle = Path().apply {
            addOval(
                Rect(
                    left = center.x - innerRadius,
                    top = center.y - innerRadius,
                    right = center.x + innerRadius,
                    bottom = center.y + innerRadius
                )
            )
        }
        clipPath(innerCircle) {
            withTransform({
                // 先平移到目标位置、再按 pivot=原点缩放，等价于 point * scale + offset
                translate(
                    left = personLeft - bounds.left * scale,
                    top = personTop - bounds.top * scale
                )
                scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
                // Clear 下颜色不参与合成，仅为必填参数
                drawPath(path = personPath, color = Color.Black, blendMode = BlendMode.Clear)
            }
        }
    }
}

/** serverUrl 缩略显示（去 scheme + 末尾斜杠） */
private fun shortServerUrl(account: Account): String =
    account.serverUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
