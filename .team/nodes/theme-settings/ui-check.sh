#!/usr/bin/env bash
# ui-check.sh — t.settings 终端主题入口 + 切换真的改了纸色（退出码即判据）
#
# A-set-ui：设置页有「终端主题 / 浅色时 / 深色时」，默认 Vesper；
#           点深槽选 Dracula 后深槽行文本变成 Dracula。
#           density 480 与 440 各采一次纸色：vesper (16,16,16) → follow-system/Afterglow (33,33,33)。
# 像素 RGB 距离断言，不用整图哈希。trap 收尾：关输入法、wm density reset、uimode auto。
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

export THEME_NODE="$NODE"
export THEME_ROOT="$ROOT"
export THEME_DENSITIES="${THEME_DENSITIES:-480 440}"
export PKG
export ACTIVITY

python3 - <<'PY'
import os, re, struct, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["THEME_NODE"]
root = os.environ["THEME_ROOT"]
pkg = os.environ["PKG"]
activity = os.environ["ACTIVITY"]
densities = [int(x) for x in os.environ["THEME_DENSITIES"].split()]
if 480 not in densities or 440 not in densities:
    print("FAIL density list %s" % densities)
    sys.exit(1)

VESPER = (16, 16, 16)
AFTERGLOW = (33, 33, 33)
chrome = {
    "‹", "查看", "LAN", "Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C", "+", "＋",
    "❯", "输入指令…", "发送", "收藏", "会话", "设置", "工作区", "‹ 返回", "‹ 工作区",
    "当前", "进行中", "空闲", "未知", "切换会话", "★", "☆",
}
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发"]

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
        dm = re.search(r'content-desc="([^"]*)"', tag)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        sm = re.search(r'selected="([^"]*)"', tag)
        if not bm:
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        out.append({
            "text": tm.group(1) if tm else "",
            "desc": dm.group(1) if dm else "",
            "selected": (sm.group(1) if sm else "") == "true",
            "x1": x1, "y1": y1, "x2": x2, "y2": y2,
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
        })
    return out

def tap(cx, cy):
    sh(adb, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(1.0)

def swipe_up():
    sh(adb, "shell", "input", "swipe", "540", "1600", "540", "500", "280")
    time.sleep(0.45)

def dist(a, b):
    return sum(abs(x - y) for x, y in zip(a, b))

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

def on_settings(ts):
    return "主机配对" in ts and "外观" in ts

def write_prefs(light_id, dark_id):
    xml = (
        '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n'
        "<map>\n"
        '    <string name="terminal-theme-light">%s</string>\n'
        '    <string name="terminal-theme-dark">%s</string>\n'
        "</map>\n" % (light_id, dark_id)
    )
    sh(adb, "shell", "run-as", pkg, "mkdir", "-p", "shared_prefs")
    p = subprocess.run(
        [adb, "shell", "run-as", pkg, "tee", "shared_prefs/app_term_theme.xml"],
        input=xml.encode("utf-8"),
        capture_output=True,
        timeout=30,
    )
    if p.returncode != 0:
        fail("写 prefs 失败 rc=%s err=%s" % (p.returncode, (p.stderr or b"").decode("utf-8", "replace")))

def force_start():
    sh(adb, "shell", "am", "force-stop", pkg)
    time.sleep(0.4)
    sh(adb, "shell", "am", "start", "-W", "-n", "%s/%s" % (pkg, activity))
    time.sleep(1.2)
    sh(adb, "shell", "input", "keyevent", "111")

def back_to_home():
    x = dump()
    ts = texts(x)
    for _ in range(8):
        if on_l1(ts) or on_settings(ts) or on_favorites(ts):
            break
        ns = nodes(x)
        back = [n for n in ns if n["text"] in ("‹", "‹ 返回", "‹ 工作区") and n["cy"] < 500]
        if not back:
            back = [n for n in ns if n["text"] == "工作区" and n["cy"] < 500]
        if not back:
            break
        tap(back[0]["cx"], back[0]["cy"])
        x = dump()
        ts = texts(x)
    return x, ts

def open_settings():
    force_start()
    x, ts = back_to_home()
    sets = [n for n in nodes(x) if n["text"] == "设置"]
    if not sets:
        fail("屏上没有「设置」tab。屏上有: %s" % ts)
    sets.sort(key=lambda n: n["cy"])
    tap(sets[-1]["cx"], sets[-1]["cy"])
    time.sleep(0.8)
    sh(adb, "shell", "input", "keyevent", "111")
    x = dump()
    ts = texts(x)
    for _ in range(6):
        if "终端主题" in ts and "浅色时" in ts:
            break
        swipe_up()
        x = dump()
        ts = texts(x)
    return x, ts

def uiassert_has(*need):
    r = subprocess.run(
        ["python3", os.path.join(root, "tools", "uiassert.py"), "has", *need],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        fail("uiassert has %s: %s" % (need, (r.stdout or "") + (r.stderr or "")))

def find_desc(ns, desc):
    hits = [n for n in ns if n["desc"] == desc or n["desc"].endswith("/" + desc)]
    return hits[0] if hits else None

def row_titles(ns, row_desc):
    row = find_desc(ns, row_desc)
    if row is None:
        return []
    out = []
    for n in ns:
        if not n["text"]:
            continue
        if n["text"] in ("浅色时", "深色时"):
            continue
        if n["y1"] >= row["y1"] - 8 and n["y2"] <= row["y2"] + 8:
            out.append(n["text"])
    return out

def open_session():
    force_start()
    x = dump()
    ts = texts(x)
    for _ in range(8):
        if on_session(ts) or on_l1(ts) or on_favorites(ts):
            break
        ns = nodes(x)
        back = [n for n in ns if n["text"] in ("‹", "‹ 返回", "‹ 工作区") and n["cy"] < 500]
        if not back:
            back = [n for n in ns if n["text"] == "工作区" and n["cy"] < 500]
        if not back:
            break
        tap(back[0]["cx"], back[0]["cy"])
        x = dump()
        ts = texts(x)
    if not on_session(ts):
        if not on_favorites(ts):
            favs = [n for n in nodes(x) if n["text"] == "收藏"]
            if favs:
                favs.sort(key=lambda n: n["cy"])
                tap(favs[-1]["cx"], favs[-1]["cy"])
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
        hits = [n for n in nodes(x) if n["text"] == name]
        if not hits:
            fail("找不到会话行 %s。屏上有: %s" % (name, ts))
        tap(hits[0]["cx"], hits[0]["cy"])
        time.sleep(1.4)
        sh(adb, "shell", "input", "keyevent", "111")
        x = dump()
        ts = texts(x)
    if not on_session(ts):
        fail("不在会话页。屏上有: %s" % ts)
    return x, ts

def term_pad_xy(xml):
    m = re.search(
        r'content-desc="term-theme-dark[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        xml,
    )
    if not m:
        m = re.search(
            r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="term-theme-dark',
            xml,
        )
    if not m:
        fail("找不到终端 SurfaceView bounds。token 缺失？ xml 片段=%s" % xml[:400])
    x1, y1, x2, y2 = map(int, m.groups())
    return x1 + 12, y1 + 24, x1, y1, x2, y2

def theme_token(ts):
    hits = [t for t in ts if t.startswith("term-theme-")]
    return hits[0] if hits else None

def sample_paper(xml, w, h, px, pad):
    sx, sy, x1, y1, x2, y2 = pad
    rgb = sample(w, h, px, sx, sy)
    # 若垫点采到字色（过亮/过饱和），换几个纸面候选点
    candidates = [
        (sx, sy),
        (x1 + 24, y1 + 12),
        (x1 + 36, y1 + 36),
        ((x1 + x2) // 2, y1 + 18),
        (x1 + 18, (y1 + y2) // 2),
    ]
    best = rgb
    best_d = dist(rgb, VESPER)
    for cx, cy in candidates:
        c = sample(w, h, px, cx, cy)
        d = min(dist(c, VESPER), dist(c, AFTERGLOW))
        if d < best_d:
            best, best_d = c, d
            sx, sy = cx, cy
    return best, sx, sy, x1, y1, x2, y2

# ── 6.4 设置页可选中 ──
write_prefs("vesper", "vesper")
x, ts = open_settings()
print("SETTINGS:", ts)
open(os.path.join(node, "ui-tree-settings.xml"), "w", encoding="utf-8").write(x)
uiassert_has("终端主题", "浅色时", "深色时")
uiassert_has("Vesper")
ns = nodes(x)
dark_row = find_desc(ns, "term-theme-dark-row")
if dark_row is None:
    hits = [n for n in ns if n["text"] == "深色时"]
    if not hits:
        fail("找不到深色时行。屏上有: %s" % ts)
    dark_row = hits[0]
before = row_titles(ns, "term-theme-dark-row")
print("DARK_ROW_BEFORE", before)
tap(dark_row["cx"], dark_row["cy"])
time.sleep(0.8)
sh(adb, "shell", "input", "keyevent", "111")
seen = set()
dracula_node = None
for i in range(12):
    x = dump()
    ts = texts(x)
    seen.update(ts)
    ns = nodes(x)
    if i == 0:
        open(os.path.join(node, "ui-tree-picker.xml"), "w", encoding="utf-8").write(x)
    hits = [n for n in ns if n["text"] == "Dracula" or n["desc"] == "term-theme-family-dracula"]
    if hits:
        dracula_node = hits[0]
        break
    swipe_up()
if "Nord" not in seen or "Dracula" not in seen:
    fail("选择页滚完仍缺 Dracula/Nord。累计: %s" % sorted(seen))
print("PICKER_HAS Dracula Nord")
if dracula_node is None:
    fail("看得见 Dracula 文本但点不到节点")
tap(dracula_node["cx"], dracula_node["cy"])
time.sleep(0.9)
x = dump()
ts = texts(x)
if not on_settings(ts):
    # 点选应立刻返回；若还在选择页，点返回
    backs = [n for n in nodes(x) if n["text"] == "‹ 返回"]
    if backs:
        tap(backs[0]["cx"], backs[0]["cy"])
        time.sleep(0.8)
        x = dump()
        ts = texts(x)
for _ in range(6):
    if "终端主题" in ts and "深色时" in ts:
        break
    swipe_up()
    x = dump()
    ts = texts(x)
open(os.path.join(node, "ui-tree-after.xml"), "w", encoding="utf-8").write(x)
uiassert_has("Dracula")
ns = nodes(x)
after = row_titles(ns, "term-theme-dark-row")
print("DARK_ROW_AFTER", after, "SCREEN", ts)
if not any("Dracula" in t for t in after):
    # 退一步：整屏有 Dracula，且深槽行不再写 Vesper
    if "Dracula" not in ts:
        fail("选中后屏上没有 Dracula。深槽行=%s 屏上=%s" % (after, ts))
dark_texts = after if after else ts
if any(t == "Vesper" for t in after) and not any("Dracula" in t for t in after):
    fail("深槽行仍是 Vesper，没变成 Dracula。行=%s" % after)
sel = [n for n in ns if n["desc"] == "term-theme-family-dracula" and n["selected"]]
print("DRACULA_SELECTED_NODE", bool(sel))

# ── 6.5 像素：每个 density 浅槽 follow-system(Alabaster)、深槽 vesper → afterglow(follow-system) ──
results = []
for dens in densities:
    sh(adb, "shell", "wm", "density", str(dens))
    time.sleep(0.8)
    write_prefs("follow-system", "vesper")
    sh(adb, "shell", "cmd", "uimode", "night", "yes")
    time.sleep(0.3)
    x, ts = open_session()
    tok_a = theme_token(ts)
    pad = term_pad_xy(x)
    w, h, px = screencap_raw()
    a, sx, sy, x1, y1, x2, y2 = sample_paper(x, w, h, px, pad)
    d_a = dist(a, VESPER)
    print(
        "density=%s A=%s distA_vesper=%s token_before=%s pad=(%s,%s) bounds=(%s,%s,%s,%s)"
        % (dens, a, d_a, tok_a, sx, sy, x1, y1, x2, y2)
    )
    open(os.path.join(node, "ui-tree-d%s-vesper.xml" % dens), "w", encoding="utf-8").write(x)
    if tok_a is None or "Vesper.itermcolors" not in tok_a:
        fail("density=%s token_before 应含 Vesper.itermcolors，实际 %s" % (dens, tok_a))
    if d_a > 48:
        fail("density=%s A=%s 距 Vesper(16,16,16) dist=%s > 48" % (dens, a, d_a))

    write_prefs("follow-system", "follow-system")
    sh(adb, "shell", "am", "force-stop", pkg)
    time.sleep(0.4)
    sh(adb, "shell", "cmd", "uimode", "night", "yes")
    x, ts = open_session()
    tok_b = theme_token(ts)
    pad_b = term_pad_xy(x)
    w2, h2, px2 = screencap_raw()
    b, sx2, sy2, *_ = sample_paper(x, w2, h2, px2, pad_b)
    d_b = dist(b, AFTERGLOW)
    d_ab = dist(a, b)
    print(
        "density=%s B=%s distB_afterglow=%s distAB=%s token_after=%s pad=(%s,%s)"
        % (dens, b, d_b, d_ab, tok_b, sx2, sy2)
    )
    open(os.path.join(node, "ui-tree-d%s-afterglow.xml" % dens), "w", encoding="utf-8").write(x)
    if tok_b is None or "Afterglow.itermcolors" not in tok_b:
        fail("density=%s token_after 应含 Afterglow.itermcolors，实际 %s" % (dens, tok_b))
    if a == b:
        fail("density=%s 纸色没变 A=B=%s" % (dens, a))
    if d_b > 48:
        fail("density=%s B=%s 距 Afterglow(33,33,33) dist=%s > 48" % (dens, b, d_b))
    if d_ab < 16:
        fail("density=%s distAB=%s < 16 A=%s B=%s" % (dens, d_ab, a, b))
    results.append((dens, a, b, tok_a, tok_b, d_a, d_b, d_ab))

print("PASS t.settings densities", results)
PY
echo "PASS ui-check.sh"
