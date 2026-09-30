#!/usr/bin/env bash
# ui-check.sh — t.list 列表三屏 + 082 收藏跨工作区取数（退出码即判据）
#
# A-ls-ui：收藏页三个互不相同的 claude code 显示名；每行有状态文案；底栏三 tab。
# A-fv-online / A-fv-name-offline：冷启动后**不进二级**直接打开收藏页，
#   三行都必须在线（非「不在线」），且不得出现 claude_code。
#
# 改前必须红、改后必须绿。trap 收尾。不启动模拟器、不起 daemon、不碰用户 tmux、不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$NODE/../../.." && pwd)"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.agentmirror.app
ACTIVITY=dev.agentmirror.app.MainActivity
UIASSERT=(python3 "$ROOT/tools/uiassert.py")

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -x "$ADB" ] || fail "adb 不在 $ADB"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

# 冷启动清掉内存里的 level2Cache，否则「没取数却在线」和修好同形。
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 0.4
"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 1.2
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

export LAND_LIST_NODE="$NODE"
export LAND_LIST_ROOT="$ROOT"
python3 - <<'PY'
import os, re, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["LAND_LIST_NODE"]
root = os.environ["LAND_LIST_ROOT"]
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
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2))
    return out

def tap(cx, cy):
    subprocess.check_call([adb, "shell", "input", "tap", str(cx), str(cy)])
    time.sleep(1.1)

def on_favorites(ts):
    return any("SESSIONS ·" in t and "WORKSPACES" not in t for t in ts) and "收藏" in ts

def on_l1(ts):
    return any("WORKSPACES" in t for t in ts)

# 回到一级（不点进工作区）。设计返回钮拆成「‹」+「工作区」两个 Text。
x = dump()
ts = texts(x)
for _ in range(6):
    if on_l1(ts) or on_favorites(ts):
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

if not on_l1(ts) and not on_favorites(ts):
    fail("无法回到一级/收藏。屏上有: %s" % ts)

# 点底栏「收藏」（最靠下）。从一级点过去，不进二级。
if not on_favorites(ts):
    favs = [n for n in nodes(x) if n[0] == "收藏"]
    if not favs:
        fail("屏上没有「收藏」tab。屏上有: %s" % ts)
    favs.sort(key=lambda n: n[2])
    tap(favs[-1][1], favs[-1][2])
    time.sleep(2.8)  # 串行按工作区取数
    subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
    x = dump()
    ts = texts(x)

x = dump()
open(os.path.join(node, "ui-tree.xml"), "w", encoding="utf-8").write(x)
ts = texts(x)
print("SCREEN:", ts)

# ③ 底栏三个 tab
for tab in ("收藏", "会话", "设置"):
    if not any(tab == t or tab in t for t in ts):
        fail("底栏缺 tab %s。屏上有: %s" % (tab, ts))

# ① 三个互不相同的 claude code 显示名（本机三收藏：远控 leader / team-leader-2 / leader）
miss = [n for n in preferred if n not in ts]
if miss:
    fail("A-ls-ui 收藏页缺显示名 %s。屏上有: %s" % (miss, ts))
if len(set(preferred)) < 3:
    fail("A-ls-ui 显示名不是互异的")

# ② 每行都有状态文案
online = sum(1 for t in ts if t in ("进行中", "空闲", "未知"))
offline = sum(1 for t in ts if t == "不在线")
if online < 3:
    fail("A-ls-ui 状态文案不足 3（进行中/空闲/未知）。屏上有: %s" % ts)

# ③ 底栏三个 tab — 上面已核

# A-fv-name-offline：不得出现 claude_code
if any("claude_code" in t for t in ts):
    fail("A-fv-name-offline 屏上出现 claude_code。屏上有: %s" % ts)

# A-fv-online：三行都必须在线。只绿一行 = 只刷了一个工作区。
print("A-fv-online offline=%d online_status=%d names=%s" % (offline, online, preferred))
if offline != 0:
    fail("A-fv-online 仍有不在线行 offline=%d online=%d 屏上=%s" % (offline, online, ts))
if online < 3:
    fail("A-fv-online 在线状态不足 3（只刷一个工作区也会绿一行）。offline=%d online=%d 屏上=%s" % (offline, online, ts))

shot = os.path.join(node, "shot-favorites.png")
subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
print("SHOT", shot)
print("PASS t.list land-list")
PY
