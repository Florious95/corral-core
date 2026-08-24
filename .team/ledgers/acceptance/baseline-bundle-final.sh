#!/bin/sh
# //! purpose: 严格合取 bundle、用户 gate、旧链迁移和 fresh 性能门的独立终审。
# //! contract: 0=根治链完成；1=有效反证；2=环境或证据不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-final: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-final: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
node="$repo_root/.team/nodes/baseline-bundle-final"
[ -e "$node/VERDICT.md" ] || fail "missing $node/VERDICT.md"
[ -r "$node/VERDICT.md" ] || unjudgeable "unreadable $node/VERDICT.md"
[ -s "$node/VERDICT.md" ] || fail "empty $node/VERDICT.md"
last=$(sed -n '$p' "$node/VERDICT.md" 2>/dev/null) || unjudgeable "cannot read final verdict"
case "$last" in 'verdict: pass') ;; 'verdict: fail') fail "final verifier reports fail" ;; 'verdict: unjudgeable') unjudgeable "final verifier could not judge" ;; *) fail "bad final verdict" ;; esac
for gate in baseline-bundle-verify.sh baseline-bundle-user-gate.sh baseline-bundle-migrate.sh baseline-bundle-measure.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "$gate returned rc=$rc" ;; esac
done
for f in "$node/EVIDENCE-MATRIX.md" "$node/MUTATION.md"; do
    [ -e "$f" ] || fail "missing $f"
    [ -r "$f" ] || unjudgeable "unreadable $f"
    [ -s "$f" ] || fail "empty $f"
done
for word in blocked_missing_baseline recover_exact_artifact rebaseline_with_equivalence_proof normalized_runtime_sha256 archive_restore user_gate ledger.perf-regress.v1 1.10 破坏齿; do
    grep -F "$word" "$node/EVIDENCE-MATRIX.md" "$node/MUTATION.md" >/dev/null 2>&1 || fail "final evidence omits $word"
done
printf '%s\n' "PASS baseline-bundle-final: immutable recoverable baseline, migration, fresh lab gate and user gold all pass"
