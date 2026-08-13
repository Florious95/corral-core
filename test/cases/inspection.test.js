// inspection.test.js — 机器眼 Layer 1 巡检考卷（棘轮机制 + 存活判据的已知答案验收）。
//
// 覆盖（纯 JVM 可测，不依赖模拟器/daemon）：
// 1. 棘轮能抓回归
// 2. 未测到默认不通过（NOT_MEASURED）
// 3. 改善收紧基线（leader 裁定：单向棘轮）
// 4. 产地不同不可比（NOT_COMPARABLE）
// 5. 存活判据 BLOCKED（leader 裁定：存活不过后续指标一律 BLOCKED）
// 6. 初始基线分级（KNOWN_BAD 不作基线）

'use strict';

const { globalRegistry } = require('../framework/registry');
const R = require('../framework/inspection/ratchet');
const { DIRECTION, STATUS, ORIGIN } = R;

const PROV = { buildSha: 'test-sha', device: 'agentmirror_test_1260x2800', latencyMs: 0, fixture: '20-line-cjk' };

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

// ---- 考卷1：棘轮能抓回归 ----

globalRegistry.define({
  name: 'inspection:R1-ratchet-catches-regression',
  tags: ['inspection', 'ratchet'],
  localOnly: true,
  description: '喂一个变差值（rightEdgeGapPx 143→30），棘轮必须红',
  async fn() {
    // 先建基线（健康值 143）。
    let r = R.ratchet({
      name: 'rightEdgeGapPx', current: 143, measured: true, prevBaseline: null,
      provenance: PROV, healthValue: 143, direction: DIRECTION.HIGHER_BETTER,
    });
    assert(r.status === STATUS.OK, `初始应 OK，实 ${r.status}`);
    // 变差到 30（higher-better，越小越差）。
    r = R.ratchet({
      name: 'rightEdgeGapPx', current: 30, measured: true,
      prevBaseline: { baseline: 143, direction: DIRECTION.HIGHER_BETTER, tolerance: 0, provenance: PROV },
      provenance: PROV,
    });
    assert(r.status === STATUS.REGRESSION && r.regressed, `变差应 REGRESSION，实 ${r.status}`);
  },
});

// ---- 考卷2：未测到默认不通过 ----

globalRegistry.define({
  name: 'inspection:R2-not-measured-default-fail',
  tags: ['inspection', 'ratchet'],
  localOnly: true,
  description: '缺采集文件 → NOT_MEASURED 且非 OK（沉默缺失比红灯危险）',
  async fn() {
    const r = R.ratchet({
      name: 'diffPattern', current: null, measured: false, prevBaseline: null, provenance: PROV,
    });
    assert(r.status === STATUS.NOT_MEASURED, `未测到应 NOT_MEASURED，实 ${r.status}`);
    assert(r.status !== STATUS.OK, '未测到不得算 OK');
  },
});

// ---- 考卷5：改善收紧基线（leader 裁定）----

globalRegistry.define({
  name: 'inspection:R5-improvement-tightens-baseline',
  tags: ['inspection', 'ratchet'],
  localOnly: true,
  description: '改善（1123→6）基线收紧到6；中间值100必须红（单向棘轮）',
  async fn() {
    // 初始 KNOWN_BAD 1123。
    let r = R.ratchet({
      name: 'bottomMarginPx', current: 1123, measured: true, prevBaseline: null,
      provenance: PROV, knownBadValue: 1123,
    });
    assert(r.status === STATUS.OK && r.origin === ORIGIN.KNOWN_BAD, `初始应 OK+KNOWN_BAD，实 ${r.status}/${r.origin}`);
    // 改善到 6 → IMPROVED + 收紧。
    r = R.ratchet({
      name: 'bottomMarginPx', current: 6, measured: true,
      prevBaseline: { baseline: 1123, direction: DIRECTION.LOWER_BETTER, tolerance: 0, provenance: PROV },
      provenance: PROV,
    });
    assert(r.status === STATUS.IMPROVED && r.baseline === 6, `改善应 IMPROVED+收紧到6，实 ${r.status}/${r.baseline}`);
    // 中间值 100（>6 应红）——缺陷不得被合法化。
    r = R.ratchet({
      name: 'bottomMarginPx', current: 100, measured: true,
      prevBaseline: { baseline: 6, direction: DIRECTION.LOWER_BETTER, tolerance: 0, provenance: PROV },
      provenance: PROV,
    });
    assert(r.status === STATUS.REGRESSION, `100 应 REGRESSION（>收紧后基线6），实 ${r.status}`);
  },
});

// ---- 考卷6：产地不可比 ----

globalRegistry.define({
  name: 'inspection:R6-provenance-not-comparable',
  tags: ['inspection', 'ratchet'],
  localOnly: true,
  description: '同指标不同 device → NOT_COMPARABLE 不硬比',
  async fn() {
    const r = R.ratchet({
      name: 'bottomMarginPx', current: 100, measured: true,
      prevBaseline: {
        baseline: 6, direction: DIRECTION.LOWER_BETTER, tolerance: 0,
        provenance: { ...PROV, device: '1080x2400' },
      },
      provenance: PROV,
    });
    assert(r.status === STATUS.NOT_COMPARABLE, `不同设备应 NOT_COMPARABLE，实 ${r.status}`);
  },
});

// ---- 考卷8：四象限方差判据（leader 裁定 + w-dev-repaint 事故）----

globalRegistry.define({
  name: 'inspection:R8-alive-variance-four-quadrants',
  tags: ['inspection', 'alive'],
  localOnly: true,
  description: '四象限方差：深活/深死/浅活/浅死，方差分离、主题无关',
  async fn() {
    const path = require('path');
    const ART = path.resolve(__dirname, '../../e2e/artifacts');
    const { pngToGrayBytes, probeDimensions } = require('../framework/machine_eye/video');

    function variance(p) {
      const gray = pngToGrayBytes(p);
      const { width, height } = probeDimensions(p);
      let sum = 0, sumsq = 0;
      for (let i = 0; i < gray.length; i++) { sum += gray[i]; sumsq += gray[i] * gray[i]; }
      const n = gray.length, mean = sum / n;
      return sumsq / n - mean * mean;
    }

    // 四象限真实语料。
    const darkAlive = variance(path.join(ART, 'd38-viewport-restore/25-app-baseline-realcc.png'));
    const darkDead = variance(path.join(ART, 'd38-verify/02-baseline.png'));
    const lightAlive = variance(path.join(ART, 'ui-review/ime-normal-light.png'));
    const lightDead = variance(path.join(ART, 'ui-review/d35-empty-light.png'));

    console.log(`[R8] 深活=${darkAlive.toFixed(0)} 深死=${darkDead.toFixed(0)} 浅活=${lightAlive.toFixed(0)} 浅死=${lightDead.toFixed(0)}`);

    // 主判据：方差阈值 1000。
    assert(darkAlive > 1000 && lightAlive > 1000, `活屏方差应 >1000，实 深活${darkAlive.toFixed(0)}/浅活${lightAlive.toFixed(0)}`);
    assert(darkDead < 1000 && lightDead < 1000, `死屏方差应 <1000，实 深死${darkDead.toFixed(0)}/浅死${lightDead.toFixed(0)}`);

    // 分离度：活/死至少 5 倍（实测 17-38 倍）。
    assert(Math.min(darkAlive, lightAlive) / Math.max(darkDead, lightDead) > 5,
      `活/死方差分离度应 >5，实 ${Math.min(darkAlive, lightAlive).toFixed(0)}/${Math.max(darkDead, lightDead).toFixed(0)}`);

    // 完整 computeAlive 路径：深死帧 + 主机有内容 → 判死（对账）。
    const { scenario } = require('../framework/inspection/index');
    const result = scenario.runScenarioMetrics({
      scenarioId: 'S1-open-session-send',
      captures: { 'open-stable': { kind: 'png', path: path.join(ART, 'd38-verify/02-baseline.png') } },
      provenance: { buildSha: 'test', device: 'test', latencyMs: 0, fixture: 'test' },
      hostContent: { nonEmpty: true, lineCount: 87 }, // 主机有内容 → 屏幕空 = 死
    });
    assert(result.alive && result.alive.alive === false, `深死帧+主机有内容应判死，实 ${JSON.stringify(result.alive)}`);
  },
});

// ---- 考卷7：存活判据 BLOCKED + 主机对账（leader 裁定，最高优先级）----

globalRegistry.define({
  name: 'inspection:R7-hostreconcile-blocks-or-indeterminate',
  tags: ['inspection', 'alive'],
  localOnly: true,
  description: '主机有内容+死屏→判死+后续BLOCKED；无主机状态→INDETERMINATE；主机空+App空→正常',
  async fn() {
    const path = require('path');
    const ART = path.resolve(__dirname, '../../e2e/artifacts');
    const { scenario } = require('../framework/inspection/index');

    // ① 主机有内容 + 屏幕死屏（P0 事故帧）→ 判死 + 后续 BLOCKED。
    const r1 = scenario.runScenarioMetrics({
      scenarioId: 'S1-open-session-send',
      captures: { 'open-stable': { kind: 'png', path: path.join(ART, 'd38-verify/02-baseline.png') } },
      provenance: PROV,
      hostContent: { nonEmpty: true, lineCount: 87 }, // 主机 pane 有内容（w-base-v2 对账事实）
    });
    assert(r1.alive && r1.alive.alive === false, `主机有内容+死屏应判死，实 ${JSON.stringify(r1.alive)}`);
    if (r1.metrics.bottomMarginPx) {
      assert(
        r1.metrics.bottomMarginPx.status === 'BLOCKED' || r1.metrics.bottomMarginPx.status === 'NOT_MEASURED',
        `存活不过时后续指标应 BLOCKED/NOT_MEASURED，实 ${r1.metrics.bottomMarginPx.status}`,
      );
    }

    // ② 无主机状态（只有历史截图）→ INDETERMINATE（不得判活也不得判死）。
    const r2 = scenario.runScenarioMetrics({
      scenarioId: 'S1-open-session-send',
      captures: { 'open-stable': { kind: 'png', path: path.join(ART, 'd38-verify/02-baseline.png') } },
      provenance: PROV,
      // 不传 hostContent
    });
    assert(
      r2.alive && r2.alive.indeterminate === true,
      `缺主机状态应 INDETERMINATE，实 ${JSON.stringify(r2.alive)}`,
    );

    // ③ 主机空 + App 空 → 正常（非死屏）。
    const r3 = scenario.runScenarioMetrics({
      scenarioId: 'S1-open-session-send',
      captures: { 'open-stable': { kind: 'png', path: path.join(ART, 'ime-no-resize/frames2/frame-0001.png') } },
      provenance: PROV,
      hostContent: { nonEmpty: false, lineCount: 0 },
    });
    assert(r3.alive && r3.alive.alive === true, `主机空+App空应正常，实 ${JSON.stringify(r3.alive)}`);
  },
});
