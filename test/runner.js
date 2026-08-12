// runner.js — 测试执行器：收集用例 → 按标签过滤 → 起隔离环境 → 逐条执行 → JSON 报告。
//
// 生命周期：
//   beforeAll：buildDaemon（若需）+ startIsolatedTmux（隔离 socket）+ startIsolatedDaemon
//   每条用例：运行 → 记 pass/fail/skip + 耗时 + error
//   afterAll：stop daemon → stop tmux → 清理 workDir → assertNoResidue
//
// 环境隔离（铁律）：
//   - 所有 spawn 用 fixtureEnv()（剔除 TEAM_AGENT_*）
//   - daemon 端口 >= 19983（nextIsolatedPort 找空闲）
//   - tmux 用独立 TMUX_TMPDIR，socket 落 discovery 会扫的目录
//   - daemon 扫描面 AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS 限定隔离目录
//
// 失败必可见：任何一步（build/start/run/stop）失败都会打印可读错误并以非零退出。

const fs = require('fs');
const path = require('path');
const { globalRegistry } = require('./framework/registry');
const { createReporter } = require('./framework/reporter');
const fixtures = require('./framework/fixtures');
const { setEnvironment } = require('./framework/context');

const TEST_DIR = __dirname;           // test/
const REPO_ROOT = path.resolve(TEST_DIR, '..');
const SERVER_DIR = path.join(REPO_ROOT, 'server');
const CASES_DIR = path.join(TEST_DIR, 'cases');
const RESULTS_DIR = path.join(TEST_DIR, 'results');

// parseArgs 解析 CLI：node runner.js [--tag=a,b] [--keep] [--name=x]
// @contract
// @post 返回 { tag, keep, name, help }
function parseArgs(argv) {
  const out = { tag: null, keep: false, name: null, help: false };
  for (const a of argv) {
    if (a === '--help' || a === '-h') out.help = true;
    else if (a.startsWith('--tag=')) out.tag = a.slice('--tag='.length);
    else if (a.startsWith('--name=')) out.name = a.slice('--name='.length);
    else if (a === '--keep') out.keep = true;      // 保留 workDir 供事后排查
    else out.help = true; // 未知参数 → 打印用法
  }
  return out;
}

function usage() {
  return `usage: node runner.js [--tag=a,b] [--name=x] [--keep]

  --tag=a,b  只跑含任一标签的用例（OR）
  --name=x   只跑名字匹配 x（子串）
  --keep     保留临时工作目录（排查用）
`;
}

// run 执行一次完整测试运行。
// @contract
// @pre 无
// @post 返回 { exitCode, reporter }
// @inv 结束时 daemon/tmux 均已收尾（除非 --keep）
async function run(argv = process.argv.slice(2)) {
  const args = parseArgs(argv);
  if (args.help) {
    process.stdout.write(usage());
    return { exitCode: 0 };
  }

  const selected = args.name
    ? globalRegistry.select(null).filter((c) => c.name.includes(args.name))
    : globalRegistry.select(args.tag);
  if (selected.length === 0) {
    process.stderr.write(`no cases selected${args.tag ? ` for tag "${args.tag}"` : ''}\n`);
    return { exitCode: 1 };
  }

  const reporter = createReporter({ resultsDir: RESULTS_DIR, filter: args.tag || args.name || 'all' });
  const log = (m) => process.stdout.write(`${m}\n`);
  const workDir = fs.mkdtempSync(path.join(require('os').tmpdir(), 'agenttest.'));

  // 纯本地用例（机器眼算子考卷）不需要 daemon/tmux 隔离环境，跳过环境起停。
  const localOnlyRun = selected.every((c) => c.localOnly);

  // --- beforeAll：构建并启动隔离环境（非 localOnly 才需要）---
  let daemon = null;
  let tmuxInfo = null;
  let setupFailed = null;
  if (!localOnlyRun) {
    try {
      fixtures.buildDaemon(SERVER_DIR, workDir, { log });
      const port = fixtures.nextIsolatedPort();
      tmuxInfo = fixtures.startIsolatedTmux(workDir, { name: 'iso', log });
      const token = `test-tok-${Date.now().toString(36)}`;
      daemon = fixtures.startIsolatedDaemon({
        serverDir: SERVER_DIR,
        workDir,
        token,
        port,
        listIntervalMs: 500,
        log,
      });
      // 写入共享环境（context.js 打破 runner↔cases 循环依赖）。
      setEnvironment({ port, token, workDir, tmuxInfo, daemon });
    } catch (err) {
      setupFailed = err;
      log(`SETUP FAILED: ${err.message}`);
    }
  }

  // --- 逐条执行 ---
  const caseCtx = {
    get daemon() { return daemon; },
    get tmuxInfo() { return tmuxInfo; },
    get workDir() { return workDir; },
    fixtures,
  };

  for (const c of selected) {
    const started = Date.now();
    let status = 'fail';
    let error = null;
    if (setupFailed) {
      status = 'error';
      error = { message: `setup failed: ${setupFailed.message}` };
    } else {
      try {
        await c.fn(caseCtx, {
          log: (m) => log(`  · ${c.name}: ${m}`),
          fixtures,
        });
        status = 'pass';
      } catch (err) {
        status = err && err.name === 'AssertionError' ? 'fail' : 'error';
        error = { name: err.name || 'Error', message: err.message || String(err) };
      }
    }
    reporter.addResult({
      name: c.name,
      tags: c.tags,
      status,
      durationMs: Date.now() - started,
      error,
    });
    const mark = status === 'pass' ? 'PASS' : status === 'skip' ? 'SKIP' : status === 'fail' ? 'FAIL' : 'ERROR';
    log(`[${mark}] ${c.name} (${Date.now() - started}ms)${error ? `\n    ${error.message}` : ''}`);
  }

  // --- afterAll：收尾（非 localOnly 才需要停环境）---
  try {
    if (daemon) await daemon.stop();
    if (tmuxInfo) fixtures.stopIsolatedTmux(tmuxInfo, { log });
    if (!localOnlyRun) fixtures.assertNoResidue({ daemon, tmuxInfo, workDir, log });
  } catch (err) {
    log(`TEARDOWN WARNING: ${err.message}`);
  }
  if (!args.keep) {
    fixtures.cleanupWorkDir(workDir, { log });
  } else {
    log(`[keep] workDir retained: ${workDir}`);
  }

  const { doc } = reporter.finish();
  log(`\nSummary: ${doc.summary.passed} passed, ${doc.summary.failed} failed, ${doc.summary.skipped} skipped, ${doc.summary.errors} errors (${doc.summary.total} total, ${doc.summary.durationMs}ms)`);
  log(`Report: ${path.join(RESULTS_DIR, 'latest.json')}`);

  const exitCode = doc.summary.failed > 0 || doc.summary.errors > 0 ? 1 : 0;
  return { exitCode, reporter };
}

// main 入口（仅当被 node 直接执行）。
if (require.main === module) {
  // 收集用例文件（依赖 globalRegistry；注册表在此前为空）。
  const files = fs.readdirSync(CASES_DIR).filter((f) => f.endsWith('.test.js')).sort();
  for (const f of files) require(path.join(CASES_DIR, f));

  run().then(({ exitCode }) => {
    process.exitCode = exitCode;
  }).catch((err) => {
    process.stderr.write(`runner fatal: ${err.stack || err.message}\n`);
    process.exitCode = 1;
  });
}

module.exports = { run, parseArgs };
