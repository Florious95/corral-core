#!/bin/sh
# 判据：第 1 步成立——**任意字节能到 pty、老路径没坏、且注入调用次数有界**。
#
# 判什么：① R1/R2/R3 三条测试在仓里；② go test 真跑（-count=1，⛔ 不吃缓存）；
#         ③ 说明里贴了改前的红；④ 说明里写清命名键闭集在哪（上一格的缺口）。
# ⛔ 不判性能——性能按两层走：机器粗筛只拦大幅退化，**最终判据是用户真机**。
#    判据若在这里设一个统计门，就是把验收标准从用户手里挪到量具手里。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.step1/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
command -v go >/dev/null 2>&1 || { echo "UNJUDGEABLE 没有 go，跑不了测试"; exit 2; }

# ① 三条红测必须留在仓里。⛔ 不接受「我手工验过了」——删掉测试也能全绿。
T=$(grep -rlE 'func Test[A-Za-z0-9_]*(RawBytes|Passthrough|ControlByte|MixedInput)' \
      server/internal 2>/dev/null | head -3)
[ -n "$T" ] || {
  echo "FAIL 找不到裸字节透传的测试（函数名应含 RawBytes/Passthrough/ControlByte/MixedInput 之一）"
  exit 1
}
echo "  ok   测试文件：$(echo "$T" | tr '\n' ' ')"

miss=""
grep -rqE '0x03|\\x03|ESC|\\x1b|控制字节' $T || miss="$miss R1(控制字节原样到 pty)"
grep -rqE '调用次数|callCount|invocations|次数|batch|合并' $T || miss="$miss R2(注入调用次数有界)"
grep -rqE 'Keys|TypeKeys|兼容|compat' $T || miss="$miss R3(老 Text/Keys 路径不坏)"
[ -z "$miss" ] || { echo "FAIL 测试里看不到这些断言：${miss}"; exit 1; }

# ③ 复现先红是铁律：没红过的修复不算数
grep -qE 'FAIL|--- FAIL|红' "$S" || {
  echo "FAIL 说明里没有贴改前的红原文 —— 复现先红是铁律"; exit 1; }

# ④ 上一格的缺口必须补上：命名键闭集到底在哪个文件
grep -qE '闭集.*\.go|\.go.*闭集|keys\.go|IsValid' "$S" || {
  echo "FAIL 说明里没写清命名键闭集实际在哪个文件（上一格的已知缺口，本格要补）"; exit 1; }

# ② 真跑，⛔ 不吃缓存
echo "  …  go test -count=1 ./internal/..."
out=$(cd server && go test -count=1 ./internal/... 2>&1)
rc=$?
printf '%s\n' "$out" | grep -vE '^ok|no test files' | head -12
[ "$rc" -eq 0 ] || { echo "FAIL go test 未通过（rc=${rc}）"; exit 1; }

echo "PASS 三条断言在仓里、说明贴了改前的红并写清闭集位置、server 全量 go test -count=1 绿"
echo "     ⚠️ 性能⛔ 不在本判据内——粗筛只拦大幅退化，最终判据是用户真机「秒开无空白」。"
exit 0
