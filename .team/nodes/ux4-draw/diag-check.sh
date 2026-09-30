#!/usr/bin/env bash
# diag-check.sh — A-dw-diag
# 诊断日志能 grep 到 [term-draw]（onDraw 耗时），且 10s 窗口该 tag 新开行 ≤ 12
# （说明.md 声明的上限；1Hz 摘要）。trap：IME 111、wm density reset。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$NODE/../../.." && pwd)"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.agentmirror.app
ACTIVITY=dev.agentmirror.app.MainActivity

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -x "$ADB" ] || fail "adb 不在 $ADB"
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"
serial="$("$ADB" get-serialno 2>/dev/null | tr -d '\r' || true)"
[ "$serial" = "emulator-5554" ] || fail "设备必须是 emulator-5554，实际='$serial'"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null

export UX4_DRAW_NODE="$NODE" PKG ACTIVITY ADB
python3 - <<'PY'
import os, re, subprocess, sys, time
from datetime import datetime

adb = os.environ["ADB"]
node = os.environ["UX4_DRAW_NODE"]
pkg = os.environ["PKG"]
activity = os.environ["ACTIVITY"]
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发"]
skip_names = {
    "收藏", "会话", "设置", "LAN", "★", "☆", "☰", "⚙", "空闲", "进行中", "未知",
    "工作区", "查看", "Esc", "Tab", "Ctrl-C", "返回", "Idle",
}
LIMIT = 12

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
        t = tm.group(1) if tm else ""
        d = cd.group(1) if cd else ""
        label = t.strip() or d.strip()
        if not label:
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        out.append((label, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2))
    return out

def tap(cx, cy, wait=1.0):
    sh(adb, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(wait)

def on_session(ts):
    return "查看" in ts and "Esc" in ts

def on_settings(ts):
    return "诊断日志" in ts or "外观" in ts

sh(adb, "shell", "am", "force-stop", pkg)
time.sleep(0.35)
sh(adb, "shell", "am", "start", "-W", "-n", "%s/%s" % (pkg, activity))
time.sleep(1.2)
sh(adb, "shell", "input", "keyevent", "111")

def pick_row(ts, ns):
    skip = skip_names | {
        "暂无收藏", "在会话列表里点星星即可收藏。", "重连中…", "正在重连…", "❯_",
        "当前", "1", "›",
    }
    name = next((n for n in preferred if n in ts), None)
    on_l2 = "空闲" in ts or "进行中" in ts
    if name is None:
        for t in ts:
            if t in skip:
                continue
            if "重连" in t or "断开" in t:
                continue
            if t.startswith("/") or "SESSIONS" in t or "WORKSPACES" in t:
                continue
            if on_l2 and t in ("多agent协作", "cwd"):
                continue
            name = t
            break
    if name is None:
        return None
    hits = [n for n in ns if n[0] == name]
    return (hits[0][1], hits[0][2], name) if hits else None

x = dump()
ts = texts(x)
if "暂无收藏" in ts:
    tabs = [n for n in nodes(x) if n[0] == "会话"]
    if tabs:
        tabs.sort(key=lambda n: n[2])
        tap(tabs[-1][1], tabs[-1][2], 1.3)
        sh(adb, "shell", "input", "keyevent", "111")
        x = dump()
        ts = texts(x)
if not on_session(ts):
    row = pick_row(ts, nodes(x))
    if row is None:
        fail("列表没有可点行 ts=%s" % ts)
    tap(row[0], row[1], 1.5)
    sh(adb, "shell", "input", "keyevent", "111")
    x = dump()
    ts = texts(x)
if not on_session(ts):
    row = pick_row(ts, nodes(x))
    if row is None:
        fail("二级没有会话名 ts=%s" % ts)
    tap(row[0], row[1], 1.5)
    sh(adb, "shell", "input", "keyevent", "111")
    x = dump()
    ts = texts(x)
if not on_session(ts):
    fail("进不了会话页。屏上有: %s" % ts)

t0 = time.time()
for _ in range(14):
    sh(adb, "shell", "input", "swipe", "630", "1100", "630", "980", "120")
    time.sleep(0.55)
while time.time() - t0 < 10.2:
    time.sleep(0.2)
sh(adb, "shell", "input", "keyevent", "111")

x = dump()
ts = texts(x)
for _ in range(8):
    if on_settings(ts):
        break
    ns = nodes(x)
    back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 工作区", "‹ 设置", "返回") and n[2] < 600]
    if back and "诊断日志" not in ts:
        tap(back[0][1], back[0][2], 0.8)
        x = dump()
        ts = texts(x)
        continue
    sets = [n for n in nodes(x) if n[0] == "设置"]
    if not sets:
        break
    sets.sort(key=lambda n: n[2])
    tap(sets[-1][1], sets[-1][2], 0.9)
    x = dump()
    ts = texts(x)
if "诊断日志" not in texts(dump()):
    fail("到不了诊断日志。屏上有: %s" % texts(dump()))
exps = [n for n in nodes(dump()) if n[0] == "导出"]
if not exps:
    fail("找不到「导出」")
exps.sort(key=lambda n: n[2])
tap(exps[-1][1], exps[-1][2], 1.3)
sh(adb, "shell", "input", "keyevent", "4")
time.sleep(0.4)

ls = subprocess.run([adb, "shell", "run-as", pkg, "ls", "files/diag"], capture_output=True, text=True)
names = [ln.strip() for ln in (ls.stdout or "").splitlines() if ln.strip().endswith(".log")]
names.sort()
if not names:
    fail("导出目录空")
cat = subprocess.run([adb, "shell", "run-as", pkg, "cat", "files/diag/" + names[-1]], capture_output=True)
text = (cat.stdout or b"").decode("utf-8", "replace")
open(os.path.join(node, "diag-check.log"), "w", encoding="utf-8").write(text)
if "[term-draw]" not in text:
    fail("诊断日志没有 [term-draw] tag")
if "dt_us_p95=" not in text:
    fail("诊断日志 [term-draw] 没有 dt_us_p95")

times = []
for ln in text.splitlines():
    if "[term-draw]" not in ln:
        continue
    m = re.match(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})", ln)
    if not m:
        continue
    times.append(datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f"))
if not times:
    fail("有 tag 但解析不到时间戳")
last = times[-1]
win = [t for t in times if (last - t).total_seconds() <= 10.0]
print("WINDOW10 count=%d total=%d last=%s" % (len(win), len(times), last.isoformat()))
if len(win) > LIMIT:
    fail("10s 窗口 [term-draw]=%d > %d（刷屏）" % (len(win), LIMIT))
if len(win) < 1:
    fail("10s 窗口一条都没有")
print("PASS A-dw-diag")
PY
