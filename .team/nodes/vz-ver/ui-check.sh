#!/usr/bin/env bash
# ui-check.sh — t.ver 独立验收（退出码即判据）
# 整数密度 480（3.0）与非整数 420（2.625）各跑一遍。trap 收尾不留后台。
# 不改产品码、不启动模拟器、不碰用户 tmux、不扫 argv。
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

[ -f "$SRC/ui/theme/DesignTokens.kt" ] || fail "缺 DesignTokens.kt"
grep -q 'val terminalCardMargin: Dp = 4.dp' "$SRC/ui/theme/DesignTokens.kt" \
  || fail "卡片外间距不是 4dp"
grep -q 'val paddingLeft: Dp = 6.dp' "$SRC/ui/theme/TerminalSpec.kt" \
  || fail "终端内 padding 不是 6dp"
grep -q 'const val maxCols: Int = 112' "$SRC/ui/theme/TerminalSpec.kt" \
  || fail "缺 cols 上限 112"
grep -q 'is InputStatus.Sent -> null' "$SRC/session/SessionScreen.kt" \
  || fail "成功态「已发送」还在"
if grep -q '"已发送"' "$SRC/session/SessionScreen.kt"; then
  fail "SessionScreen 仍有「已发送」文案"
fi
grep -q 'InputStatus.Failed' "$SRC/session/SessionScreen.kt" \
  || fail "失败态被删了"
grep -q 'draft: TextFieldValue' "$SRC/ui/screens/SessionShellScreen.kt" \
  || fail "输入框仍是 String 重载"
grep -q 'path.label' "$SRC/ui/components/CommonUi.kt" \
  || fail "LanPill 仍写死 LAN"
grep -q 'fun plan(synced: String, current: String)' "$SRC/session/DiffSync.kt" \
  || fail "缺 DiffSync.plan"
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

export VZ_VER_NODE="$NODE"
export VZ_VER_ROOT="$ROOT"
python3 - <<'PY'
import os, re, struct, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["VZ_VER_NODE"]
root = os.environ["VZ_VER_ROOT"]
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发", "vz-v1-chrome"]
chrome = {
    "‹", "查看", "LAN", "tailnet", "Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C", "+", "＋",
    "❯", "输入指令…", "发送", "收藏", "会话", "设置", "工作区", "‹ 返回", "‹ 工作区",
    "当前", "进行中", "空闲", "未知", "切换会话", "★", "☆", "拍照", "从相册选择",
}
PAPER_LIGHT = (0xF7, 0xF8, 0xFB)
ANSI0_LIGHT = (0xE7, 0xEA, 0xF0)
PAPER_DARK = (0x0A, 0x11, 0x20)
PURPLE = (0x67, 0x50, 0xA4)  # M3 默认 primary
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

def luma(rgb):
    return (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) // 1000

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

def on_settings(ts):
    return "诊断日志" in ts or "外观" in ts

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

def term_bounds(xml):
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
        return None
    return tuple(map(int, m.groups()))

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

readings = {}

def capture_density(tag, dpi):
    sh(adb, "shell", "wm", "density", str(dpi))
    time.sleep(1.4)
    sh(adb, "shell", "input", "keyevent", "111")
    x = navigate_session()
    ts = texts(x)
    print("SESSION_%s:" % tag, ts)
    xml_path = os.path.join(node, "ui-tree-%s.xml" % tag)
    open(xml_path, "w", encoding="utf-8").write(x)
    sh(adb, "shell", "input", "keyevent", "111")
    shot = os.path.join(node, "shot-%s-session.png" % tag)
    subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
    r = subprocess.run(["python3", os.path.join(root, "tools", "uiassert.py"), "absent", "已发送"], capture_output=True, text=True)
    if r.returncode != 0:
        note("屏上出现「已发送」 density=%s out=%s" % (tag, (r.stdout or "") + (r.stderr or "")))
    r = subprocess.run(["python3", os.path.join(root, "tools", "uiassert.py"), "absent", "claude_code"], capture_output=True, text=True)
    if r.returncode != 0:
        note("顶栏/屏上出现 claude_code density=%s out=%s" % (tag, (r.stdout or "") + (r.stderr or "")))
    title = session_title(ts)
    if not title:
        fail("没有会话显示名 density=%s 屏上有: %s" % (tag, ts))
    if "查看" not in ts:
        fail("会话页缺「查看」 density=%s" % tag)
    dpi_now, scale = density_scale()
    tb = term_bounds(x)
    if not tb:
        fail("找不到终端 SurfaceView bounds density=%s" % tag)
    x1, y1, x2, y2 = tb
    outer_dp = x1 / scale
    inner_dp = 6.0
    total_dp = outer_dp + inner_dp
    print("GAP_%s dpi=%s scale=%.3f surface.left_px=%d outer_dp=%.2f inner_dp=%.1f total_dp=%.2f view=%s"
          % (tag, dpi_now, scale, x1, outer_dp, inner_dp, total_dp, tb))
    if abs(outer_dp - 4.0) > 1.5:
        note("卡片外间距 density=%s surface.left=%.2fdp 期望≈4dp 操作数 left_px=%d scale=%.3f" % (tag, outer_dp, x1, scale))
    if abs(total_dp - 10.0) > 1.5:
        note("屏幕边到首字符 density=%s total=%.2fdp 期望 10dp outer=%.2f inner=%.1f" % (tag, total_dp, outer_dp, inner_dp))

    w, h, px = screencap_raw()
    pad_x = x1 + int(round(3 * scale))
    pad_y = y1 + int(round(8 * scale))
    paper_s = sample(w, h, px, pad_x, pad_y)
    token = next((t for t in ts if t.startswith("term-theme-")), "")
    dark = "term-theme-dark" in token
    paper_ref = PAPER_DARK if dark else PAPER_LIGHT
    ansi0_ref = (0x16, 0x20, 0x3A) if dark else ANSI0_LIGHT
    d_paper = dist(paper_s, paper_ref)
    d_ansi0 = dist(paper_s, ansi0_ref)
    print("PAPER_%s token=%s sample=%s luma=%d dist_paper=%.1f dist_ansi0=%.1f pad=(%d,%d)"
          % (tag, token, paper_s, luma(paper_s), d_paper, d_ansi0, pad_x, pad_y))
    if d_paper > 18:
        note("终端垫色偏离纸色 density=%s sample=%s paper=%s dist=%.1f" % (tag, paper_s, paper_ref, d_paper))
    if d_ansi0 + 8 < d_paper and not dark:
        note("浅底更像 ANSI0 暗格 density=%s dist_paper=%.1f dist_ansi0=%.1f" % (tag, d_paper, d_ansi0))
    if (not dark) and luma(paper_s) < 180:
        note("浅底 luma=%d < 180 density=%s sample=%s" % (luma(paper_s), tag, paper_s))

    ns = nodes(x)
    back = [n for n in ns if n[1] == "返回"]
    title_n = [n for n in ns if n[0] == title]
    lamp = [n for n in ns if n[1] in ("Idle", "Busy", "Unknown")]
    if not back:
        fail("找不到返回 content-desc density=%s" % tag)
    if not title_n:
        fail("找不到标题节点 %r density=%s" % (title, tag))
    bg_bar = sample(w, h, px, 20, max(0, back[0][4] - 4))
    b = back[0]
    tnode = title_n[0]
    back_cy = ink_center_y(w, h, px, b[6], b[4], b[7], b[5], bg_bar)
    title_cy = ink_center_y(w, h, px, tnode[6], tnode[4], tnode[7], tnode[5], bg_bar)
    if back_cy is None or title_cy is None:
        note("量不到墨迹盒 density=%s back=%s title=%s" % (tag, back_cy, title_cy))
        delta_dp = None
    else:
        delta_dp = abs(back_cy - title_cy) / scale
        print("ALIGN_%s back_ink_cy=%.1f title_ink_cy=%.1f delta_dp=%.2f lamp=%s"
              % (tag, back_cy, title_cy, delta_dp, lamp[0][1] if lamp else None))
        if delta_dp > 1.0 + 1e-6:
            note("光学对齐 density=%s 墨迹中心差 %.2fdp > 1dp back=%.1f title=%.1f" % (tag, delta_dp, back_cy, title_cy))

    # + 菜单：拍照/相册，采样容器色不得是 M3 紫
    plus = [n for n in ns if n[0] in ("+", "＋")]
    if not plus:
        note("找不到 + 按钮 density=%s 屏上有: %s" % (tag, ts))
        readings[tag] = {
            "dpi": dpi_now, "scale": scale, "outer_dp": outer_dp, "inner_dp": inner_dp,
            "total_dp": total_dp, "paper": paper_s, "dist_paper": d_paper, "dist_ansi0": d_ansi0,
            "align_dp": delta_dp, "title": title, "lamp": lamp[0][1] if lamp else None,
            "lan": "LAN" in ts, "tailnet": "tailnet" in ts, "surface": tb,
        }
        return
    plus.sort(key=lambda n: n[4])
    tap(plus[0][2], plus[0][3], 1.0)
    mx = dump()
    mts = texts(mx)
    print("MENU_%s:" % tag, mts)
    if "拍照" not in mts and "从相册选择" not in mts:
        note("+ 菜单没有拍照/相册 density=%s 屏上有: %s" % (tag, mts))
    menu_shot = os.path.join(node, "shot-%s-menu.png" % tag)
    subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(menu_shot, "wb"))
    open(os.path.join(node, "ui-tree-%s-menu.xml" % tag), "w", encoding="utf-8").write(mx)
    mw, mh, mpx = screencap_raw()
    photo = [n for n in nodes(mx) if n[0] == "拍照"]
    if photo:
        p = photo[0]
        menu_bg = sample(mw, mh, mpx, p[6] + 8, p[3])
        d_purple = dist(menu_bg, PURPLE)
        print("MENU_BG_%s sample=%s dist_m3_purple=%.1f" % (tag, menu_bg, d_purple))
        if d_purple < 40:
            note("+ 菜单仍是框架紫 density=%s sample=%s dist=%.1f" % (tag, menu_bg, d_purple))
    tap(80, 400, 0.6)
    sh(adb, "shell", "input", "keyevent", "111")

    readings[tag] = {
        "dpi": dpi_now,
        "scale": scale,
        "outer_dp": outer_dp,
        "inner_dp": inner_dp,
        "total_dp": total_dp,
        "paper": paper_s,
        "dist_paper": d_paper,
        "dist_ansi0": d_ansi0,
        "align_dp": delta_dp,
        "title": title,
        "lamp": lamp[0][1] if lamp else None,
        "lan": "LAN" in ts,
        "tailnet": "tailnet" in ts,
        "surface": tb,
    }

# 整数 3.0 与非整数 2.625
capture_density("d480", 480)
capture_density("d420", 420)

# 077：标题不是 claude_code（已在两密度断言）
# 076：点「查看」sheet 属于当前会话，不切页测灯
x = navigate_session()
ts = texts(x)
title = session_title(ts)
views = [n for n in nodes(x) if n[0] == "查看"]
if not views:
    fail("找不到「查看」")
views.sort(key=lambda n: (n[4], -n[2]))
tap(views[-1][2], views[-1][3], 1.2)
sx = dump()
sts = texts(sx)
print("SHEET:", sts)
open(os.path.join(node, "ui-tree-sheet.xml"), "w", encoding="utf-8").write(sx)
if "切换会话" not in sts:
    fail("查看菜单没有「切换会话」。屏上有: %s" % sts)
if title and title not in sts:
    fail("查看菜单没有当前显示名 %r。屏上有: %s" % (title, sts))
if any("claude_code" in t for t in sts):
    fail("查看菜单出现 claude_code")
tap(80, 200, 0.8)

# 设置页能滑到底且会话顶栏没有「设置」
x = dump()
if "设置" in texts(x) and on_session(texts(x)):
    # 底栏「设置」可以有，顶栏不能有
    top_settings = [n for n in nodes(x) if n[0] == "设置" and n[4] < 280]
    if top_settings:
        fail("会话顶栏出现「设置」 bounds_y=%s" % [n[4] for n in top_settings])

print("READINGS", readings)
if failures:
    print("FAILURES %d" % len(failures))
    for f in failures:
        print(" -", f)
    sys.exit(1)
print("PASS t.ver vz-ver")
PY
