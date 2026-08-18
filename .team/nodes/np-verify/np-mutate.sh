#!/usr/bin/env bash
# np-mutate.sh — 对照席定点变异（ledger.nodeprobe.v1 / t.verify）
#
# 自检：如果被测对象是坏的，这条命令会不会仍然返回 0？会，就还不是判据。
# 破坏：classify.rs fallback 把无人认领的前导符号从 unknown 回落成 idle。
# 语料测试 fixtures_corpus 必须变红；恢复后必须绿。trap 保证自动恢复。
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CRATE="$ROOT/tools/nodeprobe"
TARGET="$CRATE/src/classify.rs"
TEST_MATCH="fixtures_corpus"

MUTATION_SRC=$'        Some(c) => Class {\n            state: STATE_UNKNOWN,\n            provider: PROVIDER_UNKNOWN,\n            first: Some(c),\n            known: false,\n        },'
MUTATION_DST=$'        Some(c) => Class {\n            state: STATE_IDLE, // MUTATION[verify]: unknown falls back to idle\n            provider: PROVIDER_UNKNOWN,\n            first: Some(c),\n            known: false,\n        },'

SNAP="$(mktemp -d)/classify.rs.bak"

fail() { echo "NP-MUTATE FAIL: $1" >&2; exit 1; }

[ -f "$TARGET" ] || fail "被测文件不存在: $TARGET"
cp -f "$TARGET" "$SNAP"
BASE_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
echo "baseline hash: $BASE_HASH"

restore() { cp -f "$SNAP" "$TARGET" 2>/dev/null || true; }
trap restore EXIT

run_corpus() {
  # lib test path is tests::fixtures_corpus; --lib avoids the empty main/doc bins
  ( cd "$CRATE" && cargo test --offline --lib tests::fixtures_corpus -- --exact --nocapture )
}

if ! run_corpus; then
  fail "变异前 $TEST_MATCH 已红——基线不是绿"
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
echo "mutated: unknown leading glyph → idle"

if run_corpus; then
  fail "变异后 $TEST_MATCH 仍绿——判据对坏实现不响"
fi
echo "RED confirmed: $TEST_MATCH 变红"

restore
RESTORED="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
[ "$RESTORED" = "$BASE_HASH" ] || fail "恢复后哈希不匹配"

if ! run_corpus; then
  fail "恢复后 $TEST_MATCH 仍红"
fi
echo "GREEN confirmed: $TEST_MATCH 回绿"

FINAL="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
[ "$FINAL" = "$BASE_HASH" ] || fail "终态哈希不匹配"
rm -f "$SNAP"
trap - EXIT

echo "----"
echo "np-mutate: ALL PASS (变异红 + 恢复绿 + 文件还原)"
exit 0
