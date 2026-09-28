/*
 * 机器眼共用工具：把 PNG / mp4 变成灰度字节流（用系统 ffmpeg，正确性外包给被
 * 全世界验证过的实现——不写 PNG 解码器，那是新增的 bug 面）。
 *
 * 铁律：
 * - 每个算子必须能输出 INDETERMINATE（我算不出来），而不是在不确定时给一个数。
 *   这个工程栽过太多次「给了个数但那个数没意义」。
 * - 帧太少 / 分辨率不一致 / 内容区找不到边界 → 显式 INDETERMINATE。
 */

'use strict';

const { execFileSync } = require('child_process');

/** 结果约定：正常 = {status:'OK', ...}；不确定 = {status:'INDETERMINATE', reason}。 */
const INDETERMINATE = (reason) => ({ status: 'INDETERMINATE', reason });

/** 用 ffprobe 取图像/视频宽高。失败抛错（调用方决定是否 INDETERMINATE）。 */
function probeDimensions(mediaPath) {
  const out = execFileSync('ffprobe', [
    '-v', 'error',
    '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height',
    '-of', 'csv=p=0',
    mediaPath,
  ], { encoding: 'utf8', maxBuffer: 1 << 20 }).trim();
  const m = out.match(/^(\d+),(\d+)$/);
  if (!m) throw new Error(`ffprobe 无法解析尺寸: "${out}"`);
  return { width: Number(m[1]), height: Number(m[2]) };
}

/**
 * 单帧 PNG → 灰度字节流（Uint8Array，length = width*height）。
 * 用 ffmpeg 解码，绕过 PNG 滤波器/位深/颜色表的正确性负担。
 */
function pngToGrayBytes(pngPath) {
  const buf = execFileSync('ffmpeg', [
    '-v', 'error',
    '-i', pngPath,
    '-f', 'rawvideo',
    '-pix_fmt', 'gray',
    '-',
  ], { maxBuffer: 1 << 28 }); // 灰度可能很大（1080×2400 ≈ 2.6MB），默认 1MB 会 ENOBUFS
  return new Uint8Array(buf);
}

/**
 * 读一组帧目录：按文件名数字排序的 *.png，返回每帧的灰度字节流。
 * 分辨率不一致 / 帧数不足 → INDETERMINATE（不能拿不一致数据算差分）。
 *
 * @param {string} dir          帧目录（*.png）
 * @param {object} opts { minFrames?: number, region?: {top,bottom,left,right} }
 * @returns {status:'OK', frames:[{name, gray, width, height}], width, height}
 *          | INDETERMINATE
 */
function readFrameDir(dir, opts = {}) {
  const fs = require('fs');
  const path = require('path');
  const minFrames = opts.minFrames ?? 2;

  let names;
  try {
    names = fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.png'));
  } catch (e) {
    return INDETERMINATE(`帧目录不可读: ${e.message}`);
  }
  // 按文件名里的数字排序（frame-0001 → 1），保证时间序。
  names.sort((a, b) => {
    const na = (a.match(/\d+/) || [0])[0];
    const nb = (b.match(/\d+/) || [0])[0];
    return Number(na) - Number(nb);
  });
  if (names.length < minFrames) {
    return INDETERMINATE(`帧数不足: 仅 ${names.length} 帧（需要 ≥${minFrames}）`);
  }

  const frames = [];
  let refW = null;
  let refH = null;
  for (const name of names) {
    const full = path.join(dir, name);
    const gray = pngToGrayBytes(full);
    const { width, height } = probeDimensions(full);
    if (width * height !== gray.length) {
      return INDETERMINATE(`帧 ${name} 灰度长度(${gray.length}) ≠ 尺寸(${width}×${height})`);
    }
    if (refW === null) { refW = width; refH = height; }
    if (width !== refW || height !== refH) {
      return INDETERMINATE(`帧分辨率不一致: ${name} ${width}×${height} ≠ 首帧 ${refW}×${refH}`);
    }
    frames.push({ name, gray, width, height });
  }
  return { status: 'OK', frames, width: refW, height: refH };
}

/**
 * mp4 → 抽帧灰度。ffmpeg 抽成 rawvideo gray 流，一帧接一帧连续输出。
 * 返回 [{gray, width, height}...]（分辨率为 probe 的）。
 * 注：mp4 抽帧帧数依赖视频时长/帧率；已知答案以已抽好的 frames2/ 为准时优先用目录。
 */
function mp4ToGrayFrames(mp4Path, opts = {}) {
  const { width, height } = probeDimensions(mp4Path);
  const buf = execFileSync('ffmpeg', [
    '-v', 'error',
    '-i', mp4Path,
    '-f', 'rawvideo',
    '-pix_fmt', 'gray',
    '-',
  ], { maxBuffer: 1 << 30 }); // 长视频灰度可能数百 MB
  const bytes = new Uint8Array(buf);
  const frameSize = width * height;
  if (frameSize === 0 || bytes.length % frameSize !== 0) {
    throw new Error(`mp4 灰度长度(${bytes.length}) 非帧大小(${frameSize})整数倍`);
  }
  const frames = [];
  for (let off = 0; off < bytes.length; off += frameSize) {
    frames.push({ gray: bytes.subarray(off, off + frameSize), width, height });
  }
  return frames;
}

/** 取灰度矩阵的裁剪区（region 为 {top,bottom,left,right}，含端点；缺省全帧）。 */
function cropRegion(gray, width, height, region) {
  if (!region) return gray;
  const top = region.top ?? 0;
  const left = region.left ?? 0;
  const bottom = region.bottom ?? height - 1;
  const right = region.right ?? width - 1;
  const out = new Uint8Array((bottom - top + 1) * (right - left + 1));
  let o = 0;
  for (let y = top; y <= bottom; y++) {
    const rowOff = y * width;
    for (let x = left; x <= right; x++) out[o++] = gray[rowOff + x];
  }
  return out;
}

module.exports = {
  INDETERMINATE,
  probeDimensions,
  pngToGrayBytes,
  readFrameDir,
  mp4ToGrayFrames,
  cropRegion,
};
