#!/bin/sh
# //! purpose: 锁定 successor11 四 command consume→fresh verify→user/migrate/measure/final 完整图与精确 required。
# //! contract: 0=图/席位/固定 pair/provenance/四态门全部精确；1=弱化或 legacy/live-device 门回流；2=账本/量具不可判。
# //! boundary: production 只读固定 compiled ledger；fixture 仅限 successor11-final node-local tmp。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor11-structure: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor11-structure: $*" >&2; exit 2; }

script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
fixture=${SUCCESSOR11_STRUCTURE_FIXTURE_MODE-}
case "$fixture" in
'')
    [ "$#" -eq 0 ] || fail "production ledger path override rejected"
    ledger="$repo_root/.team/ledgers/baseline-bundle-successor11-v1.json"
    ;;
1)
    [ "$#" -eq 1 ] || fail "fixture ledger path required"
    ledger=$(CDPATH='' cd "$(dirname "$1")" 2>/dev/null && pwd)/$(basename "$1") || unjudgeable "fixture ledger unavailable"
    case "$ledger" in "$repo_root/.team/nodes/spec-sol/baseline-bundle-successor11-final/tmp/"*) ;; *) fail "fixture ledger escapes node-local tmp" ;; esac
    ;;
*) fail "SUCCESSOR11_STRUCTURE_FIXTURE_MODE must be unset, empty, or exactly 1" ;;
esac
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
[ -r "$ledger" ] && [ -s "$ledger" ] || unjudgeable "compiled successor11 ledger unavailable"

python3 - "$ledger" <<'PY'
import json,sys
path=sys.argv[1]
try:
    d=json.load(open(path,encoding='utf-8'))
except (OSError,ValueError):
    print('UNJUDGEABLE baseline-bundle-successor11-structure: invalid ledger JSON',file=sys.stderr)
    raise SystemExit(2)

def fail(msg):
    print('FAIL baseline-bundle-successor11-structure: '+msg,file=sys.stderr)
    raise SystemExit(1)

ids={
 'continuity':'t.baseline-bundle.continuity-consume',
 'test':'t.baseline-bundle.apparatus-test-consume',
 'probe':'t.baseline-bundle.apparatus-probe-consume',
 'apparatus':'t.baseline-bundle.apparatus-consume',
 'verify':'t.baseline-bundle.verify',
 'user':'t.baseline-bundle.user-gate',
 'migrate':'t.baseline-bundle.migrate',
 'measure':'t.baseline-bundle.measure',
 'final':'t.baseline-bundle.final',
}
tasks=d.get('tasks',{})
if d.get('schema_version')!='ledger.v2' or d.get('ledger_id')!='ledger.baseline-bundle.successor11.v1' or d.get('revision')!=1:
    fail('ledger identity/revision mismatch')
if d.get('run',{}).get('desired_state')!='running' or set(tasks)!=set(ids.values()):
    fail('run state or exact task set mismatch')
if d.get('transitions') not in (None,[]):
    fail('custom transitions/status routing forbidden')
if any(task.get('statuses') for task in tasks.values()):
    fail('custom statuses forbidden')

consume_script='/Volumes/nvme/Projects/远程Agent安卓/.team/ledgers/acceptance/baseline-bundle-successor11-consume.sh'
wt_token='$'+'{worktree}'
consume={
 ids['continuity']:('continuity','wt-maple-core',[]),
 ids['test']:('apparatus-test','wt-s7-cedar',['.team/nodes/baseline-bundle-successor7-test/RED.md']),
 ids['probe']:('apparatus-probe','wt-s7-orbit',['.team/nodes/baseline-bundle-successor7-probe/PROBE.md']),
 ids['apparatus']:('apparatus','wt-archive-probe',[]),
}
for task_id,(kind,worktree,artifacts) in consume.items():
    task=tasks[task_id]
    command=task.get('command',{})
    if task.get('executor')!='command' or command.get('argv')!=['/bin/sh',consume_script,kind]:
        fail(task_id+' is not exact consume command')
    if command.get('cwd')!=wt_token or command.get('expected_exit_code')!=0 or command.get('unjudgeable_exit_codes')!=[2]:
        fail(task_id+' command four-state contract mismatch')
    if command.get('artifacts')!=artifacts or task.get('resources',{}).get('worktree_id')!=worktree:
        fail(task_id+' worktree/artifact binding mismatch')
    if task.get('acceptance',{}).get('required') not in (None,[]):
        fail(task_id+' must close only through command exit')

verify=tasks[ids['verify']]
want_verify=[
 'M.baseline-bundle.successor11-verify',
 'M.baseline-bundle.successor11-regression',
 'M.baseline-bundle.successor11-structure',
]
if verify.get('executor','agent')!='agent' or verify.get('acceptance',{}).get('required')!=want_verify:
    fail('fresh verify exact required set mismatch')
mechanical={x.get('acceptance_id'):x for x in verify.get('acceptance',{}).get('mechanical',[])}
want_scripts={
 want_verify[0]:'.team/ledgers/acceptance/baseline-bundle-successor11-verify.sh',
 want_verify[1]:'.team/ledgers/acceptance/baseline-bundle-successor11-verify-regression.sh',
 want_verify[2]:'.team/ledgers/acceptance/baseline-bundle-successor11-structure.sh',
}
if set(mechanical)!=set(want_scripts):
    fail('fresh verify mechanical set mismatch')
for acc,path in want_scripts.items():
    check=mechanical[acc]
    if check.get('argv')!=['/bin/sh',path] or check.get('expected_exit_code')!=0 or check.get('unjudgeable_exit_codes')!=[2]:
        fail(acc+' script or four-state mapping mismatch')
verify_write=verify.get('resources',{}).get('write_paths',[])
if verify_write!=['.team/nodes/baseline-bundle-verify/']:
    fail('fresh verify write scope mismatch')
want_artifacts={
 '.team/nodes/baseline-bundle-verify/VERDICT.md',
 '.team/nodes/baseline-bundle-verify/RETRIEVE.md',
 '.team/nodes/baseline-bundle-verify/INSTALL.md',
 '.team/nodes/baseline-bundle-verify/MUTATION.md',
 '.team/nodes/baseline-bundle-verify/VERIFY.json',
}
if set(verify.get('handoff',{}).get('required_artifacts',[]))!=want_artifacts:
    fail('fresh verify five-file handoff mismatch')

edges={(edge.get('from'),edge.get('to'),edge.get('condition')) for edge in d.get('dependencies',[])}
expected={(task_id,ids['verify'],'requires_success') for task_id in consume}
expected |= {
 (ids['verify'],ids['user'],'requires_success'),
 (ids['user'],ids['migrate'],'requires_success'),
 (ids['migrate'],ids['measure'],'requires_success'),
 (ids['measure'],ids['final'],'requires_success'),
}
if edges!=expected:
    fail('dependency graph weakened or changed')
parallel=d.get('parallelism')
if parallel!=[{'failure_policy':'halt','group':'successor11-consume-wave','max_concurrency':4}]:
    fail('four-command frontier parallelism mismatch')

downstream={
 ids['user']:('M.baseline-bundle.successor7-user','.team/ledgers/acceptance/baseline-bundle-successor7-user-gate.sh'),
 ids['migrate']:('M.baseline-bundle.successor7-migrate','.team/ledgers/acceptance/baseline-bundle-successor7-migrate.sh'),
 ids['measure']:('M.baseline-bundle.successor7-measure','.team/ledgers/acceptance/baseline-bundle-successor7-measure.sh'),
}
for task_id,(acc,path) in downstream.items():
    task=tasks[task_id]
    if task.get('acceptance',{}).get('required')!=[acc]:
        fail(task_id+' required weakened')
    checks=task.get('acceptance',{}).get('mechanical',[])
    if len(checks)!=1 or checks[0].get('argv')!=['/bin/sh',path]:
        fail(task_id+' gate changed')
final_required=['M.baseline-bundle.successor11-final','M.baseline-bundle.successor7-real-chain']
if tasks[ids['final']].get('acceptance',{}).get('required')!=final_required:
    fail('final required weakened')
final_checks={x.get('acceptance_id'):x for x in tasks[ids['final']].get('acceptance',{}).get('mechanical',[])}
if set(final_checks)!=set(final_required):
    fail('final mechanical set mismatch')
if final_checks[final_required[0]].get('argv')!=['/bin/sh','.team/ledgers/acceptance/baseline-bundle-successor11-final.sh']:
    fail('successor11 final gate changed')

if 'A/B/A/B' not in tasks[ids['measure']].get('title','') or 'n>=10' not in tasks[ids['measure']].get('title','') or 'nearest-rank' not in tasks[ids['measure']].get('title','') or 'B/A<=1.10' not in tasks[ids['measure']].get('title',''):
    fail('measure 1.10 contract weakened')
if '秒开、没有空白' not in tasks[ids['user']].get('title',''):
    fail('real-device user gate weakened')
if any(task.get('resources',{}).get('provenance',{}).get('revision')!='3597b823204c7d25d5a77367bf2022347532e5d3' for task in tasks.values()):
    fail('resource provenance mismatch')
all_text='\n'.join(task.get('title','')+'\n'+'\n'.join(task.get('resources',{}).get('read_paths',[])) for task in tasks.values())
for token in ('ebd0dc5c285ee65244824b99db6667a1bc569c83','3597b823204c7d25d5a77367bf2022347532e5d3','13c301fd086092b02e1cb8535d1eff38ffcf0173','7c1a856ba0043c87b1aeb9ed8ffac0fefe9ebfce','7485102b26ed34eb828e94900902147d5e00e995'):
    if token not in all_text:
        fail('frozen provenance token missing: '+token[:10])
if 'baseline-bundle-successor6-verify.sh' in all_text or 'baseline-bundle-successor7-verify.sh' in all_text:
    fail('legacy verify gate returned')
roles=d.get('roles',{})
seat_expect={'verify':'sampler-review-luna2','user':'takeover-codex-luna','migrate':'sampler-dev-luna2','measure':'sampler-dev-luna2','final':'sampler-review-luna2'}
for role_name,agent in seat_expect.items():
    if roles.get(role_name,{}).get('seat',{}).get('agent')!=agent:
        fail(role_name+' seat mismatch')
print('SUCCESSOR11_STRUCTURE tasks=9 frontier=four-command successor10_r5=frozen fresh_verify=three-required legacy_verify=absent downstream=unchanged ratio=1.10 user_gate=real-device statuses=none')
PY
rc=$?
case "$rc" in 0) exit 0 ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "structure judge unsupported rc=$rc" ;; esac
