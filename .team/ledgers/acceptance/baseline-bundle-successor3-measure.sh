#!/bin/sh
# //! purpose: successor3 保留 measure 四态，并把现存证据中的空 raw/伪造 runner provenance 区分为交付失败。
# //! contract: 0=真实有效样本全格<=1.10；1=回归/伪造/证据矛盾；2=环境、身份或样本缺失不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor3-measure: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor3-measure: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
base_gate="$script_dir/baseline-bundle-measure.sh"
command -v grep >/dev/null 2>&1 || unjudgeable "grep unavailable"
[ -e "$base_gate" ] || unjudgeable "base measure gate missing"
[ -r "$base_gate" ] && [ -s "$base_gate" ] || unjudgeable "base measure gate unreadable or empty"
output=$(sh "$base_gate" 2>&1)
rc=$?
printf '%s\n' "$output"
case "$rc" in
    0) exit 0 ;;
    1) exit 1 ;;
    2)
        if printf '%s\n' "$output" | grep -F 'empty raw log' >/dev/null 2>&1; then
            fail "claimed raw evidence exists but is empty"
        fi
        if printf '%s\n' "$output" | grep -F 'runner provenance mismatch' >/dev/null 2>&1; then
            fail "claimed runner provenance does not match fixed runner bytes"
        fi
        exit 2
        ;;
    *) unjudgeable "base measure gate unsupported rc=$rc" ;;
esac
