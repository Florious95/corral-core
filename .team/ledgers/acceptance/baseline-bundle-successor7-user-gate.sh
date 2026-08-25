#!/bin/sh
# //! purpose: 将真机金标准绑定到 fresh successor7 apparatus/verify 的同一 bundle。
# //! contract: 0=用户同 bundle 秒开无空白；1=用户倒退/身份矛盾；2=未测或证据不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-user: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
sh "$script_dir/baseline-bundle-user-gate.sh"
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "base user gate unsupported rc=$rc" ;; esac
python3 - "$repo_root/.team/nodes/baseline-bundle-apparatus/APPARATUS.json" "$repo_root/.team/nodes/baseline-bundle-verify/VERIFY.json" "$repo_root/.team/nodes/baseline-bundle-user/USER-GATE.json" <<'PY'
import json,sys
try: a,v,u=[json.load(open(p,encoding="utf-8")) for p in sys.argv[1:]]
except (OSError,ValueError): print("UNJUDGEABLE baseline-bundle-successor7-user: cross-link unavailable",file=sys.stderr); raise SystemExit(2)
bid=a.get("bundle_id")
bad=[]
if not bid or v.get("apparatus_bundle_id")!=bid or u.get("bundle_id")!=bid: bad.append("bundle_id")
if v.get("runner_pid_cleanup") is not True or v.get("serial_cleanup") is not True or v.get("owned_qemu_cleanup") is not True or v.get("forced_kill") is not False: bad.append("cleanup")
if bad: print("FAIL baseline-bundle-successor7-user: "+", ".join(bad),file=sys.stderr); raise SystemExit(1)
print("SUCCESSOR7_USER bundle_same=true runner_pid_cleanup=true serial_cleanup=true qemu_cleanup=true forced_kill=false real_device_gold=true")
PY
rc=$?
case "$rc" in 0) exit 0 ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "cross-link judge unsupported rc=$rc" ;; esac
