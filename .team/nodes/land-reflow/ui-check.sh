#!/usr/bin/env bash
# ui-check.sh — t.reflow 后台久置回前台 cols 协商（退出码即判据）
#
# A-rf-cols：按 081 五步（会话页 → HOME → ≥60s → 回前台）后，导出诊断日志里
# 回前台后必须能读到 derived_cols 与 frame cols 两个数且相等。
# 读不到 = 仪表没做够；不相等 = 缺陷现场。
#
# 改前必须红、改后必须绿。trap 收尾。不启动模拟器、不起 daemon、不碰用户 tmux、不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$NODE/../../.." && pwd)"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.agentmirror.app
ACTIVITY=dev.agentmirror.app.MainActivity

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -x "$ADB" ] || fail "adb 不在 $ADB"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 1.2
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

export LAND_RF_NODE="$NODE"
export LAND_RF_ROOT="$ROOT"
python3 - <<'PY'
import os, re, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["LAND_RF_NODE"]
pkg = "dev.agentmirror.app"
activity = "dev.agentmirror.app.MainActivity"
preferred = ["远控 leader", "team-leader-2", "leader"]

def fail(msg):
    print("FAIL " + msg)
    sys.exit(1)

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
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not tm or not bm:
            continue
        t = tm.group(1)
        if not t.strip():
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2, x1, x2))
    return out

def tap(cx, cy):
    subprocess.check_call([adb, "shell", "input", "tap", str(cx), str(cy)])
    time.sleep(1.0)

def on_session(ts):
    return "查看" in ts and "Esc" in ts and "Ctrl-C" in ts

def on_favorites(ts):
    return any("SESSIONS ·" in t and "WORKSPACES" not in t for t in ts) and "收藏" in ts

def on_l1(ts):
    return any("WORKSPACES" in t for t in ts)

x = dump()
ts = texts(x)
for _ in range(8):
    if on_session(ts) or on_l1(ts) or on_favorites(ts):
        break
    ns = nodes(x)
    back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 工作区") and n[2] < 500]
    if not back:
        back = [n for n in ns if n[0] == "工作区" and n[2] < 500]
    if not back:
        break
    tap(back[0][1], back[0][2])
    x = dump()
    ts = texts(x)

if not on_session(ts):
    if not on_favorites(ts):
        favs = [n for n in nodes(x) if n[0] == "收藏"]
        if favs:
            favs.sort(key=lambda n: n[2])
            tap(favs[-1][1], favs[-1][2])
            time.sleep(1.5)
            subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
            x = dump()
            ts = texts(x)
    name = next((n for n in preferred if n in ts), None)
    if name is None:
        fail("进不了会话：收藏页没有显示名。屏上有: %s" % ts)
    hits = [n for n in nodes(x) if n[0] == name]
    if not hits:
        fail("找不到会话行 %s。屏上有: %s" % (name, ts))
    tap(hits[0][1], hits[0][2])
    time.sleep(1.4)
    subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
    x = dump()
    ts = texts(x)

if not on_session(ts):
    fail("A-rf-cols 前置：不在对话页面。屏上有: %s" % ts)

print("ON_SESSION", ts[:12])
open(os.path.join(node, "ui-tree-session.xml"), "w", encoding="utf-8").write(x)

# ① 已在对话页  ② HOME  ③ ≥60s  ④ 回前台
print("BACKGROUND_HOME")
subprocess.check_call([adb, "shell", "input", "keyevent", "KEYCODE_HOME"])
print("WAIT_65s")
time.sleep(65)
print("RESUME")
t_resume = time.time()
subprocess.check_call([adb, "shell", "am", "start", "-n", "%s/%s" % (pkg, activity)])
# 给重连 + snapshot 留窗口（判据：回前台后 30 秒内）
time.sleep(12)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])

x = dump()
ts = texts(x)
print("AFTER_RESUME", ts[:12])
open(os.path.join(node, "ui-tree-after.xml"), "w", encoding="utf-8").write(x)

# 回设置页导出诊断日志（环形缓冲 → files/diag/）
def go_settings():
    xml = dump()
    t = texts(xml)
    if "导出" in t and "诊断日志" in t:
        return xml
    ns = nodes(xml)
    back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 工作区")]
    if back:
        tap(back[0][1], back[0][2])
        time.sleep(0.8)
    xml = dump()
    ns = nodes(xml)
    sets = [n for n in ns if n[0] == "设置"]
    if not sets:
        fail("找不到「设置」。屏上有: %s" % texts(xml))
    sets.sort(key=lambda n: -n[2])
    tap(sets[0][1], sets[0][2])
    time.sleep(1.0)
    return dump()

sx = go_settings()
st = texts(sx)
print("SETTINGS", st[:16])
exports = [n for n in nodes(sx) if n[0] == "导出"]
if not exports:
    fail("设置页没有「导出」。屏上有: %s" % st)
exports.sort(key=lambda n: n[2])
tap(exports[0][1], exports[0][2])
time.sleep(1.2)
# 关掉分享框，文件已经落盘
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
time.sleep(0.4)
subprocess.run([adb, "shell", "input", "keyevent", "4"], check=False)
time.sleep(0.4)

ls = subprocess.run(
    [adb, "shell", "run-as", pkg, "ls", "files/diag"],
    capture_output=True, text=True,
)
names = [ln.strip() for ln in (ls.stdout or "").splitlines() if ln.strip().endswith(".log")]
if not names:
    fail("A-rf-cols 导出目录空。run-as ls: %s %s" % (ls.stdout, ls.stderr))
names.sort()
latest = names[-1]
print("DIAG_FILE", latest)
cat = subprocess.run(
    [adb, "shell", "run-as", pkg, "cat", "files/diag/" + latest],
    capture_output=True, text=True,
)
log = cat.stdout or ""
open(os.path.join(node, "diag-after.log"), "w", encoding="utf-8").write(log)
if not log.strip():
    fail("A-rf-cols 导出文件空 %s" % latest)

# 只看回前台后的窗口：日志墙钟可能对不齐，退而取全文里最后一次 derived_cols / frame cols
derived_hits = re.findall(r"derived_cols=(-?\d+)", log)
frame_hits = re.findall(r"frame cols=(-?\d+)", log)
print("DERIVED_HITS", derived_hits[-6:])
print("FRAME_HITS", frame_hits[-6:])
if not derived_hits:
    fail("A-rf-cols 读不到 derived_cols（仪表没做够）")
if not frame_hits:
    fail("A-rf-cols 读不到 frame cols（仪表没做够）")
derived = int(derived_hits[-1])
frame = int(frame_hits[-1])
print("A-rf-cols derived_cols=%s frame_cols=%s elapsed=%.1f" % (derived, frame, time.time() - t_resume))
if derived < 1 or frame < 1:
    fail("A-rf-cols 操作数非法 derived=%s frame=%s" % (derived, frame))
if derived != frame:
    fail("A-rf-cols 不相等 derived_cols=%s frame cols=%s（缺陷现场）" % (derived, frame))

shot = os.path.join(node, "shot-after-resume.png")
subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
print("SHOT", shot)
print("PASS t.reflow A-rf-cols")
PY
