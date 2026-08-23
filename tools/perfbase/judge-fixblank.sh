#!/bin/sh
# 判据：t.fixblank——修「订阅首帧被列表页 VM 吃掉」这个白屏根因。
# 根因（pb-emu 四轮实测，18/18 零反例）：ConnectionManager 只有一个全局 listener 槽，
# 被 WorkspaceViewModel（列表页）占着；订阅首帧交给它、就地丢弃，会话页 VM 一帧都拿不到。
# 证据：ev=first_frame_recv emitted=0 reason=has_listener listener_null=0
#       listener_ref=dev.agentmirror.app.workspace.WorkspaceViewModel
# 本判据判「行为对了」而不是「代码长什么样」：靠一条会失败的路由红测 + 全量绿。
# 四态：0=通过；1=不通过；2=不可判。
set -u
ROOT=$(pwd)
T="$ROOT/app/app/src/test/kotlin/dev/agentmirror/app/conn/ConnListenerRoutingTest.kt"
[ -f "$T" ] || T="$ROOT/app/app/src/test/java/dev/agentmirror/app/conn/ConnListenerRoutingTest.kt"
[ -f "$T" ] || { echo "FAIL 缺路由红测 ConnListenerRoutingTest.kt"; exit 1; }
grep -q "@Ignore" "$T" && { echo "FAIL 路由红测被 @Ignore"; exit 1; }
for m in 帧只投给订阅该ref的接收者 列表页占用槽位不影响会话页收帧; do
  grep -q "$m" "$T" || { echo "FAIL 路由红测缺方法 ${m}（名字即判据）"; exit 1; }
done

# 仪表不许在修复时被拆掉——它是本轮唯一能证明修好了的量具
SRC="$ROOT/app/app/src/main"
for k in listener_ref= ws_binary_recv reason=ref_mismatch; do
  grep -rq -- "$k" "$SRC" || { echo "FAIL 修复顺手把仪表 $k 拆了（量具没了就没法自证修好）"; exit 1; }
done

cd "$ROOT/app" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
OUT="$ROOT/.team/nodes/pb-fixblank/tmp/green.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :app:testDebugUnitTest :terminal:test --offline --rerun-tasks >"$OUT" 2>&1
RC=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 $OUT）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL 全量单测红 rc=${RC}（基线为 0；见 $OUT）"; exit 1; }
echo "PASS 路由红测转绿且仪表未被拆"
exit 0
