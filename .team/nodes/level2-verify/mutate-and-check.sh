#!/usr/bin/env bash
# mutate-and-check.sh — 对照席定点变异校验器（t.verify / level2-livestream）
#
# 判据自检："如果被测对象是坏的，这条命令会不会仍然返回 0？会，就还不是判据。"
# 本脚本把被测对象（level2 实时流 title 透传）改坏一处，验证具名测试变红；
# 恢复后验证回绿。两头都成立才 exit 0。
#
# 变异点：server/internal/api/level2.go:120  `title: p.PaneTitle`
#   → `title: p.PaneTitle[1:]`（去掉首字节，破坏「标题原样透传，一个字符都不解析」）
# 具名测试：TestLevel2TitleVerbatim（断言 ◐/✳ 前缀逐字节原样）
#
# 用法：bash .team/nodes/level2-verify/mutate-and-check.sh
#   exit 0 = 判据有效（变异红 + 恢复绿）
#   exit 1 = 判据无效（变异未红，或恢复未绿，或工作区残留）
set -u

# 定位仓库根（本脚本在 .team/nodes/level2-verify/ → 上溯 4 级）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SERVER_DIR="$ROOT/server"
TARGET="$SERVER_DIR/internal/api/level2.go"
TEST_MATCH="TestLevel2TitleVerbatim"
MUTATION_SRC='title: p.PaneTitle, // verbatim, zero parsing (requirement 060)'
MUTATION_DST='title: p.PaneTitle[1:], // MUTATION[verify]: 标题去首字节，破坏原样透传'

# 用只含 ASCII 的临时目录放快照（避免与仓库中文路径的编码坑）
SNAP="$(mktemp -d)/level2.go.bak"
PROBE_DIR="$SERVER_DIR"

fail() { echo "MUTATE-CHECK FAIL: $1" >&2; exit 1; }

# 快照原文件
if [ ! -f "$TARGET" ]; then
  fail "被测文件不存在: $TARGET"
fi
cp -f "$TARGET" "$SNAP"
BASE_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
echo "baseline hash: $BASE_HASH"

# 异常时也恢复（trap 保证工作区干净）
restore() {
  cp -f "$SNAP" "$TARGET" 2>/dev/null || true
}
trap restore EXIT

# ---- 1) 变异：去掉 title 首字节（破坏原样透传） ----
if grep -Fq "$MUTATION_SRC" "$TARGET"; then
  # macOS BSD sed 需要 -i ''；用 python 更稳（避免 sed 转义坑）
  python3 - "$TARGET" "$MUTATION_SRC" "$MUTATION_DST" <<'PYEOF'
import sys
p, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding='utf-8').read()
if src not in s:
    sys.exit(f"mutation anchor not found in {p}")
open(p, 'w', encoding='utf-8').write(s.replace(src, dst, 1))
PYEOF
else
  fail "变异锚点未找到（实现可能已变，或之前变异未恢复）"
fi
echo "mutated: title 透传改为去掉首字节"

# ---- 2) 变异后必须红（判据响） ----
if ( cd "$PROBE_DIR" && go test ./internal/api/ -run "$TEST_MATCH" -count=1 >/dev/null 2>&1 ); then
  fail "变异后具名测试 $TEST_MATCH 仍绿（exit 0）——判据对坏实现不响，判据无效"
fi
echo "RED confirmed: $TEST_MATCH 在变异后变红（exit 非 0）"

# ---- 3) 恢复原样 ----
restore
RESTORED_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
if [ "$RESTORED_HASH" != "$BASE_HASH" ]; then
  fail "恢复后哈希不匹配（$RESTORED_HASH != $BASE_HASH）——工作区未还原干净"
fi
echo "restored: 文件已还原到基线哈希 $BASE_HASH"

# ---- 4) 恢复后必须绿（判据不误伤） ----
if ! ( cd "$PROBE_DIR" && go test ./internal/api/ -run "$TEST_MATCH" -count=1 >/dev/null 2>&1 ); then
  fail "恢复后具名测试 $TEST_MATCH 仍红（exit 非 0）——判据误伤，判据无效"
fi
echo "GREEN confirmed: $TEST_MATCH 在恢复后回绿（exit 0）"

# ---- 5) 终态校验：工作区干净（与基线哈希一致） ----
FINAL_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
if [ "$FINAL_HASH" != "$BASE_HASH" ]; then
  fail "终态哈希不匹配（$FINAL_HASH != $BASE_HASH）——破坏未自动恢复，工作区不干净"
fi
rm -f "$SNAP"
trap - EXIT

echo "----"
echo "mutate-and-check: ALL PASS (变异红 + 恢复绿 + 工作区干净) — 判据有效"
exit 0
