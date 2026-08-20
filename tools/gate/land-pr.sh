#!/usr/bin/env bash
# tools/gate/land-pr.sh —— 评审 supports 之后把分支并进 main（leader 侧动作，做成判据以消除竞态）。
#
# 为什么做成判据：land 是 leader 的活（⛔ 不由产出方顺手完成），但如果靠人卡在
# 「评审通过」与「下一格开审」之间手工并线，就会出现竞态 ——
# 2026-08-21 实撞：pr/e2-composer 背着两个 E1 提交（seal 无差别提交 + 链内共享 worktree），
# 而 E1 当时是 refutes ⇒ 评审席只能按「改动超出本格范围」否决 compose。
# 把 land 绑在评审格的判据上，顺序就由依赖图保证，不靠人守时。
#
# 🔴 安全闸：本脚本【自己】再验一次 verdict 必须是 supports 才并线 ——
#    判据执行顺序不保证，不能假定 A-*-rv-pass 一定先跑过。
#
# 用法：bash tools/gate/land-pr.sh <分支> <verdict 文件绝对路径>
set -u
BR="${1:?branch}"; V="${2:?verdict}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO" || exit 1

[ -f "$V" ] || { echo "红：verdict 不存在 $V" >&2; exit 1; }
FIRST=$(head -1 "$V" | tr -d '\r')
echo "land-pr: branch=$BR verdict=$FIRST"
[ "$FIRST" = "VERDICT: supports" ] || { echo "红：verdict 不是 supports，⛔ 拒绝并线" >&2; exit 1; }

# 并线互斥：多个评审格可能同时判绿，git index 只有一把
LOCK="$REPO/.team/nodes/_driver/.land.lock"
for _ in $(seq 1 120); do mkdir "$LOCK" 2>/dev/null && break; sleep 2; done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
[ -d "$LOCK" ] || { echo "红：拿不到并线锁" >&2; exit 1; }

git rev-parse --verify --quiet "refs/heads/$BR" >/dev/null || { echo "红：分支不存在 $BR" >&2; exit 1; }
# 🔴 顺序要紧：幂等检查必须【先】做。
# 2026-08-21 实撞：原来先查「待并提交数 >= 1」再查 is-ancestor，
# 于是一个【已经并过】的分支 N=0，走进「没有超出 main 的提交」判红 ——
# 重跑一条已成功的判据反而失败，幂等性被顺序毁掉。
if git merge-base --is-ancestor "$BR" main; then
  echo "land-pr: ${BR} 已在 main 里（is-ancestor），无需重复并线"; exit 0
fi

N=$(git rev-list --count main.."$BR"); echo "land-pr: 待并提交数=$N"
[ "$N" -ge 1 ] || { echo "红：分支存在但没有超出 main 的提交，且不是 main 的祖先 —— 需人工查" >&2; exit 1; }
# 仓根常有未跟踪的同名产物（席位往 worktree 和仓根各写了一份说明.md），
# 会让 merge 以「untracked working tree files would be overwritten」中止。
# ⛔ 不盲删：逐个与分支上的版本比对，【逐字节相同】才移走，不同就响亮失败交人判断。
clear_untracked() {
  local out paths f tmp
  out=$(git merge --no-ff -m "land ${BR}（判据全绿 + 异源评审 VERDICT: supports）" "$BR" 2>&1) && { echo "$out"; return 0; }
  echo "$out"
  case "$out" in
    *"untracked working tree files would be overwritten"*) ;;
    *) return 1 ;;
  esac
  paths=$(printf '%s\n' "$out" | sed -n 's/^\t\(.*\)$/\1/p')
  [ -n "$paths" ] || return 1
  tmp=$(mktemp -d)
  printf '%s\n' "$paths" | while IFS= read -r f; do
    [ -n "$f" ] || continue
    if git show "$BR:$f" > "$tmp/x" 2>/dev/null && cmp -s "$tmp/x" "$f"; then
      echo "land-pr: 仓根未跟踪副本与分支版本逐字节相同，移走：$f"
      mkdir -p "$tmp/bak/$(dirname "$f")" && mv "$f" "$tmp/bak/$f"
    else
      echo "红：仓根未跟踪文件 $f 与分支版本【不同】，⛔ 不删，需人工判断" >&2
      exit 1
    fi
  done || return 1
  echo "land-pr: 被移走的副本备份在 $tmp/bak（⛔ 未删除）"
  git merge --no-ff -m "land ${BR}（判据全绿 + 异源评审 VERDICT: supports）" "$BR"
}
clear_untracked || {
  echo "红：合并失败，⛔ 已中止，需人工处理" >&2; git merge --abort 2>/dev/null; exit 1; }
echo "land-pr: 已并线 $(git rev-parse --short HEAD)"
git --no-pager log --oneline -1
