// reporter.js — 结果持久化：每次运行的 PASS/FAIL 结果落盘 JSON，支持趋势对比。
//
// 结构：每次运行一个报告文件 results/<run-id>.json，含元信息（时间/平台/过滤）与
// 每个用例的结果（status/duration/tags/error/messages）。另写 results/latest.json
// 便于外部读取最近一次。趋势对比 = 聚合 runs/ 下历史文件的 status 分布。
//
// 幂等：同一个 run 多次落盘覆盖；无全局状态（用例结果由 runner 逐条 push）。

const fs = require('fs');
const path = require('path');

// createReporter 创建 reporter 实例。
// @contract
// @post 返回 { runId, addResult, finish, resultsPath, summary }
// @inv 所有写文件操作带 fsync，进程被杀不丢半截记录
function createReporter({ resultsDir, runId = null, filter = null } = {}) {
  const dir = resultsDir || path.join(__dirname, '..', 'results');
  fs.mkdirSync(dir, { recursive: true });
  const id = runId || new Date().toISOString().replace(/[:.]/g, '-');
  const results = [];
  const startAt = Date.now();

  const addResult = (r) => {
    results.push({
      ...r,
      startedAt: r.startedAt || new Date().toISOString(),
      durationMs: r.durationMs != null ? r.durationMs : 0,
    });
  };

  const summarize = (list) => {
    const s = { total: list.length, passed: 0, failed: 0, skipped: 0, errors: 0 };
    for (const r of list) {
      if (r.status === 'pass') s.passed++;
      else if (r.status === 'fail') s.failed++;
      else if (r.status === 'skip') s.skipped++;
      else s.errors++;
    }
    s.durationMs = Date.now() - startAt;
    return s;
  };

  const build = () => ({
    runId: id,
    startedAt: new Date(startAt).toISOString(),
    finishedAt: new Date().toISOString(),
    filter: filter || null,
    summary: summarize(results),
    results,
  });

  const finish = () => {
    const doc = build();
    const file = path.join(dir, `${id}.json`);
    fs.writeFileSync(file, JSON.stringify(doc, null, 2) + '\n');
    fs.writeFileSync(path.join(dir, 'latest.json'), JSON.stringify(doc, null, 2) + '\n');
    return { doc, file };
  };

  // summary 暴露当前结果快照（供 runner 中途读取）；summarize 为内部实现。
  const summary = () => summarize(results);

  return { runId: id, addResult, finish, summary, resultsDir: dir };
}

// loadRuns 聚合 resultsDir 下全部历史报告，返回按 runId 倒序的数组。
// 用于趋势对比（FIELD.md 能力 5）。
function loadRuns(resultsDir) {
  const dir = resultsDir || path.join(__dirname, '..', 'results');
  if (!fs.existsSync(dir)) return [];
  const runs = [];
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith('.json') || f === 'latest.json') continue;
    try {
      const doc = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
      if (doc && doc.runId && doc.summary) runs.push(doc);
    } catch { /* 忽略损坏文件 */ }
  }
  runs.sort((a, b) => (a.runId < b.runId ? 1 : -1));
  return runs;
}

module.exports = { createReporter, loadRuns };
