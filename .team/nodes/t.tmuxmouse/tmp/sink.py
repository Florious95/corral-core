#!/usr/bin/env python3
"""Sink: raw tty (like a TUI), request 1002+1006, copy stdin bytes to a log."""
import sys
import termios
import tty

log_path = sys.argv[1]
fd = sys.stdin.fileno()
old = termios.tcgetattr(fd)
tty.setraw(fd)
try:
    sys.stdout.buffer.write(b"\x1b[?1002h\x1b[?1006h")
    sys.stdout.buffer.flush()
    with open(log_path, "ab", buffering=0) as log:
        while True:
            b = sys.stdin.buffer.read(1)
            if not b:
                break
            log.write(b)
            log.flush()
finally:
    termios.tcsetattr(fd, termios.TCSADRAIN, old)
