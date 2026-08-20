#!/usr/bin/env bash
# ui-check.sh — 078 §2 终端主题根因探针（退出码即判据）
#
# A-th-bg：
#  ① 浅色：整体浅底，用户消息块比它更深
#  ② 深色：关系反过来且可辨
#  ③ 切换系统深浅色，两套都生效（背景真的变了）
# 另断言数据来源 source=app-theme（写死浅色骗「现在是浅」会在③/来源上红）。
#
# 自足：自己 reverse、拉 App、离会话、进列表、切 uimode、采像素、uiassert。
# trap 收尾（关输入法、把 night 拨回 no）。不起 daemon、不碰用户默认 tmux、不扫 argv。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$NODE/../../.." && pwd)"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.agentmirror.app
ACTIVITY=dev.agentmirror.app.MainActivity

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  "$ADB" shell cmd uimode night no >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -x "$ADB" ] || fail "adb 不在 $ADB"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"

export UX_THEME_NODE="$NODE"
export UX_THEME_ROOT="$ROOT"
python3 - <<'PY'
import os, re, subprocess, sys, time, collections
from PIL import Image
from io import BytesIO

node = os.environ["UX_THEME_NODE"]
root = os.environ["UX_THEME_ROOT"]
adb = os.environ["ADB"]
uiassert = [sys.executable, os.path.join(root, "tools", "uiassert.py")]
preferred = ["远控 leader", "team-leader-2", "leader"]

def fail(msg):
    print("FAIL " + msg)
    sys.exit(1)

def sh(*args):
    return subprocess.run(list(args), capture_output=True, timeout=60)

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

def descs(x):
    return [t for t in re.findall(r'content-desc="([^"]*)"', x) if t.strip()]

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

def start_app():
    subprocess.check_call([adb, "shell", "am", "start", "-W", "-n", "dev.agentmirror.app/dev.agentmirror.app.MainActivity"],
                          stdout=subprocess.DEVNULL)
    time.sleep(1.0)
    subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
    time.sleep(0.3)

def wait_names(x, ts, tries=6):
    for i in range(tries):
        if any(n in ts for n in preferred):
            return x, ts
        time.sleep(0.7)
        x, ts = dump_texts()
        print("wait_names try=%d" % (i + 1))
    return x, ts

def navigate_session():
    start_app()
    time.sleep(1.2)
    x, ts = dump_texts()
    if on_pairing(ts):
        fail("停在配对页，探针无法进会话。屏上=" + str(ts))
    if on_session(ts):
        return x, ts, next((n for n in preferred if n in ts), "session")
    # 底栏三格：停在收藏/设置时回到「会话」
    if not any(n in ts for n in preferred) and "会话" in ts:
        tap_text(x, "会话", prefer="bottom")
        time.sleep(0.8)
        x, ts = dump_texts()
    if any("重新配对" in t or "诊断日志" in t or "字号" in t for t in ts):
        tap_text(x, "会话", prefer="bottom")
        time.sleep(0.8)
        x, ts = dump_texts()
    # 一级工作区列表：精确点「远程Agent安卓」行（不要点子串路径）
    if not any(n in ts for n in preferred) and any(t == "远程Agent安卓" for t in ts) and not any(
        "‹ 工作区" in t or t == "‹ 工作区" for t in ts
    ):
        tap_text(x, "远程Agent安卓", prefer="top")
        time.sleep(1.4)
        x, ts = dump_texts()
    # 二级空列表：回一级再进，等 listing
    if any(t in ("‹ 工作区",) or "‹ 工作区" in t for t in ts) and not any(n in ts for n in preferred):
        tap_text(x, "工作区", prefer="top")
        time.sleep(1.0)
        x, ts = dump_texts()
        if any(t == "远程Agent安卓" for t in ts):
            tap_text(x, "远程Agent安卓", prefer="top")
            time.sleep(1.4)
            x, ts = dump_texts()
    x, ts = wait_names(x, ts)
    chosen = next((n for n in preferred if n in ts), None)
    if chosen is None:
        fail("列表里没有 claude code 显示名 %s。屏上=%s" % (preferred, ts))
    if not tap_text(x, chosen, prefer="top"):
        fail("点不了列表显示名 " + chosen)
    time.sleep(0.8)
    subprocess.check_call([adb, "shell", "input", "keyevent", "111"])
    time.sleep(0.3)
    x, ts = dump_texts()
    if not on_session(ts):
        fail("点列表后没有进会话页。屏上=" + str(ts))
    return x, ts, chosen

def set_night(yes):
    mode = "yes" if yes else "no"
    subprocess.check_call([adb, "shell", "cmd", "uimode", "night", mode])
    print("uimode night " + mode)
    time.sleep(2.2)

def term_bounds(x):
    m = re.search(
        r'ViewFactoryHolder[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        x,
    )
    if not m:
        m = re.search(
            r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*ViewFactoryHolder',
            x,
        )
    if not m:
        fail("UI 树里找不到终端 ViewFactoryHolder bounds")
    return tuple(map(int, m.groups()))

def screenshot():
    png = subprocess.check_output([adb, "exec-out", "screencap", "-p"])
    if png[:8] != b"\x89PNG\r\n\x1a\n":
        fail("screencap 不是 PNG")
    return png

def luma(c):
    r, g, b = c
    return (r * 299 + g * 587 + b * 114) // 1000

def quant(c):
    return (c[0] // 8 * 8, c[1] // 8 * 8, c[2] // 8 * 8)

def pixels_of(crop):
    return list(crop.getdata())

def analyze(png, bounds):
    img = Image.open(BytesIO(png)).convert("RGB")
    x1, y1, x2, y2 = bounds
    x1 = max(0, min(x1, img.width - 2))
    x2 = max(x1 + 1, min(x2, img.width))
    y1 = max(0, min(y1, img.height - 2))
    y2 = max(y1 + 1, min(y2, img.height))
    crop = img.crop((x1, y1, x2, y2))
    inset = 8
    if crop.width > 2 * inset and crop.height > 2 * inset:
        crop = crop.crop((inset, inset, crop.width - inset, crop.height - inset))
    cnt = collections.Counter(quant(tuple(p[:3])) for p in pixels_of(crop))
    total = sum(cnt.values())
    canvas, n0 = cnt.most_common(1)[0]
    others = []
    gray_blocks = []
    for c, n in cnt.most_common(40):
        if c == canvas:
            continue
        if n / total < 0.006:
            continue
        lu = luma(c)
        d = abs(lu - luma(canvas))
        if d < 8:
            continue
        rec = (c, n, lu)
        others.append(rec)
        # 用户消息块 = Claude Code 的 48;5;254 灰（~228），不是正文深色字（luma~24）。
        if 190 <= lu <= 242:
            gray_blocks.append(rec)
    return {
        "canvas": canvas,
        "canvas_luma": luma(canvas),
        "canvas_frac": n0 / total,
        "others": others,
        "gray_blocks": gray_blocks,
        "size": (crop.width, crop.height),
        "bounds": bounds,
    }

def swipe_term(bounds):
    # 手指下拖 = 看更早历史（TermSurfaceView：正 deltaLines）。
    x1, y1, x2, y2 = bounds
    cx = (x1 + x2) // 2
    y_from = y1 + int((y2 - y1) * 0.30)
    y_to = y1 + int((y2 - y1) * 0.78)
    subprocess.check_call([adb, "shell", "input", "swipe", str(cx), str(y_from), str(cx), str(y_to), "320"])
    time.sleep(0.7)

def capture_with_gray(x, tag):
    b = term_bounds(x)
    png = screenshot()
    a = analyze(png, b)
    for i in range(8):
        if a["gray_blocks"]:
            break
        print("no gray block yet, swipe term try=%d" % (i + 1))
        swipe_term(b)
        png = screenshot()
        x = tree()
        b = term_bounds(x)
        a = analyze(png, b)
    save(png, "session-%s.png" % tag)
    open(os.path.join(node, "session-%s.ui.xml" % tag), "w", encoding="utf-8").write(x)
    return x, png, a, b

def save(png, name):
    path = os.path.join(node, name)
    open(path, "wb").write(png)
    print("SHOT " + path)
    return path

# ---- 浅色 ----
set_night(False)
x, ts, chosen = navigate_session()
x, png_l, a_l, b_l = capture_with_gray(x, "light")
ts = texts(x)
print(
    "operands light canvas=%s luma=%d frac=%.3f bounds=%s gray=%s others=%s token_text=%s descs=%s"
    % (
        a_l["canvas"],
        a_l["canvas_luma"],
        a_l["canvas_frac"],
        a_l["bounds"],
        [(c, lu, n) for c, n, lu in a_l["gray_blocks"][:4]],
        [(c, lu, n) for c, n, lu in a_l["others"][:6]],
        [t for t in ts if "term-theme" in t or "source=app-theme" in t],
        [d for d in descs(x) if "term-theme" in d or "source=" in d],
    )
)

r_has = subprocess.run(uiassert + ["has", "返回", "查看"])
print("uiassert has 返回 查看 rc=%d" % r_has.returncode)
if r_has.returncode != 0:
    fail("浅色会话壳缺失 uiassert rc=%d" % r_has.returncode)
r_src = subprocess.run(uiassert + ["has", "term-theme-light", "source=app-theme"])
print("uiassert has term-theme-light source=app-theme rc=%d" % r_src.returncode)
blob = " ".join(ts + descs(x))
print("operands light tree_blob_has_token=%s" % ("term-theme-light" in blob and "source=app-theme" in blob))
if r_src.returncode != 0 and not ("term-theme-light" in blob and "source=app-theme" in blob):
    fail("浅色 UI 树没有数据来源标签 term-theme-light source=app-theme（text+content-desc 都没有）")

if a_l["canvas_luma"] < 180:
    fail("浅色模式整体底仍是深色 luma=%d canvas=%s —— 写死 DEFAULT_BG 深蓝黑就会在这里红" % (a_l["canvas_luma"], a_l["canvas"]))
darker = [o for o in a_l["gray_blocks"] if o[2] < a_l["canvas_luma"] - 8]
if not darker:
    fail("浅色：没有比整体底更深的灰消息块（拒把正文深字当灰块）。canvas_luma=%d gray=%s others=%s" % (a_l["canvas_luma"], a_l["gray_blocks"][:6], a_l["others"][:8]))
print("PASS light: canvas luma=%d darker_gray=%s source=app-theme" % (a_l["canvas_luma"], darker[0]))

# ---- 深色（切 uimode 会重建 Activity；若仍停在会话页就别再点返回，否则会掉进收藏/一级）----
set_night(True)
start_app()
x2, ts2 = dump_texts()
if not on_session(ts2):
    x2, ts2, _ = navigate_session()
x2, png_d, a_d, b_d = capture_with_gray(x2, "dark")
ts2 = texts(x2)
print(
    "operands dark canvas=%s luma=%d frac=%.3f bounds=%s gray=%s others=%s token_text=%s descs=%s"
    % (
        a_d["canvas"],
        a_d["canvas_luma"],
        a_d["canvas_frac"],
        a_d["bounds"],
        [(c, lu, n) for c, n, lu in a_d["gray_blocks"][:4]],
        [(c, lu, n) for c, n, lu in a_d["others"][:6]],
        [t for t in ts2 if "term-theme" in t or "source=app-theme" in t],
        [d for d in descs(x2) if "term-theme" in d or "source=" in d],
    )
)

r_has2 = subprocess.run(uiassert + ["has", "返回", "查看"])
print("uiassert has 返回 查看 rc=%d" % r_has2.returncode)
if r_has2.returncode != 0:
    fail("深色会话壳缺失 uiassert rc=%d" % r_has2.returncode)
r_src2 = subprocess.run(uiassert + ["has", "term-theme-dark", "source=app-theme"])
print("uiassert has term-theme-dark source=app-theme rc=%d" % r_src2.returncode)
r_abs = subprocess.run(uiassert + ["absent", "term-theme-light"])
print("uiassert absent term-theme-light rc=%d" % r_abs.returncode)
blob2 = " ".join(ts2 + descs(x2))
print("operands dark tree_blob_has_token=%s" % ("term-theme-dark" in blob2 and "source=app-theme" in blob2))
if not ("term-theme-dark" in blob2 and "source=app-theme" in blob2):
    fail("深色 UI 树没有数据来源标签 term-theme-dark source=app-theme")
if "term-theme-light" in blob2:
    fail("深色模式仍残留 term-theme-light")

if a_d["canvas_luma"] > 80:
    fail("深色模式整体底不是深色 luma=%d canvas=%s" % (a_d["canvas_luma"], a_d["canvas"]))
lighter = [o for o in a_d["gray_blocks"] if o[2] > a_d["canvas_luma"] + 8]
if not lighter:
    fail("深色：没有与整体底可辨的浅灰消息块。canvas_luma=%d gray=%s others=%s" % (a_d["canvas_luma"], a_d["gray_blocks"][:6], a_d["others"][:8]))
print("PASS dark: canvas luma=%d lighter_gray=%s source=app-theme" % (a_d["canvas_luma"], lighter[0]))

# ---- 切换真的变了 ----
if a_l["canvas"] == a_d["canvas"] or abs(a_l["canvas_luma"] - a_d["canvas_luma"]) < 40:
    fail(
        "切换深浅色后终端背景没变 light=%s/%d dark=%s/%d —— 只验一套或写死单色会在这里红"
        % (a_l["canvas"], a_l["canvas_luma"], a_d["canvas"], a_d["canvas_luma"])
    )
print(
    "PASS switch: light_luma=%d dark_luma=%d canvas_changed=1"
    % (a_l["canvas_luma"], a_d["canvas_luma"])
)
print("PASS A-th-bg light/dark/switch + source=app-theme")
sys.exit(0)
PY
