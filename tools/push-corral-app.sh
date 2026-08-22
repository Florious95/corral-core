#!/bin/sh
# 把 corral-app 暂存工程推成远端分支并开 PR。$1 = 源目录（默认 wt-ca 里那份）。
# 🔴 源必须是**施工 worktree 里**那份，⛔ 不是仓根那份——仓根的要等 land 才更新，
#    2026-08-22 实撞：拿仓根旧版差点把带 includeBuild 的形态推上公开仓。
# 推之前四道自检，任何一条不过就拒推（判据不许绕）。
set -u
SRC="${1:-$(pwd)/.worktrees/wt-ca/.team/staging/corral-app}"
BR="${2:-feat/reference-build}"
[ -d "$SRC" ] || { echo "FAIL 源目录不存在：$SRC"; exit 1; }

# ① 源侧自检：形态必须是「只引用产物」
grep -q "includeBuild" "$SRC/settings.gradle.kts" && { echo "FAIL 源里还有 includeBuild，这是判据判死的形态"; exit 1; }
find "$SRC" -path "*core-protocol/src" -o -path "*core-terminal/src" -o -path "*core-conn/src" \
  | grep -q . && { echo "FAIL 源里带了核源码目录"; exit 1; }
grep -q "dev.agentmirror.core:core-protocol:" "$SRC/app/build.gradle.kts" \
  || { echo "FAIL 依赖没钉死 dev.agentmirror.core:<模块>:<版本>"; exit 1; }

T=$(mktemp -d "$(pwd)/.team/nodes/tmp-capp.XXXX") || exit 1
trap 'rm -rf "$T"' EXIT
git clone -q https://github.com/Florious95/corral-app "$T/repo" || { echo "FAIL clone 失败"; exit 2; }
cd "$T/repo" || exit 2
git checkout -qB "$BR" origin/main
rsync -a --delete --exclude '.git' --exclude '.gitignore' --exclude 'local.properties' \
  --exclude 'build/' --exclude '.gradle/' --exclude 'docs/' --exclude 'LICENSE' --exclude 'README.md' \
  "$SRC/" ./
git checkout -q origin/main -- docs LICENSE README.md 2>/dev/null
printf 'build/\n.gradle/\nlocal.properties\n*.apk\n' > .gitignore
git add -A

# ② 推之前再自检一遍（rsync 可能带进意外文件）
git ls-files | grep -Eiq "\.(env|jks|keystore|p12)$|local\.properties" && { echo "FAIL 暂存区混进凭据类文件"; exit 1; }
git diff --cached --quiet && { echo "SKIP 与远端 main 无差异，不开空 PR"; exit 0; }

git -c user.name="Florious95" -c user.email="casartekrupp@gmail.com" \
  commit -q -m "APP 壳：只引用 core 发布产物（⛔ 不带核源码）

自检通过：无 includeBuild / 无核源码目录 / 依赖钉死版本 / 无凭据类文件。"
git push -qf -u origin "$BR" || { echo "FAIL push 失败"; exit 2; }
gh pr view "$BR" -R Florious95/corral-app --json url --jq .url 2>/dev/null \
  || gh pr create -R Florious95/corral-app --base main --head "$BR" \
       --title "APP 壳：只引用 core 发布产物（⛔ 不带核源码）" \
       --body "自检：无 includeBuild / 无核源码 / 依赖钉死 dev.agentmirror.core:<模块>:<版本>。
⚠️ core 的 maven 分支未推前只能靠本地兜底构建；真机复验「秒开无空白」是最终判据。"
