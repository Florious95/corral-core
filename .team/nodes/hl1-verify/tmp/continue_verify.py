#!/usr/bin/env python3
"""Continue t.verify from already-paired L1. Isolated rows only."""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_flow import (
    adb_ok, dump_xml, refuse_fleet, save_png, tap_contains, tap_exact,
    session_label, measure_after_tap, back, home_and_resume, raw_screencap,
    canvas_stats, is_term_first_frame, PKG, TMP, nodes,
)


def wait_l1(tag, tries=20):
    adb_ok("shell", "am", "force-stop", PKG)
    time.sleep(0.4)
    adb_ok("shell", "am", "start", "-W", "-n", PKG + "/.MainActivity")
    time.sleep(2.0)
    xml = None
    for i in range(tries):
        xml = dump_xml("ui-l1-%s-%d.xml" % (tag, i))
        refuse_fleet(xml, "l1-%s-%d" % (tag, i))
        if "cwd-static" in xml and "cwd-hist" in xml:
            save_png("shot-l1-%s.png" % tag)
            return xml
        time.sleep(0.8)
    save_png("shot-l1-%s-fail.png" % tag)
    raise SystemExit("L1 missing isolated workspaces")


def open_iso(xml, key, tag):
    if any(bad == key for bad in ("多agent协作", "远程Agent安卓")):
        raise SystemExit("refuse fleet key")
    tap_contains(xml, key)
    time.sleep(1.2)
    xml2 = dump_xml("ui-l2-%s.xml" % tag)
    refuse_fleet(xml2, "l2-" + tag)
    save_png("shot-l2-%s.png" % tag)
    texts = [n["text"] for n in nodes(xml2) if n["text"]]
    print("L2_TEXTS", tag, texts, flush=True)
    # must not look like a fleet workspace list
    if "cwd-static" in " ".join(texts) and key != "cwd-static":
        pass
    return xml2


def run_case(ws_key, tag):
    print("=== COLD", tag, ws_key, "===", flush=True)
    xml = wait_l1(tag + "-coldstart")
    xml2 = open_iso(xml, ws_key, tag)
    label = session_label(xml2)
    print("LABEL", tag, label, flush=True)
    t_cold = measure_after_tap(xml2, label, tag + "-cold", deadline_s=8.0)
    save_png("shot-%s-cold.png" % tag)

    print("=== REENTER", tag, "===", flush=True)
    back()
    time.sleep(1.0)
    xml3 = dump_xml("ui-l2-reenter-%s.xml" % tag)
    save_png("shot-%s-l2-reenter.png" % tag)
    label = session_label(xml3)
    t_re = measure_after_tap(xml3, label, tag + "-reenter", deadline_s=8.0)
    save_png("shot-%s-reenter.png" % tag)

    print("=== RESUME", tag, "===", flush=True)
    home_and_resume()
    time.sleep(1.2)
    save_png("shot-%s-resume.png" % tag)
    st = canvas_stats(raw_screencap())
    glyphs = is_term_first_frame(st)
    open(os.path.join(TMP, "resume-%s.txt" % tag), "w").write(
        "n=%d dark=%d bright=%d color=%d glyphs=%s\n"
        % (st["n"], st["dark"], st["bright"], st["color"], glyphs)
    )
    print("RESUME", tag, st, "glyphs", glyphs, "cold", t_cold, "reenter", t_re, flush=True)
    return t_cold, t_re, glyphs


def main():
    r = {}
    r["static"] = run_case("cwd-static", "static")
    r["hist"] = run_case("cwd-hist", "hist")
    open(os.path.join(TMP, "results.txt"), "w").write(repr(r) + "\n")
    print("RESULTS", r, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
