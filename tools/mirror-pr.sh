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
  --mailmap "$WORK/mailmap" --commit-callback "$STRIP" >/dev/null
git remote add origin "$GH/corral-core.git"
git push --force -u origin main
for BR in "$@"; do
  git push --force origin "$BR"
  gh pr view "$BR" --repo Florious95/corral-core >/dev/null 2>&1 && { echo "PR 已存在：$BR"; continue; }
  gh pr create --repo Florious95/corral-core --base main --head "$BR" \
    --title "$BR" --body "$(git log main.."$BR" --format='- %s' | head -5)

由账本 hl1-v1 驱动：一格一分支，异源评审 verdict 与判据日志见仓内 .team/nodes/。

🤖 Generated with [Claude Code](https://claude.com/claude-code)" || echo "开 PR 失败：$BR"
done
echo "==> mirror-pr 完成"
