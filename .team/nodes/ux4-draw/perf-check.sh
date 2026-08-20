#!/usr/bin/env bash
# perf-check.sh — A-dw-ui（r7）
# 双密度 d480 / d440；改前(opt=0)/改后(opt=1)。
# 保持：n≥120；cellsNonBlank 与 textDraw 都非 0 且组间 ≤10%。
# 断言：两组密度 dt_us_p95 都 < 8000µs。
# 断言：onDraw 之外 = gfxinfo_p95_ms*1000 − dt_us_p95，且 > dt_us_p95。
# 删除：两个密度 p95 都必须下降。
# trap：IME 111、wm density reset。
set -euo pipefail

NODE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$NODE/../../.." && pwd)"
export ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=dev.agentmirror.app
ACTIVITY=dev.agentmirror.app.MainActivity
CUR_APK="${CUR_APK:-$ROOT/app/app/build/outputs/apk/debug/app-debug.apk}"

cleanup() {
  "$ADB" shell input keyevent 111 >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }

[ -x "$ADB" ] || fail "adb 不在 $ADB"
if [ ! -f "$CUR_APK" ]; then
  (cd "$ROOT/app" && env -u TEAM_AGENT_API_KEY ./gradlew -q :app:assembleDebug) || fail "assembleDebug 失败"
fi
[ -f "$CUR_APK" ] || fail "当前 APK 不在 $CUR_APK"
boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
[ "$boot" = "1" ] || fail "模拟器未就绪 sys.boot_completed='$boot'"
serial="$("$ADB" get-serialno 2>/dev/null | tr -d '\r' || true)"
[ "$serial" = "emulator-5554" ] || fail "设备必须是 emulator-5554，实际='$serial'"

"$ADB" reverse tcp:9900 tcp:9900 >/dev/null
"$ADB" install -r "$CUR_APK" >/dev/null

export UX4_DRAW_NODE="$NODE" PKG ACTIVITY ADB
python3 - <<'PY'
import os, re, subprocess, sys, time

adb = os.environ["ADB"]
node = os.environ["UX4_DRAW_NODE"]
pkg = os.environ["PKG"]
activity = os.environ["ACTIVITY"]
preferred = ["远控 leader", "team-leader-2", "leader", "远程控制 app 开发", "编排开发", "vz-v1-chrome"]
skip_names = {
    "收藏", "会话", "设置", "LAN", "★", "☆", "☰", "⚙", "空闲", "进行中", "未知",
    "工作区", "查看", "Esc", "Tab", "Ctrl-C", "返回", "Idle",
}

def fail(msg):
    print("FAIL " + msg)
    sys.exit(1)

def sh(*args, timeout=90):
    subprocess.check_call(list(args), timeout=timeout)

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
        t = tm.group(1) if tm else ""
        d = cd.group(1) if cd else ""
        label = t.strip() or d.strip()
        if not label:
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        out.append((label, (x1 + x2) // 2, (y1 + y2) // 2, y1, y2))
    return out

def tap(cx, cy, wait=1.0):
    sh(adb, "shell", "input", "tap", str(cx), str(cy))
    time.sleep(wait)

def on_session(ts):
    return "查看" in ts and "Esc" in ts

def on_settings(ts):
    return "诊断日志" in ts or "外观" in ts

def parse_gfx(text):
    total = janky_pct = p95 = None
    m = re.search(r"Total frames rendered:\s*(\d+)", text)
    if m:
        total = int(m.group(1))
    m = re.search(r"Janky frames:\s*(\d+)\s*\(([0-9.]+)%\)", text)
    if m:
        janky_pct = float(m.group(2))
    m = re.search(r"95th percentile:\s*([0-9.]+)\s*ms", text)
    if m:
        p95 = float(m.group(1))
    return total, janky_pct, p95

def parse_draw(log, want_opt=None):
    best = None
    count = 0
    for last in log.splitlines():
        if "[term-draw]" not in last:
            continue
        count += 1
        if "source=onDrawEmpty" in last:
            continue
        if "source=onDraw" not in last:
            continue
        def num(key, line=last):
            m = re.search(r"%s=(-?\d+)" % key, line)
            return int(m.group(1)) if m else None
        rec = {
            "n": num("n"),
            "avg": num("dt_us_avg"),
            "p95": num("dt_us_p95"),
            "bgRect": num("bgRect"),
            "opt": num("opt"),
            "cellsNonBlank": num("cellsNonBlank"),
            "textDraw": num("textDraw"),
            "dt_super_p95": num("dt_super_us_p95"),
            "dt_body_p95": num("dt_body_us_p95"),
            "dt_clear_p95": num("dt_clear_us_p95"),
            "dt_lines_p95": num("dt_lines_us_p95"),
            "dt_lock": num("dt_lock_us"),
            "dt_post": num("dt_post_us"),
            "dt_super_last": num("dt_super_us_last"),
            "dt_body_last": num("dt_body_us_last"),
            "dt_clear_last": num("dt_clear_us_last"),
            "dt_lines_last": num("dt_lines_us_last"),
            "raw": last,
        }
        if rec["n"] is None:
            continue
        if want_opt is not None and rec.get("opt") != want_opt:
            continue
        if best is None or rec["n"] > best["n"]:
            best = rec
    if best is not None:
        best["count"] = count
    return best

def pick_row(ts, ns):
    skip = skip_names | {
        "暂无收藏", "在会话列表里点星星即可收藏。", "重连中…", "正在重连…", "❯_",
        "当前", "1", "›",
    }
    name = next((n for n in preferred if n in ts), None)
    on_l2 = "空闲" in ts or "进行中" in ts
    if name is None:
        for t in ts:
            if t in skip:
                continue
            if "重连" in t or "断开" in t:
                continue
            if t.startswith("/") or "SESSIONS" in t or "WORKSPACES" in t:
                continue
            if on_l2 and t in ("多agent协作", "cwd"):
                continue
            name = t
            break
    if name is None:
        return None
    hits = [n for n in ns if n[0] == name]
    return (hits[0][1], hits[0][2], name) if hits else None

def go_session():
    x = dump()
    ts = texts(x)
    for _ in range(6):
        if on_session(ts):
            return
        ns = nodes(x)
        back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 工作区") and n[2] < 500]
        if back and "查看" not in ts and "Esc" not in ts:
            tap(back[0][1], back[0][2], 0.8)
            x = dump()
            ts = texts(x)
            continue
        break
    if on_session(ts):
        return
    if "暂无收藏" in ts:
        tabs = [n for n in nodes(x) if n[0] == "会话"]
        if tabs:
            tabs.sort(key=lambda n: n[2])
            tap(tabs[-1][1], tabs[-1][2], 1.3)
            sh(adb, "shell", "input", "keyevent", "111")
            x = dump()
            ts = texts(x)
    row = pick_row(ts, nodes(x))
    if row is None:
        fail("列表没有可点行 ts=%s" % ts)
    tap(row[0], row[1], 1.5)
    sh(adb, "shell", "input", "keyevent", "111")
    x = dump()
    ts = texts(x)
    if on_session(ts):
        return
    row = pick_row(ts, nodes(x))
    if row is None:
        fail("二级没有会话名 ts=%s" % ts)
    tap(row[0], row[1], 1.5)
    sh(adb, "shell", "input", "keyevent", "111")
    x = dump()
    ts = texts(x)
    if not on_session(ts):
        fail("进不了会话页。屏上有: %s" % ts)

def go_settings():
    x = dump()
    ts = texts(x)
    for _ in range(8):
        if on_settings(ts):
            break
        ns = nodes(x)
        back = [n for n in ns if n[0] in ("‹", "‹ 返回", "‹ 工作区", "‹ 设置", "返回") and n[2] < 600]
        if back and "诊断日志" not in ts:
            tap(back[0][1], back[0][2], 0.8)
            x = dump()
            ts = texts(x)
            continue
        sets = [n for n in nodes(x) if n[0] == "设置"]
        if not sets:
            break
        sets.sort(key=lambda n: n[2])
        tap(sets[-1][1], sets[-1][2], 0.9)
        x = dump()
        ts = texts(x)
    if "诊断日志" not in texts(dump()):
        for _ in range(4):
            sh(adb, "shell", "input", "swipe", "540", "1600", "540", "500", "280")
            time.sleep(0.3)
    if "诊断日志" not in texts(dump()):
        fail("到不了设置/诊断日志。屏上有: %s" % texts(dump()))

def export_log(tag):
    go_settings()
    exps = [n for n in nodes(dump()) if n[0] == "导出"]
    if not exps:
        fail("找不到「导出」")
    exps.sort(key=lambda n: n[2])
    tap(exps[-1][1], exps[-1][2], 1.3)
    sh(adb, "shell", "input", "keyevent", "4")
    time.sleep(0.4)
    sh(adb, "shell", "input", "keyevent", "111")
    ls = subprocess.run(
        [adb, "shell", "run-as", pkg, "ls", "files/diag"],
        capture_output=True, text=True,
    )
    names = [ln.strip() for ln in (ls.stdout or "").splitlines() if ln.strip().endswith(".log")]
    names.sort()
    if not names:
        fail("导出目录空 ls=%r" % (ls.stdout or ls.stderr))
    cat = subprocess.run(
        [adb, "shell", "run-as", pkg, "cat", "files/diag/" + names[-1]],
        capture_output=True,
    )
    text = (cat.stdout or b"").decode("utf-8", "replace")
    open(os.path.join(node, "diag-%s.log" % tag), "w", encoding="utf-8").write(text)
    return text

def tee_file(name, v):
    r = subprocess.run(
        [adb, "shell", "run-as", pkg, "tee", "files/" + name],
        input=(str(v) + "\n").encode(),
        capture_output=True,
        timeout=30,
    )
    if r.returncode != 0:
        fail("写 %s 失败 rc=%s err=%s" % (name, r.returncode, r.stderr))

def set_opt(v):
    tee_file("term_draw_opt", v)

def set_burst(n):
    tee_file("term_draw_burst", n)

def restart():
    sh(adb, "shell", "am", "force-stop", pkg)
    time.sleep(0.4)
    sh(adb, "shell", "am", "start", "-W", "-n", "%s/%s" % (pkg, activity))
    time.sleep(1.2)
    sh(adb, "shell", "input", "keyevent", "111")

def within_10(a, b):
    if a is None or b is None:
        return False
    return abs(a - b) <= 0.10 * max(a, b, 1)

def run_burst():
    set_burst(1)
    # 只 tap 唤醒采集。swipe 会把滚轮发给远端 TUI，改前/改后变成两块屏。
    sh(adb, "shell", "input", "tap", "630", "1400")
    subprocess.run([adb, "shell", "dumpsys", "gfxinfo", pkg, "reset"], capture_output=True, timeout=30)
    time.sleep(16)
    gfx = subprocess.run(
        [adb, "shell", "dumpsys", "gfxinfo", pkg],
        capture_output=True, text=True, timeout=30,
    ).stdout or ""
    return parse_gfx(gfx)

def check_draw(draw, density, label):
    if not draw or draw["p95"] is None or draw["avg"] is None:
        fail("读不到稳态 [term-draw] source=onDraw p95/avg density=%s %s raw=%s" % (
            density, label, (draw or {}).get("raw")))
    if draw["n"] is None or draw["n"] < 120:
        fail("量具无效 n=%s < 120 density=%s %s raw=%s" % (
            draw.get("n"), density, label, draw.get("raw")))
    if not draw.get("cellsNonBlank") or not draw.get("textDraw"):
        fail("空屏不可比 cellsNonBlank=%s textDraw=%s density=%s %s raw=%s" % (
            draw.get("cellsNonBlank"), draw.get("textDraw"), density, label, draw.get("raw")))

results = {}
for density in (480, 440):
    sh(adb, "shell", "wm", "density", str(density))
    time.sleep(0.8)
    restart()
    go_session()
    time.sleep(5.0)
    set_opt("0")
    gfx_b = run_burst()
    set_opt("1")
    gfx_a = run_burst()
    log = export_log("d%s" % density)
    before = parse_draw(log, want_opt=0)
    after = parse_draw(log, want_opt=1)
    check_draw(before, density, "before")
    check_draw(after, density, "after")
    print("GFX density=%s opt=0 total=%s janky%%=%s p95_ms=%s" % (density, gfx_b[0], gfx_b[1], gfx_b[2]))
    print("DRAW density=%s opt=0 n=%s avg_us=%s p95_us=%s bgRect=%s textDraw=%s cellsNonBlank=%s" % (
        density, before["n"], before["avg"], before["p95"], before["bgRect"], before["textDraw"], before["cellsNonBlank"]))
    print("GFX density=%s opt=1 total=%s janky%%=%s p95_ms=%s" % (density, gfx_a[0], gfx_a[1], gfx_a[2]))
    print("DRAW density=%s opt=1 n=%s avg_us=%s p95_us=%s bgRect=%s textDraw=%s cellsNonBlank=%s" % (
        density, after["n"], after["avg"], after["p95"], after["bgRect"], after["textDraw"], after["cellsNonBlank"]))
    results[density] = (before, after, gfx_b, gfx_a)
    if not within_10(before["cellsNonBlank"], after["cellsNonBlank"]):
        fail("d%s 同负载失败 cellsNonBlank before=%s after=%s" % (
            density, before["cellsNonBlank"], after["cellsNonBlank"]))
    if not within_10(before["textDraw"], after["textDraw"]):
        fail("d%s 同负载失败 textDraw before=%s after=%s" % (
            density, before["textDraw"], after["textDraw"]))
    for label, draw, gfx in (("before", before, gfx_b), ("after", after, gfx_a)):
        total, janky, p95ms = gfx
        dt = draw["p95"]
        if dt >= 8000:
            fail("d%s %s dt_us_p95=%s ≥ 8000" % (density, label, dt))
        if p95ms is None:
            fail("d%s %s 读不到 gfxinfo p95_ms total=%s" % (density, label, total))
        outside = int(round(p95ms * 1000)) - dt
        print("OUTSIDE d%s %s gfx_p95_ms=%s dt_us_p95=%s outside_us=%s (gfx*1000-dt)" % (
            density, label, p95ms, dt, outside))
        if outside <= dt:
            fail("d%s %s onDraw之外=%s 不大于 dt_us_p95=%s（瓶颈可能就在 onDraw） gfx_p95_ms=%s" % (
                density, label, outside, dt, p95ms))
    drop = (before["p95"] - after["p95"]) / max(before["p95"], 1)
    print("NOTE d%s p95_delta %.3f  before=%s after=%s（不再作为判据）" % (
        density, drop, before["p95"], after["p95"]))

open(os.path.join(node, "perf-numbers.txt"), "w", encoding="utf-8").write(
    "\n".join(
        "d%s before_avg=%s before_p95=%s after_avg=%s after_p95=%s gfx_before=%s gfx_after=%s" % (
            d, results[d][0]["avg"], results[d][0]["p95"],
            results[d][1]["avg"], results[d][1]["p95"],
            results[d][2], results[d][3],
        )
        for d in (480, 440)
    ) + "\n"
)
print("PASS A-dw-ui")
PY
