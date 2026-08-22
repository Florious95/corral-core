#!/usr/bin/env python3
"""Tap isolated session row and burst-capture until terminal glyphs appear."""
import os, re, struct, subprocess, sys, time, signal

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
SERIAL = "emulator-5554"
TMP = os.environ["R2_TMP"]
NODE = os.environ["R2_NODE"]
TAG = sys.argv[1]
OUTDIR = os.path.join(TMP, "burst-" + TAG)
os.makedirs(OUTDIR, exist_ok=True)


def sh(*args, timeout=30):
    return subprocess.check_output(list(args), timeout=timeout)


def dump_xml(name):
    subprocess.check_call([ADB, "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/r2.xml"])
    xml = sh(ADB, "-s", SERIAL, "exec-out", "cat", "/sdcard/r2.xml")
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
        out.append({"text": g("text"), "desc": g("content-desc"), "bounds": bounds})
    return out


def tap_label(xml, want):
    for n in nodes(xml):
        if n["bounds"] and (n["text"] == want or n["desc"] == want):
            x1, y1, x2, y2 = n["bounds"]
            x, y = (x1 + x2) // 2, (y1 + y2) // 2
            subprocess.check_call([ADB, "-s", SERIAL, "shell", "input", "tap", str(x), str(y)])
            return n
    raise SystemExit("NOTFOUND " + want)


def raw_screencap():
    data = sh(ADB, "-s", SERIAL, "exec-out", "screencap")
    if len(data) < 16:
        return None
    w, h, fmt = struct.unpack_from("<III", data)
    off = 12
    return w, h, fmt, data, off


def canvas_stats(cap):
    """Subsample the terminal band. Light UI vs empty-dark vs glyphs-on-dark."""
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
    # session canvas is dark; first frame = dark majority + some mid-luma glyphs
    # reject light list pages (bright majority)
    if st["n"] < 100:
        return False
    if st["bright"] / st["n"] > 0.35:
        return False
    if st["dark"] / st["n"] < 0.45:
        return False
    return st["color"] >= 30


def save_png(name):
    p = os.path.join(NODE, name)
    png = sh(ADB, "-s", SERIAL, "exec-out", "screencap", "-p")
    open(p, "wb").write(png)
    return len(png)


def main():
    subprocess.check_call([ADB, "-s", SERIAL, "shell", "input", "keyevent", "111"])
    xml = dump_xml("ui-%s-before.xml" % TAG)
    if "多agent协作" in xml:
        raise SystemExit("on fleet L1, refuse")
    if "hl1repro2cwd" not in xml:
        raise SystemExit("not on isolated L2")
    # tap the session row: bash (or claude.exe) — only row in isolated workspace
    label = None
    for cand in ("bash", "claude.exe", "claude"):
        if any(n["text"] == cand for n in nodes(xml)):
            label = cand
            break
    if not label:
        raise SystemExit("no session row")
    print("label", label, flush=True)

    # screenrecord in bg
    rec_remote = "/sdcard/r2-%s.mp4" % TAG
    rec = subprocess.Popen(
        [ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", "18", rec_remote],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    time.sleep(0.4)

    t0 = time.perf_counter()
    tap_label(xml, label)
    print("tapped", round(time.perf_counter() - t0, 3), flush=True)

    first_s = None
    first_i = None
    series = []
    deadline = t0 + 16.0
    i = 0
    while time.perf_counter() < deadline:
        cap = raw_screencap()
        dt = time.perf_counter() - t0
        st = canvas_stats(cap)
        series.append((i, dt, st["n"], st["dark"], st["bright"], st["color"]))
        print(
            "burst i=%d t=%.3f n=%d dark=%d bright=%d color=%d"
            % (i, dt, st["n"], st["dark"], st["bright"], st["color"]),
            flush=True,
        )
        if first_s is None and is_term_first_frame(st):
            first_s = dt
            first_i = i
            save_png("shot-ab-%s-firstframe.png" % TAG)
            print(
                "FIRST_FRAME t=%.3f i=%d dark=%d bright=%d color=%d"
                % (dt, i, st["dark"], st["bright"], st["color"]),
                flush=True,
            )
            break
        i += 1
        if i > 90:
            break
        time.sleep(0.08)

    save_png("shot-ab-%s-end.png" % TAG)
    open(os.path.join(OUTDIR, "series.tsv"), "w").write(
        "i\tsec\tn\tdark\tbright\tcolor\n"
        + "\n".join("%d\t%.4f\t%d\t%d\t%d\t%d" % r for r in series)
        + "\n"
    )
    # stop record
    try:
        subprocess.call([ADB, "-s", SERIAL, "shell", "pkill", "-INT", "screenrecord"])
    except Exception:
        pass
    time.sleep(0.8)
    if rec.poll() is None:
        rec.send_signal(signal.SIGINT)
        time.sleep(0.4)
        rec.kill()
    try:
        sh(ADB, "-s", SERIAL, "pull", rec_remote, os.path.join(NODE, "rec-ab-%s.mp4" % TAG))
    except Exception as e:
        print("pull_rec", e, flush=True)

    if first_s is None:
        print("RESULT timeout>16s", flush=True)
        return 0
    print("RESULT first_frame_s=%.3f" % first_s, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
