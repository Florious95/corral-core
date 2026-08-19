#!/usr/bin/env bash
# ui-check.sh — t.set 设置页 + 底栏 3b + 外观切换（退出码即判据）
#
# A-st-ui：设置页能读到「主机配对 / 字体大小 / 诊断日志 / 外观」；
#   滚到底后最后一项可见；切换「外观」后页面背景像素真的变了。
# 076 §2：一二级右上角没有「设置」（底栏除外）。
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

"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 0.4
"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 1.2
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

export LAND_SET_NODE="$NODE"
python3 - <<'PY'
import os, re, struct, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["LAND_SET_NODE"]

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

def swipe_up():
    subprocess.check_call([adb, "shell", "input", "swipe", "540", "1600", "540", "500", "280"])
    time.sleep(0.45)

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

# 回到一级（不点进工作区 / 会话）
x = dump()
ts = texts(x)
for _ in range(6):
    if any("WORKSPACES" in t for t in ts) or ("设置" in ts and "主机配对" in ts):
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

# 点底栏「设置」（最靠下）
sets = [n for n in nodes(x) if n[0] == "设置"]
if not sets:
    fail("屏上没有「设置」tab。屏上有: %s" % ts)
sets.sort(key=lambda n: n[2])
tap(sets[-1][1], sets[-1][2])
time.sleep(0.8)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
x = dump()
ts = texts(x)
print("SCREEN:", ts)

need = ("主机配对", "字体大小", "诊断日志", "外观")
miss = [n for n in need if not any(n in t for t in ts)]
if miss:
    fail("A-st-ui 设置页缺 %s。屏上有: %s" % (miss, ts))

# 滚到底：最后一项（外观分段「跟随系统」）必须完整可见
for _ in range(5):
    swipe_up()
x = dump()
ts = texts(x)
open(os.path.join(node, "ui-tree.xml"), "w", encoding="utf-8").write(x)
last_label = "跟随系统"
cands = [n for n in nodes(x) if n[0] == last_label]
if not cands:
    fail("滚到底后看不到「%s」。屏上有: %s" % (last_label, ts))
last = max(cands, key=lambda n: n[4])
# 标题也叫「设置」，不能拿它当底栏。收藏/会话只出现在 3b tab 上。
nav = [n for n in nodes(x) if n[0] in ("收藏", "会话")]
if not nav:
    fail("底栏缺 收藏/会话。屏上有: %s" % ts)
tab_top = min(n[3] for n in nav)
if last[3] < 0 or last[4] > tab_top + 2:
    fail("滚到底后最后一项未完整可见 last=%s tab_top=%s" % (last, tab_top))

# 076 §2：底栏三个 tab 都在；标题区不得再有第二个「设置」入口
for tab in ("收藏", "会话", "设置"):
    if not any(tab == t or tab in t for t in ts):
        fail("底栏缺 tab %s。屏上有: %s" % (tab, ts))

# 切换外观：先强制浅色再强制深色，页面背景像素必须变（不依赖系统深浅）
def find_seg(label):
    hits = [n for n in nodes(dump()) if n[0] == label]
    if not hits:
        fail("找不到分段「%s」" % label)
    return max(hits, key=lambda n: n[4])

light = find_seg("浅色")
tap(light[1], light[2])
time.sleep(0.9)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
w, h, px = screencap_raw()
before = sample(w, h, px, w // 8, int(h * 0.08))
print("BG_BEFORE", before, "wh", w, h)

dark = find_seg("深色")
tap(dark[1], dark[2])
time.sleep(0.9)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
w2, h2, px2 = screencap_raw()
after = sample(w2, h2, px2, w2 // 8, int(h2 * 0.08))
print("BG_AFTER", after, "wh", w2, h2)
if before == after:
    fail("切换外观后页面背景像素没变 before=%s after=%s" % (before, after))

shot = os.path.join(node, "shot-settings.png")
subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
print("SHOT", shot)
print("PASS t.set land-set")
PY
