#!/usr/bin/env python3
"""Isolate tmux, inject SGR via send-keys -l/-H (same as InjectRaw), measure mouse on vs off."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

WORK = Path("/Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-tmuxmouse")
NODE_TMP = WORK / ".team/nodes/t.tmuxmouse/tmp"
SINK_PY = NODE_TMP / "sink.py"
SOCK_DIR = Path("/tmp/e2e-tmxm")
SOCK = SOCK_DIR / "tmxm.sock"
SESSION = "tmxm"
TARGET = "tmxm:0.0"
N = 10
INJECTED = b"\x1b[<0;5;3M\x1b[<0;5;3m"
LOG = NODE_TMP / "sink.bin"

env = os.environ.copy()
env.pop("TMUX", None)
env.pop("TMUX_TMPDIR", None)


def tmux(*args: str, check: bool = True, timeout: float = 8.0) -> subprocess.CompletedProcess[str]:
    cmd = ["tmux", "-S", str(SOCK), "-f", "/dev/null", *args]
    return subprocess.run(
        cmd,
        env=env,
        check=check,
        capture_output=True,
        text=True,
        timeout=timeout,
    )


def inject() -> None:
    # Same split as server/internal/bridge/inject_raw.go: C0 via -H hex, rest via -l.
    tmux("send-keys", "-t", TARGET, "-H", "--", "1b")
    tmux("send-keys", "-t", TARGET, "-l", "--", "[<0;5;3M")
    tmux("send-keys", "-t", TARGET, "-H", "--", "1b")
    tmux("send-keys", "-t", TARGET, "-l", "--", "[<0;5;3m")


def flags() -> str:
    p = tmux(
        "display-message",
        "-t",
        TARGET,
        "-p",
        "std=#{mouse_standard_flag} btn=#{mouse_button_flag} all=#{mouse_all_flag} sgr=#{mouse_sgr_flag}",
    )
    return p.stdout.strip()


def load1() -> float:
    out = subprocess.check_output(["sysctl", "-n", "vm.loadavg"], text=True)
    # "{ 6.95 7.12 7.01 }"
    parts = out.replace("{", "").replace("}", "").split()
    return float(parts[0])


def pane_meta() -> dict[str, str]:
    p = tmux(
        "display-message",
        "-t",
        TARGET,
        "-p",
        "#{pane_in_mode}|#{window_panes}|#{pane_id}|#{pane_active}",
    )
    in_mode, panes, pane_id, active = p.stdout.strip().split("|")
    return {
        "pane_in_mode": in_mode,
        "window_panes": panes,
        "pane_id": pane_id,
        "pane_active": active,
    }


def wait_bytes(before: int, want: int, timeout_s: float = 1.5) -> bytes:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if not LOG.exists():
            time.sleep(0.02)
            continue
        sz = LOG.stat().st_size
        if sz >= before + want:
            data = LOG.read_bytes()
            return data[before:]
        time.sleep(0.02)
    if not LOG.exists():
        return b""
    data = LOG.read_bytes()
    return data[before:] if len(data) > before else data[before:] if data else b""


def selfcheck() -> None:
    ls = tmux("list-sessions")
    names = [ln.split(":", 1)[0] for ln in ls.stdout.splitlines() if ln.strip()]
    if names != [SESSION]:
        raise SystemExit(f"SELFCHECK FAIL sessions={names!r} (want only {SESSION}); abort, no kill-server")
    shown = tmux("display-message", "-p", "#{socket_path}").stdout.strip()
    if shown != str(SOCK) and os.path.realpath(shown) != os.path.realpath(SOCK):
        # some tmux versions print empty; also accept if list-sessions only had ours
        if shown and Path(shown).name != SOCK.name:
            raise SystemExit(f"SELFCHECK FAIL socket_path={shown!r} want={SOCK}")
    print(f"selfcheck ok sessions={names} socket={shown or SOCK}")


def start_server() -> None:
    if SOCK_DIR.exists():
        subprocess.run(["tmux", "-S", str(SOCK), "kill-server"], env=env, capture_output=True)
        time.sleep(0.2)
    SOCK_DIR.mkdir(parents=True, exist_ok=True)
    NODE_TMP.mkdir(parents=True, exist_ok=True)
    if LOG.exists():
        LOG.unlink()
    LOG.write_bytes(b"")
    sink_cmd = f"python3 {SINK_PY} {LOG}"
    tmux(
        "new-session",
        "-d",
        "-s",
        SESSION,
        "-x",
        "80",
        "-y",
        "24",
        sink_cmd,
    )
    # dummy neighbour pane: detect whether inject steals focus / splits further
    tmux("split-window", "-h", "-t", TARGET, "sleep 3600")
    tmux("select-pane", "-t", TARGET)
    time.sleep(0.4)
    selfcheck()


def set_mouse(on: bool) -> None:
    tmux("set-option", "-g", "mouse", "on" if on else "off")
    tmux("set-option", "-s", "mouse", "on" if on else "off")
    tmux("set-option", "-w", "-t", SESSION, "mouse", "on" if on else "off")
    shown = tmux("show-options", "-g", "mouse").stdout.strip()
    print(f"  mouse option now: {shown!r}")


def run_arm(label: str) -> dict:
    set_mouse(label == "on")
    time.sleep(0.2)
    mode = flags()
    print(f"  sink flags ({label}): {mode}")
    received = 0
    exact = 0
    copy_mode = 0
    pane_changed = 0
    samples = []
    for i in range(N):
        before_meta = pane_meta()
        before_sz = LOG.stat().st_size if LOG.exists() else 0
        inject()
        got = wait_bytes(before_sz, len(INJECTED))
        after_meta = pane_meta()
        rec = len(got) > 0
        match = got == INJECTED
        if rec:
            received += 1
        if match:
            exact += 1
        if after_meta["pane_in_mode"] not in ("0", "1"):
            pass
        if after_meta["pane_in_mode"] == "1" and before_meta["pane_in_mode"] != "1":
            copy_mode += 1
        if after_meta["pane_id"] != before_meta["pane_id"] or after_meta["window_panes"] != before_meta["window_panes"]:
            pane_changed += 1
        samples.append(
            {
                "i": i,
                "got_hex": got.hex(),
                "got_len": len(got),
                "exact": match,
                "before": before_meta,
                "after": after_meta,
            }
        )
        print(f"    {label}[{i}] len={len(got)} exact={match} in_mode={after_meta['pane_in_mode']} panes={after_meta['window_panes']}")
    return {
        "n": N,
        "received_count": received,
        "byte_exact_match_count": exact,
        "entered_copy_mode_count": copy_mode,
        "pane_layout_changed_count": pane_changed,
        "sink_mode_flags": mode,
        "samples": samples,
    }


def cleanup() -> None:
    subprocess.run(["tmux", "-S", str(SOCK), "kill-server"], env=env, capture_output=True)
    # only our sock dir
    if SOCK_DIR.exists():
        shutil.rmtree(SOCK_DIR, ignore_errors=True)


def main() -> int:
    NODE_TMP.mkdir(parents=True, exist_ok=True)
    (WORK / ".team/perf").mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%Y%m%d-%H%M%S")
    out_json = WORK / f".team/perf/tmuxmouse-{ts}.json"
    ld = load1()
    try:
        start_server()
        # wait until flags show tracking (sink printed DECSET)
        flags_wait = ""
        for _ in range(40):
            flags_wait = flags()
            if "sgr=1" in flags_wait and "btn=1" in flags_wait:
                break
            time.sleep(0.1)
        print(f"sink flags after start: {flags_wait}")
        if "sgr=1" not in flags_wait:
            Path(NODE_TMP / "blocked_sink.txt").write_text(flags_wait + "\n", encoding="utf-8")
            print("BLOCKED_SINK flags never showed sgr=1")
            result = {
                "blocked_sink": True,
                "sink_mode_flags": flags_wait,
                "load1": ld,
                "injected_bytes": INJECTED.decode("ascii"),
                "on": {"n": 0, "received_count": 0, "byte_exact_match_count": 0},
                "off": {"n": 0, "received_count": 0, "byte_exact_match_count": 0},
            }
            out_json.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            return 2
        off = run_arm("off")
        on = run_arm("on")
        result = {
            "injected_bytes": INJECTED.decode("ascii"),
            "injected_hex": INJECTED.hex(),
            "inject_path": "send-keys -H 1b + send-keys -l '[<0;5;3M' + same for release m (InjectRaw split)",
            "sink_mode_flags": {
                "after_start": flags_wait,
                "off": off["sink_mode_flags"],
                "on": on["sink_mode_flags"],
            },
            "load1": ld,
            "off": {k: off[k] for k in ("n", "received_count", "byte_exact_match_count", "entered_copy_mode_count", "pane_layout_changed_count")},
            "on": {k: on[k] for k in ("n", "received_count", "byte_exact_match_count", "entered_copy_mode_count", "pane_layout_changed_count")},
            "off_samples": off["samples"],
            "on_samples": on["samples"],
            "socket": str(SOCK),
        }
        out_json.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print("wrote", out_json)
        return 0
    finally:
        cleanup()
        print("cleaned isolated tmux", SOCK)


if __name__ == "__main__":
    sys.exit(main())
