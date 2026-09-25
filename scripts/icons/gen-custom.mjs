/*
 * gen-custom.mjs —— 生成 AmperfyIconPathsCustom.kt 的 9 个**自绘**图标常量
 * （B6 自绘批产物，归档于 2026-08-08）。
 *
 * ## 与 convert-cupertino-icons.mjs 的分工
 *
 * | 脚本 | 覆盖对象 | 产物 |
 * |---|---|---|
 * | `convert-cupertino-icons.mjs` | cupertino_icons 有对应字形的图标 | `AmperfyIconPaths.kt`（**整文件重生成，可随时重跑覆盖**） |
 * | 本脚本 | cupertino 无对应字形、必须自绘的 9 个 | `custom-consts.txt`（**仅常量块，需手动粘贴**） |
 *
 * 两者产物落在两个不同的 Kotlin 文件里，正是因为前者会被整文件覆盖——
 * `AmperfyIconPathsCustom.kt` 是**手写维护**文件，其文件头的合规说明与构成说明由人维护，
 * 本脚本不生成也不触碰。
 *
 * ## 产物与粘贴方式（重要）
 *
 * 本脚本**不输出完整的 .kt 文件**，只在自身目录写出 `custom-consts.txt`，内容是 9 段
 * 已按 4 空格缩进、按 100 字符折行拼接好的 `const val NAME = "..." + "..."`。
 * 改动流程：跑脚本 → 目视核验渲染 → 把 `custom-consts.txt` 全文替换掉
 * `AmperfyIconPathsCustom.kt` 中 `internal object AmperfyIconPathsCustom {` 与结尾 `}` 之间的部分。
 * **不要手改常量里的坐标**：各子形之间有相切 / 让位（截断）关系，单点改动会破坏几何一致性；
 * 要调形状请改本脚本的参数后整条常量替换。
 *
 * ## 运行方式与依赖
 *
 * 依赖：**仅 Node 内置的 `node:fs`，无任何 npm 包**（不需要 opentype.js——那是
 * convert-cupertino-icons.mjs 才需要的；也不需要 puppeteer，那只用于渲染核验）。
 *
 * 输入：脚本**自身目录**下的 `AmperfyIconPaths.kt`（用于取 SQUARE_STACK / STAROFLIFE / TIMER
 * 三个 MIT 字形做仿射变换复用）。路径按 `import.meta.url` 解析，故必须与脚本同目录。
 *
 * 本仓开发环境（WSL）约定 node 只在仓库外的临时目录跑，因此步骤是：
 *
 * ```bash
 * mkdir -p /tmp/icons && cd /tmp/icons
 * cp <repo>/scripts/icons/gen-custom.mjs .
 * cp <repo>/app/src/main/java/com/amperfy/ui/theme/AmperfyIconPaths.kt .
 * node gen-custom.mjs          # 产出 ./custom-consts.txt
 * ```
 *
 * 渲染核验（可选，需 `npm i puppeteer`）：把生成的常量包进一个临时
 * `object AmperfyIconPathsCustom { ... }` 文件，用 24 / 48 / 96 三档 `<svg viewBox="0 0 24 24">`
 * 渲染并与 iOS `.symbolset` 的 Regular-S 变体并排目视。
 *
 * ## 合规（照形重绘）
 *
 * 本脚本按 iOS symbolset 的**几何测量**（各子形包围盒的位置 / 尺寸这类事实数据）照形重绘，
 * 生成 24×24 viewport 的 pathData：
 * - **不含任何来自 Apple SF Symbols 的 path data**——iOS 的 `.svg` 模板只用于目视参考与量尺寸；
 * - MIT 授权的 CupertinoIcons 字形（SQUARE_STACK / STAROFLIFE / TIMER）只做仿射变换后复用，合法。
 */
import fs from 'node:fs';

const KT = fs.readFileSync(new URL('./AmperfyIconPaths.kt', import.meta.url), 'utf8');
const glyph = (name) => {
  const m = KT.match(new RegExp(`const val ${name} =\\s*\\n?\\s*"([\\s\\S]*?)"\\s*\\n`, 'm'));
  if (!m) throw new Error('未找到字形 ' + name);
  return m[1].replace(/"\s*\+\s*\n\s*"/g, '');
};

const n = (v) => {
  const r = Math.round(v * 1000) / 1000;
  return String(r);
};

// ---------- 基础图元（均返回单条子路径字符串；cw=true 为填充方向，false 用于反挖）----------

// 圆弧转三次贝塞尔（每段 ≤90°），a0/a1 为弧度，方向由 a1>a0 决定
function arcPts(cx, cy, r, a0, a1) {
  const segs = Math.max(1, Math.ceil(Math.abs(a1 - a0) / (Math.PI / 2)));
  const da = (a1 - a0) / segs;
  const k = (4 / 3) * Math.tan(da / 4);
  let out = '';
  for (let i = 0; i < segs; i++) {
    const t0 = a0 + da * i;
    const t1 = t0 + da;
    const x0 = cx + r * Math.cos(t0), y0 = cy + r * Math.sin(t0);
    const x1 = cx + r * Math.cos(t1), y1 = cy + r * Math.sin(t1);
    const c1x = x0 - k * r * Math.sin(t0), c1y = y0 + k * r * Math.cos(t0);
    const c2x = x1 + k * r * Math.sin(t1), c2y = y1 - k * r * Math.cos(t1);
    out += `C${n(c1x)} ${n(c1y)} ${n(c2x)} ${n(c2y)} ${n(x1)} ${n(y1)}`;
  }
  return out;
}

function circle(cx, cy, r, cw = true) {
  const a0 = -Math.PI / 2;
  const a1 = cw ? a0 + Math.PI * 2 : a0 - Math.PI * 2;
  return `M${n(cx)} ${n(cy - r)}` + arcPts(cx, cy, r, a0, a1) + 'Z';
}

function ellipse(cx, cy, rx, ry, cw = true) {
  // 用单位圆生成后按 rx/ry 缩放：直接内联缩放，避免再写一套解析
  const a0 = -Math.PI / 2;
  const a1 = cw ? a0 + Math.PI * 2 : a0 - Math.PI * 2;
  const segs = 4;
  const da = (a1 - a0) / segs;
  const k = (4 / 3) * Math.tan(da / 4);
  let out = `M${n(cx)} ${n(cy - ry)}`;
  for (let i = 0; i < segs; i++) {
    const t0 = a0 + da * i, t1 = t0 + da;
    const P = (t, dx = 0, dy = 0) => [cx + rx * (Math.cos(t) + dx), cy + ry * (Math.sin(t) + dy)];
    const [x1, y1] = P(t1);
    const [c1x, c1y] = P(t0, -k * Math.sin(t0), k * Math.cos(t0));
    const [c2x, c2y] = P(t1, k * Math.sin(t1), -k * Math.cos(t1));
    out += `C${n(c1x)} ${n(c1y)} ${n(c2x)} ${n(c2y)} ${n(x1)} ${n(y1)}`;
  }
  return out + 'Z';
}

/** 横向棒：左右端可选圆头（iOS 文本行是全圆头 stadium） */
function bar(x0, x1, yc, h, roundLeft = true, roundRight = true) {
  const r = h / 2, top = yc - r, bot = yc + r;
  let d = `M${n(x0 + (roundLeft ? r : 0))} ${n(top)}`;
  d += `L${n(x1 - (roundRight ? r : 0))} ${n(top)}`;
  d += roundRight
    ? arcPts(x1 - r, yc, r, -Math.PI / 2, Math.PI / 2)
    : `L${n(x1)} ${n(bot)}`;
  d += `L${n(x0 + (roundLeft ? r : 0))} ${n(bot)}`;
  d += roundLeft
    ? arcPts(x0 + r, yc, r, Math.PI / 2, Math.PI * 1.5)
    : `L${n(x0)} ${n(top)}`;
  return d + 'Z';
}

/** 右向实心三角（三角尖朝右，三个角小圆角，对齐 SF arrowtriangle 观感） */
function triRight(x, yc, w, h, r = 0.42) {
  const top = yc - h / 2, bot = yc + h / 2, tipX = x + w;
  // 顶点顺序：左上 -> 右尖 -> 左下（顺时针）
  const P = [[x, top], [tipX, yc], [x, bot]];
  let d = '';
  for (let i = 0; i < 3; i++) {
    const cur = P[i], prev = P[(i + 2) % 3], next = P[(i + 1) % 3];
    const v1 = [prev[0] - cur[0], prev[1] - cur[1]];
    const v2 = [next[0] - cur[0], next[1] - cur[1]];
    const l1 = Math.hypot(...v1), l2 = Math.hypot(...v2);
    const a = [cur[0] + (v1[0] / l1) * r, cur[1] + (v1[1] / l1) * r];
    const b = [cur[0] + (v2[0] / l2) * r, cur[1] + (v2[1] / l2) * r];
    d += (i === 0 ? `M${n(a[0])} ${n(a[1])}` : `L${n(a[0])} ${n(a[1])}`);
    d += `Q${n(cur[0])} ${n(cur[1])} ${n(b[0])} ${n(b[1])}`;
  }
  return d + 'Z';
}

/** 圆角矩形（cw=false 时反向，用于挖空成描边框） */
function roundRect(x, y, w, h, r, cw = true) {
  const x1 = x + w, y1 = y + h;
  if (cw) {
    return `M${n(x + r)} ${n(y)}L${n(x1 - r)} ${n(y)}` +
      arcPts(x1 - r, y + r, r, -Math.PI / 2, 0) +
      `L${n(x1)} ${n(y1 - r)}` + arcPts(x1 - r, y1 - r, r, 0, Math.PI / 2) +
      `L${n(x + r)} ${n(y1)}` + arcPts(x + r, y1 - r, r, Math.PI / 2, Math.PI) +
      `L${n(x)} ${n(y + r)}` + arcPts(x + r, y + r, r, Math.PI, Math.PI * 1.5) + 'Z';
  }
  return `M${n(x + r)} ${n(y)}` + arcPts(x + r, y + r, r, -Math.PI / 2, -Math.PI) +
    `L${n(x)} ${n(y1 - r)}` + arcPts(x + r, y1 - r, r, Math.PI, Math.PI / 2) +
    `L${n(x1 - r)} ${n(y1)}` + arcPts(x1 - r, y1 - r, r, Math.PI / 2, 0) +
    `L${n(x1)} ${n(y + r)}` + arcPts(x1 - r, y + r, r, 0, -Math.PI / 2) + 'Z';
}

/** 环带扇形（arc band）：外半径 rO、内半径 rI，角度 a0→a1，两端为平口 */
function annulus(cx, cy, rO, rI, a0, a1) {
  const p = (r, a) => [cx + r * Math.cos(a), cy + r * Math.sin(a)];
  const [sx, sy] = p(rO, a0);
  const [mx, my] = p(rI, a1);
  return `M${n(sx)} ${n(sy)}` + arcPts(cx, cy, rO, a0, a1) +
    `L${n(mx)} ${n(my)}` + arcPts(cx, cy, rI, a1, a0) + 'Z';
}

/** 斜向胶囊（用于 X 的两笔） */
function stroke(x0, y0, x1, y1, hw) {
  const ang = Math.atan2(y1 - y0, x1 - x0);
  const nx = Math.cos(ang + Math.PI / 2) * hw, ny = Math.sin(ang + Math.PI / 2) * hw;
  return `M${n(x0 + nx)} ${n(y0 + ny)}L${n(x1 + nx)} ${n(y1 + ny)}` +
    arcPts(x1, y1, hw, ang + Math.PI / 2, ang - Math.PI / 2) +
    `L${n(x0 - nx)} ${n(y0 - ny)}` +
    arcPts(x0, y0, hw, ang - Math.PI / 2, ang + Math.PI / 2) + 'Z';
}

// ---------- MIT 字形的仿射变换（缩放 + 平移）----------
function xform(d, s, dx, dy) {
  return d.replace(/([MLQCZ])([^MLQCZ]*)/g, (_, cmd, args) => {
    if (cmd === 'Z') return 'Z';
    const nums = args.trim().split(/[\s,]+/).filter(Boolean).map(Number);
    const out = nums.map((v, i) => n(i % 2 === 0 ? v * s + dx : v * s + dy));
    return cmd + out.join(' ');
  });
}

// ===========================================================================
// 各图标构成
// ===========================================================================
const ICONS = {};

// --- 队列四件：文本行 + 右向三角（+ 人形角标）---
// 几何取自 iOS symbolset Regular-S 的子形包围盒测量，归一化到 24×24
function queueBase({ barX0, barX1, barH, thickH, ycs, thickIdx, tri, badge }) {
  const parts = [];
  ycs.forEach((yc, i) => {
    const h = i === thickIdx ? thickH : barH;
    let x1 = barX1, roundRight = true;
    if (badge) {
      const dy = Math.abs(yc - badge.cy);
      if (dy < badge.rOuter) {
        // 让位：棒在角标外圈处平口截断，外圈与实心角标之间自然留出白隙
        const cut = badge.cx - Math.sqrt(badge.rOuter ** 2 - dy ** 2);
        if (cut < x1) { x1 = cut; roundRight = false; }
      }
    }
    if (x1 - barX0 > h) parts.push(bar(barX0, x1, yc, h, true, roundRight));
  });
  parts.push(triRight(tri.x, tri.yc, tri.w, tri.h, 0.42));
  if (badge) {
    parts.push(circle(badge.cx, badge.cy, badge.rInner, true));
    // 人形反挖（逆时针）：头 + 肩
    parts.push(ellipse(badge.head.cx, badge.head.cy, badge.head.rx, badge.head.ry, false));
    parts.push(shoulders(badge.body, false));
  }
  return parts.join('');
}

/** 肩部：上圆下平的半胶囊 */
function shoulders({ cx, top, bot, w }, cw = true) {
  const r = w / 2, yc = top + r;
  if (cw) {
    return `M${n(cx - r)} ${n(bot)}L${n(cx - r)} ${n(yc)}` +
      arcPts(cx, yc, r, Math.PI, 0) + `L${n(cx + r)} ${n(bot)}Z`;
  }
  return `M${n(cx - r)} ${n(bot)}L${n(cx + r)} ${n(bot)}L${n(cx + r)} ${n(yc)}` +
    arcPts(cx, yc, r, 0, -Math.PI) + `L${n(cx - r)} ${n(bot)}Z`;
}

ICONS.CONTEXT_QUEUE_INSERT = queueBase({
  barX0: 6.82, barX1: 22.0, barH: 1.28, thickH: 2.6,
  ycs: [6.9, 10.98, 14.8, 18.61], thickIdx: 0,
  tri: { x: 2.0, yc: 6.9, w: 3.64, h: 4.31 }
});
ICONS.CONTEXT_QUEUE_APPEND = queueBase({
  barX0: 6.82, barX1: 22.0, barH: 1.28, thickH: 2.6,
  ycs: [5.39, 9.2, 13.02, 17.1], thickIdx: 3,
  tri: { x: 2.0, yc: 17.1, w: 3.64, h: 4.31 }
});

const BADGE_INSERT = {
  cx: 18.005, cy: 15.325, rOuter: 4.0, rInner: 3.1,
  head: { cx: 18.005, cy: 14.42, rx: 0.975, ry: 1.05 },
  body: { cx: 18.005, top: 15.95, bot: 17.63, w: 3.49 }
};
const BADGE_APPEND = {
  cx: 18.005, cy: 14.975, rOuter: 4.0, rInner: 3.1,
  head: { cx: 18.005, cy: 14.08, rx: 0.975, ry: 1.05 },
  body: { cx: 18.005, top: 15.6, bot: 17.28, w: 3.49 }
};

ICONS.USER_QUEUE_INSERT = queueBase({
  barX0: 5.9, barX1: 18.16, barH: 1.04, thickH: 2.1,
  ycs: [6.42, 9.71, 12.79, 15.875], thickIdx: 0,
  tri: { x: 2.0, yc: 6.42, w: 2.94, h: 3.48 }, badge: BADGE_INSERT
});
ICONS.USER_QUEUE_APPEND = queueBase({
  barX0: 5.9, barX1: 18.16, barH: 1.04, thickH: 2.1,
  ycs: [5.535, 8.62, 11.7, 15.0], thickIdx: 3,
  tri: { x: 2.0, yc: 15.0, w: 2.94, h: 3.48 }, badge: BADGE_APPEND
});

// --- 方块堆叠 + 内嵌件：底形直接复用 MIT 的 SQUARE_STACK，内嵌件按 iOS 比例缩放居中 ---
// SQUARE_STACK 前方块内腔实测 x 4.91..19.05 / y 7.59..21.73（14.14 见方，中心 11.98,14.66）
const STACK = glyph('SQUARE_STACK');
function insetGlyph(name, srcBox, targetW) {
  const s = targetW / srcBox.w;
  const scx = srcBox.x + srcBox.w / 2, scy = srcBox.y + srcBox.h / 2;
  return xform(glyph(name), s, 11.98 - scx * s, 14.66 - scy * s);
}
// iOS 内嵌件占内腔宽度：staroflife 8.06/14.10 ≈ 57%、timer 8.46/14.10 ≈ 60%
ICONS.ALBUM_NEWEST = STACK + insetGlyph('STAROFLIFE', { x: 2.46, y: 1.59, w: 19.09, h: 20.64 }, 8.06);
ICONS.ALBUM_RECENT = STACK + insetGlyph('TIMER', { x: 2.07, y: 1.96, w: 19.86, h: 19.91 }, 8.46);

// --- podcast：同心开口弧 + 中心圆点 + 下方渐窄身形 ---
{
  const cx = 12, cy = 10.75;
  const parts = [];
  // 弧自底部开口、顺时针绕行一周减去开口：a0 = π/2+g，a1 = π/2-g+2π
  // 开口半角 g 由 iOS 实测端点推得：外弧端点距圆心 7.89（r=8.75）→ g≈25.6°；
  // 内弧端点距圆心 4.01（r=5.78）→ g≈46.1°（下方让位给身形，开口更大）
  const band = (rO, rI, g) => annulus(cx, cy, rO, rI, Math.PI / 2 + g, Math.PI / 2 - g + Math.PI * 2);
  parts.push(band(8.75, 6.95, 0.4468));   // 外弧（顶 y=2.0）
  parts.push(band(5.78, 3.98, 0.8046));   // 内弧（顶 y=4.97）
  // 中心圆点 r=2.5
  parts.push(circle(cx, cy, 2.5, true));
  // 身形：上窄下宽再收圆（x 9.50..14.50，y 14.19..22.0）
  const bx = 2.5, by0 = 14.19, by1 = 22.0, topHalf = 1.35;
  parts.push(
    `M${n(cx - topHalf)} ${n(by0 + 0.5)}` +
    `Q${n(cx - topHalf)} ${n(by0)} ${n(cx)} ${n(by0)}` +
    `Q${n(cx + topHalf)} ${n(by0)} ${n(cx + topHalf)} ${n(by0 + 0.5)}` +
    `L${n(cx + bx)} ${n(by1 - 1.5)}` +
    `Q${n(cx + bx)} ${n(by1)} ${n(cx)} ${n(by1)}` +
    `Q${n(cx - bx)} ${n(by1)} ${n(cx - bx)} ${n(by1 - 1.5)}` + 'Z'
  );
  ICONS.PODCAST = parts.join('');
}

// --- audioVisualizer：circle.dashed —— 16 段等分弧，占空比 ~0.58 ---
{
  const cx = 12, cy = 12, rMid = 9.1, hw = 0.85;
  const N = 16, duty = 0.58;
  const step = (Math.PI * 2) / N, half = (step * duty) / 2;
  const parts = [];
  for (let i = 0; i < N; i++) {
    const a = -Math.PI / 2 + step * i;
    parts.push(annulus(cx, cy, rMid + hw, rMid - hw, a - half, a + half));
  }
  ICONS.CIRCLE_DASHED = parts.join('');
}

// --- clear：横置圆角方框描边 + 内嵌 X（SF "clear" 键盘清除键形）---
{
  const parts = [];
  // B6.1 真机修正：iOS 该符号是**正方形**外框 + 正中**小**叉（此前误做成横置矩形）。
  // 外框 17×17 居中（x/y 3.5），描边厚 1.6（内框 13.8 见方）；圆角比例沿用 r/边长 ≈ 0.24
  parts.push(roundRect(3.5, 3.5, 17.0, 17.0, 4.1, true));
  parts.push(roundRect(5.1, 5.1, 13.8, 13.8, 2.5, false));
  // X 半臂 2.7 → 总宽 5.4 ≈ 内框宽的 39%，居中
  const cx = 12, cy = 12, a = 2.7, hw = 0.78;
  parts.push(stroke(cx - a, cy - a, cx + a, cy + a, hw));
  parts.push(stroke(cx + a, cy - a, cx - a, cy + a, hw));
  ICONS.CLEAR_BOX = parts.join('');
}

// ---------- 输出 ----------
const wrap = (s) => {
  const CH = 100;
  const lines = [];
  for (let i = 0; i < s.length; i += CH) lines.push(s.slice(i, i + CH));
  return lines.map((l) => `            "${l}"`).join(' +\n');
};
const out = Object.entries(ICONS)
  .map(([k, v]) => `    const val ${k} =\n${wrap(v)}\n`).join('\n');
fs.writeFileSync(new URL('./custom-consts.txt', import.meta.url), out, 'utf8');
for (const [k, v] of Object.entries(ICONS)) console.log(k, v.length, 'chars');
console.log('-> custom-consts.txt');
