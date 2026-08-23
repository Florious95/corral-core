#!/usr/bin/env bash
# keyecho.sh [n]  —— 量「客户端把按键交给传输层 → 回显字符完成绘制」的 p50/p95。
# 远端 sink 用 cat（一对一回吐）。每次迭代不同字符 a–z 循环，凭 PerfTrace
# key_send/key_echo 的 (seq,char) 配对。只从 `adb logcat -s PerfTrace` 取数。
# 输出：.team/perf/keyecho-<时间戳>.json 与 .team/perf/keyecho-baseline.json
# 原始 logcat 写 .team/nodes/t.instr/tmp/  ⛔ 不写 .team/perf/raw/
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
N="${1:-30}"
if [ "$N" -lt 30 ]; then
  echo "FAIL n=$N < 30 —— 不许拿几个样本报 p95" >&2
  exit 1
fi

NODE="$ROOT/.team/nodes/t.armfix"
TMP="$NODE/tmp"
mkdir -p "$TMP" "$ROOT/.team/perf"
# unix socket 短路径（与 e2e/layer2 同类例外：worktree 路径超 unix socket ~104 字节上限）。
# tmux 要求 socket 目录 0700，否则报 unsafe permissions。mktemp 默认 0700。
SOCKROOT="$(mktemp -d /tmp/e2e-ke.XXXXXX)"
TMUX_ROOT="$SOCKROOT/tmux"
mkdir -p "$TMUX_ROOT"
chmod 700 "$SOCKROOT" "$TMUX_ROOT"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_TS="$ROOT/.team/perf/keyecho-$STAMP.json"
OUT_BASE="$ROOT/.team/perf/keyecho-baseline.json"
LOGCAT="$TMP/keyecho-$STAMP.logcat"
HOSTLOAD="$TMP/host-load.txt"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
[ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ] || ANDROID_SDK_ROOT="/Volumes/nvme/android-sdk"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
PKG=dev.agentmirror.app
APK="$ROOT/app/app/build/outputs/apk/debug/app-debug.apk"

load1() { uptime | sed -n 's/.*averages: \([0-9.]*\).*/\1/p' | head -1; }
mem_mib() {
  python3 - <<'PY'
import subprocess
out=subprocess.check_output(["vm_stat"], text=True)
page=4096; free=inact=0
for line in out.splitlines():
    if "page size of" in line: page=int(line.split()[-2])
    if line.startswith("Pages free:"): free=int(line.split()[-1].rstrip("."))
    if line.startswith("Pages inactive:"): inact=int(line.split()[-1].rstrip("."))
print(int((free+inact)*page/1024/1024))
PY
}
LOAD1="$(load1)"
MEM="$(mem_mib)"
{
  echo "load1=$LOAD1"
  echo "free_inactive_mib=$MEM"
  echo "uptime=$(uptime)"
} | tee "$HOSTLOAD"

python3 - "$LOAD1" "$MEM" <<'PY' || { echo "blocked_env: load1>15 且未达地板（见 $HOSTLOAD）" >&2; exit 3; }
import sys
load=float(sys.argv[1]); mem=float(sys.argv[2])
# 已有模拟器时不硬起新实例；内存不够才拒。load 超 15 只在「需要新起模拟器」时拒。
if mem < 3000:
    sys.exit(1)
sys.exit(0)
PY

CLEANUP_PID=""
cleanup() {
  if [ -n "${CLEANUP_PID:-}" ]; then
    kill "$CLEANUP_PID" 2>/dev/null || true
    sleep 0.3
    kill -9 "$CLEANUP_PID" 2>/dev/null || true
    wait "$CLEANUP_PID" 2>/dev/null || true
  fi
  if [ -n "${TMUX_ROOT:-}" ]; then
    TMUX='' TMUX_TMPDIR="$TMUX_ROOT" tmux -f /dev/null kill-server 2>/dev/null || true
  fi
  rm -rf "$SOCKROOT" 2>/dev/null || true
  if [ -n "${ADB:-}" ]; then
    "$ADB" shell setprop debug.agentmirror.keyecho 0 >/dev/null 2>&1 || true
    "$ADB" shell am force-stop "${PKG:-dev.agentmirror.app}" >/dev/null 2>&1 || true
    if [ -n "${PORT:-}" ]; then
      "$ADB" reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

# --- APK ---
if [ ! -f "$APK" ]; then
  echo "=== assembleDebug ==="
  (cd "$ROOT/app" && ./gradlew :app:assembleDebug --offline) || \
    (cd "$ROOT/app" && ./gradlew :app:assembleDebug)
fi
[ -f "$APK" ] || { echo "FAIL 无 APK $APK" >&2; exit 1; }
APK_MD5="$(md5 -q "$APK" 2>/dev/null || md5sum "$APK" | awk '{print $1}')"

# --- 模拟器（只用已在线的，⛔ 不新起）---
[ -x "$ADB" ] || { echo "FAIL 无 adb" >&2; exit 1; }
if ! "$ADB" devices | grep -q $'emulator.*device'; then
  echo "blocked_env: 无在线模拟器且 load1=$LOAD1 不许新起（门槛 load1<=15）" >&2
  exit 3
fi

# --- 隔离 tmux + cat（TMUX_TMPDIR 短路径；自检必须打在自己的 socket 上）---
unset TMUX
export -n TMUX_TMPDIR 2>/dev/null || true
CWD="$TMP/cwd"
mkdir -p "$CWD"
# 产品 listing 按进程 comm 白名单滤掉非 Agent CLI（filterModel）。
# sink 仍必须是 cat（一对一回吐）。做法：/bin/cat 以 argv0=claude 的符号链接 exec，
# 不改产品行为，只让白名单认出门面。参照 e2e「假 CLI ln -s …/claude」。
FAKEBIN="$TMP/bin"
mkdir -p "$FAKEBIN"
ln -sfn /bin/cat "$FAKEBIN/claude"
export PATH="$FAKEBIN:$PATH"
DISC_DIR="$TMUX_ROOT/tmux-$(id -u)"
mkdir -p "$DISC_DIR"
chmod 700 "$DISC_DIR"
TMUX='' TMUX_TMPDIR="$TMUX_ROOT" tmux -f /dev/null kill-server 2>/dev/null || true
TMUX='' TMUX_TMPDIR="$TMUX_ROOT" PATH="$PATH" tmux -f /dev/null new-session -d -s keyecho_cat -c "$CWD" "exec claude" || {
  echo "FAIL 建隔离 tmux 失败" >&2; exit 1
}
SESS="$(TMUX='' TMUX_TMPDIR="$TMUX_ROOT" tmux -f /dev/null list-sessions 2>/dev/null || true)"
echo "$SESS" | grep -q keyecho_cat || { echo "FAIL tmux 自检：会话不在自己的 socket 上 TMUX_TMPDIR=$TMUX_ROOT sess=$SESS" >&2; exit 1; }
# 自检：socket 必须在隔离目录，绝不能落到 /tmp/tmux-<uid>（用户真实舰队）
SOCK="$(echo "$DISC_DIR"/default)"
[ -S "$SOCK" ] || SOCK="$(ls -1 "$DISC_DIR" 2>/dev/null | head -1 | sed "s|^|$DISC_DIR/|")"
case "$SOCK" in
  /tmp/tmux-*|/private/tmp/tmux-*) echo "FAIL tmux 回退到真实 socket SOCK=$SOCK" >&2; exit 1 ;;
esac
TMUX='' TMUX_TMPDIR="$TMUX_ROOT" tmux -f /dev/null rename-window -t keyecho_cat:0 keyecho_cat 2>/dev/null || true
echo "=== tmux selfcheck SOCK=$SOCK DISC_DIR=$DISC_DIR ==="
TMUX='' TMUX_TMPDIR="$TMUX_ROOT" tmux -f /dev/null list-sessions

# --- 隔离 daemon（避开 9900 生产与本机已占用的 19900）---
TOKEN="KEYECHO$(date +%s | tail -c 6)"
STATE="$TMP/daemon-state"
mkdir -p "$STATE" "$TMP/uploads"
DAEMON=""
for cand in "$TMP/agentmirrord" "$ROOT/e2e/bin/agentmirrord" "$ROOT/server/agentmirrord"; do
  [ -x "$cand" ] && DAEMON="$cand" && break
done
if [ -z "$DAEMON" ]; then
  echo "=== go build agentmirrord ==="
  (cd "$ROOT/server" && go build -o "$TMP/agentmirrord" ./cmd/agentmirrord) || {
    echo "FAIL 编不出 agentmirrord" >&2; exit 1
  }
  DAEMON="$TMP/agentmirrord"
fi
# 只扫隔离 socket 目录。⛔ 不设这个 env 会走 DefaultSocketDirs，把用户真实 tmux 扫进去。
# TMUX_TMPDIR 必须是 tmux 真正用的那层（…/tmux），socket 在 $TMUX_TMPDIR/tmux-<uid>/。
export TMUX_TMPDIR="$TMUX_ROOT"
export AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$DISC_DIR"
# 避开已被占用的端口（本机 19900 是别的工程的 repro daemon，不许动；9900 是生产）。
pick_port() {
  local p
  for p in 19917 19918 19919 19921 19923 19927; do
    if ! (echo >/dev/tcp/127.0.0.1/$p) 2>/dev/null; then
      echo "$p"; return 0
    fi
  done
  return 1
}
PORT="$(pick_port)" || { echo "FAIL 19917–19927 都被占，不许抢 9900/19900" >&2; exit 1; }
echo "=== daemon listen 0.0.0.0:$PORT (isolated, not 9900) ==="
AGENTMIRROR_TOKEN="$TOKEN" AGENTMIRROR_STATE_DIR="$STATE" \
  AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS="$DISC_DIR" \
  TMUX_TMPDIR="$TMUX_ROOT" \
  "$DAEMON" -listen "0.0.0.0:$PORT" -upload-dir "$TMP/uploads" \
  -log-level debug -list-interval 500ms >"$TMP/daemon.log" 2>&1 &
CLEANUP_PID=$!
ok=0
for i in $(seq 1 30); do
  (echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && { ok=1; break; }
  sleep 0.2
done
[ "$ok" = 1 ] || { echo "FAIL daemon 没在 $PORT 听（不是 9900）"; tail -20 "$TMP/daemon.log"; exit 1; }

"$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 || true
# 模拟器访问宿主机：layer2 实证路径是 10.0.2.2；同时 reverse 让 127.0.0.1 也可达。
WSURL="ws://10.0.2.2:$PORT/ws"

# --- 安装并配对 ---
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell pm clear "$PKG" >/dev/null 2>&1
"$ADB" shell setprop debug.agentmirror.perftrace 1 >/dev/null 2>&1 || true
# 按键回显独立开关（默认关）。必须在进程启动前打开，否则 onAsciiPrint 保持 null、量不到。
"$ADB" shell setprop debug.agentmirror.keyecho 1 >/dev/null 2>&1 || true
"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 4

dumpui() { "$ADB" shell "uiautomator dump /sdcard/ke.xml >/dev/null 2>&1; cat /sdcard/ke.xml" 2>/dev/null; }
tap_text() {
  local cx cy
  read cx cy < <(python3 - "$1" "$2" <<'PY'
import re, sys
xml=open(sys.argv[1], encoding="utf-8", errors="replace").read()
want=sys.argv[2]
for m in re.finditer(r"<node[^>]*/?>", xml):
    n=m.group(0)
    t=re.search(r'text="([^"]*)"', n)
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1)==want:
        x1,y1,x2,y2=map(int,b.groups())
        print((x1+x2)//2,(y1+y2)//2); break
PY
)
  [ -n "${cx:-}" ] || return 1
  "$ADB" shell input tap "$cx" "$cy" >/dev/null
}
edit_center() {
  python3 - "$1" "$2" <<'PY'
import re, sys
xml=open(sys.argv[1], encoding="utf-8", errors="replace").read()
idx=int(sys.argv[2]); n=0
for m in re.finditer(r"<node[^>]*/?>", xml):
    node=m.group(0)
    cls=re.search(r'class="([^"]*)"', node)
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if cls and "EditText" in cls.group(1) and b:
        if n==idx:
            x1,y1,x2,y2=map(int,b.groups())
            print((x1+x2)//2,(y1+y2)//2); break
        n+=1
PY
}

UI="$(dumpui)"; echo "$UI" > "$TMP/pair.xml"
# 手填连接（精确等于，避免「连接」命中「连接主机」）
tap_text "$TMP/pair.xml" "手填连接" || true
sleep 1
UI="$(dumpui)"; echo "$UI" > "$TMP/pair.xml"
read CX0 CY0 < <(edit_center "$TMP/pair.xml" 0) || { echo "FAIL 无 URL 框"; exit 1; }
"$ADB" shell input tap "$CX0" "$CY0"; sleep 1
"$ADB" shell input text "$WSURL" >/dev/null
sleep 1
UI="$(dumpui)"; echo "$UI" > "$TMP/pair-url.xml"
grep -qF "$WSURL" "$TMP/pair-url.xml" || { echo "FAIL URL 没填进框 WSURL=$WSURL"; exit 1; }
read CX1 CY1 < <(edit_center "$TMP/pair-url.xml" 1) || { echo "FAIL 无 token 框"; exit 1; }
"$ADB" shell input tap "$CX1" "$CY1"; sleep 1
"$ADB" shell input text "$TOKEN" >/dev/null
sleep 1
UI="$(dumpui)"; echo "$UI" > "$TMP/pair.xml"
# token 框是 password（dump 只见 ••••），不能按明文 TOKEN 搜。禁止 URL+token 拼进同一框。
python3 - "$TMP/pair.xml" "$WSURL" "$TOKEN" <<'PY' || { echo "FAIL 表单回填不对（URL 缺失或与 token 拼栏）"; exit 1; }
import sys
xml=open(sys.argv[1], encoding="utf-8", errors="replace").read()
url, tok = sys.argv[2], sys.argv[3]
if url not in xml:
    sys.exit(1)
if (url+tok) in xml:
    sys.exit(1)
sys.exit(0)
PY
tap_text "$TMP/pair.xml" "连接" || { echo "FAIL 连不上（无「连接」按钮）"; exit 1; }

# 点「包含该精确 text 的最小 clickable 祖先」——Compose 行上 text 节点往往 clickable=false。
tap_clickable_row() {
  python3 - "$1" "$2" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want = sys.argv[2]
nodes = []
for m in re.finditer(r"<node[^>]*/?>", xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    c = re.search(r'clickable="(true|false)"', n)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    nodes.append((x1, y1, x2, y2, t.group(1) if t else "", c.group(1) if c else "false"))
hits = [n for n in nodes if n[4] == want]
if not hits:
    sys.exit(1)
leaf = hits[0]
best = None
for a in nodes:
    if a[5] != "true":
        continue
    if a[0] <= leaf[0] and a[1] <= leaf[1] and a[2] >= leaf[2] and a[3] >= leaf[3]:
        area = (a[2] - a[0]) * (a[3] - a[1])
        if best is None or area < best[0]:
            best = (area, a)
tgt = best[1] if best else leaf
print((tgt[0] + tgt[2]) // 2, (tgt[1] + tgt[3]) // 2)
PY
}

CWD_REAL="$(cd "$CWD" && pwd -P)"
CWD_LABEL="$(basename "$CWD_REAL")"

# 等配对页走掉（探针 WS 会马上 client close；列表可能是探针带过来的缓存行）。
ok=0
for i in $(seq 1 40); do
  sleep 1
  UI="$(dumpui)"; echo "$UI" > "$TMP/after-pair.xml"
  if grep -q "工作区" "$TMP/after-pair.xml" && ! grep -q "连接主机" "$TMP/after-pair.xml"; then
    ok=1; break
  fi
done
[ "$ok" = 1 ] || { echo "FAIL 配对后未进工作区列表"; exit 1; }

# 配对探针 stop() 会关掉第一条 WS。冷启走 ServiceWire 持久连接
# （与 instrperf2 coldopen 同路径），否则点进二级是空的。
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
sleep 2
"$ADB" shell setprop debug.agentmirror.perftrace 1 >/dev/null 2>&1 || true
"$ADB" shell setprop debug.agentmirror.keyecho 1 >/dev/null 2>&1 || true
"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3

# 只看到 cwd 名还不够——那是缓存行，点进去二级是空的（instrperf2 复现）。
# 等到计数里真有会话再点。
ok=0
for i in $(seq 1 45); do
  UI="$(dumpui)"; echo "$UI" > "$TMP/list.xml"
  if grep -q "工作区" "$TMP/list.xml" && grep -qE '[1-9] SESSIONS' "$TMP/list.xml" && \
     { grep -qF "$CWD_REAL" "$TMP/list.xml" || grep -q "text=\"$CWD_LABEL\"" "$TMP/list.xml" || grep -q keyecho_cat "$TMP/list.xml"; }; then
    ok=1; break
  fi
  sleep 1
done
[ "$ok" = 1 ] || { echo "FAIL 冷启后列表不见隔离 cwd=$CWD_REAL 或 1 SESSIONS"; exit 1; }

unset CX CY
read CX CY < <(tap_clickable_row "$TMP/list.xml" "$CWD_LABEL") || \
  read CX CY < <(tap_clickable_row "$TMP/list.xml" "$CWD_REAL") || true
if [ -n "${CX:-}" ]; then
  "$ADB" shell input tap "$CX" "$CY" >/dev/null
else
  tap_text "$TMP/list.xml" "$CWD_REAL" || tap_text "$TMP/list.xml" "$CWD_LABEL" || {
    echo "FAIL 点不开 cwd 行"; exit 1
  }
fi

ok=0
for i in $(seq 1 30); do
  sleep 1
  UI="$(dumpui)"; echo "$UI" > "$TMP/ws.xml"
  if grep -q 'text="keyecho_cat"' "$TMP/ws.xml"; then
    ok=1; break
  fi
done
[ "$ok" = 1 ] || { echo "FAIL 二级页未出现会话行 keyecho_cat"; exit 1; }
unset CX CY
read CX CY < <(tap_clickable_row "$TMP/ws.xml" "keyecho_cat") || true
if [ -n "${CX:-}" ]; then
  "$ADB" shell input tap "$CX" "$CY" >/dev/null
else
  tap_text "$TMP/ws.xml" "keyecho_cat" || { echo "FAIL 点不开会话 keyecho_cat"; exit 1; }
fi
sleep 4

# 必须进会话页（顶栏 + 输入条），否则会把键打到列表搜索框
UI="$(dumpui)"; echo "$UI" > "$TMP/sess.xml"
python3 - "$TMP/sess.xml" <<'PY' || { echo "FAIL 未进会话页"; exit 1; }
import re, sys
xml=open(sys.argv[1], encoding="utf-8", errors="replace").read()
has_title = "keyecho_cat" in xml
has_back = 'content-desc="返回"' in xml or "返回" in xml
has_keys = "Ctrl-C" in xml or "输入指令" in xml
has_edit = "EditText" in xml
sys.exit(0 if (has_title and has_edit and (has_back or has_keys)) else 1)
PY

# 清 logcat，开始打键。先收 IME（111=ESCAPE，不用 BACK），避免组合期 hold keys=0。
"$ADB" logcat -c >/dev/null 2>&1
read IX IY < <(edit_center "$TMP/sess.xml" 0) || { echo "FAIL 会话页无输入框"; exit 1; }
"$ADB" shell input tap "$IX" "$IY"; sleep 0.4
"$ADB" shell input keyevent 111
sleep 0.3
"$ADB" shell input tap "$IX" "$IY"; sleep 0.3

chars=$(python3 - "$N" <<'PY'
import sys
n=int(sys.argv[1])
print("".join(chr(ord("a")+i%26) for i in range(n)))
PY
)
# 逐字符：adb input text 一次一个，避免合批配错。第 5 键抽查 PerfTrace。
for ((i=0; i<${#chars}; i++)); do
  ch="${chars:i:1}"
  "$ADB" shell input text "$ch" >/dev/null
  sleep 0.35
  if [ "$i" -eq 4 ]; then
    "$ADB" logcat -d -s PerfTrace > "$TMP/probe-5.logcat" || true
    if ! grep -q "ev=key_send" "$TMP/probe-5.logcat"; then
      echo "FAIL 打了 5 个字符 logcat 仍无 key_send（IME 组合期或没进会话输入框）" >&2
      "$ADB" logcat -d > "$TMP/probe-5-all.logcat" || true
      dumpui > "$TMP/probe-5.xml" || true
      exit 1
    fi
  fi
done
sleep 2
"$ADB" logcat -d -s PerfTrace > "$LOGCAT"

python3 - "$LOGCAT" "$OUT_TS" "$OUT_BASE" "$N" "$LOAD1" "$APK_MD5" "$LOGCAT" <<'PY'
import json, re, sys, statistics
log, out_ts, out_base, n_want, load1, apk, raw = sys.argv[1:8]
n_want=int(n_want)
text=open(log, encoding="utf-8", errors="replace").read()
sends={}
echos={}
for line in text.splitlines():
    if "ev=key_send" in line:
        mseq=re.search(r"seq=(\d+)", line); mc=re.search(r"char=([a-z])", line); mt=re.search(r"\bt=(\d+)", line)
        if mseq and mc and mt: sends[int(mseq.group(1))]=(mc.group(1), int(mt.group(1)))
    if "ev=key_echo" in line:
        mseq=re.search(r"seq=(\d+)", line); mc=re.search(r"char=([a-z])", line); mt=re.search(r"\bt=(\d+)", line)
        if mseq and mc and mt: echos[int(mseq.group(1))]=(mc.group(1), int(mt.group(1)))
samples=[]
unmatched=0
for seq,(ch,t0) in sorted(sends.items()):
    e=echos.get(seq)
    if not e or e[0]!=ch:
        unmatched += 1
        continue
    dt=e[1]-t0
    if dt<=0:
        unmatched += 1
        continue
    samples.append(dt)
n=len(samples)
def pct(xs, p):
    if not xs: return None
    ys=sorted(xs)
    k=(len(ys)-1)*p/100.0
    i=int(k); f=k-i
    if i+1<len(ys): return ys[i]*(1-f)+ys[i+1]*f
    return float(ys[i])
body={
  "n": n,
  "unmatched": unmatched,
  "load1": float(load1),
  "apk_md5": apk,
  "method": "adb logcat -s PerfTrace",
  "sink": "cat",
  "unit": "ms",
  "raw_log_path": raw,
  "samples": samples,
  "fixtures": {
    "cat": {
      "p50": pct(samples, 50),
      "p95": pct(samples, 95),
      "n": n,
      "samples": samples,
    }
  }
}
print("paired n=%d unmatched=%d p50=%s p95=%s sends=%d echos=%d" % (
    n, unmatched, body["fixtures"]["cat"]["p50"], body["fixtures"]["cat"]["p95"],
    len(sends), len(echos)))
if n < 30:
    # ⛔ 不许把不足 30 的样本写成 baseline 让判据去 FAIL；没测成就不落 json。
    open(log+".parse.txt","w").write(json.dumps(body, indent=2)+"\n")
    sys.exit(4)
open(out_ts,"w").write(json.dumps(body, indent=2)+"\n")
open(out_base,"w").write(json.dumps(body, indent=2)+"\n")
print("wrote", out_ts)
PY
echo "=== done $OUT_BASE ==="
