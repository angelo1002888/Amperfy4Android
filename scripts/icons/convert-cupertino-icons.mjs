#!/usr/bin/env node
/**
 * CupertinoIcons.ttf 字形 -> Kotlin path 常量文件（AmperfyIconPaths.kt）转换脚本
 *
 * 路线说明（XML drawable -> Kotlin 常量）：
 * 实际消费方（SwipeActionType.getIcon()、九个 build*ContextMenuItems、
 * LibraryDisplayType.icon）全是非组合上下文，vectorResource 的 @Composable 约束不成立，
 * 故改产出普通 Kotlin 常量，由 AmperfyIcons.kt 用 ImageVector.Builder + addPathNodes 组装。
 *
 * 用法：
 *   npm i opentype.js                             （装在仓库外的临时目录，勿在仓库内建 node_modules）
 *   node scripts/icons/convert-cupertino-icons.mjs [--out <file>]
 *
 * 输入：
 *   scripts/icons/CupertinoIcons.ttf   字体本体（cupertino_icons 1.0.8，MIT）
 *   scripts/icons/codepoints.json      名称 -> 码位（Flutter 3.24.0 icons.dart 解析产物）
 *   本文件的 TARGETS 常量           目标字形清单
 *
 * 输出：
 *   单个 Kotlin 文件；缺省写入
 *   app/src/main/java/com/amperfy/ui/theme/AmperfyIconPaths.kt
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import opentype from 'opentype.js';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');
const TTF_PATH = path.join(SCRIPT_DIR, 'CupertinoIcons.ttf');
const CODEPOINTS_PATH = path.join(SCRIPT_DIR, 'codepoints.json');
const DEFAULT_OUT_FILE = path.join(
  REPO_ROOT, 'app', 'src', 'main', 'java', 'com', 'amperfy', 'ui', 'theme', 'AmperfyIconPaths.kt'
);

// --out <file>：中转文件输出（在仓库外的临时目录跑 node 时用），缺省直接写入仓库
function parseOutFile(argv) {
  const i = argv.indexOf('--out');
  if (i === -1) return DEFAULT_OUT_FILE;
  const file = argv[i + 1];
  if (!file) {
    console.error('FATAL: --out 后缺少文件路径参数');
    process.exit(1);
  }
  return path.resolve(file);
}

const OUT_FILE = parseOutFile(process.argv.slice(2));

// ---------------------------------------------------------------------------
// 目标字形清单（name 取 codepoints.json 的键 = Flutter CupertinoIcons 属性名）
//   B0 基建批 16 个高频字形 + B1 上下文菜单/滑动批新增 9 个 + B2 列表行/详情页批新增 9 个
//   + B2 修补 3 个 + B3 播放器批新增 9 个 + B3 追补 2 个（Skip ±10s）
//   + B4 导航/AccountMenu/LibraryDisplayType 批新增 9 个
//   + B5 设置/登录/下载/搜索/Home 批新增 12 个（另换 1 个：person_crop_circle_badge_plus
//     -> person_crop_circle_fill_badge_plus，取与 iOS .userCirclePlus 精确同名的 fill 版） = 69
//   + B5.2 真机修补换 1 个（gear_alt_fill -> settings，见下方该项注释），总数仍为 69
// 生成的常量名 = name.toUpperCase()；autoMirrored 由 AmperfyIcons.kt 侧声明
// （ImageVector 的 autoMirror 是构建参数，不落在 pathData 里），此处不再登记。
// 后续批次在本数组追加字形名后重跑本脚本即可。
// ---------------------------------------------------------------------------
const TARGETS = [
  // --- B0：通用操作 / 播放与队列 / 收藏评分 / 下载选择态 ---
  'ellipsis',
  'checkmark',
  'play_fill',
  'heart',
  'heart_fill',
  'star',
  'star_fill',
  'shuffle',
  'trash',
  'arrow_down_circle',
  'chevron_right',
  'plus',
  'xmark',
  'circle',
  'info_circle',
  'list_bullet',
  // --- B1：上下文菜单与滑动手势新增 ---
  'heart_slash',                    // 取消收藏（iOS .heartSlash）
  'slash_circle',                   // No Rating（iOS .ban = circle.slash）
  'text_badge_plus',                // Add to Playlist（iOS .playlistPlus）
  'square_stack',                   // Show Album（iOS .album）
  'music_mic',                      // Show Artist（iOS .artist）
  'arrow_right_square',             // Show Podcast / 滚动到当前播放（iOS .squareArrow）
  'arrowshape_turn_up_right_fill',  // Go to Site（iOS .followLink）
  'doc_on_doc',                     // Copy ID（iOS .clipboard）
  'music_note_list',                // Add to Playlist 滑动动作 / Playlists（iOS .playlist）
  // --- B2：列表行与详情页新增 ---
  'exclamationmark',                // 下载失败附件（iOS .exclamation）
  'plus_circle',                    // 添加模式未选态（iOS .plusCircle）
  'line_horizontal_3',              // 手动排序拖拽手柄（iOS .bars）
  'line_horizontal_3_decrease',     // 列表 Sort / OptionsButton（iOS .filter）
  'chevron_left',                   // 顶栏返回箭头（系统 chevron.backward，镜像）
  'suit_heart_fill',                // 行内收藏小心（xib "suit.heart.fill"，与常量 heart.fill 不同符号）
  'square_grid_2x2',                // Albums 网格样式（iOS .grid）
  'arrow_2_circlepath',             // 刷新 / Sync All Playlists（iOS .refresh 近似）
  'arrow_up_left_arrow_down_right', // Change Grid Size（iOS .resize 近似）
  // --- B2 修补（用户真机报障，主循环对 iOS 源码定案） ---
  'arrow_up_arrow_down',            // 列表 Sort 菜单（iOS .sort，UIImageAssetsExtension.swift:236）
  'chevron_down',                   // 上下文子菜单**展开态**父项箭头（收起为 chevron_right）
  'checkmark_circle_fill',          // 选择模式已选态（iOS .isSelected，SelectionAccessory.swift:36）
  // --- B3：播放器域新增 ---
  'music_note',                     // 播放器模式切换按钮（iOS .musicalNotes）
  'speaker_3_fill',                 // 音量按钮（iOS .volumeMax = speaker.wave.3.fill）
  'antenna_radiowaves_left_right',  // 播放器「流播中」角标（iOS .antenna）
  'quote_bubble',                   // Show/Hide Lyrics（iOS .lyrics）
  'sparkles',                       // Visualizer Style 子菜单（iOS .sparkles）
  'speedometer',                    // Playback Rate 子菜单（iOS .playbackRate 近似）
  'moon_zzz',                       // Sleep Timer 子菜单（iOS .sleep）
  'repeat',                         // 队列段头 Repeat off/all（iOS .repeatMenu / .repeatAll）
  'repeat_1',                       // 队列段头 Repeat single（iOS .repeatOne）
  'gobackward_10',                  // 播放器 Skip -10s（iOS .skipBackward10）
  'goforward_10',                   // 播放器 Skip +10s（iOS .skipForward10）
  // --- B4：导航三 Tab / AccountMenu / LibraryDisplayType 新增 ---
  'house_fill',                     // Home Tab（iOS .home = "house.fill"）
  'music_albums_fill',              // Library Tab（iOS .musicLibrary 近似）
  'search',                         // Search Tab（iOS .search = "magnifyingglass"）
  'person_crop_circle_fill_badge_plus', // Add Account（iOS .userCirclePlus，同名精确对应）
  'settings',                       // Settings（iOS .settings = "gear"）。B5 曾取 gear_alt_fill
                                    //   并把本字形判为「旧 iOS 7 风格不取」——用户 iOS 真机对照
                                    //   后**判断反转**：SF "gear" 实为细密多齿的老式齿轮（iOS
                                    //   Settings App 经典形），正是 cupertino 'settings' 0xf411；
                                    //   settings_solid 0xf412 字体内轮廓与之相同，故取前者
  'guitars',                        // Genres（iOS .genre = "guitars.fill" 近似）
  'folder_fill',                    // Directories（iOS .folder = "folder.fill"）
  'dot_radiowaves_left_right',      // Radios（iOS .radio，与 .antenna 是两个不同 SF 符号）
  // --- B5：设置 / 登录 / 下载 / 搜索 / Home 新增 ---
  'person_circle_fill',             // 账户头像（iOS .account = "person.circle.fill"，同名精确对应）
  'person_fill',                    // 登录页 Username 输入框（iOS .userPerson = "person.fill"）
  'globe',                          // 登录页 Server URL 输入框（iOS .serverUrl = "globe"）
  'arrow_right_to_line',            // 登录按钮（iOS .login = "arrow.right.to.line"，方向性镜像）
  'gobackward',                     // Downloads 页 Retry failed（iOS .redo = "gobackward"）
  'xmark_circle_fill',              // 搜索框清空钮（UIKit UISearchBar 系统清空钮同形）
  'chevron_up',                     // 滑动手势设置的上移（与 chevron_down 配对，Android 独有）
  'eye_fill',                       // 登录页密码可见（Android 独有，iOS 无该开关）
  'eye_slash_fill',                 // 登录页密码隐藏（同上）
  'minus_circle_fill',              // 滑动手势设置的移除（UIKit 编辑态红减号同形）
  'pencil',                         // Manage Server URLs 的 Edit（Android 独有）
  'lock_fill',                      // 登录页 Password 输入框（iOS .password = "key.fill" 近似）
  // --- B6：自绘批新增 2 个（MIT 字形直取，供 AmperfyIconPathsCustom.kt 组合复用）---
  //   注：cupertino 的 `clear` 0xf404 经渲染实证只是个 X（≠ SF "clear" 的圆角框+X），故不取，改自绘
  'staroflife',                     // album_newest 的方块内嵌件（iOS custom "staroflife.square.stack"）
  'timer',                          // album_recent 的方块内嵌件（iOS custom "timer.square.stack"）
  // --- B6.1：Player Options 补 Clear User Queue ---
  'text_badge_xmark',               // Clear User Queue（iOS .playlistX = "text.badge.xmark"，
                                    //   UIImageAssetsExtension.swift:211，同名精确对应）
];

// ---------------------------------------------------------------------------
// 归一参数（字体度量实测：unitsPerEm=512、ascender=454、descender=-74）
// em 盒总高 = 454 + 74 = 528，把它恰好映射到 24×24 画布：
//   fontSize = 24 * unitsPerEm / emHeight，baselineY = ascender / emHeight * 24
// 水平方向按字形 advanceWidth 在 24 宽内居中。
// ---------------------------------------------------------------------------
const CANVAS = 24;
const UNITS_PER_EM = 512;
const ASCENDER = 454;
const DESCENDER = -74;
const EM_HEIGHT = ASCENDER - DESCENDER; // 528
const FONT_SIZE = (CANVAS * UNITS_PER_EM) / EM_HEIGHT;
const BASELINE_Y = (ASCENDER / EM_HEIGHT) * CANVAS;

const BOUND_MIN = -0.5;
const BOUND_MAX = CANVAS + 0.5;

function fmt(n) {
  return Number(n.toFixed(3)).toString();
}

/** Kotlin 字符串字面量转义：pathData 只含 ASCII 数字/字母/符号，仅需防 \ 与 " */
function kotlinString(s) {
  return s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$');
}

function fileHeader() {
  return [
    '/*',
    ' * Amperfy4Android - an unofficial Android port of Amperfy',
    ' * Copyright (c) 2026 angelo',
    ' * Based on Amperfy for iOS, Copyright (c) 2019-2025 Maximilian Bauer',
    ' *',
    ' * This program is free software: you can redistribute it and/or modify',
    ' * it under the terms of the GNU General Public License as published by',
    ' * the Free Software Foundation, either version 3 of the License, or',
    ' * (at your option) any later version.',
    ' *',
    ' * This program is distributed in the hope that it will be useful,',
    ' * but WITHOUT ANY WARRANTY; without even the implied warranty of',
    ' * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the',
    ' * GNU General Public License for more details.',
    ' *',
    ' * You should have received a copy of the GNU General Public License',
    ' * along with this program.  If not, see <https://www.gnu.org/licenses/>.',
    ' */',
    '',
    'package com.amperfy.ui.theme',
    '',
    '/**',
    ' * CupertinoIcons 字形的 SVG pathData 常量表',
    ' *',
    ' * **本文件由脚本生成，请勿手改**——改动会在下次重跑时丢失。',
    ' * 生成器：scripts/icons/convert-cupertino-icons.mjs（新增字形 / 重跑方式见',
    ' * scripts/icons/README.md）。',
    ' *',
    ' * 字形来源：cupertino_icons 1.0.8 的 CupertinoIcons.ttf —— **MIT License**,',
    ' * Copyright (c) 2016 Vladimir Kharlampidi。许可全文留档于',
    ' * app/src/main/assets/licenses/cupertino_icons_LICENSE.txt，App 内许可页亦有条目。',
    ' *',
    ' * 坐标系：24×24 viewport、Y 向下（已按 fontSize=24×512/528、baselineY=454/528×24',
    ' * 归一并按 advanceWidth 水平居中）。仅供 AmperfyIcons.kt 经 addPathNodes() 构建',
    ' * ImageVector 使用；填充色恒由调用方 tint 决定。',
    ' *',
    ' * 注：opentype.js 的 glyf 解析不产出 \'Z\' 命令，各子路径无显式闭合指令；',
    ' * 只填充不描边时 Skia 自动闭合，几何结果与 TrueType 闭合轮廓一致。',
    ' * 填充规则用默认 NonZero（TrueType 轮廓绕向即 NonZero 语义，反挖轮廓靠绕向实现）。',
    ' */',
    'internal object AmperfyIconPaths {',
  ].join('\n');
}

function main() {
  const codepoints = JSON.parse(fs.readFileSync(CODEPOINTS_PATH, 'utf8'));
  const buf = fs.readFileSync(TTF_PATH);
  const font = opentype.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength));

  // 度量自检：与归一公式假设不符时立即报错，避免静默产出错位字形
  if (font.unitsPerEm !== UNITS_PER_EM) {
    console.error(`FATAL: unitsPerEm=${font.unitsPerEm}，与脚本假设 ${UNITS_PER_EM} 不符`);
    process.exit(1);
  }

  const seenConst = new Set();
  const body = [];
  let warnings = 0;

  for (const name of TARGETS) {
    const cpHex = codepoints[name];
    if (!cpHex) {
      console.error(`FATAL: codepoints.json 中无字形 "${name}"`);
      process.exit(1);
    }
    const cp = parseInt(cpHex, 16);
    const glyph = font.charToGlyph(String.fromCodePoint(cp));
    if (!glyph || glyph.index === 0) {
      console.error(`FATAL: 字体中无 ${name} (${cpHex}) 对应字形`);
      process.exit(1);
    }

    const constName = name.toUpperCase();
    if (seenConst.has(constName)) {
      console.error(`FATAL: 常量名冲突 ${constName}（字形 "${name}" 重复）`);
      process.exit(1);
    }
    seenConst.add(constName);

    const scale = FONT_SIZE / UNITS_PER_EM;
    const advance = glyph.advanceWidth * scale;
    const xOffset = (CANVAS - advance) / 2;
    const glyphPath = glyph.getPath(xOffset, BASELINE_Y, FONT_SIZE);
    const pathData = glyphPath.toPathData(3);

    if (!pathData || pathData.trim() === '') {
      console.error(`FATAL: ${name} (${cpHex}) 的 pathData 为空`);
      process.exit(1);
    }

    // sanity：打印 advance、路径边界与子路径数（'M' 命令计数，反挖轮廓项据此复核），
    // 越出画布则 WARN
    const bb = glyphPath.getBoundingBox();
    const subPaths = (pathData.match(/M/g) || []).length;
    const outOfBounds =
      bb.x1 < BOUND_MIN || bb.y1 < BOUND_MIN || bb.x2 > BOUND_MAX || bb.y2 > BOUND_MAX;
    const line =
      `${name.padEnd(30)} ${cpHex}  advance=${fmt(advance)}  ` +
      `bounds=[${fmt(bb.x1)}, ${fmt(bb.y1)}, ${fmt(bb.x2)}, ${fmt(bb.y2)}]  ` +
      `subPaths=${subPaths}`;
    if (outOfBounds) {
      warnings++;
      console.warn(`WARN  ${line}  超出 [${BOUND_MIN}, ${BOUND_MAX}]`);
    } else {
      console.log(`ok    ${line}`);
    }

    body.push('');
    body.push(`    /** cupertino_icons \`${name}\` (U+${cpHex.replace(/^0x/, '').toUpperCase()}) */`);
    body.push(`    const val ${constName} =`);
    body.push(`        "${kotlinString(pathData)}"`);
  }

  const out = [fileHeader(), ...body, '}', ''].join('\n');
  fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true });
  fs.writeFileSync(OUT_FILE, out, 'utf8');

  console.log(`\n生成 ${TARGETS.length} 个字形常量 -> ${OUT_FILE}，WARN ${warnings} 项`);
}

main();
