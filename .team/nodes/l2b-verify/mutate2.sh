#!/usr/bin/env bash
# mutate2.sh — 对照席定点变异（ledger.l2-tristate.v1 / t.verify）
#
# 自检：「如果被测对象是坏的，这条命令会不会仍然返回 0？会，就还不是判据。」
# 破坏：classifyFallback 把「无前导符号（字母/数字）」从 idle 改回 unknown。
# 具名测试 TestL2NoGlyphIsIdle 必须变红；恢复后必须回绿。
# 两头成立才 exit 0。trap 保证破坏自动恢复。
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SERVER_DIR="$ROOT/server"
TARGET="$SERVER_DIR/internal/api/detect.go"
TEST_MATCH="TestL2NoGlyphIsIdle"

MUTATION_SRC=$'	if !letter && !number {\n		return protocol.SessionStatusUnknown, r, false\n	}\n	return protocol.SessionStatusIdle, r, true'
MUTATION_DST=$'	if !letter && !number {\n		return protocol.SessionStatusUnknown, r, false\n	}\n	return protocol.SessionStatusUnknown, r, true // MUTATION[verify]: no-glyph back to unknown'

SNAP="$(mktemp -d)/detect.go.bak"

fail() { echo "MUTATE2 FAIL: $1" >&2; exit 1; }

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

if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 ); then
  fail "变异前具名测试 $TEST_MATCH 已红——基线不是绿，无法验判据"
fi
echo "GREEN baseline: $TEST_MATCH"

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
  fail "变异锚点未找到或非唯一"
fi
echo "mutated: classifyFallback no-glyph idle → unknown"

if ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 ); then
  fail "变异后具名测试 $TEST_MATCH 仍绿（exit 0）——判据对坏实现不响，判据无效"
fi
echo "RED confirmed: $TEST_MATCH 在变异后变红（exit 非 0）"

restore
RESTORED_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
if [ "$RESTORED_HASH" != "$BASE_HASH" ]; then
  fail "恢复后哈希不匹配（$RESTORED_HASH != $BASE_HASH）"
fi
echo "restored: $BASE_HASH"

if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 ); then
  fail "恢复后具名测试 $TEST_MATCH 仍红——判据误伤"
fi
echo "GREEN confirmed: $TEST_MATCH 在恢复后回绿（exit 0）"

FINAL_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
if [ "$FINAL_HASH" != "$BASE_HASH" ]; then
  fail "终态哈希不匹配——破坏未自动恢复"
fi
rm -f "$SNAP"
trap - EXIT

echo "----"
echo "mutate2: ALL PASS (变异红 + 恢复绿 + 工作区干净) — 判据有效"
exit 0
