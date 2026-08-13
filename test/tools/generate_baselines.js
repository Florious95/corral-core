/*
 * 机器眼 Layer 1 · 基线集生成：用 e2e/artifacts/ 既有真实语料跑算子，产出带产地的基线。
 *
 * 分级（leader 裁定）：
 * - HEALTHY：已知正常态（如 25-app-baseline-realcc 的 bottomMarginPx≈6）
 * - KNOWN_BAD：已知失败态（用户 1123 / P0 空白 / D-38 复现 106）——必须被改善的目标
 * - PROVISIONAL：算得出但不知道对错
 * - UNKNOWN_PROVENANCE：产地不明，**不得作基线**，只当考卷语料
 *
 * 指标：contentRatio / bottomMarginPx / rightMarginPx / variance（主题无关存活判据）
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { analyzeFrame } = require('../framework/machine_eye/space');
const { pngToGrayBytes, probeDimensions } = require('../framework/machine_eye/video');

const ART = path.resolve(__dirname, '../../e2e/artifacts');
const OUT = path.resolve(__dirname, '../baselines/corpus-metrics.json');

/** 灰度方差（主题无关存活判据）。 */
function varianceOf(p) {
  const gray = pngToGrayBytes(p);
  const { width, height } = probeDimensions(p);
  let sum = 0, sumsq = 0;
  for (let i = 0; i < gray.length; i++) { sum += gray[i]; sumsq += gray[i] * gray[i]; }
  const n = gray.length, mean = sum / n;
  return { variance: Math.round(sumsq / n - mean * mean), avgGray: Math.round(mean) };
}

/** 一组的语料定义：{ id, provenance, origin, files: [{name, path, known?}] } */
const GROUPS = [
  {
    id: 'd38-baseline-healthy',
    provenance: { device: 'agentmirror_geo_1260x2800', latencyMs: 0, fixture: 'realcc-baseline' },
    origin: 'HEALTHY',
    buildSha: 'known-healthy',
    files: [
      { name: '25-app-baseline-realcc', path: 'd38-viewport-restore/25-app-baseline-realcc.png' },
    ],
  },
  {
    id: 'd38-verify-healthy',
    provenance: { device: 'avd-1080x2400', latencyMs: 0, fixture: 'd38-verify' },
    origin: 'HEALTHY',
    buildSha: 'd38-fixed',
    files: [],
  },
  {
    id: 'ime-normal-healthy',
    provenance: { device: 'avd-1080x2400', latencyMs: 0, fixture: 'ime' },
    origin: 'HEALTHY',
    buildSha: 'ime-fixed',
    files: [
      { name: 'ime-normal-dark', path: 'ui-review/ime-normal-dark.png' },
      { name: 'ime-normal-light', path: 'ui-review/ime-normal-light.png' },
      { name: 'd35-normal-dark', path: 'ui-review/d35-normal-dark.png' },
      { name: 'd35-normal-light', path: 'ui-review/d35-normal-light.png' },
    ],
  },
  {
    id: 'p0-blank-dead',
    provenance: { device: 'avd-1080x2400', latencyMs: 0, fixture: 'p0-accident' },
    origin: 'KNOWN_BAD',
    buildSha: 'p0-accident',
    files: [
      { name: '02-baseline', path: 'd38-verify/02-baseline.png' },
      { name: '02b-retry', path: 'd38-verify/02b-baseline-retry.png' },
      { name: '03-clean-head', path: 'd38-verify/03-clean-head-check.png' },
      { name: '06-render-check', path: 'd38-verify/06-render-check-after-revert.png' },
    ],
  },
  {
    id: 'user-d38-fail',
    provenance: { device: 'agentmirror_geo_1260x2800', latencyMs: 'tailscale', fixture: 'user-upload' },
    origin: 'KNOWN_BAD',
    buildSha: 'user-upload',
    files: [
      { name: '151812-2637', path: '/Users/alauda/Downloads/agentmirror-uploads/upload-20260812T151812-1000022637.jpg' },
    ],
  },
  {
    id: 'ime-4line-pinched',
    provenance: { device: 'avd-1080x2400', latencyMs: 0, fixture: 'ime-4line' },
    origin: 'KNOWN_BAD',
    buildSha: 'ime-4line',
    files: [
      { name: '11-final-screenshot', path: 'ime-no-resize/11-final-screenshot.png' },
    ],
  },
];

function analyzeOne(relPath) {
  const full = path.isAbsolute(relPath) ? relPath : path.resolve(ART, relPath);
  if (!fs.existsSync(full)) return { status: 'MISSING' };
  const sp = analyzeFrame(full);
  const v = varianceOf(full);
  const base = {
    variance: v.variance,
    avgGray: v.avgGray,
  };
  if (sp.status === 'OK') {
    base.contentRatio = Number(sp.contentRatio.toFixed(4));
    base.bottomMarginPx = sp.bottomMarginPx;
    base.rightMarginPx = sp.rightMarginPx;
    base.terminalBand = `${sp.terminalBand.top}-${sp.terminalBand.bottom}`;
    base.contentBounds = sp.contentBounds;
  } else {
    base.status = sp.status;
    base.reason = sp.reason;
  }
  return base;
}

const out = { generatedAt: new Date().toISOString(), groups: {} };
for (const g of GROUPS) {
  out.groups[g.id] = {
    provenance: { ...g.provenance, buildSha: g.buildSha },
    origin: g.origin,
    metrics: {},
  };
  for (const f of g.files) {
    out.groups[g.id].metrics[f.name] = analyzeOne(f.path);
  }
}

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n');
console.log('基线集已写:', OUT);
// 汇总对照表
for (const [id, g] of Object.entries(out.groups)) {
  console.log(`\n[${g.origin}] ${id} (${g.provenance.device})`);
  for (const [name, m] of Object.entries(g.metrics)) {
    if (m.status === 'MISSING') { console.log(`  ${name}: MISSING`); continue; }
    const ratio = m.contentRatio !== undefined ? `ratio=${m.contentRatio}` : `INDET(${m.reason})`;
    const bot = m.bottomMarginPx !== undefined ? `bottom=${m.bottomMarginPx}` : '';
    const right = m.rightMarginPx !== undefined ? `right=${m.rightMarginPx}` : '';
    console.log(`  ${name}: var=${m.variance} avg=${m.avgGray} ${ratio} ${bot} ${right}`);
  }
}
