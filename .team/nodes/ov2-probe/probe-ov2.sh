#!/usr/bin/env bash
# probe-ov2.sh — 066 附则三段式：抓真实流夹具 → 喂安卓 OverlayEmulator → 退出码=单测
#
# ① 抓夹具：隔离 socket + 真实 agentmirrord，连续 ≥8 帧 overlay_frame 原样落盘
#    （text/rows/cols 不剥 ESC、不本地渲染）
# ② 喂安卓渲染器：gradle *OverlayEmulatorFixture*（t.port 产出）读夹具，
#    逐帧喂 App 真实 OverlayEmulator，在最终屏幕文本上断言 065 三条
# ③ 本脚本退出码 = 该单测退出码
#
# ⛔ 不用 JS 模拟器代跑：被测对象是安卓渲染器。旧口径「text 当普通字符串画」
# 在 App 改用模拟器后过期——那会把已修的安卓判成仍红，或让错误渲染器蒙混。
set -u
fail() { echo "FAIL $1"; exit 1; }

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
SERVER="$ROOT/server"
APP="$ROOT/app"
FRAMES="$ORACLE_DIR/frames.jsonl"

command -v tmux >/dev/null || fail "tmux 不在 PATH"
command -v go >/dev/null || fail "go 不在 PATH"
command -v node >/dev/null || fail "node 不在 PATH"
command -v python3 >/dev/null || fail "python3 不在 PATH"
[ -d "$SERVER/cmd/agentmirrord" ] || fail "找不到 server/cmd/agentmirrord"
[ -x "$APP/gradlew" ] || fail "找不到 app/gradlew"

BASE=/tmp/ov2-advisor
SOCK_DIR="$BASE/tmux"
SOCK_A="$SOCK_DIR/sock-a"
SOCK_B="$SOCK_DIR/sock-b"
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
  tmux -S "$SOCK_A" kill-server 2>/dev/null || true
  tmux -S "$SOCK_B" kill-server 2>/dev/null || true
}
trap cleanup EXIT

# --- ① 抓夹具：真实服务端流 ---
tmux -S "$SOCK_A" kill-server 2>/dev/null || true
tmux -S "$SOCK_B" kill-server 2>/dev/null || true
# 目标 socket：会话名独特，pane 用 cat，避免和 scratch 的 sleep* 撞名
tmux -S "$SOCK_A" new-session -d -s alpha-ov2 -n main 'cat' || fail "sock-a new-session"
SESS_A="$(tmux -S "$SOCK_A" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS_A" | grep -qx alpha-ov2 || fail "自检失败 sock-a（got='$SESS_A'）"
tmux -S "$SOCK_B" new-session -d -s beta-ov2 -n other 'cat' || fail "sock-b new-session"
SESS_B="$(tmux -S "$SOCK_B" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS_B" | grep -qx beta-ov2 || fail "自检失败 sock-b（got='$SESS_B'）"

echo "building isolated agentmirrord..."
( cd "$SERVER" && go build -o "$BIN" ./cmd/agentmirrord ) || fail "go build 失败"
PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
[ "$PORT" != "9900" ] || fail "拒绝 9900"

export AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$SOCK_DIR"
export AGENTMIRROR_STATE_DIR="$STATE"
export AGENTMIRROR_TOKEN="ov2-probe-token"
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
[ "$READY" = 1 ] || fail "daemon 未监听"

export PROBE_URL="ws://127.0.0.1:${PORT}/ws"
export PROBE_TOKEN="ov2-probe-token"
export PROBE_SOCK_A="$SOCK_A"
export OV2_FRAMES_JSONL="$FRAMES"
node --input-type=module - <<'NODE'
import fs from 'node:fs';

const url = process.env.PROBE_URL;
const token = process.env.PROBE_TOKEN;
const sockA = process.env.PROBE_SOCK_A;
const outPath = process.env.OV2_FRAMES_JSONL;

function send(ws, type, payload) {
  ws.send(JSON.stringify({ v: 1, type, payload: payload || {} }));
}
function waitFrame(ws, pred, ms, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timeout ${ms}ms ${label}`)), ms);
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

const ws = new WebSocket(url);
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('ws open timeout')), 5000);
  ws.addEventListener('open', () => { clearTimeout(t); resolve(); });
  ws.addEventListener('error', () => { clearTimeout(t); reject(new Error('ws error')); });
});
send(ws, 'auth', { token });
const ack = await waitFrame(ws, (f) => f.type === 'auth_ack', 5000, 'auth_ack');
if (!ack.payload || ack.payload.ok !== true) throw new Error('auth rejected');

send(ws, 'overlay_subscribe', { socket: sockA });

const frames = [];
const deadline = Date.now() + 20000;
while (Date.now() < deadline && frames.length < 8) {
  try {
    const f = await waitFrame(ws, (x) => x.type === 'overlay_frame', Math.max(200, deadline - Date.now()), 'overlay_frame');
    const p = f.payload || {};
    if (typeof p.text === 'string' && p.text.length) frames.push(f);
  } catch {
    break;
  }
}
ws.close();
if (frames.length < 8) {
  throw new Error('overlay_frame 不足 8 帧（got=' + frames.length + '），夹具不立');
}

// 原样：payload 的 text/rows/cols/seq 不剥 ESC、不本地渲染
const lines = frames.map((f) => {
  const p = f.payload || {};
  return JSON.stringify({
    seq: p.seq,
    text: p.text,
    rows: p.rows,
    cols: p.cols,
  });
});
fs.writeFileSync(outPath, lines.join('\n') + '\n');
const last = frames[frames.length - 1].payload || {};
console.log(
  'capture frames=' + frames.length +
  ' last_seq=' + last.seq +
  ' last_bytes=' + String(last.text || '').length +
  ' rows=' + last.rows +
  ' cols=' + last.cols +
  ' -> ' + outPath
);
NODE
if [ $? -ne 0 ]; then
  fail "① 抓夹具失败"
fi
N="$(python3 -c "import pathlib; p=pathlib.Path(r'$FRAMES'); print(sum(1 for l in p.read_text(encoding='utf-8').splitlines() if l.strip()) if p.is_file() else 0)")"
[ "$N" -ge 8 ] || fail "① frames.jsonl 行数=$N 不足 8"

echo "PASS ① 夹具 $N 帧 -> $FRAMES"

# --- ② 喂安卓渲染器（不是 JS）---
export OV2_FRAMES_JSONL="$FRAMES"
echo "running OverlayEmulatorFixture via gradlew..."
set +e
( cd "$APP" && ./gradlew testDebugUnitTest --tests '*OverlayEmulatorFixture*' )
GRADLE_RC=$?
set -e

echo "gradle OverlayEmulatorFixture exit=$GRADLE_RC"
# --- ③ 退出码 = 单测退出码 ---
exit "$GRADLE_RC"
