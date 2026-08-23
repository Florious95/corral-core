#!/bin/sh
# 换生产 daemon 二进制，**原样继承它当前的启动参数**。
#
# 为什么要这个脚本：daemon 的配对 token 只在它的 argv 里（`-token`，不持久化到 state-dir，
# 见 server/internal/config/config.go:193）。leader ⛔ 不许读 argv（CLAUDE.md 凭据红线），
# 所以无法手工拼出重启命令。本脚本把 argv 从旧进程**管道式**取出并直接用于 exec：
# **不 echo、不落日志、不进任何人的上下文**。同 `TS_AUTHKEY` 的处置方式。
#
# 用法：sh tools/swap-prod-daemon.sh <新二进制路径>
#       sh tools/swap-prod-daemon.sh --rollback        # 换回上一次备份
#
# 做三件事（CLAUDE.md 2026-08-14 用户裁定的固定流程）：先备份现二进制 → 换 → 起完核 :9900 在听。
set -eu

BAK_DIR=.team/nodes/_driver/daemon-backup
mkdir -p "$BAK_DIR"

PID=$(lsof -nP -iTCP:9900 -sTCP:LISTEN -t 2>/dev/null | head -1)
[ -n "${PID:-}" ] || { echo "FAIL :9900 上没有监听进程，没什么可换的"; exit 1; }

# 现二进制路径（txt fd），⛔ 这一步不碰 argv
CUR=$(lsof -a -p "$PID" -d txt -Fn 2>/dev/null | grep '^n' | cut -c2- | head -1)
[ -n "${CUR:-}" ] || { echo "FAIL 认不出 pid ${PID} 的可执行文件"; exit 1; }
CWD=$(lsof -a -p "$PID" -d cwd -Fn 2>/dev/null | grep '^n' | cut -c2- | head -1)

if [ "${1:-}" = "--rollback" ]; then
  NEW=$(ls -t "$BAK_DIR"/agentmirrord.* 2>/dev/null | head -1)
  [ -n "${NEW:-}" ] || { echo "FAIL 没有可回退的备份"; exit 1; }
  echo "回退到备份：$NEW"
else
  NEW="${1:-}"
  [ -n "$NEW" ] && [ -f "$NEW" ] || { echo "用法：sh $0 <新二进制路径> | --rollback"; exit 1; }
fi

STAMP=$(date +%Y%m%d-%H%M%S)
cp "$CUR" "$BAK_DIR/agentmirrord.$STAMP"
echo "已备份现二进制 -> $BAK_DIR/agentmirrord.$STAMP"
echo "现: $(md5 -q "$CUR" | cut -c1-12)   新: $(md5 -q "$NEW" | cut -c1-12)"

# 🔴 argv 全程只在管道里走：⛔ 不 echo、⛔ 不写文件。
# `ps -o args=` 的输出直接喂给 python，由 python fork+exec 后立刻退出。
ARGS_SRC=$(ps -o args= -p "$PID")

kill "$PID"
sleep 2
cp "$NEW" "$CUR"

printf '%s' "$ARGS_SRC" | CWD="$CWD" python3 -c '
import os, shlex, sys
argv = shlex.split(sys.stdin.read())
if not argv:
    sys.stderr.write("FAIL 取不到原 argv\n"); sys.exit(1)
os.chdir(os.environ["CWD"])
if os.fork() == 0:
    os.setsid()
    fd = os.open("/dev/null", os.O_RDWR)
    os.dup2(fd, 0); os.dup2(fd, 1); os.dup2(fd, 2)
    os.execvp(argv[0], argv)   # ⛔ 从不打印 argv
'

sleep 3
NP=$(lsof -nP -iTCP:9900 -sTCP:LISTEN -t 2>/dev/null | head -1)
[ -n "${NP:-}" ] || { echo "FAIL 换完之后 :9900 没有监听——立刻 --rollback"; exit 1; }
echo "PASS 新 daemon 已在 :9900 监听（pid ${NP}），二进制 $(md5 -q "$CUR" | cut -c1-12)"
echo "回退：sh $0 --rollback"
