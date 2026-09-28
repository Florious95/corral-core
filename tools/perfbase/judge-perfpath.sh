#!/bin/sh
# 判据：t.path——把「性能关键路径」显式钉住，并让「碰它必须复测」可机械执行。
# 由来：用户 2026-08-22「性能体验是最核心的体验，不能回退」。八个打点里六个在壳、两个沾核，
# 所以边界不能按仓划，得按**代码位置**划。
# 四态：0=通过；1=不通过；2=不可判。
set -u
ROOT=$(pwd)
L="$ROOT/docs/性能关键路径.md"
G="$ROOT/tools/perfbase/judge-perf-touched.sh"
[ -f "$L" ] || { echo "FAIL 缺清单 docs/性能关键路径.md"; exit 1; }
[ -x "$G" ] || { echo "FAIL 缺可执行判据 tools/perfbase/judge-perf-touched.sh"; exit 1; }

# ① 八个事件一个不许少
for ev in tap route_enter subscribe_sent geom_seed first_frame_recv snapshot_applied first_draw layout_settled; do
  grep -q -- "$ev" "$L" || { echo "FAIL 清单缺事件 ${ev}"; exit 1; }
done
# ② 每条必须给出真实存在的文件路径（⛔ 不许只写模块名糊弄）
MISS=0
# shellcheck disable=SC2013
for f in $(grep -oE '(app|server|tools|docs)/[A-Za-z0-9_./-]+\.(kt|go|sh)' "$L" | sort -u); do
  [ -e "$ROOT/$f" ] || { echo "FAIL 清单引用了不存在的文件：$f"; MISS=1; }
done
[ "$MISS" -eq 0 ] || exit 1
# ③ 必须标明每条在核还是在壳（边界的意义就在这）
grep -qE '核|壳' "$L" || { echo "FAIL 清单没标每条在「核」还是「壳」"; exit 1; }
# ④ 守门判据自身能跑：不带参数应给出 2（不可判），⛔ 不许默认放行
sh "$G" >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] || { echo "FAIL judge-perf-touched.sh 无参时应退出 2（不可判），实得 ${rc}"; exit 1; }
echo "PASS 性能关键路径已钉住且守门判据可跑"
exit 0
