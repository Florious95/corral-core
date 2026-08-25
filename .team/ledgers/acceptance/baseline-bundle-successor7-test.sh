#!/bin/sh
# //! purpose: 验 apparatus-test RED 索引，并重跑无设备 override/mode/timeout 与 permanent fixture 齿。
# //! contract: 0=RED与真实齿全绿；1=交付/行为反证；2=目录/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor7-test: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-test: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve worktree root"
report="$repo_root/.team/nodes/baseline-bundle-successor7-test/RED.md"
[ -e "$report" ] || fail "RED.md missing"
[ -r "$report" ] || unjudgeable "RED.md unreadable"
[ -s "$report" ] || fail "RED.md empty"
last=$(sed -n '$p' "$report" 2>/dev/null) || unjudgeable "cannot read RED.md verdict"
case "$last" in 'test: pass') ;; 'test: fail') fail "test report says fail" ;; 'test: unjudgeable') unjudgeable "test report unjudgeable" ;; *) fail "bad test verdict" ;; esac
for token in SUCCESSOR7_EMULATOR_EVIDENCE SUCCESSOR7_IMPL_BYPASS SUCCESSOR7_STRUCTURE invalid_mode_exit=1 production_test_override_exit=1 production_empty_test_override_exit=1 unknown_test_override_exit=1 evidence_mode_0600=true derived_mode_0600=true evidence_0644_exit=1 bounded_term_exit=2 forced_runner_exit=2 forced_runner_cleanup=true foreign_qemu_touched=false permanent_forged=1 permanent_missing=2 da46a6b2b 0df3562b7; do
    grep -F "$token" "$report" >/dev/null 2>&1 || fail "RED.md missing $token"
done
for gate in baseline-bundle-successor7-structure.sh baseline-bundle-successor7-emulator-regression.sh baseline-bundle-successor7-bypass-regression.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "$gate unsupported rc=$rc" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor7-test: isolated apparatus red/green and structure teeth pass"

