#!/bin/sh
# 环境卫生闸：任何性能测量／回归测试**开跑之前**先过这一关。
#
# 为什么存在（2026-08-23 实撞，用户在真机上撞到的）：
#   服务端源码 0 提交、生产二进制 33 小时没换、用户手机 APP 也没换——**两端都没变**，
#   但「打开会话白屏很久 + 终端不断整屏重绘」。根因是**环境中间变量**：
#   `/private/tmp/tmux-501/` 里积了 161 个 tmux socket，其中 **140 个是死的**；
#   而 daemon 每 2 秒（list-interval 缺省）重扫一遍，做法是
#   **对每一个 socket 文件 fork 一个 `tmux list-panes`**（scan.go:152），
#   死 socket 要等连接失败才跳过 ⇒ 约 80 次 fork/秒 ⇒ daemon 空闲 CPU 均值 ~23%。
#   删掉那 140 个死 socket 之后，均值当场掉到 ~1.7%（降一个数量级），用户实测「变好了」。
#
# 教训（用户原话）：「一旦这种中间变量导致回退，就有可能极大地影响我们的测试以及回归测试，
#   就会极大地影响我们定位的成本。」
#   ⇒ 环境脏的时候测出来的**任何**性能数字都不可信：绿是假绿，红是假红。
#      所以这一关**不达标就判「不可判」，⛔ 不判通过也不判失败**。
#
# 用法：sh tools/perfbase/envcheck.sh            # 只报告
#       sh tools/perfbase/envcheck.sh --gate     # 不达标 exit 2（不可判）
#       sh tools/perfbase/envcheck.sh --clean    # 顺手清掉死 socket（⛔ 只删连不上的）
#
# 退出码：0=环境干净；2=环境脏（不可判）；⛔ 不返回 1——环境脏不是「测试失败」。
set -u
MODE="${1:-}"
DIR="/private/tmp/tmux-501"
MAX_DEAD=10          # 死 socket 上限；超过就认定环境脏
MAX_LOAD=12          # 与 20260822 地板负载区间 6.87-10.49 对齐留一点余量

dirty=0

# ① 死 tmux socket ——本次事故的直接肇因
dead=0; live=0
if [ -d "$DIR" ]; then
  for f in "$DIR"/*; do
    [ -S "$f" ] || continue
    if tmux -S "$f" list-sessions >/dev/null 2>&1; then live=$((live+1)); else dead=$((dead+1)); fi
  done
fi
echo "tmux socket：活 ${live}  死 ${dead}（阈值 ${MAX_DEAD}）"
if [ "$dead" -gt "$MAX_DEAD" ]; then
  dirty=1
  echo "  ⚠️ 死 socket 超阈值 —— daemon 每 2s 会为每一个 socket fork 一次 tmux，死的也要 fork 完才跳过"
  if [ "$MODE" = "--clean" ]; then
    n=0
    for f in "$DIR"/*; do
      [ -S "$f" ] || continue
      tmux -S "$f" list-sessions >/dev/null 2>&1 || { rm -f "$f" && n=$((n+1)); }
    done
    echo "  已清理死 socket ${n} 个（⛔ 只删连不上的，活着的一个没动）"
    dead=0; dirty=0
  fi
fi

# ② 机器负载 —— 换负载区间的性能数不能直接比较
l1=$(uptime | sed -E 's/.*load averages?: *([0-9.]+).*/\1/')
echo "load1：${l1}（阈值 ${MAX_LOAD}）"
case "$(echo "$l1 $MAX_LOAD" | awk '{print ($1>$2)}')" in
  1) dirty=1; echo "  ⚠️ 负载过高 —— 机器漂移会大过要检测的效应" ;;
esac

# ③ 残留模拟器 —— 单进程约 6 核 + 2.4GB
emu=$(ps -eo comm 2>/dev/null | grep -c 'qemu-system' || true)
echo "模拟器进程：${emu}"

# ④ 生产 daemon 空闲 CPU —— 静默经济红线（工程常识红线第 1 条）
pid=$(lsof -nP -iTCP:9900 -sTCP:LISTEN -t 2>/dev/null | head -1)
if [ -n "${pid:-}" ]; then
  cpu=$(top -l 5 -pid "$pid" -stats pid,cpu -n 1 2>/dev/null | grep "^${pid}" \
        | awk '{s+=$2; n++} END{if(n)printf "%.1f", s/n; else print "?"}')
  echo "生产 daemon(pid ${pid}) 空闲 CPU 均值：${cpu}%（红线：趋近 0）"
  case "$(echo "${cpu:-0} 5" | awk '{print ($1>$2)}')" in
    1) dirty=1; echo "  ⚠️ 空闲 CPU 偏高 —— 它会跟你的被测路径抢 CPU，白屏/掉帧都可能是它" ;;
  esac
else
  echo "生产 daemon：未在 :9900 监听"
fi

if [ "$dirty" -ne 0 ]; then
  echo "UNJUDGEABLE 环境不干净 —— 此刻测出来的性能数字不可信（绿是假绿，红是假红）"
  echo "     先清干净再测：sh tools/perfbase/envcheck.sh --clean"
  [ "$MODE" = "--gate" ] && exit 2
  exit 2
fi
echo "PASS 环境干净：死 socket ${dead}、load1 ${l1}、模拟器 ${emu}"
exit 0
