/*
 * 机器眼 · 空间算子：从一帧算内容边界（纯数字，不看图）。
 *
 * 输入：单帧 PNG（或已解码灰度 + 尺寸）。
 * 输出：内容包围盒 {top,bottom,left,right}、末行文字底边、最右非背景像素、右/底余量、
 *       背景占比。
 *
 * 判的用户现象：
 * - 右列截断：rightmostNonBgX 接近/越过 width → rightMarginPx 小或负。
 * - 底部留白（D-38「内容只占顶部 1/4 空黑」）：bottomMarginPx 大。
 *
 * 判不出（背景阈值无法识别、找不到内容边界）→ INDETERMINATE，不猜。
 */

'use strict';

const { execFileSync } = require('child_process');
const { probeDimensions, pngToGrayBytes, INDETERMINATE } = require('./video');

/**
 * 分析单帧 PNG 的内容边界。
 *
 * 语义：**终端内容区** = 屏幕上「终端背景 + 终端文字」连成的 band，排除状态栏/键条等
 * 亮色 UI。方法是按行 band 统计灰度：找出「终端深底段」（低亮度、内容像素稀疏的连续
 * 段），内容边界只在该段内算——否则状态栏/键条会把包围盒撑满全帧。
 *
 * 诊断实证（11-final-screenshot.png）：y0-200=状态栏(亮)、y400-1000=终端内容(深底+稀疏内容)、
 * y1000-1600=纯深底(无内容)、y1600-2400=键条/输入框(亮)。
 *
 * @param {string} pngPath
 * @param {object} opts {
 *   bgThreshold?: number  // 灰度阈值（0-255），区分背景 vs 内容。默认 60。
 *   bgDark?: boolean      // 背景是深色还是浅色。默认 true（终端深底）。
 *   rowBand?: number      // 行 band 高度（灰度诊断的粒度）。默认 100。
 * }
 * @returns {status:'OK', ...} | INDETERMINATE
 */
function analyzeFrame(pngPath, opts = {}) {
  const gray = pngToGrayBytes(pngPath);
  const { width, height } = probeDimensions(pngPath);
  if (width * height !== gray.length) {
    return INDETERMINATE(`灰度长度(${gray.length}) ≠ 尺寸(${width}×${height})`);
  }
  return analyzeGray({ gray, width, height }, opts);
}

/** 判断单像素是否「内容」（非背景）。深底背景：内容像素比背景亮。 */
function isContent(v, opts) {
  const t = opts.bgThreshold ?? 60;
  return opts.bgDark === false ? v <= t : v > t; // 深底：亮即内容；浅底：暗即内容
}

/** 从灰度矩阵分析内容边界（空间算子的核心，独立于输入来源）。 */
function analyzeGray({ gray, width, height }, opts = {}) {
  const bgThreshold = opts.bgThreshold ?? 60;
  const bgDark = opts.bgDark !== false;
  const band = opts.rowBand ?? 100;

  // 第一步：按行 band 找「终端深底段」（低平均灰度、内容像素稀疏的连续段）。
  // 状态栏/键条是亮色 UI（avgGray 高），终端深底段 avgGray 低。
  const bandStats = [];
  for (let y0 = 0; y0 < height; y0 += band) {
    const y1 = Math.min(y0 + band, height);
    let sum = 0;
    for (let y = y0; y < y1; y++) {
      const off = y * width;
      for (let x = 0; x < width; x++) sum += gray[off + x];
    }
    const rows = y1 - y0;
    bandStats.push({ y0, y1, avgGray: sum / (width * rows) });
  }

  // 终端深底段 = avgGray 显著低于「亮色 UI」的段（bgDark=true 时）。取全局最低 band 为锚，
  // 扩展相邻也低的 band。**必须连续**：只有紧邻当前段的 band 才并入，遇亮 band（状态栏/键条）
  // 立即停——否则会跳过亮带直连更下方暗带（实证：11-final-screenshot 键条 band1700-2100 亮度
  // 219，其下 band2300 亮度 7 会被误并入，把 terminalBand 底错拉到 2399）。
  // 锚不取「绝对最低」：输入框/特殊区域可能比终端深底更暗（实证 band2300=7 < 终端深底 21），
  // 取全局最低会把终端深底段误判成那个更暗的小段。正确策略 = 找「最长的连续深底候选段」
  // （每 band avgGray ≤ 深底阈值），终端深底段是最大的那个连续段。
  const darkThreshold = (opts.bgThreshold ?? 60); // 深底段上界
  // 连续候选段扫描：标记每 band 是否候选，找最长连续段。
  const candidates = bandStats.map((bs) => ({ ...bs, isDark: bs.avgGray <= darkThreshold }));
  let best = null; // {startBand, endBand, length}
  let curStart = -1;
  for (let i = 0; i <= candidates.length; i++) {
    const isDark = i < candidates.length ? candidates[i].isDark : false;
    if (isDark) {
      if (curStart < 0) curStart = i;
    } else {
      if (curStart >= 0) {
        const len = i - curStart;
        if (!best || len > best.length) best = { start: curStart, end: i - 1, length: len };
        curStart = -1;
      }
    }
  }
  if (!best) return INDETERMINATE('未找到任何深底 band（背景阈值可能不对）');
  const darkTop = candidates[best.start].y0;
  const darkBottom = candidates[best.end].y1 - 1;

  // 第二步：在深底段内算内容边界（内容像素 = 非背景）。
  let minX = Infinity, maxX = -1, minY = Infinity, maxY = -1;
  let contentPx = 0;
  for (let y = darkTop; y <= darkBottom; y++) {
    const off = y * width;
    for (let x = 0; x < width; x++) {
      const v = gray[off + x];
      if (isContent(v, { bgThreshold, bgDark })) {
        contentPx++;
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }

  if (maxX < 0) {
    return INDETERMINATE('未在终端深底段中找到任何内容像素（背景阈值可能不对）');
  }

  return {
    status: 'OK',
    width,
    height,
    terminalBand: { top: darkTop, bottom: darkBottom }, // 终端深底段的 y 范围
    contentBounds: { top: minY, bottom: maxY, left: minX, right: maxX },
    lastTextBaselineY: maxY,
    rightmostNonBgX: maxX,
    rightMarginPx: width - 1 - maxX,
    bottomMarginPx: darkBottom - maxY, // 相对终端段底（内容底 vs 终端底）
    contentRatio: contentPx / ((darkBottom - darkTop + 1) * width),
  };
}

module.exports = { analyzeFrame, analyzeGray };
