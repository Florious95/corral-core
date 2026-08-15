#!/usr/bin/env bash
# 刷新模型判据探针（run-probe）
#
# 被测对象：app/app/src/main/java/dev/agentmirror/app/workspace/ 下的刷新触发链路。
# 判据 = 三条结构不变量，全部通过才 exit 0：
#   R1 进入一级/二级 必须存在触发 ConnectionManager.list() 的调用点（进入即刷）
#   R2 下拉手势必须存在绑定 list() 的 onRefresh（下拉手动刷）
#   R3 workspace 包内不存在周期性自动刷新结构（零周期自动刷新 = 禁令）
#
# 退出码：全 PASS → 0；任一 FAIL → 1。
# 用法：bash .team/nodes/refresh-oracle/run-probe.sh
#   返回 0 = 刷新模型判据满足；返回 1 = 被测对象坏（红）。
# 判据自检：把被测对象改坏（删 list 触发 / 加 while(true) 周期刷新），本命令必须转非 0。
set -u

# 以本脚本所在目录定位仓库根（.team/nodes/refresh-oracle → 仓库根上溯 3 级）。
ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
WS_DIR="$ROOT/app/app/src/main/java/dev/agentmirror/app/workspace"

if [ ! -d "$WS_DIR" ]; then
  echo "FATAL: workspace 源码目录不存在: $WS_DIR"
  exit 1
fi

FAIL=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }

# ---- R1 进入即刷：存在触发全量列表刷新的调用点 ----
# 语义：每次导航到一级（工作区列表）或二级（会话列表），都要发一次 ConnectionManager.list()。
# 机械判据：workspace 包源码里必须出现对 list() 的调用（manager.list() / list() 直调）。
if grep -rnE '(^|[^A-Za-z])list\(\)' "$WS_DIR" --include='*.kt' >/dev/null 2>&1; then
  pass "R1 存在列表刷新调用点（进入即刷链路在）"
else
  fail "R1 workspace 包无任何 list() 调用点（进入一级/二级均不会刷新）"
fi

# ---- R2 下拉手动刷：存在绑定 list() 的下拉刷新回调 ----
# 语义：用户手指下滑要触发一次刷新。
# 机械判据：workspace 包出现 Compose 下拉刷新 API（pullRefresh / PullToRefresh / onRefresh），
# 且其回调体内有 list() 调用。为可判，要求同目录下 onRefresh/pull 语义与 list() 共存。
if grep -rnE 'pullRefresh|PullToRefresh|onRefresh' "$WS_DIR" --include='*.kt' >/dev/null 2>&1 \
   && grep -rnE '(^|[^A-Za-z])list\(\)' "$WS_DIR" --include='*.kt' >/dev/null 2>&1; then
  pass "R2 下拉刷新入口存在且与 list() 共存"
else
  fail "R2 缺下拉刷新（pullRefresh/onRefresh 与 list() 未共存，或两者皆无）"
fi

# ---- R3 零周期性自动刷新：workspace 包禁止周期拉取结构 ----
# 语义：用户裁定「没有周期性自动刷新」，这条是禁令不是频率低一点。
# 机械判据：workspace 包内不允许出现 while(true) / 周期定时器 / 协程固定延迟重复拉列表。
# 注意：会话页（session 包）有自己的本地视口泵 while(true)（syncFromPresenter），那不是
# 列表刷新、不适用本判据；本判据只扫 workspace 包。
if grep -rnE 'while[[:space:]]*\([[:space:]]*true|scheduleAtFixedRate|scheduleWithFixedDelay|Timer\s*\(|CoroutineScope[^/]*Launch.*while' "$WS_DIR" --include='*.kt' >/dev/null 2>&1; then
  fail "R3 workspace 包存在周期性自动刷新结构（违反零周期禁令）"
else
  pass "R3 workspace 包零周期性自动刷新"
fi

echo "----"
if [ "$FAIL" -eq 0 ]; then
  echo "probe: ALL PASS (exit 0)"
  exit 0
else
  echo "probe: ${FAIL} FAIL (exit 1) —— 刷新模型未满足或被测对象坏"
  exit 1
fi
