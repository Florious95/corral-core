/*
 * 机器眼 · 时间算子：算帧间差分的**分布形态**，不是差分总量。
 *
 * 输入：帧序列（PNG 目录 / mp4）。输出：非零差分帧集合 + 每帧差分区域 + 形态分类。
 *
 * 判的用户现象：
 * - IME/输入框挤压是否触发重排/闪烁（T1 硬考卷：ime frames2 254 帧 → 恰 3 帧非零差分、
 *   且纯滚动非重排）。
 * - 发消息整屏刷 vs 底部追加（T2）。
 * - 版本间差分形态（abc-regression FULL_REFLOW → BOTTOM_APPEND）。
 *
 * 形态判别（分布形态学，非差分总量）：
 * - STATIC：全部帧差分 ≈ 0。
 * - BOTTOM_APPEND：差分区域贴内容底（底接近帧底），顶不高于内容顶。
 * - SCROLL_DOWN：差分区域随帧序号单调下移（整屏上推/滚动）。
 * - FULL_REFLOW：差分覆盖全屏 + 行内容位置改变。
 * - MIXED：不属单一形态。
 *
 * 硬考卷纪律：复现不出已知答案时，先怀疑算子/阈值，最后才怀疑已知答案；若真认为
 * 已知答案错，停下报 leader，不自己改考卷。
 */

'use strict';

const { readFrameDir, INDETERMINATE, cropRegion } = require('./video');

/** 差分形态枚举。 */
const PATTERN = {
  STATIC: 'STATIC',
  BOTTOM_APPEND: 'BOTTOM_APPEND',
  SCROLL_DOWN: 'SCROLL_DOWN',
  FULL_REFLOW: 'FULL_REFLOW',
  MIXED: 'MIXED',
};

/**
 * 分析帧序列的差分分布形态。
 *
 * @param {string} framesDir  帧目录（*.png，按文件名数字排序）
 * @param {object} opts {
 *   diffThreshold?: number   // 单像素差分阈值（灰度差 > 此值才算变）。默认 12。
 *   minChangedPx?: number    // 一帧至少多少变化像素才算非零差分。默认 200。
 *   minAreaRatio?: number    // 变化像素占比下限（鲁棒：噪声帧变化面积极小，如 0.01%）。
 *                            // 默认 0.001（0.1%）。真实差分帧（IME 增高）占比 ≥5%，噪声 <0.02%。
 *   region?: {top,bottom,left,right}  // 裁剪区（如终端内容区），缺省全帧。
 *   minFrames?: number       // 最少帧数。默认 3。
 * }
 * @returns {status:'OK', ...} | INDETERMINATE
 */
function analyzeSequence(framesDir, opts = {}) {
  const r = readFrameDir(framesDir, { minFrames: opts.minFrames ?? 3 });
  if (r.status !== 'OK') return r;
  return analyzeFrames(r.frames, r.width, r.height, opts);
}

/** 从已解码帧数组分析（mp4 路径用）。frames 含 {gray,width,height}。 */
function analyzeFrames(frames, width, height, opts = {}) {
  const diffThreshold = opts.diffThreshold ?? 12;
  const minChangedPx = opts.minChangedPx ?? 200;
  const minAreaRatio = opts.minAreaRatio ?? 0.001; // 0.1%：滤掉录制噪声帧（0.01%）保留真实变化（≥5%）
  const region = opts.region;
  // 裁剪区宽高（算 x/y 坐标用）。
  const cw = region ? (region.right - region.left + 1) : width;
  const ch = region ? (region.bottom - region.top + 1) : height;

  const results = [];
  for (let i = 1; i < frames.length; i++) {
    const prev = cropRegion(frames[i - 1].gray, width, height, region);
    const cur = cropRegion(frames[i].gray, width, height, region);
    if (prev.length !== cur.length) {
      return INDETERMINATE(`帧 ${i} 与前一帧裁剪区长度不一致`);
    }
    const d = diffRegion(prev, cur, cw, ch, diffThreshold);
    // 双阈值：变化像素数 ≥ minChangedPx 且 变化面积占比 ≥ minAreaRatio（滤掉录制噪声帧）。
    if (d.changedPx >= minChangedPx && d.areaRatio >= minAreaRatio) {
      results.push({ index: i, name: frames[i].name, ...d });
    }
  }

  const nonZeroDiffFrames = results.map((r) => r.index);
  const movementPattern = classify(results);
  const reflowSignal = detectReflow(results);

  return {
    status: 'OK',
    frameCount: frames.length,
    nonZeroDiffFrames,
    diffAreas: Object.fromEntries(results.map((r) => [
      r.index,
      {
        top: r.top, bottom: r.bottom, left: r.left, right: r.right,
        topRatio: r.topRatio, bottomRatio: r.bottomRatio,
        areaRatio: r.areaRatio, changedPx: r.changedPx,
      },
    ])),
    movementPattern,
    reflowSignal,
  };
}

/** 计算两帧裁剪区的差分：changedPx、包围盒（像素 + 归一化比例 0..1）。 */
function diffRegion(a, b, cw, ch, threshold) {
  const len = a.length;
  let changedPx = 0;
  let minX = Infinity, maxX = -1, minY = Infinity, maxY = -1;
  for (let y = 0; y < ch; y++) {
    const off = y * cw;
    for (let x = 0; x < cw; x++) {
      if (Math.abs(a[off + x] - b[off + x]) > threshold) {
        changedPx++;
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  if (changedPx === 0) return { changedPx: 0 };
  return {
    changedPx,
    top: minY, bottom: maxY, left: minX, right: maxX,
    topRatio: minY / Math.max(1, ch - 1),
    bottomRatio: maxY / Math.max(1, ch - 1),
    areaRatio: changedPx / len,
  };
}

/** 从差分区域数组分类形态。
 *
 * 形态语义（按差分区域的空间分布，非差分总量）：
 * - FULL_REFLOW：差分覆盖全屏（areaRatio 大）→ 整屏重排/重绘。
 * - SCROLL_DOWN：整屏内容平移（视口上推）→ 差分区域从内容顶延伸到内容底
 *   （topRatio≈0 且 bottomRatio≈1，因整屏都动了），且 areaRatio 显著。
 * - BOTTOM_APPEND：只贴底部追加 → 差分区域 topRatio 高（>0.3）、bottomRatio≈1。
 * - MIXED：不属以上。
 */
function classify(results) {
  if (results.length === 0) return PATTERN.STATIC;

  // 全帧覆盖 → FULL_REFLOW（整屏重排）。
  if (results.some((r) => r.areaRatio > 0.5)) return PATTERN.FULL_REFLOW;

  // 整屏平移（SCROLL_DOWN）：差分从顶延伸到底（topRatio 低 + bottomRatio 高 + 覆盖显著）。
  // IME 视口上推 = 整屏内容平移一截，顶行滚出 + 底行位移 → 差分覆盖 [顶,底]。
  const fullHeightScroll = results.every((r) => r.topRatio < 0.1 && r.bottomRatio > 0.9 && r.areaRatio > 0.01);
  if (fullHeightScroll) return PATTERN.SCROLL_DOWN;

  // 只贴底部追加 → BOTTOM_APPEND（差分区域顶在内容中下部，底贴内容底）。
  if (results.every((r) => r.topRatio > 0.3 && r.bottomRatio > 0.7)) return PATTERN.BOTTOM_APPEND;

  // 多帧：差分区域顶随帧序号单调下移 → 也是滚动特征（增量下移）。
  const topRatios = results.map((r) => r.topRatio);
  const monotonicDown = topRatios.every((v, i) => i === 0 || v >= topRatios[i - 1]);
  if (monotonicDown && results.length > 1) return PATTERN.SCROLL_DOWN;

  return PATTERN.MIXED;
}

/** 重排信号：任一差分帧差分区域覆盖 >50% 高（行位置大改）。 */
function detectReflow(results) {
  return results.some((r) => r.areaRatio > 0.5);
}

module.exports = { analyzeSequence, analyzeFrames, PATTERN };
