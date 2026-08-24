#!/bin/sh
# //! purpose: 核旧 perf-regress park 已在机械前置后安全停止并以 ledgerdsl 置 paused。
# //! contract: 0=迁移完成；1=越权/历史或状态错误；2=现场漂移不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-migrate: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-migrate: $*" >&2; exit 2; }
primary=/Volumes/nvme/Projects/远程Agent安卓
node="$primary/.team/nodes/baseline-bundle-migrate"
ledger="$primary/.team/ledgers/perf-regress-v1.json"
source_file="$primary/.team/ledgers/src/perf-regress-v1.py"
lease="$ledger.lease"
[ -e "$node/MIGRATION.md" ] || fail "missing migration artifact $node/MIGRATION.md"
[ -r "$node/MIGRATION.md" ] || unjudgeable "unreadable migration artifact $node/MIGRATION.md"
[ -s "$node/MIGRATION.md" ] || fail "empty migration artifact $node/MIGRATION.md"
last=$(sed -n '$p' "$node/MIGRATION.md" 2>/dev/null) || unjudgeable "cannot read migration verdict"
case "$last" in 'migration: pass') ;; 'migration: fail') fail "migration report says fail" ;; 'migration: unjudgeable') unjudgeable "migration report unjudgeable" ;; *) fail "bad migration verdict" ;; esac
for f in "$node/PRECHECK.json" "$node/MIGRATION.json" "$node/ledgerdsl-plan.log" "$node/ledgerdsl-apply.log" "$ledger" "$source_file"; do
    [ -e "$f" ] || fail "missing migration artifact $f"
    [ -r "$f" ] || unjudgeable "unreadable migration artifact $f"
    [ -s "$f" ] || fail "empty migration artifact $f"
done
[ ! -e "$lease" ] || fail "old ledger lease still exists"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v kill >/dev/null 2>&1 || unjudgeable "kill unavailable"
python3 - "$node/PRECHECK.json" "$node/MIGRATION.json" "$ledger" <<'PY'
import json, sys
try: pre,mig,led=[json.load(open(p,encoding="utf-8")) for p in sys.argv[1:]]
except Exception as exc: print("UNJUDGEABLE baseline-bundle-migrate:",exc,file=sys.stderr); sys.exit(2)
need_pre={"ledger_id":"ledger.perf-regress.v1","impl_state":"failed_retryable","measurement":"unjudgeable","lease_pid_matches_pidfile":True,"comm":"ledger-run","no_active_tasks":True,"bundle_verify":"pass","user_gate":"pass"}
need_mig={"signal":"TERM","pid_dead":True,"history_preserved":True,"desired_state":"paused","other_driver_pids_touched":0}
bad=[f"pre.{k}={pre.get(k)!r}" for k,v in need_pre.items() if pre.get(k)!=v]
bad += [f"migration.{k}={mig.get(k)!r}" for k,v in need_mig.items() if mig.get(k)!=v]
if led.get("ledger_id")!="ledger.perf-regress.v1" or (led.get("run") or {}).get("desired_state")!="paused": bad.append("compiled ledger is not paused")
if not isinstance(pre.get("revision"),int) or not pre.get("pid"): bad.append("missing revision/pid")
if bad: print("FAIL baseline-bundle-migrate: "+", ".join(bad),file=sys.stderr); sys.exit(1)
print(pre.get("pid"))
PY
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "migration json judge unsupported rc" ;; esac
pid=$(python3 -c 'import json; print(json.load(open("/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/baseline-bundle-migrate/PRECHECK.json"))["pid"])' 2>/dev/null) || unjudgeable "cannot read stopped pid"
if kill -0 "$pid" 2>/dev/null; then fail "old ledger-run pid remains alive: $pid"; fi
grep -F 'desired_state="paused"' "$source_file" >/dev/null 2>&1 || fail "ledgerdsl source is not paused"
printf '%s\n' "PASS baseline-bundle-migrate: old park safely stopped and paused"
