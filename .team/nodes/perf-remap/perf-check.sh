#!/usr/bin/env bash
# perf-check.sh — A-pf-jank + 用户追加 S1/S2/S3
# 基线 APK = git archive 6e7b3ed43 assembleDebug（085 之前）
# 当前 APK = 仓根 debug
# 同一模拟器、同一次开机、同一生产 daemon:9900、同一收藏会话
# S2：定长定次 swipe；gfxinfo reset 后再采；total<600 ⇒ FAIL 量具无效
# 不杀生产 daemon、不碰用户 tmux。trap：IME 111、density reset。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$NODE/../../.." && pwd)"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.agentmirror.app
ACTIVITY=dev.agentmirror.app.MainActivity
CUR_APK="${CUR_APK:-$ROOT/app/app/build/outputs/apk/debug/app-debug.apk}"
BASE_APK="${BASE_APK:-/tmp/am-baseline-6e7b3ed4/app/app/build/outputs/apk/debug/app-debug.apk}"
MIN_FRAMES="${MIN_FRAMES:-600}"
P95_CAP_MS="${P95_CAP_MS:-42}"
JANK_DROP="${JANK_DROP:-0.15}"
SWIPE_PAIRS="${SWIPE_PAIRS:-160}"
TOKEN_FILE="${TOKEN_FILE:-$HOME/Library/Application Support/agentmirror/token}"

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -x "$ADB" ] || fail "adb 不在 $ADB"
[ -f "$CUR_APK" ] || fail "当前 APK 不在 $CUR_APK"
[ -f "$BASE_APK" ] || fail "基线 APK 不在 $BASE_APK"
[ -f "$TOKEN_FILE" ] || fail "缺配对 token 文件"
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪"
serial="$("$ADB" get-serialno 2>/dev/null | tr -d '\r' || true)"
[ "$serial" = "emulator-5554" ] || fail "设备必须是 emulator-5554，实际='$serial'"

"$ADB" reverse tcp:9900 tcp:9900 >/dev/null
TOKEN="$(tr -d '\n\r' < "$TOKEN_FILE")"
export TOKEN

export VZ_PF_NODE="$NODE" PKG ACTIVITY CUR_APK BASE_APK
export MIN_FRAMES P95_CAP_MS JANK_DROP SWIPE_PAIRS ADB
python3 - <<'PY'
import os, re, struct, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["VZ_PF_NODE"]
pkg = os.environ["PKG"]
activity = os.environ["ACTIVITY"]
token = os.environ["TOKEN"]
min_frames = int(os.environ["MIN_FRAMES"])
p95_cap = float(os.environ["P95_CAP_MS"])
drop_need = float(os.environ["JANK_DROP"])
swipe_pairs = int(os.environ["SWIPE_PAIRS"])
cur_apk = os.environ["CUR_APK"]
base_apk = os.environ["BASE_APK"]
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发", "vz-v1-chrome"]
chrome = {
    "‹", "查看", "LAN", "tailnet", "Esc", "Tab", "↑", "↓", "←", "→", "Ctrl-C", "+", "＋",
    "❯", "输入指令…", "发送", "收藏", "会话", "设置", "工作区", "‹ 返回", "‹ 工作区",
    "当前", "进行中", "空闲", "未知", "切换会话", "★", "☆", "拍照", "从相册选择",
}

def fail(msg):
    print("FAIL " + msg)
    sys.exit(1)

def sh(*args, timeout=90):
    subprocess.check_call(list(args), timeout=timeout)

def out(*args, timeout=60):
    r = subprocess.run(list(args), capture_output=True, timeout=timeout)
    return (r.stdout or b"").decode("utf-8", "replace")

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
    outn = []
    for m in re.finditer(r"<node\b[^>]*>", x):
        tag = m.group(0)
        tm = re.search(r'text="([^"]*)"', tag)
        cd = re.search(r'content-desc="([^"]*)"', tag)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not bm:
            continue
        t = tm.group(1) if tm else ""
        d = cd.group(1) if cd else ""
        if not t.strip() and not d.strip():
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        outn.append((t, d, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2, x1, x2))
    return outn

def tap(cx, cy, wait=1.0):
    sh(adb, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(wait)

def key(code):
    sh(adb, "shell", "input", "keyevent", str(code))
    time.sleep(0.15)

def type_text(s):
    sh(adb, "shell", "input", "text", s.replace(" ", "%s"))
    time.sleep(0.3)

def on_session(ts):
    return "查看" in ts and "Esc" in ts

def on_pairing(ts):
    return "连接主机" in ts or "手填连接" in ts

def on_favorites(ts):
    return "暂无收藏" in ts or (
        any("SESSIONS ·" in t and "WORKSPACES" not in t for t in ts) and "收藏" in ts
    )

def on_l1(ts):
    return any("WORKSPACES" in t for t in ts) or "工作区" in ts

def back_nodes(ns):
    return [n for n in ns if (n[0] in ("‹", "‹ 返回", "‹ 工作区") or n[1] == "返回") and n[2] < 500]

def install(apk):
    sh(adb, "install", "-r", apk, timeout=120)
    print("INSTALLED", os.path.basename(apk))

def start_app():
    sh(adb, "shell", "am", "force-stop", pkg)
    time.sleep(0.35)
    sh(adb, "shell", "am", "start", "-W", "-n", "%s/%s" % (pkg, activity))
    time.sleep(1.1)
    key(111)

def go_repair():
    x = dump()
    for _ in range(10):
        ts = texts(x)
        ns = nodes(x)
        if on_pairing(ts):
            return
        if "重新配对" in ts:
            hits = [n for n in ns if n[0] == "重新配对"]
            if hits:
                tap(hits[0][2], hits[0][3], 1.2)
                return
        if on_session(ts):
            b = back_nodes(ns)
            if b:
                tap(b[0][2], b[0][3], 0.8)
                x = dump()
                continue
        sets = [n for n in ns if n[0] == "设置"]
        if sets:
            sets.sort(key=lambda n: n[4])
            tap(sets[-1][2], sets[-1][3], 1.0)
            x = dump()
            continue
        for _s in range(4):
            sh(adb, "shell", "input", "swipe", "540", "1600", "540", "500", "250")
            time.sleep(0.2)
        x = dump()
        if "重新配对" in texts(x):
            hits = [n for n in nodes(x) if n[0] == "重新配对"]
            tap(hits[0][2], hits[0][3], 1.2)
            return
        b = back_nodes(ns)
        if b:
            tap(b[0][2], b[0][3], 0.7)
            x = dump()
            continue
        break

def pair_prod():
    x = dump()
    ts = texts(x)
    if "重连中" in "".join(ts) or "正在重连" in "".join(ts) or "连接已断开" in "".join(ts):
        print("PAIR repair from reconnect banner")
        go_repair()
        x = dump()
        ts = texts(x)
    if not on_pairing(ts):
        print("PAIR skip")
        return
    ns = nodes(x)
    hand = [n for n in ns if "手填" in n[0]]
    if hand:
        tap(hand[0][2], hand[0][3], 1.0)
        x = dump()
    edits = []
    for m in re.finditer(r"<node\b[^>]*>", x):
        tag = m.group(0)
        cls = re.search(r'class="([^"]*)"', tag)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if cls and bm and "EditText" in cls.group(1):
            x1, y1, x2, y2 = map(int, bm.groups())
            edits.append(((x1 + x2) // 2, (y1 + y2) // 2))
    if len(edits) < 2:
        fail("配对页缺输入框 ts=%s" % texts(x))
    tap(edits[0][0], edits[0][1], 0.35)
    type_text("ws://127.0.0.1:9900/ws")
    key(111)
    tap(edits[1][0], edits[1][1], 0.35)
    type_text(token)
    key(111)
    x = dump()
    conn = [n for n in nodes(x) if n[0] == "连接"]
    if not conn:
        fail("找不到连接")
    tap(conn[0][2], conn[0][3], 2.0)
    key(111)
    for i in range(20):
        ts = texts(dump())
        if not on_pairing(ts):
            print("PAIR prod ok i=%d" % i)
            return
        time.sleep(0.8)
    fail("配对后仍在配对页")

def leave_session():
    x = dump()
    if not on_session(texts(x)):
        return
    b = back_nodes(nodes(x))
    if b:
        tap(b[0][2], b[0][3], 0.9)
        key(111)

def session_row():
    x = dump()
    ts = texts(x)
    for _ in range(8):
        if on_session(ts):
            b = back_nodes(nodes(x))
            if b:
                tap(b[0][2], b[0][3], 0.8)
                x = dump()
                ts = texts(x)
            else:
                break
        if on_pairing(ts):
            fail("掉回配对页")
        if on_favorites(ts) or any("SESSIONS" in t or "WORKSPACES" in t for t in ts):
            break
        favs = [n for n in nodes(x) if n[0] == "收藏"]
        if favs:
            favs.sort(key=lambda n: n[4])
            tap(favs[-1][2], favs[-1][3], 1.4)
            key(111)
            x = dump()
            ts = texts(x)
            continue
        b = back_nodes(nodes(x))
        if b:
            tap(b[0][2], b[0][3], 0.8)
            x = dump()
            ts = texts(x)
            continue
        break
    # 收藏被 pm clear 清空，走「会话」工作区列表
    tabs = [n for n in nodes(x) if n[0] == "会话"]
    if tabs:
        tabs.sort(key=lambda n: n[4])
        tap(tabs[-1][2], tabs[-1][3], 1.3)
        key(111)
        x = dump()
        ts = texts(x)
    # 若还在收藏空页，再点一次会话 tab
    if "暂无收藏" in ts:
        tabs = [n for n in nodes(x) if n[0] == "会话"]
        if tabs:
            tap(tabs[-1][2], tabs[-1][3], 1.3)
            x = dump()
            ts = texts(x)
    def pick_row(ts, ns):
        name = next((n for n in preferred if n in ts), None)
        skip = {
            "暂无收藏", "在会话列表里点星星即可收藏。", "重连中…", "正在重连…", "❯_",
            "空闲", "进行中", "未知", "当前",
        }
        on_l2 = "空闲" in ts or "进行中" in ts
        if name is None:
            for t in ts:
                if t in chrome or t in skip:
                    continue
                if "重连" in t or "断开" in t:
                    continue
                if t.startswith("/") or "SESSIONS" in t or "WORKSPACES" in t:
                    continue
                if t in ("1", "›", "☰", "⚙"):
                    continue
                if on_l2 and t in ("多agent协作", "cwd"):
                    continue
                name = t
                break
        if name is None:
            return None
        hits = [n for n in ns if n[0] == name]
        return (hits[0][2], hits[0][3], name) if hits else None

    row = pick_row(ts, nodes(x))
    if row is None:
        fail("列表没有可点行 ts=%s" % ts)
    if not on_session(ts) and "查看" not in ts:
        # 一级工作区 → 二级会话
        tap(row[0], row[1], 1.4)
        key(111)
        x = dump()
        ts = texts(x)
        if on_session(ts):
            b = back_nodes(nodes(x))
            if b:
                tap(b[0][2], b[0][3], 0.9)
            x = dump()
            ts = texts(x)
        row = pick_row(ts, nodes(x))
    if row is None:
        fail("二级没有会话名 ts=%s" % ts)
    print("SESSION_CANDIDATE", row[2], "ts0", ts[:8])
    return row

def parse_gfx(text):
    total = janky = janky_pct = p95 = None
    m = re.search(r"Total frames rendered:\s*(\d+)", text)
    if m:
        total = int(m.group(1))
    m = re.search(r"Janky frames:\s*(\d+)\s*\(([0-9.]+)%\)", text)
    if m:
        janky = int(m.group(1))
        janky_pct = float(m.group(2))
    m = re.search(r"95th percentile:\s*([0-9.]+)\s*ms", text)
    if m:
        p95 = float(m.group(1))
    return total, janky, janky_pct, p95

def set_density(d):
    sh(adb, "shell", "wm", "density", str(d))
    time.sleep(0.9)
    print("DENSITY_SET", d)

def screencap_raw():
    r = subprocess.run([adb, "exec-out", "screencap"], capture_output=True, timeout=20)
    b = r.stdout or b""
    if len(b) < 12:
        return None
    w, h, fmt = struct.unpack_from("<III", b, 0)
    pixels = b[12:]
    bpp = 4
    if w * h * bpp > len(pixels):
        return None
    return w, h, pixels

def empty_term(frame):
    """终端内容区是否均匀（无字形）——白屏或纯底都算空内容，不只查 RGB=白。"""
    if frame is None:
        return True
    w, h, pix = frame
    x0, x1 = int(w * 0.08), int(w * 0.92)
    y0, y1 = int(h * 0.18), int(h * 0.62)
    step = 12
    colors = set()
    for y in range(y0, y1, step):
        row = y * w * 4
        for x in range(x0, x1, step):
            i = row + x * 4
            if i + 2 >= len(pix):
                continue
            colors.add((pix[i] >> 4, pix[i + 1] >> 4, pix[i + 2] >> 4))
            if len(colors) > 12:
                return False
    return len(colors) <= 6

def export_diag(tag):
    x = dump()
    for _ in range(10):
        ts = texts(x)
        ns = nodes(x)
        if "导出" in ts or "诊断日志" in ts:
            break
        if on_session(ts):
            b = back_nodes(ns)
            if b:
                tap(b[0][2], b[0][3], 0.8)
                x = dump()
                continue
        sets = [n for n in ns if n[0] == "设置"]
        if sets:
            sets.sort(key=lambda n: n[4])
            tap(sets[-1][2], sets[-1][3], 1.0)
            x = dump()
            continue
        b = back_nodes(ns)
        if b:
            tap(b[0][2], b[0][3], 0.7)
            x = dump()
            continue
        break
    for _ in range(5):
        if "导出" in texts(x):
            break
        sh(adb, "shell", "input", "swipe", "540", "1600", "540", "500", "250")
        time.sleep(0.25)
        x = dump()
    exps = [n for n in nodes(x) if n[0] == "导出"]
    if not exps:
        print("DIAG skip")
        return ""
    tap(exps[-1][2], exps[-1][3], 1.1)
    key(4)
    key(111)
    ls = out(adb, "shell", "run-as", pkg, "ls", "files/diag")
    names = [ln.strip() for ln in ls.splitlines()
             if ln.strip().startswith("diag-") and ln.strip().endswith(".log")]
    names.sort()
    if not names:
        return ""
    raw = subprocess.run(
        [adb, "shell", "run-as", pkg, "cat", "files/diag/" + names[-1]],
        capture_output=True,
    )
    text = (raw.stdout or b"").decode("utf-8", "replace")
    open(os.path.join(node, "diag-%s.log" % tag), "w", encoding="utf-8").write(text)
    print("EXPORT diag-%s.log bytes=%d" % (tag, len(text)))
    return text

def count_diag(text, tags):
    rows = []
    for line in text.splitlines():
        m = re.match(r"^(\S+) \[([^\]]+)\] (.*)$", line.strip())
        if m:
            rows.append((m.group(2).strip(), m.group(3)))
    outc = {}
    for tag in tags:
        hits = [r for r in rows if r[0] == tag]
        outc[tag] = hits
        print("DIAGTAG %s n=%d" % (tag, len(hits)))
    return outc

def measure_apk(label, apk):
    install(apk)
    start_app()
    pair_prod()
    result = {"label": label}
    for dens in (480, 440):
        set_density(dens)
        start_app()
        pair_prod()
        sx, sy, name = session_row()
        print("SESSION_ROW", label, dens, name, sx, sy)

        # S3 白屏：点进后连拍，统计内容区均匀帧
        t0 = time.time()
        tap(sx, sy, 0.05)
        empty_n = 0
        total_n = 0
        first_content_ms = None
        for i in range(12):
            fr = screencap_raw()
            total_n += 1
            empty = empty_term(fr)
            if empty:
                empty_n += 1
            elif first_content_ms is None:
                first_content_ms = int((time.time() - t0) * 1000)
            time.sleep(0.03)
        key(111)
        enter_ms = int((time.time() - t0) * 1000)
        x = dump()
        if not on_session(texts(x)):
            fail("%s d%d 点进后不在会话 ts=%s" % (label, dens, texts(x)))
        shot = os.path.join(node, "shot-%s-d%d-session.png" % (label, dens))
        subprocess.check_call([adb, "exec-out", "screencap", "-p"], stdout=open(shot, "wb"))
        print("S3 %s d%d empty=%d/%d first_content_ms=%s enter_ms=%d" % (
            label, dens, empty_n, total_n, first_content_ms, enter_ms,
        ))

        # S1 重排：导出诊断，计 viewport/reflow/grid
        diag = export_diag("%s-d%d" % (label, dens))
        tags = count_diag(diag, ("viewport", "reflow", "grid", "session-live", "session-lamp", "view-menu"))
        resized = sum(1 for _, m in tags.get("viewport", []) if "resized=true" in m)
        print("S1 %s d%d viewport=%d resized_true=%d reflow=%d grid=%d" % (
            label, dens, len(tags.get("viewport", [])), resized,
            len(tags.get("reflow", [])), len(tags.get("grid", [])),
        ))

        # 回到会话做 S2
        start_app()
        pair_prod()
        sx, sy, name = session_row()
        tap(sx, sy, 1.0)
        key(111)
        if not on_session(texts(dump())):
            fail("%s d%d S2 进不了会话" % (label, dens))
        subprocess.run([adb, "shell", "dumpsys", "gfxinfo", pkg, "reset"], capture_output=True, timeout=30)
        for _ in range(swipe_pairs):
            sh(adb, "shell", "input", "swipe", "630", "1500", "630", "900", "80")
            sh(adb, "shell", "input", "swipe", "630", "900", "630", "1500", "80")
        time.sleep(0.3)
        gfx = out(adb, "shell", "dumpsys", "gfxinfo", pkg)
        open(os.path.join(node, "gfxinfo-%s-d%d.txt" % (label, dens)), "w", encoding="utf-8").write(gfx)
        total, janky, pct, p95 = parse_gfx(gfx)
        print("S2 %s d%d total=%s janky=%s pct=%s p95=%s" % (label, dens, total, janky, pct, p95))
        if total is None or total < min_frames:
            fail("量具无效 S2 %s d%d total=%s < %s" % (label, dens, total, min_frames))
        if pct is None or p95 is None:
            fail("量具无效 S2 %s d%d 无 janky%%/p95" % (label, dens))
        result["d%d" % dens] = {
            "empty_frames": empty_n,
            "burst_n": total_n,
            "first_content_ms": first_content_ms,
            "enter_ms": enter_ms,
            "viewport": len(tags.get("viewport", [])),
            "resized_true": resized,
            "reflow": len(tags.get("reflow", [])),
            "grid": len(tags.get("grid", [])),
            "session_live": len(tags.get("session-live", [])),
            "session_lamp": len(tags.get("session-lamp", [])),
            "view_menu": len(tags.get("view-menu", [])),
            "s2_total": total,
            "s2_janky": janky,
            "s2_pct": pct,
            "s2_p95": p95,
        }
        leave_session()
    return result

def fmt(r, dens):
    d = r["d%d" % dens]
    return (
        "%s d%d S1 viewport=%d resized_true=%d reflow=%d grid=%d enter_ms=%d "
        "S2 total=%d janky_pct=%.2f p95=%.2f "
        "S3 empty=%d/%d first_content_ms=%s live=%d lamp=%d viewmenu=%d"
        % (
            r["label"], dens, d["viewport"], d["resized_true"], d["reflow"], d["grid"], d["enter_ms"],
            d["s2_total"], d["s2_pct"], d["s2_p95"],
            d["empty_frames"], d["burst_n"], d["first_content_ms"],
            d["session_live"], d["session_lamp"], d["view_menu"],
        )
    )

base = measure_apk("baseline", base_apk)
cur = measure_apk("current", cur_apk)
lines = [fmt(base, 480), fmt(base, 440), fmt(cur, 480), fmt(cur, 440)]
open(os.path.join(node, "jank-compare.txt"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
for ln in lines:
    print("ROW", ln)

verdicts = []
for dens in (480, 440):
    b = base["d%d" % dens]
    c = cur["d%d" % dens]
    print("COMPARE d%d S2 base_pct=%.2f cur_pct=%.2f base_p95=%.2f cur_p95=%.2f n=%d/%d" % (
        dens, b["s2_pct"], c["s2_pct"], b["s2_p95"], c["s2_p95"], b["s2_total"], c["s2_total"],
    ))
    print("COMPARE d%d S1 resized %d→%d enter_ms %d→%d" % (
        dens, b["resized_true"], c["resized_true"], b["enter_ms"], c["enter_ms"],
    ))
    print("COMPARE d%d S3 empty %d/%d → %d/%d first_ms %s→%s" % (
        dens, b["empty_frames"], b["burst_n"], c["empty_frames"], c["burst_n"],
        b["first_content_ms"], c["first_content_ms"],
    ))
    if c["s2_pct"] > b["s2_pct"] * 1.08 + 1.0:
        fail("A-pf-jank d%d S2 相对基线劣化" % dens)
    dropped = c["s2_pct"] <= b["s2_pct"] * (1.0 - drop_need)
    same = abs(c["s2_pct"] - b["s2_pct"]) <= max(2.0, b["s2_pct"] * 0.10)
    if dropped and c["s2_p95"] > p95_cap:
        fail("A-pf-jank d%d p95=%.2f > cap=%.2f" % (dens, c["s2_p95"], p95_cap))
    if dropped:
        verdicts.append("d%d=s2_changed" % dens)
    elif same:
        verdicts.append("d%d=s2_same" % dens)
    else:
        verdicts.append("d%d=s2_not_worse" % dens)

open(os.path.join(node, "jank-verdict.txt"), "w", encoding="utf-8").write(" ".join(verdicts) + "\n")
print("PASS instrument S1/S2/S3 " + " ".join(verdicts))
PY
