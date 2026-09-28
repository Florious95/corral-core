#!/usr/bin/env bash
# ui-check.sh — t.glyph 框线/块缝 + onDraw 日志去重（退出码即判据）
#
# A-gl-quiet：会话页正常重绘 10s，日志里 [term-left-edge] ≤ 3；改 viewW（wm density）后必须多一条。
# A-gl-seam：几何矩形首尾相接由 GlyphSeam 单测在整数/非整数密度各跑；本脚本负责世界侧去重与截图。
# trap 收尾：关输入法、wm density reset。不碰用户 tmux、不扫 argv。
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
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

"$ADB" shell wm density reset >/dev/null 2>&1 || true
# 关掉上一轮「导出」残留的系统分享页
"$ADB" shell input keyevent 4 >/dev/null 2>&1 || true
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 0.4
"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 1.4
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

export LAND_GLYPH_NODE="$NODE"
export LAND_GLYPH_ROOT="$ROOT"
python3 - <<'PY'
import os, re, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["LAND_GLYPH_NODE"]
root = os.environ["LAND_GLYPH_ROOT"]
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发"]
skip_names = {
    "收藏", "会话", "设置", "LAN", "★", "☆", "☰", "⚙", "空闲", "进行中", "未知",
    "工作区", "查看", "Esc", "Tab", "Ctrl-C",
}

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
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2))
    return out

def tap(cx, cy):
    subprocess.check_call([adb, "shell", "input", "tap", str(cx), str(cy)])
    time.sleep(1.0)

def on_session(ts):
    return "查看" in ts and "Esc" in ts

def on_settings(ts):
    return "诊断日志" in ts or "外观" in ts

def on_l1(ts):
    return any("WORKSPACES" in t for t in ts)

x = dump()
ts = texts(x)
for _ in range(8):
    if on_session(ts) or on_l1(ts) or "收藏" in ts:
        break
    ns = nodes(x)
    back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 工作区") and n[2] < 500]
    if not back:
        break
    tap(back[0][1], back[0][2])
    x = dump()
    ts = texts(x)

if not on_session(ts):
    if on_l1(ts) or (any("WORKSPACES" in t for t in ts)):
        favs = [n for n in nodes(x) if n[0] == "收藏"]
        if favs:
            favs.sort(key=lambda n: n[2])
            tap(favs[-1][1], favs[-1][2])
            time.sleep(1.8)
            subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
            x = dump()
            ts = texts(x)
    name = next((n for n in preferred if n in ts), None)
    if name is None:
        name = next((t for t in ts if t not in skip_names and not t.startswith("/") and "SESSIONS" not in t and "WORKSPACES" not in t), None)
    if name:
        hits = [n for n in nodes(x) if n[0] == name]
        if hits:
            tap(hits[0][1], hits[0][2])
            time.sleep(1.4)
            subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
            x = dump()
            ts = texts(x)

if not on_session(ts):
    fail("进不了会话页。屏上有: %s" % ts)
print("SESSION:", [t for t in ts if t in preferred or t in ("查看", "Esc")])
python3 = os.path.join(root, "tools", "uiassert.py")
r = subprocess.run(["python3", python3, "absent", "claude_code"], capture_output=True, text=True)
if r.returncode != 0:
    fail("uiassert absent claude_code: %s" % ((r.stdout or "") + (r.stderr or "")))

# 连续重绘：终端区小幅滑动，逼出 onDraw（空闲不 invalidate 就测不到去重）
print("REDRAW swipes")
for _ in range(12):
    subprocess.check_call([adb, "shell", "input", "swipe", "630", "1100", "630", "980", "120"])
    time.sleep(0.45)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])

def go_settings():
    x = dump()
    ts = texts(x)
    for _ in range(8):
        if on_settings(ts):
            break
        ns = nodes(x)
        back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 设置") and n[2] < 600]
        if back and "诊断日志" not in ts:
            tap(back[0][1], back[0][2])
            x = dump()
            ts = texts(x)
            continue
        sets = [n for n in nodes(x) if n[0] == "设置"]
        if not sets:
            break
        sets.sort(key=lambda n: n[2])
        tap(sets[-1][1], sets[-1][2])
        time.sleep(0.8)
        x = dump()
        ts = texts(x)
    if "诊断日志" not in ts:
        for _ in range(4):
            subprocess.check_call([adb, "shell", "input", "swipe", "540", "1600", "540", "500", "280"])
            time.sleep(0.35)
        x = dump()
        ts = texts(x)
    if "诊断日志" not in texts(x):
        fail("到不了设置/诊断日志。屏上有: %s" % texts(x))
    return dump()

def export_and_count():
    go_settings()
    exps = [n for n in nodes(dump()) if n[0] == "导出"]
    if not exps:
        fail("找不到「导出」。屏上有: %s" % texts(dump()))
    exps.sort(key=lambda n: n[2])
    tap(exps[-1][1], exps[-1][2])
    time.sleep(1.4)
    # 分享页用 BACK 关掉，留在设置；文件已经写进 files/diag
    subprocess.check_call([adb, "shell", "input", "keyevent", "4"])
    time.sleep(0.5)
    subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
    time.sleep(0.3)
    ls = subprocess.run(
        [adb, "shell", "run-as", "dev.agentmirror.app", "ls", "files/diag"],
        capture_output=True, text=True,
    )
    names = [ln.strip() for ln in (ls.stdout or "").splitlines() if ln.strip().startswith("diag-") and ln.endswith(".log")]
    names.sort()
    if not names:
        fail("导出后 files/diag 没有日志文件 ls=%r" % (ls.stdout or ls.stderr))
    path = "files/diag/" + names[-1]
    cat = subprocess.run(
        [adb, "shell", "run-as", "dev.agentmirror.app", "cat", path],
        capture_output=True,
    )
    text = (cat.stdout or b"").decode("utf-8", "replace")
    open(os.path.join(node, "diag-export.log"), "w", encoding="utf-8").write(text)
    n = text.count("[term-left-edge]")
    print("EXPORT", path, "term-left-edge=%d lines=%d" % (n, text.count("\n") + 1))
    return n

n_edge = export_and_count()
if n_edge > 3:
    fail("A-gl-quiet 重绘后 term-left-edge=%d > 3" % n_edge)
if n_edge < 1:
    fail("A-gl-quiet 一条都没有——仪表被整段关掉了")

# 改密度 = 改 contentLeft/viewW 像素。必须回到会话页触发 onDraw，不能停在设置。
subprocess.check_call([adb, "shell", "wm", "density", "440"])
time.sleep(1.8)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
x = dump()
ts = texts(x)
for _ in range(8):
    if on_session(ts):
        break
    ns = nodes(x)
    back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 设置") and n[2] < 600]
    if back:
        tap(back[0][1], back[0][2])
        x = dump()
        ts = texts(x)
        continue
    favs = [n for n in ns if n[0] == "收藏"]
    if favs:
        favs.sort(key=lambda n: n[2])
        tap(favs[-1][1], favs[-1][2])
        time.sleep(1.2)
        x = dump()
        ts = texts(x)
    name = next((n for n in preferred if n in ts), None)
    if name is None:
        name = next((t for t in ts if t not in skip_names and not t.startswith("/") and "SESSIONS" not in t), None)
    if name:
        hits = [n for n in nodes(x) if n[0] == name]
        if hits:
            tap(hits[0][1], hits[0][2])
            time.sleep(1.2)
            x = dump()
            ts = texts(x)
            break
    break
if not on_session(texts(dump())):
    fail("改密度后回不到会话页。屏上有: %s" % texts(dump()))
subprocess.check_call([adb, "shell", "input", "swipe", "630", "1100", "630", "980", "120"])
time.sleep(0.6)
n2 = export_and_count()
print("A-gl-quiet after density term-left-edge=%d (was %d)" % (n2, n_edge))
if n2 < n_edge + 1:
    fail("A-gl-quiet 改密度后必须立刻多一条 before=%d after=%d" % (n_edge, n2))

shot = os.path.join(node, "shot-session.png")
x = dump()
for _ in range(5):
    ns = nodes(x)
    back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 设置") and n[2] < 600]
    if not back:
        break
    tap(back[0][1], back[0][2])
    x = dump()
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
print("SHOT", shot, "density=440")
print("PASS t.glyph vz-glyph")
PY
