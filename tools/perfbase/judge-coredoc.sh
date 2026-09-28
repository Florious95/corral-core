#!/bin/sh
# 判据：core 消费者文档成立——**里面提到的每个符号、每条路径都真实存在**。
#
# 为什么要机械核：这类「怎么用这个库」的文档最容易写成看着专业但 API 根本不存在的样子，
# 而读者要到编译报错才发现。判据不判文笔，只判**可证伪的部分**：
#   ① 文档在且不是空壳；
#   ② 提到的 Kotlin 符号（`Foo` 反引号包起来的大驼峰）在三核源码里能找到定义；
#   ③ 提到的仓内路径逐个 test -e；
#   ④ 依赖坐标与实际发布的产物版本一致；
#   ⑤ 必须覆盖三个模块各自"能干什么"，⛔ 不许只写一个。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
D=docs/core-消费者指南.md
[ -f "$D" ] || { echo "FAIL 文档不存在：${D}"; exit 1; }
N=$(wc -l < "$D" | tr -d ' ')
[ "$N" -ge 60 ] || { echo "FAIL ${D} 只有 ${N} 行，空壳不算交付"; exit 1; }

SRC="app/core-protocol/src app/core-terminal/src app/core-conn/src"
for d in $SRC; do
  [ -d "$d" ] || { echo "UNJUDGEABLE 源码目录不在：${d}（判据没法核符号）"; exit 2; }
done

# ⑤ 三个模块都要讲到
miss=""
for m in core-protocol core-terminal core-conn; do
  grep -q "$m" "$D" || miss="$miss $m"
done
[ -z "$miss" ] || { echo "FAIL 文档没覆盖这些模块：${miss}"; exit 1; }

# ② 反引号里的大驼峰符号必须在三核源码里有定义
bad=""
for sym in $(grep -oE '`[A-Z][A-Za-z0-9]{2,}`' "$D" | tr -d '`' | sort -u); do
  grep -rqE "(class|interface|object|fun|val|enum class|data class|sealed class|typealias) +$sym\b" $SRC 2>/dev/null \
    || bad="$bad $sym"
done
[ -z "$bad" ] || {
  echo "FAIL 文档提到的符号在三核源码里找不到定义：${bad}"
  echo "     （⛔ 不许凭印象写 API——读者会在编译时才发现）"
  exit 1
}

# ③ 仓内路径逐个 test -e
badp=""
for p in $(grep -oE '`(app|docs|tools|\.team)/[A-Za-z0-9._/-]+`' "$D" | tr -d '`' | sort -u); do
  [ -e "$p" ] || badp="$badp $p"
done
[ -z "$badp" ] || { echo "FAIL 文档写的路径不存在：${badp}"; exit 1; }

# ④ 坐标与实际产物一致
VER=$(ls .team/staging/maven-repo/dev/agentmirror/core/core-protocol/ 2>/dev/null | head -1)
[ -n "$VER" ] || { echo "UNJUDGEABLE 本地 maven-repo 里没有 core-protocol，核不了版本"; exit 2; }
grep -q "dev.agentmirror.core:core-protocol:${VER}" "$D" \
  || { echo "FAIL 文档里的依赖坐标与实际产物版本对不上（实际 ${VER}）"; exit 1; }
grep -q "raw.githubusercontent.com/Florious95/corral-core/maven" "$D" \
  || { echo "FAIL 文档没写 maven 分支的仓库 URL，读者拿不到产物"; exit 1; }

echo "PASS ${D}（${N} 行）：三模块齐、符号全部可定位、路径全部存在、坐标 ${VER} 对得上"
exit 0
