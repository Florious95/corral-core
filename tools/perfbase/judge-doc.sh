#!/bin/sh
# 通用文书判据（收账格/升报格用）：$1 = 文件 glob（相对本 worktree）。
# 只判「文书在且不是空壳」——升报格的价值是不许静默收工，⛔ 不重做事实判断。
# 四态：0=通过；1=不通过；2=不可判。
set -u
P="${1:-}"
[ -n "$P" ] || { echo "UNJUDGEABLE 判据未传路径"; exit 2; }
# shellcheck disable=SC2012,SC2086
F=$(ls $P 2>/dev/null | tail -1)
[ -n "$F" ] || { echo "FAIL 文书不存在：$P"; exit 1; }
N=$(wc -l < "$F" | tr -d ' ')
[ "$N" -ge 10 ] || { echo "FAIL $F 只有 $N 行，空壳不算交付"; exit 1; }
echo "PASS $F（$N 行）"
exit 0
