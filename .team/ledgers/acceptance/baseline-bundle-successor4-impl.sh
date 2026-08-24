#!/bin/sh
# //! purpose: successor4 组合无泄露 SDK 前置、bootstrap canonical/impl 真实门与 required 结构齿。
# //! contract: 0=真实交付与结构全绿；1=实现/结构被反证；2=SDK/fixture/量具/事实不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor4-impl: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor4-impl: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"

for gate in baseline-bundle-successor4-sdk.sh baseline-bundle-successor4-structure.sh baseline-bundle-successor3-impl.sh; do
    [ -r "$script_dir/$gate" ] && [ -s "$script_dir/$gate" ] || unjudgeable "required gate unavailable"
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "required gate rc=$rc" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor4-impl: exact required set, SDK and bootstrap real gates verified"
