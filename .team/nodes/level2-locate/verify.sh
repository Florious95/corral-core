#!/usr/bin/env bash
# verify.sh — 二级菜单旧模型 + 状态判定 拔除干净度机械判据
#
# 契约：requirement-base/entries/060（二级菜单改实时流，状态判定连根拔起）。
# 用法：bash .team/nodes/level2-locate/verify.sh
#   exit 0 = 所有该消失的符号一个都搜不到（拔干净）
#   exit 1 = 仍有旧模型符号残留（未拔干净 / 被测对象坏）
# 现在（未拔）跑必须失败（exit 1）——这是拔干净的机械判据。
#
# 判据自检：若把任一「该消失的符号」放回源码，本命令必须转非 0。
# 保留边界：本脚本不搜 ConnectionState（连接态，保留）、manager.state()（连接状态，
# 保留）、帧编解码结构（保留）、一级菜单刷新模型（保留）。它只搜「状态判定旧模型 +
# 二级菜单列表模型」专属符号。
set -u

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"

SERVER_DIR="$ROOT/server"
APP_MAIN="$ROOT/app/app/src/main/java"
APP_TEST="$ROOT/app/app/src/test"

FAIL=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }

# check_absent label dirs patterns...
# 在给定目录的 .go/.kt 里搜 pattern，搜到即 FAIL。
check_absent() {
  local label="$1"; local dir="$2"; local pat="$3"
  if grep -rnE -- "$pat" "$dir" --include='*.go' --include='*.kt' 2>/dev/null | grep -v '^Binary file' >/dev/null; then
    local hits
    hits=$(grep -rnE -- "$pat" "$dir" --include='*.go' --include='*.kt' 2>/dev/null | grep -v '^Binary file' | head -3 | tr '\n' ' ')
    fail "${label}（残留：${hits}）"
  else
    pass "${label}"
  fi
}

echo "===== server 端：状态判定决策层 ====="

# agentstate 包（整包删）
if [ -d "$SERVER_DIR/internal/agentstate" ]; then
  fail "server/internal/agentstate/ 目录仍存在"
else
  pass "server/internal/agentstate/ 目录已删"
fi

# protocol.AgentState 类型 + 常量
check_absent "protocol.AgentState 类型/常量（state.go）" "$SERVER_DIR/internal/protocol" "AgentState|StateWorking|StateIdle|StateBlocked|StateUnknown"
# api 层 StateProvider 链路
check_absent "api StateProvider 链路（state.go/state_wiring.go/options.go/server.go）" "$SERVER_DIR/internal/api" "StateProvider|wiredStateProvider|unknownState|NewStateProvider"
# 聚合规则函数
check_absent "listing 聚合规则（statePriority/aggregateState/wsAggregate）" "$SERVER_DIR/internal/api" "statePriority|wsAggregate|aggregateState"
# requireState / ErrInvalidState
check_absent "protocol requireState/ErrInvalidState（validate.go/errors.go）" "$SERVER_DIR/internal/protocol" "requireState|ErrInvalidState"
# cmd 装配
check_absent "cmd/agentmirrord 装配（NewStateProvider）" "$SERVER_DIR/cmd" "NewStateProvider|stateProvider"
# 一级菜单保留物不应被误伤：检查保留关键函数仍在（防御性，防拔过头）
echo "===== server 端：保留边界防御（必须在，别拔过头） ====="
if grep -q "func (s \*Server) listingLoop\|func.*listingLoop" "$SERVER_DIR/internal/api/server.go" 2>/dev/null; then
  pass "【防御】一级菜单刷新模型保留（listingLoop）"
else
  fail "【防御】一级菜单刷新模型丢失（listingLoop 误伤！）"
fi
if grep -q "func.*toSession\|func.*buildSnapshot" "$SERVER_DIR/internal/api/listing.go" 2>/dev/null; then
  pass "【防御】listing 两级结构保留（toSession/buildSnapshot）"
else
  fail "【防御】listing 两级结构丢失（toSession/buildSnapshot 误伤！）"
fi

echo "===== app 端：状态徽章 / AgentState / StateWatcher / 二级列表 ====="

check_absent "App AgentState 枚举（conn/AgentState.kt）" "$APP_MAIN" "AgentState"
check_absent "App StateBadge/StateBadgeStyle（workspace/）" "$APP_MAIN" "StateBadge"
check_absent "App StateWatcher（service/）" "$APP_MAIN" "StateWatcher"
check_absent "App 状态色板 StateTone/StateTones（ui/theme/）" "$APP_MAIN" "StateTone"
check_absent "App SessionUi（workspace/ 二级列表模型）" "$APP_MAIN" "SessionUi"
check_absent "App aggregate_state JSON 键（conn/Frames.kt）" "$APP_MAIN" "aggregate_state"
check_absent "App aggregateState 字段（workspace/conn 模型）" "$APP_MAIN" "aggregateState"

echo "===== app 端：保留边界防御（必须在，别拔过头） ====="

# 防御：连接态 ConnectionState 必须保留（不是会话状态）
if grep -q "enum class ConnectionState" "$APP_MAIN/dev/agentmirror/app/conn/ConnectionManager.kt" 2>/dev/null; then
  pass "【防御】ConnectionState 连接态保留"
else
  fail "【防御】ConnectionState 连接态丢失（误伤保留物！）"
fi

# 防御：一级菜单刷新模型（WorkspaceViewModel.refresh / WorkspaceScreen.PullToRefreshBox）必须保留
if grep -q "fun refresh\|refreshing" "$APP_MAIN/dev/agentmirror/app/workspace/WorkspaceViewModel.kt" 2>/dev/null; then
  pass "【防御】一级菜单刷新模型保留（refresh/refreshing）"
else
  fail "【防御】一级菜单刷新模型丢失（误伤保留物！）"
fi

# 防御：帧编解码 ListingFrame/ListDeltaFrame 必须保留
if grep -q "data class ListingFrame\|data class ListDeltaFrame" "$APP_MAIN/dev/agentmirror/app/conn/Frames.kt" 2>/dev/null; then
  pass "【防御】帧编解码 ListingFrame/ListDeltaFrame 保留"
else
  fail "【防御】帧编解码 ListingFrame/ListDeltaFrame 丢失（误伤保留物！）"
fi

# 防御：三级终端流 SessionViewModel 必须保留
if [ -f "$APP_MAIN/dev/agentmirror/app/session/SessionViewModel.kt" ]; then
  pass "【防御】三级终端流 SessionViewModel 保留"
else
  fail "【防御】三级终端流 SessionViewModel 丢失（误伤保留物！）"
fi

echo "----"
if [ "$FAIL" -eq 0 ]; then
  echo "verify: ALL PASS (exit 0) —— 二级菜单旧模型 + 状态判定已拔干净"
  exit 0
else
  echo "verify: ${FAIL} FAIL (exit 1) —— 仍有旧模型符号残留，未拔干净"
  exit 1
fi
