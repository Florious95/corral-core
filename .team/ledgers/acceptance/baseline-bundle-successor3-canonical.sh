#!/bin/sh
# //! purpose: 通过真实 baseline_bundle.py retrieve 入口锁 stale-path 红、final-path 绿与 provenance 伪造红。
# //! contract: 0=真实入口控制变量三齿符合；1=实现接受伪造或拒绝绿色；2=入口/固定夹具/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor3-canonical: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor3-canonical: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
helper="$script_dir/baseline-bundle-successor3-real-fixture.py"
contract="$script_dir/fixtures/baseline-bundle-successor3/control-contract.json"
scratch="$repo_root/.team/nodes/spec-sol/baseline-bundle-successor3/tmp/canonical-real"

command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v shasum >/dev/null 2>&1 || unjudgeable "shasum unavailable"
[ -e "$repo_root/tools/perfbase/baseline_bundle.py" ] || unjudgeable "real baseline_bundle.py missing"
[ -r "$repo_root/tools/perfbase/baseline_bundle.py" ] && [ -s "$repo_root/tools/perfbase/baseline_bundle.py" ] || unjudgeable "real baseline_bundle.py unreadable or empty"
for f in "$helper" "$contract"; do
    [ -e "$f" ] || unjudgeable "fixed bootstrap input missing"
    [ -r "$f" ] && [ -s "$f" ] || unjudgeable "fixed bootstrap input unreadable or empty"
done
contract_sha=$(shasum -a 256 "$contract" 2>/dev/null | awk '{print $1}') || unjudgeable "cannot hash fixed control contract"
[ "$contract_sha" = "ffcea3d0d3282618ad91f9db44c7a99616868b6610c88516e022385e59bd3fd9" ] || unjudgeable "fixed control contract digest drift"

python3 "$helper" --mode canonical --implementation-root "$repo_root" --source-root "$repo_root" --contract "$contract" --scratch "$scratch"
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "real canonical fixture unsupported rc=$rc" ;; esac
printf '%s\n' "PASS baseline-bundle-successor3-canonical: real manifest stale/final/provenance controls verified"
