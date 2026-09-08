#!/usr/bin/env python3
"""One exact PERF15 hosted wave; source carrier never claims product acceptance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import re
import sys
from ownership import execute, adopted_negative_control

assert os.environ.get('GITHUB_ACTIONS') == 'true', 'hosted execution only'
carrier = Path.cwd()
root = Path(os.environ['RUNNER_TEMP'])/'perf15'
root.mkdir(exist_ok=False)
fixed_base = 'b98504e742ed1e7c7475767c512934b07eac592b'
base = '1fa3b651c613f7b973b5187429d8e9e50d9d3d45'
expected_tree = os.environ['PERF15_EXPECTED_TREE']
patch = carrier/'tools/perf15/catalog-refresh.patch'
assert hashlib.sha256(patch.read_bytes()).hexdigest() == os.environ['PERF15_PATCH_SHA256']
receipts = []
failed = []

def cmd(args, cwd=carrier):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()

def checkout(name, ref=base):
    path = root/name
    subprocess.run(['git','clone','--no-hardlinks','--no-checkout',str(carrier),str(path)], check=True)
    subprocess.run(['git','checkout','--detach',ref], cwd=path, check=True)
    return path

def apply_candidate(path):
    subprocess.run(['git','apply','--index',str(patch)], cwd=path, check=True)
    tree = cmd(['git','write-tree'],path)
    assert tree == expected_tree, (tree,expected_tree)

candidate = checkout('candidate')
apply_candidate(candidate)
(root/'identity.json').write_text(json.dumps({'carrier':cmd(['git','rev-parse','HEAD']), 'base':base,'tested_tree':expected_tree,'patch_sha256':hashlib.sha256(patch.read_bytes()).hexdigest(),'status':'unverified_before_execution'},indent=2))
# The compile-only gate proves no behavior. Never use zero matching tests as green.
compiled = execute(['go','test','-race','-count=1','-run','^$','./internal/api','./internal/bridge','./internal/protocol'],candidate,root/'compile.log',root/'compile-cleanup.json',300)
code = compiled['exit']
(root/'compile.json').write_text(json.dumps({'exit':code,'executed_tests':0,'behavior_pass':False}))
assert code == 0, 'candidate compile failed; no behavior classification'
# Artifacts are execution inputs for this owner's real App A/B; producing them
# is separate from accepting any behavioral or performance gate.
fixed = checkout('fixed-base',fixed_base)
binaries=root/'binaries';binaries.mkdir()
built=[]
for label,path in [('candidate',candidate),('b985',fixed)]:
    binary=binaries/(label+'-darwin-arm64-agentmirrord')
    command=['env','GOOS=darwin','GOARCH=arm64','CGO_ENABLED=0','go','build','-trimpath','-buildvcs=true','-o',str(binary),'./cmd/agentmirrord']
    outcome=execute(command,path,root/('build-'+label+'.log'),root/('build-'+label+'-cleanup.json'),300)
    assert outcome['exit']==0 and not outcome['external_timeout'],'binary build failed: '+label
    data=binary.read_bytes()
    built.append(dict(label=label,tree=cmd(['git','write-tree'],path),checkout_head=cmd(['git','rev-parse','HEAD'],path),sha256=hashlib.sha256(data).hexdigest(),bytes=len(data),command=command,behavior_pass=False))
    (root/'BINARY-MANIFEST.json').write_text(json.dumps(built,indent=2))


def run(label, path, package, pattern, names, red=None):
    log = root/(label+'.jsonl')
    command = ['go','test','-race','-count=1','-timeout','120s','-json',package,'-run',pattern]
    lifecycle = execute(command,path,log,root/(label+'-cleanup.json'),150)
    code = lifecycle['exit']
    records=[]
    for line in log.read_text().splitlines():
        try: records.append(json.loads(line))
        except json.JSONDecodeError: pass
    ran={r.get('Test') for r in records if r.get('Action')=='run'}
    passed={r.get('Test') for r in records if r.get('Action')=='pass'}
    failures={r.get('Test') for r in records if r.get('Action')=='fail' and r.get('Test')}
    skips={r.get('Test') for r in records if r.get('Action')=='skip'}
    output=''.join(r.get('Output','') for r in records)
    accepted = not lifecycle['external_timeout'] and code is not None and not skips and set(names)<=ran and 'DATA RACE' not in output and 'panic:' not in output
    if red:
        # Only the declared assertion leaf and its ancestors may fail.
        allowed=set()
        for name in names:
            parts=name.split('/')
            allowed.update('/'.join(parts[:i]) for i in range(1,len(parts)+1))
        diagnostics=[r.get('Output','').strip() for r in records if r.get('Test') in allowed and re.match(r'^\s+\S+\.go:\d+: ', r.get('Output',''))]
        accepted &= code != 0 and bool(failures) and failures<=allowed and set(names)<=failures and len(diagnostics)==1 and red in diagnostics[0]
    else:
        accepted &= code == 0 and not failures and set(names)<=passed
    receipt={'label':label,'cleanup':label+'-cleanup.json','command':command,'exit':code,'ran':sorted(n for n in ran if n),'passed':sorted(n for n in passed if n),'failed':sorted(failures),'skipped':sorted(n for n in skips if n),'expected_red':red,'accepted_scoped_result':bool(accepted),'tree':cmd(['git','write-tree'],path)}
    # Mutations are unstaged and have their exact diff archived independently.
    receipts.append(receipt)
    (root/'receipts.json').write_text(json.dumps(receipts,indent=2))
    if not accepted:failed.append(label)

# Real fixture failures are apparatus controls, never product negative controls.
for fault in ('go-timeout','outer-timeout','fixture-start','handoff-intent','handoff-post'):
    label='apparatus-'+fault
    log=root/(label+'.jsonl')
    command=['go','test','-race','-count=1','-timeout','2s' if fault=='go-timeout' else '120s','-json','./internal/api','-run','^TestListScanBlockedKnownSubscribeGetsSnapshot$/^Discover$']
    outcome=execute(command,candidate,log,root/(label+'-cleanup.json'),150,fault)
    raw=log.read_text()
    expected = ('panic: test timed out after 2s' in raw and outcome['exit'] not in (None,0)) if fault=='go-timeout' else outcome['external_timeout'] if fault=='outer-timeout' else ('intentional fixture-start failure' in raw and outcome['exit'] not in (None,0))
    if fault.startswith('handoff-'):
        expected=outcome['external_timeout'] and outcome['enrollment_sealed'] and any(p['group'] != outcome['owner']['group'] for x in outcome['fixtures'] for p in x.get('processes',[])) and any(x.get('handoff')==fault.removeprefix('handoff-') and x.get('cleanup_started') for x in outcome['fixtures'])
    accepted=expected and outcome['fixture_registered'] and outcome['cleanup_proven']
    (root/(label+'.json')).write_text(json.dumps({'apparatus_control':True,'behavior_pass':False,'expected_fault_observed':expected,'cleanup_proven':outcome['cleanup_proven'],'accepted':accepted},indent=2))
    assert accepted, 'apparatus fault control failed; stop wave: '+fault

adopted_negative_control(candidate,root)

old = checkout('stack-base')
relative='internal/api/scan_reader_boundary_test.go'
for label,path in [('b985',fixed),('stack-1fa3',old)]:
    (path/relative).write_bytes((candidate/relative).read_bytes())
    for menu,parent in [('list','TestListScanBlockedKnownSubscribeGetsSnapshot'),('level2','TestLevel2ScanBlockedKnownSubscribeGetsSnapshot')]:
        for stage in ('Discover','Sample'):
            name=parent+'/'+stage
            run('base-red-'+label+'-'+menu+'-'+stage,path,'./internal/api','^'+parent+'$/^'+stage+'$',[name],red='catalog gate blocked known-ref SNAPSHOT')

# The two old scrollback readers hid text/error frames. The identical repaired
# observation is run against fixed base and stack to distinguish inherited
# behavior from a catalog-refresh regression; neither red is waived.
for label,path in [('b985',fixed),('stack-1fa3',old)]:
    relative=Path('internal/api/api_tmux_test.go')
    (path/relative).write_bytes((candidate/relative).read_bytes())
    for name in ('TestScrollbackConvergedRange','TestScrollbackExactHeaderBytes'):
        run('baseline-compat-'+label+'-'+name,path,'./internal/api','^'+name+'$',[name])

parents=['TestListScanBlockedKnownSubscribeGetsSnapshot','TestLevel2ScanBlockedKnownSubscribeGetsSnapshot','TestScanSingleFlightAndOnePendingGeneration','TestCatalogSnapshotSequenceAtomicCommit','TestListReplyDeltaWatermarkContinuity','TestLevel2WorkspaceEpochRejectsOldCompletion','TestScanWaiterDeadlineCancelAndOverload','TestScanCoordinatorShutdownAndIdle']
children={parents[0]:['Discover','Sample'], parents[1]:['Discover','Sample'], parents[4]:['slow-peer'], parents[5]:['before-new-completion','after-new-completion'], parents[6]:['zero-wire-rejected','zero-internal-cancel','queued-deadline','timely-duplicate-writes','per-connection','global','deadline','scan-deadline','cancel-one-preserves-other'], parents[7]:['Discover','Sample','pending-List-gets-real-close','zero-auth-zero-L2']}
for name in parents:
    run('candidate-'+name,candidate,'./internal/api','^'+name+'$',[name]+[name+'/'+child for child in children.get(name,[])])

# Exact existing PR20 27-test selection, plus its N7 publication gate. The
# historical fixed-base 2 reds / 8 mutation receipts are not silently relabeled.
legacy = (candidate/'.github/workflows/perf16-repair-bounded.yml').read_text()
block = legacy.split("done <<'TESTS'\n")[1].split('\n          TESTS')[0]
compat = {}
for line in block.splitlines():
    package,name=line.strip().split()
    compat.setdefault(package,[]).append(name)
assert sum(map(len,compat.values())) == 27
compat['./internal/api'] += ['TestConnInitPublicationAndCallbackIsolation','TestRefreshOnOpenListRescans','TestRefreshOnOpenListFailureKeepsCache','TestRefreshOnOpenLevel2Resubscribe','TestRefreshOnOpenLevel2ZeroSubscribersNoPoll','TestListDeltaSeqMonotonic','TestDiscoveryRecoveryResumesDelta','TestDiscoveryRecoveryReachesConnectedClientFromStartFailure','TestFourAxesPropagateThroughListingDeltaAndLevel2Key','TestNodeprobeFailureDoesNotPublishEmptyReplacement','TestEnterLevel2TenSecondCounts','TestEnterLevel2TenSecondCountsHeightJitter']
# Derive existing local compatibility names from the preserved source files;
# fail if a declared file ceases to contain tests, never silently run zero.
import re
for filename in ('api_test.go','api_tmux_test.go','level2_test.go','name_projection_test.go','discovery_failure_test.go','idle_gate_test.go'):
    names=re.findall(r'^func (Test\w+)\(', (candidate/'internal/api'/filename).read_text(),re.M)
    assert names,filename
    compat['./internal/api']+=names
for package,names in compat.items():
    names=sorted(set(names))
    # Each named test gets its own bounded result; no unrelated full-suite red
    # or skip can be hidden by a cached aggregate exit.
    for name in names:
        required=[name]+([name+'/bridge',name+'/ws'] if name=='TestOverflowDisconnectReplayStaticOracle' else [])
        run('compat-'+name,candidate,package,'^'+name+'$',required)

mutants=[('sync-list','TestListScanBlockedKnownSubscribeGetsSnapshot/Sample','catalog gate blocked known-ref SNAPSHOT'),('sync-level2','TestLevel2ScanBlockedKnownSubscribeGetsSnapshot/Sample','catalog gate blocked known-ref SNAPSHOT'),('parallel-scan','TestScanSingleFlightAndOnePendingGeneration','parallel scan worker'),('split-publication','TestCatalogSnapshotSequenceAtomicCommit','mixed catalog/snapshot generation'),('writer-epoch','TestLevel2WorkspaceEpochRejectsOldCompletion','queued old workspace leaked'),('completion-epoch','TestLevel2WorkspaceEpochRejectsOldCompletion','stale completion admitted')]
for mutation,name,assertion in mutants:
    mutant=checkout('mutation-'+mutation);apply_candidate(mutant)
    subprocess.run([sys.executable,str(carrier/'tools/perf15/mutations.py'),mutation,str(mutant)],check=True)
    diff=subprocess.check_output(['git','diff'],cwd=mutant)
    (root/('mutation-'+mutation+'.diff')).write_bytes(diff)
    (root/('mutation-'+mutation+'.sha256')).write_text(hashlib.sha256(diff).hexdigest()+'\n')
    selected = [name+'/'+order for order in ('before-new-completion','after-new-completion')] if mutation in ('writer-epoch','completion-epoch') else [name]
    for leaf in selected:
        pattern='/'.join('^'+part+'$' for part in leaf.split('/'))
        run('mutation-'+mutation+'-'+leaf.split('/')[-1],mutant,'./internal/api',pattern,[leaf],red=assertion)

(root/'result.json').write_text(json.dumps({'failed_gates':failed,'all_scoped_gates_accepted':not failed,'final_product_acceptance':False,'fixed_performance_baseline':'b98504e742ed1e7c7475767c512934b07eac592b'},indent=2))
if failed:raise SystemExit('failed gates: '+', '.join(failed))
print('PERF15 scoped hosted wave accepted; composition/user-performance remains separate')
