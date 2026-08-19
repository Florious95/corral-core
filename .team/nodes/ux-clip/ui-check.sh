#!/usr/bin/env bash
# A-cl-col0 根因探针：会话页终端第 0 列（●）必须完整可见。
# 绿：UI 树到了会话页，且截图像素里左缘 ● 类色块不被 clip（宽≈高，且不贴 x=0 被切）。
# 红：到不了会话 / 取不到树 / ● 色块贴左缘且宽明显小于高（被裁半）。
set -u
set -o pipefail

ROOT="/Volumes/nvme/Projects/远程Agent安卓"
OUT="$ROOT/.team/nodes/ux-clip"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
UIASSERT=(python3 "$ROOT/tools/uiassert.py")
SHOT="$OUT/session-left-edge.png"
XML="$OUT/ui.xml"

fail() { echo "FAIL $1"; exit 1; }
pass() { echo "PASS $1"; }

mkdir -p "$OUT"
# 本脚本不拉起 daemon/node/tmux。只调已有 adb。
trap 'true' EXIT

[ -x "$ADB" ] || fail "adb 不存在：$ADB"
"$ADB" devices | grep -q 'emulator-5554[[:space:]]*device' || fail "emulator-5554 不在线"
"$ADB" reverse tcp:9900 tcp:9900 >/dev/null || fail "adb reverse tcp:9900 失败"

# 关输入法（ESC，不用 BACK）
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true

"$ADB" shell am start -n dev.agentmirror.app/.MainActivity >/dev/null 2>&1 || true
sleep 1

# 自导航：配对页失败；一级点工作区；二级点会话；已在会话则留下。
python3 - "$ADB" <<'PY' || fail "导航失败"
import re, subprocess, sys, time
ADB = sys.argv[1]

def dump():
    r = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"],
                       capture_output=True, timeout=60)
    x = r.stdout.decode("utf-8", "replace")
    if "<hierarchy" not in x:
        print("⛔ 取不到 UI 树")
        sys.exit(2)
    return x

def texts(x):
    return [t for t in re.findall(r'text="([^"]*)"', x) if t.strip()]

def tap_text(x, needle):
    for n in re.findall(r"<node [^>]*>", x):
        t = re.search(r'text="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not t or not b:
            continue
        if t.group(1) != needle and needle not in t.group(1):
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if y2 - y1 < 20:
            continue
        subprocess.check_call([ADB, "shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2)])
        return True
    return False

def on_session(ts):
    return any("返回" in t and "工作区" not in t for t in ts)

def on_l2(ts):
    return any("工作区" in t and t.startswith("‹") for t in ts) or (
        any(t == "空闲" or t == "进行中" for t in ts) and any("‹" in t for t in ts)
    )

x = dump()
ts = texts(x)
print("texts:", ts[:24])
if any(k in "".join(ts) for k in ("重新配对", "扫码", "服务端地址")):
    print("⛔ 停在配对页，测不了终端左缘")
    sys.exit(3)
if on_session(ts):
    print("already on session")
    sys.exit(0)

# 若停在别的工作区二级，先返回一级再进「远程Agent安卓」（对照图同一会话线）。
if on_l2(ts) and not any("远程Agent安卓" in t for t in ts):
    if tap_text(x, "‹ 工作区") or tap_text(x, "工作区"):
        print("back to L1 from other workspace")
        time.sleep(1.0)
        x = dump()
        ts = texts(x)
        print("after back:", ts[:20])

# 一级：点「远程Agent安卓」工作区（对照图同一条线）；没有再点第一个带路径的行的上一行。
if not on_l2(ts):
    target = None
    for t in ts:
        if "远程Agent安卓" in t and not t.startswith("/"):
            target = t
            break
    if target is None:
        for t in ts:
            if t not in {"工作区", "收藏", "会话", "设置", "LAN", "❯"} and not t.startswith("/") and "个会话" not in t:
                target = t
                break
    if not target or not tap_text(x, target):
        print("⛔ 一级点不进工作区", ts[:20])
        sys.exit(4)
    print("tapped workspace", target)
    time.sleep(1.2)
    x = dump()
    ts = texts(x)
    print("after L1:", ts[:20])

if on_session(ts):
    print("navigated to session")
    sys.exit(0)

# 二级：优先点带 leader / 进行中 的会话名
prefer = [t for t in ts if "leader" in t.lower() or "远控" in t]
skip = {"‹ 工作区", "工作区", "LAN", "★", "☆", "空闲", "进行中", "未知", "查看"}
target = prefer[0] if prefer else None
if target is None:
    for t in ts:
        if t in skip or t.startswith("/") or t.startswith("‹") or len(t) < 2:
            continue
        target = t
        break
if not target or not tap_text(x, target):
    print("⛔ 二级点不进会话", ts[:24])
    sys.exit(5)
print("tapped session", target)
time.sleep(1.5)
x = dump()
ts = texts(x)
if not on_session(ts):
    print("⛔ 点完仍不在会话页", ts[:20])
    sys.exit(6)
print("navigated to session")
PY

# 关输入法再断言 / 截图
"$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
sleep 0.3

"${UIASSERT[@]}" has "返回" || fail "uiassert has 返回"
"${UIASSERT[@]}" has "查看" || fail "uiassert has 查看"
"${UIASSERT[@]}" save "$XML" >/dev/null || fail "uiassert save"

# 等一帧终端画上（进会话后画面可能晚于顶栏）。
sleep 1.2
"$ADB" exec-out screencap -p > "$SHOT" || fail "screencap"
[ -s "$SHOT" ] || fail "截图空"

# 像素判据：终端画布左缘的 ● 色块不得被裁。
# 坏基线实拍：橙点 min_x=0、高 46、可见宽 36（宽/高=0.78）——贴 clip 边且不圆。
# 修后：色块完整（宽≈高）且左缘离开 x=0。
python3 - "$SHOT" "$XML" "$ADB" <<'PY' || fail "左缘 ● 仍被 clip（或量不到）"
import re, sys
from collections import Counter
from PIL import Image

shot, xml_path = sys.argv[1], sys.argv[2]
xml = open(xml_path, encoding="utf-8").read()
# argv[3] 可能是 adb，忽略
# 终端画布：ViewFactoryHolder，最大那块
best = None
for n in re.findall(r"<node [^>]*>", xml):
    cls = re.search(r'class="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not cls or not b:
        continue
    if "ViewFactoryHolder" not in cls.group(1) and "termview" not in cls.group(1):
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    area = (x2-x1)*(y2-y1)
    if best is None or area > best[-1]:
        best = (x1, y1, x2, y2, area)
if best is None:
    # 退化：顶栏下、底栏上
    x1, y1, x2, y2 = 0, 216, 1260, 2356
    print("WARN no ViewFactoryHolder, fallback bounds", x1, y1, x2, y2)
else:
    x1, y1, x2, y2, _ = best
    print("term bounds", x1, y1, x2, y2)

im = Image.open(shot).convert("RGBA")
W, H = im.size
x1, y1, x2, y2 = max(0, x1), max(0, y1), min(W, x2), min(H, y2)
content_left = x1
print("contentLeft", content_left, "image", W, H)

def is_orange(p):
    r, g, b = p[:3]
    return r > 140 and g > 80 and b < 90 and r > g > b

def dist(a, b):
    return sum(abs(a[i] - b[i]) for i in range(3))

# 右缘 8px 中位数 = 终端底色（右边距是空的）
bg_s = []
for y in range(y1, min(y1 + 400, y2), 3):
    for x in range(max(x1, x2 - 8), x2):
        bg_s.append(im.getpixel((x, y))[:3])
bg = Counter(bg_s).most_common(1)[0][0]
print("term bg", bg)

# 路径 A：对照图同款橙 ●
pts = []
scan_r = min(x1 + 80, x2)
for y in range(y1, y2):
    for x in range(x1, scan_r):
        if is_orange(im.getpixel((x, y))):
            pts.append((x, y))
if pts:
    minx = min(p[0] for p in pts)
    maxx = max(p[0] for p in pts)
    miny = min(p[1] for p in pts)
    maxy = max(p[1] for p in pts)
    bw, bh = maxx - minx + 1, maxy - miny + 1
    ratio = bw / float(bh)
    print("glyphX", minx, "contentLeft", content_left,
          "bbox", bw, "x", bh, "ratio", round(ratio, 3), "n", len(pts))
    clipped = (minx <= content_left + 1) and (ratio < 0.88)
    print("verdict", "CLIPPED" if clipped else "OK",
          "operands glyphX=%d contentLeft=%d width=%d height=%d" % (minx, content_left, bw, bh))
    if clipped:
        print("⛔ 首列 ● 被左缘裁半（glyphX≈contentLeft 且宽/高=%.2f < 0.88）" % ratio)
        sys.exit(7)
    if bw < 8 or bh < 8:
        print("⛔ 色块过小，不像完整 ●")
        sys.exit(8)
    print("● 完整：glyphX=%d contentLeft=%d bbox=%dx%d" % (minx, content_left, bw, bh))
    sys.exit(0)

# 路径 B：当前帧没有橙 ●（滚动到了别的段）。改断言「首列墨迹相对 contentLeft」。
# 坏基线：墨迹 min_x = contentLeft = 0（贴 clip 边）。修后 8dp 左边距，墨迹 min_x ≥ 8。
ink_min = None
ink_n = 0
for y in range(y1, y2, 1):
    for x in range(x1, min(x1 + 64, x2)):
        p = im.getpixel((x, y))[:3]
        if dist(p, bg) > 30:
            ink_n += 1
            if ink_min is None or x < ink_min:
                ink_min = x
if ink_min is None or ink_n < 20:
    print("⛔ 终端左侧没有可测量墨迹（空屏/未连上）。尺子量不到，不能当绿。")
    sys.exit(6)
gutter = ink_min - content_left
print("glyphX", ink_min, "contentLeft", content_left, "gutter", gutter, "ink_n", ink_n)
# 坏基线 gutter=0；修后密度 3 下 8dp=24px。阈值取 6px：真机/Robolectric 密度差也能红/绿。
if gutter < 6:
    print("⛔ 首列墨迹贴着 clip 边（glyphX=%d contentLeft=%d gutter=%d）——布局贴边或被裁" %
          (ink_min, content_left, gutter))
    sys.exit(7)
print("OK 左缘有内边距：glyphX=%d contentLeft=%d gutter=%d" % (ink_min, content_left, gutter))
sys.exit(0)
PY

pass "会话页左缘 ● 完整可见"
echo "probe: ALL PASS (exit 0)"
exit 0
