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
 * @param {object} opts {
 *   scenarioId, captures: {captureName: {kind:'png'|'xml'|'mp4', path}},
 *   provenance: {buildSha, device, latencyMs, fixture},
 *   latencyMs?: number,
 * }
 * @returns { { metrics: {name: {status,current,...}}, regressions } }
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

  const result = runRatchet({
    scenarioId: opts.scenarioId,
    provenance: opts.provenance,
    metrics: metricsInput,
  });
  result.notMeasured = notMeasured;
  result.latencyMs = opts.latencyMs ?? 0;
  return result;
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
