#!/usr/bin/env bash
# ui-check.sh — 077 会话页顶栏标题根因探针（退出码即判据）
#
# 进入任一 claude code 会话后：
#   uiassert.py absent claude_code  必须 0
#   uiassert.py has <该会话在列表里的显示名> 必须 0
#
# 自足：自己拉起 App、自己离开已打开的会话页、自己点列表、自己断言。
# trap 收尾。不启动模拟器、不起 daemon/node、不碰用户默认 tmux、不扫 argv。
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
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

"$ADB" shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
sleep 1
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

export UX_TITLE_NODE="$NODE"
export UX_TITLE_ROOT="$ROOT"
python3 - <<'PY'
import os, re, subprocess, sys, time

node = os.environ["UX_TITLE_NODE"]
root = os.environ["UX_TITLE_ROOT"]
adb = os.environ["ADB"]
uiassert = [sys.executable, os.path.join(root, "tools", "uiassert.py")]
preferred = ["远控 leader", "team-leader-2", "leader"]

def fail(msg):
    print("FAIL " + msg)
    sys.exit(1)

def tree():
    r = subprocess.run(
        [adb, "exec-out", "uiautomator", "dump", "/dev/tty"],
        capture_output=True,
        timeout=60,
    )
    x = (r.stdout or b"").decode("utf-8", "replace")
    if "<hierarchy" not in x:
        fail("取不到 UI 树（设备没连？应用没在前台？）—— 尺子坏了和被测空闲同形")
    return x

def texts(x):
    return [t for t in re.findall(r'text="([^"]*)"', x) if t.strip()]

def nodes(x):
    out = []
    for m in re.finditer(
        r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        x,
    ):
        t = m.group(1)
        if not t.strip():
            continue
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        out.append((t, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2))
    return out

def tap(cx, cy):
    subprocess.check_call([adb, "shell", "input", "tap", str(cx), str(cy)])
    time.sleep(0.9)

def tap_text(x, needle, *, prefer="any"):
    hits = [(t, cx, cy, y1, y2) for t, cx, cy, y1, y2 in nodes(x) if needle in t]
    if not hits:
        return False
    if prefer == "bottom":
        hits.sort(key=lambda h: h[4], reverse=True)
    elif prefer == "top":
        hits.sort(key=lambda h: h[3])
    _, cx, cy, _, _ = hits[0]
    print(f"tap {needle!r} at {cx},{cy} prefer={prefer}")
    tap(cx, cy)
    return True

def dump_texts():
    x = tree()
    ts = texts(x)
    print("SCREEN " + " | ".join(ts[:40]))
    return x, ts

def on_session(ts):
    return any("返回" in t for t in ts) and any(t in ("查看", "LAN", "tailnet") for t in ts)

def on_pairing(ts):
    blob = " ".join(ts)
    return "配对" in blob and ("跳过" in blob or "连接" in blob or "扫码" in blob)

x, ts = dump_texts()
if on_pairing(ts):
    fail("停在配对页，探针无法进会话。屏上=" + str(ts))

if on_session(ts):
    if not tap_text(x, "返回", prefer="top"):
        fail("会话页找不到返回")
    x, ts = dump_texts()

if any("重新配对" in t or "诊断日志" in t or "字号" in t for t in ts) and not any(
    n in ts for n in preferred
):
    tap_text(x, "收藏", prefer="bottom")
    x, ts = dump_texts()

if not any(n in ts for n in preferred) and any("远程Agent安卓" in t for t in ts):
    tap_text(x, "远程Agent安卓", prefer="top")
    x, ts = dump_texts()

if not any(n in ts for n in preferred):
    tap_text(x, "收藏", prefer="bottom")
    x, ts = dump_texts()

chosen = next((n for n in preferred if n in ts), None)
if chosen is None:
    fail("列表里没有 claude code 显示名 %s。屏上=%s" % (preferred, ts))

print("LIST_DISPLAY_NAME " + chosen)
print(
    "operands before_open list_display=%r claude_code_in_list=%s"
    % (chosen, "claude_code" in ts)
)

if not tap_text(x, chosen, prefer="top"):
    fail("点不了列表显示名 " + chosen)

time.sleep(0.7)
subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
time.sleep(0.3)

x, ts = dump_texts()
if not on_session(ts):
    fail("点列表后没有进会话页。屏上=" + str(ts))

shot = os.path.join(node, "session-title.png")
xmlp = os.path.join(node, "session-title.ui.xml")
png = subprocess.check_output([adb, "exec-out", "screencap", "-p"])
open(shot, "wb").write(png)
open(xmlp, "w", encoding="utf-8").write(x)
print("SHOT " + shot)
print("UIXML " + xmlp)
print(
    "operands after_open has_return=%s has_view=%s has_list_name=%s has_claude_code=%s texts=%s"
    % (
        any("返回" in t for t in ts),
        "查看" in ts,
        chosen in ts,
        "claude_code" in ts,
        ts[:20],
    )
)

r_abs = subprocess.run(uiassert + ["absent", "claude_code"])
print("uiassert absent claude_code rc=%d" % r_abs.returncode)
r_has = subprocess.run(uiassert + ["has", chosen])
print("uiassert has %r rc=%d" % (chosen, r_has.returncode))

if r_abs.returncode != 0 or r_has.returncode != 0:
    fail(
        "会话页顶栏仍用旧名或未带上列表显示名 "
        "absent_rc=%d has_rc=%d chosen=%r"
        % (r_abs.returncode, r_has.returncode, chosen)
    )
print("PASS session title matches list display name %r and claude_code is absent" % chosen)
sys.exit(0)
PY
