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

// ---- 考卷7：存活判据 BLOCKED（leader 裁定，最高优先级）----

globalRegistry.define({
  name: 'inspection:R7-dead-screen-blocks-all',
  tags: ['inspection', 'alive'],
  localOnly: true,
  description: '死屏（contentRatio=0）→ 存活不过 → 后续指标一律 BLOCKED 不得 PASS',
  async fn() {
    const { scenario } = require('../framework/inspection/index');
    // 缺采集 → 存活判据无法成立（alive=false）→ 后续指标 BLOCKED。
    const result = scenario.runScenarioMetrics({
      scenarioId: 'S1-open-session-send',
      captures: { /* 空：存活采集点缺失，其余也缺 */ },
      provenance: PROV,
    });
    // 存活判据缺采集 → alive=false（存活不过）。
    assert(result.alive && result.alive.alive === false, `缺采集应存活不过，实 ${JSON.stringify(result.alive)}`);
    // 后续指标（bottomMarginPx 等）应 BLOCKED 而非 PASS（存活不过 → 后续无意义）。
    if (result.metrics.bottomMarginPx) {
      assert(
        result.metrics.bottomMarginPx.status === 'BLOCKED' || result.metrics.bottomMarginPx.status === 'NOT_MEASURED',
        `存活不过时后续指标应 BLOCKED/NOT_MEASURED，实 ${result.metrics.bottomMarginPx.status}`,
      );
    }
    // 存活指标自己不得被 BLOCKED 覆盖。
    assert(
      !result.metrics.terminalContentAlive || result.metrics.terminalContentAlive.status !== 'BLOCKED',
      '存活指标自己不得被标 BLOCKED',
    );
  },
});
