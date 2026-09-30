#!/bin/sh
# 必须由使用者先检查并按自己的 Team 修改这三项，然后 trial；未经 trial 不得 install。
# 2026-08-24 本 leader 核过：本仓、30 分钟、nodeprobe state.sh、team=remote-agent-android。
HEARTBEAT_WORKSPACE='/Volumes/nvme/Projects/远程Agent安卓'
HEARTBEAT_INTERVAL_SECONDS='1800'
TEAM_ACTIVITY_STATE_SH='/Users/alauda/.agents/skills/tmux-node-activity/scripts/state.sh'
HEARTBEAT_TEAM='remote-agent-android'
HEARTBEAT_PY=/Users/alauda/.agents/skills/team-heartbeat/scripts/heartbeat.py

if [ "${1:-}" = "--print-config" ]; then
  printf '%s\n' "$HEARTBEAT_WORKSPACE" "$HEARTBEAT_INTERVAL_SECONDS" "$TEAM_ACTIVITY_STATE_SH"
  exit 0
fi
exec /usr/bin/python3 "$HEARTBEAT_PY" \
  --workspace "$HEARTBEAT_WORKSPACE" \
  --state-script "$TEAM_ACTIVITY_STATE_SH" \
  --team "$HEARTBEAT_TEAM" \
  --send
