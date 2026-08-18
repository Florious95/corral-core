#!/usr/bin/env bash
# probe-overlay.sh — 064 跨端探针：悬浮窗流必须是动态抓屏，不是静态图
#
# 绿：订阅后收到非空 overlay_frame，且若干秒内至少两帧内容不同。
# 现在协议/服务端都没有这条流 ⇒ 必须红。
set -u
fail() { echo "FAIL $1"; exit 1; }
pass() { echo "PASS $1"; }

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
SERVER="$ROOT/server"

command -v tmux >/dev/null || fail "tmux 不在 PATH"
command -v go >/dev/null || fail "go 不在 PATH"
command -v node >/dev/null || fail "node 不在 PATH"
command -v python3 >/dev/null || fail "python3 不在 PATH"
[ -d "$SERVER/cmd/agentmirrord" ] || fail "找不到 server/cmd/agentmirrord"

BASE=/tmp/ov-probe
SOCK_DIR="$BASE/tmux"
SOCK="$SOCK_DIR/sock"
STATE="$BASE/state"
BIN="$BASE/agentmirrord"
DAEMON_LOG="$BASE/daemon.log"
unset TMUX
unset TMUX_TMPDIR
mkdir -p "$SOCK_DIR" "$STATE"

DAEMON_PID=""
cleanup() {
  if [ -n "${DAEMON_PID:-}" ]; then
    kill "$DAEMON_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8; do
      kill -0 "$DAEMON_PID" 2>/dev/null || break
      sleep 0.15
    done
    kill -9 "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
  fi
  if [ -e "$SOCK" ]; then
    tmux -S "$SOCK" kill-server 2>/dev/null || true
  fi
}
trap cleanup EXIT

tmux -S "$SOCK" kill-server 2>/dev/null || true
tmux -S "$SOCK" new-session -d -s ovp -n p0 'sleep 3600' || fail "隔离 tmux 失败"
SESS="$(tmux -S "$SOCK" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS" | grep -qx ovp || fail "自检失败：list-sessions 未见 ovp（got='$SESS'）"

echo "building isolated agentmirrord..."
( cd "$SERVER" && go build -o "$BIN" ./cmd/agentmirrord ) || fail "go build 失败"
PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
[ "$PORT" != "9900" ] || fail "拒绝 9900"

export AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$SOCK_DIR"
export AGENTMIRROR_STATE_DIR="$STATE"
export AGENTMIRROR_TOKEN="ov-probe-token"
export AGENTMIRROR_LISTEN="127.0.0.1:${PORT}"
unset TS_AUTHKEY
: >"$DAEMON_LOG"
"$BIN" >"$DAEMON_LOG" 2>&1 &
DAEMON_PID=$!
sleep 0.2
kill -0 "$DAEMON_PID" 2>/dev/null || fail "daemon 立刻退出"

READY=0
for _ in $(seq 1 40); do
  if python3 -c "import socket;s=socket.socket();s.settimeout(0.2);s.connect(('127.0.0.1',int('$PORT')));s.close()" 2>/dev/null; then
    READY=1; break
  fi
  kill -0 "$DAEMON_PID" 2>/dev/null || fail "daemon 监听前退出"
  sleep 0.2
done
[ "$READY" = 1 ] || fail "daemon 未监听 127.0.0.1:${PORT}"

export PROBE_URL="ws://127.0.0.1:${PORT}/ws"
export PROBE_TOKEN="ov-probe-token"
node --input-type=module - <<'NODE'
const url = process.env.PROBE_URL;
const token = process.env.PROBE_TOKEN;

function send(ws, type, payload) {
  ws.send(JSON.stringify({ v: 1, type, payload: payload || {} }));
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

function payloadText(f) {
  const p = f.payload || {};
  if (typeof p.text === 'string') return p.text;
  if (typeof p.data === 'string') return p.data;
  if (typeof p.hash === 'string') return p.hash;
  return JSON.stringify(p);
}

const ws = new WebSocket(url);
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('ws open timeout')), 5000);
  ws.addEventListener('open', () => { clearTimeout(t); resolve(); });
  ws.addEventListener('error', () => { clearTimeout(t); reject(new Error('ws error')); });
});
send(ws, 'auth', { token });
const ack = await waitFrame(ws, (f) => f.type === 'auth_ack', 5000, 'auth_ack');
if (!ack.payload || ack.payload.ok !== true) throw new Error('auth rejected');

send(ws, 'overlay_subscribe', {});
let first;
try {
  first = await waitFrame(ws, (f) => f.type === 'overlay_frame', 6000, 'overlay_frame');
} catch (e) {
  throw new Error(`订阅后没有 overlay_frame（当前无悬浮窗流，预期红）：${e.message}`);
}
const a = payloadText(first);
if (!a || !String(a).trim()) throw new Error('第一帧为空');

const second = await waitFrame(
  ws,
  (f) => f.type === 'overlay_frame' && payloadText(f) !== a,
  5000,
  'second different overlay_frame',
);
const b = payloadText(second);
if (a === b) throw new Error('两帧内容相同，不是动态流');
console.log('PASS two distinct non-empty overlay frames');
ws.close();
NODE
if [ $? -ne 0 ]; then
  fail "未收到动态非空抓屏帧"
fi
pass "悬浮窗流：非空且两帧不同"
exit 0
