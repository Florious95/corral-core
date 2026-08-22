#!/bin/sh
# 判据：t.instr4——把 instr2 与 instr3 两批留痕合进同一个构建，并补上「接帧者是谁」这个操作数。
# 由来（pb-emu 三轮实测）：29/29 都是 ws_binary_recv(kind=snapshot,bytes>0) + listener_null=0，
# 即 WS 收到了、ref 对得上、listener 槽非空且已派发 ⇒ 帧死在 SessionViewModel.onBinary 内部。
# 但「是 frame.ref != ref 挡的，还是接帧的 listener 根本不是这个会话页的 VM」分不开——
# 因为 ref_mismatch/no_open（instr2）与 ws_binary_recv/no_listener（instr3）**从未同时在场**。
# 本判据要求：两批留痕齐全 + 派发点同时打出「帧的 ref」与「接帧者自己的 ref/实现类」。
# 四态：0=通过；1=不通过；2=不可判。
set -u
ROOT=$(pwd)
SRC="$ROOT/app/app/src/main"
[ -d "$SRC" ] || { echo "UNJUDGEABLE 无 app 源码"; exit 2; }

for k in reason=ref_mismatch reason=no_open frame_ref= want_ref= \
         reason=no_listener listener_null= ws_binary_recv "kind=" "bytes=" emitted=0 \
         listener_ref=; do
  grep -rq -- "$k" "$SRC" || { echo "FAIL 缺留痕/操作数：$k"; exit 1; }
done

cd "$ROOT/app" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
OUT="$ROOT/.team/nodes/pb-instr4/tmp/green.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :app:testDebugUnitTest :terminal:test --offline >"$OUT" 2>&1
RC=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 $OUT）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL 全量单测红 rc=${RC}（基线为 0；见 $OUT）"; exit 1; }
echo "PASS 两批留痕同时在场且带接帧者身份"
exit 0
