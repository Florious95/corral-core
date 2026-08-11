#!/bin/bash
# 看门狗守护：让 watchdog.py 死了能自己回来。
#
# 存在理由（2026-08-11 实证）：watchdog.py 被外部杀掉过一次——stdout 日志 0 字节
# （Python 异常会留 traceback，没有 ⇒ 是收到信号直接没的），死亡时刻落在施工席跑
# 全量 tools/gate/run.sh 的窗口内，最可能是测试/清理链路按进程名扫射时误伤。
# 后果：无人值守近 5 小时，期间 w-notif-toggle 建完任务列表即停摆无人发现。
#
# 设计：
#   1. 本脚本用 setsid 启动 ⇒ 独立进程组，按 "watchdog.py" 扫射的清理命中不到它；
#   2. watchdog.py 退出即重启（区分正常跑满 MAX_SAMPLES 与异常早退，都重启，但分别记账）；
#   3. 退出事件写 .team/logs/watchdog-supervisor.log，供事后归因"它到底被杀过几次"。
#
# 用法（leader 侧）：
#   setsid nohup bash .team/watchdog-supervisor.sh >/dev/null 2>&1 &
# 存活核验必须按 cwd，不能只看 pgrep -f（会误匹配别的工程的同名脚本，本工程已栽过一次）。

WS="/Volumes/nvme/Projects/远程Agent安卓"
LOG="$WS/.team/logs/watchdog-supervisor.log"
MIN_ALIVE=300   # 秒：低于此存活时长视为异常早退（正常跑满约 6 小时）

cd "$WS" || exit 1

# 单实例守卫：已有守护在跑就退出（按 cwd 核，不只看进程名）
me=$$
for p in $(pgrep -f "watchdog-supervisor.sh" 2>/dev/null); do
  [ "$p" = "$me" ] && continue
  cwd=$(lsof -p "$p" 2>/dev/null | awk '$4=="cwd"{print $NF}')
  if [ "$cwd" = "$WS" ]; then
    echo "$(date '+%F %T') supervisor 已在跑 pid=$p，本次退出" >> "$LOG"
    exit 0
  fi
done

echo "$(date '+%F %T') supervisor 启动 pid=$me" >> "$LOG"

while true; do
  start=$(date +%s)
  python3 "$WS/.team/watchdog.py" >> "$WS/.team/logs/watchdog-stdout.log" 2>&1
  rc=$?
  end=$(date +%s)
  alive=$((end - start))

  if [ "$alive" -lt "$MIN_ALIVE" ]; then
    kind="异常早退"
  else
    kind="正常退出(疑跑满 MAX_SAMPLES)"
  fi
  echo "$(date '+%F %T') watchdog 退出 rc=$rc 存活=${alive}s [$kind]，3s 后重启" >> "$LOG"

  sleep 3
done
