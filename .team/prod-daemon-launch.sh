#!/usr/bin/env bash
# 生产 daemon 唯一启动入口：忽略 Terminal SIGHUP，并固定接管 stdout/stderr。
# 不做 kill、takeover 或重启；单实例拒绝由 agentmirrord 自身守卫负责。
set -euo pipefail

TEAM_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
WORKSPACE="$(dirname -- "$TEAM_DIR")"
DAEMON="$WORKSPACE/server/agentmirrord"
PROD_LOG="$TEAM_DIR/logs/agentmirrord-prod.log"

if [[ ! -x "$DAEMON" ]]; then
  echo "prod launcher: daemon binary is not executable: $DAEMON" >&2
  exit 1
fi

mkdir -p "$TEAM_DIR/logs"
exec nohup "$DAEMON" "$@" </dev/null >>"$PROD_LOG" 2>&1
