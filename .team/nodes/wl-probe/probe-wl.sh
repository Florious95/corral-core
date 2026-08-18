#!/usr/bin/env bash
# probe-wl.sh — 068 白名单根因探针
#
# ① 五家都被识别且 provider 正确
# ② 裸 bash pane 不在结果里（阴性控制）
# ③ 全路径 comm 必须命中（整串相等必须先红；macOS ps comm 给全路径）
# ④ 实现里 grep 不到 argv 读法（-f / args= / command=）
#
# 夹具：五个可执行脚本 claude/codex/copilot/grok/cursor-agent，正文 `exec sleep 600`。
# 本机实测直接跑脚本 comm 会变成 sleep，故启动用 exec -a <脚本全路径> sleep 600，
# 使 comm 与真 CLI 同形（全路径，basename 才是家名）。
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
[ -d "$SERVER/cmd/agentmirrord" ] || fail "找不到 server"

BASE=/tmp/wl-advisor
BIN="$BASE/bin"
SOCK_DIR="$BASE/tmux"
SOCK="$SOCK_DIR/sock"
STATE="$BASE/state"
DAEMON_LOG="$BASE/daemon.log"
BIN_DAEMON="$BASE/agentmirrord"
unset TMUX
unset TMUX_TMPDIR
rm -rf "$BASE"
mkdir -p "$BIN" "$SOCK_DIR" "$STATE"

DAEMON_PID=""
cleanup() {
  if [ -n "${DAEMON_PID:-}" ]; then
    kill "$DAEMON_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8; do
      kill -0 "$DAEMON_PID" 2>/dev/null || break
      sleep 0.12
    done
    kill -9 "$DAEMON_PID" 2>/dev/null || true
    wait "$DAEMON_PID" 2>/dev/null || true
  fi
  tmux -S "$SOCK" kill-server 2>/dev/null || true
}
trap cleanup EXIT

# 五个脚本（任务规定的正文）
for name in claude codex copilot grok cursor-agent; do
  printf '%s\n' '#!/bin/sh' 'exec sleep 600' > "$BIN/$name"
  chmod +x "$BIN/$name"
done

# 隔离 tmux：五家 + 裸 bash
tmux -S "$SOCK" new-session -d -s wlfix -n w-claude "exec -a $BIN/claude sleep 600" \
  || fail "new-session 失败"
SESS="$(tmux -S "$SOCK" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS" | grep -qx wlfix || fail "自检失败：会话不在隔离 socket（got='$SESS'）"

tmux -S "$SOCK" new-window -t wlfix -n w-codex "exec -a $BIN/codex sleep 600" || fail "w-codex"
tmux -S "$SOCK" new-window -t wlfix -n w-copilot "exec -a $BIN/copilot sleep 600" || fail "w-copilot"
tmux -S "$SOCK" new-window -t wlfix -n w-grok "exec -a $BIN/grok sleep 600" || fail "w-grok"
tmux -S "$SOCK" new-window -t wlfix -n w-cursor "exec -a $BIN/cursor-agent sleep 600" || fail "w-cursor"
tmux -S "$SOCK" new-window -t wlfix -n w-bare 'exec bash --norc --noprofile -c "exec -a bash sleep 600"' \
  || fail "w-bare"

sleep 0.3
echo "=== panes ==="
tmux -S "$SOCK" list-panes -a -F '#{window_name} pid=#{pane_pid} cmd=#{pane_current_command} title=#{pane_title}'

# ③ 取样：codex pane 的 comm（只取 pid/ppid/comm）
CODEX_PID="$(tmux -S "$SOCK" list-panes -a -F '#{window_name} #{pane_pid}' | awk '$1=="w-codex"{print $2; exit}')"
[ -n "$CODEX_PID" ] || fail "找不到 w-codex pane_pid"
export WL_CODEX_COMM
WL_CODEX_COMM="$(python3 - "$CODEX_PID" <<'PY'
import subprocess,sys
root=int(sys.argv[1])
out=subprocess.check_output(["ps","-axo","pid=,ppid=,comm="], text=True)
rows=[]
for ln in out.splitlines():
    p=ln.split(None,2)
    if len(p)<3: continue
    rows.append((int(p[0]),int(p[1]),p[2]))
by_pp={}
for pid,pp,c in rows:
    by_pp.setdefault(pp,[]).append((pid,c))
comms=[]
def walk(pid):
    rec=next((c for p,pp,c in rows if p==pid), None)
    if rec: comms.append(rec)
    for cpid,_ in by_pp.get(pid,[]):
        walk(cpid)
walk(root)
# prefer a comm whose basename is codex
for c in comms:
    base=c.rsplit("/",1)[-1]
    if base=="codex":
        print(c); raise SystemExit
print(comms[0] if comms else "")
PY
)"
echo "codex_comm=$WL_CODEX_COMM"

echo "building isolated agentmirrord..."
( cd "$SERVER" && go build -o "$BIN_DAEMON" ./cmd/agentmirrord ) || fail "go build 失败"
PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
[ "$PORT" != "9900" ] || fail "拒绝 9900"

export AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$SOCK_DIR"
export AGENTMIRROR_STATE_DIR="$STATE"
export AGENTMIRROR_TOKEN="wl-probe-token"
export AGENTMIRROR_LISTEN="127.0.0.1:${PORT}"
unset TS_AUTHKEY
: >"$DAEMON_LOG"
"$BIN_DAEMON" >"$DAEMON_LOG" 2>&1 &
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
export PROBE_TOKEN="wl-probe-token"
export WL_ROOT="$ROOT"
export WL_CODEX_COMM
node --input-type=module - <<'NODE'
const url = process.env.PROBE_URL;
const token = process.env.PROBE_TOKEN;
const comm = process.env.WL_CODEX_COMM || '';
const root = process.env.WL_ROOT;

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

send(ws, 'list', { req_id: 1 });
const listing = await waitFrame(ws, (f) => f.type === 'listing', 5000, 'listing');
const workspaces = listing.payload?.workspaces || [];
if (!workspaces.length) throw new Error('listing 无 workspace');
const cwd = workspaces[0].cwd;
send(ws, 'level2_subscribe', { workspace: cwd });
const l2 = await waitFrame(ws, (f) => f.type === 'level2_frame', 8000, 'level2_frame');
const sessions = l2.payload?.sessions || [];
ws.close();

const names = sessions.map((s) => s.name);
const providers = sessions.map((s) => s.provider || '');
console.log('sessions=' + sessions.length + ' names=' + JSON.stringify(names) + ' providers=' + JSON.stringify(providers));

const want = [
  ['w-claude', 'claude_code'],
  ['w-codex', 'codex'],
  ['w-copilot', 'copilot'],
  ['w-grok', 'grok'],
  ['w-cursor', 'cursor'],
];
const fails = [];

// ①
const missing = [];
for (const [name, prov] of want) {
  const s = sessions.find((x) => x.name === name);
  if (!s) { missing.push(name + ':absent'); continue; }
  if (s.provider !== prov) missing.push(name + ':provider=' + (s.provider || '(empty)') + ' want=' + prov);
}
if (missing.length) {
  fails.push('① ' + missing.join(','));
  console.log('FAIL ① ' + missing.join(' '));
} else {
  console.log('PASS ① 五家均在且 provider 正确');
}

// ② 裸 bash 阴性控制
const bare = sessions.find((x) => x.name === 'w-bare');
if (bare) {
  fails.push('② w-bare 不应出现');
  console.log('FAIL ② 裸 bash pane 仍在列表 name=w-bare');
} else {
  console.log('PASS ② 裸 bash 未进列表');
}

// ③ 全路径 comm：整串相等必须先红
const base = comm.split('/').pop();
const exact = comm === 'codex';
const byBase = base === 'codex';
console.log('③ comm=' + comm + ' exact=' + exact + ' basename=' + byBase);
if (!comm || !comm.includes('/')) {
  fails.push('③ 未采到全路径 comm');
  console.log('FAIL ③ comm 不是全路径: ' + JSON.stringify(comm));
} else if (exact) {
  fails.push('③ 整串相等意外命中（夹具不对）');
  console.log('FAIL ③ unexpected exact hit');
} else {
  // 产品若已做 basename，应能从列表里认出 w-codex 的 provider=codex
  const s = sessions.find((x) => x.name === 'w-codex');
  if (s && s.provider === 'codex') {
    console.log('PASS ③ 全路径 comm 已被产品命中');
  } else {
    fails.push('③ 整串相等未命中（comm=' + comm + '），产品也未 basename');
    console.log('FAIL ③ 整串相等 miss；产品未把全路径认成 codex');
  }
}

// ④ argv 读法（产品实现，不含本探针）
const { spawnSync } = await import('node:child_process');
const rg = spawnSync('grep', [
  '-RInE',
  'pgrep[^\n]*-f|ps[^\n]*-f|args=|command=',
  root + '/server/internal',
  root + '/tools/nodeprobe',
], { encoding: 'utf8' });
const hits = (rg.stdout || '').split('\n').filter((ln) => {
  if (!ln) return false;
  if (ln.includes('pane_current_command')) return false;
  if (ln.includes('probe-wl')) return false;
  return true;
});
if (hits.length) {
  fails.push('④ argv 读法 ' + hits.length + ' 处');
  console.log('FAIL ④\n' + hits.slice(0, 20).join('\n'));
} else {
  console.log('PASS ④ 实现中无 -f / args= / command=');
}

if (fails.length) {
  console.log('RED ' + fails.join(' | '));
  process.exit(1);
}
console.log('probe-wl ALL PASS');
NODE
if [ $? -ne 0 ]; then
  fail "白名单探针未全绿（当前无白名单预期红）"
fi
pass "四条断言全过"
exit 0
