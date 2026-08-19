#!/bin/bash
# leader 侧编排监控。停滞就退出（退出即为告警，宿主收到后台任务失败通知）。
# 🔴 与对方 skill 的 stall-alert.sh 的差别（见 findings F-10）：
#    判据阶段驱动器不写日志、席位本来就 idle，只看这两样必然误报。
#    这里再加两个活性来源：**驱动器有没有子进程** 和 **判据输出 .team/acclogs 有没有在长**。
set -u
WS="${WS:-/Volumes/nvme/Projects/远程Agent安卓}"
SOCK="${SOCK:-/tmp/tmux-501/ta-b7cc1c640ccf}"
INTERVAL="${INTERVAL:-60}"
NEED="${NEED:-3}"
FRESH="${FRESH:-300}"
cd "$WS" || exit 2
# 判活只用这一把尺（skill: tmux-node-activity）。⛔ 不许手写 ps/pgrep/find 判活。
NP=$(command -v nodeprobe || echo ~/.local/bin/nodeprobe)
strikes=0
while :; do
  now=$(date +%s)
  # ① 席位在工作数（排除 leader 自己）
  read -r work unk < <($NP -S "$SOCK" 2>/dev/null | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: print('ERR ERR'); sys.exit()
ns=[n for n in d['nodes'] if n['name']!='claude_code']
print(sum(1 for n in ns if n['state']=='working'), sum(1 for n in ns if n['state']=='unknown'))")
  # 🔴 unknown 绝不能当 idle：那是把「不知道」染成「确定空闲」，会让瞎掉的判据看着一切正常
  if [ "${unk:-0}" != "0" ] && [ "${unk:-0}" != "ERR" ]; then
    echo "[$(date -u +%H:%M:%SZ)] 🔴 有 $unk 个 unknown —— 符号表缺项或 nodeprobe 二进制旧了；⛔ 不许当 idle 用"; exit 1
  fi
  if [ "$work" = "ERR" ] || [ -z "$work" ]; then
    echo "[$(date -u +%H:%M:%SZ)] 🔴 判活尺子坏了（nodeprobe 不可判）——尺子坏和被测空闲同形，必须响"; exit 1
  fi
  # ② 驱动器存活 + 有没有子进程（判据在跑的硬信号）
  alive=0; busy=0; dead=""
  for f in .team/nodes/_driver/*.pid; do
    [ -e "$f" ] || continue
    p=$(cat "$f")
    if ps -p "$p" > /dev/null 2>&1; then
      alive=$((alive+1))
      [ -n "$(pgrep -P "$p")" ] && busy=$((busy+1))
    else
      dead="$dead $(basename "$f" .pid)"
    fi
  done
  # ③ 最近一次任何活动（驱动器日志 或 判据输出）
  last=$(find .team/ledgers -name '*-drive.log' -newermt "@$((now-FRESH))" 2>/dev/null | wc -l)
  last=$((last + $(find .team/acclogs -type f -newermt "@$((now-FRESH))" 2>/dev/null | wc -l)))
  if [ -n "$dead" ]; then
    echo "[$(date -u +%H:%M:%SZ)] 🔴 驱动器不见了:$dead —— 承诺常驻的进程自己消失过"; exit 1
  fi
  if [ "$work" -gt 0 ] || [ "$busy" -gt 0 ] || [ "$last" -gt 0 ]; then
    strikes=0
    echo "[$(date -u +%H:%M:%SZ)] ok 席位working=$work 驱动器有子进程=$busy 近${FRESH}s活动=$last"
  else
    strikes=$((strikes+1))
    echo "[$(date -u +%H:%M:%SZ)] 停滞嫌疑 $strikes/$NEED 席位working=0 驱动器${alive}个全无子进程 近${FRESH}s零活动"
    [ "$strikes" -ge "$NEED" ] && { echo "#ALERT 链路中断：没有节点在工作，且驱动器没有在跑判据"; exit 1; }
  fi
  sleep "$INTERVAL"
done
