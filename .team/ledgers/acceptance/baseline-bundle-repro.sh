#!/bin/sh
# //! purpose: 两次运行真实 probe，经固定 schema 转译“预期旧红”为 repro acceptance 通过。
# //! contract: 0=预期旧红证据齐；1=非预期/伪造/矛盾；2=probe/证据/provenance 不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-repro: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-repro: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
probe="$script_dir/baseline-bundle-real-chain-probe.sh"
translate="$script_dir/baseline-bundle-repro-translate.sh"
node="$repo_root/.team/nodes/baseline-bundle-repro"
report_json="$node/REPRO.json"
report_md="$node/REPRO.md"
tmp="$node/tmp/acceptance-$$"

[ -r "$probe" ] || unjudgeable "real-chain probe unreadable"
[ -r "$translate" ] || unjudgeable "repro translator unreadable"
mkdir -p "$tmp" 2>/dev/null || unjudgeable "cannot create node-local acceptance temp"
cleanup() { [ ! -d "$tmp" ] || find "$tmp" -depth -delete 2>/dev/null || :; }
trap cleanup EXIT HUP INT TERM

run1_output=$(sh "$probe" 2>&1)
rc1=$?
printf '%s\n' "$run1_output" > "$tmp/run1.raw"
printf '%s\n' "$run1_output"
printf '%s\n' "$run1_output" | sed -n 's/^REAL_CHAIN_PROBE_JSON //p' > "$tmp/run1.json"
run2_output=$(sh "$probe" 2>&1)
rc2=$?
printf '%s\n' "$run2_output" > "$tmp/run2.raw"
printf '%s\n' "$run2_output"
printf '%s\n' "$run2_output" | sed -n 's/^REAL_CHAIN_PROBE_JSON //p' > "$tmp/run2.json"

sh "$translate" "$tmp/run1.json" "$rc1" "$tmp/run2.json" "$rc2" "$report_json"
translate_rc=$?
case "$translate_rc" in 0) ;; 1) fail "expected-red translation rejected" ;; 2) unjudgeable "expected-red translation lacks judgeable evidence" ;; *) unjudgeable "translator returned rc=$translate_rc" ;; esac
[ -e "$report_md" ] || fail "missing human-readable REPRO.md"
[ -r "$report_md" ] || unjudgeable "REPRO.md unreadable"
[ -s "$report_md" ] || fail "REPRO.md empty"
printf '%s\n' "PASS baseline-bundle-repro: two real expected-red probes translated to acceptance exit 0 with REPRO.json provenance"
