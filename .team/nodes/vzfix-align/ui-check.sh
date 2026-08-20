#!/usr/bin/env bash
# ui-check.sh — t.align 光学对齐 + 菜单采样（退出码即判据）
# 整数密度 480（3.0）与非整数 420（2.625）各跑一遍。trap 收尾不留后台。
# 不启动模拟器、不碰用户 tmux、不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="/Volumes/nvme/Projects/远程Agent安卓"
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

export VZ_ALIGN_NODE="$NODE"
python3 - <<'PY'
import os, re, struct, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["VZ_ALIGN_NODE"]
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发", "vz-v1-chrome"]
chrome = {
    "‹", "查看", "LAN", "tailnet", "Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C", "+", "＋",
    "❯", "输入指令…", "发送", "收藏", "会话", "设置", "工作区", "‹ 返回", "‹ 工作区",
    "当前", "进行中", "空闲", "未知", "切换会话", "★", "☆", "拍照", "从相册选择",
}
failures = []

def fail(msg):
    print("FAIL " + msg)
    sys.exit(1)

def note(msg):
    print("FAIL " + msg)
    failures.append(msg)

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
    x = max(0, min(w - 1, int(x)))
    y = max(0, min(h - 1, int(y)))
    i = (y * w + x) * 4
    return tuple(px[i:i + 3])

def dist(a, b):
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5

def density_scale():
    r = subprocess.run([adb, "shell", "wm", "density"], capture_output=True, text=True)
    text = (r.stdout or "") + (r.stderr or "")
    m = re.search(r"Override density:\s*(\d+)", text)
    if m:
        return int(m.group(1)), int(m.group(1)) / 160.0
    m = re.search(r"Physical density:\s*(\d+)", text)
    if m:
        return int(m.group(1)), int(m.group(1)) / 160.0
    fail("读不到 wm density: %r" % text)

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

def ink_center_y(w, h, px, x1, y1, x2, y2, bg):
    ys = []
    for y in range(max(0, y1), min(h, y2)):
        hit = False
        for x in range(max(0, x1), min(w, x2)):
            c = sample(w, h, px, x, y)
            if dist(c, bg) > 28:
                hit = True
                break
        if hit:
            ys.append(y)
    if not ys:
        return None
    return (ys[0] + ys[-1]) / 2.0

def largest_containing(ns, px, py):
    cands = [n for n in ns if n[6] <= px <= n[7] and n[4] <= py <= n[5]]
    if not cands:
        return None
    cands.sort(key=lambda n: (n[7] - n[6]) * (n[5] - n[4]), reverse=True)
    return cands[0]

def capture_density(tag, dpi):
    sh(adb, "shell", "wm", "density", str(dpi))
    time.sleep(1.4)
    sh(adb, "shell", "input", "keyevent", "111")
    x = navigate_session()
    ts = texts(x)
    print("SESSION_%s:" % tag, ts)
    open(os.path.join(node, "ui-tree-%s.xml" % tag), "w", encoding="utf-8").write(x)
    sh(adb, "shell", "input", "keyevent", "111")
    shot = os.path.join(node, "shot-%s-session.png" % tag)
    subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
    title = session_title(ts)
    if not title:
        fail("没有会话显示名 density=%s 屏上有: %s" % (tag, ts))
    dpi_now, scale = density_scale()
    w, h, px = screencap_raw()
    ns = nodes(x)
    back = [n for n in ns if n[1] == "返回"]
    title_n = [n for n in ns if n[0] == title]
    if not back:
        fail("找不到返回 content-desc density=%s" % tag)
    if not title_n:
        fail("找不到标题节点 %r density=%s" % (title, tag))
    bg_bar = sample(w, h, px, 20, max(0, back[0][4] - 4))
    b = back[0]
    tnode = title_n[0]
    back_cy = ink_center_y(w, h, px, b[6], b[4], b[7], b[5], bg_bar)
    title_cy = ink_center_y(w, h, px, tnode[6], tnode[4], tnode[7], tnode[5], bg_bar)
    print(
        "ALIGN_BOX_%s back_bounds=[%d,%d][%d,%d] title_bounds=[%d,%d][%d,%d] bar_bg=%s"
        % (tag, b[6], b[4], b[7], b[5], tnode[6], tnode[4], tnode[7], tnode[5], bg_bar)
    )
    if back_cy is None or title_cy is None:
        note("量不到墨迹盒 density=%s back=%s title=%s" % (tag, back_cy, title_cy))
        delta_dp = None
    else:
        delta_dp = abs(back_cy - title_cy) / scale
        print(
            "ALIGN_%s back_ink_cy=%.1f title_ink_cy=%.1f delta_dp=%.2f scale=%.3f dpi=%s"
            % (tag, back_cy, title_cy, delta_dp, scale, dpi_now)
        )
        if delta_dp > 1.0 + 1e-6:
            note(
                "光学对齐 density=%s 墨迹中心差 %.2fdp > 1dp back_ink_cy=%.1f title_ink_cy=%.1f scale=%.3f"
                % (tag, delta_dp, back_cy, title_cy, scale)
            )

    plus = [n for n in ns if n[0] in ("+", "＋")]
    if not plus:
        note("找不到 + 按钮 density=%s 屏上有: %s" % (tag, ts))
        return
    plus.sort(key=lambda n: n[4])
    tap(plus[0][2], plus[0][3], 1.0)
    mx = dump()
    mts = texts(mx)
    print("MENU_%s:" % tag, mts)
    menu_shot = os.path.join(node, "shot-%s-menu.png" % tag)
    subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(menu_shot, "wb"))
    open(os.path.join(node, "ui-tree-%s-menu.xml" % tag), "w", encoding="utf-8").write(mx)
    mw, mh, mpx = screencap_raw()
    photo = [n for n in nodes(mx) if n[0] == "拍照"]
    if not photo:
        note("+ 菜单没有拍照 density=%s 屏上有: %s" % (tag, mts))
        tap(80, 400, 0.6)
        sh(adb, "shell", "input", "keyevent", "111")
        return
    p = photo[0]
    container = largest_containing(nodes(mx), p[2], p[3])
    if container is None:
        fail("找不到菜单容器 density=%s" % tag)
    # 容器左上内缩：菜单垫色（避开字墨）
    pad_x = container[6] + 12
    pad_y = container[4] + 12
    # 验收席旧点：拍照节点左缘+8、垂直中心 —— 会落到字墨上
    old_x = p[6] + 8
    old_y = p[3]
    pad_rgb = sample(mw, mh, mpx, pad_x, pad_y)
    old_rgb = sample(mw, mh, mpx, old_x, old_y)
    print(
        "MENU_BOUNDS_%s container=[%d,%d][%d,%d] photo=[%d,%d][%d,%d]"
        % (tag, container[6], container[4], container[7], container[5], p[6], p[4], p[7], p[5])
    )
    print(
        "MENU_SAMPLE_%s pad_xy=(%d,%d) pad_rgb=%s oldprobe_xy=(%d,%d) oldprobe_rgb=%s"
        % (tag, pad_x, pad_y, pad_rgb, old_x, old_y, old_rgb)
    )
    tap(80, 400, 0.6)
    sh(adb, "shell", "input", "keyevent", "111")

capture_density("d480", 480)
capture_density("d420", 420)

if failures:
    print("FAILURES %d" % len(failures))
    for f in failures:
        print(" -", f)
    sys.exit(1)
print("PASS t.align vzfix-align")
PY
