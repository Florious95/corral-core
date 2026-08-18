#!/usr/bin/env bash
# mutate-and-check.sh — 对照席定点变异（ledger.level2-list-status.v1 / t.e2e）
#
# 自检：「如果被测对象是坏的，这条命令会不会仍然返回 0？会，就还不是判据。」
# 本脚本把 classifyPaneTitle 的表外符号从 unknown 改成回落 idle，
# 具名测试 TestL2UnknownGlyphStaysUnknown 必须变红；恢复后必须回绿。
# 两头都成立才 exit 0。trap 保证破坏自动恢复，跑完工作区干净。
#
# 用法：bash .team/nodes/l2-verify/mutate-and-check.sh
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SERVER_DIR="$ROOT/server"
TARGET="$SERVER_DIR/internal/api/level2.go"
TEST_MATCH="TestL2UnknownGlyphStaysUnknown"

MUTATION_SRC=$'	default:\n		return protocol.SessionStatusUnknown, r, false'
MUTATION_DST=$'	default:\n		return protocol.SessionStatusIdle, r, false // MUTATION[verify]: unknown glyph falls back to idle'

SNAP="$(mktemp -d)/level2.go.bak"

fail() { echo "MUTATE-CHECK FAIL: $1" >&2; exit 1; }

if [ ! -f "$TARGET" ]; then
  fail "被测文件不存在: $TARGET"
fi
cp -f "$TARGET" "$SNAP"
BASE_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
echo "baseline hash: $BASE_HASH"

restore() {
  cp -f "$SNAP" "$TARGET" 2>/dev/null || true
}
trap restore EXIT

# 基线必须先绿：否则「恢复后绿」分不清是判据误伤还是基线本来就红
if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 ); then
  fail "变异前具名测试 $TEST_MATCH 已红——基线不是绿，无法验判据"
fi
echo "GREEN baseline: $TEST_MATCH"

# ---- 1) 定向破坏：表外符号回落 idle ----
python3 - "$TARGET" "$MUTATION_SRC" "$MUTATION_DST" <<'PYEOF'
import sys
p, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding="utf-8").read()
if src not in s:
    sys.exit("mutation anchor not found")
if s.count(src) != 1:
    sys.exit(f"mutation anchor not unique: count={s.count(src)}")
open(p, "w", encoding="utf-8").write(s.replace(src, dst, 1))
PYEOF
if [ $? -ne 0 ]; then
  fail "变异锚点未找到或非唯一（实现可能已变，或之前变异未恢复）"
fi
echo "mutated: classifyPaneTitle default unknown → idle"

# ---- 2) 变异后必须红 ----
if ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 ); then
  fail "变异后具名测试 $TEST_MATCH 仍绿（exit 0）——判据对坏实现不响，判据无效"
fi
echo "RED confirmed: $TEST_MATCH 在变异后变红（exit 非 0）"

# ---- 3) 恢复 ----
restore
RESTORED_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
if [ "$RESTORED_HASH" != "$BASE_HASH" ]; then
  fail "恢复后哈希不匹配（$RESTORED_HASH != $BASE_HASH）——工作区未还原干净"
fi
echo "restored: $BASE_HASH"

# ---- 4) 恢复后必须绿 ----
if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 ); then
  fail "恢复后具名测试 $TEST_MATCH 仍红（exit 非 0）——判据误伤，判据无效"
fi
echo "GREEN confirmed: $TEST_MATCH 在恢复后回绿（exit 0）"

# ---- 5) 终态干净 ----
FINAL_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
if [ "$FINAL_HASH" != "$BASE_HASH" ]; then
  fail "终态哈希不匹配（$FINAL_HASH != $BASE_HASH）——破坏未自动恢复"
fi
rm -f "$SNAP"
trap - EXIT

echo "----"
echo "mutate-and-check: ALL PASS (变异红 + 恢复绿 + 工作区干净) — 判据有效"
exit 0
