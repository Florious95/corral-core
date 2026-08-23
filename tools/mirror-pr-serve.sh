#!/usr/bin/env bash
# //! purpose: server 侧分支经 subdirectory-filter 推 corral-serve 并开 PR（与 mirror-push serve 段同规则）
# //! maturity: wired
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GH="git@github.com:Florious95"
FR="$(python3 -c 'import git_filter_repo;print(git_filter_repo.__file__)')"
WORK=$(mktemp -d /tmp/mirror-prs.XXXXXX); trap 'rm -rf "$WORK"' EXIT
printf 'Claude <noreply@anthropic.com> Florious95 <281215401+Florious95@users.noreply.github.com>\n' > "$WORK/mailmap"
STRIP='msg = commit.message; import re; commit.message = re.sub(rb"\n?Co-Authored-By: Claude[^\n]*\n?", b"\n", msg)'
git clone --no-hardlinks --quiet "$SRC" "$WORK/serve"
cd "$WORK/serve"
for BR in "$@"; do git branch "$BR" "origin/$BR"; done
python3 "$FR" --force --subdirectory-filter server >/dev/null
python3 "$FR" --force --invert-paths --path-glob 'agentmirrord*' --path-glob '*.env' \
  --mailmap "$WORK/mailmap" --commit-callback "$STRIP" >/dev/null
cp "$SRC/LICENSE" ./LICENSE; git add LICENSE
GIT_AUTHOR_DATE='2026-08-14T16:35:00+00:00' GIT_COMMITTER_DATE='2026-08-14T16:35:00+00:00' \
git -c user.name=Florious95 -c user.email=281215401+Florious95@users.noreply.github.com \
    commit -q -m "补入 Apache-2.0 LICENSE（拆仓时随服务端一起带上）" || true
git remote add origin "$GH/corral-serve.git"
# 🔴 次序＝先分支+开 PR，后推 main（2026-08-23 与 mirror-pr.sh 同源修，⛔ 别再只改一个）。
# 先推 main 的话，本地已并线时远端 main 就先拿到了那些提交，`gh` 会报
# "No commits between main and <BR>"：分支推上去了、PR 却立不起来，只剩一条孤儿分支。
for BR in "$@"; do
  git push --force origin "$BR"
  gh pr view "$BR" --repo Florious95/corral-serve >/dev/null 2>&1 && { echo "PR 已存在：$BR"; continue; }
  gh pr create --repo Florious95/corral-serve --base main --head "$BR" \
    --title "$BR" --body "$(git log main.."$BR" --format='- %s' | head -5)

由账本 hl1-v1 驱动：一格一分支，异源评审与判据日志见 corral-core 仓 .team/nodes/。

🤖 Generated with [Claude Code](https://claude.com/claude-code)" || echo "开 PR 失败：$BR"
done
git push --force -u origin main
echo "==> mirror-pr-serve 完成"
