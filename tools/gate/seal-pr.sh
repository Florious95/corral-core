#!/usr/bin/env bash
# tools/gate/seal-pr.sh —— 把某格 worktree 里的改动【封版】成它自己分支上的 commit。
#
# 为什么需要：编排引擎的派单信封里有一条硬约束「不跑 git commit / push」，
# 于是席位的改动只以【未提交工作区】的形式存在，分支上一个 commit 都没有 ——
# 没有 PR 可提、没有 diff 可审、也没有东西可以 land。
# 评审必须审「最终会并线的那一份」，而未提交的工作区不保证这个性质。
# ⇒ 由 leader 侧在【评审席被派单之前】把 sha 固定下来。
#
# ⚠️ 这是绕法：判据里做了有副作用的事（提交），不是纯判据。
#    对方修好「格完成后产出可引用 commit」之后，本脚本与对应判据一并拆掉。
#    报告见 .team/artifacts/ledger-p0-派单硬约束禁止commit导致PR链断裂-20260821.md
#
# 用法：bash tools/gate/seal-pr.sh <worktree 绝对路径> <分支名>
# 退出码：0=分支上有非空提交；1=封版失败或提交后仍为空。
set -u
WT="${1:?worktree}"; BR="${2:?branch}"
cd "$WT" || { echo "worktree 不存在：$WT" >&2; exit 1; }

CUR=$(git branch --show-current 2>/dev/null)
DIRTY0=$(git status --porcelain | wc -l | tr -d ' ')
echo "seal-pr: worktree=$WT 当前分支=${CUR:-<detached>} 目标分支=$BR 待提交=$DIRTY0"
if [ "$CUR" != "$BR" ]; then
  # 🔴 2026-08-21 实撞：本脚本原来无条件 checkout，会把工作树从【正在干活的席位】手里夺走。
  # 分支不符 + 工作区有未提交改动 ⇒ 说明席位还在动它（或做在了别的分支上）⇒ 响亮失败，⛔ 不夺树。
  if [ "$DIRTY0" != "0" ]; then
    echo "红：worktree 在 $CUR 且有 $DIRTY0 项未提交改动，与目标分支 $BR 不符。" >&2
    echo "    ⛔ 拒绝 checkout —— 那会把树从正在干活的席位手里夺走。" >&2
    echo "    人工判断：改动属于哪一格？属于本格就把分支指过去，不属于就查席位为什么跑到别的分支。" >&2
    exit 1
  fi
  git show-ref --verify --quiet "refs/heads/$BR" && git checkout "$BR" || git checkout -b "$BR"
  echo "seal-pr: 工作区干净，已切到 $(git branch --show-current)"
fi

DIRTY=$(git status --porcelain | wc -l | tr -d ' ')
echo "seal-pr: 工作区待提交条目=$DIRTY"
if [ "$DIRTY" != "0" ]; then
  git add -A
  git -c user.name=Florious95 -c user.email=281215401+Florious95@users.noreply.github.com \
      commit -q -m "[$BR] 席位交付封版（leader 代提交：引擎硬约束禁止席位 commit）"
  echo "seal-pr: 已提交 $(git rev-parse --short HEAD)"
fi

BASE=$(git merge-base main HEAD 2>/dev/null || echo "")
N=$(git rev-list --count "${BASE:-main}..HEAD" 2>/dev/null || echo 0)
echo "seal-pr: 分支超出 main 的提交数=$N  head=$(git rev-parse --short HEAD)"
if [ "$N" -lt 1 ]; then
  echo "红：封版后分支上仍无提交 —— 这一格没有可引用的交付物" >&2
  exit 1
fi
git --no-pager log --oneline "${BASE:-main}..HEAD" | head -5
exit 0
