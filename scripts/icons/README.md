# CupertinoIcons → Kotlin path 常量转换

## 输出形态（B1 起）

脚本产出**单个 Kotlin 文件** `app/src/main/java/com/amperfy/ui/theme/AmperfyIconPaths.kt`
（`internal object AmperfyIconPaths`，每字形一个 `const val <NAME>` = 24×24 viewport 的 SVG pathData），
由 `ui/theme/AmperfyIcons.kt` 用 `ImageVector.Builder` + `addPathNodes()` 组装成 `ImageVector`。

> **沿革**：B0 批曾产出 `res/drawable/ic_cup_*.xml` 并在 `AmperfyIcons` 里用
> `@Composable ImageVector.vectorResource(...)` 读取，**该形态已于 B1 废弃并删除全部 16 个 XML**——
> 实际消费方（`SwipeActionType.getIcon()`、九个 `build*ContextMenuItems`、`LibraryDisplayType.icon`）
> 都是非组合上下文，`vectorResource` 的 `@Composable` 约束会迫使注解全链传播。

`autoMirrored` 不再落在产物里（ImageVector 的 `autoMirror` 是构建参数），改由 `AmperfyIcons.kt`
在各属性上声明。

## 来源与版本

| 文件 | 来源 | 版本 |
|---|---|---|
| `CupertinoIcons.ttf` | Flutter 官方 `cupertino_icons` 包 `assets/CupertinoIcons.ttf` | 1.0.8 |
| `LICENSE` | 同包 `LICENSE`（MIT，Copyright (c) 2016 Vladimir Kharlampidi） | 1.0.8 |
| `codepoints.json` | 由 Flutter `packages/flutter/lib/src/cupertino/icons.dart` 的 1322 条 `static const IconData` 声明解析而来（名称 → 码位权威表） | Flutter 3.24.0 |

`cupertino_icons` 包本体的 `css/icons.css` 是 ligature 字体样式表，**没有**逐字形的名称→码位映射，
因此权威表取自 Flutter SDK 的 `icons.dart`。

许可落点三处：本目录 `LICENSE` 留档、`app/src/main/assets/licenses/cupertino_icons_LICENSE.txt`、
`ui/screens/settings/LicenseSettingsScreen.kt` 许可页条目；生成的 `AmperfyIconPaths.kt` 头部亦带来源与许可注释。

## 字形清单（48 个）

B0 基建批 16 个：
`ellipsis` / `checkmark` / `play_fill` / `heart` / `heart_fill` / `star` / `star_fill` / `shuffle` /
`trash` / `arrow_down_circle` / `chevron_right` / `plus` / `xmark` / `circle` / `info_circle` / `list_bullet`

B1（上下文菜单与滑动手势）新增 9 个：
`heart_slash`（取消收藏）/ `slash_circle`（No Rating，iOS `.ban` = `circle.slash`）/
`text_badge_plus`（Add to Playlist）/ `square_stack`（Show Album）/ `music_mic`（Show Artist）/
`arrow_right_square`（Show Podcast，镜像）/ `arrowshape_turn_up_right_fill`（Go to Site，镜像）/
`doc_on_doc`（Copy ID）/ `music_note_list`（Playlists）

B2（列表行与详情页）新增 9 个：
`exclamationmark`（下载失败附件，iOS `.exclamation`）/ `plus_circle`（添加模式未选态，iOS `.plusCircle`）/
`line_horizontal_3`（拖拽手柄，iOS `.bars`）/ `line_horizontal_3_decrease`（Sort，iOS `.filter`）/
`chevron_left`（顶栏返回，系统 `chevron.backward`，镜像）/ `suit_heart_fill`（行内收藏小标，xib 硬编码）/
`square_grid_2x2`（网格样式，iOS `.grid`）/ `arrow_2_circlepath`（刷新，iOS `.refresh` **近似**）/
`arrow_up_left_arrow_down_right`（Change Grid Size，iOS `.resize` **近似**）

> 实测：`suit_heart_fill`(U+F834) 与 `heart_fill`(U+F443) 在本字体里是两个不同的 glyph index
> （999 / 587）但**轮廓数据完全相同**——iOS 侧 `suit.heart.fill` 与 `heart.fill` 的形状差异
> 在 cupertino_icons 1.0.8 中不存在。属性仍分开保留以对齐 iOS 符号出处。

B2 修补（用户真机报障，主循环对 iOS 源码定案）新增 3 个：
`arrow_up_arrow_down`（**排序**菜单，iOS `.sort` = "arrow.up.arrow.down"；B2 曾误接到
`line_horizontal_3_decrease`＝iOS `.filter` **筛选**图标）/ `chevron_down`（上下文子菜单**展开态**
父项箭头，收起态仍为 `chevron_right`）/ `checkmark_circle_fill`（Library 编辑页已选态，
iOS `.isSelected`；列表行选择/添加模式的已选态仍是裸勾 `checkmark`，二者不同源）

B3（播放器域）新增 9 个：
`music_note`（模式切换按钮音乐侧，iOS `.musicalNotes`）/ `speaker_3_fill`（音量按钮，iOS `.volumeMax`）/
`antenna_radiowaves_left_right`（「流播中」角标，iOS `.antenna`）/ `quote_bubble`（歌词，iOS `.lyrics`）/
`sparkles`（可视化样式子菜单，iOS `.sparkles`）/ `speedometer`（播放速率，iOS `.playbackRate` **近似**）/ `moon_zzz`（睡眠定时器，iOS `.sleep`）/ `repeat`（Repeat off/all，iOS
`.repeatMenu`/`.repeatAll`）/ `repeat_1`（Repeat single，iOS `.repeatOne`）

B3 追补新增 2 个：
`gobackward_10` / `goforward_10`（播放器 Skip ±10s，iOS `.skipBackward10` / `.skipForward10`）

> `Skip ±10s` 是「xib 优先」的**例外**：xib 硬编码的 `10.arrow.trianglehead.counterclockwise`
> / `…clockwise`（PlayerControlView.xib:59,107）是 SF 6 新符号，cupertino_icons 1.0.8 必无同款，
> 故取与**常量表**一致的 `gobackward.10` / `goforward.10`（圆弧箭头 + 反挖数字 10）。

> 实测：cupertino_icons 1.0.8 的 `clear`(U+F404) 与 `xmark`(U+F404) 是**同一码位**（裸 ✕），
> 无 SF `clear` 的圆角矩形外框——故 `clear` 改由 `gen-custom.mjs` 自绘（`CLEAR_BOX`）。同理 `volume_up`(U+F3BA) 即 `speaker_3_fill`。

## 重跑方式

**不要在本仓库内 `npm i`**（不得引入 `node_modules` / `package.json`）。在任意有 Node 的机器上：

```bash
cd /some/tmp/dir            # 仓库外的临时目录
npm i opentype.js           # 唯一依赖
node /path/to/repo/scripts/icons/convert-cupertino-icons.mjs --out ./AmperfyIconPaths.kt
# 目视确认后拷回 app/src/main/java/com/amperfy/ui/theme/
```

省略 `--out` 时直接写入 `app/src/main/java/com/amperfy/ui/theme/AmperfyIconPaths.kt`。
新增字形：在脚本的 `TARGETS` 数组追加字形名后重跑（名取 `codepoints.json` 的键），
再到 `AmperfyIcons.kt` 加对应属性（常量名 = 字形名的全大写）。

脚本每字形打印 `advance`、路径边界与子路径数（`M` 命令计数，反挖轮廓项据此复核），
边界越出 `[-0.5, 24.5]` 报 WARN，`pathData` 为空或常量名冲突则报错退出。

## 归一公式

字体度量实测 `unitsPerEm=512`、`ascender=454`、`descender=-74`（em 盒总高 528），把 em 盒恰好映到 24×24 画布：
`fontSize = 24×512/528`、`baselineY = 454/528×24`、`xOffset = (24 − advanceWidth×fontSize/512)/2`（按 advance 水平居中）。

填充规则用默认 **NonZero**（TrueType 轮廓绕向即 NonZero 语义；`heart.slash` / `circle.slash`
等反挖轮廓靠绕向实现，写 EvenOdd 反而会错）。opentype.js 的 glyf 解析不产出 `Z` 命令，
子路径无显式闭合指令；只填充不描边时 Skia 自动闭合，几何结果与 TrueType 闭合轮廓一致。

## 另一个脚本：`gen-custom.mjs`（自绘常量）

`convert-cupertino-icons.mjs` 只覆盖 cupertino_icons **有对应字形**的图标；cupertino 没有、
必须自绘的 9 个（四个队列符号 / album_newest / album_recent / podcast / circle.dashed / clear）
由 `gen-custom.mjs` 按 iOS `.symbolset` 的**几何测量**照形重绘生成
（不含任何 Apple SF Symbols 的 path data，详见脚本头部合规说明）。

`cloudX`（`CLOUD_X`，云形 + 挖空 ×）与 `airplayaudio`（`AIRPLAY_AUDIO`，Cast 语义的屏幕框 + 三角）
原为 Material 占位，现已改为自绘：二者是从零几何构造（圆弧 / 圆角矩形 / 圆角三角）的手写常量，
直接维护在 `AmperfyIconPathsCustom.kt`，不由 `gen-custom.mjs` 生成；项目不再依赖任何 Material 图标。

两者的关键差别：

| | `convert-cupertino-icons.mjs` | `gen-custom.mjs` |
|---|---|---|
| 产物 | `AmperfyIconPaths.kt`（**整文件重生成**，手写内容会被覆盖） | `custom-consts.txt`（**仅常量块，需手动粘贴**） |
| 落点 | `internal object AmperfyIconPaths` | `internal object AmperfyIconPathsCustom`（**手写维护**文件） |
| 依赖 | node + `opentype.js` | **无 npm 依赖**，仅 `node:fs` |
| 输入 | `CupertinoIcons.ttf` + `codepoints.json` | 同目录的 `AmperfyIconPaths.kt`（取三个 MIT 字形做仿射复用） |

跑法（node 须在仓库外目录执行）：

```bash
mkdir -p /tmp/icons && cd /tmp/icons
cp <repo>/scripts/icons/gen-custom.mjs .
cp <repo>/app/src/main/java/com/amperfy/ui/theme/AmperfyIconPaths.kt .   # 必须同目录
node gen-custom.mjs          # 产出 ./custom-consts.txt，并打印每个常量的字符数
```

再把 `custom-consts.txt` 全文替换 `AmperfyIconPathsCustom.kt` 中 object 大括号之间的部分
（文件头的合规与构成说明由人维护，脚本不生成也不触碰）。**不要手改常量里的坐标**——
各子形之间有相切 / 让位（截断）关系，改形状请改脚本参数后整条常量替换。

## 目视

`ui/theme/AmperfyIconsPreview.kt` 是全字形 @Preview 画廊（浅 / 深两个 Preview），
新增属性后在 `amperfyIconGallery()` 追加一行即可在 Android Studio 中目视。
