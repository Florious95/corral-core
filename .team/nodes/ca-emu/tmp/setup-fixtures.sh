#!/usr/bin/env bash
# 起隔离 tmux（自检在自己的 socket 上）+ 三夹具 pane。
# ⛔ 不碰用户真实舰队 socket；socket 目录用短路径 /tmp/e2e-ca-emu（任务书唯一许可的 /tmp 例外）。
set -uo pipefail
NODE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"      # .../.team/nodes/pb-emu/tmp
SOCKROOT=/tmp/e2e-ca-emu
SOCKDIR="$SOCKROOT/tmux-$(id -u)"
SOCK="$SOCKDIR/default"
FAKEBIN="$NODE/fakebin"
CWD="$NODE/cwd"

mkdir -p "$SOCKDIR" "$FAKEBIN" "$CWD"
# 假 CLI：必须 symlink（cp 的 bash 起不来；shebang 会让 comm=bash 漏白名单）
rm -f "$FAKEBIN/claude"
ln -s /bin/bash "$FAKEBIN/claude"

unset TMUX

# --- 夹具脚本 ---
cat > "$NODE/fx_claude_idle.sh" <<'EOF'
printf '\033[?1049h'          # 进 alt-screen（alt=1）
printf '\033[2J\033[H'
for i in $(seq 1 24); do printf '  claude idle row %02d  ..............................\n' "$i"; done
printf '\n> '
while :; do sleep 3600; done
EOF

cat > "$NODE/fx_redraw_tui.sh" <<'EOF'
draw() {
  printf '\033[2J\033[H'
  for i in $(seq 1 30); do printf '  redraw-tui row %02d cols=%s lines=%s\n' "$i" "${COLUMNS:-?}" "${LINES:-?}"; done
}
trap 'draw' WINCH
draw
while :; do sleep 3600 & wait $!; done
EOF

cat > "$NODE/fx_big_scrollback.sh" <<'EOF'
seq 40000
printf '\n> '
while :; do sleep 3600; done
EOF

start_pane() {
  local name="$1" script="$2"
  tmux -S "$SOCK" new-session -d -s "$name" -c "$CWD" -x 200 -y 50 \
    "exec '$FAKEBIN/claude' '$script'"
}

tmux -S "$SOCK" kill-server 2>/dev/null || true
sleep 0.5
start_pane real_claude_idle "$NODE/fx_claude_idle.sh"
# 大滚回夹具要真滚回：tmux 默认 history-limit=2000 会把 seq 40000 截到 ~1950 行
tmux -S "$SOCK" set -g history-limit 50000
start_pane redraw_tui       "$NODE/fx_redraw_tui.sh"
start_pane big_scrollback   "$NODE/fx_big_scrollback.sh"
# 二级页行名默认是 bash，语义定位分不开三夹具；改成夹具 key（与任务书 02 / 前任同一偏离，显式报出）
tmux -S "$SOCK" rename-window -t real_claude_idle:0 real_claude_idle
tmux -S "$SOCK" rename-window -t redraw_tui:0 redraw_tui
tmux -S "$SOCK" rename-window -t big_scrollback:0 big_scrollback
sleep 3

echo "=== 自检①：socket 路径 ==="
echo "SOCK=$SOCK"
ls -la "$SOCK"
echo "=== 自检②：会话必须在我自己的 socket 上 ==="
tmux -S "$SOCK" list-sessions
echo "=== 自检③：本 socket 的 pane 进程 comm（必须是 claude）==="
for s in real_claude_idle redraw_tui big_scrollback; do
  pid=$(tmux -S "$SOCK" list-panes -t "$s" -F '#{pane_pid}')
  echo -n "$s pane_pid=$pid comm="; ps -o comm= -p "$pid"
done
echo "=== 自检④：用户真实 socket 目录未被我建会话（只列不动）==="
ls -d /private/tmp/tmux-$(id -u) 2>/dev/null || echo "(no real fleet dir)"
