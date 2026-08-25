#!/bin/sh
# //! purpose: 独立核 bundle 恢复、实际安装、清理和破坏齿。
# //! contract: 0=独立验证通过；1=有效量具反证；2=环境或证据不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor6-verify: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor6-verify: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
node="$repo_root/.team/nodes/baseline-bundle-verify"
sh "$script_dir/baseline-bundle-successor6-impl.sh"
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "impl gate rc=$rc" ;; esac
[ -e "$node/VERDICT.md" ] || fail "missing $node/VERDICT.md"
[ -r "$node/VERDICT.md" ] || unjudgeable "unreadable $node/VERDICT.md"
[ -s "$node/VERDICT.md" ] || fail "empty $node/VERDICT.md"
last=$(sed -n '$p' "$node/VERDICT.md" 2>/dev/null) || unjudgeable "cannot read verdict"
case "$last" in 'verdict: pass') ;; 'verdict: fail') fail "independent verifier reports fail" ;; 'verdict: unjudgeable') unjudgeable "independent verifier could not judge" ;; *) fail "bad verdict line" ;; esac
for f in "$node/RETRIEVE.md" "$node/INSTALL.md" "$node/MUTATION.md" "$node/VERIFY.json"; do
    [ -e "$f" ] || fail "missing $f"
    [ -r "$f" ] || unjudgeable "unreadable $f"
    [ -s "$f" ] || fail "empty $f"
done
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
python3 - "$node/VERIFY.json" <<'PY'
import json, sys
try: d=json.load(open(sys.argv[1],encoding="utf-8"))
except Exception as exc: print("UNJUDGEABLE baseline-bundle-successor6-verify:",exc,file=sys.stderr); sys.exit(2)
want=(("restore_pass",True),("install_exit",0),("envcheck_gate_exit",0),("independent_inode",True),("owned_qemu_cleanup",True),("mutation_red",True),("mutation_restored_green",True))
bad=[f"{k}={d.get(k)!r}" for k,v in want if d.get(k)!=v]
if bad: print("FAIL baseline-bundle-successor6-verify: "+", ".join(bad),file=sys.stderr); sys.exit(1)
print("verify-json-ok")
PY
case "$?" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "verify json unsupported rc" ;; esac
printf '%s\n' "PASS baseline-bundle-successor6-verify: independent restore/install/mutation gate passed"
