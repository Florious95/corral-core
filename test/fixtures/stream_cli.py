#!/usr/bin/env python3
# stream_cli.py — a deterministic streaming-CLI fixture for the D-27 red tests.
#
# Models the two behaviors the D-27 goal contrasts:
#   * CLI self-output  —— a periodic bottom-append line, NO user input involved
#                         (the "CLI 自己吐字追加" case, e.g. an agent status tick);
#   * message append    —— on a line of stdin, the CLI echoes the user line and
#                         appends a short reply at the bottom (the "发消息追加"
#                         case, e.g. Claude Code printing assistant text).
#
# Both are plain bottom-append writes — exactly the "两者本质都是 CLI 底部追加内容"
# the D-27 taskbook entry describes. A healthy mirror must carry BOTH as a stream
# of `delta` frames only: a bottom append is additive terminal bytes, never a full
# snapshot/clear-and-rebuild. If a red test sees a snapshot (or a \x1b[2J/\x1b[3J
# full-clear) in either window, the mirror is doing per-frame rebuild work the CLI
# never asked for — the D-27 "从上往下逐行刷新" artifact.
#
# -u: line-buffered stdout so tmux pipe-pane sees each line as it is written.
import sys, time, threading

def emit(s):
    sys.stdout.write(s)
    sys.stdout.flush()

def self_tick():
    # Self-output: append one status line at the bottom every TICK_MS.
    # Must keep running forever so the test can measure the self-output baseline
    # window and still have ticks arrive during the input window.
    i = 0
    while True:
        time.sleep(0.8)
        i += 1
        emit("status-tick %d\n" % i)

# Marker line proves the fixture booted inside the pane (and gives the input
# echo a non-colliding first line).
emit("== stream-cli ready ==\n")

threading.Thread(target=self_tick, daemon=True).start()

for line in sys.stdin:
    line = line.rstrip("\n")
    # Message append: user line, then a short fixed reply.
    emit("user-said: %s\n" % line)
    emit("reply: ok\n")
