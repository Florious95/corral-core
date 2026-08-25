#!/bin/sh
# //! purpose: 终审 successor11 fresh verify、真机 gate、迁移、fresh 1.10、permanent fixture 与完整图。
# //! contract: 0=全链严格合取；1=有效反证/伪造；2=环境、资产或证据不可判。
# //! boundary: 不调用 legacy successor6/7 verify，不查询 cleanup 后 live adb。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor11-final: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor11-final: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
node="$repo_root/.team/nodes/baseline-bundle-final"
for file in "$node/VERDICT.md" "$node/EVIDENCE-MATRIX.md" "$node/MUTATION.md"; do
    [ -e "$file" ] || fail "missing final artifact"
    [ -r "$file" ] || unjudgeable "final artifact unreadable"
    [ -s "$file" ] || fail "final artifact empty"
done
last=$(sed -n '$p' "$node/VERDICT.md" 2>/dev/null) || unjudgeable "cannot read final verdict"
case "$last" in 'verdict: pass') ;; 'verdict: fail') fail "final report says fail" ;; 'verdict: unjudgeable') unjudgeable "final report unjudgeable" ;; *) fail "bad final verdict" ;; esac
for token in da46a6b2b 0df3562b7 ebd0dc5c2 3597b8232 13c301fd0 7c1a856ba successor11_verify_schema apparatus_evidence_sha256 runner_pid_cleanup serial_cleanup owned_qemu_cleanup forced_kill A/B/A/B nearest-rank 'n>=10' 'B/A<=1.10' '秒开' '没有空白' '破坏齿'; do
    grep -F "$token" "$node/EVIDENCE-MATRIX.md" "$node/MUTATION.md" >/dev/null 2>&1 || fail "final evidence missing $token"
done
for gate in baseline-bundle-successor11-structure.sh baseline-bundle-successor11-verify.sh baseline-bundle-successor7-user-gate.sh baseline-bundle-successor7-migrate.sh baseline-bundle-successor7-measure.sh baseline-bundle-successor7-impl-bypass.sh; do
    sh "$script_dir/$gate"
    rc=$?
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "$gate unsupported rc=$rc" ;; esac
done
python3 - "$repo_root/.team/nodes/baseline-bundle-apparatus/APPARATUS.json" "$repo_root/.team/nodes/baseline-bundle-verify/VERIFY.json" <<'PY'
import hashlib,json,os,stat,sys
try:
 raw=open(sys.argv[1],'rb').read(); a=json.loads(raw); v=json.load(open(sys.argv[2],encoding='utf-8')); mode=stat.S_IMODE(os.lstat(sys.argv[1]).st_mode)
except (OSError,ValueError): print('UNJUDGEABLE baseline-bundle-successor11-final: apparatus cross-link unavailable',file=sys.stderr); raise SystemExit(2)
want={
 'schema':'agentmirror.successor11.verify.v1',
 'apparatus_evidence_sha256':hashlib.sha256(raw).hexdigest(),
 'apparatus_bundle_id':a.get('bundle_id'),
 'apparatus_manifest_sha256':a.get('manifest_sha256'),
 'apparatus_install_exit':0,
 'apparatus_pm_identity_verified':True,
 'runner_pid_cleanup':True,
 'serial_cleanup':True,
 'owned_qemu_cleanup':True,
 'forced_kill':False,
 'legacy_temporary_gate_used':False,
 'current_adb_required':False,
}
bad=[key for key,value in want.items() if v.get(key)!=value]
if mode!=0o600: bad.append('apparatus_mode')
if bad: print('FAIL baseline-bundle-successor11-final: '+', '.join(bad),file=sys.stderr); raise SystemExit(1)
print('SUCCESSOR11_FINAL_CROSSLINK apparatus_sha=true bundle_id=true manifest=true install_pm=true cleanup=true current_adb_required=false')
PY
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "final cross-link unsupported rc=$rc" ;; esac
printf '%s\n' "PASS baseline-bundle-successor11-final: archived apparatus, fresh verify, user gold and fresh 1.10 gate all pass"
