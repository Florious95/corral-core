#!/usr/bin/env bash
# ov2-mutate.sh — 对照席定点变异（ledger.overlay-fix.v1 / t.ver）
#
# 自检：如果被测对象是坏的，这条命令会不会仍然返回 0？会，就还不是判据。
# 两处破坏，各自恢复：
#   A) 去掉 scratch 排除 → TestOverlayExcludesScratchSession 必须红
#   B) OverlayEmulator 只剥 ESC、CSI 当字形 → OverlayEmulatorFixtureTest 必须红
# 两头都成立才 exit 0；trap 保证工作区干净。
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SERVER_DIR="$ROOT/server"
APP_DIR="$ROOT/app"
TMUX_GO="$SERVER_DIR/internal/overlay/tmux.go"
EMU_KT="$APP_DIR/app/src/main/java/dev/agentmirror/app/overlay/OverlayEmulator.kt"
TEST_SCRATCH="TestOverlayExcludesScratchSession"
TEST_RENDER="OverlayEmulatorFixtureTest"

ANCHOR1_SRC=$'	if err := runTmux(ctx, sock, "choose-tree", "-f", scratchFilter, "-t", ScratchSession+":0.0"); err != nil {'
ANCHOR1_DST=$'	if err := runTmux(ctx, sock, "choose-tree", "-t", ScratchSession+":0.0"); err != nil { // MUTATION[verify]: no scratch filter'

ANCHOR2_SRC=$'func stripObserver(raw []byte) []byte {\n	if len(raw) == 0 {\n		return raw\n	}'
ANCHOR2_DST=$'func stripObserver(raw []byte) []byte {\n	return raw // MUTATION[verify]: keep observer tokens\n	if len(raw) == 0 {\n		return raw\n	}'

ANCHOR3_SRC=$'                ESC -> {\n                    i = consumeEsc(text, i)\n                    continue\n                }'
ANCHOR3_DST=$'                ESC -> {\n                    i += 1 // MUTATION[verify]: skip ESC only, print CSI as glyphs\n                    continue\n                }'

SNAP_DIR="$(mktemp -d)"
SNAP_GO="$SNAP_DIR/tmux.go.bak"
SNAP_KT="$SNAP_DIR/OverlayEmulator.kt.bak"
fail() { echo "OV2-MUTATE FAIL: $1" >&2; exit 1; }

[ -f "$TMUX_GO" ] || fail "被测文件不存在: $TMUX_GO"
[ -f "$EMU_KT" ] || fail "被测文件不存在: $EMU_KT"
cp -f "$TMUX_GO" "$SNAP_GO"
cp -f "$EMU_KT" "$SNAP_KT"
BASE_GO="$(shasum -a 256 "$TMUX_GO" | awk '{print $1}')"
BASE_KT="$(shasum -a 256 "$EMU_KT" | awk '{print $1}')"
echo "baseline tmux.go=$BASE_GO"
echo "baseline OverlayEmulator.kt=$BASE_KT"

restore() {
  cp -f "$SNAP_GO" "$TMUX_GO" 2>/dev/null || true
  cp -f "$SNAP_KT" "$EMU_KT" 2>/dev/null || true
}
trap restore EXIT

apply() {
  python3 - "$1" "$2" "$3" <<'PYEOF'
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

# --- A: scratch 排除 ---
if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_SCRATCH}$" -count=1 -timeout 90s ); then
  fail "变异前 $TEST_SCRATCH 已红——基线不是绿"
fi
echo "GREEN baseline: $TEST_SCRATCH"

apply "$TMUX_GO" "$ANCHOR1_SRC" "$ANCHOR1_DST" || fail "锚点1（choose-tree -f）未找到或非唯一"
apply "$TMUX_GO" "$ANCHOR2_SRC" "$ANCHOR2_DST" || fail "锚点2（stripObserver）未找到或非唯一"
echo "mutated: scratch filter + stripObserver removed"

if ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_SCRATCH}$" -count=1 -timeout 90s ); then
  fail "变异后 $TEST_SCRATCH 仍绿——判据对坏实现不响"
fi
echo "RED confirmed: $TEST_SCRATCH 变红"

cp -f "$SNAP_GO" "$TMUX_GO"
[ "$(shasum -a 256 "$TMUX_GO" | awk '{print $1}')" = "$BASE_GO" ] || fail "A 恢复后 tmux.go 哈希不匹配"

if ! ( cd "$SERVER_DIR" && go test ./internal/api/ -run "^${TEST_SCRATCH}$" -count=1 -timeout 90s ); then
  fail "恢复后 $TEST_SCRATCH 仍红"
fi
echo "GREEN confirmed: $TEST_SCRATCH 回绿"

# --- B: 绕过 OverlayEmulator CSI ---
if ! ( cd "$APP_DIR" && ./gradlew -q :app:testDebugUnitTest --tests "*${TEST_RENDER}" ); then
  fail "变异前 $TEST_RENDER 已红——基线不是绿"
fi
echo "GREEN baseline: $TEST_RENDER"

apply "$EMU_KT" "$ANCHOR3_SRC" "$ANCHOR3_DST" || fail "锚点3（consumeEsc）未找到或非唯一"
echo "mutated: OverlayEmulator prints CSI as glyphs"

if ( cd "$APP_DIR" && ./gradlew -q :app:testDebugUnitTest --tests "*${TEST_RENDER}" ); then
  fail "变异后 $TEST_RENDER 仍绿——判据对坏实现不响"
fi
echo "RED confirmed: $TEST_RENDER 变红"

cp -f "$SNAP_KT" "$EMU_KT"
[ "$(shasum -a 256 "$EMU_KT" | awk '{print $1}')" = "$BASE_KT" ] || fail "B 恢复后 OverlayEmulator.kt 哈希不匹配"

if ! ( cd "$APP_DIR" && ./gradlew -q :app:testDebugUnitTest --tests "*${TEST_RENDER}" ); then
  fail "恢复后 $TEST_RENDER 仍红"
fi
echo "GREEN confirmed: $TEST_RENDER 回绿"

FINAL_GO="$(shasum -a 256 "$TMUX_GO" | awk '{print $1}')"
FINAL_KT="$(shasum -a 256 "$EMU_KT" | awk '{print $1}')"
[ "$FINAL_GO" = "$BASE_GO" ] || fail "终态 tmux.go 哈希不匹配"
[ "$FINAL_KT" = "$BASE_KT" ] || fail "终态 OverlayEmulator.kt 哈希不匹配"
rm -rf "$SNAP_DIR"
trap - EXIT
echo "----"
echo "ov2-mutate: ALL PASS (scratch 红/绿 + emulator 红/绿 + 文件还原)"
exit 0
