#!/usr/bin/env python3
"""E1 reflow-sync + E2 235-col capture. Isolated tmux only. No production :9900."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone

WORKTREE = "/Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-selfcheck"
NODE_TMP = os.path.join(WORKTREE, ".team/nodes/t.selfcheck/tmp")
PERF_DIR = os.path.join(WORKTREE, ".team/perf")
FX_DIR = os.path.join(WORKTREE, "app/core-terminal/src/test/resources/capture")
SOCK_DIR = "/tmp/e2e-selfcheck"
SOCK = os.path.join(SOCK_DIR, "s")  # short path; unix socket ~104 byte limit
SESSION = "scself"
DELAYS_MS = [0, 20, 50, 100, 200, 500]
N = 20
NARROW_FROM, NARROW_TO = 220, 80
WIDEN_FROM, WIDEN_TO = 80, 220
E2_COLS, E2_ROWS = 235, 24
ROWS = 24

os.makedirs(NODE_TMP, exist_ok=True)
os.makedirs(PERF_DIR, exist_ok=True)
os.makedirs(FX_DIR, exist_ok=True)
os.makedirs(SOCK_DIR, exist_ok=True)

SEED = (
    "┌─ E1E2MARK 中文框 ─┐\n"
    "│ box-drawing + 汉字 │\n"
    "└────────────────────┘\n"
    + ("A" * 200)
    + "\n"
    + ("中文长行一二三四五六七八九十" * 8)
    + "\n"
)


def scrub_env() -> dict:
    env = {k: v for k, v in os.environ.items() if k not in ("TMUX", "TMUX_TMPDIR")}
    return env


def tmux(args: list[str], check: bool = False) -> subprocess.CompletedProcess:
    cmd = ["tmux", "-S", SOCK, "-f", "/dev/null", *args]
    r = subprocess.run(cmd, env=scrub_env(), capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(
            f"tmux {args!r} rc={r.returncode} stdout={r.stdout!r} stderr={r.stderr!r}"
        )
    return r


def load1() -> float:
    out = subprocess.check_output(["uptime"], text=True)
    m = re.search(r"load averages?:\s*([0-9.]+)", out)
    if not m:
        raise RuntimeError(f"cannot parse load1 from uptime: {out!r}")
    return float(m.group(1))


def selfcheck_socket() -> None:
    """Must be on our socket; tmux silently falls back to the user's server."""
    r = tmux(["list-sessions"])
    if r.returncode != 0 or SESSION.encode() not in r.stdout:
        raise RuntimeError(
            f"selfcheck FAIL: session {SESSION} not on {SOCK}: "
            f"rc={r.returncode} out={r.stdout!r} err={r.stderr!r}"
        )
    # Must NOT appear on the default socket (user fleet).
    default = subprocess.run(
        ["tmux", "list-sessions"],
        env=scrub_env(),
        capture_output=True,
    )
    if default.returncode == 0 and SESSION.encode() in default.stdout:
        raise RuntimeError(
            "selfcheck FAIL: session leaked onto default tmux socket (user fleet). STOP."
        )
    if SOCK.encode() in (default.stderr + default.stdout) and False:
        pass
    # socket file must exist
    if not os.path.exists(SOCK):
        raise RuntimeError(f"selfcheck FAIL: socket file missing {SOCK}")


def kill_ours() -> None:
    tmux(["kill-server"])
    time.sleep(0.05)


def new_pane(cols: int, rows: int = ROWS) -> None:
    kill_ours()
    r = tmux(
        [
            "new-session",
            "-d",
            "-x",
            str(cols),
            "-y",
            str(rows),
            "-s",
            SESSION,
            "cat",
        ]
    )
    if r.returncode != 0:
        raise RuntimeError(
            f"new-session failed rc={r.returncode} out={r.stdout!r} err={r.stderr!r}"
        )
    selfcheck_socket()
    win = tmux(["display-message", "-p", "-t", SESSION, "#{window_id}"], check=True)
    win_id = win.stdout.decode().strip()
    tmux(["set-option", "-w", "-t", win_id, "window-size", "latest"], check=True)
    seed_path = os.path.join(NODE_TMP, "seed.txt")
    with open(seed_path, "wb") as f:
        f.write(SEED.encode("utf-8"))
    tmux(["load-buffer", "-b", "scseed", seed_path], check=True)
    tmux(["paste-buffer", "-d", "-b", "scseed"], check=True)
    deadline = time.time() + 3
    while time.time() < deadline:
        cap = capture()
        if b"E1E2MARK" in cap:
            return
        time.sleep(0.05)
    raise RuntimeError(f"seed never appeared in pane; last capture={capture()[:400]!r}")


def window_id() -> str:
    r = tmux(["display-message", "-p", "-t", SESSION, "#{window_id}"], check=True)
    return r.stdout.decode().strip()


def resize(cols: int, rows: int = ROWS) -> None:
    wid = window_id()
    tmux(["set-option", "-w", "-t", wid, "window-size", "latest"], check=True)
    r = tmux(
        ["resize-window", "-t", wid, "-x", str(cols), "-y", str(rows)],
        check=True,
    )
    _ = r


def capture() -> bytes:
    r = tmux(["capture-pane", "-e", "-p", "-t", SESSION], check=True)
    return r.stdout


def classify_mismatch(a: bytes, b: bytes) -> str:
    if a == b:
        return "identical"
    sa, sb = a.split(b"\n"), b.split(b"\n")
    if sa and sa[-1] == b"":
        sa = sa[:-1]
    if sb and sb[-1] == b"":
        sb = sb[:-1]
    # strip CSI for shape
    csi = re.compile(rb"\x1b\[[0-9;?]*[A-Za-z]")
    ta = [csi.sub(b"", x).rstrip() for x in sa]
    tb = [csi.sub(b"", x).rstrip() for x in sb]
    if ta == tb:
        return "csi_or_trailing_ws_only"
    nonempty_a = [x for x in ta if x]
    nonempty_b = [x for x in tb if x]
    if nonempty_a and nonempty_b and nonempty_a[0] != nonempty_b[0]:
        if nonempty_a[0] in nonempty_b[1:3] or nonempty_b[0] in nonempty_a[1:3]:
            return "top_row_shifted"
        return "top_row_changed"
    if len(nonempty_a) != len(nonempty_b):
        return "wrap_line_count_changed"
    if any(b"\xe2\x94" in x or "┌".encode() in x for x in ta + tb):
        # box drawing present; see if box rows diverged
        box_a = [i for i, x in enumerate(ta) if b"\xe2\x94" in x or "┌".encode() in x or "│".encode() in x]
        box_b = [i for i, x in enumerate(tb) if b"\xe2\x94" in x or "┌".encode() in x or "│".encode() in x]
        if box_a != box_b:
            return "box_row_index_changed"
        for i in box_a:
            if i < len(tb) and ta[i] != tb[i]:
                return "box_line_broken"
    return "other_content_diff"


def run_direction(name: str, src: int, dst: int) -> dict:
    cells: dict[str, dict] = {
        str(d): {"n": 0, "mismatch_n": 0, "mismatch_rate": 0.0, "kinds": {}}
        for d in DELAYS_MS
    }
    samples = []
    for trial in range(N):
        new_pane(src)
        resize(dst)
        t0 = time.monotonic()
        caps: dict[int, bytes] = {}
        for d in DELAYS_MS:
            target = t0 + (d / 1000.0)
            now = time.monotonic()
            if target > now:
                time.sleep(target - now)
            caps[d] = capture()
        gold = caps[500]
        for d in DELAYS_MS:
            cell = cells[str(d)]
            cell["n"] += 1
            if caps[d] != gold:
                cell["mismatch_n"] += 1
                kind = classify_mismatch(caps[d], gold)
                cell["kinds"][kind] = cell["kinds"].get(kind, 0) + 1
                if len(samples) < 6:
                    samples.append(
                        {
                            "direction": name,
                            "trial": trial,
                            "delay_ms": d,
                            "kind": kind,
                            "bytes_delay": len(caps[d]),
                            "bytes_500": len(gold),
                            "head_delay": caps[d][:240].decode("utf-8", "replace"),
                            "head_500": gold[:240].decode("utf-8", "replace"),
                        }
                    )
        # verify we actually changed size
        geo = tmux(
            ["display-message", "-p", "-t", SESSION, "#{pane_width}x#{pane_height}"],
            check=True,
        ).stdout.decode().strip()
        if not geo.startswith(str(dst) + "x"):
            raise RuntimeError(f"{name} trial {trial}: pane geometry {geo!r} want {dst}x{ROWS}")
    for d, cell in cells.items():
        n = cell["n"]
        cell["mismatch_rate"] = cell["mismatch_n"] / n if n else 0.0
    cells["_samples"] = samples
    return cells


def capture_e2_fixture() -> dict:
    new_pane(E2_COLS, E2_ROWS)
    geo = (
        tmux(
            ["display-message", "-p", "-t", SESSION, "#{pane_width}x#{pane_height}"],
            check=True,
        )
        .stdout.decode()
        .strip()
    )
    raw = capture()
    if b"E1E2MARK" not in raw:
        raise RuntimeError("E2 fixture missing marker")
    fx = os.path.join(FX_DIR, "wide235.bin")
    with open(fx, "wb") as f:
        f.write(raw)
    meta_path = os.path.join(FX_DIR, "wide235.meta.txt")
    ver = subprocess.check_output(["tmux", "-V"], text=True).strip()
    with open(meta_path, "w", encoding="utf-8") as f:
        f.write(
            f"source=tmux capture-pane -e -p\n"
            f"tmux={ver}\n"
            f"geometry={geo}\n"
            f"session={SESSION}\n"
            f"socket={SOCK}\n"
            f"bytes={len(raw)}\n"
            f"sha256={__import__('hashlib').sha256(raw).hexdigest()}\n"
            f"command=new-session -d -x {E2_COLS} -y {E2_ROWS} cat; load-buffer; paste-buffer\n"
            f"note=NOT hand-crafted. Taken from isolated tmux on this host.\n"
        )
    return {"path": fx, "bytes": len(raw), "geometry": geo, "tmux": ver}


def main() -> int:
    load_before = load1()
    ver = subprocess.check_output(["tmux", "-V"], text=True).strip()
    print(f"load1={load_before} tmux={ver} sock={SOCK}", flush=True)

    kill_ours()
    # bootstrap session so first selfcheck has something to list — new_pane does this.
    try:
        narrow = run_direction("narrow", NARROW_FROM, NARROW_TO)
        widen = run_direction("widen", WIDEN_FROM, WIDEN_TO)
        e2 = capture_e2_fixture()
    finally:
        kill_ours()
        shutil.rmtree(SOCK_DIR, ignore_errors=True)

    load_after = load1()
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = {
        "observed_at": datetime.now(timezone.utc).isoformat(),
        "load1": load_before,
        "load1_after": load_after,
        "tmux": ver,
        "n_per_cell": N,
        "delays_ms": DELAYS_MS,
        "narrow_from": NARROW_FROM,
        "narrow_to": NARROW_TO,
        "widen_from": WIDEN_FROM,
        "widen_to": WIDEN_TO,
        "method": (
            "per trial: new detached cat pane at src size, paste seed (box+CJK+200A), "
            "set-option window-size latest, resize-window to dst, capture-pane -e -p at "
            "delays from resize return; compare each delay to same-trial delay=500ms bytes"
        ),
        "narrow": {k: v for k, v in narrow.items() if not k.startswith("_")},
        "widen": {k: v for k, v in widen.items() if not k.startswith("_")},
        "mismatch_samples": (narrow.get("_samples") or []) + (widen.get("_samples") or []),
        "e2_fixture": e2,
        "socket": SOCK,
        "cleaned": True,
    }
    path = os.path.join(PERF_DIR, f"reflow-sync-{ts}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"wrote {path}", flush=True)
    print(json.dumps({"e2": e2, "narrow0": out["narrow"]["0"], "widen0": out["widen"]["0"]}), flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        kill_ours()
        shutil.rmtree(SOCK_DIR, ignore_errors=True)
        print(f"FAIL {type(e).__name__}: {e}", file=sys.stderr)
        raise
