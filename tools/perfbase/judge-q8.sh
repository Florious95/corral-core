#!/bin/sh
# 判据：8 条答复稿成立——**每条都有结论、有置信度、且「源码确定」的那些真的带了存在的行号**。
#
# 判什么、不判什么：
#   判  ① 8 条都在；② 每条有结论 + 置信度三选一；
#       ③ 标了「源码确定」的条目必须带 `文件:行号`，且**文件真实存在、行号不越界**；
#       ④ Q5（可能是我方缺陷）必须在「暴露了我方缺陷」一节里被点名讨论。
#   ⛔ 不判结论对不对，也⛔ 不逼 8 条都「源码确定」——
#      「需实测」「查不出」是合法答案。下游已经因为一个未验证的前提翻过车，
#      判据若奖励「都有答案」，就是在鼓励我方犯同样的错。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
A=.team/nodes/t.q8/答复.md
S=.team/nodes/t.q8/说明.md
[ -f "$A" ] || { echo "FAIL 答复稿不存在：${A}"; exit 1; }
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }

python3 - "$A" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是不可判，⛔ 不是不通过（未捕获异常退出码恰好 1，会与真红撞码）。
    traceback.print_exception(t, v, tb); print("UNJUDGEABLE 判据自己跑不起来"); _s.exit(2)
_s.excepthook = _hook
import os, re, sys
txt = open(sys.argv[1], encoding="utf-8").read()

# 按 "## Q<n>" 切块
blocks = re.split(r'^##\s*Q(\d)\b', txt, flags=re.M)
secs = {}
for i in range(1, len(blocks) - 1, 2):
    secs[int(blocks[i])] = blocks[i + 1]

bad, conf_count = [], {"源码确定": 0, "需实测": 0, "查不出": 0}
for n in range(1, 9):
    b = secs.get(n)
    if b is None:
        bad.append("缺 Q%d —— 8 条一条都不能少" % n); continue
    if "结论" not in b:
        bad.append("Q%d 没有【结论】" % n)
    m = re.search(r'置信度[^\n]*?(源码确定|需实测|查不出)', b)
    if not m:
        bad.append("Q%d 没有标置信度（源码确定 / 需实测 / 查不出）" % n); continue
    kind = m.group(1); conf_count[kind] += 1
    if kind == "源码确定":
        # ⛔ 「源码确定」是最强主张，必须能被复核：文件要在、行号不能越界
        refs = re.findall(r'([A-Za-z0-9_./-]+\.(?:go|kt|md)):(\d+)', b)
        if not refs:
            bad.append("Q%d 标了「源码确定」却没有任何 文件:行号" % n); continue
        for path, ln in refs:
            if not os.path.exists(path):
                bad.append("Q%d 引用的文件不存在：%s" % (n, path)); continue
            try:
                total = sum(1 for _ in open(path, encoding="utf-8", errors="ignore"))
            except Exception:
                continue
            if int(ln) > total:
                bad.append("Q%d 引用 %s:%s 越界（该文件共 %d 行）" % (n, path, ln, total))

# Q5 必须在缺陷小节里被点名——它是本轮最可能命中我方缺陷的一条
tail = txt.split("暴露")[-1] if "暴露" in txt else ""
if "Q5" not in tail and "pipe-pane" not in tail:
    bad.append("「哪几条暴露了我方缺陷」一节里没有点名 Q5 / pipe-pane —— 它是本轮最要紧的一条")

if bad:
    print("FAIL 答复稿不合格：")
    for x in bad[:12]: print("  - " + x)
    sys.exit(1)
print("  ok   8 条齐；置信度分布：源码确定 %d / 需实测 %d / 查不出 %d"
      % (conf_count["源码确定"], conf_count["需实测"], conf_count["查不出"]))
print("PASS 每条有结论与置信度；「源码确定」的引用文件都存在且行号不越界；Q5 已在缺陷节点名")
print("     ⚠️ 本判据⛔ 不判结论对错——「需实测/查不出」是合法答案，⛔ 不奖励「都有答案」。")
sys.exit(0)
PY
