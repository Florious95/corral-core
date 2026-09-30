#!/usr/bin/env python3
"""Emulator driver for t.verify. Semantic taps only. Never open fleet sessions."""
import os, re, struct, subprocess, sys, time

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
SERIAL = "emulator-5554"
PKG = "dev.agentmirror.app"
ROOT = "/Volumes/nvme/Projects/远程Agent安卓"
TMP = os.path.join(ROOT, ".team/nodes/hl1-verify/tmp")
NODE = os.path.join(ROOT, ".team/nodes/hl1-verify")
WTNODE = os.path.join(ROOT, ".worktrees/hl1.verify/.team/nodes/hl1-verify")
APK = os.path.join(ROOT, "app/app/build/outputs/apk/debug/app-debug.apk")
PORT = open(os.path.join(TMP, "port")).read().strip()
TOKEN = open(os.path.join(TMP, "token")).read().strip()
URL = "ws://10.0.2.2:%s/ws" % PORT

ISOLATED = ("hl1verifycwd-static", "hl1verifycwd-hist", "cwd-static", "cwd-hist")
FLEET_REFUSE = ("多agent协作", "远程Agent安卓")  # real workspace names seen in repro


def sh(*args, timeout=60):
    return subprocess.check_output(list(args), timeout=timeout)


def adb(*args, timeout=60):
    return sh(ADB, "-s", SERIAL, *args, timeout=timeout)


def adb_ok(*args, timeout=60):
    return subprocess.call([ADB, "-s", SERIAL, *args], timeout=timeout) == 0


def ime_off():
    adb_ok("shell", "input", "keyevent", "111")
    time.sleep(0.3)


def save_png(name):
    ime_off()
    png = adb("exec-out", "screencap", "-p", timeout=30)
    for d in (NODE, WTNODE):
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, name), "wb").write(png)
    open(os.path.join(TMP, name), "wb").write(png)
    print("SHOT", name, "bytes", len(png), flush=True)
    return png


def dump_xml(name):
    adb_ok("shell", "uiautomator", "dump", "/sdcard/hl1v.xml")
    xml = adb("exec-out", "cat", "/sdcard/hl1v.xml")
    path = os.path.join(TMP, name)
    open(path, "wb").write(xml)
    return xml.decode("utf-8", "replace")


def nodes(xml):
    out = []
    for m in re.finditer(r"<node[^>]*/?>", xml):
        n = m.group(0)

        def g(k):
            m2 = re.search(k + r'="([^"]*)"', n)
            return m2.group(1) if m2 else ""

        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        bounds = tuple(map(int, b.groups())) if b else None
        out.append(
            {
                "text": g("text"),
                "desc": g("content-desc"),
                "cls": g("class"),
                "bounds": bounds,
            }
        )
    return out


def refuse_fleet(xml, where):
    texts = " | ".join(n["text"] for n in nodes(xml) if n["text"])
    for bad in FLEET_REFUSE:
        # isolation workspaces must be present; fleet names on L1 is expected
        # because daemon scans default socket. We only refuse TAPPING them.
        pass
    print("UI_TEXTS", where, texts[:500], flush=True)
    return texts


def tap_xy(x, y):
    adb_ok("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def tap_exact(xml, want):
    for n in nodes(xml):
        if n["bounds"] and (n["text"] == want or n["desc"] == want):
            x1, y1, x2, y2 = n["bounds"]
            x, y = (x1 + x2) // 2, (y1 + y2) // 2
            print("TAP", want, x, y, flush=True)
            tap_xy(x, y)
            return n
    raise SystemExit("NOTFOUND exact=%r" % want)


def tap_contains(xml, want, forbid_fleet=True):
    hits = []
    for n in nodes(xml):
        blob = (n["text"] or "") + " " + (n["desc"] or "")
        if n["bounds"] and want in blob:
            if forbid_fleet and any(bad in blob for bad in FLEET_REFUSE):
                continue
            hits.append(n)
    if not hits:
        raise SystemExit("NOTFOUND contains=%r" % want)
    n = hits[0]
    x1, y1, x2, y2 = n["bounds"]
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    print("TAP_CONTAINS", want, n["text"], x, y, flush=True)
    tap_xy(x, y)
    return n


def edittexts(xml):
    out = []
    for n in nodes(xml):
        if n["bounds"] and "EditText" in (n["cls"] or ""):
            out.append(n)
    return out


def raw_screencap():
    data = adb("exec-out", "screencap", timeout=20)
    if len(data) < 16:
        return None
    w, h, fmt = struct.unpack_from("<III", data)
    return w, h, fmt, data, 12


def canvas_stats(cap):
    if cap is None:
        return {"n": 0, "dark": 0, "bright": 0, "color": 0}
    w, h, fmt, data, off = cap
    x0, x1 = 40, min(w - 40, 1040)
    y0, y1 = 320, min(h - 400, 2000)
    bpp = 4
    n = dark = bright = color = 0
    step = 8
    for y in range(y0, y1, step):
        row = off + y * w * bpp
        for x in range(x0, x1, step):
            i = row + x * bpp
            if i + 3 > len(data):
                continue
            r, g, b = data[i], data[i + 1], data[i + 2]
            n += 1
            luma = (r + g + b) / 3
            if luma < 28:
                dark += 1
            elif luma > 180:
                bright += 1
            else:
                color += 1
    return {"n": n, "dark": dark, "bright": bright, "color": color}


def is_term_first_frame(st):
    if st["n"] < 100:
        return False
    if st["bright"] / st["n"] > 0.35:
        return False
    if st["dark"] / st["n"] < 0.45:
        return False
    return st["color"] >= 30


def measure_after_tap(xml, label, tag, deadline_s=8.0):
    """Tap session row, burst-sample canvas until glyphs or timeout."""
    ime_off()
    t0 = time.perf_counter()
    tap_exact(xml, label)
    first_s = None
    series = []
    i = 0
    while time.perf_counter() - t0 < deadline_s:
        cap = raw_screencap()
        dt = time.perf_counter() - t0
        st = canvas_stats(cap)
        series.append((i, dt, st["n"], st["dark"], st["bright"], st["color"]))
        print(
            "burst tag=%s i=%d t=%.3f n=%d dark=%d bright=%d color=%d"
            % (tag, i, dt, st["n"], st["dark"], st["bright"], st["color"]),
            flush=True,
        )
        if first_s is None and is_term_first_frame(st):
            first_s = dt
            save_png("shot-%s-firstframe.png" % tag)
            print("FIRST_FRAME tag=%s t=%.3f" % (tag, first_s), flush=True)
            break
        i += 1
        if i > 80:
            break
        time.sleep(0.08)
    save_png("shot-%s-end.png" % tag)
    open(os.path.join(TMP, "series-%s.tsv" % tag), "w").write(
        "i\tsec\tn\tdark\tbright\tcolor\n"
        + "\n".join("%d\t%.4f\t%d\t%d\t%d\t%d" % r for r in series)
        + "\n"
    )
    open(os.path.join(TMP, "first-%s.txt" % tag), "w").write(
        "none" if first_s is None else "%.3f" % first_s
    )
    return first_s


def session_label(xml):
    for cand in ("claude", "bash", "claude.exe"):
        if any(n["text"] == cand for n in nodes(xml)):
            return cand
    # some builds show provider name
    for n in nodes(xml):
        t = n["text"] or ""
        if t in ("Claude", "空闲", "进行中"):
            continue
    raise SystemExit("no session row in: " + " | ".join(n["text"] for n in nodes(xml) if n["text"]))


def pair():
    print("=== pair ===", flush=True)
    adb_ok("shell", "am", "force-stop", PKG)
    adb_ok("shell", "pm", "clear", PKG)
    subprocess.check_call([ADB, "-s", SERIAL, "install", "-r", APK])
    adb_ok("shell", "am", "start", "-W", "-n", PKG + "/.MainActivity")
    time.sleep(4)
    xml = dump_xml("ui-01-pairing.xml")
    refuse_fleet(xml, "pairing")
    save_png("shot-01-pairing.png")
    if "手填连接" in xml:
        tap_exact(xml, "手填连接")
        time.sleep(1)
        xml = dump_xml("ui-02-form.xml")
    ets = edittexts(xml)
    if len(ets) < 2:
        save_png("shot-02-form-fail.png")
        raise SystemExit("need 2 EditTexts, got %d" % len(ets))
    x1, y1, x2, y2 = ets[0]["bounds"]
    tap_xy((x1 + x2) // 2, (y1 + y2) // 2)
    time.sleep(0.4)
    adb_ok("shell", "input", "text", URL)
    time.sleep(0.6)
    xml = dump_xml("ui-03-url.xml")
    ets = edittexts(xml)
    x1, y1, x2, y2 = ets[1]["bounds"]
    tap_xy((x1 + x2) // 2, (y1 + y2) // 2)
    time.sleep(0.4)
    adb_ok("shell", "input", "text", TOKEN)
    time.sleep(0.6)
    ime_off()
    xml = dump_xml("ui-04-filled.xml")
    refuse_fleet(xml, "filled")
    save_png("shot-02-filled.png")
    tap_exact(xml, "连接")
    # wait listing
    for i in range(25):
        time.sleep(1)
        xml = dump_xml("ui-05-l1-%d.xml" % i)
        texts = refuse_fleet(xml, "l1-%d" % i)
        if any(k in xml for k in ("hl1verifycwd-static", "cwd-static", "hl1verifycwd-hist", "cwd-hist")):
            save_png("shot-03-l1.png")
            print("L1_OK i=%d" % i, flush=True)
            return xml
        if "连接主机" in xml and i > 3:
            save_png("shot-03-still-pairing.png")
    save_png("shot-03-l1-timeout.png")
    raise SystemExit("pairing did not reach isolated L1")


def open_workspace(xml, key):
    # refuse tapping fleet names
    for n in nodes(xml):
        t = n["text"] or ""
        if key in t:
            if any(bad in t for bad in FLEET_REFUSE):
                raise SystemExit("refusing fleet-looking row %r" % t)
            tap_contains(xml, key)
            time.sleep(1.2)
            xml2 = dump_xml("ui-l2-%s.xml" % key)
            refuse_fleet(xml2, "l2-" + key)
            save_png("shot-l2-%s.png" % key)
            if any(bad in xml2 for bad in ("多agent协作",)) and key not in xml2:
                pass
            return xml2
    raise SystemExit("workspace %r not on L1" % key)


def back():
    adb_ok("shell", "input", "keyevent", "4")
    time.sleep(1.0)


def home_and_resume():
    adb_ok("shell", "input", "keyevent", "3")  # HOME
    time.sleep(1.2)
    save_png("shot-home.png")
    adb_ok("shell", "am", "start", "-n", PKG + "/.MainActivity")
    time.sleep(1.5)


def run_case(l1_xml, ws_key, tag):
    print("=== case", tag, ws_key, "===", flush=True)
    xml = l1_xml
    # may already be on L1
    if ws_key not in xml:
        # navigate back to L1
        for _ in range(3):
            back()
            xml = dump_xml("ui-nav-%s.xml" % tag)
            if ws_key in xml or "cwd-static" in xml:
                break
    xml = dump_xml("ui-l1-before-%s.xml" % tag)
    xml2 = open_workspace(xml, ws_key)
    label = session_label(xml2)
    print("LABEL", tag, label, flush=True)

    # cold open
    t = measure_after_tap(xml2, label, tag + "-cold", deadline_s=8.0)
    save_png("shot-%s-cold.png" % tag)
    # reenter
    back()
    time.sleep(0.8)
    xml3 = dump_xml("ui-l2-reenter-%s.xml" % tag)
    save_png("shot-%s-l2-reenter.png" % tag)
    label = session_label(xml3)
    t2 = measure_after_tap(xml3, label, tag + "-reenter", deadline_s=8.0)
    save_png("shot-%s-reenter.png" % tag)
    # bg -> fg
    home_and_resume()
    time.sleep(1.0)
    save_png("shot-%s-resume.png" % tag)
    cap = raw_screencap()
    st = canvas_stats(cap)
    open(os.path.join(TMP, "resume-%s.txt" % tag), "w").write(
        "n=%d dark=%d bright=%d color=%d firstframe=%s\n"
        % (st["n"], st["dark"], st["bright"], st["color"], is_term_first_frame(st))
    )
    print("RESUME", tag, st, "glyphs", is_term_first_frame(st), flush=True)
    # back to L1 for next case
    back()
    time.sleep(0.6)
    back()
    time.sleep(0.6)
    return t, t2


def main():
    os.makedirs(WTNODE, exist_ok=True)
    l1 = pair()
    r = {}
    r["static_cold"], r["static_reenter"] = run_case(l1, "cwd-static", "static")
    l1b = dump_xml("ui-l1-after-static.xml")
    save_png("shot-l1-after-static.png")
    r["hist_cold"], r["hist_reenter"] = run_case(l1b, "cwd-hist", "hist")
    open(os.path.join(TMP, "results.txt"), "w").write(repr(r) + "\n")
    print("RESULTS", r, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
