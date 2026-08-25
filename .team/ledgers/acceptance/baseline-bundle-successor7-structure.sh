#!/bin/sh
# //! purpose: 锁 successor7 九格、首 frontier、retained/new WT、command 与 required 精确集合。
# //! contract: 0=结构精确；1=结构漂移；2=编译账本/量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor7-structure: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor7-structure: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
ledger="$repo_root/.team/ledgers/baseline-bundle-successor7-v1.json"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
[ -e "$ledger" ] || unjudgeable "compiled successor7 ledger missing"
[ -r "$ledger" ] && [ -s "$ledger" ] || unjudgeable "compiled successor7 ledger unavailable"

python3 - "$ledger" <<'PY'
import json,sys
p=sys.argv[1]
try: d=json.load(open(p,encoding="utf-8"))
except OSError:
 print("UNJUDGEABLE baseline-bundle-successor7-structure: cannot read ledger",file=sys.stderr); raise SystemExit(2)
except (UnicodeError,json.JSONDecodeError):
 print("FAIL baseline-bundle-successor7-structure: malformed ledger",file=sys.stderr); raise SystemExit(1)

pin="0df3562b7f7479ce4a2683f8c98546fab69bcf1c"
core="wt-maple-core"; test="wt-s7-cedar"; probe="wt-s7-orbit"
ids={
 "t.baseline-bundle.continuity",
 "t.baseline-bundle.apparatus-test",
 "t.baseline-bundle.apparatus-probe",
 "t.baseline-bundle.apparatus",
 "t.baseline-bundle.verify",
 "t.baseline-bundle.user-gate",
 "t.baseline-bundle.migrate",
 "t.baseline-bundle.measure",
 "t.baseline-bundle.final",
}
initial={"t.baseline-bundle.continuity","t.baseline-bundle.apparatus-test","t.baseline-bundle.apparatus-probe"}
deps={
 ("t.baseline-bundle.continuity","t.baseline-bundle.apparatus"),
 ("t.baseline-bundle.apparatus-test","t.baseline-bundle.apparatus"),
 ("t.baseline-bundle.apparatus-probe","t.baseline-bundle.apparatus"),
 ("t.baseline-bundle.apparatus","t.baseline-bundle.verify"),
 ("t.baseline-bundle.verify","t.baseline-bundle.user-gate"),
 ("t.baseline-bundle.user-gate","t.baseline-bundle.migrate"),
 ("t.baseline-bundle.migrate","t.baseline-bundle.measure"),
 ("t.baseline-bundle.measure","t.baseline-bundle.final"),
}
bad=[]
if d.get("ledger_id")!="ledger.baseline-bundle.successor7.v1" or d.get("revision")!=1: bad.append("ledger identity")
tasks=d.get("tasks",{})
if set(tasks)!=ids: bad.append("task ids")
actual_deps={(x.get("from"),x.get("to")) for x in d.get("dependencies",[]) if x.get("condition")=="requires_success"}
if actual_deps!=deps or len(d.get("dependencies",[]))!=len(deps): bad.append("dependencies")
incoming={x[1] for x in deps}
if ids-incoming!=initial: bad.append("initial frontier")
expected_wt={tid:core for tid in ids}
expected_wt["t.baseline-bundle.apparatus-test"]=test
expected_wt["t.baseline-bundle.apparatus-probe"]=probe
expected_required={
 "t.baseline-bundle.continuity":[],
 "t.baseline-bundle.apparatus-test":["M.baseline-bundle.successor7-test"],
 "t.baseline-bundle.apparatus-probe":["M.baseline-bundle.successor7-probe"],
 "t.baseline-bundle.apparatus":["M.baseline-bundle.successor7-apparatus","M.baseline-bundle.successor7-fixture","M.baseline-bundle.successor7-continuity"],
 "t.baseline-bundle.verify":["M.baseline-bundle.successor7-verify"],
 "t.baseline-bundle.user-gate":["M.baseline-bundle.successor7-user"],
 "t.baseline-bundle.migrate":["M.baseline-bundle.successor7-migrate"],
 "t.baseline-bundle.measure":["M.baseline-bundle.successor7-measure"],
 "t.baseline-bundle.final":["M.baseline-bundle.successor7-final","M.baseline-bundle.successor7-real-chain"],
}
expected_taskbook={
 "t.baseline-bundle.continuity":"final-continuity-任务书.md",
 "t.baseline-bundle.apparatus-test":"test-任务书.md",
 "t.baseline-bundle.apparatus-probe":"probe-任务书.md",
 "t.baseline-bundle.apparatus":"apparatus-任务书.md",
 "t.baseline-bundle.verify":"verify-任务书.md",
 "t.baseline-bundle.user-gate":"final-user-gate-任务书.md",
 "t.baseline-bundle.migrate":"final-migrate-任务书.md",
 "t.baseline-bundle.measure":"final-measure-任务书.md",
 "t.baseline-bundle.final":"final-final-任务书.md",
}
for tid in ids:
 t=tasks.get(tid,{})
 if t.get("resources",{}).get("worktree_id")!=expected_wt[tid]: bad.append("worktree "+tid)
 if t.get("resources",{}).get("provenance",{}).get("revision")!=pin: bad.append("provenance "+tid)
 if "statuses" in t: bad.append("statuses "+tid)
 if expected_taskbook[tid] not in t.get("title",""): bad.append("taskbook title "+tid)
 acceptance=t.get("acceptance",{})
 required=acceptance.get("required",[])
 mechanical=[x.get("acceptance_id") for x in acceptance.get("mechanical",[])]
 if required!=expected_required[tid] or mechanical!=expected_required[tid]: bad.append("required "+tid)
 if any("legacy" in str(x) or x in {"M.baseline-bundle.impl-bypass","M.baseline-bundle.probe"} for x in required): bad.append("legacy required "+tid)
apparatus=tasks.get("t.baseline-bundle.apparatus",{})
cmd=apparatus.get("command",{})
if apparatus.get("executor")!="command": bad.append("apparatus executor")
if cmd.get("argv")!=["/bin/sh",".team/ledgers/acceptance/baseline-bundle-successor7-owned-emulator.sh"]: bad.append("apparatus argv")
if cmd.get("cwd")!="${worktree}" or cmd.get("expected_exit_code")!=0 or cmd.get("unjudgeable_exit_codes")!=[2]: bad.append("apparatus command four-state")
want_required=["M.baseline-bundle.successor7-apparatus","M.baseline-bundle.successor7-fixture","M.baseline-bundle.successor7-continuity"]
if apparatus.get("acceptance",{}).get("required")!=want_required: bad.append("apparatus required")
continuity=tasks.get("t.baseline-bundle.continuity",{})
ccmd=continuity.get("command",{})
if continuity.get("executor")!="command" or ccmd.get("argv")!=["/bin/sh",".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh"]: bad.append("continuity command")
if any(tasks.get(t,{}).get("executor")=="command" for t in initial-{"t.baseline-bundle.continuity"}): bad.append("review frontier command")
for tid in ("t.baseline-bundle.apparatus-test","t.baseline-bundle.apparatus-probe"):
 title=tasks.get(tid,{}).get("title","")
 if not all(x in title for x in ("严禁","adb","qemu","emulator")): bad.append("review frontier device ban "+tid)
groups=d.get("parallelism",[])
if groups!=[{"failure_policy":"halt","group":"successor7-apparatus-wave","max_concurrency":3}]: bad.append("parallelism")
for tid in initial:
 if tasks.get(tid,{}).get("parallel",{}).get("group")!="successor7-apparatus-wave": bad.append("initial parallel "+tid)
if d.get("transitions") not in ([],None): bad.append("transitions")
if bad:
 print("FAIL baseline-bundle-successor7-structure: "+", ".join(sorted(set(bad))),file=sys.stderr); raise SystemExit(1)
print("SUCCESSOR7_STRUCTURE tasks=9 dependencies=8 frontier=continuity+apparatus-test+apparatus-probe retained_wt=wt-maple-core test_wt=wt-s7-cedar probe_wt=wt-s7-orbit apparatus_command=owned-emulator required=exact-no-legacy statuses=none frontier_device_action=false")
PY
