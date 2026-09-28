#!/usr/bin/env python3
import os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_flow import (
    adb_ok, dump_xml, refuse_fleet, save_png, tap_contains, session_label,
    measure_after_tap, back, home_and_resume, raw_screencap, canvas_stats,
    is_term_first_frame, PKG, TMP, nodes,
)


def wait_l1(tag):
    adb_ok("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    adb_ok("shell", "am", "start", "-W", "-n", PKG + "/.MainActivity")
    time.sleep(2.0)
    for i in range(25):
        xml = dump_xml("ui-l1-%s-%d.xml" % (tag, i))
        refuse_fleet(xml, "l1-%s-%d" % (tag, i))
        if "cwd-static" in xml and "cwd-hist" in xml:
            save_png("shot-l1-%s.png" % tag)
            return xml
        time.sleep(0.7)
    save_png("shot-l1-%s-fail.png" % tag)
    raise SystemExit("L1 missing iso")


def wait_l2_row(xml, key, tag):
    tap_contains(xml, key)
    xml2 = None
    for i in range(15):
        time.sleep(1.0)
        xml2 = dump_xml("ui-l2-%s-%d.xml" % (tag, i))
        texts = [n["text"] for n in nodes(xml2) if n["text"]]
        print("L2_WAIT", tag, i, texts, flush=True)
        if any(t in texts for t in ("bash", "claude", "claude.exe", "Claude")):
            save_png("shot-l2-%s.png" % tag)
            return xml2
    save_png("shot-l2-%s-empty.png" % tag)
    raise SystemExit("L2 no session row for %s" % key)


def run_case(ws_key, tag):
    xml = wait_l1(tag)
    xml2 = wait_l2_row(xml, ws_key, tag)
    label = session_label(xml2)
    t_cold = measure_after_tap(xml2, label, tag + "-cold16", deadline_s=16.0)
    save_png("shot-%s-cold16.png" % tag)
    back()
    time.sleep(1.2)
    xml3 = dump_xml("ui-l2-re-%s.xml" % tag)
    save_png("shot-%s-l2-re.png" % tag)
    label = session_label(xml3)
    t_re = measure_after_tap(xml3, label, tag + "-re16", deadline_s=16.0)
    save_png("shot-%s-re16.png" % tag)
    home_and_resume()
    time.sleep(1.5)
    save_png("shot-%s-resume16.png" % tag)
    st = canvas_stats(raw_screencap())
    g = is_term_first_frame(st)
    open(os.path.join(TMP, "resume-%s-16.txt" % tag), "w").write(repr(st) + " glyphs=" + str(g) + "\n")
    print("CASE", tag, "cold", t_cold, "reenter", t_re, "resume_glyphs", g, flush=True)
    return t_cold, t_re, g


def main():
    r = {"hist": run_case("cwd-hist", "hist"), "static": run_case("cwd-static", "static")}
    open(os.path.join(TMP, "results16.txt"), "w").write(repr(r) + "\n")
    print("RESULTS16", r, flush=True)


if __name__ == "__main__":
    main()
