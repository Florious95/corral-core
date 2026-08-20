#!/usr/bin/env bash
# ui-check.sh — t.bg 显式背景重映射（退出码即判据）
#
# 颜色 UI 树读不到：像素采样 + tools/uiassert.py 内容断言。
# A-bg-ui：浅色终端纸色 ≈ TerminalSpec.background，不得等于 ANSI 0 暗格；
#          切 night yes|no 后像素与 theme token 真的变。
# 整数密度 480（3.0）与非整数 440（2.75）各跑一遍。
# trap 收尾：关输入法、wm density reset、uimode auto。不碰用户 tmux、不扫 argv。
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

export VZ_BG_NODE="$NODE"
export VZ_BG_ROOT="$ROOT"
export VZ_BG_DENSITIES="${VZ_BG_DENSITIES:-480 440}"

python3 - <<'PY'
import os, re, struct, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["VZ_BG_NODE"]
root = os.environ["VZ_BG_ROOT"]
densities = [int(x) for x in os.environ["VZ_BG_DENSITIES"].split()]
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发"]
chrome = {
    "‹", "查看", "LAN", "Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C", "+", "＋",
    "❯", "输入指令…", "发送", "收藏", "会话", "设置", "工作区", "‹ 返回", "‹ 工作区",
    "当前", "进行中", "空闲", "未知", "切换会话", "★", "☆",
}
# TerminalPaletteLight.background / ansi[0]（与 TerminalSpec 同源，脚本只作采样对照）
PAPER = (0xF7, 0xF8, 0xFB)
ANSI0 = (0xE7, 0xEA, 0xF0)
PAPER_LUMA = (PAPER[0]*299 + PAPER[1]*587 + PAPER[2]*114) // 1000
ANSI0_LUMA = (ANSI0[0]*299 + ANSI0[1]*587 + ANSI0[2]*114) // 1000

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
    sh(adb, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(1.0)

def luma(rgb):
    r, g, b = rgb
    return (r*299 + g*587 + b*114) // 1000

def dist(a, b):
    return sum(abs(x-y) for x, y in zip(a, b))

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

def term_pad_xy(xml):
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

def open_session():
    sh(adb, "shell", "am", "start", "-W", "-n", "%s/%s" % (os.environ.get("PKG", "dev.agentmirror.app"), "dev.agentmirror.app.MainActivity"))
    time.sleep(1.2)
    sh(adb, "shell", "input", "keyevent", "111")
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
                time.sleep(1.6)
                sh(adb, "shell", "input", "keyevent", "111")
                x = dump()
                ts = texts(x)
        grok = next((t for t in ts if "grok" in t.lower()), None)
        cc = next((t for t in ts if "claude" in t.lower() and t not in chrome), None)
        name = grok or cc or next((n for n in preferred if n in ts), None)
        if name is None:
            names = [t for t in ts if t not in chrome and not t.startswith("term-theme-")]
            fail("进不了会话。屏上有: %s" % ts)
        hits = [n for n in nodes(x) if n[0] == name]
        if not hits:
            fail("找不到会话行 %s。屏上有: %s" % (name, ts))
        tap(hits[0][1], hits[0][2])
        time.sleep(1.4)
        sh(adb, "shell", "input", "keyevent", "111")
        x = dump()
        ts = texts(x)
    if not on_session(ts):
        fail("不在会话页。屏上有: %s" % ts)
    return x, ts

pkg = "dev.agentmirror.app"
os.environ["PKG"] = pkg

results = []
for dens in densities:
    sh(adb, "shell", "wm", "density", str(dens))
    time.sleep(0.8)
    sh(adb, "shell", "am", "force-stop", pkg)
    time.sleep(0.3)
    sh(adb, "shell", "cmd", "uimode", "night", "no")
    time.sleep(0.3)
    x, ts = open_session()
    tok_light = theme_token(ts)
    sx, sy, x1, y1, x2, y2 = term_pad_xy(x)
    w, h, px = screencap_raw()
    light = sample(w, h, px, sx, sy)
    light_y = luma(light)
    d_paper = dist(light, PAPER)
    d_ansi0 = dist(light, ANSI0)
    print("DENSITY", dens, "TOKEN_LIGHT", tok_light)
    print("TERM_BG_LIGHT", light, "luma", light_y, "dist_paper", d_paper, "dist_ansi0", d_ansi0,
          "paperLuma", PAPER_LUMA, "ansi0Luma", ANSI0_LUMA, "pad", (sx, sy), "view", (x1, y1, x2, y2))
    open(os.path.join(node, "ui-tree-d%s-light.xml" % dens), "w", encoding="utf-8").write(x)
    if tok_light is None or "term-theme-light" not in tok_light:
        fail("density=%s 浅色 theme token 不对 %s" % (dens, tok_light))
    if light_y < 180:
        fail("density=%s 浅色终端底过暗 luma=%s rgb=%s（grok 整屏不得是黑）" % (dens, light_y, light))
    if d_ansi0 < d_paper:
        fail("density=%s 浅色底更接近 ANSI0 暗格 %s dist_ansi0=%s dist_paper=%s" % (
            dens, light, d_ansi0, d_paper))
    if d_paper > 48:
        fail("density=%s 浅色底偏离 paper %s dist=%s" % (dens, light, d_paper))

    sh(adb, "shell", "cmd", "uimode", "night", "yes")
    time.sleep(1.4)
    sh(adb, "shell", "input", "keyevent", "111")
    x = dump()
    ts = texts(x)
    tok_dark = theme_token(ts)
    sx2, sy2, *_ = term_pad_xy(x)
    w2, h2, px2 = screencap_raw()
    darkp = sample(w2, h2, px2, sx2, sy2)
    print("DENSITY", dens, "TOKEN_DARK", tok_dark, "TERM_BG_DARK", darkp, "luma", luma(darkp))
    if tok_dark is None or "term-theme-dark" not in tok_dark:
        fail("density=%s 深色 theme token 不对 %s" % (dens, tok_dark))
    if tok_light == tok_dark:
        fail("density=%s 切深浅色 token 没变 %s" % (dens, tok_light))
    if light == darkp:
        fail("density=%s 切深浅色像素没变 %s" % (dens, light))
    if luma(darkp) >= light_y:
        fail("density=%s 深色底 luma=%s 应低于浅色 luma=%s" % (dens, luma(darkp), light_y))
    results.append((dens, light, darkp, tok_light, tok_dark))
    shot = os.path.join(node, "shot-d%s-dark.png" % dens)
    subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
    print("SHOT", shot)

r = subprocess.run(
    ["python3", os.path.join(root, "tools", "uiassert.py"), "absent", "claude_code"],
    capture_output=True, text=True,
)
if r.returncode != 0:
    fail("uiassert absent claude_code: %s" % (r.stdout or r.stderr))

print("PASS t.bg densities", results)
PY
echo "PASS ui-check.sh"
