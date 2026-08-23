#!/bin/sh
# 判据：选中高亮的**归因**做扎实了——三个问题各有答案与置信度、有代码/实测证据、给了最小改动方向。
#
# ⛔ 不判「根因是什么」：这一格是查不是改，`inconclusive` 是合法且有价值的结果。
#    判据若逼出一个根因，就是在鼓励席位编一个说得通的解释——那正是下游翻车的方式。
# ⛔ 也机械核实：这一格不许改任何产品代码。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
S=.team/nodes/t.selrender/说明.md
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "UNJUDGEABLE 没有 git"; exit 2; }

D=$(git status --porcelain -- 'app/*' 'server/*' 2>/dev/null | grep -v '^?? .team' | head -5)
[ -z "$D" ] || { echo "FAIL 这一格只许查不许改，但产品目录有改动："; printf '%s\n' "$D"; exit 1; }

python3 - "$S" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是不可判，⛔ 不是不通过。
    traceback.print_exception(t, v, tb); print("UNJUDGEABLE 判据自己跑不起来"); _s.exit(2)
_s.excepthook = _hook
import os, re, sys
txt = open(sys.argv[1], encoding="utf-8").read()
bad = []

# 三个问题都要有答案 + 置信度
conf = re.findall(r'(源码确定|实测确定|需实测|查不出)', txt)
if len(conf) < 3:
    bad.append("置信度标记只有 %d 处（三个问题各要一个）" % len(conf))

for kw, name in (("谁", "Q1 谁负责画选中高亮"),
                 ("断在", "Q2 断在第几环"),
                 ("改动方向", "Q3 最小改动方向")):
    if kw not in txt:
        bad.append("看不到【%s】的答案" % name)

# 「源码确定」的引用必须真实存在且行号不越界
for path, ln in re.findall(r'([A-Za-z0-9_./-]+\.(?:kt|go|java)):(\d+)', txt):
    if not os.path.exists(path):
        bad.append("引用的文件不存在：%s" % path); continue
    total = sum(1 for _ in open(path, encoding="utf-8", errors="ignore"))
    if int(ln) > total:
        bad.append("引用 %s:%s 越界（共 %d 行）" % (path, ln, total))

# 「判不出的部分」不许空着，除非三条全确定
m = re.split(r'判不出的部分', txt)
if len(m) > 1 and len(m[-1].strip()) < 15 and "需实测" in txt:
    bad.append("有「需实测」却把「判不出的部分」留空")

if bad:
    print("FAIL 归因产物不合格：")
    for x in bad[:10]: print("  - " + x)
    sys.exit(1)
print("  ok   置信度标记 %d 处；引用的文件与行号都核过" % len(conf))
print("PASS 三问有答案与置信度、引用可复核、产品码零改动")
print("     ⚠️ 本判据⛔ 不判根因对错——inconclusive 是合法且有价值的结果。")
sys.exit(0)
PY
