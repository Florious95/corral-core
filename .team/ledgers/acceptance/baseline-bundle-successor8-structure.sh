#!/bin/sh
# //! purpose: 锁 successor8 九格消费图、三 command frontier、原 WT/产物与 r1 审计 provenance。
# //! contract: 0=结构与冻结事实精确；1=有效漂移/篡改；2=量具、Git、WT或产物不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor8-structure: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
ledger="$repo_root/.team/ledgers/baseline-bundle-successor8-v1.json"
case "$#" in
    0) ;;
    1)
        candidate_dir=$(CDPATH='' cd "$(dirname "$1")" 2>/dev/null && pwd) || unjudgeable "cannot resolve test ledger directory"
        case "$candidate_dir/" in
            "$repo_root/.team/nodes/spec-sol/baseline-bundle-successor8/tmp/"*) ledger=$1 ;;
            *) printf '%s\n' "FAIL baseline-bundle-successor8-structure: test ledger outside node-local tmp" >&2; exit 1 ;;
        esac
        ;;
    *) printf '%s\n' "FAIL baseline-bundle-successor8-structure: unsupported arguments" >&2; exit 1 ;;
esac
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
[ -e "$ledger" ] || unjudgeable "compiled successor8 ledger missing"
[ -r "$ledger" ] && [ -s "$ledger" ] || unjudgeable "compiled successor8 ledger unavailable"

python3 - "$repo_root" "$ledger" <<'PY'
import hashlib,json,pathlib,subprocess,sys
root=pathlib.Path(sys.argv[1]); ledger_path=pathlib.Path(sys.argv[2])
pin="132e635761060c92edbcc789d0eac852c2a4d1e4"
final_commit="79cd08f0f53d0bd2e44dfd4d4e2fb33cbde001f2"
pair_commit="25517d808cc19e3f002ceba51000b2a269bec362"
pair_source="7485102b26ed34eb828e94900902147d5e00e995"
r1_sha="447d8a6fb608c2ab520c0a4f3a3d7bb5ab69f9818d2e008bb049096d103320dd"
driver_sha="aff56f8ceb238464429d577e33f51a75f51889578f6f3a3cb6ba746802e90b91"
verdict_sha="1191853ef9e1abd5cf27a1bc1cbd28a03f59871576c80f832d32fab6f6ecd1d1"
red_sha="04cdbd661548a4b3261c88d491cf80c48f98dcbe3c080e710fb7d12bbe6c105a"
probe_sha="88868a1a1979d3f1504e5efd6876dc5ca8ed5cc6b45a2eb6f6dd23b8e5176cf7"

def fail(msg): print("FAIL baseline-bundle-successor8-structure: "+msg,file=sys.stderr); raise SystemExit(1)
def unknown(msg): print("UNJUDGEABLE baseline-bundle-successor8-structure: "+msg,file=sys.stderr); raise SystemExit(2)
def sha(data): return hashlib.sha256(data).hexdigest()
def read(path):
 try: return path.read_bytes()
 except OSError: unknown("unavailable "+str(path))
def run(argv):
 try: return subprocess.run(argv,capture_output=True,check=False)
 except OSError: unknown("cannot execute "+argv[0])

try: ledger=json.loads(read(ledger_path))
except (UnicodeError,json.JSONDecodeError): fail("compiled ledger malformed")
ids={
 "t.baseline-bundle.continuity-consume","t.baseline-bundle.apparatus-test-consume","t.baseline-bundle.apparatus-probe-consume",
 "t.baseline-bundle.apparatus","t.baseline-bundle.verify","t.baseline-bundle.user-gate","t.baseline-bundle.migrate",
 "t.baseline-bundle.measure","t.baseline-bundle.final",
}
frontier={"t.baseline-bundle.continuity-consume","t.baseline-bundle.apparatus-test-consume","t.baseline-bundle.apparatus-probe-consume"}
deps={
 ("t.baseline-bundle.continuity-consume","t.baseline-bundle.apparatus"),
 ("t.baseline-bundle.apparatus-test-consume","t.baseline-bundle.apparatus"),
 ("t.baseline-bundle.apparatus-probe-consume","t.baseline-bundle.apparatus"),
 ("t.baseline-bundle.apparatus","t.baseline-bundle.verify"),
 ("t.baseline-bundle.verify","t.baseline-bundle.user-gate"),
 ("t.baseline-bundle.user-gate","t.baseline-bundle.migrate"),
 ("t.baseline-bundle.migrate","t.baseline-bundle.measure"),
 ("t.baseline-bundle.measure","t.baseline-bundle.final"),
}
tasks=ledger.get("tasks",{}); bad=[]
if ledger.get("ledger_id")!="ledger.baseline-bundle.successor8.v1" or ledger.get("revision")!=1: bad.append("ledger identity")
if set(tasks)!=ids: bad.append("task ids")
actual={(x.get("from"),x.get("to")) for x in ledger.get("dependencies",[]) if x.get("condition")=="requires_success"}
if actual!=deps or len(ledger.get("dependencies",[]))!=8: bad.append("dependencies")
if ids-{b for _,b in deps}!=frontier: bad.append("frontier")
if ledger.get("parallelism")!=[{"failure_policy":"halt","group":"successor8-consume-wave","max_concurrency":3}]: bad.append("parallelism")
if ledger.get("transitions") not in ([],None): bad.append("transitions")
for tid in ids:
 t=tasks.get(tid,{})
 if t.get("resources",{}).get("provenance",{}).get("revision")!=pin: bad.append("provenance "+tid)
 if "statuses" in t: bad.append("statuses "+tid)
expected={
 "t.baseline-bundle.continuity-consume":("wt-maple-core",["/bin/sh",".team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh"],[]),
 "t.baseline-bundle.apparatus-test-consume":("wt-s7-cedar",["/bin/sh",".team/ledgers/acceptance/baseline-bundle-successor7-test.sh"],[".team/nodes/baseline-bundle-successor7-test/RED.md"]),
 "t.baseline-bundle.apparatus-probe-consume":("wt-s7-orbit",["/bin/sh",".team/ledgers/acceptance/baseline-bundle-successor7-probe.sh"],[".team/nodes/baseline-bundle-successor7-probe/PROBE.md"]),
}
for tid,(wt,argv,artifacts) in expected.items():
 t=tasks.get(tid,{}); cmd=t.get("command",{}); acceptance=t.get("acceptance",{})
 if t.get("executor")!="command" or t.get("parallel",{}).get("group")!="successor8-consume-wave": bad.append("consume executor "+tid)
 if t.get("resources",{}).get("worktree_id")!=wt or t.get("resources",{}).get("write_paths")!=[]: bad.append("consume isolation "+tid)
 if cmd.get("argv")!=argv or cmd.get("cwd")!="${worktree}" or cmd.get("expected_exit_code")!=0 or cmd.get("unjudgeable_exit_codes")!=[2] or cmd.get("artifacts")!=artifacts: bad.append("consume command "+tid)
 if acceptance.get("required")!=[] or acceptance.get("mechanical")!=[]: bad.append("consume self-report leak "+tid)
 if pair_source not in t.get("title","") or "79cd08f0f/25517d808" not in t.get("title",""): bad.append("consume provenance title "+tid)
apparatus=tasks.get("t.baseline-bundle.apparatus",{}); acmd=apparatus.get("command",{})
if apparatus.get("executor")!="command" or apparatus.get("resources",{}).get("worktree_id")!="wt-maple-core": bad.append("apparatus executor")
if acmd.get("argv")!=["/bin/sh",".team/ledgers/acceptance/baseline-bundle-successor7-owned-emulator.sh"] or acmd.get("unjudgeable_exit_codes")!=[2]: bad.append("apparatus command")
want=["M.baseline-bundle.successor7-apparatus","M.baseline-bundle.successor7-fixture","M.baseline-bundle.successor7-continuity"]
if apparatus.get("acceptance",{}).get("required")!=want: bad.append("apparatus required")
if bad: fail(", ".join(sorted(set(bad))))

for commit in (final_commit,pair_commit):
 if run(["git","-C",str(root),"merge-base","--is-ancestor",commit,pin]).returncode!=0: fail("provenance ancestry "+commit[:9])
for commit,path in (
 (final_commit,".team/ledgers/baseline-bundle-successor7-v1.json"),
 (pair_commit,".team/nodes/baseline-bundle-successor7-final-review/VERDICT.md"),
 (pair_commit,".team/nodes/baseline-bundle-successor7-final-review/tests.log"),
):
 if run(["git","-C",str(root),"cat-file","-e",commit+":"+path]).returncode!=0: unknown("Git provenance unavailable "+commit[:9]+":"+path)

r1=read(root/".team/ledgers/baseline-bundle-successor7-v1.json")
driver=read(root/".team/nodes/_driver/baseline-bundle-successor7-v1.out")
verdict=read(root/".team/nodes/baseline-bundle-successor7-frontier-recovery/VERDICT.md")
if sha(r1)!=r1_sha or sha(driver)!=driver_sha or sha(verdict)!=verdict_sha: fail("r1 ledger/dispatch/verdict digest drift")
try: r1_ledger=json.loads(r1)
except (UnicodeError,json.JSONDecodeError): fail("r1 ledger malformed")
for tid in (
 "t.baseline-bundle.apparatus","t.baseline-bundle.verify","t.baseline-bundle.user-gate",
 "t.baseline-bundle.migrate","t.baseline-bundle.measure","t.baseline-bundle.final",
):
 old=r1_ledger.get("tasks",{}).get(tid,{}); new=tasks.get(tid,{})
 for key in ("title","owner","seat_wait_seconds","handoff","acceptance","executor","command"):
  if old.get(key)!=new.get(key): fail("downstream weakened "+tid+" field="+key)
 oldr=old.get("resources",{}); newr=new.get("resources",{})
 for key in ("worktree_id","write_paths","environment_fidelity"):
  if oldr.get(key)!=newr.get(key): fail("downstream resources weakened "+tid+" field="+key)
 old_reads=oldr.get("read_paths",[]); new_reads=newr.get("read_paths",[])
 if not old_reads or len(new_reads)<len(old_reads) or new_reads[-len(old_reads):]!=old_reads: fail("downstream read paths weakened "+tid)
text=driver.decode("utf-8",errors="strict")
if text.count("开工 start | ledger_id=ledger.baseline-bundle.successor7.v1 revision=1")!=2: fail("r1 dispatch run count")
if text.count("派单 dispatch | task=t.baseline-bundle.apparatus-test")!=2 or text.count("派单 dispatch | task=t.baseline-bundle.apparatus-probe")!=2: fail("r1 two dispatch shapes")
if text.count("命令执行 command-exec | task=t.baseline-bundle.continuity")!=2: fail("r1 continuity dispatch shape")

listing=run(["git","-C",str(root),"worktree","list","--porcelain"])
if listing.returncode!=0: unknown("cannot inspect worktrees")
wtmap={}
for line in listing.stdout.decode("utf-8",errors="strict").splitlines():
 if line.startswith("worktree "):
  p=pathlib.Path(line[9:]); wtmap[p.name]=p
for name in ("wt-maple-core","wt-s7-cedar","wt-s7-orbit"):
 if name not in wtmap or not wtmap[name].is_dir(): unknown("worktree missing "+name)
red=read(wtmap["wt-s7-cedar"]/".team/nodes/baseline-bundle-successor7-test/RED.md")
probe=read(wtmap["wt-s7-orbit"]/".team/nodes/baseline-bundle-successor7-probe/PROBE.md")
if sha(red)!=red_sha or sha(probe)!=probe_sha: fail("fresh RED/PROBE digest drift")
if red.decode("utf-8",errors="strict").splitlines()[-1]!="test: pass" or probe.decode("utf-8",errors="strict").splitlines()[-1]!="probe: pass": fail("fresh RED/PROBE verdict drift")
print("SUCCESSOR8_STRUCTURE tasks=9 dependencies=8 frontier=three-command worktrees=maple+cedar+orbit r1_dispatch_runs=2 red_sha=true probe_sha=true provenance=79cd+25517d+132e command_pair=7485102 statuses=none apparatus_locked=true")
PY
