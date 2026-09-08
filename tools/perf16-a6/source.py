#!/usr/bin/env python3
import json
import os
import pathlib
import sys
import time

done_file = pathlib.Path(os.environ["PERF16_A6_BURST_DONE_FILE"])
sys.stdout.write("\033[2J\033[HA6_OLD_RESIDUAL")
sys.stdout.flush()
if sys.stdin.readline().strip() != "go":
    raise SystemExit("source fixture expected go")
sys.stdout.write("\033[8;1HOLD_LATE_FRAME")
sys.stdout.write("\033]0;P16_A6_BURST_BEGIN\007")
sys.stdout.flush()
# Healthy-peer acknowledgements pace real bytes without weakening the 16MiB
# contiguous-X oracle or depending on OS TCP buffer sizes.
ack = done_file.parent / "healthy-ack"
deadline = time.monotonic() + 30
for sent in range(0, 16 * 1024 * 1024, 16384):
    sys.stdout.write("X" * 16384)
    sys.stdout.flush()
    while True:
        acknowledgement = json.loads(ack.read_text()) if ack.exists() else {"bytes": 0, "frames": 0}
        received = acknowledgement["bytes"]
        progress_path = done_file.parent / "progress.json"
        progress = json.loads(progress_path.read_text()) if progress_path.exists() else None
        target_caught_up = os.environ["PERF16_A6_STAGE"] != "ws" or (
            progress is not None and (progress["aborted"] or
                progress["target_frames"] >= acknowledgement["frames"] and
                progress["healthy_frames"] >= acknowledgement["frames"])
        )
        if received >= sent + 16384 and target_caught_up:
            break
        if time.monotonic() >= deadline:
            raise SystemExit(f"paired progress timed out: healthy_bytes={received} want={sent+16384} frames={progress}")
        time.sleep(0.001)
sys.stdout.write("\033]0;P16_A6_BURST_END\007")
sys.stdout.write("\033[2J\033[H\033[1;34mRECOVERED TITLE\033[0m\n")
sys.stdout.write("\033[3;5m日本語 ✓\033[0m\n")
sys.stdout.write("\033[1;5mCURSOR_ORACLE\033[0m\n\n\n\n")
sys.stdout.write("            RECOVERY_DONE")
if os.environ.get("PERF16_A6_CONTROL") == "screen-corruption":
    # One actual tmux cell differs; preserve title, cursor and byte-load oracle.
    # The unchanged full Cell oracle must reject this after real recovery.
    sys.stdout.write("\033[21;1HX\033[7;26H")
sys.stdout.flush()
done_file.write_text("burst-complete\n")
if sys.stdin.readline().strip() != "release":
    raise SystemExit("source fixture expected release")
sys.stdout.write("\033]0;P16_A6_AFTER\007")
sys.stdout.flush()
time.sleep(120)
