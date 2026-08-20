#!/usr/bin/env python3
"""Fixed ANSI stream for A-pf-jank. Same bytes every run. --seconds N then exit."""
import os
import sys
import time

ESC = "\x1b"
BLOCK = (
    f"{ESC}[31mA16R{ESC}[32mA16G{ESC}[34mA16B{ESC}[91mBR{ESC}[0m "
    f"{ESC}[38;5;196mI196{ESC}[38;5;46mI46{ESC}[38;5;21mI21{ESC}[38;5;244mI244{ESC}[0m\n"
    f"{ESC}[38;2;255;0;0mTCr{ESC}[38;2;0;0;255mTCb{ESC}[38;2;255;175;0mTCo{ESC}[0m "
    f"{ESC}[48;5;0m.0.{ESC}[48;5;16m.16{ESC}[48;5;254m.254{ESC}[48;2;12;12;40m.rgb{ESC}[0m\n"
    "BOX \u250c" + "\u2500" * 12 + "\u2510 \u2502 \u2588\u2593\u2592\u2591 \u2502 \u2514"
    + "\u2500" * 12 + "\u2518\n"
    "BLK \u2580\u2584\u2588\u258c\u2590\u25a0\u25a1\n"
)


def pump(seconds):
    t0 = time.time()
    n = 0
    while time.time() - t0 < seconds:
        sys.stdout.write(BLOCK)
        sys.stdout.flush()
        n += 1
        time.sleep(0.016)
    sys.stderr.write("pump_lines=%d\n" % n)


def main():
    seconds = 25.0
    worker = False
    go = "go"
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--seconds" and i + 1 < len(args):
            seconds = float(args[i + 1])
            i += 2
        elif args[i] == "--worker":
            worker = True
            i += 1
        elif args[i] == "--go" and i + 1 < len(args):
            go = args[i + 1]
            i += 2
        else:
            i += 1
    if worker:
        sys.stdout.write("perf-pump worker\n")
        sys.stdout.flush()
        while True:
            if os.path.exists(go):
                raw = open(go, encoding="utf-8").read().strip() or str(seconds)
                try:
                    os.remove(go)
                except OSError:
                    pass
                pump(float(raw))
            time.sleep(0.05)
    pump(seconds)


if __name__ == "__main__":
    main()
