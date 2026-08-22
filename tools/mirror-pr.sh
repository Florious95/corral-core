#!/usr/bin/env bash
# //!
# //! purpose: 把本地 pr/* 分支经与 mirror-push 相同的过滤推到 corral-core，并用 gh 开/更新 PR
# //! contract:
# //!   provides:
# //!     - name: mirror-pr
# //!       what: 用法 bash tools/mirror-pr.sh <分支>...；过滤规则与 mirror-push.sh corral-core 段一致
# //! boundary:
# //!   - 只动 corral-core；main 也会同步推（PR 需要可比基线）
# //!   - PR 已存在则跳过创建只推分支
# //! maturity: wired
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GH="git@github.com:Florious95"
FR="$(python3 -c 'import git_filter_repo;print(git_filter_repo.__file__)')"
WORK=$(mktemp -d /tmp/mirror-pr.XXXXXX); trap 'rm -rf "$WORK"' EXIT
printf 'Claude <noreply@anthropic.com> Florious95 <281215401+Florious95@users.noreply.github.com>\n' > "$WORK/mailmap"
STRIP='msg = commit.message; import re; commit.message = re.sub(rb"\n?Co-Authored-By: Claude[^\n]*\n?", b"\n", msg)'
git clone --no-hardlinks --quiet "$SRC" "$WORK/core"
cd "$WORK/core"
for BR in "$@"; do git branch "$BR" "origin/$BR"; done
python3 "$FR" --force --invert-paths \
  --path .team/runtime/ --path .team/logs/ --path .team/__pycache__/ \
  --path .team/leader-inbox.log --path .team/watchdog.log \
  --path e2e/artifacts/ --path e2e/bin/ --path server/ \
  --path-glob '*/build/*' --path-glob '*/.gradle/*' \
  --path-glob '*.env' --path-glob '*.apk' --path-glob 'agentmirrord*' \
  --path-glob '*/tmp/daemon.log' \
  --mailmap "$WORK/mailmap" --commit-callback "$STRIP" >/dev/null
git remote add origin "$GH/corral-core.git"
# 🔴 次序是**先分支+开 PR，后推 main**（2026-08-23 实撞后改）。
# 原来是先推 main：一旦本地已经把分支并进 main，远端 main 就先拿到了那些提交，
# 等到开 PR 时 `gh` 报 "No commits between main and <BR>" —— 分支推上去了，PR 却立不起来，
# 远端只留下一条孤儿分支。用户已为此点名三次「一事一 PR」没成立。
# 反过来：先开 PR（此时远端 main 还没有这些提交，diff 成立），再推 main，
# 该 PR 会被 GitHub 自动判为 merged —— 一事一 PR 一闭环。
for BR in "$@"; do
  git push --force origin "$BR"
  gh pr view "$BR" --repo Florious95/corral-core >/dev/null 2>&1 && { echo "PR 已存在：$BR"; continue; }
  gh pr create --repo Florious95/corral-core --base main --head "$BR" \
    --title "$BR" --body "$(git log origin/main.."$BR" --format='- %s' 2>/dev/null | head -5)

由账本编排驱动：一格一分支，判据日志与评审 verdict 见仓内 .team/nodes/。

🤖 Generated with [Claude Code](https://claude.com/claude-code)" || echo "开 PR 失败：$BR"
done
git push --force -u origin main
echo "==> mirror-pr 完成"
