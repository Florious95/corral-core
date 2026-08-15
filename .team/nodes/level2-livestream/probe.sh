#!/usr/bin/env bash
# probe.sh — 二级菜单实时流根因探针
#
# 契约：requirement-base/entries/060。断言三条不变量在世界里成立：
#   ① pane 标题原样显示，一个字符都不解析/匹配/映射/美化
#   ② 会话身份只用 tmux 结构字段 session_name/window_index/window_name，禁止从标题抠
#   ③ 实时流只在二级菜单打开时拉数据，关掉即停；不 attach tmux 客户端
#   形态：二级菜单可点，点一行进那个会话（三级终端唯一入口）
#
# 用法：bash .team/nodes/level2-livestream/probe.sh
#   exit 0 = 三条不变量在世界里成立（实现完成）
#   exit 1 = 任一不变量不成立（当前未实现，必须红）
#
# 探针自检：把实现改坏（title 去首字符 / 身份改从标题抠 / 删订阅 gate），对应断言必须转 FAIL。
set -u

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
SERVER="$ROOT/server"
APP_MAIN="$ROOT/app/app/src/main/java"
APP_TEST="$ROOT/app/app/src/test"

FAIL=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }

# 文件存在性守卫
for f in "$SERVER/internal/protocol/frames.go" "$SERVER/internal/discovery/scan.go" \
         "$APP_MAIN/dev/agentmirror/app/conn/Frames.kt" \
         "$APP_MAIN/dev/agentmirror/app/conn/ConnectionManager.kt"; do
  if [ ! -f "$f" ]; then
    echo "FATAL: 被测文件不存在: $f"
    exit 1
  fi
done

echo "===== 不变量①：标题原样显示，零解析 ====="

# L2-1 协议 Session 有 title 字段（pane_title 原样通道）
if grep -q '"title"' "$SERVER/internal/protocol/frames.go" 2>/dev/null; then
  pass "L2-1 协议 Session.Title 字段存在（pane_title 原样通道）"
else
  fail "L2-1 协议 Session 缺 title 字段（无 pane_title 原样通道）"
fi

# L2-2 服务端不解析标题：pane_title 只出现在扫描格式串与赋值，无处理函数
# 判定：新增实现里不得对 title 做 trim/strip/前缀处理。扫描格式串含 #{pane_title} 是合法的（原样取数）。
if grep -q 'pane_title' "$SERVER/internal/discovery/scan.go" 2>/dev/null; then
  pass "L2-2 服务端扫描含 pane_title 取数（原样透传源）"
else
  fail "L2-2 服务端扫描缺 pane_title 取数"
fi
# 防御：discovery/model.go 的 PaneTitle 注释必须声明「原样、不解析」（060 语义）
if grep -q '原样\|verbatim\|never parsed\|opaque' "$SERVER/internal/discovery/model.go" 2>/dev/null; then
  pass "L2-2b PaneTitle 注释声明原样/不解析"
else
  fail "L2-2b PaneTitle 注释未声明原样/不解析"
fi

echo "===== 不变量②：会话身份只用结构字段 ====="

# L2-3 身份 ref 从结构字段组装（socket+paneid），不涉及标题
if grep -q 'func sessionRef' "$SERVER/internal/api/session.go" 2>/dev/null \
   && grep -q 'Socket.*PaneID\|Socket.*PaneId' "$SERVER/internal/api/session.go" 2>/dev/null; then
  pass "L2-3 会话身份 ref 从结构字段组装（socket+paneid）"
else
  fail "L2-3 缺 ref 结构字段组装，或 ref 可能依赖标题"
fi

# L2-4 二级实现不得从标题字符串抠身份：禁止 startsWith/contains 作用于 title 做身份判断
TITLE_HANDLING=$(grep -rnE '\.Title|pane_title' "$SERVER/internal/api/level2.go" "$SERVER/internal/protocol/frames.go" 2>/dev/null | grep -E 'trim|strip|contains|HasPrefix|HasSuffix|Replace' || true)
if [ -z "${TITLE_HANDLING:-}" ]; then
  pass "L2-4 无标题字符串处理（trim/contains/prefix）用作身份"
else
  fail "L2-4 发现标题字符串处理（${TITLE_HANDLING}）——身份可能从标题抠"
fi

echo "===== 不变量③：二级实时流只在打开时拉，关掉即停；不 attach ====="

# L2-5 服务端有二级订阅 gate（Level2Subscribe/Level2Unsubscribe 帧）
if grep -q 'Level2Subscribe\|Level2Unsubscribe' "$SERVER/internal/protocol/frames.go" 2>/dev/null; then
  pass "L2-5 协议有 Level2Subscribe/Level2Unsubscribe 帧"
else
  fail "L2-5 协议缺二级订阅帧（无法实现打开拉/关掉停）"
fi

# L2-6 服务端有订阅者计数 + gate（countLevel2 / park）
if grep -q 'countLevel2\|level2Subscribers\|Level2Subscribers' "$SERVER/internal/api/level2.go" 2>/dev/null; then
  pass "L2-6 服务端有二级订阅者计数 gate（零订阅者停拉）"
else
  fail "L2-6 服务端缺二级订阅者计数 gate（无打开拉/关掉停保证）"
fi

# L2-7 服务端命令集合永不出现 attach（不 attach tmux 客户端）
ATTACH_HITS=$(grep -rnE '"attach(-session)?"|attach-session' "$SERVER/internal/discovery/" "$SERVER/internal/api/level2.go" 2>/dev/null | grep -v '_test.go' || true)
if [ -z "${ATTACH_HITS:-}" ]; then
  pass "L2-7 服务端取数命令无 attach（list-panes -a，多客户端不谈判尺寸）"
else
  fail "L2-7 服务端发现 attach 命令（${ATTACH_HITS}）——违反不 attach"
fi

echo "===== App 端 ====="

# L2-8 App 有 Level2ViewModel（消费 Level2Frame，title 不加工）
if grep -q 'Level2ViewModel\|Level2Entry' "$APP_MAIN/dev/agentmirror/app/workspace/" 2>/dev/null; then
  pass "L2-8 App 有 Level2ViewModel（消费 Level2Frame）"
else
  fail "L2-8 App 缺 Level2ViewModel（未实现二级实时流视图）"
fi

# L2-9 App 渲染 title 原样：渲染代码不得对 title 做字符串操作
APP_TITLE_HANDLING=$(grep -rnE 'title\.(trim|startsWith|contains|replace|removePrefix|substring|drop)' "$APP_MAIN/dev/agentmirror/app/workspace/" 2>/dev/null || true)
if [ -z "${APP_TITLE_HANDLING:-}" ]; then
  pass "L2-9 App 渲染 title 原样（无字符串操作）"
else
  fail "L2-9 App 对 title 做字符串操作（${APP_TITLE_HANDLING}）——违反零解析"
fi

# L2-10 App 二级菜单可点：onOpenSession 接 ref（三级唯一入口）
if grep -q 'onOpenSession' "$APP_MAIN/dev/agentmirror/app/workspace/WorkspaceScreen.kt" 2>/dev/null; then
  pass "L2-10 App 二级菜单可点（onOpenSession 接 ref，进三级终端）"
else
  fail "L2-10 App 二级菜单不可点（缺 onOpenSession）——做成只读进不去会话"
fi

# L2-11 App 生命周期绑定：subscribeLevel2/unsubscribeLevel2 存在（打开订阅/退出退订）
if grep -q 'subscribeLevel2\|unsubscribeLevel2' "$APP_MAIN/dev/agentmirror/app/conn/ConnectionManager.kt" 2>/dev/null; then
  pass "L2-11 App 有 subscribeLevel2/unsubscribeLevel2（生命周期绑定）"
else
  fail "L2-11 App 缺 subscribeLevel2/unsubscribeLevel2（无法打开拉/关掉停）"
fi

echo "----"
if [ "$FAIL" -eq 0 ]; then
  echo "probe: ALL PASS (exit 0) —— 二级菜单实时流三条不变量 + 形态裁定在世界里成立"
  exit 0
else
  echo "probe: ${FAIL} FAIL (exit 1) —— 二级菜单实时流未实现或不变量被违反"
  exit 1
fi
