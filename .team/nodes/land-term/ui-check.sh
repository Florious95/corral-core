#!/usr/bin/env bash
# ui-check.sh — t.term 会话页外壳 + 「查看」sheet + 终端深浅色（退出码即判据）
#
# A-tm-ui：顶栏是显示名不是 claude_code；点「查看」后 sheet 属于当前工作区（有「切换会话」）；
#   adb uimode night yes|no 切深浅色后终端背景像素真的变了。
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
  "$ADB" shell cmd uimode night auto >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -x "$ADB" ] || fail "adb 不在 $ADB"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

# 外观钉死浅/深时 uimode 改不了终端。先改回跟随系统（非敏感 prefs）。
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' \
  '<map>' '    <string name="appearance">system</string>' '</map>' \
  | "$ADB" shell run-as "$PKG" tee shared_prefs/app_appearance.xml >/dev/null
got="$("$ADB" shell run-as "$PKG" cat shared_prefs/app_appearance.xml 2>/dev/null || true)"
echo "$got" | grep -q '>system<' || fail "外观未写成 system：${got:-empty}"

"$ADB" shell cmd uimode night no >/dev/null 2>&1 || true
sleep 0.3
"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 1.2
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

export LAND_TERM_NODE="$NODE"
export LAND_TERM_ROOT="$ROOT"
python3 - <<'PY'
import os, re, struct, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["LAND_TERM_NODE"]
root = os.environ["LAND_TERM_ROOT"]
preferred = ["远控 leader", "team-leader-2", "leader"]
chrome = {
    "‹", "查看", "LAN", "Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C", "+", "＋",
    "❯", "输入指令…", "发送", "收藏", "会话", "设置", "工作区", "‹ 返回", "‹ 工作区",
    "当前", "进行中", "空闲", "未知", "切换会话", "★", "☆",
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
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2, x1, x2))
    return out

def tap(cx, cy):
    subprocess.check_call([adb, "shell", "input", "tap", str(cx), str(cy)])
    time.sleep(1.1)

def screencap_raw():
    r = subprocess.run([adb, "exec-out", "screencap"], capture_output=True, timeout=30)
    data = r.stdout or b""
    if len(data) < 16:
        fail("screencap 太短 %d" % len(data))
    w, h, fmt = struct.unpack_from("<III", data, 0)
    off = 12
    bpp = 4
    need = w * h * bpp
    if len(data) < off + need:
        w, h, fmt, _stride = struct.unpack_from("<IIII", data, 0)
        off = 16
        need = w * h * bpp
    if w <= 0 or h <= 0 or len(data) < off + need:
        fail("screencap 头异常 w=%s h=%s fmt=%s len=%d" % (w, h, fmt, len(data)))
    return w, h, data[off:off + need]

def sample(w, h, px, x, y):
    x = max(0, min(w - 1, x))
    y = max(0, min(h - 1, y))
    i = (y * w + x) * 4
    return tuple(px[i:i + 3])

def on_session(ts):
    return "查看" in ts and "Esc" in ts and "Ctrl-C" in ts

def on_favorites(ts):
    return any("SESSIONS ·" in t and "WORKSPACES" not in t for t in ts) and "收藏" in ts

def on_l1(ts):
    return any("WORKSPACES" in t for t in ts)

def session_title(ts):
    for t in ts:
        if t in chrome:
            continue
        if t.startswith("term-theme-"):
            continue
        if t.startswith("/") or t == "claude_code":
            continue
        return t
    return None

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
            time.sleep(2.0)
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

print("SESSION_SCREEN:", ts)
open(os.path.join(node, "ui-tree.xml"), "w", encoding="utf-8").write(x)

if not on_session(ts):
    fail("A-tm-ui 不在会话页。屏上有: %s" % ts)
if any("claude_code" in t for t in ts):
    fail("A-tm-ui 顶栏出现 claude_code。屏上有: %s" % ts)
title = session_title(ts)
if not title:
    fail("A-tm-ui 顶栏没有会话显示名。屏上有: %s" % ts)
print("TITLE:", title)

# 点「查看」
views = [n for n in nodes(x) if n[0] == "查看"]
if not views:
    fail("找不到「查看」按钮")
views.sort(key=lambda n: (n[2], -n[1]))
tap(views[-1][1], views[-1][2])
time.sleep(1.0)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
x = dump()
ts = texts(x)
print("SHEET:", ts)
open(os.path.join(node, "ui-tree-sheet.xml"), "w", encoding="utf-8").write(x)
if "切换会话" not in ts:
    fail("A-tm-ui 点「查看」后没有「切换会话」sheet。屏上有: %s" % ts)
if any("claude_code" in t for t in ts):
    fail("A-tm-ui sheet 出现 claude_code。屏上有: %s" % ts)
if title not in ts:
    fail("A-tm-ui sheet 没有当前会话显示名 %r。屏上有: %s" % (title, ts))
badge = [t for t in ts if "·" in t and any(ch.isdigit() for ch in t)]
if not badge:
    fail("A-tm-ui sheet 没有工作区徽章。屏上有: %s" % ts)
print("WORKSPACE_BADGE:", badge)

# 关 sheet：点遮罩上方
tap(80, 200)
time.sleep(0.8)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
x = dump()
ts = texts(x)
if "切换会话" in ts:
    tap(80, 180)
    time.sleep(0.7)

def term_pad_xy(xml):
    """SurfaceView 左 14dp 是主题清屏底，不是主机 SGR 格子。从 content-desc 取框。"""
    m = re.search(
        r'content-desc="term-theme-[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        xml,
    )
    if not m:
        m = re.search(
            r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="term-theme-',
            xml,
        )
    if not m:
        fail("找不到终端 SurfaceView bounds。token 缺失？")
    x1, y1, x2, y2 = map(int, m.groups())
    return x1 + 12, y1 + 24, x1, y1, x2, y2

def theme_token(ts):
    hits = [t for t in ts if t.startswith("term-theme-")]
    return hits[0] if hits else None

# 终端背景：uimode 切深浅色。采样左内边距（主题底），不要采画面中心（那是主机格子）。
subprocess.check_call([adb, "shell", "cmd", "uimode", "night", "no"])
time.sleep(1.3)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
x = dump()
ts = texts(x)
tok_light = theme_token(ts)
sx, sy, x1, y1, x2, y2 = term_pad_xy(x)
w, h, px = screencap_raw()
before = sample(w, h, px, sx, sy)
print("TERM_TOKEN_LIGHT", tok_light, "pad", (sx, sy), "view", (x1, y1, x2, y2))
print("TERM_BG_LIGHT", before, "wh", w, h)

subprocess.check_call([adb, "shell", "cmd", "uimode", "night", "yes"])
time.sleep(1.6)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
x = dump()
ts = texts(x)
tok_dark = theme_token(ts)
sx2, sy2, *_ = term_pad_xy(x)
w2, h2, px2 = screencap_raw()
after = sample(w2, h2, px2, sx2, sy2)
print("TERM_TOKEN_DARK", tok_dark, "pad", (sx2, sy2))
print("TERM_BG_DARK", after, "wh", w2, h2)
if tok_light is None or tok_dark is None:
    fail("A-tm-ui 终端 theme token 缺失 light=%s dark=%s" % (tok_light, tok_dark))
if tok_light == tok_dark:
    fail("A-tm-ui 切深浅色后 theme token 没变 %s" % tok_light)
if before == after:
    fail("A-tm-ui 切深浅色后终端背景像素没变 before=%s after=%s token %s→%s" % (
        before, after, tok_light, tok_dark,
    ))

r = subprocess.run(
    ["python3", os.path.join(root, "tools", "uiassert.py"), "absent", "claude_code"],
    capture_output=True, text=True,
)
if r.returncode != 0:
    fail("uiassert absent claude_code: %s" % (r.stdout or r.stderr))

shot = os.path.join(node, "shot-session.png")
subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
print("SHOT", shot)
print("PASS t.term land-term")
PY
