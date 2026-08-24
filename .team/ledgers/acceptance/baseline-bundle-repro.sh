#!/bin/sh
# //! purpose: 以真实 perf-regress 现场先红证明缺冻结 A 会落入 failed_retryable park。
# //! contract: 0=真实旧链红已复现并留档；1=复现/交付失败；2=环境或事实不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-repro: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-repro: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
probe="$script_dir/baseline-bundle-real-chain-probe.sh"
report="$repo_root/.team/nodes/baseline-bundle-repro/REPRO.md"

[ -r "$probe" ] || unjudgeable "real-chain probe unreadable"
sh "$probe"
probe_rc=$?
case "$probe_rc" in
    1) printf '%s\n' "BASELINE_MISSING_DEADLOCK_REPRO real_chain=observed legacy_state=failed_retryable frontier=empty verify=dependency_unsatisfied" ;;
    0) fail "legacy chain is already cured; this repro task must retain its earlier red evidence" ;;
    2) unjudgeable "real perf-regress chain cannot be judged" ;;
    *) unjudgeable "unsupported real-chain probe rc=$probe_rc" ;;
esac
[ -e "$report" ] || fail "missing REPRO.md after behavioral probe"
[ -r "$report" ] || unjudgeable "REPRO.md unreadable"
[ -s "$report" ] || fail "REPRO.md empty"
for token in 'ledger.perf-regress.v1' 'revision: 4' 'failed_retryable' 'frontier: []' 'dependency_unsatisfied' 'measurement: unjudgeable' 'blocked_missing_baseline' 'recover_exact_artifact' 'rebaseline_with_equivalence_proof'; do
    grep -F "$token" "$report" >/dev/null 2>&1 || fail "REPRO.md omits $token"
done
printf '%s\n' "PASS baseline-bundle-repro: real missing-A failed_retryable park reproduced"

