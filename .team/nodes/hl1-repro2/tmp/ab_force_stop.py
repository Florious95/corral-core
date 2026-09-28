#!/usr/bin/env python3
"""One A/B trial: force-stop app, navigate ONLY isolated workspace, time tap→first frame."""
import os, re, struct, subprocess, sys, time, signal

ADB = os.environ.get("ADB", os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
SERIAL = "emulator-5554"
TMP = os.environ["R2_TMP"]
NODE = os.environ["R2_NODE"]
TAG = sys.argv[1]
PKG = "dev.agentmirror.app"


def sh(*args, timeout=40):
    return subprocess.check_output(list(args), timeout=timeout)


def call(*args, timeout=40):
    return subprocess.call(list(args), timeout=timeout)


def dump(name):
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


def tap_want(xml, want):
    for n in nodes(xml):
        if n["bounds"] and (n["text"] == want or n["desc"] == want):
            x1, y1, x2, y2 = n["bounds"]
            subprocess.check_call(
                [ADB, "-s", SERIAL, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)]
            )
            print("tapped", want, flush=True)
            return True
    return False


def raw_screencap():
    data = sh(ADB, "-s", SERIAL, "exec-out", "screencap")
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
    n = dark = bright = color = 0
    step = 8
    bpp = 4
    for y in range(y0, y1, step):
        row = off + y * w * bpp
        for x in range(x0, x1, step):
            i = row + x * bpp
            if i + 3 > len(data):
                continue
            r, g, b = data[i], data[i + 1], data[i + 2]
            n += 1
            luma = (r + g + b) / 3.0
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


def save_png(name):
    p = os.path.join(NODE, name)
    open(p, "wb").write(sh(ADB, "-s", SERIAL, "exec-out", "screencap", "-p"))
    return p


def wait_xml(pred, tag, n=40):
    last = ""
    for i in range(n):
        subprocess.call([ADB, "-s", SERIAL, "shell", "input", "keyevent", "111"])
        xml = dump("ui-%s-%d.xml" % (tag, i))
        last = xml
        kind = pred(xml)
        print("wait", tag, i, kind, flush=True)
        if kind == "ok":
            return xml
        if kind == "fleet_l1":
            if not tap_want(xml, "hl1repro2cwd"):
                time.sleep(1)
            else:
                time.sleep(1.2)
            continue
        if kind == "session":
            tap_want(xml, "返回")
            time.sleep(1)
            continue
        time.sleep(0.8)
    raise SystemExit("timeout waiting " + tag + " last pairing=" + str("连接主机" in last))


def classify_l1(xml):
    if "连接主机" in xml:
        return "pairing"
    if "重连" in xml or "连接中" in xml:
        return "reconnect"
    if "hl1repro2cwd" in xml and "查看" not in xml:
        # L1 list includes isolated workspace (and maybe fleet). Only tap isolated.
        return "ok"
    if "查看" in xml and "Esc" in xml:
        return "session"
    if "工作区" in xml:
        return "fleet_l1" if "hl1repro2cwd" not in xml else "ok"
    return "other"


def classify_l2(xml):
    if "多agent协作" in xml and "hl1repro2cwd" in xml and "空闲" not in xml and "bash" not in xml:
        return "fleet_l1"
    if "hl1repro2cwd" in xml and ("bash" in xml or "claude" in xml) and "查看" not in xml:
        return "ok"
    if "查看" in xml:
        return "session"
    if "hl1repro2cwd" in xml:
        return "ok" if "bash" in xml or "claude.exe" in xml else "fleet_l1"
    if "连接主机" in xml:
        return "pairing"
    if "重连" in xml:
        return "reconnect"
    return "other"


def main():
    call(ADB, "-s", SERIAL, "shell", "am", "force-stop", PKG)
    time.sleep(0.8)
    call(ADB, "-s", SERIAL, "shell", "am", "start", "-W", "-n", PKG + "/.MainActivity")
    time.sleep(2.0)
    xml = wait_xml(classify_l1, TAG + "-l1")
    if "多agent协作" in xml and "hl1repro2cwd" not in xml:
        raise SystemExit("fleet L1 without isolated workspace")
    if not tap_want(xml, "hl1repro2cwd"):
        raise SystemExit("cannot tap isolated workspace")
    time.sleep(1.2)
    xml = wait_xml(classify_l2, TAG + "-l2")
    label = None
    for cand in ("bash", "claude.exe", "claude"):
        if any(n["text"] == cand for n in nodes(xml)):
            label = cand
            break
    if not label:
        raise SystemExit("no session row on isolated L2")
    save_png("shot-ab2-%s-l2.png" % TAG)

    rec_remote = "/sdcard/r2-%s.mp4" % TAG
    rec = subprocess.Popen(
        [ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", "20", rec_remote],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    time.sleep(0.3)

    t0 = time.perf_counter()
    if not tap_want(xml, label):
        raise SystemExit("tap session failed")
    print("tapped_session", round(time.perf_counter() - t0, 3), flush=True)

    first = None
    series = []
    deadline = t0 + 14.0
    i = 0
    outdir = os.path.join(TMP, "burst2-" + TAG)
    os.makedirs(outdir, exist_ok=True)
    while time.perf_counter() < deadline:
        cap = raw_screencap()
        dt = time.perf_counter() - t0
        st = canvas_stats(cap)
        series.append((i, dt, st["n"], st["dark"], st["bright"], st["color"]))
        print(
            "burst i=%d t=%.3f dark=%d bright=%d color=%d" % (i, dt, st["dark"], st["bright"], st["color"]),
            flush=True,
        )
        if first is None and is_term_first_frame(st):
            first = dt
            save_png("shot-ab2-%s-firstframe.png" % TAG)
            print("FIRST_FRAME t=%.3f i=%d color=%d" % (dt, i, st["color"]), flush=True)
            break
        i += 1
        if i > 80:
            break
        time.sleep(0.05)
    save_png("shot-ab2-%s-end.png" % TAG)
    open(os.path.join(outdir, "series.tsv"), "w").write(
        "i\tsec\tn\tdark\tbright\tcolor\n"
        + "\n".join("%d\t%.4f\t%d\t%d\t%d\t%d" % r for r in series)
        + "\n"
    )
    try:
        call(ADB, "-s", SERIAL, "shell", "pkill", "-INT", "screenrecord")
    except Exception:
        pass
    time.sleep(0.7)
    if rec.poll() is None:
        rec.send_signal(signal.SIGINT)
        time.sleep(0.3)
        rec.kill()
    try:
        sh(ADB, "-s", SERIAL, "pull", rec_remote, os.path.join(NODE, "rec-ab2-%s.mp4" % TAG))
    except Exception as e:
        print("pull_rec", e, flush=True)

    if first is None:
        print("RESULT first_frame_s=>14 timeout", flush=True)
    else:
        print("RESULT first_frame_s=%.3f" % first, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
