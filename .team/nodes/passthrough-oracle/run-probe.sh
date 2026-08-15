#!/usr/bin/env bash
# 直通输入判据探针（run-probe）
#
# 被测对象：①直通输入的三条语义（用户 2026-08-15 裁定，059 契约条目）：
#   S1 每个按键单独直通到 Agent CLI 输入框（不再 App 本地累积整条草稿）
#   S2 虚拟键盘删除键同样直通（backspace 直通，不经 App 本地消费）
#   S3 发送键只提交、不再整条注入（草稿态归 CLI 输入框，发送键是唯一提交动作）
#
# 判据 = 服务端注入原语 + App 输入链路两端各三条结构不变量。当前未实现树必须红（exit 1）。
# 实现完成后必须全 PASS（exit 0）。对照席定点变异（把任一语义退回旧行为）必须转红。
#
# 退出码：全 PASS → 0；任一 FAIL → 1。
# 用法：bash .team/nodes/passthrough-oracle/run-probe.sh
set -u

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
BRIDGE="$ROOT/server/internal/bridge/bridge.go"
KEYS="$ROOT/server/internal/protocol/keys.go"
SESSION_VM="$ROOT/app/app/src/main/java/dev/agentmirror/app/session/SessionViewModel.kt"
SESSION_SCREEN="$ROOT/app/app/src/main/java/dev/agentmirror/app/session/SessionScreen.kt"

for f in "$BRIDGE" "$KEYS" "$SESSION_VM" "$SESSION_SCREEN"; do
  if [ ! -f "$f" ]; then
    echo "FATAL: 被测文件不存在: $f"
    exit 1
  fi
done

FAIL=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }

# ---- S1 每键直通：服务端存在"逐键注入且不回车"的原语 ----
# 语义：每个按键单独 send-keys 到 CLI 输入框，不追加 Enter（草稿态停留在 CLI 输入框）。
# 机械判据：bridge.go 里存在一个函数，内含 send-keys -l（逐键字面量注入）但不在本函数
# 内追加 Enter。旧范式 Inject() 是"-l 注入 + 同函数 Enter"一体——把注入与提交绑成一个动作，
# 正是被取代的一次性注入。实现后必须把"逐键打字"与"提交"拆成两个原语。
# 判定：用 awk 按函数块统计——函数体内出现 send-keys 的 -l 参数、且该函数无 "Enter" 提交。
S1=$(awk '
  /^func / { name=$0; in_func=1; has_literal=0; has_enter=0; next }
  in_func && /"-l"/ { has_literal=1 }
  in_func && /"Enter"/ { has_enter=1 }
  in_func && /^}/ { if (has_literal==1 && has_enter==0) print name; in_func=0 }
' "$BRIDGE")
if [ -n "${S1:-}" ]; then
  pass "S1 服务端存在逐键注入不回车原语（${S1}）"
else
  fail "S1 服务端缺逐键注入不回车原语（唯一 send-keys -l 路径仍与 Enter 绑定）"
fi

# ---- S2 删除键直通：服务端协议/bridge 存在 backspace 映射 ----
# 语义：虚拟键盘删除键同样直通 CLI。旧实现删除键归 App 本地消费（本地输入框删字符），
# 不会送到远端。实现后必须有 wire backspace 键名 → tmux BSpace/Backspace 的映射。
# 机械判据：protocol/keys.go 或 bridge.go 出现 backspace 命名键（协议常量或 namedKeys 映射）。
S2=$(grep -rniE 'backspace|"bspace"|"bksp"' "$KEYS" "$BRIDGE" || true)
if [ -n "${S2:-}" ]; then
  pass "S2 服务端存在删除键 backspace 映射"
else
  fail "S2 服务端缺删除键 backspace 映射（删除键仍本地消费，未直通）"
fi

# ---- S3 发送只提交：App 发送键不再整条注入草稿文本 ----
# 语义：发送键只提交（CLI 输入框里已经逐键直通的草稿），不再把整条 text 一次性注入。
# 旧实现 sendDraft() 取 textFieldValue.text 整条 sendInput(ref, text) —— 正是被取代的注入。
# 机械判据：SessionViewModel 发送路径不再把草稿文本整条交给 sendInput。
S3=$(grep -nE 'sendInput\(ref, text|textFieldValue\.text' "$SESSION_VM" || true)
if [ -z "${S3:-}" ]; then
  pass "S3 App 发送路径不再整条注入草稿文本"
else
  fail "S3 App 发送路径仍整条注入草稿文本（${S3}）"
fi

# ---- S4 每键直通：App 键盘输入不再累积本地草稿 ----
# 语义：键盘按键直接上行到服务端，不再写进 App 本地 textFieldValue 当整条草稿累积。
# 旧实现 InputBar onValueChange = { viewModel.textFieldValue = it } —— 本地累积。
# 机械判据：SessionScreen 里不再把键盘输入写进本地草稿（onValueChange 不再更新 textFieldValue）。
S4=$(grep -nE 'textFieldValue\s*=\s*it|textFieldValue\s*=\s*value|onValueChange.*textFieldValue' "$SESSION_SCREEN" || true)
if [ -z "${S4:-}" ]; then
  pass "S4 App 键盘输入不再累积本地草稿"
else
  fail "S4 App 键盘输入仍累积本地草稿（${S4}）"
fi

echo "----"
if [ "$FAIL" -eq 0 ]; then
  echo "probe: ALL PASS (exit 0) —— 直通输入三条语义已满足"
  exit 0
else
  echo "probe: ${FAIL} FAIL (exit 1) —— 直通输入未实现或被测对象坏"
  exit 1
fi
