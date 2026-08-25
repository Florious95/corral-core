#!/bin/sh
# //! purpose: fresh verify 严格消费 successor7 apparatus 与 permanent bypass，再复用 successor6 深门/恢复/安装报告结构。
# //! contract: 0=apparatus 与 fresh verify 同 bundle 全绿；1=有效反证；2=事实/环境不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-verify: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(git -C "${PWD:-.}" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "cannot resolve worktree"

for gate in baseline-bundle-successor7-apparatus.sh baseline-bundle-successor6-verify.sh; do
    output=$(sh "$script_dir/$gate" 2>&1)
    rc=$?
    printf '%s\n' "$output"
    case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "$gate unsupported rc=$rc" ;; esac
done

python3 - "$repo_root/.team/nodes/baseline-bundle-apparatus/APPARATUS.json" \
    "$repo_root/.team/nodes/baseline-bundle-verify/VERIFY.json" <<'PY'
import hashlib,json,sys
apparatus_path,verify_path=sys.argv[1:]
try:
    apparatus_bytes=open(apparatus_path,'rb').read()
    apparatus=json.loads(apparatus_bytes)
    verify=json.load(open(verify_path,encoding='utf-8'))
except (OSError,ValueError):
    print('UNJUDGEABLE baseline-bundle-successor7-verify: cross-link evidence unavailable',file=sys.stderr)
    raise SystemExit(2)
want={
    'apparatus_evidence_sha256':hashlib.sha256(apparatus_bytes).hexdigest(),
    'apparatus_bundle_id':apparatus.get('bundle_id'),
    'permanent_bypass_probe_exit':0,
    'install_exit':0,
    'runner_pid_cleanup':True,
    'serial_cleanup':True,
    'owned_qemu_cleanup':True,
    'forced_kill':False,
    'verdict_basis':'apparatus_complete',
}
bad=[k for k,v in want.items() if verify.get(k)!=v]
if bad:
    print('FAIL baseline-bundle-successor7-verify: fresh verify cross-link mismatch '+','.join(bad),file=sys.stderr)
    raise SystemExit(1)
print('SUCCESSOR7_VERIFY_LINK apparatus_sha=true bundle_id=true permanent_bypass=0 install=0 runner_pid_cleanup=true serial_cleanup=true qemu_cleanup=true forced_kill=false')
PY
rc=$?
case "$rc" in 0) exit 0 ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "cross-link judge unsupported rc=$rc" ;; esac
