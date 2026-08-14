#!/usr/bin/env bash
# 把本地单仓镜像推到 GitHub 上的三个仓库（云端备份，防信息丢失）。
#
# 为什么需要这个脚本：
#   本地是单仓，远端是拆开的三仓，两边历史结构不同 —— 不能直接 git push。
#   每次都要重新过滤一遍再强推，手工做必然会忘、会做错、会漏掉排除项。
#
# 排除项及其理由（改之前先读）：
#   .team/ agents/   编排与席位配置，用户 2026-08-14 裁定「不用上传，没有版本管理需求」
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

echo "==> corral-core（当前 App + 需求维基 + 任务书 + 文档）"
git clone --no-hardlinks --quiet "$SRC" "$WORK/core"
cd "$WORK/core"
python3 "$FR" --force --invert-paths \
  --path .team/ --path agents/ --path e2e/artifacts/ --path e2e/bin/ --path server/ \
  --path-glob '*/build/*' --path-glob '*/.gradle/*' \
  --path-glob '*.env' --path-glob '*.apk' >/dev/null
guard
git remote add origin "$GH/corral-core.git"
git branch -M main
git push --force -u origin main

echo "==> corral-serve（服务端 daemon）"
git clone --no-hardlinks --quiet "$SRC" "$WORK/serve"
cd "$WORK/serve"
python3 "$FR" --force --subdirectory-filter server >/dev/null
python3 "$FR" --force --invert-paths --path-glob 'agentmirrord*' --path-glob '*.env' >/dev/null
guard
cp "$SRC/LICENSE" ./LICENSE
git add LICENSE
git -c user.name=Alauda -c user.email=codebaton@team-agent.net \
    commit -q -m "补入 Apache-2.0 LICENSE（拆仓时随服务端一起带上）" || true
git remote add origin "$GH/corral-serve.git"
git branch -M main
git push --force -u origin main

# corral-app 是下一代 UI 的独立仓库，不由本单仓派生，故不在此镜像。
echo "==> 完成。corral-app 独立维护，本脚本不动它。"
