#!/bin/sh
# 通用裁定书判据（评审格用）。$1 = 裁定书路径（相对本 worktree）。
# 判的是「裁定书形式合法」，⛔ 不重做事实判断：
#   - 文件在；
#   - 有且只有一行 `status=pass|rework|inconclusive`（三态，⛔ 不许缺）；
#   - status=pass 时正文里不许出现「没核到/未核/查不清/跑不起来/blocked/inconclusive」这类
#     与 pass 自相矛盾的词（反造假哨兵：形式绿最常见的形态就是嘴上 pass 身体 blocked）；
#   - 必须有「证据」节且引用了至少一处 `file:line` 或命令输出（⛔ 纯印象裁定不收）。
# 四态：0=通过；1=不通过；2=不可判。
set -u
V="${1:-}"
[ -n "$V" ] || { echo "UNJUDGEABLE 判据未传裁定书路径"; exit 2; }
[ -f "$V" ] || { echo "FAIL 裁定书不存在：$V"; exit 1; }

N=$(grep -cE '^status=(pass|rework|inconclusive)$' "$V" || true)
[ "$N" -eq 1 ] || { echo "FAIL 需要恰好一行 status=pass|rework|inconclusive，实得 $N 行"; exit 1; }
S=$(grep -E '^status=' "$V" | head -1 | cut -d= -f2)

if [ "$S" = "pass" ]; then
  if grep -nEi '没核到|未核|查不清|跑不起来|没跑到|blocked|inconclusive|不可判' "$V" >/dev/null; then
    echo "FAIL status=pass 但正文含自相矛盾的词（下列行），pass 不成立："
    grep -nEi '没核到|未核|查不清|跑不起来|没跑到|blocked|inconclusive|不可判' "$V"
    exit 1
  fi
fi

grep -qE '证据' "$V" || { echo "FAIL 裁定书缺「证据」节"; exit 1; }
grep -qE '[A-Za-z0-9_./-]+\.(kt|go|kts|sh|json|md):[0-9]+|\$ |rc=|exit code' "$V" || {
  echo "FAIL 证据节里没有 file:line 也没有命令输出原文"; exit 1; }

echo "PASS 裁定书形式合法 status=$S"
exit 0
