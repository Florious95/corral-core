/*
 * 机器眼 Layer 1 · 棘轮：基线存取 + 变差检测 + 改善收紧 + 产地比较。
 *
 * leader 裁定三机制（2026-08-13）：
 * A. **改善必须收紧基线**：单向棘轮，只允许往好的方向走。指标改善时基线立刻收紧到新值，
 *    之后任何变差值都红。否则基线从缺陷态确立会锁住缺陷（D-38 1123 作基线 → 回归到 100 不红）。
 * B. **基线带产地，产地不同不可比**：buildSha/device/latencyMs/fixture 任一不同 → NOT_COMPARABLE。
 * C. **初始基线分级**：HEALTHY（已知健康值）/ KNOWN_BAD（必须改善目标，非不许变差下限）/
 *    PROVISIONAL（首轮未确认，不红别人）。
 *
 * 硬要求：NOT_MEASURED 默认不通过，沉默的缺失比红灯危险。
 */

'use strict';

const fs = require('fs');
const path = require('path');

const BASELINE_DIR = path.resolve(__dirname, '../../baselines');

/** 指标方向。 */
const DIRECTION = { LOWER_BETTER: 'lower-better', HIGHER_BETTER: 'higher-better', EQUALS: 'equals' };

/** 初始基线分级。 */
const ORIGIN = { HEALTHY: 'HEALTHY', KNOWN_BAD: 'KNOWN_BAD', PROVISIONAL: 'PROVISIONAL' };

/** 结果状态。 */
const STATUS = {
  OK: 'OK',                     // 数字正常，与基线一致或更好
  REGRESSION: 'REGRESSION',     // 比基线差（红）
  IMPROVED: 'IMPROVED',         // 比基线好，基线已收紧
  NOT_MEASURED: 'NOT_MEASURED', // 这轮没测到（默认不通过）
  NOT_COMPARABLE: 'NOT_COMPARABLE', // 产地不同，不可比
  INDETERMINATE: 'INDETERMINATE',   // 判不出
};

/** 基线文件路径。 */
function baselinePath(scenarioId) {
  return path.join(BASELINE_DIR, `${scenarioId}.json`);
}

/**
 * 读基线文件。不存在 → null。
 * @returns {null | { scenario, updatedAt, metrics: {name: {baseline, direction, tolerance, origin, provenance}} }}
 */
function readBaseline(scenarioId) {
  const p = baselinePath(scenarioId);
  if (!fs.existsSync(p)) return null;
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) {
    throw new Error(`基线文件损坏: ${p}: ${e.message}`);
  }
}

/** 写基线文件。 */
function writeBaseline(scenarioId, baseline) {
  const p = baselinePath(scenarioId);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(baseline, null, 2) + '\n');
}

/**
 * 对单指标做棘轮比较。
 *
 * @param {object} opts {
 *   name: string
 *   current: number|boolean|null        // 本轮值（null → NOT_MEASURED）
 *   measured: boolean                    // 是否真的测到（false → NOT_MEASURED）
 *   prevBaseline: {baseline, direction, tolerance, origin, provenance} | null
 *   provenance: {buildSha, device, latencyMs, fixture}   // 本轮产地
 *   healthValue: number|boolean|null     // 已知健康值（初始基线用）
 *   knownBadValue: number|boolean|null   // 已知失败值（标 KNOWN_BAD）
 * }
 * @returns { { status, current, baseline, regressed } }
 *   status: OK|REGRESSION|IMPROVED|NOT_MEASURED|NOT_COMPARABLE
 *   regressed: 是否红（棘轮）
 */
function ratchet(opts) {
  const { name, current, measured, prevBaseline, provenance } = opts;

  // 未测到 → 默认不通过（沉默缺失比红灯危险）。
  if (!measured || current === null || current === undefined) {
    return { status: STATUS.NOT_MEASURED, current, baseline: prevBaseline, regressed: false, notMeasured: true };
  }

  // 无既有基线：用已知健康值 / 已知失败值 / PROVISIONAL。
  if (!prevBaseline) {
    let baseline = null;
    let origin = ORIGIN.PROVISIONAL;
    if (opts.healthValue !== undefined && opts.healthValue !== null) {
      baseline = opts.healthValue;
      origin = ORIGIN.HEALTHY;
    } else if (opts.knownBadValue !== undefined && opts.knownBadValue !== null) {
      baseline = opts.knownBadValue;
      origin = ORIGIN.KNOWN_BAD;
    }
    if (baseline === null) {
      // 无已知值 → PROVISIONAL，用当前值作占位但不红别人（leader 裁定 C）。
      baseline = current;
      origin = ORIGIN.PROVISIONAL;
    }
    return {
      status: STATUS.OK, current, baseline, origin, regressed: false,
      message: `初始基线: ${baseline} (${origin})`,
    };
  }

  // 有基线：产地必须可比。
  const bp = prevBaseline.provenance;
  const diffProv =
    !bp || bp.buildSha !== provenance.buildSha || bp.device !== provenance.device ||
    bp.latencyMs !== provenance.latencyMs || bp.fixture !== provenance.fixture;
  if (diffProv) {
    return {
      status: STATUS.NOT_COMPARABLE, current, baseline: prevBaseline.baseline, regressed: false,
      message: `产地不同不可比: prev(${JSON.stringify(bp)}) vs cur(${JSON.stringify(provenance)})`,
    };
  }

  const dir = prevBaseline.direction;
  const tol = prevBaseline.tolerance ?? 0;
  const base = prevBaseline.baseline;
  const origin = prevBaseline.origin;

  // 比较（数值或布尔）。
  let worse = false, better = false;
  if (dir === DIRECTION.LOWER_BETTER) {
    worse = current > base + tol;
    better = current < base - tol;
  } else if (dir === DIRECTION.HIGHER_BETTER) {
    worse = current < base - tol;
    better = current > base + tol;
  } else { // EQUALS
    worse = Math.abs(current - base) > tol;
    better = false;
  }

  // 改善 → 收紧基线（机制 A：单向棘轮）。
  if (better) {
    return {
      status: STATUS.IMPROVED, current, baseline: current, origin, regressed: false,
      message: `改善收紧: ${base} → ${current}`,
    };
  }
  // 变差 → 红。
  if (worse) {
    return {
      status: STATUS.REGRESSION, current, baseline: base, origin, regressed: true,
      message: `回归: 基线 ${base} → 当前 ${current}`,
    };
  }
  // 不变 → OK。
  return { status: STATUS.OK, current, baseline: base, origin, regressed: false };
}

/**
 * 跑一个场景的全部指标棘轮，输出数字表 + 收紧后的新基线。
 *
 * @param {object} opts { scenarioId, provenance, metrics: [{name, current, measured, direction,
 *   tolerance, healthValue, knownBadValue}] }
 * @returns { { regressions: [], metrics: {name: {status, current, baseline}}, baselineUpdated } }
 */
function runRatchet(opts) {
  const prev = readBaseline(opts.scenarioId);
  const prevMetrics = prev ? prev.metrics : {};
  const newMetrics = {};
  const regressions = [];

  for (const m of opts.metrics) {
    const r = ratchet({
      name: m.name,
      current: m.current,
      measured: m.measured,
      prevBaseline: prevMetrics[m.name] || null,
      provenance: opts.provenance,
      healthValue: m.healthValue,
      knownBadValue: m.knownBadValue,
    });
    newMetrics[m.name] = {
      baseline: r.baseline?.baseline ?? r.baseline ?? null,
      baselineOrigin: r.origin ?? prevMetrics[m.name]?.origin ?? null,
      direction: m.direction ?? prevMetrics[m.name]?.direction ?? DIRECTION.LOWER_BETTER,
      tolerance: m.tolerance ?? prevMetrics[m.name]?.tolerance ?? 0,
      provenance: opts.provenance,
      status: r.status,
      current: r.current,
    };
    if (r.regressed) regressions.push({ name: m.name, message: r.message });
  }

  const updated = {
    scenario: opts.scenarioId,
    updatedAt: new Date().toISOString(),
    provenance: opts.provenance,
    metrics: newMetrics,
  };
  // 写回（改善已收紧；NOT_MEASURED 保留原基线不覆盖）。
  writeBaseline(opts.scenarioId, updated);

  return { regressions, metrics: newMetrics, baselineUpdated: updated };
}

module.exports = {
  DIRECTION, ORIGIN, STATUS,
  readBaseline, writeBaseline, ratchet, runRatchet, baselinePath,
};
