#!/bin/sh
# 判据：一条 pipe 扇出给 N 个订阅者（Q5 修复）——**三条断言的测试真的在仓里、且真的绿**。
#
# 判什么：① 三条断言的测试存在（多订阅者共存 / 一方断开不误伤 / 被踢必有明确帧）；
#         ② `go test` 在 bridge + api 两个包上绿；③ 说明里贴了「改前的红」原文。
# ⛔ 不判性能——单订阅者对照由 leader 读；这一格判的是**功能正确**。
# ⛔ 不奖励「测试都绿」而不看测试存不存在：删掉测试也能全绿。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.fanout/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
command -v go >/dev/null 2>&1 || { echo "UNJUDGEABLE 没有 go，跑不了测试"; exit 2; }

# ① 三条断言必须以测试形式留在仓里。⛔ 不接受「我手工验过了」。
T=$(grep -rlE 'func Test[A-Za-z0-9_]*(Fanout|MultiSub|SecondSubscriber|TwoSubscribers|Displaced)' \
      server/internal/bridge server/internal/api 2>/dev/null | head -3)
[ -n "$T" ] || {
  echo "FAIL 找不到多订阅者相关的测试函数（名字里应含 Fanout/MultiSub/TwoSubscribers/Displaced 之一）"
  echo "     ⛔ 三条断言必须留在仓里——删掉测试也能全绿，那不算修好"
  exit 1
}
echo "  ok   找到测试文件：$(echo "$T" | tr '\n' ' ')"

# 三条语义各要有一条断言在。用测试函数名+注释里的关键词粗核，⛔ 只挡「整条忘了写」。
miss=""
grep -rqE '仍能收|still receives|not affected|不受影响' $T || miss="$miss A(第二人接入后第一人仍收帧)"
grep -rqE '断开|detach|disconnect|close' $T || miss="$miss B(一方断开不误伤另一方)"
grep -rqE 'sendError|明确帧|displaced|kick|notif' $T || miss="$miss C(被踢者收得到明确帧)"
[ -z "$miss" ] || { echo "FAIL 测试里看不到这些断言：${miss}"; exit 1; }

# ③ 说明里必须有「改前的红」——复现先红是本工程铁律
grep -qE 'FAIL|--- FAIL|红|failed' "$S" || {
  echo "FAIL 说明里没有贴改前的红原文 —— 复现先红是铁律，没红过的修复不算数"; exit 1; }

# ② 真跑测试
echo "  …  go test ./internal/bridge/ ./internal/api/"
out=$(cd server && go test ./internal/bridge/ ./internal/api/ 2>&1)
rc=$?
printf '%s\n' "$out" | tail -12
[ "$rc" -eq 0 ] || { echo "FAIL go test 未通过（rc=${rc}）"; exit 1; }

echo "PASS 三条断言的测试在仓里且齐、说明贴了改前的红、bridge+api 两包 go test 绿"
echo "     ⚠️ 本判据⛔ 不判性能——单订阅者路径的对照由 leader 读说明。"
exit 0
