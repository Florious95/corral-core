#!/bin/sh
# 判据：第 2 步成立——**客户端能表达裸字节、互斥守住、且不带 bytes 时线格式没变**。
#
# 判什么：① R1/R2/R3 三条测试在仓里；② core-protocol 测试真跑且绿；
#         ③ 说明里贴了改前的红；④ 服务端零改动（这一格只动客户端）。
# ⛔ 不判性能——这一格是纯协议表达，不在热路径；最终判据是用户真机。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.step2/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "UNJUDGEABLE 没有 git"; exit 2; }

# ④ 这一格只动客户端 —— 机械核实，⛔ 不采信自报
DIRTY=$(git status --porcelain -- 'server/*' 2>/dev/null | head -10)
[ -z "$DIRTY" ] || { echo "FAIL 这一格不许改服务端，但 server/ 下有改动："; printf '%s\n' "$DIRTY"; exit 1; }

# ① 三条断言必须以测试形式留在仓里。⛔ 删掉测试也能全绿。
T=$(grep -rlE '(fun|class) .*(RawBytes|Base64|InputBytes|Passthrough)' \
      app/core-protocol/src/test app/core-conn/src/test 2>/dev/null | head -3)
[ -n "$T" ] || {
  echo "FAIL 找不到裸字节相关测试（app/core-protocol|core-conn 的 test 下，名字含 RawBytes/Base64/InputBytes/Passthrough）"
  exit 1
}
echo "  ok   测试文件：$(echo "$T" | tr '\n' ' ')"

miss=""
grep -rqE 'base64|Base64' $T                      || miss="$miss R1(base64 往返)"
grep -rqE '互斥|mutual|exclusiv|both|同时'         $T || miss="$miss R2(text/keys/bytes 互斥)"
grep -rqE '兼容|compat|unchanged|逐字段|identical' $T || miss="$miss R3(不带 bytes 时线格式不变)"
[ -z "$miss" ] || { echo "FAIL 测试里看不到这些断言：${miss}"; exit 1; }

# ③ 复现先红是铁律
grep -qE 'FAIL|红|failed' "$S" || {
  echo "FAIL 说明里没有贴改前的红原文 —— 复现先红是铁律，没红过的修复不算数"; exit 1; }

# ② 真跑
if [ -x ./gradlew ]; then
  echo "  …  ./gradlew :core-protocol:test :core-conn:test"
  out=$(./gradlew --console=plain -q :core-protocol:test :core-conn:test 2>&1)
  rc=$?
  printf '%s\n' "$out" | tail -12
  [ "$rc" -eq 0 ] || { echo "FAIL gradle 测试未通过（rc=${rc}）"; exit 1; }
else
  echo "UNJUDGEABLE 没有 ./gradlew，跑不了 JVM 测试"; exit 2
fi

echo "PASS 三条断言在仓里、说明贴了改前的红、服务端零改动、core-protocol+core-conn 测试绿"
echo "     ⚠️ 性能⛔ 不在本判据内——最终判据是用户真机「秒开无空白」。"
exit 0
