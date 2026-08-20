#!/usr/bin/env bash
# ui-check.sh — t.live 顶栏灯实时 + 查看菜单有行（退出码即判据）
# 整数密度 480（3.0）与非整数 420（2.625）各跑一遍。
# trap 收尾：关输入法、density reset，不留后台进程。
# 不启动模拟器、不碰用户 tmux、不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="/Volumes/nvme/Projects/远程Agent安卓"
APP="$ROOT/app"
SRC="$APP/app/src/main/java/dev/agentmirror/app"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.agentmirror.app
ACTIVITY=dev.agentmirror.app.MainActivity

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
  "$ADB" shell cmd uimode night auto >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -f "$SRC/workspace/WorkspaceViewModel.kt" ] || fail "缺 WorkspaceViewModel.kt"
[ -f "$SRC/AgentMirrorApp.kt" ] || fail "缺 AgentMirrorApp.kt"
grep -q 'fun viewMenuSource' "$SRC/workspace/WorkspaceViewModel.kt" \
  || fail "缺 viewMenuSource"
[ -x "$ADB" ] || fail "adb 不在 $ADB"
[ -f "$ROOT/tools/uiassert.py" ] || fail "找不到 uiassert.py"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null 2>&1 || true
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' \
  '<map>' '    <string name="appearance">system</string>' '</map>' \
  | "$ADB" shell run-as "$PKG" tee shared_prefs/app_appearance.xml >/dev/null
"$ADB" shell cmd uimode night no >/dev/null 2>&1 || true
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 0.8
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

export VZ_LIVE_NODE="$NODE"
python3 - <<'PY'
import os, re, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["VZ_LIVE_NODE"]
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
        x1, y1, x2, y2 = map(int, bm.groups())
        t = tm.group(1) if tm else ""
        d = cd.group(1) if cd else ""
        out.append((t, d, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2, x1, x2))
    return out

def tap(cx, cy, wait=1.1):
    sh(adb, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(wait)

def on_session(ts):
    return "查看" in ts and "Esc" in ts and "Ctrl-C" in ts

def on_l1(ts):
    return any("WORKSPACES" in t for t in ts)

def on_favorites(ts):
    return any("SESSIONS ·" in t and "WORKSPACES" not in t for t in ts) and "收藏" in ts

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

def lamp_of(x):
    lamps = [n[1] for n in nodes(x) if n[1] in ("Idle", "Busy", "Unknown")]
    return lamps[0] if lamps else None

def navigate_session():
    x = dump()
    ts = texts(x)
    for _ in range(8):
        if on_session(ts) or on_l1(ts) or on_favorites(ts):
            break
        ns = nodes(x)
        back = [n for n in ns if (n[0] in ("‹", "‹ 返回", "‹ 工作区") or n[1] == "返回") and n[2] < 500]
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
            tap(favs[-1][2], favs[-1][3], 1.8)
            sh(adb, "shell", "input", "keyevent", "111")
            x = dump()
            ts = texts(x)
    name = next((n for n in preferred if n in ts), None)
    if name is None:
        name = next(
            (t for t in ts if t not in chrome and not t.startswith("/") and "SESSIONS" not in t and "WORKSPACES" not in t),
            None,
        )
    if name is None:
        fail("进不了会话：没有显示名。屏上有: %s" % ts)
    hits = [n for n in nodes(x) if n[0] == name]
    if not hits:
        fail("找不到会话行 %s。屏上有: %s" % (name, ts))
    tap(hits[0][2], hits[0][3], 1.4)
    sh(adb, "shell", "input", "keyevent", "111")
    x = dump()
    if not on_session(texts(x)):
        fail("点进后不在会话页。屏上有: %s" % texts(x))
    return x

def poke_working(x):
    """空闲/未知时往输入框打字并点右下发送键，逼会话进入 working。"""
    ns = nodes(x)
    drafts = [n for n in ns if n[0] == "❯"]
    ups = [n for n in ns if n[0] == "↑"]
    if not ups:
        print("POKE_NO_SEND_BTN")
        return False
    if drafts:
        tap(drafts[0][2] + 80, drafts[0][3], 0.4)
    sh(adb, "shell", "input", "text", "vzfix-live")
    time.sleep(0.3)
    ups.sort(key=lambda n: n[4])
    send = ups[-1]
    tap(send[2], send[3], 0.8)
    sh(adb, "shell", "input", "keyevent", "111")
    print("POKE_SEND at y=%s" % send[4])
    return True

def run_density(tag, dpi):
    sh(adb, "shell", "wm", "density", str(dpi))
    time.sleep(1.4)
    sh(adb, "shell", "input", "keyevent", "111")
    x = navigate_session()
    ts = texts(x)
    title = session_title(ts)
    if not title:
        fail("没有会话显示名 density=%s 屏上有: %s" % (tag, ts))
    lamp_before = lamp_of(x)
    t0 = time.time()
    print("LAMP_BEFORE_%s title=%r lamp=%s" % (tag, title, lamp_before))
    open(os.path.join(node, "ui-tree-%s-before.xml" % tag), "w", encoding="utf-8").write(x)
    subprocess.check_call(
        [adb, "exec-out", "screencap", "-p"],
        stdout=open(os.path.join(node, "shot-%s-before.png" % tag), "wb"),
    )

    poked = False
    if lamp_before != "Busy":
        poked = poke_working(x)

    lamp_after = lamp_before
    waited = 0.0
    deadline = t0 + 14.0
    while time.time() < deadline:
        time.sleep(1.0)
        sh(adb, "shell", "input", "keyevent", "111")
        x = dump()
        if not on_session(texts(x)):
            fail("等待灯变色时离开了会话页 density=%s 屏上有: %s" % (tag, texts(x)))
        lamp_after = lamp_of(x)
        waited = time.time() - t0
        print("LAMP_POLL_%s t=%.1fs lamp=%s poked=%s" % (tag, waited, lamp_after, poked))
        if lamp_after == "Busy":
            break

    waited = time.time() - t0
    print("LAMP_AFTER_%s title=%r before=%s after=%s waited_s=%.1f poked=%s"
          % (tag, title, lamp_before, lamp_after, waited, poked))
    open(os.path.join(node, "ui-tree-%s-after.xml" % tag), "w", encoding="utf-8").write(x)
    subprocess.check_call(
        [adb, "exec-out", "screencap", "-p"],
        stdout=open(os.path.join(node, "shot-%s-after.png" % tag), "wb"),
    )
    if lamp_before is None:
        fail("变化前读不到灯 content-desc density=%s" % tag)
    if lamp_after is None:
        fail("变化后读不到灯 content-desc density=%s" % tag)
    if lamp_after != "Busy":
        fail("灯未变成 Busy density=%s before=%s after=%s waited_s=%.1f"
             % (tag, lamp_before, lamp_after, waited))
    if lamp_before == lamp_after and lamp_before != "Busy":
        fail("灯未变化 density=%s before=after=%s waited_s=%.1f" % (tag, lamp_before, waited))

    views = [n for n in nodes(x) if n[0] == "查看"]
    if not views:
        fail("找不到「查看」 density=%s" % tag)
    views.sort(key=lambda n: (n[4], -n[2]))
    tap(views[-1][2], views[-1][3], 1.2)
    sx = dump()
    sts = texts(sx)
    print("SHEET_%s:" % tag, sts)
    open(os.path.join(node, "ui-tree-%s-sheet.xml" % tag), "w", encoding="utf-8").write(sx)
    subprocess.check_call(
        [adb, "exec-out", "screencap", "-p"],
        stdout=open(os.path.join(node, "shot-%s-sheet.png" % tag), "wb"),
    )
    if "切换会话" not in sts:
        fail("查看菜单没有「切换会话」 density=%s 屏上有: %s" % (tag, sts))
    if title not in sts:
        fail("查看菜单没有当前显示名 %r density=%s 屏上有: %s" % (title, tag, sts))
    count_m = None
    for t in sts:
        m = re.search(r"·\s*(\d+)\s*$", t)
        if m:
            count_m = int(m.group(1))
            break
    if count_m is None:
        fail("查看菜单没有会话行计数 density=%s 屏上有: %s" % (tag, sts))
    if count_m <= 0:
        fail("查看菜单会话行数=%d 期望 > 0 density=%s 屏上有: %s" % (count_m, tag, sts))
    print("SHEET_OK_%s title=%r rows=%d" % (tag, title, count_m))
    tap(80, 200, 0.8)
    sh(adb, "shell", "input", "keyevent", "111")

run_density("d480", 480)
run_density("d420", 420)
print("PASS t.live vzfix-live")
PY
