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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * AmperfyIcons - 全应用图标的唯一映射对象
 *
 * 对应 iOS: AmperfyKit/Assets/UIImageAssetsExtension.swift 的 `AmperfyImage` 静态表
 * （iOS 把每个 UI 位置用到的 SF Symbol 名集中在那一处，本对象是它的 Android 对位）。
 *
 * 字形来源：cupertino_icons 1.0.8（MIT）的 CupertinoIcons.ttf，经
 * scripts/icons/convert-cupertino-icons.mjs 转为 [AmperfyIconPaths] 的 pathData 常量，
 * 本文件用 [buildIcon] 组装成 ImageVector。
 *
 * 施工约定：
 * - **属性一律是普通 `val ... by lazy`，不带 @Composable**——实际消费方
 *   （`SwipeActionType.getIcon()`、九个 `build*ContextMenuItems`、`LibraryDisplayType.icon`）
 *   全是非组合上下文，用 `vectorResource` 会迫使 @Composable 全链传播。
 *   `by lazy` 默认 SYNCHRONIZED，多线程首次访问安全。
 * - 属性名与 iOS `AmperfyImage` 常量名一一对应（命名与 iOS 一致）；
 *   iOS 无对应常量的（如行 disclosure 走 UIKit 系统绘制）用 SF 符号名直译并在注释注明。
 * - 填充色恒为黑（`SolidColor(Color.Black)`），颜色一律由调用方 `Icon(tint = ...)` 决定；
 *   填充规则用默认 NonZero（TrueType 轮廓绕向即 NonZero 语义，
 *   `heart.slash` / `circle.slash` 之类反挖轮廓靠绕向实现，写 EvenOdd 反而会错）。
 * - **无合适 Cupertino 字形时该属性内部改用 [AmperfyIconPathsCustom] 的自绘路径**（见文件末
 *   「Custom-drawn icons」段），对调用方透明——换源只改本文件一处。
 */
object AmperfyIcons {

    /**
     * pathData 常量 -> ImageVector
     *
     * viewport 与 default 尺寸恒 24（转换脚本已把字体 em 盒归一到 24×24 画布）。
     *
     * @param autoMirror RTL 布局下水平镜像（替代 VectorDrawable 的
     *   `android:autoMirrored` 与 `Icons.AutoMirrored.*`）
     */
    private fun buildIcon(
        name: String,
        pathData: String,
        autoMirror: Boolean = false
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).addPath(
        pathData = addPathNodes(pathData),
        fill = SolidColor(Color.Black)
    ).build()

    // MARK: - 通用操作

    /** iOS: AmperfyImage.ellipsis ("ellipsis")——列表行 / 顶栏 / 播放器的 More 入口 */
    val ellipsis: ImageVector by lazy {
        buildIcon(name = "ellipsis", pathData = AmperfyIconPaths.ELLIPSIS)
    }

    /**
     * iOS: AmperfyImage.check ("checkmark" 的 **SF 裸勾**)——**菜单单选打勾**与
     * 列表行「下载完成」附件（PlayableTableCell.swift:361-364）。
     *
     * **不用于任何多选已选态**——那些在 iOS 是 UIKit 内建 `UIImage.checkmark` 圆徽，见 [isSelected]。
     */
    val check: ImageVector by lazy {
        buildIcon(name = "check", pathData = AmperfyIconPaths.CHECKMARK)
    }

    /** iOS: AmperfyImage.plus ("plus")——新增（Swipe 设置、Server URLs、播放列表工具栏） */
    val plus: ImageVector by lazy {
        buildIcon(name = "plus", pathData = AmperfyIconPaths.PLUS)
    }

    /** iOS: AmperfyImage.xmark ("xmark")——关闭 */
    val xmark: ImageVector by lazy {
        buildIcon(name = "xmark", pathData = AmperfyIconPaths.XMARK)
    }

    /**
     * iOS 无对应 AmperfyImage 常量：搜索框右侧的**清空钮**由 UIKit `UISearchBar` 系统绘制，
     * 形为 SF `xmark.circle.fill`（实心圆内反挖叉）。属性名按 SF 名直译。
     * 与裸叉 [xmark]（导航栏 Close 按钮）分工：带底圆的只用于输入框内联清空。
     */
    val xmarkCircleFill: ImageVector by lazy {
        buildIcon(name = "xmarkCircleFill", pathData = AmperfyIconPaths.XMARK_CIRCLE_FILL)
    }

    /**
     * iOS 无对应 AmperfyImage 常量：UIKit 列表编辑态的**删除控件**（红色减号圆徽）由
     * `UITableViewCell.editingStyle == .delete` 系统绘制，形为 SF `minus.circle.fill`。
     * 属性名按 SF 名直译；用点为 Swipe 设置页的「移除已配置动作」。
     */
    val minusCircleFill: ImageVector by lazy {
        buildIcon(name = "minusCircleFill", pathData = AmperfyIconPaths.MINUS_CIRCLE_FILL)
    }

    /**
     * iOS 无对应 AmperfyImage 常量（**Android 独有用点**）：Manage Server URLs 页的
     * 「Edit」按钮——iOS 该页用 UIKit `editButtonItem` 的**纯文字** Edit/Done，
     * Android 侧现为图标按钮，取 SF `pencil` 同形字形以并入本图标族。
     */
    val pencil: ImageVector by lazy {
        buildIcon(name = "pencil", pathData = AmperfyIconPaths.PENCIL)
    }

    /** iOS: AmperfyImage.trash ("trash")——删除 / 移出缓存 */
    val trash: ImageVector by lazy {
        buildIcon(name = "trash", pathData = AmperfyIconPaths.TRASH)
    }

    /** iOS: AmperfyImage.info ("info.circle")——Detailed Info / 播放器信息 / 描述 */
    val info: ImageVector by lazy {
        buildIcon(name = "info", pathData = AmperfyIconPaths.INFO_CIRCLE)
    }

    /**
     * iOS: AmperfyImage.exclamation ("exclamationmark")——列表行下载失败附件
     * （PlayableTableCell.swift:357-360）。iOS 是**裸感叹号**，非 Material 的圆内感叹号。
     * 使用点在 DownloadsScreen。
     */
    val exclamation: ImageVector by lazy {
        buildIcon(name = "exclamation", pathData = AmperfyIconPaths.EXCLAMATIONMARK)
    }

    /**
     * iOS: AmperfyImage.bars ("line.3.horizontal")——手动排序行的拖拽手柄
     * （PlayableTableCell.swift:352-355，displayMode == .reorder）。
     */
    val bars: ImageVector by lazy {
        buildIcon(name = "bars", pathData = AmperfyIconPaths.LINE_HORIZONTAL_3)
    }

    /**
     * iOS: AmperfyImage.filter ("line.3.horizontal.decrease")——**筛选**菜单
     * （ArtistsVC.swift:462-463 的 All / Album Artists；另 OptionsButton.swift:34-36
     * `createSortBarButton` 的工具栏按钮位亦用此符号）。
     *
     * **注意**：排序菜单不是本项——iOS `UIMenu(title: "Sort", image: .sort)` 用的是
     * [sort]（"arrow.up.arrow.down"），勿混用。
     */
    val filter: ImageVector by lazy {
        buildIcon(name = "filter", pathData = AmperfyIconPaths.LINE_HORIZONTAL_3_DECREASE)
    }

    /**
     * iOS: AmperfyImage.sort ("arrow.up.arrow.down"，UIImageAssetsExtension.swift:236)
     * ——列表页 / 详情页的 **Sort 菜单**图标（ArtistsVC.swift:439-440、
     * AlbumsCommonVCInteractions.swift:358-359、PlaylistsVC.swift:329-330、
     * SongsVC.swift:441-456、PlaylistSelectorVC.swift:283-284）。与筛选用的 [filter] 区分。
     */
    val sort: ImageVector by lazy {
        buildIcon(name = "sort", pathData = AmperfyIconPaths.ARROW_UP_ARROW_DOWN)
    }

    /**
     * iOS: AmperfyImage.grid ("square.grid.2x2")——Albums 页 Style 菜单的网格样式
     * （AlbumsCommonVCInteractions.swift:451）。
     */
    val grid: ImageVector by lazy {
        buildIcon(name = "grid", pathData = AmperfyIconPaths.SQUARE_GRID_2X2)
    }

    /**
     * iOS: AmperfyImage.refresh ("arrow.triangle.2.circlepath")——Sync All Playlists
     * （PlaylistsVC.swift:337）、Home section 刷新随机、同步弹窗。
     * **近似**：Cupertino 1.0.8 无 `arrow.triangle.2.circlepath`（三角箭头头），
     * 取 `arrow_2_circlepath`（同为「双圆弧循环箭头」，箭头头为普通尖角）。
     */
    val refresh: ImageVector by lazy {
        buildIcon(name = "refresh", pathData = AmperfyIconPaths.ARROW_2_CIRCLEPATH)
    }

    /**
     * iOS: AmperfyImage.redo ("gobackward"，UIImageAssetsExtension.swift:215)
     * ——Downloads 页的 Retry failed downloads（DownloadsVC.swift:74-76）。
     * 单圈逆时针箭头，与双弧循环的 [refresh] 是不同符号。
     */
    val redo: ImageVector by lazy {
        buildIcon(name = "redo", pathData = AmperfyIconPaths.GOBACKWARD)
    }

    /**
     * iOS: AmperfyImage.resize ("arrow.down.left.and.arrow.up.right")——Albums 页
     * Change Grid Size（AlbumsCommonVCInteractions.swift:443）。
     * **近似**：Cupertino 1.0.8 无该符号，取 `arrow_up_left_arrow_down_right`
     * （同为「对角双向箭头」，仅对角线方向相反：iOS 是 ↙↗，Cupertino 是 ↖↘）。
     */
    val resize: ImageVector by lazy {
        buildIcon(name = "resize", pathData = AmperfyIconPaths.ARROW_UP_LEFT_ARROW_DOWN_RIGHT)
    }

    /** iOS: AmperfyImage.clipboard ("doc.on.doc")——Copy ID to Clipboard */
    val clipboard: ImageVector by lazy {
        buildIcon(name = "clipboard", pathData = AmperfyIconPaths.DOC_ON_DOC)
    }

    /**
     * iOS: AmperfyImage.ban ("circle.slash")——上下文菜单 Rating 调色板的 No Rating。
     * 用 Cupertino `slash_circle`（同为「圆内斜杠」形）。
     */
    val ban: ImageVector by lazy {
        buildIcon(name = "ban", pathData = AmperfyIconPaths.SLASH_CIRCLE)
    }

    /**
     * iOS: AmperfyImage.followLink ("arrowshape.turn.up.forward.fill")——电台 Go to Site。
     * 箭头有方向性，RTL 需镜像。
     */
    val followLink: ImageVector by lazy {
        buildIcon(
            name = "followLink",
            pathData = AmperfyIconPaths.ARROWSHAPE_TURN_UP_RIGHT_FILL,
            autoMirror = true
        )
    }

    /**
     * iOS: AmperfyImage.squareArrow ("arrow.forward.square")——单集菜单 Show Podcast、
     * 播放器「滚动到当前播放」。箭头有方向性，RTL 需镜像。
     */
    val squareArrow: ImageVector by lazy {
        buildIcon(
            name = "squareArrow",
            pathData = AmperfyIconPaths.ARROW_RIGHT_SQUARE,
            autoMirror = true
        )
    }

    /**
     * iOS 无对应 AmperfyImage 常量：行尾 disclosure 走 UIKit `.disclosureIndicator`、
     * 上下文子菜单箭头由 UIMenu 系统绘制，二者均为 SF `chevron.right` 形。
     * 属性名按 SF 名直译；RTL 需镜像。
     */
    val chevronRight: ImageVector by lazy {
        buildIcon(
            name = "chevronRight",
            pathData = AmperfyIconPaths.CHEVRON_RIGHT,
            autoMirror = true
        )
    }

    /**
     * iOS 无对应 AmperfyImage 常量：顶栏返回箭头由 UINavigationController 系统绘制，
     * 形为 SF `chevron.backward`。属性名按 SF 名直译；与 [chevronRight] 同集配对，
     * RTL 需镜像。
     */
    val chevronLeft: ImageVector by lazy {
        buildIcon(
            name = "chevronLeft",
            pathData = AmperfyIconPaths.CHEVRON_LEFT,
            autoMirror = true
        )
    }

    /**
     * iOS 无对应 AmperfyImage 常量：UIMenu 子菜单**展开态**父项右侧的箭头由系统绘制，
     * 形为 SF `chevron.down`（收起态为 `chevron.right`，见 [chevronRight]）。
     * 属性名按 SF 名直译；垂直方向无 RTL 语义，**不镜像**。
     */
    val chevronDown: ImageVector by lazy {
        buildIcon(name = "chevronDown", pathData = AmperfyIconPaths.CHEVRON_DOWN)
    }

    /**
     * iOS 无对应 AmperfyImage 常量（**Android 独有用点**）：Swipe 设置页「上移一位」按钮
     * ——iOS 该页靠列表拖拽重排，无上下移按钮。形为 SF `chevron.up`，与 [chevronDown]
     * 同集配对；垂直方向无 RTL 语义，**不镜像**。
     */
    val chevronUp: ImageVector by lazy {
        buildIcon(name = "chevronUp", pathData = AmperfyIconPaths.CHEVRON_UP)
    }

    // MARK: - 导航与账户

    /**
     * iOS: AmperfyImage.home ("house.fill"，UIImageAssetsExtension.swift:185)
     * ——底部 Home Tab（对齐 iOS homeTab）。
     */
    val home: ImageVector by lazy {
        buildIcon(name = "home", pathData = AmperfyIconPaths.HOUSE_FILL)
    }

    /**
     * iOS: AmperfyImage.musicLibrary ("music.note.square.stack.fill"，
     * UIImageAssetsExtension.swift:194)——底部 Library Tab。
     * **近似**：iOS 用的是 SF 5 复合符号，cupertino_icons 1.0.8 无同款，
     * 取 `music_albums_fill`（同为「唱片盒堆叠 + 音符」形）。
     */
    val musicLibrary: ImageVector by lazy {
        buildIcon(name = "musicLibrary", pathData = AmperfyIconPaths.MUSIC_ALBUMS_FILL)
    }

    /**
     * iOS: AmperfyImage.search ("magnifyingglass"，UIImageAssetsExtension.swift:222)
     * ——底部 Search Tab。
     */
    val search: ImageVector by lazy {
        buildIcon(name = "search", pathData = AmperfyIconPaths.SEARCH)
    }

    /**
     * iOS: AmperfyImage.account ("person.circle.fill"，UIImageAssetsExtension.swift:144)
     * ——各 Tab 顶栏右上的账户按钮（经 UIImage.userCircle(withConfiguration:) 取用，
     * UIImageAssetsExtension.swift:422-425）、账户菜单内的每一行账户。
     *
     * cupertino `person_circle_fill` 与 SF 同名**精确对应**（勿取 crop 变体
     * `person_crop_circle_fill`——那是人形被圆裁切的版本，肩部剪影延伸到圆边、人形占比更大）。
     */
    val account: ImageVector by lazy {
        buildIcon(name = "account", pathData = AmperfyIconPaths.PERSON_CIRCLE_FILL)
    }

    /**
     * iOS: AmperfyImage.userCirclePlus ("person.crop.circle.fill.badge.plus"，
     * UIImageAssetsExtension.swift:250)——账户菜单的 Add Account
     * （CommonScreenOperations.swift:88）。cupertino 同名字形精确对应
     * （勿取非 fill 的 `person_crop_circle_badge_plus`：圆为描边环 + 实心人形，
     * 与 fill 版的「实心圆盘反挖人形」明暗相反）。
     */
    val userCirclePlus: ImageVector by lazy {
        buildIcon(
            name = "userCirclePlus",
            pathData = AmperfyIconPaths.PERSON_CROP_CIRCLE_FILL_BADGE_PLUS
        )
    }

    /**
     * iOS: AmperfyImage.userPerson ("person.fill"，UIImageAssetsExtension.swift:251)
     * ——登录页 Username 输入框的前置图标。
     */
    val userPerson: ImageVector by lazy {
        buildIcon(name = "userPerson", pathData = AmperfyIconPaths.PERSON_FILL)
    }

    /**
     * iOS: AmperfyImage.serverUrl ("globe"，UIImageAssetsExtension.swift:224)
     * ——登录页 Server URL 输入框的前置图标。字形取同名的 cupertino `globe`。
     */
    val serverUrl: ImageVector by lazy {
        buildIcon(name = "serverUrl", pathData = AmperfyIconPaths.GLOBE)
    }

    /**
     * iOS: AmperfyImage.password ("key.fill"，UIImageAssetsExtension.swift:201)
     * ——登录页 Password 输入框的前置图标。
     * **近似**：cupertino_icons 1.0.8 无钥匙字形，取 `lock_fill`（实心挂锁）
     * ——属性名按约定仍随 iOS 常量名，字形与 SF 的钥匙形不同一点以本注记为准。
     */
    val password: ImageVector by lazy {
        buildIcon(name = "password", pathData = AmperfyIconPaths.LOCK_FILL)
    }

    /**
     * iOS 无对应 AmperfyImage 常量（**Android 独有用点**）：登录页密码「显示」开关
     * ——iOS 登录页无明文切换按钮。形为 SF `eye.fill`，与 [eyeSlashFill] 配对。
     */
    val eyeFill: ImageVector by lazy {
        buildIcon(name = "eyeFill", pathData = AmperfyIconPaths.EYE_FILL)
    }

    /** iOS 无对应常量（Android 独有）：登录页密码「隐藏」态，SF `eye.slash.fill`；说明同 [eyeFill] */
    val eyeSlashFill: ImageVector by lazy {
        buildIcon(name = "eyeSlashFill", pathData = AmperfyIconPaths.EYE_SLASH_FILL)
    }

    /**
     * iOS: AmperfyImage.login ("arrow.right.to.line"，UIImageAssetsExtension.swift:190)
     * ——登录页的登录按钮。箭头有方向性，RTL 需镜像。
     */
    val login: ImageVector by lazy {
        buildIcon(
            name = "login",
            pathData = AmperfyIconPaths.ARROW_RIGHT_TO_LINE,
            autoMirror = true
        )
    }

    /**
     * iOS: AmperfyImage.settings ("gear"，UIImageAssetsExtension.swift:225)
     * ——账户菜单的 Settings 入口。
     * 用 Cupertino `settings` (U+F411)：**细密多齿 + 三辐条 + 中心圆孔**的老式齿轮，
     * 即 iOS 设置 App 的经典齿轮形，与 SF `gear` 真机形态一致（辐条与中心孔由 nonZero
     * 反挖得到）；`settings_solid` (U+F412) 字体内轮廓与本字形相同，取前者即可。
     *
     * **选型记录**：曾取 `gear_alt_fill`（实心齿轮剪影）、并把本字形判为「旧 iOS 7 风格」
     * 排除——iOS 真机对照后确认判断恰好反了，故换回本字形。
     * 同族的 `gear` / `gear_alt` (U+F43C) 是**描边**齿轮（对应 SF `gearshape`），仍不取。
     */
    val settings: ImageVector by lazy {
        buildIcon(name = "settings", pathData = AmperfyIconPaths.SETTINGS)
    }

    // MARK: - 播放与队列

    /** iOS: AmperfyImage.play ("play.fill")——菜单 / 滑动动作 / 详情页头部的播放（播放器走带控件不用此项） */
    val play: ImageVector by lazy {
        buildIcon(name = "play", pathData = AmperfyIconPaths.PLAY_FILL)
    }

    /** iOS: AmperfyImage.shuffle ("shuffle")（另 AmperfyImage.shuffleMenu 同符号） */
    val shuffle: ImageVector by lazy {
        buildIcon(name = "shuffle", pathData = AmperfyIconPaths.SHUFFLE)
    }

    /**
     * iOS: AmperfyImage.listBullet ("list.bullet")——Music/Podcast Queue 子菜单父项；
     * 另 AmperfyImage.playlistDisplayStyle 为同一符号（播放器 LARGE⇄COMPACT / 展开队列）。
     * RTL 需镜像（对应原 Icons.AutoMirrored 的 List / FormatListBulleted）。
     */
    val listBullet: ImageVector by lazy {
        buildIcon(
            name = "listBullet",
            pathData = AmperfyIconPaths.LIST_BULLET,
            autoMirror = true
        )
    }

    /** iOS: AmperfyImage.playlist ("music.note.list")——Playlists 导航项 / Add to Playlist 滑动动作 */
    val playlist: ImageVector by lazy {
        buildIcon(name = "playlist", pathData = AmperfyIconPaths.MUSIC_NOTE_LIST)
    }

    /**
     * iOS: AmperfyImage.playlistX ("text.badge.xmark"，UIImageAssetsExtension.swift:211)
     * ——播放器 Player Options 菜单的 Clear User Queue（PlayerControlView.swift:322-325）
     */
    val playlistX: ImageVector by lazy {
        buildIcon(name = "playlistX", pathData = AmperfyIconPaths.TEXT_BADGE_XMARK)
    }

    /** iOS: AmperfyImage.playlistPlus ("text.badge.plus")——上下文菜单 Add to Playlist */
    val playlistPlus: ImageVector by lazy {
        buildIcon(name = "playlistPlus", pathData = AmperfyIconPaths.TEXT_BADGE_PLUS)
    }

    /**
     * iOS: AmperfyImage.repeatAll ("repeat")；AmperfyImage.repeatMenu 为同一符号
     * ——队列段头 Repeat 按钮的 **off / all** 两态（PlayerUIHandler.swift:177-201，
     * 两态同图标、只靠选中态配色区分）。
     */
    val repeatAll: ImageVector by lazy {
        buildIcon(name = "repeatAll", pathData = AmperfyIconPaths.REPEAT)
    }

    /** iOS: AmperfyImage.repeatOne ("repeat.1")——队列段头 Repeat 按钮的 single 态 */
    val repeatOne: ImageVector by lazy {
        buildIcon(name = "repeatOne", pathData = AmperfyIconPaths.REPEAT_1)
    }

    /**
     * iOS: AmperfyImage.skipBackward10 ("gobackward.10")——播放器 Skip -10s 按钮
     * （Settings→Display 的 Music Player Skip Buttons 开启时显示）。
     *
     * **注**：xib 硬编码的是 SF 6 新符号 `10.arrow.trianglehead.counterclockwise`
     * （PlayerControlView.xib:59），cupertino_icons 1.0.8 必无同款；此处取与**常量表**
     * 一致的 `gobackward.10`（圆弧箭头 + 数字 10），是「以 xib 为准」的例外，
     * 形似度为「中」。
     */
    val skipBackward10: ImageVector by lazy {
        buildIcon(name = "skipBackward10", pathData = AmperfyIconPaths.GOBACKWARD_10)
    }

    /** iOS: AmperfyImage.skipForward10 ("goforward.10")——播放器 Skip +10s 按钮；说明同 [skipBackward10] */
    val skipForward10: ImageVector by lazy {
        buildIcon(name = "skipForward10", pathData = AmperfyIconPaths.GOFORWARD_10)
    }

    // MARK: - 实体语义

    /** iOS: AmperfyImage.album ("square.stack")——Show Album / Albums 导航项 */
    val album: ImageVector by lazy {
        buildIcon(name = "album", pathData = AmperfyIconPaths.SQUARE_STACK)
    }

    /** iOS: AmperfyImage.artist ("music.mic")——Show Artist / Artists 导航项 */
    val artist: ImageVector by lazy {
        buildIcon(name = "artist", pathData = AmperfyIconPaths.MUSIC_MIC)
    }

    /**
     * iOS: AmperfyImage.musicalNotes ("music.note")——播放器「音乐 ⇄ 播客」模式切换按钮的
     * 音乐侧（PlayerControlView.swift:438-448 refreshPlayerModeChangeButton）；
     * 另 ArtworkType.song 的实体图标亦为此常量。
     */
    val musicalNotes: ImageVector by lazy {
        buildIcon(name = "musicalNotes", pathData = AmperfyIconPaths.MUSIC_NOTE)
    }

    /**
     * iOS: AmperfyImage.genre ("guitars.fill"，UIImageAssetsExtension.swift:177)
     * ——Genres 导航项 / 流派实体默认图标。
     * **近似**：cupertino_icons 1.0.8 只有轮廓版 `guitars`（无 fill 变体），
     * 形状（三把乐器并排）一致但为线稿，视觉重量比同排填充图标轻。
     */
    val genre: ImageVector by lazy {
        buildIcon(name = "genre", pathData = AmperfyIconPaths.GUITARS)
    }

    /**
     * iOS: AmperfyImage.folder ("folder.fill"，UIImageAssetsExtension.swift:174)
     * ——Directories 导航项 / 音乐文件夹与目录实体图标。
     */
    val folder: ImageVector by lazy {
        buildIcon(name = "folder", pathData = AmperfyIconPaths.FOLDER_FILL)
    }

    /**
     * iOS: AmperfyImage.radio ("dot.radiowaves.left.and.right"，
     * UIImageAssetsExtension.swift:214)——Radios 导航项 / 电台实体图标。
     *
     * **与 [antenna] 不是同一符号**：后者是 "antenna.radiowaves.left.and.right"
     * （:147，播放器「流播中」角标，带天线杆），本项只有中心圆点 + 两侧弧线。
     * **近似**：Cupertino 版每侧 3 道弧，SF 每侧 2 道弧。
     */
    val radio: ImageVector by lazy {
        buildIcon(name = "radio", pathData = AmperfyIconPaths.DOT_RADIOWAVES_LEFT_RIGHT)
    }

    // MARK: - 播放器

    /**
     * iOS: AmperfyImage.lyrics ("quote.bubble")——播放器菜单 Show / Hide Lyrics
     * （PlayerControlView.swift:334,346）、上下文菜单 Show Lyrics（EntityPreviewVC.swift:778）。
     */
    val lyrics: ImageVector by lazy {
        buildIcon(name = "lyrics", pathData = AmperfyIconPaths.QUOTE_BUBBLE)
    }

    /**
     * iOS: AmperfyImage.sparkles ("sparkles")——播放器菜单 Visualizer Style 子菜单
     * （PlayerControlView.swift:290-310 createVisualizerTypeMenu）。
     */
    val sparkles: ImageVector by lazy {
        buildIcon(name = "sparkles", pathData = AmperfyIconPaths.SPARKLES)
    }

    /**
     * iOS: AmperfyImage.playbackRate ("gauge.open.with.lines.needle.33percent")
     * ——播放器菜单 Playback Rate 子菜单（PlayerControlView.swift:271-288）。
     * **近似**：iOS 用的是 SF 6 新符号，cupertino_icons 1.0.8 必无同款，
     * 取 `speedometer`（同为「仪表盘带指针」）。
     */
    val playbackRate: ImageVector by lazy {
        buildIcon(name = "playbackRate", pathData = AmperfyIconPaths.SPEEDOMETER)
    }

    /**
     * iOS: AmperfyImage.sleep ("moon.zzz")——播放器菜单 Sleep Timer 子菜单
     * （AppDelegateMainMenuExtension.swift:363,416 createSleepTimerMenu）。
     */
    val sleep: ImageVector by lazy {
        buildIcon(name = "sleep", pathData = AmperfyIconPaths.MOON_ZZZ)
    }

    /**
     * iOS: AmperfyImage.volumeMax ("speaker.wave.3.fill")——播放器底排音量按钮
     * （xib 硬编码 "volume.3.fill" 为同形符号的旧名；MiniPlayerView.swift:360 用常量）。
     */
    val volumeMax: ImageVector by lazy {
        buildIcon(name = "volumeMax", pathData = AmperfyIconPaths.SPEAKER_3_FILL)
    }

    /**
     * iOS: AmperfyImage.antenna ("antenna.radiowaves.left.and.right")——播放器
     * 「流播中」角标（PlayerUIHandler.swift:487，与已缓存态的 [download] 二选一）。
     */
    val antenna: ImageVector by lazy {
        buildIcon(name = "antenna", pathData = AmperfyIconPaths.ANTENNA_RADIOWAVES_LEFT_RIGHT)
    }

    // MARK: - 收藏与评分

    /** iOS: AmperfyImage.heartFill ("heart.fill")——已收藏 */
    val heartFill: ImageVector by lazy {
        buildIcon(name = "heartFill", pathData = AmperfyIconPaths.HEART_FILL)
    }

    /** iOS: AmperfyImage.heartEmpty ("heart")——未收藏 / 上下文菜单 Favorite */
    val heartEmpty: ImageVector by lazy {
        buildIcon(name = "heartEmpty", pathData = AmperfyIconPaths.HEART)
    }

    /**
     * iOS: AmperfyImage.heartSlash ("heart.slash")——上下文菜单「Unmark favorite」。
     * Android 原用实心心表示"已收藏可取消"，改为对齐 iOS 的斜杠心。
     */
    val heartSlash: ImageVector by lazy {
        buildIcon(name = "heartSlash", pathData = AmperfyIconPaths.HEART_SLASH)
    }

    /**
     * iOS: xib 硬编码 "suit.heart.fill"（View/PlayableTableCell.xib:88、AlbumTableCell 同）
     * ——**列表行内的「已收藏」小标**，与常量 AmperfyImage.heartFill ("heart.fill") 是
     * 不同的 SF 符号（以 xib 为准，不并入 heartFill）。
     * 属性名按 SF 名直译（iOS 无对应 AmperfyImage 常量）。
     *
     * **实测提示**：cupertino_icons 1.0.8 中 `suit_heart_fill` (U+F834) 与
     * `heart_fill` (U+F443) 虽是两个不同的 glyph index（999 / 587），轮廓数据却完全相同
     * ——即本属性与 [heartFill] 当前渲染无差别。仍保留独立属性以对齐 iOS 的符号出处，
     * 将来换字形源时只改此处。
     */
    val suitHeartFill: ImageVector by lazy {
        buildIcon(name = "suitHeartFill", pathData = AmperfyIconPaths.SUIT_HEART_FILL)
    }

    /** iOS: AmperfyImage.starFill ("star.fill")——评分实星 */
    val starFill: ImageVector by lazy {
        buildIcon(name = "starFill", pathData = AmperfyIconPaths.STAR_FILL)
    }

    /** iOS: AmperfyImage.starEmpty ("star")——评分空星 */
    val starEmpty: ImageVector by lazy {
        buildIcon(name = "starEmpty", pathData = AmperfyIconPaths.STAR)
    }

    // MARK: - 下载与选择态

    /**
     * iOS: AmperfyImage.download ("arrow.down.circle")；
     * AmperfyImage.startDownload 与 UIImage.cache 均为同一常量的别名
     * （UIImageAssetsExtension.swift:298 `cache = download`），播放器"已缓存"角标共用。
     */
    val download: ImageVector by lazy {
        buildIcon(name = "download", pathData = AmperfyIconPaths.ARROW_DOWN_CIRCLE)
    }

    /** iOS: AmperfyImage.circle ("circle")；AmperfyImage.unSelected 为同一常量的别名——多选未选态 */
    val circle: ImageVector by lazy {
        buildIcon(name = "circle", pathData = AmperfyIconPaths.CIRCLE)
    }

    /**
     * iOS: AmperfyImage.isSelected ("checkmark.circle.fill")——**一切列表行的「已选」附件**
     * （填充圆内反挖白钩的圆徽），未选态按场景为 [circle] 或 [plusCircle]。用点：
     * - Library 编辑页（SelectionAccessory.swift:36 `.isSelected`，由
     *   LibraryNavigatorConfigurator.swift:332-334 装配）；
     * - 列表行 selection / add 模式（PlayableTableCell.swift:339-351）；
     * - 播放列表多选（PlaylistSelectorVC.swift:305-312）。
     *
     * **与 [check] 的分工（易错点）**：后三处 iOS 源码写的是 `.checkmark`
     * ——那是 **UIKit 内建 `UIImage.checkmark`**（全仓无自定义重载），渲染即本项的圆徽形；
     * 而 [check] 是 `AmperfyImage.check` = `UIImage.create(systemName: "checkmark")`
     * （UIImageAssetsExtension.swift:158,301）的 **SF 裸勾**，只用于**菜单单选打勾**。
     * 二者名字近似、形态迥异，改动前务必回查 iOS 是 `.checkmark` 还是 `.check`。
     */
    val isSelected: ImageVector by lazy {
        buildIcon(name = "isSelected", pathData = AmperfyIconPaths.CHECKMARK_CIRCLE_FILL)
    }

    /**
     * iOS: AmperfyImage.plusCircle ("plus.circle")——**添加模式**未选态
     * （PlayableTableCell.swift:346-351，displayMode == .add，用于 PlaylistAdd* 系列 VC）。
     * 与选择模式（displayMode == .selection）的 [circle] 区分：iOS 两种模式的已选态同为
     * 裸勾 [check]，未选态分别是 plus.circle / circle。
     */
    val plusCircle: ImageVector by lazy {
        buildIcon(name = "plusCircle", pathData = AmperfyIconPaths.PLUS_CIRCLE)
    }

    // ═══════════════════════════════════════════════════════════
    // Custom-drawn icons
    //
    // Positions with no suitable Cupertino glyph, or whose iOS icon is a custom asset.
    // All of them are built from the custom path data in [AmperfyIconPathsCustom];
    // no Material icon is used anywhere in the app.
    // ═══════════════════════════════════════════════════════════

    /**
     * iOS: AmperfyImage.cloudX ("xmark.icloud") - podcast episode Delete on Server and
     * Downloads screen Cancel all downloads.
     * Custom-drawn ([AmperfyIconPathsCustom.CLOUD_X]): cupertino_icons 1.0.8 has no
     * `xmark_icloud` glyph, so a cloud with a punched-out cross is built from geometry.
     */
    val cloudX: ImageVector by lazy {
        buildIcon(name = "cloudX", pathData = AmperfyIconPathsCustom.CLOUD_X)
    }

    /**
     * iOS: AmperfyImage.airplayaudio ("airplayaudio") - output device button at the far left
     * of the player's bottom row.
     * Custom-drawn ([AmperfyIconPathsCustom.AIRPLAY_AUDIO]): cupertino_icons has no AirPlay
     * glyph, and AirPlay is an Apple trademark graphic; on Android this button has Cast
     * semantics, so a Cast-style shape (screen outline + triangle) is drawn instead.
     */
    val airplayaudio: ImageVector by lazy {
        buildIcon(name = "airplayaudio", pathData = AmperfyIconPathsCustom.AIRPLAY_AUDIO)
    }

    /**
     * iOS: AmperfyImage.audioVisualizer ("circle.dashed")——播放器菜单
     * Show / Hide Audio Visualizer（PlayerControlView.swift:356-380）。
     * cupertino_icons 1.0.8 无虚线圆字形，按 16 段等分环带弧自绘
     * （[AmperfyIconPathsCustom.CIRCLE_DASHED]）。
     */
    val audioVisualizer: ImageVector by lazy {
        buildIcon(name = "audioVisualizer", pathData = AmperfyIconPathsCustom.CIRCLE_DASHED)
    }

    /**
     * iOS: 自定义 asset `podcast`（AmperfyImage.podcast / .podcastEpisode 同一 asset）
     * ——播放器「音乐 ⇄ 播客」模式切换按钮的播客侧、播客实体默认图标。
     * 照形重绘（[AmperfyIconPathsCustom.PODCAST]）：同心开口弧 + 中心点 + 身形。
     */
    val podcast: ImageVector by lazy {
        buildIcon(name = "podcast", pathData = AmperfyIconPathsCustom.PODCAST)
    }

    /**
     * iOS: 自定义 asset `album_newest`（AmperfyImage.albumNewest，
     * UIImageAssetsExtension.swift:131）——Library 的 Newest Albums 导航项。
     * 自绘（[AmperfyIconPathsCustom.ALBUM_NEWEST]）：MIT `square_stack` 底形 +
     * MIT `staroflife` 缩放居中内嵌，与 iOS `staroflife.square.stack` 同形。
     */
    val albumNewest: ImageVector by lazy {
        buildIcon(name = "albumNewest", pathData = AmperfyIconPathsCustom.ALBUM_NEWEST)
    }

    /**
     * iOS: 自定义 asset `album_recent`（AmperfyImage.albumRecent，
     * UIImageAssetsExtension.swift:132）——Library 的 Recently Played Albums 导航项。
     * 自绘（[AmperfyIconPathsCustom.ALBUM_RECENT]）：同上底形 + MIT `timer` 内嵌，
     * 与 iOS `timer.square.stack` 同形。
     */
    val albumRecent: ImageVector by lazy {
        buildIcon(name = "albumRecent", pathData = AmperfyIconPathsCustom.ALBUM_RECENT)
    }

    /**
     * iOS: 自定义 asset `user_queue_insert`（SF Symbols 5 custom
     * `text.line.first.and.arrowtriangle.forward` + person 角标）。
     * 照形重绘（[AmperfyIconPathsCustom.USER_QUEUE_INSERT]）。
     */
    val userQueueInsert: ImageVector by lazy {
        buildIcon(name = "userQueueInsert", pathData = AmperfyIconPathsCustom.USER_QUEUE_INSERT)
    }

    /** iOS: 自定义 asset `user_queue_append`；同上照形重绘 */
    val userQueueAppend: ImageVector by lazy {
        buildIcon(name = "userQueueAppend", pathData = AmperfyIconPathsCustom.USER_QUEUE_APPEND)
    }

    /**
     * iOS: 自定义 asset `context_queue_insert`（SF Symbols 5 custom
     * `text.line.first.and.arrowtriangle.forward`）。同上照形重绘。
     */
    val contextQueueInsert: ImageVector by lazy {
        buildIcon(name = "contextQueueInsert", pathData = AmperfyIconPathsCustom.CONTEXT_QUEUE_INSERT)
    }

    /** iOS: 自定义 asset `context_queue_append`；同上照形重绘 */
    val contextQueueAppend: ImageVector by lazy {
        buildIcon(name = "contextQueueAppend", pathData = AmperfyIconPathsCustom.CONTEXT_QUEUE_APPEND)
    }

    /**
     * iOS: `podcastQueueInsert = contextQueueInsert`（同一 asset，
     * UIImageAssetsExtension.swift:420），与 [contextQueueInsert] 合流。
     */
    val podcastQueueInsert: ImageVector by lazy { contextQueueInsert }

    /**
     * iOS: `podcastQueueAppend = contextQueueAppend`（同一 asset，同上 :421）。
     * 与 [contextQueueAppend] 合流。
     */
    val podcastQueueAppend: ImageVector by lazy { contextQueueAppend }

    /**
     * iOS: AmperfyImage.clear (SF "clear"，UIImageAssetsExtension.swift:160)
     * ——播放器菜单 Clear Player（PlayerControlView.swift:316）与 Downloads 页
     * Clear finished（DownloadsVC.swift:67-69）共用。
     * 自绘（[AmperfyIconPathsCustom.CLEAR_BOX]）：键盘清除键形的圆角方框描边 + 内嵌 X。
     */
    val clear: ImageVector by lazy {
        buildIcon(name = "clear", pathData = AmperfyIconPathsCustom.CLEAR_BOX)
    }
}
