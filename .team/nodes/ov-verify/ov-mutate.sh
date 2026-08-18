#!/usr/bin/env bash
# ov-mutate.sh — 对照席定点变异（ledger.overlay.v1 / t.verify）
#
# 自检：如果被测对象是坏的，这条命令会不会仍然返回 0？会，就还不是判据。
# 破坏：unmarkOverlay 变成空操作（关闭后不退订、不 Stop）。
# 具名测试 TestOverlayNoResourceWithoutSubscriber 必须变红；恢复后必须绿。
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SERVER_DIR="$ROOT/server"
TARGET="$SERVER_DIR/internal/api/overlay.go"
TEST_MATCH="TestOverlayNoResourceWithoutSubscriber"

MUTATION_SRC=$'func (s *Server) unmarkOverlay() {\n	if s.overlaySubscribers.Add(-1) <= 0 {\n		s.overlaySubscribers.Store(0)\n		if s.overlay != nil {\n			s.overlay.Stop()\n		}\n	}\n}'
MUTATION_DST=$'func (s *Server) unmarkOverlay() {\n	// MUTATION[verify]: close does not unsubscribe / Stop\n}'

SNAP="$(mktemp -d)/overlay.go.bak"
fail() { echo "OV-MUTATE FAIL: $1" >&2; exit 1; }

[ -f "$TARGET" ] || fail "被测文件不存在: $TARGET"
cp -f "$TARGET" "$SNAP"
BASE_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
echo "baseline hash: $BASE_HASH"

restore() { cp -f "$SNAP" "$TARGET" 2>/dev/null || true; }
trap restore EXIT

if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 -timeout 60s ); then
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
echo "mutated: unmarkOverlay is a no-op (close does not unsubscribe)"

if ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 -timeout 60s ); then
  fail "变异后 $TEST_MATCH 仍绿——判据对坏实现不响"
fi
echo "RED confirmed: $TEST_MATCH 变红"

restore
RESTORED="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
[ "$RESTORED" = "$BASE_HASH" ] || fail "恢复后哈希不匹配"

if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 -timeout 60s ); then
  fail "恢复后 $TEST_MATCH 仍红"
fi
echo "GREEN confirmed: $TEST_MATCH 回绿"

FINAL="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
[ "$FINAL" = "$BASE_HASH" ] || fail "终态哈希不匹配"
rm -f "$SNAP"
trap - EXIT
echo "----"
echo "ov-mutate: ALL PASS (变异红 + 恢复绿 + 文件还原)"
exit 0
