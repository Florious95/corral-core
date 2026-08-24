#!/bin/sh
# //! purpose: 将两次“真实旧链按预期红”事实转译为 repro acceptance 通过，不吞并不可判。
# //! contract: 0=两次预期红+REPRO.json 一致；1=非预期/伪造/矛盾；2=缺事实或 provenance 不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-repro-translate: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-repro-translate: $*" >&2; exit 2; }
[ "$#" -eq 5 ] || unjudgeable "usage: run1.json rc1 run2.json rc2 REPRO.json"
run1=$1; rc1=$2; run2=$3; rc2=$4; report=$5

case "$rc1:$rc2" in *2* ) unjudgeable "real probe returned unjudgeable rc1=$rc1 rc2=$rc2" ;; esac
case "$rc1:$rc2" in 1:1) ;; *) fail "real probe did not produce two expected-red rc=1 observations rc1=$rc1 rc2=$rc2" ;; esac
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
for f in "$run1" "$run2" "$report"; do
    [ -e "$f" ] || unjudgeable "missing machine evidence $f"
    [ -r "$f" ] || unjudgeable "unreadable machine evidence $f"
    [ -s "$f" ] || unjudgeable "empty machine evidence $f"
done

python3 - "$run1" "$run2" "$report" <<'PY'
import datetime,hashlib,json,re,sys

def fail(msg): print("FAIL baseline-bundle-repro-translate: "+msg,file=sys.stderr); raise SystemExit(1)
def uj(msg): print("UNJUDGEABLE baseline-bundle-repro-translate: "+msg,file=sys.stderr); raise SystemExit(2)
def load(path):
    try: return json.load(open(path,encoding="utf-8"))
    except Exception as e: uj(f"cannot parse {path}: {e}")
def h64(v): return isinstance(v,str) and re.fullmatch(r"[0-9a-f]{64}",v) is not None
def canonical(v): return json.dumps(v,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()
def required(obj,key,kind=None):
    if not isinstance(obj,dict) or key not in obj or obj[key] in (None,""): uj("missing provenance/evidence field "+key)
    value=obj[key]
    if kind is not None and not isinstance(value,kind): uj("bad evidence type "+key)
    return value
def validate_record(d,label):
    if not isinstance(d,dict): uj(label+" is not an object")
    if d.get("schema")!="agentmirror.baseline-bundle.real-chain.v1": uj(label+" schema missing/drifted")
    if d.get("classification")!="legacy_missing_baseline_park": fail(label+" classification is not expected legacy red")
    probe=required(d,"probe",dict); ledger=required(d,"ledger",dict); measurement=required(d,"measurement",dict)
    process=required(d,"process",dict); dry=required(d,"dry_run",dict)
    fixed={
      "probe.path":".team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh",
      "ledger.path":".team/ledgers/perf-regress-v1.json",
      "ledger.ledger_id":"ledger.perf-regress.v1",
      "ledger.revision":4,
      "ledger.desired_state":"running",
      "ledger.impl_state":"failed_retryable",
      "measurement.worktree_id":"wt-pr-impl",
      "measurement.path":".team/nodes/perf-regress/FIXED-MEASURE.md",
      "measurement.verdict":"unjudgeable",
      "process.lease_path":".team/ledgers/perf-regress-v1.json.lease",
      "process.pidfile_path":".team/nodes/_driver/perf-regress-v1.pid",
      "process.comm":"ledger-run",
      "dry.impl_exclusion":"state_not_dispatchable",
      "dry.verify_exclusion":"dependency_unsatisfied",
    }
    actual={
      "probe.path":probe.get("path"),"ledger.path":ledger.get("path"),"ledger.ledger_id":ledger.get("ledger_id"),
      "ledger.revision":ledger.get("revision"),"ledger.desired_state":ledger.get("desired_state"),"ledger.impl_state":ledger.get("impl_state"),
      "measurement.worktree_id":measurement.get("worktree_id"),"measurement.path":measurement.get("path"),"measurement.verdict":measurement.get("verdict"),
      "process.lease_path":process.get("lease_path"),"process.pidfile_path":process.get("pidfile_path"),"process.comm":process.get("comm"),
      "dry.impl_exclusion":dry.get("impl_exclusion"),"dry.verify_exclusion":dry.get("verify_exclusion"),
    }
    bad=[f"{k}={actual[k]!r}" for k,v in fixed.items() if actual[k]!=v]
    if bad: fail(label+" unexpected shape: "+", ".join(bad))
    for section,obj,keys in (("probe",probe,("sha256",)),("ledger",ledger,("sha256",)),("measurement",measurement,("sha256",)),("process",process,("lease_sha256","pidfile_sha256")),("dry_run",dry,("sha256",))):
        for key in keys:
            if not h64(obj.get(key)): uj(f"{label} missing/malformed provenance {section}.{key}")
    if dry.get("frontier")!=[]: fail(label+" frontier is not empty")
    lp,pp=process.get("lease_pid"),process.get("pidfile_pid")
    if not isinstance(lp,int) or lp<=0 or lp!=pp: fail(label+" lease/pidfile operands differ")
    return d
def stable(d):
    x=json.loads(json.dumps(d))
    x["process"].pop("lease_sha256",None)
    return x
def rfc3339(value):
    if not isinstance(value,str): return False
    try: datetime.datetime.fromisoformat(value.replace("Z","+00:00")); return True
    except ValueError: return False

fresh=[validate_record(load(sys.argv[1]),"fresh run 1"),validate_record(load(sys.argv[2]),"fresh run 2")]
if stable(fresh[0])!=stable(fresh[1]): fail("two fresh probe records disagree")
report=load(sys.argv[3])
if report.get("schema")!="agentmirror.baseline-bundle.repro.v1" or report.get("evidence_kind")!="expected_legacy_red": uj("REPRO.json schema/evidence_kind missing or drifted")
contract=required(report,"contract",dict)
want_contract={"taskbook_path":".team/nodes/spec-sol/baseline-bundle/repro-任务书.md","acceptance_path":".team/ledgers/acceptance/baseline-bundle-repro.sh","translator_path":".team/ledgers/acceptance/baseline-bundle-repro-translate.sh","human_report_path":".team/nodes/baseline-bundle-repro/REPRO.md"}
if contract!=want_contract: uj("REPRO.json contract provenance missing or drifted")
runs=required(report,"runs",list)
if len(runs)!=2: uj("REPRO.json must contain exactly two runs")
for index,item in enumerate(runs,1):
    if not isinstance(item,dict): uj("REPRO.json run is not an object")
    if item.get("sequence")!=index or item.get("probe_exit")!=1: fail("REPRO.json run sequence/rc forged")
    if not rfc3339(item.get("observed_at")): uj("REPRO.json run observed_at missing/malformed")
    record=validate_record(required(item,"probe_record",dict),f"REPRO.json run {index}")
    want=hashlib.sha256(canonical(record)).hexdigest()
    if item.get("probe_record_sha256")!=want: fail("REPRO.json embedded record digest mismatch")
    if stable(record)!=stable(fresh[index-1]): fail("REPRO.json semantic/provenance record does not match fresh real probe")
translation=required(report,"translation",dict)
want_translation={"rule_id":"expected_legacy_red_to_repro_pass.v1","input_classification":"legacy_missing_baseline_park","input_probe_exit":1,"output_acceptance_exit":0}
if translation!=want_translation: fail("REPRO.json translation rule is forged or incomplete")
future=required(report,"future_gate",dict)
if future!={"task_id":"t.baseline-bundle.final","required_probe_exit":0,"classification":"baseline_bundle_migration_complete"}: fail("REPRO.json future gate drifted")
print("REPRO_TRANSLATION schema=agentmirror.baseline-bundle.repro.v1 input=expected_legacy_red probe_runs=2 acceptance_exit=0")
PY
rc=$?
case "$rc" in 0) exit 0 ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "translator judge unsupported rc=$rc" ;; esac
