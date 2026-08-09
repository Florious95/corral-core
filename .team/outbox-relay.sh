#!/bin/bash
# outbox 自动转投器：裁定席写 outbox-framework.md，本脚本增量转投框架 leader。
# 存在理由：0.5.61 下 worker pane 跨工作区直投不通（A-13），而转投若走 leader 会话
# 就要烧 leader 上下文（用户裁定=浪费）。host shell 跑 team-agent CLI 已实证可达，
# 故用常驻脚本把 leader 从管道里拆掉。0.5.62 修复后裁定席恢复直投、本脚本退役。
set -u
WS=/Volumes/nvme/Projects/远程Agent安卓
OUTBOX="$WS/.team/adjudicator/outbox-framework.md"
STATE="$WS/.team/adjudicator/.outbox-relay-offset"
TARGET='/Users/alauda/Documents/code/agent前沿探索/多agent协作::refactor-maintainability/leader'
LOG="$WS/.team/logs/outbox-relay.log"
export PATH="/opt/homebrew/bin:$PATH"

while true; do
  if [ -f "$OUTBOX" ]; then
    size=$(stat -f%z "$OUTBOX")
    last=$(cat "$STATE" 2>/dev/null || echo 0)
    if [ "$size" -gt "$last" ]; then
      chunk=$(tail -c +"$((last + 1))" "$OUTBOX")
      if [ -n "$(printf '%s' "$chunk" | tr -d '[:space:]')" ]; then
        if team-agent send "$TARGET" "[remote-agent-android 裁定席·outbox 自动转投] $chunk" \
             --workspace "$WS" >> "$LOG" 2>&1; then
          echo "$size" > "$STATE"
          echo "$(date '+%F %T') relayed bytes $last..$size" >> "$LOG"
        else
          echo "$(date '+%F %T') send failed, will retry" >> "$LOG"
        fi
      else
        echo "$size" > "$STATE"
      fi
    fi
  fi
  sleep 120
done
