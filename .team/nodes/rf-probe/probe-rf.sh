#!/usr/bin/env bash
# probe-rf.sh — 069 进菜单即时刷新：断言世界变了，不验「有 scan 调用」
#
# ① A-rf-l1：无人订阅时增一个白名单会话 → list → 必须是新世界
# ② A-rf-l2：无人订阅时 idle→working → 进二级 → 首个非缓存帧是新状态，且 < 2s
# ③ 不倒退：进入瞬间不得出现空列表帧
# ④ 不倒退：没人在二级菜单时零 level2 推送（零订阅零轮询）
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

BASE=/tmp/rf-advisor
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

# 白名单 comm：exec -a 钉全路径（与 068 探针同一招）
printf '%s\n' '#!/bin/sh' 'exec sleep 600' > "$BIN/grok"
chmod +x "$BIN/grok"

tmux -S "$SOCK" new-session -d -s rkeep -n rf-keep "exec -a $BIN/grok sleep 600" \
  || fail "new-session 失败"
SESS="$(tmux -S "$SOCK" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS" | grep -qx rkeep || fail "自检失败：会话不在隔离 socket（got='$SESS'）"
tmux -S "$SOCK" select-pane -t rkeep:0.0 -T "idle keep - grok"

echo "building isolated agentmirrord..."
( cd "$SERVER" && go build -o "$BIN_DAEMON" ./cmd/agentmirrord ) || fail "go build 失败"
PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
[ "$PORT" != "9900" ] || fail "拒绝 9900"

# 拉长一级 cadence，避免 listingLoop 在两次 list 之间偷偷重扫把 ① 假绿
export AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$SOCK_DIR"
export AGENTMIRROR_STATE_DIR="$STATE"
export AGENTMIRROR_TOKEN="rf-probe-token"
export AGENTMIRROR_LISTEN="127.0.0.1:${PORT}"
export AGENTMIRROR_LIST_INTERVAL="60s"
unset TS_AUTHKEY
: >"$DAEMON_LOG"
# 🔴 收尾必须挂在 trap 上：脚本以前只在 fail 路径清理，成功路径 exit 0 时把 daemon 留成孤儿。
# 后果不只是脏——孤儿继承着调用方的 stdout 管道，**调用方永远等不到 EOF**，
# 于是「探针 ALL PASS 了但判据挂死到超时」。一天下来攒了 12 个孤儿，最老的跑了 6h10m。
# `</dev/null` 一并断开 stdin，别让它攥着任何继承来的 fd。
cleanup_daemon() { [ -n "${DAEMON_PID:-}" ] && kill "$DAEMON_PID" 2>/dev/null; }
trap cleanup_daemon EXIT INT TERM
"$BIN_DAEMON" >"$DAEMON_LOG" 2>&1 </dev/null &
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
export PROBE_TOKEN="rf-probe-token"
export PROBE_SOCK="$SOCK"
export PROBE_GROK="$BIN/grok"
node --input-type=module - <<'NODE'
const url = process.env.PROBE_URL;
const token = process.env.PROBE_TOKEN;
const sock = process.env.PROBE_SOCK;
const grok = process.env.PROBE_GROK;
const { spawnSync } = await import('node:child_process');

function send(ws, type, payload) {
  ws.send(JSON.stringify({ v: 1, type, payload }));
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
function namesOf(listing) {
  const out = [];
  for (const w of listing.payload?.workspaces || []) {
    for (const s of w.sessions || []) out.push(s.name);
    if (typeof w.session_count === 'number') out.push('count=' + w.session_count);
  }
  return out;
}
function sessionCount(listing) {
  let n = 0;
  for (const w of listing.payload?.workspaces || []) n += (w.sessions || []).length || w.session_count || 0;
  return n;
}

const ws = new WebSocket(url);
const inbox = [];
ws.addEventListener('message', (ev) => {
  try { inbox.push({ t: Date.now(), f: JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString()) }); }
  catch { /* ignore */ }
});
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('ws open timeout')), 5000);
  ws.addEventListener('open', () => { clearTimeout(t); resolve(); });
  ws.addEventListener('error', () => { clearTimeout(t); reject(new Error('ws error')); });
});
send(ws, 'auth', { token });
const ack = await waitFrame(ws, (f) => f.type === 'auth_ack', 5000, 'auth_ack');
if (!ack.payload || ack.payload.ok !== true) throw new Error('auth rejected');

// —— ① 先吃一次 list 建缓存 ——
send(ws, 'list', { req_id: 1 });
const list1 = await waitFrame(ws, (f) => f.type === 'listing' && f.payload?.req_id === 1, 5000, 'listing1');
const n1 = sessionCount(list1);
const names1 = namesOf(list1);
console.log('list1 count=' + n1 + ' names=' + JSON.stringify(names1));
if (n1 < 1) throw new Error('夹具 grok 没进列表，无法测刷新');

// 无人订阅二级。一级 listingLoop cadence=60s，不会在中间救场。
const add = spawnSync('tmux', ['-S', sock, 'new-session', '-d', '-s', 'radd', '-n', 'rf-added',
  `exec -a ${grok} sleep 600`], { encoding: 'utf8' });
if (add.status !== 0) throw new Error('new-session radd failed: ' + (add.stderr || add.stdout));
spawnSync('tmux', ['-S', sock, 'select-pane', '-t', 'radd:0.0', '-T', 'idle added - grok']);

send(ws, 'list', { req_id: 2 });
const list2 = await waitFrame(ws, (f) => f.type === 'listing' && f.payload?.req_id === 2, 5000, 'listing2');
const n2 = sessionCount(list2);
const names2 = namesOf(list2);
console.log('list2 count=' + n2 + ' names=' + JSON.stringify(names2));

const fails = [];
const sawAdded = names2.includes('rf-added') || n2 > n1;
if (!sawAdded) {
  fails.push('① list 仍是旧世界 count ' + n1 + '→' + n2);
  console.log('FAIL ① 增会话后 list 未见 rf-added（ensureInitialScan 缓存）');
} else {
  console.log('PASS ① list 反映新世界');
}

// ③ 两次 listing 都非空（缓存优先，不得空帧）
const l1empty = (list1.payload?.workspaces || []).length === 0 || n1 === 0;
const l2empty = (list2.payload?.workspaces || []).length === 0 || n2 === 0;
if (l1empty || l2empty) {
  fails.push('③ 出现空 listing');
  console.log('FAIL ③ empty listing l1=' + l1empty + ' l2=' + l2empty);
} else {
  console.log('PASS ③ listing 进入瞬间非空');
}

// —— ② 无人订阅时改状态，再进二级 ——
spawnSync('tmux', ['-S', sock, 'select-pane', '-t', 'rkeep:0.0', '-T', 'was idle - grok']);
send(ws, 'level2_unsubscribe', {});
await new Promise((r) => setTimeout(r, 150));

// ④ 窗口：退订后 2.5s 不得再来 level2_frame
const tQuiet0 = Date.now();
await new Promise((r) => setTimeout(r, 2500));
const quiet = inbox.filter((x) => x.t >= tQuiet0 && (x.f.type === 'level2_frame' || x.f.type === 'level2_heartbeat'));
if (quiet.length) {
  fails.push('④ 退订后仍有 ' + quiet.length + ' 条 level2 推送');
  console.log('FAIL ④ 零订阅仍推送');
} else {
  console.log('PASS ④ 退订后 2.5s 无 level2 帧');
}

spawnSync('tmux', ['-S', sock, 'select-pane', '-t', 'rkeep:0.0', '-T', '⠋ now working - grok']);
const tEnter = Date.now();
send(ws, 'level2_subscribe', { workspace: (list1.payload.workspaces[0] || {}).cwd || '' });
let firstL2 = null;
try {
  firstL2 = await waitFrame(ws, (f) => f.type === 'level2_frame', 2500, 'l2 after enter');
} catch (e) {
  fails.push('② 进入二级 2.5s 内无 level2_frame');
  console.log('FAIL ② ' + e.message);
}
if (firstL2) {
  const dt = Date.now() - tEnter;
  const sess = firstL2.payload?.sessions || [];
  const keep = sess.find((s) => s.name === 'rf-keep') || sess[0];
  const st = keep?.status;
  const empty = sess.length === 0;
  console.log('l2 first dt_ms=' + dt + ' status=' + st + ' n=' + sess.length + ' title=' + JSON.stringify(keep?.title));
  if (empty) {
    fails.push('③ 二级首帧空列表');
    console.log('FAIL ③ 二级首帧 sessions=[]');
  } else {
    console.log('PASS ③ 二级首帧非空');
  }
  if (st !== 'working') {
    fails.push('② 首帧 status=' + st + ' want working');
    console.log('FAIL ② 首个非缓存帧不是新状态');
  } else if (dt >= 2000) {
    fails.push('② 等到 ' + dt + 'ms ≥ 2s cadence');
    console.log('FAIL ② 等满了 cadence');
  } else {
    console.log('PASS ② 首帧 working 且 dt=' + dt + 'ms < 2000');
  }
}

if (fails.length) {
  console.log('RED ' + fails.join(' | '));
  process.exit(1);
}
console.log('probe-rf ALL PASS');
// 🔴 成功路径必须显式退出：失败路径有 process.exit(1)，成功路径没有，
// 而 WebSocket 还连着 ⇒ 事件循环不空 ⇒ node 不退 ⇒ 调用它的 bash 不退 ⇒
// 判据的父进程永远等不到 EOF。表现是「探针 ALL PASS 了，判据却挂死到超时」。
process.exit(0);
NODE
if [ $? -ne 0 ]; then
  fail "即时刷新探针未全绿（① 当前必红）"
fi
pass "四条断言全过"
exit 0
