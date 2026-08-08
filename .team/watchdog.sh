#!/bin/bash
# 进度停滞看门狗（解释器护栏 #10 的落地）：
# 触发条件 = 连续 3 次采样（间隔 180s）全队 0 BUSY 且存在在途任务（有 intent 无 evidence）。
# 触发即退出（非零），由 leader 会话的后台任务机制唤醒 leader 处置；6h 心跳兜底退出。
cd "/Volumes/nvme/Projects/远程Agent安卓" || exit 2
consec=0
for i in $(seq 1 120); do
  busy=$(team-agent status --json 2>/dev/null | grep -c '"worker_state": "BUSY"')
  inflight=0; list=""
  for f in .team/evidence/*.intent.json; do
    [ -e "$f" ] || continue
    t=$(basename "$f" .intent.json)
    if [ ! -f ".team/evidence/$t.json" ]; then inflight=$((inflight+1)); list="$list $t"; fi
  done
  if [ "$busy" -eq 0 ] && [ "$inflight" -gt 0 ]; then consec=$((consec+1)); else consec=0; fi
  echo "$(date '+%H:%M:%S') busy=$busy inflight=$inflight($list) consec=$consec" >> .team/logs/watchdog.log
  if [ "$consec" -ge 3 ]; then
    echo "STALL_DETECTED busy=0 inflight=$inflight tasks:$list"
    exit 1
  fi
  sleep 180
done
echo "WATCHDOG_HEARTBEAT_6H inflight=$inflight"
exit 0
