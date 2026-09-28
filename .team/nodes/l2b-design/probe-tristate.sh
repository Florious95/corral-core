#!/usr/bin/env bash
# probe-tristate.sh — 062 三态规则探针：断言「世界变了」
#
# 绿（exit 0）当且仅当隔离 daemon 对真实 pane 标题给出：
#   1. Grok 空闲标题（无前导符号、以 " - grok" 结尾）→ status=idle，不是 unknown
#   2. Grok 工作标题（盲文 U+283C + " - Waiting for response"）→ status=working
#   3. 无法识别的前导符号（※ U+203B）→ status=unknown，且日志含 U+203B 与完整标题
#
# 当前 061 实现把「无前导符号」判成 unknown ⇒ 第 1 条必红。
# 文件存在、符号存在一律不算。
set -u

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
SERVER="$ROOT/server"

fail() { echo "FAIL $1"; exit 1; }
pass() { echo "PASS $1"; }

command -v tmux >/dev/null || fail "tmux 不在 PATH"
command -v go >/dev/null || fail "go 不在 PATH"
command -v node >/dev/null || fail "node 不在 PATH"
command -v python3 >/dev/null || fail "python3 不在 PATH"
[ -d "$SERVER/cmd/agentmirrord" ] || fail "找不到 server/cmd/agentmirrord（ROOT=$ROOT）"

TMP="/tmp/e2e-l2-tristate"
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
tmux -S "$SOCK" new-session -d -s l2tri -n pane0 || fail "隔离 tmux new-session 失败"
SESS="$(tmux -S "$SOCK" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS" | grep -qx l2tri || fail "自检失败：list-sessions 未见 l2tri（sock=$SOCK got='$SESS'）"

MARKER="L2T$(date +%s)$$"
# 契约 062 空闲样例：无前导符号、保留摘要、以 " - grok" 结尾
IDLE_TITLE="修滚动摘要${MARKER} - grok"
tmux -S "$SOCK" select-pane -t l2tri:0.0 -T "$IDLE_TITLE" || fail "无法设置空闲标题"
GOT="$(tmux -S "$SOCK" display-message -p -t l2tri:0.0 '#{pane_title}' 2>/dev/null || true)"
echo "$GOT" | grep -q "$MARKER" || fail "pane 标题未带上 marker（got='$GOT'）"

echo "building isolated agentmirrord..."
( cd "$SERVER" && go build -o "$BIN" ./cmd/agentmirrord ) || fail "go build agentmirrord 失败"
[ -x "$BIN" ] || fail "构建产物不可执行"

PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
[ -n "$PORT" ] || fail "无法分配临时端口"
[ "$PORT" != "9900" ] || fail "拒绝占用生产端口 9900"

TOKEN="l2tri-token"
export AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$SOCK_DIR"
export AGENTMIRROR_STATE_DIR="$STATE"
export AGENTMIRROR_TOKEN="$TOKEN"
export AGENTMIRROR_LISTEN="127.0.0.1:${PORT}"
unset TS_AUTHKEY
: >"$DAEMON_LOG"
"$BIN" >"$DAEMON_LOG" 2>&1 &
DAEMON_PID=$!
sleep 0.2
kill -0 "$DAEMON_PID" 2>/dev/null || fail "daemon 立刻退出，见 $DAEMON_LOG"

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

export PROBE_URL="ws://127.0.0.1:${PORT}/ws"
export PROBE_TOKEN="$TOKEN"
export PROBE_MARKER="$MARKER"
export PROBE_SOCK="$SOCK"
export PROBE_IDLE_TITLE="$IDLE_TITLE"
# 契约 062 工作样例：⠼ U+283C + Waiting for response（摘要不得单独判活）
export PROBE_WORK_TITLE="⠼ - Waiting for response… - 修滚动摘要${MARKER}"
export PROBE_UNK_TITLE="※unk-${MARKER}"

node --input-type=module - <<'NODE'
const url = process.env.PROBE_URL;
const token = process.env.PROBE_TOKEN;
const marker = process.env.PROBE_MARKER;
const sock = process.env.PROBE_SOCK;
const idleTitle = process.env.PROBE_IDLE_TITLE;
const workTitle = process.env.PROBE_WORK_TITLE;
const unkTitle = process.env.PROBE_UNK_TITLE;
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

function setTitle(title) {
  const r = spawnSync('tmux', ['-S', sock, 'select-pane', '-t', 'l2tri:0.0', '-T', title], {
    encoding: 'utf8',
  });
  if (r.status !== 0) throw new Error('改标题失败: ' + (r.stderr || r.stdout));
}

const ws = new WebSocket(url);
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('ws open timeout')), 5000);
  ws.addEventListener('open', () => { clearTimeout(t); resolve(); });
  ws.addEventListener('error', () => { clearTimeout(t); reject(new Error('ws error')); });
});

send(ws, 'auth', { token });
const ack = await waitFrame(ws, (f) => f.type === 'auth_ack', 5000, 'auth_ack');
if (!ack.payload || ack.payload.ok !== true) {
  throw new Error(`auth rejected: ${JSON.stringify(ack)}`);
}

send(ws, 'list', { req_id: 1 });
const listing = await waitFrame(ws, (f) => f.type === 'listing', 5000, 'listing');
const cwd = listing?.payload?.workspaces?.[0]?.cwd;
if (!cwd) throw new Error('listing 无 workspace.cwd（隔离发现没扫到本 tmux？）');

send(ws, 'level2_subscribe', { workspace: cwd });

const idleFrame = await waitFrame(ws, (f) => f.type === 'level2_frame' && rowWithMarker(f), 8000, 'level2_frame idle');
const idleRow = rowWithMarker(idleFrame);
if (!idleRow) throw new Error('空闲帧无 marker 行: ' + JSON.stringify(idleFrame));
if (idleRow.status !== 'idle') {
  throw new Error(
    `Grok 空闲标题应判 idle，got status=${idleRow.status} title=${JSON.stringify(idleRow.title)}（061 会给出 unknown）`,
  );
}
console.log('PASS grok idle → idle title=' + idleRow.title);

setTitle(workTitle);
const workFrame = await waitFrame(
  ws,
  (f) => f.type === 'level2_frame' && rowWithMarker(f) && rowWithMarker(f).title.includes('Waiting for response'),
  5000,
  'level2_frame grok working',
);
const workRow = rowWithMarker(workFrame);
if (!workRow || workRow.status !== 'working') {
  throw new Error(`Grok 工作标题应判 working，got ${JSON.stringify(workRow)}`);
}
console.log('PASS grok working → working title=' + workRow.title);

setTitle(unkTitle);
const unkFrame = await waitFrame(
  ws,
  (f) => f.type === 'level2_frame' && rowWithMarker(f) && rowWithMarker(f).title.startsWith('※'),
  5000,
  'level2_frame unknown glyph',
);
const unkRow = rowWithMarker(unkFrame);
if (!unkRow || unkRow.status !== 'unknown') {
  throw new Error(`无法识别前导符号应判 unknown，got ${JSON.stringify(unkRow)}`);
}
console.log('PASS unknown glyph → unknown title=' + unkRow.title);
ws.close();
NODE
if [ $? -ne 0 ]; then
  echo "---- daemon classify-related log ----"
  grep -E 'glyph unknown|codepoint=|level2' "$DAEMON_LOG" 2>/dev/null | tail -n 20 || true
  fail "三态探针未在线上看到正确 status（空闲应变 idle）"
fi

# 第 3 条：日志必须同时有码点与完整标题（判据的操作数，不只判决）
if ! grep -q 'U+203B' "$DAEMON_LOG"; then
  fail "未知符号日志缺码点 U+203B"
fi
if ! grep -Fq "$PROBE_UNK_TITLE" "$DAEMON_LOG"; then
  fail "未知符号日志缺完整原始标题"
fi
pass "unknown log has U+203B + full title"

pass "三态：Grok 空闲=idle、Grok 工作=working、※=unknown+码点"
echo "probe-tristate: ALL PASS (exit 0)"
exit 0
