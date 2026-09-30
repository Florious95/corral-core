#!/usr/bin/env python3
"""Node-local contract self-test for the existing four-segment parser.

This never touches product code, devices, tmux, production ports, or shared temp.
It records parser behavior so a parser gap is visible instead of being treated as green.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

NODE = Path(__file__).resolve().parent.parent
REPO = Path("/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/favorites-online-filter/core")
PARSER = REPO / "tools/perfbase/parse-input-ab.py"
ROOT = NODE / "tmp" / "parser-selftest"
FIXTURES = ("big_scrollback", "real_claude_idle", "redraw_tui")
A_MD5 = "0907d6881bb1e034ef33a49f89afaa44"
B_MD5 = "b456b0c9802ba145e5abc782d67f597c"

def invoke(root: Path, order: Path, out: Path, *, a_md5=A_MD5, b_md5=B_MD5,
           baseline_tag="baseline-20260822-release", env_exit=0) -> tuple[int, dict | None, str]:
    cmd = [sys.executable, str(PARSER), "--a", str(root / "A"), "--b", str(root / "B"),
           "--order", str(order), "--out", str(out), "--baseline-tag", baseline_tag,
           "--baseline-reference-md5", A_MD5, "--a-md5", a_md5, "--b-md5", b_md5,
           "--a-revision", "baseline-20260822-release", "--b-revision", "649f5f91",
           "--envcheck-exit", str(env_exit), "--load1", "1.0", "--free", "1024",
           "--inactive", "4096"]
    p = subprocess.run(cmd, text=True, capture_output=True)
    data = json.loads(out.read_text()) if out.exists() else None
    return p.returncode, data, (p.stdout + p.stderr).strip()

def write_packet(root: Path, *, b_delta: int = 1, interleaved: bool = False) -> Path:
    if root.exists(): shutil.rmtree(root)
    for package in ("A", "B"):
        for fixture in FIXTURES:
            (root / package).mkdir(parents=True, exist_ok=True)
    order_lines = []
    for fixture in FIXTURES:
        for n in range(1, 11):
            for package in ("A", "B"):
                order_lines.append(f"{fixture}\t{n}\t{package}\n")
                base = 100000 + n * 1000
                delta = 0 if package == "A" else b_delta
                path = root / package / f"{fixture}-{n:02d}.log"
                oid = f"{package}-{fixture}-{n}"
                if interleaved and fixture == "real_claude_idle" and n == 1 and package == "A":
                    path.write_text(
                        f"D PerfTrace: open_id=A-one ev=tap t={base}\n"
                        f"D PerfTrace: open_id=B-one ev=tap t={base+5}\n"
                        f"D PerfTrace: open_id=A-one ev=route_enter t={base+10}\n"
                        f"D PerfTrace: open_id=B-one ev=route_enter t={base+15}\n"
                        f"D PerfTrace: open_id=A-one ev=first_frame_recv t={base+30}\n"
                        f"D PerfTrace: open_id=B-one ev=first_frame_recv t={base+35}\n"
                        f"D PerfTrace: open_id=A-one ev=first_draw t={base+70}\n"
                        f"D PerfTrace: open_id=B-one ev=first_draw t={base+75}\n",
                        encoding="utf-8")
                    continue
                path.write_text("\n".join([
                    f"D PerfTrace: open_id={oid} ev=tap t={base}",
                    f"D PerfTrace: open_id={oid} ev=route_enter t={base+10+delta}",
                    f"D PerfTrace: open_id={oid} ev=first_frame_recv t={base+30+delta}",
                    f"D PerfTrace: open_id={oid} ev=first_draw t={base+70+delta}",
                ]) + "\n", encoding="utf-8")
    order = root / "order.tsv"
    order.write_text("".join(order_lines), encoding="utf-8")
    return order

def main() -> int:
    ROOT.mkdir(parents=True, exist_ok=True)
    results = {}
    base = ROOT / "positive"
    order = write_packet(base)
    rc, data, text = invoke(base, order, base / "positive.json")
    results["positive"] = {"rc": rc, "verdict": data.get("verdict") if data else None,
                            "counts": {fx: {seg: data["fixtures"][fx][seg]["n"]
                                             for seg in ("tap_to_route_enter", "route_enter_to_first_frame",
                                                         "first_frame_to_first_draw", "tap_to_first_draw")}
                                       for fx in FIXTURES} if data else None}
    if rc != 0 or not data or data.get("verdict") != "pass":
        raise SystemExit(f"positive parser self-test failed: {rc} {text}")

    def expect(name: str, root: Path, order_path: Path, *, parser_gap: bool = False,
               **kwargs: object) -> None:
        r, d, t = invoke(root, order_path, root / f"{name}.json", **kwargs)
        results[name] = {"rc": r, "verdict": d.get("verdict") if d else None,
                         "diagnostic": t.splitlines()[:4], "tool_gap": parser_gap and r == 0}
        if parser_gap:
            if r not in (0, 2):
                raise SystemExit(f"{name} unexpected parser rc={r}: {t}")
        elif r != 2:
            raise SystemExit(f"{name} expected parser rc=2, got {r}: {t}")

    identity = ROOT / "identity"
    order = write_packet(identity)
    expect("wrong_a_md5", identity, order, a_md5="6ae3a104825b51d7d2a8efee40ed4a85")
    expect("same_md5", identity, order, b_md5=A_MD5)

    bad_order = ROOT / "bad-order"
    order = write_packet(bad_order)
    lines = order.read_text().splitlines(True)
    lines[0] = lines[0].replace("\tA\n", "\tB\n")
    order.write_text("".join(lines))
    expect("bad_order", bad_order, order)

    missing = ROOT / "missing-event"
    order = write_packet(missing)
    (missing / "A" / "real_claude_idle-01.log").write_text(
        "D PerfTrace: open_id=A-real_claude_idle-1 ev=tap t=100\n"
        "D PerfTrace: open_id=A-real_claude_idle-1 ev=route_enter t=110\n"
        "D PerfTrace: open_id=A-real_claude_idle-1 ev=first_draw t=170\n", encoding="utf-8")
    expect("missing_event", missing, order)

    nonmono = ROOT / "nonmonotonic"
    order = write_packet(nonmono)
    p = nonmono / "A" / "redraw_tui-01.log"
    # Keep one open_id and make first_draw precede first_frame_recv.
    p.write_text(p.read_text().replace("t=101070", "t=101020"), encoding="utf-8")
    expect("nonmonotonic", nonmono, order)

    dirty_env = ROOT / "dirty-env"
    order = write_packet(dirty_env)
    expect("dirty_env", dirty_env, order, env_exit=2)

    interleaved = ROOT / "interleaved-open-id"
    order = write_packet(interleaved, interleaved=True)
    rc, data, text = invoke(interleaved, order, interleaved / "interleaved.json")
    results["interleaved_open_id"] = {"rc": rc, "verdict": data.get("verdict") if data else None,
                                      "diagnostic": text.splitlines()[:6]}
    # This is a legal packet: each open_id has a monotonic complete chain,
    # while lines from the two chains are interleaved. It must remain accepted.
    if rc != 0:
        raise SystemExit(f"legal interleaved packet was rejected: {text}")
    results["interleaved_open_id"]["accepted"] = True

    (ROOT / "SELFTEST-RESULT.json").write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(results, indent=2, ensure_ascii=False))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
