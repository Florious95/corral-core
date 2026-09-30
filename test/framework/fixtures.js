// fixtures.js — 环境管理：隔离 daemon 起停 + 隔离 tmux 起停。
//
// 隔离铁律（FIELD.md / TESTPLAN §1）：
//   - 测试一律 env -u TEAM_AGENT_*（工作区见 fixtureEnv()，凡 spawn 必前缀）
//   - 隔离 daemon 用高端口（>=19983），绝不触碰生产 daemon
//   - 隔离 tmux 用独立 TMUX_TMPDIR，socket 落在 $TMUX_TMPDIR/tmux-<uid>/
//     ——这正是 discovery.DefaultSocketDirs 会扫的位置（scan.go）
//   - daemon 的 tmux 扫描面用 AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS 限定到
//     隔离目录（api/discoverer.go 的 e2e 专用隔离桥），绝不扫生产 /tmp/tmux-<uid>
//   - 杀进程只 scoped kill 自建二进制名与自建 tmux socket，禁 pkill 扫射
//
// 收尾自证零监听零孤儿：stop() 显式 kill daemon（SIGTERM→等待→SIGKILL 兜底）、
// tmux kill-server、删临时目录。runner 的 afterAll 强制调用。

const { spawn, spawnSync, execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

// 隔离 daemon 端口下限（协议/纪律：>=19983）。
const MIN_ISOLATED_PORT = 19983;

// 隔离 daemon 二进制路径（go build 产物）。
function daemonBinaryPath(workDir) {
  return path.join(workDir, 'daemon', 'agentmirrord');
}

// fixtureEnv 返回净化后的环境对象：剔除 TEAM_AGENT_* 与 TMUX，保留其余。
// TMUX 是外层 tmux 客户端变量（"<socket>,<pid>,<index>"）；daemon/tmux 子进程
// 若继承它会撞嵌套会话守卫或误指外层 socket。daemon 自身对 tmux 子进程已剥
// TMUX（scan.go envWithout），这里在源头再剥一层（纵深防御）。
// @contract
// @post 返回的环境对象不含任何 TEAM_AGENT_ 键，也不含 TMUX 键
// @inv 不修改 process.env（返回副本）
function fixtureEnv() {
  const env = { ...process.env };
  for (const k of Object.keys(env)) {
    if (k.startsWith('TEAM_AGENT_') || k === 'TMUX') delete env[k];
  }
  return env;
}

// nextIsolatedPort 在 >= MIN_ISOLATED_PORT 中找一个未监听端口。
// 从 19983 起尝试，遇占用则 +1；避免测试间并行撞端口。
function nextIsolatedPort() {
  const { execSync } = require('child_process');
  let port = MIN_ISOLATED_PORT;
  for (let i = 0; i < 64; i++, port++) {
    try {
      execSync(`lsof -nP -iTCP:${port} -sTCP:LISTEN`, { stdio: 'ignore' });
      // 有监听 → 占用，试下一个
    } catch {
      return port; // lsof 无输出（exit != 0）→ 空闲
    }
  }
  throw new Error(`no free isolated port found above ${MIN_ISOLATED_PORT}`);
}

// buildDaemon 用 go build 构建隔离 daemon 到 workDir/daemon/agentmirrord。
// serverDir 为仓库 server/ 目录（go.mod 所在）。
// @contract
// @pre serverDir 存在且含 go.mod
// @post 产物在 daemonBinaryPath(workDir)；可执行
// @err go build 失败抛 Error（含输出）
function buildDaemon(serverDir, workDir, { log = console.log } = {}) {
  const bin = daemonBinaryPath(workDir);
  fs.mkdirSync(path.dirname(bin), { recursive: true });
  log(`[fixture] go build agentmirrord -> ${bin}`);
  try {
    execFileSync('go', ['build', '-o', bin, './cmd/agentmirrord'], {
      cwd: serverDir,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: fixtureEnv(),
      timeout: 180000,
    });
  } catch (err) {
    const detail = (err.stdout || '') + (err.stderr || '');
    throw new Error(`go build agentmirrord failed:\n${detail}`);
  }
  if (!fs.existsSync(bin)) throw new Error(`go build succeeded but binary missing: ${bin}`);
  return bin;
}

// --- 隔离 tmux ------------------------------------------------------------

// startIsolatedTmux 在 workDir/tmux 下启动隔离 tmux server。
// 返回 tmux socket 路径（$TMUX_TMPDIR/tmux-<uid>/<name>）与操作命令封装。
// @contract
// @pre workDir 已存在
// @post 隔离 tmux server 在 $TMUX_TMPDIR 下运行；socket 落 discovery 会扫的目录
// @err tmux 启动失败抛 Error
function startIsolatedTmux(workDir, { name = 'iso', log = console.log } = {}) {
  const tmuxDir = path.join(workDir, 'tmux');
  fs.mkdirSync(tmuxDir, { recursive: true });
  const uid = os.userInfo().uid;
  const socketDir = path.join(tmuxDir, `tmux-${uid}`);
  // tmux 要求 socket 目录权限 0700（owner-only），否则报 "unsafe permissions"。
  // 实测 0755 会被拒；由 tmux 自建时它用 0700。这里预建必须同权限。
  fs.mkdirSync(socketDir, { recursive: true, mode: 0o700 });
  fs.chmodSync(socketDir, 0o700);
  const socketPath = path.join(socketDir, name);
  const env = { ...fixtureEnv(), TMUX: '', TMUX_TMPDIR: tmuxDir };
  const tmux = (args) => runTmux(env, socketPath, args);
  return { socketPath, tmux, env, tmuxDir, name };
}

// runTmux 执行一条 tmux 命令，返回 stdout（失败抛错）。
function runTmux(env, socketPath, args) {
  const res = spawnSync('tmux', ['-f', '/dev/null', '-L', path.basename(socketPath), ...args], {
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (res.status !== 0) {
    throw new Error(`tmux ${args.join(' ')} failed (exit ${res.status}): ${(res.stderr || '').toString().trim()}`);
  }
  return res.stdout.toString();
}

// stopIsolatedTmux 关停隔离 tmux server（幂等）。
function stopIsolatedTmux(tmuxInfo, { log = console.log } = {}) {
  if (!tmuxInfo) return;
  try {
    runTmux(tmuxInfo.env, tmuxInfo.socketPath, ['kill-server']);
  } catch (err) {
    log(`[fixture] tmux stop (already dead?): ${err.message}`);
  }
}

// --- 隔离 daemon ----------------------------------------------------------

// startIsolatedDaemon 在 workDir 下启动隔离 daemon。
// opts: { serverDir, token, port, host, listIntervalMs, uploadDir }
// 返回 { pid, port, token, logPath, workDir } 与 stop()。
// @contract
// @pre 已 buildDaemon
// @post daemon 监听 port 并完成单实例守卫；stop() 可幂等收尾
// @err 启动失败/端口占用抛 Error；超时未监听抛 Error
function startIsolatedDaemon({ serverDir, workDir, token, port, host = '127.0.0.1', listIntervalMs = 500, uploadDir, log = console.log } = {}) {
  const bin = daemonBinaryPath(workDir);
  const stateDir = path.join(workDir, 'state');
  const upDir = uploadDir || path.join(workDir, 'uploads');
  fs.mkdirSync(stateDir, { recursive: true });
  fs.mkdirSync(upDir, { recursive: true });

  // 关键：把 daemon 的 tmux 扫描面限定到隔离目录，绝不扫生产 /tmp/tmux-<uid>。
  const sockDir = path.join(workDir, 'tmux', `tmux-${os.userInfo().uid}`);
  const daemonEnv = {
    ...fixtureEnv(),
    TMUX_TMPDIR: path.join(workDir, 'tmux'),
    AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS: sockDir,
  };

  const logPath = path.join(workDir, 'daemon.log');
  const out = fs.openSync(logPath, 'a');
  const args = [
    '-listen', `${host}:${port}`,
    '-host', host,
    '-token', token,
    '-state-dir', stateDir,
    '-upload-dir', upDir,
    '-list-interval', `${listIntervalMs}ms`,
  ];
  log(`[fixture] starting daemon: ${bin} ${args.join(' ')}`);

  const proc = spawn(bin, args, {
    env: daemonEnv,
    stdio: ['ignore', out, out],
    detached: false,
  });
  proc.on('error', (err) => log(`[fixture] daemon spawn error: ${err.message}`));

  // 等待端口就绪（有限时间，红线5：失败必可见）。
  // lsof 的 -i 语法：-iTCP@host:port（带 host 时用 @ 分隔）；-s 的协议:状态
  // 必须作为单个 argv token（-sTCP:LISTEN），execFileSync 会原样传参，拆开即报
  // "unknown -s protocol: TCP"。
  const deadline = Date.now() + 10000;
  let ready = false;
  while (Date.now() < deadline) {
    try {
      execFileSync('lsof', ['-nP', `-iTCP@${host}:${port}`, '-sTCP:LISTEN'], { stdio: 'ignore' });
      ready = true;
      break;
    } catch {
      if (proc.exitCode !== null) break; // 进程已退出
    }
    sleepSync(200);
  }
  if (!ready) {
    const tail = fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8').split('\n').slice(-10).join('\n') : '';
    try { proc.kill('SIGKILL'); } catch { /* ignore */ }
    throw new Error(`daemon did not listen on ${host}:${port} within 10s.\n--- daemon.log tail ---\n${tail}`);
  }
  log(`[fixture] daemon ready on ${host}:${port} (pid ${proc.pid})`);

  let stopped = false;
  // stop 异步等待子进程真正退出：SIGTERM 优雅关停，2s 未退则 SIGKILL 兜底。
  // 必须让事件循环跑起来（await）才能收到 'exit' 事件；同步阻塞轮询会令
  // proc.exitCode 永不更新（此前实测 daemon 已退仍报"still alive"）。
  const stop = () => new Promise((resolve) => {
    if (stopped) { resolve(); return; }
    stopped = true;
    if (proc.exitCode !== null || proc.signalCode !== null) { resolve(); return; }
    const killer = setTimeout(() => {
      try { proc.kill('SIGKILL'); } catch (err) { log(`[fixture] daemon SIGKILL warning: ${err.message}`); }
      // 再等 500ms 让 SIGKILL 生效，随后 resolve（防残留）。
      setTimeout(resolve, 500);
    }, 2000);
    proc.once('exit', () => { clearTimeout(killer); resolve(); });
    proc.once('error', () => { clearTimeout(killer); resolve(); });
    try { proc.kill('SIGTERM'); } catch (err) { log(`[fixture] daemon SIGTERM warning: ${err.message}`); }
  });
  return { pid: proc.pid, port, token, logPath, workDir, stateDir, upDir, stop };
}

// --- 工具 ----------------------------------------------------------------

function sleepSync(ms) {
  // 同步阻塞毫秒。用 Int32 等待不派生子进程；ms=0 直接返回。
  if (ms <= 0) return;
  const { SharedArrayBuffer } = globalThis;
  const sab = new Int32Array(new SharedArrayBuffer(4));
  Atomics.wait(sab, 0, 0, ms);
}

// cleanupWorkDir 删除临时工作目录（收尾用；失败仅警告不阻断）。
function cleanupWorkDir(workDir, { log = console.log } = {}) {
  try {
    fs.rmSync(workDir, { recursive: true, force: true });
    log(`[fixture] cleaned up ${workDir}`);
  } catch (err) {
    log(`[fixture] cleanup warning: ${err.message}`);
  }
}

// assertNoResidue 收尾自证：确认隔离 daemon 进程与隔离 tmux server 均已消失。
// 只在测试全部结束后调用一次；有残留则 warning（不抛——避免掩没主结果）。
function assertNoResidue({ daemon, tmuxInfo, workDir, log = console.log } = {}) {
  const issues = [];
  if (daemon) {
    try { process.kill(daemon.pid, 0); issues.push(`daemon pid ${daemon.pid} still alive`); } catch { /* gone */ }
  }
  if (tmuxInfo) {
    try {
      runTmux(tmuxInfo.env, tmuxInfo.socketPath, ['list-sessions']);
      issues.push('isolated tmux server still alive');
    } catch { /* gone */ }
  }
  if (issues.length > 0) {
    log(`[fixture] RESIDUE WARNING: ${issues.join('; ')}`);
  }
  return issues;
}

module.exports = {
  MIN_ISOLATED_PORT,
  fixtureEnv,
  nextIsolatedPort,
  buildDaemon,
  startIsolatedTmux,
  stopIsolatedTmux,
  startIsolatedDaemon,
  cleanupWorkDir,
  assertNoResidue,
  daemonBinaryPath,
};
