#!/bin/sh
# //! purpose: 从 Git 固定路径复用历史 impl-bypass 夹具，并用 successor6 真实 projection 门重做绿控/伪造拒绝。
# //! contract: 0=历史 provenance 与摘要相符、绿控0、伪造1、缺失2；1=伪造被放行或绿控被拒；2=固定夹具/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor7-impl-bypass: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-impl-bypass: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve worktree root"
fixture="$script_dir/fixtures/baseline-bundle-successor7/impl-bypass/BUNDLE-MANIFEST.json"
contract="$script_dir/fixtures/baseline-bundle-successor7/impl-bypass/contract.json"
green="$script_dir/fixtures/baseline-bundle-successor6/legal-successor5-manifest.json"
projection="$script_dir/baseline-bundle-successor6-projection.py"
projection_contract="$script_dir/fixtures/baseline-bundle-successor6/projection-contract.json"
history_commit=548572dfd7d8ee2e3f602a274268e8bd881ef8b2
history_path=.team/nodes/baseline-bundle-prelaunch-review/tests.log

for tool in git python3 shasum grep; do command -v "$tool" >/dev/null 2>&1 || unjudgeable "$tool unavailable"; done
for item in "$fixture" "$contract" "$green" "$projection" "$projection_contract"; do
    [ -r "$item" ] && [ -s "$item" ] || unjudgeable "fixed input unavailable"
done
hash_file() { shasum -a 256 "$1" 2>/dev/null | awk '{print $1}'; }
[ "$(hash_file "$fixture")" = e1b5333e417e4b45d62b22f27b29fc662dac88e7098b5e42b977570eee4296b9 ] || unjudgeable "impl-bypass fixture digest drift"
[ "$(hash_file "$green")" = 60b27f49819c1254ea6d11b01cc619f5f243bcd8bc537943430d9ef74ab86bd3 ] || unjudgeable "green control digest drift"

git -C "$repo_root" cat-file -e "$history_commit:$history_path" 2>/dev/null || unjudgeable "historical provenance object unavailable"
history_output=$(git -C "$repo_root" show "$history_commit:$history_path" 2>/dev/null) || unjudgeable "cannot read historical provenance"
history_sha=$(printf '%s\n' "$history_output" | shasum -a 256 | awk '{print $1}')
[ "$history_sha" = c7a6e9c228a258b94ab6e510faae923e207fc51d7dfdbb2d0aca2519fed8c907 ] || unjudgeable "historical provenance digest drift"
printf '%s\n' "$history_output" | grep -F 'impl tooth: legacy=0 hardened=2, fixture=short-digest-stub-manifest, hardened rejection=repository root mismatch' >/dev/null 2>&1 || unjudgeable "historical bypass classification missing"

python3 - "$contract" <<'PY'
import json, sys
try:
    data=json.load(open(sys.argv[1],encoding="utf-8"))
except (OSError,ValueError):
    raise SystemExit(2)
want=(
    data.get("schema")=="agentmirror.successor7.impl-bypass-fixture.v1",
    data.get("fixture",{}).get("expected_current_exit")==1,
    data.get("fixture",{}).get("expected_reason")=="bundle_id is not 64 lowercase hex",
    data.get("green_control",{}).get("expected_current_exit")==0,
    data.get("missing_input_exit")==2,
)
raise SystemExit(0 if all(want) else 2)
PY
case "$?" in 0) ;; *) unjudgeable "fixture contract drift" ;; esac

python3 "$projection" --manifest "$green" --contract "$projection_contract" >/dev/null 2>&1
green_rc=$?
[ "$green_rc" -eq 0 ] || fail "real projection green control rc=$green_rc"
forged_output=$(python3 "$projection" --manifest "$fixture" --contract "$projection_contract" 2>&1)
forged_rc=$?
[ "$forged_rc" -eq 1 ] || fail "frozen impl-bypass fixture expected rc1 got rc=$forged_rc"
printf '%s\n' "$forged_output" | grep -F 'bundle_id is not 64 lowercase hex' >/dev/null 2>&1 || fail "forged fixture rejected by wrong reason"
python3 "$projection" --manifest "$fixture.missing" --contract "$projection_contract" >/dev/null 2>&1
missing_rc=$?
[ "$missing_rc" -eq 2 ] || fail "missing fixture expected rc2 got rc=$missing_rc"

printf '%s\n' "SUCCESSOR7_IMPL_BYPASS historical_legacy=0 historical_hardened=2 permanent_green=0 permanent_forged=1 permanent_missing=2 provenance=$history_commit"
printf '%s\n' "PASS baseline-bundle-successor7-impl-bypass: permanent Git fixture and real projection rejection verified"
