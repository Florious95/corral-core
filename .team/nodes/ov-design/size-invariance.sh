#!/usr/bin/env bash
# size-invariance.sh — 064 尺寸不变门
# 隔离 tmux server 上：采全部窗口宽高 → 走抓屏路径若干秒 → 再采。
# 逐窗口必须完全相等。任何一个变了非零退出。
# 抓屏路径 = 只 attach scratch（winsize 对齐），choose-tree + refresh-client，不挂 user。
set -u
fail() { echo "FAIL $1"; exit 1; }
pass() { echo "PASS $1"; }

command -v tmux >/dev/null || fail "tmux 不在 PATH"
command -v python3 >/dev/null || fail "python3 不在 PATH"

BASE=/tmp/ov-sizeinv
SOCK="$BASE/sock"
rm -rf "$BASE"
mkdir -p "$BASE"
unset TMUX
unset TMUX_TMPDIR

cleanup() {
  if [ -n "${SOCK:-}" ] && [ -e "$SOCK" ]; then
    tmux -S "$SOCK" kill-server 2>/dev/null || true
  fi
}
trap cleanup EXIT

tmux -S "$SOCK" new-session -d -s user -n u0 -x 80 -y 24 'sleep 3600' \
  || fail "new-session 失败"
SESS="$(tmux -S "$SOCK" list-sessions -F '#{session_name}' 2>/dev/null || true)"
echo "$SESS" | grep -qx user || fail "自检失败：会话不在隔离 socket（got='$SESS'）"
tmux -S "$SOCK" new-window -t user -n u1 'sleep 3600' || fail "new-window u1"
tmux -S "$SOCK" new-session -d -s scratch -n sc0 -x 80 -y 24 'sleep 3600' || fail "scratch session"

snap() {
  tmux -S "$SOCK" list-windows -a -F '#{session_name}:#{window_index}:#{window_name}=#{window_width}x#{window_height}' | sort
}

BEFORE="$(snap)"
echo "=== BEFORE ==="
echo "$BEFORE"

# 抓屏路径：专用客户端只挂 scratch，行列与窗口一致，刷新 2s 后拆掉
python3 - "$SOCK" <<'PY'
import os, pty, time, subprocess, struct, fcntl, termios, signal, select, sys
SOCK = sys.argv[1]

def tmux(*args):
    return subprocess.run(["tmux", "-S", SOCK, *args], text=True, capture_output=True)

pid, fd = pty.fork()
if pid == 0:
    os.environ.pop("TMUX", None)
    os.environ.pop("TMUX_TMPDIR", None)
    os.environ["TERM"] = "xterm-256color"
    os.execvp("tmux", ["tmux", "-S", SOCK, "attach", "-t", "scratch"])
fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
time.sleep(0.3)
tmux("choose-tree", "-t", "scratch:0.0")
cname = tmux("list-clients", "-F", "#{client_name}").stdout.strip().splitlines()
end = time.time() + 2.0
while time.time() < end:
    if cname:
        tmux("refresh-client", "-t", cname[0])
    # drain pty, do not print
    r, _, _ = select.select([fd], [], [], 0.1)
    if r:
        try:
            os.read(fd, 16384)
        except OSError:
            break
os.kill(pid, signal.SIGHUP)
time.sleep(0.2)
PY

AFTER="$(snap)"
echo "=== AFTER ==="
echo "$AFTER"

if [ "$BEFORE" != "$AFTER" ]; then
  echo "=== DIFF ==="
  diff -u <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$AFTER") || true
  fail "有窗口尺寸变化（零尺寸影响未满足）"
fi
pass "全部窗口 #{window_width}x#{window_height} 开/关抓屏路径前后一致"
echo "size-invariance: ALL PASS (exit 0)"
exit 0
