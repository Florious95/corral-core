/*
 * 机器眼 Layer 1 · 场景驱动：注册场景 + 驱动（采集→算子→数字表→棘轮）。
 *
 * 场景 = 前置状态 + 动作序列 + 采集点 + 指标集。一次巡检跑一个场景，输出数字表。
 * 动作注入（adb/模拟器）由 w-base-v2 执行（本席禁碰模拟器）；本骨架负责编排与指标计算。
 *
 * 硬要求：
 * - 未测到默认不通过（NOT_MEASURED）
 * - 产地不同不可比（NOT_COMPARABLE）
 * - 改善收紧基线（单向棘轮）
 */

'use strict';

const { runRatchet } = require('./ratchet');

/** 场景注册表。 */
const scenarios = new Map();

/**
 * 注册一个场景。
 * @param {object} sc {
 *   id, tags, actions: [{type:'adb'|'wait'|'capture'|'input'|'screenrecord', ...}],
 *   metrics: [{name, capture, fn, target, direction, tolerance, healthValue, knownBadValue}],
 *   latencyVariants: [{name, latencyMs}],
 * }
 */
function defineScenario(sc) {
  if (!sc.id) throw new Error('scenario requires id');
  if (scenarios.has(sc.id)) throw new Error(`duplicate scenario: ${sc.id}`);
  scenarios.set(sc.id, sc);
  return sc;
}

/** 取场景（id 或通配）。 */
function getScenario(id) {
  return scenarios.get(id) || null;
}

function listScenarios() {
  return [...scenarios.keys()].map((id) => ({ id, tags: scenarios.get(id).tags }));
}

/**
 * 计算一个场景的指标（从采集产物文件 → Layer 0 算子 → 数字）。
 * 由 w-base-v2 跑完动作序列后调用，传入采集产物路径映射。
 *
 * 存活判据前置（leader 裁定，2026-08-13 刚发生的事故）：
 * - `terminalContentAlive`：主机 pane 有内容时 App 终端区必须有非背景像素（contentRatio>0）。
 * - `screenResponsive`：主机追加新内容后 App 端合理时间内必须出现变化（画面不动 = 红）。
 * - **存活判据不过 → 后续指标一律 BLOCKED**，不得 PASS（屏幕都是死的，别的指标没意义）。
 *
 * @param {object} opts {
 *   scenarioId, captures: {captureName: {kind:'png'|'xml'|'mp4', path}},
 *   provenance: {buildSha, device, latencyMs, fixture},
 *   latencyMs?: number,
 * }
 * @returns { { metrics: {name: {status,current,...}}, regressions, alive, notMeasured } }
 */
function runScenarioMetrics(opts) {
  const sc = scenarios.get(opts.scenarioId);
  if (!sc) throw new Error(`unknown scenario: ${opts.scenarioId}`);

  const metricsInput = [];
  const notMeasured = [];

  for (const m of sc.metrics) {
    // 采集点产物是否齐。
    const caps = Array.isArray(m.capture) ? m.capture : [m.capture];
    const missing = caps.filter((c) => !opts.captures || !opts.captures[c]);
    if (missing.length > 0) {
      metricsInput.push({
        name: m.name, current: null, measured: false,
        direction: m.direction, tolerance: m.tolerance,
        healthValue: m.healthValue, knownBadValue: m.knownBadValue,
        reason: `缺采集点: ${missing.join(',')}`,
      });
      notMeasured.push({ name: m.name, reason: `缺采集点: ${missing.join(',')}` });
      continue;
    }
    // 用算子从采集产物算数字。
    try {
      const value = computeMetric(m, opts.captures);
      metricsInput.push({
        name: m.name, current: value, measured: value !== null,
        direction: m.direction, tolerance: m.tolerance,
        healthValue: m.healthValue, knownBadValue: m.knownBadValue,
      });
    } catch (e) {
      metricsInput.push({
        name: m.name, current: null, measured: false,
        direction: m.direction, tolerance: m.tolerance,
        healthValue: m.healthValue, knownBadValue: m.knownBadValue,
        reason: `算子失败: ${e.message}`,
      });
      notMeasured.push({ name: m.name, reason: `算子失败: ${e.message}` });
    }
  }

  // ---- 存活判据前置（leader 裁定，最高优先级）----
  const alive = computeAlive(opts, sc);
  if (!alive.alive) {
    // 存活判据不过 → 后续指标一律 BLOCKED（屏幕死了，别的指标没意义）。
    for (const m of sc.metrics) {
      if (m.name === 'terminalContentAlive' || m.name === 'screenResponsive') continue; // 存活指标自己保留状态
      // 在 metricsInput 里把对应指标标 BLOCKED。
      const target = metricsInput.find((x) => x.name === m.name);
      if (target) {
        target.blocked = true;
        target.reason = alive.reason;
      }
    }
  }

  const result = runRatchet({
    scenarioId: opts.scenarioId,
    provenance: opts.provenance,
    metrics: metricsInput,
  });
  // 把 BLOCKED 落到输出（存活不过 → 非存活指标标 BLOCKED）。
  for (const m of sc.metrics) {
    if (m.name !== 'terminalContentAlive' && m.name !== 'screenResponsive' && !alive.alive) {
      if (result.metrics[m.name]) {
        result.metrics[m.name].status = 'BLOCKED';
      }
    }
  }
  result.notMeasured = notMeasured;
  result.alive = alive;
  result.latencyMs = opts.latencyMs ?? 0;
  return result;
}

/**
 * 存活判据：终端内容还活着吗。
 * - terminalContentAlive：open-stable 帧 contentRatio > 下限（默认 0.001）。
 * - screenResponsive：主机追加新内容后 App 端帧差分非零（画面有变化）。
 * 任一不过 → alive=false + reason。
 */
function computeAlive(opts, sc) {
  const captures = opts.captures || {};
  const aliveMetric = sc.metrics.find((m) => m.name === 'terminalContentAlive');
  const respMetric = sc.metrics.find((m) => m.name === 'screenResponsive');

  // terminalContentAlive：contentRatio > 下限。
  if (aliveMetric) {
    const cap = captures[Array.isArray(aliveMetric.capture) ? aliveMetric.capture[0] : aliveMetric.capture];
    if (!cap) {
      return { alive: false, reason: `terminalContentAlive: 缺采集点 ${aliveMetric.capture}` };
    }
    try {
      const { analyzeFrame } = require('../machine_eye/space');
      const r = analyzeFrame(cap.path, cap.opts);
      if (r.status !== 'OK') return { alive: false, reason: `terminalContentAlive: ${r.reason}` };
      const minRatio = aliveMetric.minRatio ?? 0.001;
      if (r.contentRatio <= minRatio) {
        return { alive: false, reason: `terminalContentAlive: contentRatio=${r.contentRatio} ≤ ${minRatio}（屏幕无内容）` };
      }
    } catch (e) {
      return { alive: false, reason: `terminalContentAlive: ${e.message}` };
    }
  }

  // screenResponsive：追加后帧差分非零。
  if (respMetric) {
    const cap = captures[Array.isArray(respMetric.capture) ? respMetric.capture[0] : respMetric.capture];
    if (!cap) {
      return { alive: false, reason: `screenResponsive: 缺采集点 ${respMetric.capture}` };
    }
    try {
      const { analyzeSequence } = require('../machine_eye/time');
      const r = analyzeSequence(cap.path, cap.opts);
      if (r.status !== 'OK') return { alive: false, reason: `screenResponsive: ${r.reason}` };
      if (r.movementPattern === 'STATIC' && r.nonZeroDiffFrames.length === 0) {
        return { alive: false, reason: 'screenResponsive: 主机追加后 App 画面不动（STATIC）——疑似冻结' };
      }
    } catch (e) {
      return { alive: false, reason: `screenResponsive: ${e.message}` };
    }
  }

  return { alive: true };
}

/** 单指标计算：把采集产物喂给对应 Layer 0 算子。 */
function computeMetric(m, captures) {
  const caps = Array.isArray(m.capture) ? m.capture : [m.capture];
  const firstCap = caps[0];
  const cap = captures[firstCap];

  if (m.fn === 'space.analyzeFrame') {
    const { analyzeFrame } = require('../machine_eye/space');
    const r = analyzeFrame(cap.path, cap.opts);
    if (r.status !== 'OK') throw new Error(`space: ${r.reason}`);
    return r[m.target];
  }
  if (m.fn === 'symbol.analyzeDump') {
    const { analyzeDump } = require('../machine_eye/symbol');
    const r = analyzeDump(cap.path);
    if (r.status !== 'OK') throw new Error(`symbol: ${r.reason}`);
    // target 支持点路径如 boundsByRole.inputField.top
    const v = m.target.split('.').reduce((o, k) => (o == null ? o : o[k]), r);
    return v;
  }
  if (m.fn === 'time.analyzeSequence') {
    const { analyzeSequence } = require('../machine_eye/time');
    const r = analyzeSequence(cap.path, cap.opts);
    if (r.status !== 'OK') throw new Error(`time: ${r.reason}`);
    return r[m.target];
  }
  throw new Error(`unknown metric fn: ${m.fn}`);
}

module.exports = { defineScenario, getScenario, listScenarios, runScenarioMetrics };
