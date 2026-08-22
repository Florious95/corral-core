#!/bin/bash
# isolated fake Claude Code alt-screen TUI (level 2). Stay in bash so comm stays claude.
printf '\033[?1049h\033[?25l' || true
frame=0
trap 'printf "\033[?1049l\033[?25h"; exit 0' INT TERM
long='这是一条很长的行用来模拟 Claude Code 把工具输出和思考过程拉得很长：ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-复现白屏-契约092-'
long="${long}${long}${long}"
while true; do
  cols=$(tput cols 2>/dev/null || echo 80)
  rows=$(tput lines 2>/dev/null || echo 24)
  printf '\033[H\033[2J'
  printf '\033[48;5;236m\033[38;5;81m Claude Code \033[0m \033[38;5;244mopus 4.6\033[0m  frame=%s  %sx%s  alt=1\n' "$frame" "$cols" "$rows"
  printf '\033[38;5;244m────────────────────────────────────────────────────────────\033[0m\n'
  printf '\033[38;5;252m ▸ 中文界面：你好世界 会话页复现 打开会话白屏\033[0m\n'
  printf '\033[38;5;114m ● 进行中\033[0m  隔离 cwd=hl1repro2cwd  只许点这个会话\n'
  printf '\033[38;5;215m 思考中…\033[0m 正在读取 requirement-base/entries/092-会话页白屏回归与两处简陋UI.md\n'
  printf '\033[38;5;81m%s\033[0m\n' "$long"
  printf '\n\033[48;5;24m\033[38;5;15m > \033[0m 输入提示（模拟 composer）CJK：修白屏 / 回炉流程\n'
  i=6
  while [ "$i" -lt "$rows" ]; do
    printf '\033[38;5;238m·\033[0m\n'
    i=$((i+1))
  done
  frame=$((frame+1))
  sleep 0.25
done
