#!/bin/sh
# //! purpose: 三态观察真实旧性能链；旧缺 A park=1，根治迁移完成=0，事实漂移=2。
# //! contract: 0=根治已迁移；1=真实旧链仍死锁；2=缺事实或中间态不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-real-chain: $*" >&2; exit 2; }
primary=/Volumes/nvme/Projects/远程Agent安卓
ledger="$primary/.team/ledgers/perf-regress-v1.json"
lease="$ledger.lease"
pidfile="$primary/.team/nodes/_driver/perf-regress-v1.pid"
migration="$primary/.team/nodes/baseline-bundle-migrate/MIGRATION.json"
manifest="$primary/.team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json"

[ -r "$ledger" ] && [ -s "$ledger" ] || unjudgeable "missing or unreadable $ledger"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v ledger-run >/dev/null 2>&1 || unjudgeable "ledger-run unavailable"
command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
wt_path=$(git -C "$primary" worktree list --porcelain 2>/dev/null | awk '$1=="worktree" {n=split($2,a,"/"); if (a[n]=="wt-pr-impl") print $2}' | sed -n '1p')
[ -n "$wt_path" ] || unjudgeable "cannot locate exact wt-pr-impl from git worktree metadata"
fixed="$wt_path/.team/nodes/perf-regress/FIXED-MEASURE.md"
dry=$(ledger-run --dry-run --json "$ledger" 2>&1)
dry_rc=$?
[ "$dry_rc" -eq 0 ] || unjudgeable "ledger-run dry-run rc=$dry_rc"

state=$(python3 - "$ledger" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1],encoding="utf-8"))
except Exception as e:
    print("bad:"+str(e)); raise SystemExit(2)
t=(d.get("tasks") or {}).get("t.perf-regress.impl") or {}
desired=(d.get("run") or {}).get("desired_state") or "running"
print("%s|%s|%s|%s"%(d.get("ledger_id"),d.get("revision"),desired,t.get("state")))
PY
) || unjudgeable "cannot parse perf-regress ledger"

case "$state" in
    'ledger.perf-regress.v1|4|running|failed_retryable')
        [ -r "$fixed" ] && [ -s "$fixed" ] || unjudgeable "missing frozen failed measurement $fixed"
        [ "$(sed -n '$p' "$fixed" 2>/dev/null)" = 'measurement: unjudgeable' ] || unjudgeable "failed measurement verdict drifted"
        [ -r "$lease" ] && [ -r "$pidfile" ] || unjudgeable "old lease/pidfile unavailable"
        lease_pid=$(python3 - "$lease" <<'PY'
import json,sys
try: print(int(json.load(open(sys.argv[1]))["pid"]))
except Exception: raise SystemExit(2)
PY
) || unjudgeable "cannot parse lease pid"
        pid_value=$(sed -n 's/^pid=//p' "$pidfile" 2>/dev/null | sed -n '1p') || unjudgeable "cannot read pidfile"
        [ "$lease_pid" = "$pid_value" ] || unjudgeable "lease pid and pidfile differ"
        comm=$(ps -p "$lease_pid" -o comm= 2>/dev/null | sed -n '1p') || unjudgeable "cannot inspect old driver pid"
        [ "$comm" = ledger-run ] || unjudgeable "old driver comm=$comm"
        printf '%s\n' "$dry" | python3 -c 'import json,sys
try: d=json.load(sys.stdin)
except Exception: raise SystemExit(2)
if d.get("frontier") != []: raise SystemExit(2)
x={e.get("task_id"):(e.get("code"),e.get("reason","")) for e in d.get("excluded",[])}
if x.get("t.perf-regress.impl",("",))[0] != "state_not_dispatchable": raise SystemExit(2)
if x.get("t.perf-regress.verify",("",))[0] != "dependency_unsatisfied": raise SystemExit(2)
' || unjudgeable "dry-run no longer proves empty frontier/dependency block"
        printf '%s\n' "FAIL baseline-bundle-real-chain: state=failed_retryable frontier=[] verify=dependency_unsatisfied measurement=unjudgeable lease_pid=$lease_pid"
        exit 1
        ;;
    ledger.perf-regress.v1\|*\|paused\|failed_retryable)
        [ ! -e "$lease" ] || unjudgeable "paused ledger still has lease"
        [ -r "$migration" ] && [ -r "$manifest" ] || unjudgeable "migration/bundle evidence unavailable"
        if ! python3 - "$migration" "$manifest" <<'PY'
import json,re,sys
try: mig,man=[json.load(open(p,encoding="utf-8")) for p in sys.argv[1:]]
except Exception: raise SystemExit(2)
if mig.get("desired_state")!="paused" or mig.get("pid_dead") is not True or mig.get("history_preserved") is not True: raise SystemExit(2)
bid=man.get("bundle_id")
if not isinstance(bid,str) or not re.fullmatch(r"[0-9a-f]{64}",bid): raise SystemExit(2)
if mig.get("bundle_id") != bid: raise SystemExit(2)
PY
        then
            unjudgeable "paused ledger is not bound to completed bundle migration"
        fi
        printf '%s\n' "PASS baseline-bundle-real-chain: blocked_missing_baseline migrated_to=baseline_bundle desired_state=paused"
        exit 0
        ;;
    *) unjudgeable "unexpected perf-regress state=$state" ;;
esac
