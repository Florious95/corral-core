#!/bin/sh
# //! purpose: 合取 SDK、fresh A/B/A/B raw 独立重算与 permanent fixture，不依赖旧临时 bypass root。
# //! contract: 0=三夹具四段每段n>=10且nearest-rank全格<=1.10；1=有效回退/伪造；2=环境/身份/样本不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-measure: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
for gate in baseline-bundle-successor5-sdk.sh baseline-bundle-successor3-measure.sh baseline-bundle-successor7-impl-bypass.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "$gate unsupported rc=$rc" ;; esac
done
printf '%s\n' "PASS baseline-bundle-successor7-measure: SDK, permanent fixture and fresh nearest-rank A/B/A/B gate pass"

