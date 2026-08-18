#!/usr/bin/env bash
# probe.sh — 061 跨端联调探针：断言「世界变了」
#
# 绿（exit 0）当且仅当：
#   1. 隔离 tmux 里有一条真实 pane，标题含唯一 marker；
#   2. 隔离 agentmirrord（非生产 9900、非用户 tmux）已起来；
#   3. 客户端真订阅后收到 type=level2_frame，某行 title 含 marker 且 status=working；
#   4. 把标题改成 ✳ 后 5s 内再收到 status=idle 的 level2_frame。
#
# 文件存在、Go/Kotlin 符号存在、dex 含 level2 —— 一律不算。
# 回退后功能不存在 ⇒ 必须红（exit != 0）。实现完必须转绿。
#
# 用法：bash .team/nodes/l2-design/probe.sh
set -u

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
# .team/nodes/l2-design → 仓根或对应 worktree 根
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
SERVER="$ROOT/server"

fail() { echo "FAIL $1"; exit 1; }
pass() { echo "PASS $1"; }

command -v tmux >/dev/null || fail "tmux 不在 PATH"
command -v go >/dev/null || fail "go 不在 PATH"
command -v node >/dev/null || fail "node 不在 PATH"
command -v python3 >/dev/null || fail "python3 不在 PATH"
[ -d "$SERVER/cmd/agentmirrord" ] || fail "找不到 server/cmd/agentmirrord（ROOT=$ROOT）"

# --- 隔离现场：短路径、预建目录、unset TMUX，自检必须落在自己的 socket ---
TMP="/tmp/e2e-l2-design"
SOCK_DIR="$TMP/tmux"
SOCK="$SOCK_DIR/l2p"
STATE="$TMP/state"
BIN="$TMP/agentmirrord"
DAEMON_LOG="$TMP/daemon.log"
unset TMUX
unset TMUX_TMPDIR
mkdir -p "$SOCK_DIR" "$STATE"

DAEMON_PID=""
cleanup() {
  if [ -n "${DAEMON_PID:-}" ]; then
    kill "$DAEMON_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8; do
      kill -0 "$DAEMON_PID" 2>/dev/null || break
      sleep 0.2
    done
    kill -9 "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
  fi
  if [ -S "$SOCK" ] || [ -e "$SOCK" ]; then
    tmux -S "$SOCK" kill-server 2>/dev/null || true
  fi
}
trap cleanup EXIT

tmux -S "$SOCK" kill-server 2>/dev/null || true
tmux -S "$SOCK" new-session -d -s l2probe -n pane0 || fail "隔离 tmux new-session 失败"
# 自检：会话必须在本 socket 上，否则立刻停手（静默回退到用户 tmux 的已知坑）
SESS="$(tmux -S "$SOCK" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS" | grep -qx l2probe || fail "自检失败：list-sessions 未见 l2probe（sock=$SOCK got='$SESS'）"

MARKER="L2P$(date +%s)$$"
# U+25D0 进行中
tmux -S "$SOCK" select-pane -t l2probe:0.0 -T "◐ ${MARKER}" \
  || fail "无法设置隔离 pane 标题"
GOT_TITLE="$(tmux -S "$SOCK" display-message -p -t l2probe:0.0 '#{pane_title}' 2>/dev/null || true)"
echo "$GOT_TITLE" | grep -q "$MARKER" || fail "pane 标题未带上 marker（got='$GOT_TITLE'）"
echo "tmux title ok: $GOT_TITLE"

echo "building isolated agentmirrord..."
( cd "$SERVER" && go build -o "$BIN" ./cmd/agentmirrord ) || fail "go build agentmirrord 失败"
[ -x "$BIN" ] || fail "构建产物不可执行"

PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
[ -n "$PORT" ] || fail "无法分配临时端口"
[ "$PORT" != "9900" ] || fail "拒绝占用生产端口 9900"

TOKEN="l2probe-token"
# 只扫本探针的 socket 目录，绝不枚举用户 /tmp/tmux-*
export AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$SOCK_DIR"
export AGENTMIRROR_STATE_DIR="$STATE"
export AGENTMIRROR_TOKEN="$TOKEN"
export AGENTMIRROR_LISTEN="127.0.0.1:${PORT}"
unset TS_AUTHKEY
"$BIN" >"$DAEMON_LOG" 2>&1 &
DAEMON_PID=$!
sleep 0.2
kill -0 "$DAEMON_PID" 2>/dev/null || fail "daemon 立刻退出，见 $DAEMON_LOG"

# 等监听（最多 8s）
READY=0
for _ in $(seq 1 40); do
  if python3 -c "import socket;s=socket.socket();s.settimeout(0.2);s.connect(('127.0.0.1',int('$PORT')));s.close()" 2>/dev/null; then
    READY=1
    break
  fi
  kill -0 "$DAEMON_PID" 2>/dev/null || fail "daemon 在监听前退出，见 $DAEMON_LOG"
  sleep 0.2
done
[ "$READY" = 1 ] || fail "daemon 未在 127.0.0.1:${PORT} 监听"

echo "daemon listening 127.0.0.1:${PORT} pid=$DAEMON_PID"

# Node 22+ 全局 WebSocket。验的是线上帧，不是仓库里有没有符号。
export PROBE_URL="ws://127.0.0.1:${PORT}/ws"
export PROBE_TOKEN="$TOKEN"
export PROBE_MARKER="$MARKER"
export PROBE_SOCK="$SOCK"
node --input-type=module - <<'NODE'
const url = process.env.PROBE_URL;
const token = process.env.PROBE_TOKEN;
const marker = process.env.PROBE_MARKER;
const sock = process.env.PROBE_SOCK;
const { spawnSync } = await import('node:child_process');

function send(ws, type, payload) {
  ws.send(JSON.stringify({ v: 1, type, payload }));
}

function waitFrame(ws, pred, ms, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timeout ${ms}ms waiting ${label}`)), ms);
    const onMsg = (ev) => {
      let f;
      try { f = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString()); }
      catch { return; }
      if (pred(f)) {
        clearTimeout(timer);
        ws.removeEventListener('message', onMsg);
        resolve(f);
      }
    };
    ws.addEventListener('message', onMsg);
  });
}

function rowWithMarker(frame) {
  const sessions = frame?.payload?.sessions;
  if (!Array.isArray(sessions)) return null;
  return sessions.find((s) => typeof s?.title === 'string' && s.title.includes(marker)) || null;
}

const ws = new WebSocket(url);
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('ws open timeout')), 5000);
  ws.addEventListener('open', () => { clearTimeout(t); resolve(); });
  ws.addEventListener('error', (e) => { clearTimeout(t); reject(new Error('ws error')); });
});

send(ws, 'auth', { token });
const ack = await waitFrame(ws, (f) => f.type === 'auth_ack', 5000, 'auth_ack');
if (!ack.payload || ack.payload.ok !== true) {
  throw new Error(`auth rejected: ${JSON.stringify(ack)}`);
}

// 用 listing 拿真实 cwd，订阅必须跟一级 cwd 逐字节相同（实现方案 §2）
send(ws, 'list', { req_id: 1 });
const listing = await waitFrame(ws, (f) => f.type === 'listing', 5000, 'listing');
const workspaces = listing?.payload?.workspaces || [];
if (!Array.isArray(workspaces) || workspaces.length === 0) {
  throw new Error(`listing 无 workspace（隔离发现是否扫到本 tmux？） ${JSON.stringify(listing)}`);
}
const cwd = workspaces[0].cwd;
if (!cwd) throw new Error('listing workspace.cwd 为空');

send(ws, 'level2_subscribe', { workspace: cwd });

let first;
try {
  first = await waitFrame(ws, (f) => f.type === 'level2_frame', 8000, 'level2_frame after subscribe');
} catch (e) {
  throw new Error(
    `订阅后没收到 level2_frame（回退基线预期红：协议/循环不存在）。${e.message}`,
  );
}

const row1 = rowWithMarker(first);
if (!row1) {
  throw new Error(`level2_frame 无含 marker 的 title。frame=${JSON.stringify(first)}`);
}
if (row1.status !== 'working') {
  throw new Error(`期望 status=working（标题以 ◐ 开头），got=${JSON.stringify(row1)}`);
}
console.log('PASS recv working row title=' + row1.title);

const retitle = spawnSync('tmux', ['-S', sock, 'select-pane', '-t', 'l2probe:0.0', '-T', '✳ ' + marker], {
  encoding: 'utf8',
});
if (retitle.status !== 0) {
  throw new Error('改标题失败: ' + (retitle.stderr || retitle.stdout));
}

const second = await waitFrame(
  ws,
  (f) => f.type === 'level2_frame' && rowWithMarker(f) && rowWithMarker(f).status === 'idle',
  5000,
  'level2_frame status=idle within 5s',
);
const row2 = rowWithMarker(second);
if (!row2 || row2.status !== 'idle') {
  throw new Error(`5s 内未观察到 idle。last=${JSON.stringify(second)}`);
}
console.log('PASS recv idle row within 5s title=' + row2.title);
ws.close();
NODE
if [ $? -ne 0 ]; then
  echo "---- daemon log (last 15 non-qr lines) ----"
  grep -E '^(time=|FAIL|error|level=)' "$DAEMON_LOG" 2>/dev/null | tail -n 15 || tail -n 8 "$DAEMON_LOG"
  fail "跨端联调未看到真实 pane 标题+状态"
fi

pass "跨端：订阅后收到真实 ◐ 行 working，5s 内 ✳ 变为 idle"
echo "probe: ALL PASS (exit 0)"
exit 0
