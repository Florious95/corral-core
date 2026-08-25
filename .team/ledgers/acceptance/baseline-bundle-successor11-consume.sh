#!/bin/sh
# //! purpose: 只读消费 successor10 r5 四个 succeeded command 与原 worktree 冻结产物，不重派、不起设备。
# //! contract: 0=运行态/尝试/产物/原 required 全部闭合；1=有效反证或伪造；2=冻结事实/量具缺失。
# //! boundary: 固定 main 与原 worktree 路径；apparatus 只验归档 JSON+permanent fixture，不查 live adb/emulator/qemu。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor11-consume: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor11-consume: $*" >&2; exit 2; }

[ "$#" -eq 1 ] || fail "expected exactly one consume kind"
kind=$1
case "$kind" in continuity|apparatus-test|apparatus-probe|apparatus) ;; *) fail "unknown consume kind" ;; esac
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
main_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve main repository"
helper="$script_dir/baseline-bundle-successor11-consume.py"
for tool in git python3 sh; do command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"; done
[ -r "$helper" ] && [ -s "$helper" ] || unjudgeable "consume validator unavailable"

for object in \
    'ebd0dc5c285ee65244824b99db6667a1bc569c83:.team/ledgers/acceptance/baseline-bundle-successor11-verify.sh' \
    '3597b823204c7d25d5a77367bf2022347532e5d3:.team/nodes/baseline-bundle-successor11-wt-preflight/VERDICT.md' \
    '13c301fd086092b02e1cb8535d1eff38ffcf0173:.team/ledgers/baseline-bundle-successor10-v1.json' \
    '7c1a856ba0043c87b1aeb9ed8ffac0fefe9ebfce:.team/nodes/baseline-bundle-successor10-verify-diagnosis/VERDICT.md'
do
    git -C "$main_root" cat-file -e "$object" 2>/dev/null || unjudgeable "frozen provenance unavailable"
done

python3 "$helper" --main-root "$main_root" --kind "$kind"
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "consume validator unsupported rc=$rc" ;; esac

case "$kind" in
continuity)
    target="$main_root/.worktrees/wt-maple-core"
    gate=.team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh
    ;;
apparatus-test)
    target="$main_root/.worktrees/wt-s7-cedar"
    gate=.team/ledgers/acceptance/baseline-bundle-successor7-test.sh
    ;;
apparatus-probe)
    target="$main_root/.worktrees/wt-s7-orbit"
    gate=.team/ledgers/acceptance/baseline-bundle-successor7-probe.sh
    ;;
apparatus)
    target="$main_root/.worktrees/wt-maple-core"
    gate=.team/ledgers/acceptance/baseline-bundle-successor7-impl-bypass.sh
    ;;
esac
[ -r "$target/$gate" ] && [ -s "$target/$gate" ] || unjudgeable "original required gate unavailable"
output=$(CDPATH='' cd "$target" 2>/dev/null && sh "$gate" 2>&1)
rc=$?
printf '%s\n' "$output"
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "original required unsupported rc=$rc" ;; esac
printf '%s\n' "PASS baseline-bundle-successor11-consume: $kind successor10 r5 success consumed without redispatch or device action"
