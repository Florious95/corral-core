#!/bin/sh
# //! purpose: 验 apparatus-probe 独立坐标与 source/fixture/structure 关键事实。
# //! contract: 0=独立探针与真实门一致；1=遗漏/矛盾；2=目录/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor7-probe: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-probe: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve worktree root"
report="$repo_root/.team/nodes/baseline-bundle-successor7-probe/PROBE.md"
source_gate="$script_dir/baseline-bundle-successor7-owned-emulator.sh"
[ -e "$report" ] || fail "PROBE.md missing"
[ -r "$report" ] || unjudgeable "PROBE.md unreadable"
[ -s "$report" ] || fail "PROBE.md empty"
last=$(sed -n '$p' "$report" 2>/dev/null) || unjudgeable "cannot read PROBE.md verdict"
case "$last" in 'probe: pass') ;; 'probe: fail') fail "probe report says fail" ;; 'probe: unjudgeable') unjudgeable "probe report unjudgeable" ;; *) fail "bad probe verdict" ;; esac
for token in SUCCESSOR7_CONTINUITY SUCCESSOR7_APPARATUS_EVIDENCE SUCCESSOR7_IMPL_BYPASS SUCCESSOR7_STRUCTURE da46a6b2b 0df3562b7 wt-maple-core wt-s7-cedar wt-s7-orbit 'continuity=0' 'first_frontier=continuity+apparatus-test+apparatus-probe' 'device_started=false' 'mode0600' 'TERM grace' 'KILL grace' 'start identity' 'foreign_qemu_touched=false'; do
    grep -F "$token" "$report" >/dev/null 2>&1 || fail "PROBE.md missing $token"
done
[ -r "$source_gate" ] && [ -s "$source_gate" ] || unjudgeable "owned-emulator source unavailable"
sh -n "$source_gate" || fail "owned-emulator POSIX syntax invalid"
for snippet in 'invalid fixture mode selector' 'test override present in production count=' 'mode is not 0600' 'wait_pid_gone' 'qemu_start_now='; do
    grep -F "$snippet" "$source_gate" "$script_dir/baseline-bundle-successor7-apparatus.py" >/dev/null 2>&1 || fail "source guard missing $snippet"
done
for gate in baseline-bundle-successor7-structure.sh baseline-bundle-successor7-impl-bypass.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "$gate unsupported rc=$rc" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor7-probe: independent apparatus coordinates and permanent fixture agree"

