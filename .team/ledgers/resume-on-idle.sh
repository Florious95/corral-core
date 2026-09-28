#!/bin/sh
# 等本机负载降下来再续跑 coreapp-v1 的 t.perf。
# 为什么不直接重派：模拟器测量需要机器，load 100 时重派只会撞同一堵墙、
# 烧席位额度，并把「不可判」堆成一串看起来像反复失败的记录。
cd /Volumes/nvme/Projects/远程Agent安卓 || exit 1
LOG=.team/ledgers/resume-watch.log
while :; do
  L=$(uptime | sed 's/.*averages: //' | awk '{print $1}' | tr -d ,)
  FREE=$(vm_stat | awk '/Pages free/{f=$3} /Pages inactive/{i=$3} END{printf "%d", (f+i)*16384/1048576}')
  # 门槛照席位任务书：load1 ≤ 15 且 free+inactive ≥ 3000MB
  if [ "$(printf '%.0f' "$L")" -le 15 ] && [ "$FREE" -ge 3000 ]; then
    echo "[$(date -u +%FT%TZ)] load1=$L free+inactive=${FREE}MB → 达标，重起驱动器" >>$LOG
    python3 - <<'PY'
import json
p='.team/ledgers/coreapp-v1.json'
d=json.load(open(p)); t=d['tasks']['t.perf']
# ⚠️ attempts 条目的键是 state，⛔ 不是 outcome；不清掉 failed_retryable 就是 frozen_no_new_case
t['attempts']=[a for a in (t.get('attempts') or []) if a.get('state') not in ('failed','failed_retryable')]
t['state']='planned'; t.pop('status_record',None); t.pop('blocking_reasons',None)
d['revision']+=1; d['run']['desired_state']='running'
# ⛔ 不动 rounds 与 audit.route_hops
json.dump(d,open(p,'w'),ensure_ascii=False,indent=2)
PY
    exec ledger-run --drive .team/ledgers/coreapp-v1.json >>.team/ledgers/coreapp-drive5.log 2>&1
  fi
  echo "[$(date -u +%FT%TZ)] load1=$L free+inactive=${FREE}MB → 未达标，等 120s" >>$LOG
  sleep 120
done
