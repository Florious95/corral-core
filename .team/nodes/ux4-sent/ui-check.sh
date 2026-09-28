#!/usr/bin/env bash
# ui-check.sh — t.sent 「已发送」节点数（退出码即判据）
# 双 density：480（整数 3.0）与 440（非整数 2.75）。
# 先证明消息真发出去了，再数「已发送」节点 == 0；再构造发送失败仍可见。
# trap 收尾：IME 111 + wm density reset。不留后台。不碰用户 tmux。不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="/Volumes/nvme/Projects/远程Agent安卓"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.agentmirror.app
ACTIVITY=dev.agentmirror.app.MainActivity
TOKEN_FILE="${TOKEN_FILE:-/tmp/e2e-sent/token}"
TMUX_TMPDIR_ISO="${TMUX_TMPDIR_ISO:-/tmp/e2e-sent/tmux}"
DAEMON_PID_FILE="${DAEMON_PID_FILE:-/tmp/e2e-sent/daemon.pid}"
DAEMON_BIN="${DAEMON_BIN:-/tmp/e2e-sent/agentmirrord}"
DAEMON_STATE="${DAEMON_STATE:-/tmp/e2e-sent/state}"
DAEMON_UPLOAD="${DAEMON_UPLOAD:-/tmp/e2e-sent/uploads}"
DAEMON_SOCKDIR="${DAEMON_SOCKDIR:-/private/tmp/e2e-sent/tmux/tmux-501}"

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -x "$ADB" ] || fail "adb 不在 $ADB"
[ -f "$ROOT/tools/uiassert.py" ] || fail "找不到 uiassert.py"
[ -f "$TOKEN_FILE" ] || fail "缺配对 token 文件"
serial="$("$ADB" get-serialno 2>/dev/null | tr -d '\r' || true)"
[ "$serial" = "emulator-5554" ] || fail "设备必须是 emulator-5554，实际='$serial'"
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true

# 源码钉死：成功态不得再组「已发送」字面量，失败态还在。
SRC="$ROOT/app/app/src/main/java/dev/agentmirror/app/session/SessionScreen.kt"
grep -q 'is InputStatus.Sent -> null' "$SRC" || fail "成功态「已发送」还在 StatusArea"
if grep -q '"已发送"' "$SRC"; then
  fail "SessionScreen 仍有「已发送」文案"
fi
grep -q 'InputStatus.Failed' "$SRC" || fail "失败态被删了"
if grep -E 'AnimatedVisibility\(' "$SRC" >/dev/null; then
  fail "StatusArea 仍用 AnimatedVisibility 藏节点"
fi

export SENT_NODE="$NODE" SENT_ROOT="$ROOT" SENT_TOKEN_FILE="$TOKEN_FILE"
export SENT_TMUX_TMPDIR="$TMUX_TMPDIR_ISO" PKG ACTIVITY ADB
export SENT_DAEMON_PID_FILE="$DAEMON_PID_FILE" SENT_DAEMON_BIN="$DAEMON_BIN"
export SENT_DAEMON_STATE="$DAEMON_STATE" SENT_DAEMON_UPLOAD="$DAEMON_UPLOAD"
export SENT_DAEMON_SOCKDIR="$DAEMON_SOCKDIR"
python3 - <<'PY'
import os, re, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["SENT_NODE"]
pkg = os.environ["PKG"]
activity = os.environ["ACTIVITY"]
token = open(os.environ["SENT_TOKEN_FILE"], "r", encoding="utf-8").read().strip()
tmux_tmpdir = os.environ["SENT_TMUX_TMPDIR"]
chrome = {
    "‹", "查看", "LAN", "tailnet", "Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C", "+", "＋",
    "❯", "输入指令…", "发送", "收藏", "会话", "设置", "工作区", "‹ 返回", "‹ 工作区",
    "当前", "进行中", "空闲", "未知", "切换会话", "★", "☆", "拍照", "从相册选择", "☰", "⚙",
}

def fail(msg):
    print("FAIL " + msg)
    sys.exit(1)

def sh(*args, timeout=90):
    subprocess.check_call(list(args), timeout=timeout)

def dump():
    r = subprocess.run(
        [adb, "exec-out", "uiautomator", "dump", "/dev/tty"],
        capture_output=True, timeout=60,
    )
    x = (r.stdout or b"").decode("utf-8", "replace")
    if "<hierarchy" not in x:
        fail("取不到 UI 树")
    return x

def texts(x):
    return [t for t in re.findall(r'text="([^"]*)"', x) if t.strip()]

def nodes(x):
    out = []
    for m in re.finditer(r"<node\b[^>]*>", x):
        tag = m.group(0)
        tm = re.search(r'text="([^"]*)"', tag)
        cd = re.search(r'content-desc="([^"]*)"', tag)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not bm:
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        t = tm.group(1) if tm else ""
        d = cd.group(1) if cd else ""
        out.append((t, d, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2, x1, x2))
    return out

def count_sent(x):
    n = 0
    for t, d, *_ in nodes(x):
        if "已发送" in t or "已发送" in d:
            n += 1
    return n

def tap(cx, cy, wait=1.0):
    sh(adb, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(wait)

def key(code):
    sh(adb, "shell", "input", "keyevent", str(code))
    time.sleep(0.15)

def type_text(s):
    sh(adb, "shell", "input", "text", s.replace(" ", "%s"))
    time.sleep(0.25)

def on_pairing(ts):
    return "连接主机" in ts or any("手填" in t for t in ts)

def on_session(ts):
    return "查看" in ts and "Esc" in ts

def screencap(path):
    r = subprocess.run([adb, "exec-out", "screencap", "-p"], capture_output=True, timeout=20)
    open(path, "wb").write(r.stdout or b"")
    print("SHOT", path, len(r.stdout or b""))

def restart_daemon():
    token = open(os.environ["SENT_TOKEN_FILE"], "r", encoding="utf-8").read().strip()
    env = os.environ.copy()
    env["TMUX_TMPDIR"] = os.environ["SENT_TMUX_TMPDIR"]
    env["AGENTMIRROR_TOKEN"] = token
    env["AGENTMIRROR_STATE_DIR"] = os.environ["SENT_DAEMON_STATE"]
    env["AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS"] = os.environ["SENT_DAEMON_SOCKDIR"]
    logf = open("/tmp/e2e-sent/daemon.log", "ab")
    p = subprocess.Popen(
        [
            os.environ["SENT_DAEMON_BIN"],
            "-listen", "0.0.0.0:9900",
            "-upload-dir", os.environ["SENT_DAEMON_UPLOAD"],
            "-log-level", "debug",
            "-list-interval", "500ms",
        ],
        cwd=os.environ["SENT_ROOT"],
        env=env,
        stdout=logf,
        stderr=logf,
        start_new_session=True,
    )
    open(os.environ["SENT_DAEMON_PID_FILE"], "w").write(str(p.pid))
    for _ in range(25):
        s = __import__("socket").socket()
        s.settimeout(0.3)
        try:
            s.connect(("127.0.0.1", 9900))
            s.close()
            print("DAEMON_UP pid=%d" % p.pid)
            return
        except OSError:
            time.sleep(0.2)
    fail("隔离 daemon 没起来")

def tmux_capture():
    env = os.environ.copy()
    env["TMUX_TMPDIR"] = tmux_tmpdir
    env.pop("TMUX", None)
    r = subprocess.run(
        ["tmux", "capture-pane", "-p"],
        capture_output=True, text=True, env=env, timeout=10,
    )
    return r.stdout or ""

def go_session():
    x = dump()
    ts = texts(x)
    if on_session(ts):
        return
    if on_pairing(ts):
        ns = nodes(x)
        hand = [n for n in ns if "手填" in n[0]]
        if hand:
            tap(hand[0][2], hand[0][3], 0.9)
            x = dump()
        edits = []
        for m in re.finditer(r"<node\b[^>]*>", x):
            tag = m.group(0)
            cls = re.search(r'class="([^"]*)"', tag)
            bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
            if cls and bm and "EditText" in cls.group(1):
                x1, y1, x2, y2 = map(int, bm.groups())
                edits.append(((x1 + x2) // 2, (y1 + y2) // 2))
        if len(edits) < 2:
            fail("配对页缺输入框 ts=%s" % texts(x))
        tap(edits[0][0], edits[0][1], 0.3)
        type_text("ws://127.0.0.1:9900/ws")
        key(111)
        tap(edits[1][0], edits[1][1], 0.3)
        type_text(token)
        key(111)
        x = dump()
        conn = [n for n in nodes(x) if n[0] == "连接"]
        if not conn:
            fail("找不到连接")
        tap(conn[0][2], conn[0][3], 2.0)
        key(111)
        ok = False
        for i in range(25):
            if not on_pairing(texts(dump())):
                ok = True
                break
            time.sleep(0.6)
        if not ok:
            fail("配对后仍在配对页")
        x = dump()
        ts = texts(x)
    tabs = [n for n in nodes(x) if n[0] == "会话"]
    if tabs:
        tabs.sort(key=lambda n: n[4])
        tap(tabs[-1][2], tabs[-1][3], 1.0)
        x = dump()
        ts = texts(x)
    if on_session(ts):
        return
    # L1 工作区 → L2 → 会话
    row = None
    ns = nodes(x)
    for n in ns:
        t = n[0]
        if t in chrome or not t:
            continue
        if "WORKSPACES" in t or "SESSIONS" in t:
            continue
        if t in ("cwd",) or t.startswith("/"):
            row = n
            break
    if row is None:
        for n in ns:
            if n[0] and n[0] not in chrome and "WORKSPACE" not in n[0]:
                row = n
                break
    if row is None:
        fail("列表没有可点行 ts=%s" % ts)
    if "claude" not in ts:
        tap(row[2], row[3], 1.6)
        x = dump()
        ts = texts(x)
    if on_session(ts):
        return
    hits = [n for n in nodes(x) if n[0] == "claude"]
    if not hits:
        fail("二级没有 claude ts=%s" % ts)
    tap(hits[0][2], hits[0][3], 1.8)
    if not on_session(texts(dump())):
        fail("没进会话页 ts=%s" % texts(dump()))

def send_marker(marker):
    x = dump()
    ns = nodes(x)
    ups = [n for n in ns if n[0] == "↑"]
    if not ups:
        fail("找不到发送键 ts=%s" % texts(x))
    send = max(ups, key=lambda n: (n[4], n[6]))
    draft = [n for n in ns if n[0] == "输入指令…"]
    if draft:
        tap(draft[0][2], draft[0][3], 0.35)
    else:
        tap(send[2] - 470, send[3], 0.35)
    type_text(marker)
    tap(send[2], send[3], 0.2)
    return send

def run_density(tag, dpi):
    sh(adb, "shell", "wm", "density", str(dpi))
    time.sleep(0.8)
    sh(adb, "shell", "am", "force-stop", pkg)
    time.sleep(0.3)
    sh(adb, "shell", "am", "start", "-W", "-n", "%s/%s" % (pkg, activity))
    time.sleep(1.2)
    key(111)
    go_session()
    marker = "UX4SENT%s" % tag
    before_pane = tmux_capture()
    before_n = count_sent(dump())
    send_marker(marker)
    # screencap 比 dump 快，落在 1.2s 成功态窗口内；dump 仍要数节点。
    screencap(os.path.join(node, "shot-after-%s.png" % tag))
    x = dump()
    after_n = count_sent(x)
    open(os.path.join(node, "ui-tree-%s.xml" % tag), "w", encoding="utf-8").write(x)
    pane = tmux_capture()
    sent_ok = marker in pane
    print(
        "DENSITY %s before_sent_nodes=%d after_sent_nodes=%d marker_in_pane=%s"
        % (tag, before_n, after_n, sent_ok)
    )
    if not sent_ok:
        fail("%s 终端没出现发出的 %s pane=%r" % (tag, marker, pane[-400:]))
    if after_n != 0:
        fail("%s 「已发送」节点数=%d 期望 0" % (tag, after_n))

    # 失败态：停掉本席隔离 daemon（不碰生产），立刻再发。失败态 hold=3.6s，dump 赶得上。
    pid_path = os.environ["SENT_DAEMON_PID_FILE"]
    try:
        dpid = int(open(pid_path).read().strip())
    except (OSError, ValueError):
        fail("读不到隔离 daemon pid")
    os.kill(dpid, 15)
    for _ in range(20):
        try:
            os.kill(dpid, 0)
            time.sleep(0.1)
        except OSError:
            break
    ns = nodes(dump())
    ups = [n for n in ns if n[0] == "↑"]
    if ups:
        send = max(ups, key=lambda n: (n[4], n[6]))
        tap(send[2], send[3], 0.35)
    xfail = dump()
    open(os.path.join(node, "ui-tree-%s-fail.xml" % tag), "w", encoding="utf-8").write(xfail)
    ft = " ".join(texts(xfail))
    fail_ok = ("发送失败" in ft) or ("连接未就绪" in ft) or ("连接已断开" in ft) or ("无法发送" in ft)
    print("DENSITY %s fail_texts_has_error=%s sample=%s" % (tag, fail_ok, texts(xfail)[:16]))
    restart_daemon()
    subprocess.check_call([adb, "reverse", "tcp:9900", "tcp:9900"])
    if not fail_ok:
        fail("%s 发送失败提示没出现 ts=%s" % (tag, texts(xfail)))

# restore reverse helper used inside run_density via subprocess
for tag, dpi in (("d480", 480), ("d440", 440)):
    run_density(tag, dpi)

print("PASS sent-nodes==0 both densities; fail banner kept")
PY
echo "PASS ui-check"
