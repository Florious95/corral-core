#!/usr/bin/env bash
# 把本地单仓镜像推到 GitHub 上的三个仓库（云端备份，防信息丢失）。
#
# 为什么需要这个脚本：
#   本地是单仓，远端是拆开的三仓，两边历史结构不同 —— 不能直接 git push。
#   每次都要重新过滤一遍再强推，手工做必然会忘、会做错、会漏掉排除项。
#
# 排除项及其理由（改之前先读）：
#   .team/ 除 evidence 外全部  编排与席位配置/运行时，用户 2026-08-14 裁定「不用上传」。
#                    ⚠️ .team/evidence/ 是例外，必须上传 —— CLAUDE.md 原文：
#                    「任务状态的唯一权威是 taskbook.yaml + .team/evidence/」。
#                    taskbook 说「做了什么」，evidence 说「凭什么算做完」（根因/判据/实测数字/谁验的）。
#                    只传前者 = 备份了结论没备份依据。2026-08-14 首次推送漏了这个，用户当场发现。
#                    .team/runtime 单独 1.6 GB / 69189 文件，.team/logs 里 daemon 明文打配对 token —— 这两个永不上传。
#   agents/          席位角色文件
#   e2e/artifacts/   99 MB 的截图录屏，一次性证据不是代码
#   */build/ .gradle 构建产物；历史里曾有 178 MB 的 gradle jar，超过 GitHub 单文件 100 MB 硬限
#   *.env            席位 API key。三个 .env 从基线 commit 就在历史里，
#                    d6f450e16「仓库卫生」只停止跟踪、没抹掉历史 —— 这里是唯一把它们挡在远端外面的地方
#   agentmirrord*    编译出来的 Go 二进制，29 MB × 多个版本
#
# 用法：bash tools/mirror-push.sh
set -euo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd)"
FR="$(python3 -c 'import git_filter_repo;print(git_filter_repo.__file__)')"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

GH=git@github.com:Florious95
# 推到哪条远端分支。PR 流程用非 main 分支当 base，故参数化；缺省仍是 main。
MIRROR_BRANCH="${MIRROR_BRANCH:-main}"
ME='Florious95 <281215401+Florious95@users.noreply.github.com>'

# 署名归位（用户 2026-08-14 裁定「Contributor 应该是我」）。
# 本地 249 个 commit 的 author 是 alauda@MacBook-Pro.local —— 本机主机名邮箱，
# GitHub 关联不到任何账号，于是贡献者显示成一个陌生人而不是仓库主人。
# 这里改的只是【推上去的那一份】；本地 sha 一律不动 ——
# taskbook / .team/evidence / HANDOFF 全靠 sha 互相引用，重写本地历史会把这条链整体打断。
cat > "$WORK/mailmap" <<MAILMAP
$ME Alauda <alauda@MacBook-Pro.local>
$ME Alauda <codebaton@team-agent.net>
MAILMAP

# 同时摘掉 Co-Authored-By: Claude —— 210 个 commit 带着它，
# 不摘的话 Claude 会以共同作者身份出现在贡献者列表里。
STRIP_COAUTHOR='commit.message = b"\n".join(
    l for l in commit.message.split(b"\n") if not l.startswith(b"Co-Authored-By: Claude")
).rstrip() + b"\n"'

# 凭据兜底闸：过滤完还能找到 .env / 密钥文件就中止，绝不推上去。
# 这条不是冗余 —— 过滤规则是人写的，人会写错，而推上去的历史撤不回来。
guard() {
  local leaked
  leaked=$(git log --all --diff-filter=A --name-only --pretty=format: \
           | grep -E '\.env$|tailscale_keys|\.credentials' | sort -u || true)
  if [ -n "$leaked" ]; then
    echo "中止：过滤后历史里仍有凭据文件：" >&2
    echo "$leaked" >&2
    exit 1
  fi
}


# 祖先闸：filter-repo 是确定性映射，所以远端同名分支必须是本次过滤结果的祖先。
# 不是 ⇒ 过滤规则变过（或远端被别处改过），这一推会静默重写远端历史 —— 中止。
ancestor_gate() {
  local remote_head
  remote_head=$(git ls-remote origin "refs/heads/$MIRROR_BRANCH" 2>/dev/null | cut -f1)
  [ -z "$remote_head" ] && { echo "   远端 $MIRROR_BRANCH 不存在，按新建分支推送"; return 0; }
  if git cat-file -e "$remote_head^{commit}" 2>/dev/null && \
     git merge-base --is-ancestor "$remote_head" HEAD; then
    echo "   祖先闸通过：远端 $MIRROR_BRANCH @ ${remote_head:0:9} 是本次结果的祖先"
  else
    echo "中止：远端 $MIRROR_BRANCH @ ${remote_head:0:9} 不是过滤结果的祖先，推上去会重写远端历史" >&2
    exit 1
  fi
}

echo "==> corral-core（当前 App + 需求维基 + 任务书 + 文档）"
git clone --no-hardlinks --quiet "$SRC" "$WORK/core"
cd "$WORK/core"
# .team/ 下逐项点名排除，而不是整目录排除 —— 目的是把 .team/evidence/ 留下来。
# 用点名而不是 glob：glob 会在新增子目录时静默把它一起带上去，而那里面可能有凭据。
# 新增 .team 子目录时必须显式决定它的去留，这个"必须显式"就是这里的设计意图。
TEAM_EXCLUDE=(
  --path .team/current/ --path .team/runtime/ --path .team/logs/ --path .team/nodes/
  --path .team/recheck-20260811/ --path .team/__pycache__/ --path .team/adjudicator/
  --path .team/verify-t3/ --path .team/ta --path .team/orch.wake
  --path .team/orchestrator.py --path .team/orchestrator-state.json
  --path .team/leader-sink.py --path .team/leader-inbox.log
  --path .team/llm-leader-boot.md --path .team/escalations-for-human.md
  --path .team/outbox-relay.sh --path .team/prod-daemon-launch.sh
  --path .team/watchdog.py --path .team/watchdog.sh --path .team/watchdog.log
  --path .team/watchdog-supervisor.sh
  --path .team/grok/ --path .team/dynamic-role-files/
)

python3 "$FR" --force --invert-paths \
  "${TEAM_EXCLUDE[@]}" \
  --path agents/ --path e2e/artifacts/ --path e2e/bin/ --path server/ \
  --path-glob '*/build/*' --path-glob '*/.gradle/*' \
  --path-glob '*.env' --path-glob '*.apk' \
  --mailmap "$WORK/mailmap" --commit-callback "$STRIP_COAUTHOR" >/dev/null
guard
git remote add origin "$GH/corral-core.git"
git branch -M "$MIRROR_BRANCH"
ancestor_gate
git push --force -u origin "$MIRROR_BRANCH"

echo "==> corral-serve（服务端 daemon）"
git clone --no-hardlinks --quiet "$SRC" "$WORK/serve"
cd "$WORK/serve"
python3 "$FR" --force --subdirectory-filter server >/dev/null
python3 "$FR" --force --invert-paths --path-glob 'agentmirrord*' --path-glob '*.env' \
  --mailmap "$WORK/mailmap" --commit-callback "$STRIP_COAUTHOR" >/dev/null
guard
cp "$SRC/LICENSE" ./LICENSE
git add LICENSE
git -c user.name=Florious95 -c user.email=281215401+Florious95@users.noreply.github.com \
    commit -q -m "补入 Apache-2.0 LICENSE（拆仓时随服务端一起带上）" || true
git remote add origin "$GH/corral-serve.git"
git branch -M "$MIRROR_BRANCH"
ancestor_gate
git push --force -u origin "$MIRROR_BRANCH"

# corral-app 是下一代 UI 的独立仓库，不由本单仓派生，故不在此镜像。
echo "==> 完成。corral-app 独立维护，本脚本不动它。"
