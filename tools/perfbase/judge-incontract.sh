#!/bin/sh
# 判据：输入透传「输入模型契约」成立——**七项裁定逐条落纸，且这一格没碰产品码**。
#
# 为什么这样判：契约文档最容易写成一篇通顺但**把关键取舍含糊过去**的东西
# （比如只说"支持鼠标"，不说用哪种编码、不说 1003 为什么不能开）。
# 判据不判文笔，只判**可证伪的部分**：该出现的裁定关键词在不在、代码引用有没有行号、
# 以及**这一格声明的"不碰产品码"是不是真的**。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
D=docs/输入透传契约.md
S=.team/nodes/t.contract/说明.md

[ -f "$D" ] || { echo "FAIL 契约不存在：${D}"; exit 1; }
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
N=$(wc -l < "$D" | tr -d ' ')
[ "$N" -ge 80 ] || { echo "FAIL ${D} 只有 ${N} 行，七节写不完，空壳不算交付"; exit 1; }

command -v git >/dev/null 2>&1 || { echo "UNJUDGEABLE 没有 git，核不了'有没有碰产品码'"; exit 2; }

# ① 七项裁定的关键词逐条核。每行：<标签>|<必须命中的 grep -E 模式>
# ⚠️ 这是**存在性**检查，⛔ 不是正确性检查——正确性由 leader 读文。
# 但缺了关键词一定是漏写了那一节，这一层能挡住"整节忘了写"。
fail=""
check() {
  grep -qE "$2" "$D" || fail="$fail
  缺【$1】：文档里找不到 /$2/"
}
check "鼠标归属=Agent"      'Agent'
check "tmux 不许截获鼠标"    'tmux'
check "SGR 1006 编码"        '1006'
check "排除 X10/1005/1015"   'X10|1005|1015'
check "跟踪模式 1002"        '1002'
check "禁用 1003 的理由"     '1003'
check "重连按序重放模式"      '重放|重连'
check "ack 策略"             'ack|ACK'
check "send-keys -l/-H"      'send-keys'
check "许可证边界 GPLv3"     'GPLv3'
check "许可证边界 Apache"    'Apache-2\.0'
check "xterm.js 可参考(MIT)" 'MIT'
check "已知坑：拖拽变复制"    '选择复制|拖拽'

[ -z "$fail" ] || { printf 'FAIL 契约漏了这些裁定：%s\n' "$fail"; exit 1; }

# ② 代码引用必须带行号。⛔ 不许凭印象写 API——这是本工程反复踩的坑。
REF=$(grep -oE '[A-Za-z0-9_./-]+\.(go|kt):[0-9]+' "$D" | sort -u | wc -l | tr -d ' ')
[ "$REF" -ge 2 ] || {
  echo "FAIL 契约里带行号的代码引用只有 ${REF} 处（要求 >=2）"
  echo "     （⛔ 引用代码事实必须写 文件:行号，否则无法复核）"
  exit 1
}
# 引用的文件必须真实存在
badf=""
for p in $(grep -oE '[A-Za-z0-9_./-]+\.(go|kt):[0-9]+' "$D" | cut -d: -f1 | sort -u); do
  [ -e "$p" ] || badf="$badf $p"
done
[ -z "$badf" ] || { echo "FAIL 契约引用的文件不存在：${badf}"; exit 1; }

# ③ 这一格声明"不碰产品码"——机械核实，⛔ 不采信自报。
DIRTY=$(git status --porcelain -- 'app/*' 'server/*' 2>/dev/null | head -20)
[ -z "$DIRTY" ] || {
  echo "FAIL 这一格不许改产品码，但 app/ 或 server/ 下有改动："
  printf '%s\n' "$DIRTY"
  exit 1
}

echo "PASS ${D}（${N} 行）：13 项裁定关键词齐、${REF} 处带行号引用且文件都在、产品码零改动"
exit 0
