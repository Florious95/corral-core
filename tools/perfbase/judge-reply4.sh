#!/bin/sh
# 判据：给下游的 4 条答复稿成立——**六节齐、每条标了置信度、「源码确定」的引用真实存在、
# 且给了指到他们代码的可执行建议**。
#
# ⛔ 不判结论对错；⛔ 不奖励「四条都源码确定」——「需实测/查不出」是合法答案。
# 下游已经因为一个未验证的前提翻过车，判据若奖励「都有答案」，就是在鼓励我方犯同样的错。
#
# 四态：0=通过；1=不通过；2=不可判。
set -u
A=.team/nodes/t.reply4/答复.md
S=.team/nodes/t.reply4/说明.md
[ -f "$A" ] || { echo "FAIL 答复稿不存在：${A}"; exit 1; }
[ -f "$S" ] || { echo "FAIL 说明不存在：${S}"; exit 1; }

DESK=/Volumes/nvme/Projects/tmux桌面端
[ -d "$DESK" ] || { echo "UNJUDGEABLE 对方仓不在 ${DESK}，核不了「建议是否指到他们的代码」"; exit 2; }

# ⛔ 红线：只读对方仓。答复里若出现我方改了他们文件的痕迹，直接红。
grep -qE '我(已|们)(改|修改|提交).*(tmux桌面端|corral-desktop)' "$A" && {
  echo "FAIL 答复里出现「我方改了对方仓」的表述——上游对他们只读，红线"; exit 1; }

python3 - "$A" "$DESK" <<'PY'
import traceback, sys as _s
def _hook(t, v, tb):
    # 🔴 判据自己崩了是不可判，⛔ 不是不通过（未捕获异常退出码恰好 1，会与真红撞码）。
    traceback.print_exception(t, v, tb); print("UNJUDGEABLE 判据自己跑不起来"); _s.exit(2)
_s.excepthook = _hook
import os, re, sys
txt = open(sys.argv[1], encoding="utf-8").read()
desk = sys.argv[2]
bad = []

# 六节：0 纠正 / ①②③④ / ⑤ Q5 / ⑥ 答不出
for key, name in ((r'##\s*0', "0 先纠正一件事"), (r'##\s*①', "① 等不等首帧"),
                  (r'##\s*②', "② reshape 不撕"), (r'##\s*③', "③ 用什么宽度渲染"),
                  (r'##\s*④', "④ 排布正确的判据"), (r'##\s*⑤', "⑤ Q5 缺陷"),
                  (r'##\s*⑥', "⑥ 我方答不出的")):
    if not re.search(key, txt, re.M):
        bad.append("缺节：%s" % name)

# 四条主问题各要有置信度
blocks = re.split(r'^##\s*([①②③④])', txt, flags=re.M)
secs = {blocks[i]: blocks[i+1] for i in range(1, len(blocks)-1, 2)}
conf = {"源码确定": 0, "需实测": 0, "查不出": 0}
for k in "①②③④":
    b = secs.get(k)
    if b is None:
        continue
    if "结论" not in b:
        bad.append("%s 没有【结论】" % k)
    m = re.search(r'置信度[^\n]*?(源码确定|需实测|查不出)', b)
    if not m:
        bad.append("%s 没有标置信度" % k); continue
    conf[m.group(1)] += 1
    if m.group(1) == "源码确定":
        refs = re.findall(r'([A-Za-z0-9_./-]+\.(?:go|kt|js|jsx|md)):(\d+)', b)
        if not refs:
            bad.append("%s 标了「源码确定」却没有任何 文件:行号" % k); continue
        for path, ln in refs:
            p = path if os.path.exists(path) else os.path.join(desk, path)
            if not os.path.exists(p):
                bad.append("%s 引用的文件不存在：%s" % (k, path)); continue
            try:
                total = sum(1 for _ in open(p, encoding="utf-8", errors="ignore"))
            except Exception:
                continue
            if int(ln) > total:
                bad.append("%s 引用 %s:%s 越界（共 %d 行）" % (k, path, ln, total))

# 必须指到他们的代码，否则等于只讲了自己
if not re.search(r'(TerminalView\.js|client\.js|TerminalPane\.jsx|CLIENT-CONTRACT\.md)', txt):
    bad.append("答复里没有指到对方任何一个文件 —— 他们要的是方向，不是我方独白")

# 必须标明读的是工作树还是 main（他们 main 上还带着坏改动）
if not re.search(r'(工作树|working tree|ca1f54c|6aa5921)', txt):
    bad.append("没写清引用的是他们的「工作树」还是「main」—— main 上仍带坏改动，会指导到 clone 不到的代码")

# ⑥ 不许空着
m6 = re.split(r'^##\s*⑥', txt, flags=re.M)
if len(m6) > 1 and len(m6[1].strip()) < 20:
    bad.append("⑥「我方答不出的」几乎是空的 —— 除非四条全「源码确定」，否则不该空")

if bad:
    print("FAIL 答复稿不合格：")
    for x in bad[:12]: print("  - " + x)
    sys.exit(1)
print("  ok   六节齐；四条置信度分布：源码确定 %d / 需实测 %d / 查不出 %d"
      % (conf["源码确定"], conf["需实测"], conf["查不出"]))
print("PASS 引用文件存在且行号不越界、指到了对方代码、标明了读的是哪个状态、⑥ 非空")
print("     ⚠️ 本判据⛔ 不判结论对错，也⛔ 不奖励「四条都源码确定」。")
sys.exit(0)
PY
