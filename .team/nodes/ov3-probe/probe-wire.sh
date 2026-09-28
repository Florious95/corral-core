#!/usr/bin/env bash
# probe-wire.sh — 070 跨语言线上帧往返（App 序列化器 → Go UnmarshalFrame+Validate）
#
# ① Kotlin *WireFixtureExport* 用 FrameCodec 把每一个 C→S 帧落盘 app/wire-fixtures/*.json
# ② Go 读同一批文件，服务端 UnmarshalFrame（含 Validate）逐个解
# ③ overlay_subscribe 的 socket 必须非空，并打印原值
#
# 红必须能指出是「socket 为空」还是别的。不启动模拟器，不碰用户 tmux。
set -u
fail() { echo "FAIL $1"; exit 1; }

ORACLE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$ORACLE_DIR/../../.." && pwd)"
APP="$ROOT/app"
SERVER="$ROOT/server"
FIXTURES="$ROOT/app/wire-fixtures"
KT_SRC="$ORACLE_DIR/WireFixtureExportTest.kt"
KT_DST="$APP/app/src/test/kotlin/dev/agentmirror/app/conn/WireFixtureExportTest.kt"
GO_SRC="$ORACLE_DIR/wire_fixture_import_test.go"
GO_DST="$SERVER/internal/protocol/zz_wire_fixture_import_test.go"

[ -x "$APP/gradlew" ] || fail "找不到 app/gradlew"
[ -d "$SERVER/internal/protocol" ] || fail "找不到 server/internal/protocol"
[ -f "$KT_SRC" ] || fail "找不到 WireFixtureExportTest.kt"
[ -f "$GO_SRC" ] || fail "找不到 wire_fixture_import_test.go"

mkdir -p "$FIXTURES"
mkdir -p "$(dirname "$KT_DST")"

cleanup() {
  rm -f "$KT_DST" "$GO_DST"
}
trap cleanup EXIT

cp "$KT_SRC" "$KT_DST"

echo "=== ① App FrameCodec 导出 C→S 夹具 ==="
set +e
( cd "$APP" && ./gradlew :app:testDebugUnitTest --tests '*WireFixtureExport*' )
kt_rc=$?
set -e
echo "WireFixtureExport exit=$kt_rc"
if [ "$kt_rc" != "0" ]; then
  fail "① WireFixtureExport 未产出夹具"
fi

N="$(python3 -c "import pathlib; p=pathlib.Path(r'$FIXTURES'); print(len(list(p.glob('*.json'))) if p.is_dir() else 0)")"
[ "$N" -ge 1 ] || fail "① $FIXTURES 没有 json"
echo "PASS ① fixtures=$N -> $FIXTURES"
if [ -f "$FIXTURES/overlay_subscribe.json" ]; then
  echo "--- overlay_subscribe.json ---"
  cat "$FIXTURES/overlay_subscribe.json"
  echo
fi
if [ -f "$FIXTURES/overlay_subscribe.operands.txt" ]; then
  echo "--- sessionSocketFromRef 操作数 ---"
  cat "$FIXTURES/overlay_subscribe.operands.txt"
fi

cp "$GO_SRC" "$GO_DST"
export OV3_WIRE_FIXTURES="$FIXTURES"
echo "=== ②③ Go UnmarshalFrame + Validate ==="
set +e
( cd "$SERVER" && go test ./internal/protocol -count=1 -run TestWireFixtureImport )
go_rc=$?
set -e
echo "TestWireFixtureImport exit=$go_rc"
if [ "$go_rc" != "0" ]; then
  echo "FAIL 跨语言往返未全绿"
  exit 1
fi
echo "probe-wire ALL PASS"
exit 0
