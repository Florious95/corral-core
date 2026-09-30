#!/usr/bin/env bash
# ui-check.sh — t.dedup 诊断日志同操作数去重（退出码即判据）
#
# A-dd-ui：
#   1) 同操作数连打 [term-left-edge]：10s 窗口内该 tag ≤3
#   2) 随后 wm density 480→420：立刻多至少 1 条，且操作数与旧的不同
# 两条都在。只有前一条 = 把仪表删掉也能绿。
# trap 收尾：关输入法、wm density reset。不启动模拟器、不碰用户 tmux、不扫 argv。
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
[ -f "$ROOT/tools/uiassert.py" ] || fail "找不到 uiassert.py"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

"$ADB" shell wm density 480 >/dev/null 2>&1 || true
"$ADB" shell input keyevent 4 >/dev/null 2>&1 || true
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 0.4
"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 1.2
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

export VZ_DD_NODE="$NODE"
export VZ_DD_ROOT="$ROOT"
python3 - <<'PY'
import os, re, subprocess, sys, time
from datetime import datetime, timedelta

adb = os.environ["ADB"]
node = os.environ["VZ_DD_NODE"]
root = os.environ["VZ_DD_ROOT"]
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发", "vz-v1-chrome"]
chrome = {
    "‹", "查看", "LAN", "tailnet", "Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C", "+", "＋",
    "❯", "输入指令…", "发送", "收藏", "会话", "设置", "工作区", "‹ 返回", "‹ 工作区",
    "当前", "进行中", "空闲", "未知", "切换会话", "★", "☆", "拍照", "从相册选择",
}

def fail(msg):
    print("FAIL " + msg)
    sys.exit(1)

def sh(*args):
    subprocess.check_call(list(args))

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
        if not t.strip() and not d.strip():
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        out.append((t, d, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2, x1, x2))
    return out

def tap(cx, cy, wait=1.0):
    sh(adb, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(wait)

def back_nodes(ns):
    return [n for n in ns if (n[0] in ("‹", "‹ 返回", "‹ 工作区") or n[1] == "返回") and n[2] < 500]

def on_session(ts):
    return "查看" in ts and "Esc" in ts and "Ctrl-C" in ts

def on_settings(ts):
    return "诊断日志" in ts or "外观" in ts

def on_l1(ts):
    return any("WORKSPACES" in t for t in ts)

def on_favorites(ts):
    return any("SESSIONS ·" in t and "WORKSPACES" not in t for t in ts) and "收藏" in ts

def navigate_session():
    x = dump()
    ts = texts(x)
    for _ in range(8):
        if on_session(ts) or on_l1(ts) or on_favorites(ts):
            break
        ns = nodes(x)
        back = back_nodes(ns)
        if not back:
            break
        tap(back[0][2], back[0][3])
        x = dump()
        ts = texts(x)
    if on_session(ts):
        return dump()
    if not on_favorites(ts):
        favs = [n for n in nodes(x) if n[0] == "收藏"]
        if favs:
            favs.sort(key=lambda n: n[4])
            tap(favs[-1][2], favs[-1][3], 1.6)
            sh(adb, "shell", "input", "keyevent", "111")
            x = dump()
            ts = texts(x)
    name = next((n for n in preferred if n in ts), None)
    if name is None:
        name = next(
            (t for t in ts if t not in chrome and not t.startswith("/")
             and "SESSIONS" not in t and "WORKSPACES" not in t),
            None,
        )
    if name is None:
        fail("进不了会话：没有显示名。屏上有: %s" % ts)
    hits = [n for n in nodes(x) if n[0] == name]
    if not hits:
        fail("找不到会话行 %s。屏上有: %s" % (name, ts))
    tap(hits[0][2], hits[0][3], 1.3)
    sh(adb, "shell", "input", "keyevent", "111")
    x = dump()
    if not on_session(texts(x)):
        fail("点进后不在会话页。屏上有: %s" % texts(x))
    return x

def leave_session():
    x = dump()
    ts = texts(x)
    if not on_session(ts):
        return
    ns = nodes(x)
    back = back_nodes(ns)
    if back:
        tap(back[0][2], back[0][3], 0.9)
        sh(adb, "shell", "input", "keyevent", "111")

def go_settings():
    x = dump()
    ts = texts(x)
    for _ in range(10):
        if on_settings(ts):
            break
        ns = nodes(x)
        if on_session(ts):
            back = back_nodes(ns)
            if not back:
                fail("会话页找不到返回。屏上有: %s" % ts)
            tap(back[0][2], back[0][3], 0.9)
            x = dump()
            ts = texts(x)
            continue
        sets = [n for n in ns if n[0] == "设置"]
        if sets:
            sets.sort(key=lambda n: n[4])
            tap(sets[-1][2], sets[-1][3], 1.0)
            x = dump()
            ts = texts(x)
            continue
        back = back_nodes(ns) or [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 设置") and n[2] < 600]
        if back:
            tap(back[0][2], back[0][3], 0.8)
            x = dump()
            ts = texts(x)
            continue
        break
    if "诊断日志" not in texts(x):
        for _ in range(4):
            sh(adb, "shell", "input", "swipe", "540", "1600", "540", "500", "280")
            time.sleep(0.3)
        x = dump()
    if "诊断日志" not in texts(x):
        fail("到不了设置/诊断日志。屏上有: %s" % texts(x))
    return dump()

def export_log(tag):
    go_settings()
    exps = [n for n in nodes(dump()) if n[0] == "导出"]
    if not exps:
        fail("找不到「导出」。屏上有: %s" % texts(dump()))
    exps.sort(key=lambda n: n[4])
    tap(exps[-1][2], exps[-1][3], 1.4)
    sh(adb, "shell", "input", "keyevent", "4")
    time.sleep(0.4)
    sh(adb, "shell", "input", "keyevent", "111")
    time.sleep(0.3)
    ls = subprocess.run(
        [adb, "shell", "run-as", "dev.agentmirror.app", "ls", "files/diag"],
        capture_output=True, text=True,
    )
    names = [ln.strip() for ln in (ls.stdout or "").splitlines()
             if ln.strip().startswith("diag-") and ln.strip().endswith(".log")]
    names.sort()
    if not names:
        fail("导出后 files/diag 没有日志文件 ls=%r" % ((ls.stdout or "") + (ls.stderr or "")))
    path = "files/diag/" + names[-1]
    cat = subprocess.run(
        [adb, "shell", "run-as", "dev.agentmirror.app", "cat", path],
        capture_output=True,
    )
    text = (cat.stdout or b"").decode("utf-8", "replace")
    dest = os.path.join(node, "diag-%s.log" % tag)
    open(dest, "w", encoding="utf-8").write(text)
    print("EXPORT", tag, path, "bytes=%d lines=%d dest=%s" % (len(text), text.count("\n") + 1, dest))
    return text

TS_RE = re.compile(
    r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}) \[term-left-edge\] (.*)$"
)
OP_RE = re.compile(
    r"contentLeft=(\S+)\s+col0Origin=(\S+)\s+cellW=(\S+)\s+viewW=(\S+)"
)

def parse_edge(text):
    out = []
    for line in text.splitlines():
        m = TS_RE.match(line.strip())
        if not m:
            continue
        ts = datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f")
        msg = m.group(2)
        om = OP_RE.search(msg)
        ops = om.groups() if om else None
        out.append((ts, msg, ops, line.strip()))
    return out

def max_in_window(events, seconds=10):
    if not events:
        return 0, []
    best_n = 0
    best_slice = []
    delta = timedelta(seconds=seconds)
    for i, (t, _, _, _) in enumerate(events):
        sl = [e for e in events if t <= e[0] <= t + delta]
        if len(sl) > best_n:
            best_n = len(sl)
            best_slice = sl
    return best_n, best_slice

# --- 同操作数连打：短间隔反复进出会话，逼 Compose 重建 AndroidView ---
# dump() 很慢（~10s），插在进出之间会把 4 条记录撑出 10s 窗口，判据变白。
sh(adb, "shell", "wm", "density", "480")
time.sleep(0.8)
print("DENSITY_SET 480")
sx = navigate_session()
for _ in range(4):
    sh(adb, "shell", "input", "swipe", "630", "1100", "630", "980", "80")
    time.sleep(0.05)
sh(adb, "shell", "input", "keyevent", "111")
sns = nodes(sx)
back = back_nodes(sns)
if not back:
    fail("会话页找不到返回（content-desc=返回）。屏上有: %s" % texts(sx))
bx, by = back[0][2], back[0][3]
# 退出一次，记住会话行坐标，后面进出不再 dump
tap(bx, by, 0.7)
sh(adb, "shell", "input", "keyevent", "111")
fx = dump()
fts = texts(fx)
name = next((n for n in preferred if n in fts), None)
if name is None:
    name = next(
        (t for t in fts if t not in chrome and not t.startswith("/")
         and "SESSIONS" not in t and "WORKSPACES" not in t),
        None,
    )
if name is None:
    fail("收藏/列表没有显示名。屏上有: %s" % fts)
hits = [n for n in nodes(fx) if n[0] == name]
if not hits:
    fail("找不到会话行 %s。屏上有: %s" % (name, fts))
sx_, sy_ = hits[0][2], hits[0][3]
print("RAPID_REENTER name=%s session=(%d,%d) back=(%d,%d)" % (name, sx_, sy_, bx, by))
# 4 次进出会话：每次 factory 新 View，实例级 lastKey 清零 → 同操作数连打
for i in range(4):
    tap(sx_, sy_, 0.35)
    sh(adb, "shell", "input", "swipe", "630", "1100", "630", "980", "80")
    time.sleep(0.15)
    tap(bx, by, 0.35)
print("RAPID_REENTER done")
tap(sx_, sy_, 0.8)
sh(adb, "shell", "input", "keyevent", "111")
shot1 = os.path.join(node, "shot-d480-session.png")
subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot1, "wb"))
print("SHOT", shot1)

log1 = export_log("d480")
open(os.path.join(node, "diag-export.log"), "w", encoding="utf-8").write(log1)
edges1 = parse_edge(log1)
n10, sl = max_in_window(edges1, 10)
print("WINDOW10 count=%d events=%d" % (n10, len(edges1)))
for e in sl:
    print("  WIN", e[3])
if n10 > 3:
    fail("A-dd-ui 10s 窗口 [term-left-edge]=%d > 3（同操作数连打）" % n10)

# --- 改密度 480→420：必须立刻多出操作数不同的新记录 ---
sh(adb, "shell", "wm", "density", "420")
time.sleep(1.6)
sh(adb, "shell", "input", "keyevent", "111")
print("DENSITY_SET 420")
navigate_session()
sh(adb, "shell", "input", "swipe", "630", "1100", "630", "980", "120")
time.sleep(0.6)
sh(adb, "shell", "input", "keyevent", "111")
shot2 = os.path.join(node, "shot-d420-session.png")
subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot2, "wb"))
print("SHOT", shot2)

log2 = export_log("d420")
edges2 = parse_edge(log2)
old_ops = {e[2] for e in edges1 if e[2]}
new_diff = [e for e in edges2 if e[2] and e[2] not in old_ops]
print("AFTER_DENSITY total=%d old_ops=%s new_diff=%d" % (len(edges2), sorted(old_ops), len(new_diff)))
for e in new_diff:
    print("  NEW", e[3])
if not edges1:
    fail("A-dd-ui 改密度前一条 [term-left-edge] 都没有——仪表被整段关掉了")
if len(new_diff) < 1:
    fail(
        "A-dd-ui 480→420 后必须立刻多至少 1 条操作数不同的 [term-left-edge] "
        "before=%d after=%d old_ops=%s"
        % (len(edges1), len(edges2), sorted(old_ops))
    )

print("PASS t.dedup A-dd-ui window10=%d new_diff=%d" % (n10, len(new_diff)))
PY
