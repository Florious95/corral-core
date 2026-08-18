#!/usr/bin/env bash
# ov2-mutate.sh — 对照席定点变异（ledger.overlay-fix.v1 / t.ver）
#
# 自检：如果被测对象是坏的，这条命令会不会仍然返回 0？会，就还不是判据。
# 破坏：去掉 scratch 排除（choose-tree 不再 -f 过滤，stripObserver 变空操作）。
# 具名测试 TestOverlayExcludesScratchSession 必须变红；恢复后必须绿。
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SERVER_DIR="$ROOT/server"
TARGET="$SERVER_DIR/internal/overlay/tmux.go"
TEST_PKG="./internal/overlay/"
# The named test lives in api/ and drives the live capturer.
TEST_MATCH="TestOverlayExcludesScratchSession"

ANCHOR1_SRC=$'	if err := runTmux(ctx, sock, "choose-tree", "-f", scratchFilter, "-t", ScratchSession+":0.0"); err != nil {'
ANCHOR1_DST=$'	if err := runTmux(ctx, sock, "choose-tree", "-t", ScratchSession+":0.0"); err != nil { // MUTATION[verify]: no scratch filter'

ANCHOR2_SRC=$'func stripObserver(raw []byte) []byte {\n	if len(raw) == 0 {\n		return raw\n	}'
ANCHOR2_DST=$'func stripObserver(raw []byte) []byte {\n	return raw // MUTATION[verify]: keep observer tokens\n	if len(raw) == 0 {\n		return raw\n	}'

SNAP="$(mktemp -d)/tmux.go.bak"
fail() { echo "OV2-MUTATE FAIL: $1" >&2; exit 1; }

[ -f "$TARGET" ] || fail "被测文件不存在: $TARGET"
cp -f "$TARGET" "$SNAP"
BASE_HASH="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
echo "baseline hash: $BASE_HASH"

restore() { cp -f "$SNAP" "$TARGET" 2>/dev/null || true; }
trap restore EXIT

apply() {
  python3 - "$TARGET" "$1" "$2" <<'PYEOF'
import sys
p, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding="utf-8").read()
if src not in s:
    sys.exit("anchor not found")
if s.count(src) != 1:
    sys.exit(f"anchor not unique: {s.count(src)}")
open(p, "w", encoding="utf-8").write(s.replace(src, dst, 1))
PYEOF
}

if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 -timeout 90s ); then
  fail "变异前 $TEST_MATCH 已红——基线不是绿"
fi
echo "GREEN baseline: $TEST_MATCH"

apply "$ANCHOR1_SRC" "$ANCHOR1_DST" || fail "锚点1（choose-tree -f）未找到或非唯一"
apply "$ANCHOR2_SRC" "$ANCHOR2_DST" || fail "锚点2（stripObserver）未找到或非唯一"
echo "mutated: scratch filter + stripObserver removed"

if ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 -timeout 90s ); then
  fail "变异后 $TEST_MATCH 仍绿——判据对坏实现不响"
fi
echo "RED confirmed: $TEST_MATCH 变红"

restore
RESTORED="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
[ "$RESTORED" = "$BASE_HASH" ] || fail "恢复后哈希不匹配"

if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_MATCH}$" -count=1 -timeout 90s ); then
  fail "恢复后 $TEST_MATCH 仍红"
fi
echo "GREEN confirmed: $TEST_MATCH 回绿"

FINAL="$(shasum -a 256 "$TARGET" | awk '{print $1}')"
[ "$FINAL" = "$BASE_HASH" ] || fail "终态哈希不匹配"
rm -f "$SNAP"
trap - EXIT
echo "----"
echo "ov2-mutate: ALL PASS (变异红 + 恢复绿 + 文件还原)"
exit 0
