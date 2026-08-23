#!/bin/sh
# 判据：t.instr2——把收帧侧两个「静默 return」的盲区补上。
# 由来：pb-emu 30/30 冷点开里 first_frame_recv / snapshot_applied 一次没出现，而服务端旁证说推了快照。
# 光看日志分不出「帧没到」还是「帧到了被 ref 守卫吞了」——两种世界在日志里都是沉默。
# 本判据只判「守卫不再沉默且带了参与比较的两边操作数」，⛔ 不判白屏修没修（那是下一格的事）。
# 四态：0=通过；1=不通过；2=不可判。
set -u

# 🔴 worktree 里没有 local.properties（它按机器路径生成、已 gitignore），
# gradle 会报 "SDK location not found"。⛔ 别往仓里塞 local.properties——
# 那是机器相关路径。这里用环境变量供给，缺了就判**不可判**（不是判红：
# 那是本机环境不具备，不是被测物有问题）。2026-08-23 实撞。
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -d "$ANDROID_HOME" ] || { echo "UNJUDGEABLE 找不到 Android SDK（ANDROID_HOME=$ANDROID_HOME），跑不了 gradle"; exit 2; }
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

ROOT=$(pwd)
SRC="$ROOT/app/app/src/main"
[ -d "$SRC" ] || { echo "UNJUDGEABLE 无 app 源码"; exit 2; }

# ① ref 不匹配这条路必须留痕，且必须同时记下比较的两边（收到的 ref 与本页的 ref）
grep -rq -- "reason=ref_mismatch" "$SRC" || {
  echo "FAIL 收帧口的 ref 守卫仍然沉默：找不到 reason=ref_mismatch"; exit 1; }
# ② opens 里没有该次打开（idFor/opens[ref] 落空）这条路必须留痕
grep -rq -- "reason=no_open" "$SRC" || {
  echo "FAIL first_frame_recv/snapshot_applied 的 opens 守卫仍然沉默：找不到 reason=no_open"; exit 1; }
# ③ 两边操作数：ref_mismatch 那行必须能同时给出 frame 侧与本页侧的 ref
for k in frame_ref want_ref; do
  grep -rq -- "$k" "$SRC" || { echo "FAIL 缺操作数键 ${k}（只记判决不记操作数 = 判不出谁对谁错）"; exit 1; }
done
# ④ 这两条留痕必须是 emitted=0 形态，⛔ 不许伪装成正常事件把假时间戳混进基线
grep -rq -- "emitted=0" "$SRC" || { echo "FAIL 守卫留痕不是 emitted=0 形态"; exit 1; }

cd "$ROOT/app" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
OUT="$ROOT/.team/nodes/pb-instr2/tmp/green.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :app:testDebugUnitTest :terminal:test --offline --rerun-tasks >"$OUT" 2>&1
RC=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 ${OUT}）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL 全量单测红 rc=${RC}（基线为 0；见 ${OUT}）"; exit 1; }
echo "PASS 收帧侧守卫不再沉默且带两边操作数"
exit 0
