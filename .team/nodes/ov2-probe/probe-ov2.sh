#!/usr/bin/env bash
# probe-ov2.sh — 065 加强探针：验渲染结果，不验「有东西在动」
#
# ① 无裸控制序列：渲染后不得出现 ESC[ / [?1049 / [K / (B[m
# ② socket 正确且无自我映照：必须有目标会话名，不得有 am-overlay / tree / sleep / ov-spin
# ③ 替换而非追加：行数 bounded（≤ 终端行数），同一棵树不得重复多份
#
# 渲染口径：与当前 App 一致——把 overlay_frame.text 当普通字符串画
# （ESC 不可见，留下 [?1049h 这类字面量）。这正是真机坏样本。
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

tmux -S "$SOCK_A" kill-server 2>/dev/null || true
tmux -S "$SOCK_B" kill-server 2>/dev/null || true
# 目标 socket：会话名独特，pane 命令用 cat，避免和 scratch 的 sleep* 撞名
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
node --input-type=module - <<'NODE'
const url = process.env.PROBE_URL;
const token = process.env.PROBE_TOKEN;
const sockA = process.env.PROBE_SOCK_A;

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

// 修完后服务端必须认这个 socket；当前实现忽略，正好打 ②
send(ws, 'overlay_subscribe', { socket: sockA });

const frames = [];
const deadline = Date.now() + 8000;
while (Date.now() < deadline && frames.length < 8) {
  try {
    const f = await waitFrame(ws, (x) => x.type === 'overlay_frame', Math.max(200, deadline - Date.now()), 'overlay_frame');
    if (typeof f.payload?.text === 'string' && f.payload.text.length) frames.push(f);
  } catch {
    break;
  }
}
ws.close();
if (frames.length < 1) throw new Error('没有 overlay_frame，无法验三条渲染断言');

const rows = Number(frames[0].payload.rows) || 24;
const last = frames[frames.length - 1];
const raw = String(last.payload.text);
// 当前 App：text 当普通字符串。ESC 不可见，留下 [?1049h
const rendered = raw.replace(/\x1b/g, '');

const fails = [];

// ① 无裸控制序列
const csiHits = [];
if (raw.includes('\x1b[' ) || rendered.includes('ESC[')) csiHits.push('ESC[');
if (rendered.includes('[?1049')) csiHits.push('[?1049');
if (/(^|[^0-9])\[K(?![A-Za-z0-9])/.test(rendered) || rendered.includes('[K')) csiHits.push('[K');
if (rendered.includes('(B[m')) csiHits.push('(B[m');
if (rendered.includes('[30m') || rendered.includes('[43m')) csiHits.push('[30m/[43m');
if (csiHits.length) {
  fails.push('① 裸控制序列: ' + csiHits.join(','));
  console.log('FAIL ① 渲染后仍有 ' + csiHits.join(' '));
} else {
  console.log('PASS ① 无裸控制序列');
}

// ② socket 正确且无自我映照
const need = 'alpha-ov2';
const forbidden = [];
if (!rendered.includes(need)) forbidden.push('missing-target-session:' + need);
if (rendered.includes('am-overlay')) forbidden.push('am-overlay');
if (rendered.includes('ov-spin')) forbidden.push('ov-spin');
if (/\btree\b/.test(rendered) || rendered.includes('tree*')) forbidden.push('tree');
if (rendered.includes('sleep*') || /\bsleep\b/.test(rendered)) forbidden.push('sleep');
if (rendered.includes('beta-ov2')) forbidden.push('wrong-socket:beta-ov2');
if (forbidden.length) {
  fails.push('② socket/自我映照: ' + forbidden.join(','));
  console.log('FAIL ② ' + forbidden.join(' '));
} else {
  console.log('PASS ② 目标会话在、scratch 不在');
}

// ③ 替换而非追加：行数 bounded，同一棵树不得重复多份
const lineCount = rendered.split(/\r\n|\n|\r/).length;
const bounded = lineCount <= rows;
const treeMarks = (rendered.match(/am-overlay/g) || []).length
  + (rendered.match(/alpha-ov2/g) || []).length;
const dup = treeMarks > 4;
if (!bounded || dup) {
  fails.push(`③ 行数=${lineCount} rows=${rows} bounded=${bounded} treeMarks=${treeMarks}`);
  console.log(`FAIL ③ 行数=${lineCount} (≤${rows}? ${bounded}) treeMarks=${treeMarks} frames=${frames.length}`);
} else {
  console.log(`PASS ③ 行数有界 ${lineCount}≤${rows} 且无重复树`);
}

console.log('--- last frame meta ---');
console.log('frames=' + frames.length + ' last_bytes=' + raw.length + ' last_lines=' + lineCount);
if (fails.length) {
  console.log('RED ' + fails.join(' | '));
  process.exit(1);
}
console.log('probe-ov2 ALL PASS');
NODE
if [ $? -ne 0 ]; then
  fail "加强探针未全绿（当前坏实现预期红）"
fi
pass "三条渲染断言全过"
exit 0
