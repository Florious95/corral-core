#!/bin/bash
# ===== 判据命令: 状态检测「零字形差分/存在判据」红测驱动器 =====
#
# 用法(给对照席的唯一判据命令):
#   bash .team/nodes/state-oracle/run-probe.sh          # 对 live 代码跑探针
#   bash .team/nodes/state-oracle/run-probe.sh --source <dir>   # 对指定源跑(测试用)
#
# 语义:
#   1. 把被测 agentstate 决策源码(默认 live: server/internal/agentstate/)
#      装进判据模块 scratch/agentstate/,仅重写 import 路径。
#   2. 同步被测 protocol/state.go(默认 live: server/internal/protocol/state.go)。
#   3. go test ./probe/ -count=1 跑探针(零字形差分/存在判据)。
#   4. 恢复化石(判据模块默认载化石,保证红测载体不被破坏)。
#   5. 退出码 = go test 退出码,权威。
#
# 判据自检(方法论 §1): 如果被测对象是坏的——
#   - live 被改坏成「永远返回 idle」→ 探针 R1/R2 FAIL → go test 非 0 → 判据响。
#   - live 复活了字形白名单(认 ⠋/✳/◐ 判 working)→ 探针在化石上红、G1/G2 可能绿但
#     R1/R2 仍红(◐/块在动它认不出)→ 判据响。
#   - 若脚本本身坏的(装错了源)→ go test 编译失败或红 → 判据响(基线就是非 0)。
# 定点变异: 把被测 agentstate 决策源码改坏一处(如删掉 diff、永远返回 idle),重跑本命令
#          必须转非 0 —— 不转,判据无效(报告 refutes)。
#
# 硬边界: 本脚本只读被测源(默认 live),写到 scratch/;不碰 live 文件 → 顾问席不失去判权。
# 不 commit / 不 push / 不碰生产 daemon 与用户 tmux。
set -u
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SCRATCH="$ROOT/.team/nodes/state-oracle/scratch"
PROBE_DIR="$SCRATCH/probe"

LIVE_AGENTSTATE="$ROOT/server/internal/agentstate"
LIVE_PROTOCOL="$ROOT/server/internal/protocol/state.go"
FOSSIL_AGENTSTATE="$ROOT/docs/archive/agentstate-round4"

SOURCE_DIR="${2:-$LIVE_AGENTSTATE}"
# 规范化被测源为绝对路径(脚本后面 cd 进 SCRATCH,相对路径会失效)
case "$SOURCE_DIR" in
  /*) : ;;
  *)  SOURCE_DIR="$(cd "$ROOT/$SOURCE_DIR" 2>/dev/null && pwd)" ;;
esac

cd "$SCRATCH"

# 0) 校验被测源存在
if [ ! -d "$SOURCE_DIR" ]; then
  echo "PROBE ERROR: 被测源不存在: $SOURCE_DIR" >&2
  exit 2
fi

# 1) 备份化石决策文件(不覆盖原始 archive)
FOSSIL_BAK="$SCRATCH/.fossil-bak"
mkdir -p "$FOSSIL_BAK"
cp -f "$SCRATCH/agentstate/"*.go "$FOSSIL_BAK/"

# 2) 装被测源进判据模块(决策逻辑零改动,只重写 import 路径)
rm -f "$SCRATCH/agentstate/"*.go
for f in "$SOURCE_DIR"/*.go; do
  [ -e "$f" ] || continue
  base="$(basename "$f")"
  # 排除测试文件、归档的 api 化石测试(它们 package api,非 agentstate 决策源码)、
  # 与被测源里混入的 protocol 副本(archive 含测试; 仅 fake-live 混协议副本)
  case "$base" in *_test.go|api-*-fossil*.go|state_copied.go) continue ;; esac
  cp -f "$f" "$SCRATCH/agentstate/$base"
  sed -i '' 's#"github.com/agentmirror/agentmirror/internal/protocol"#"stateoracleprobe/protocol"#g' "$SCRATCH/agentstate/$base"
done

# 3) 同步被测 protocol/state.go(若有独立 protocol 源;fake-live 用自带副本则跳过)
if [ -n "${3:-}" ] && [ -f "$3" ]; then
  cp -f "$3" "$SCRATCH/protocol/state.go"
fi

# 4) 跑探针(-count=1 防缓存绿)
echo "PROBE TARGET SOURCE: $SOURCE_DIR"
go test ./probe/ -count=1 -v
TEST_EXIT=$?

# 5) 恢复化石(判据模块默认载化石)
rm -f "$SCRATCH/agentstate/"*.go
cp -f "$FOSSIL_BAK/"*.go "$SCRATCH/agentstate/"
rm -rf "$FOSSIL_BAK"

echo "PROBE EXIT=$TEST_EXIT"
exit $TEST_EXIT
