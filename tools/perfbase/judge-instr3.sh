#!/bin/sh
# 判据：t.instr3——把「帧到底有没有进 app」这最后一段盲区补上。
# 由来（pb-emu 两轮实测收窄）：subscribe 发出了、服务端说推了快照，但客户端
# first_frame_recv/snapshot_applied 一次没有；t.instr2 补的 ref_mismatch/no_open 也一条没打
# ⇒ onBinary 根本没被调用。剩下两种世界分不开：①WS 层没收到这一帧 ②收到了但
# ConnectionManager.kt:617 的 `listener?.onBinary(frame)` 单一全局槽为 null/被覆盖，静默丢掉。
# 本判据只判「这两处不再沉默」，⛔ 不判白屏修没修（修在下一格，先让日志能一锤定音）。
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

# ① listener 槽落空必须留痕，且带两边操作数（帧的 ref、槽是不是 null）
grep -rq -- "reason=no_listener" "$SRC" || {
  echo "FAIL ConnectionManager 的 listener 槽仍然静默丢帧：找不到 reason=no_listener"; exit 1; }
grep -rq -- "listener_null=" "$SRC" || {
  echo "FAIL no_listener 那行缺操作数 listener_null=（只记判决不记操作数=判不出谁对谁错）"; exit 1; }

# ② WS 二进制读入口必须有「收到帧」的计数/留痕 —— 没有它就分不出「没收到」与「收到没派出去」
for k in ws_binary_recv "kind=" "bytes="; do
  grep -rq -- "$k" "$SRC" || {
    echo "FAIL WS 收帧留痕缺 ${k}（没有它就分不出「没收到」与「收到没派出去」）"; exit 1; }
done

# ③ 留痕一律 emitted=0 形态，⛔ 不许伪装成正常事件把假时间戳混进基线
grep -rq -- "emitted=0" "$SRC" || { echo "FAIL 留痕不是 emitted=0 形态"; exit 1; }

cd "$ROOT/app" || { echo "UNJUDGEABLE cd 失败"; exit 2; }
OUT="$ROOT/.team/nodes/pb-instr3/tmp/green.log"
mkdir -p "$(dirname "$OUT")"
./gradlew :app:testDebugUnitTest :terminal:test --offline --rerun-tasks >"$OUT" 2>&1
RC=$?
grep -qE "Compilation error|Unresolved reference" "$OUT" && { echo "UNJUDGEABLE 编译不过（见 ${OUT}）"; exit 2; }
[ "$RC" -eq 0 ] || { echo "FAIL 全量单测红 rc=${RC}（基线为 0；见 ${OUT}）"; exit 1; }
echo "PASS 收帧入口与 listener 槽不再沉默"
exit 0
