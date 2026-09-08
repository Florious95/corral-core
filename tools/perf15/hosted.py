#!/usr/bin/env python3
"""One exact PERF15 hosted wave; source carrier never claims product acceptance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import re
import sys

assert os.environ.get('GITHUB_ACTIONS') == 'true', 'hosted execution only'
carrier = Path.cwd()
root = Path(os.environ['RUNNER_TEMP'])/'perf15'
root.mkdir(exist_ok=False)
base = '1fa3b651c613f7b973b5187429d8e9e50d9d3d45'
expected_tree = os.environ['PERF15_EXPECTED_TREE']
patch = carrier/'tools/perf15/catalog-refresh.patch'
assert hashlib.sha256(patch.read_bytes()).hexdigest() == os.environ['PERF15_PATCH_SHA256']
receipts = []
failed = []

def cmd(args, cwd=carrier):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()

def checkout(name):
    path = root/name
    subprocess.run(['git','clone','--no-hardlinks','--no-checkout',str(carrier),str(path)], check=True)
    subprocess.run(['git','checkout','--detach',base], cwd=path, check=True)
    return path

def apply_candidate(path):
    subprocess.run(['git','apply','--index',str(patch)], cwd=path, check=True)
    tree = cmd(['git','write-tree'],path)
    assert tree == expected_tree, (tree,expected_tree)

candidate = checkout('candidate')
apply_candidate(candidate)
(root/'identity.json').write_text(json.dumps({'carrier':cmd(['git','rev-parse','HEAD']), 'base':base,'tested_tree':expected_tree,'patch_sha256':hashlib.sha256(patch.read_bytes()).hexdigest(),'status':'unverified_before_execution'},indent=2))
# The compile-only gate proves no behavior. Never use zero matching tests as green.
with (root/'compile.log').open('wb') as log:
    code = subprocess.run(['go','test','-race','-count=1','-run','^$','./internal/api','./internal/bridge','./internal/protocol'],cwd=candidate,stdout=log,stderr=subprocess.STDOUT,timeout=300).returncode
(root/'compile.json').write_text(json.dumps({'exit':code,'executed_tests':0,'behavior_pass':False}))
assert code == 0, 'candidate compile failed; no behavior classification'

def run(label, path, package, pattern, names, red=None):
    log = root/(label+'.jsonl')
    command = ['go','test','-race','-count=1','-timeout','120s','-json',package,'-run',pattern]
    with log.open('wb') as output:
        code = subprocess.run(command,cwd=path,stdout=output,stderr=subprocess.STDOUT,timeout=150).returncode
    records=[]
    for line in log.read_text().splitlines():
        try: records.append(json.loads(line))
        except json.JSONDecodeError: pass
    ran={r.get('Test') for r in records if r.get('Action')=='run'}
    passed={r.get('Test') for r in records if r.get('Action')=='pass'}
    failures={r.get('Test') for r in records if r.get('Action')=='fail' and r.get('Test')}
    skips={r.get('Test') for r in records if r.get('Action')=='skip'}
    output=''.join(r.get('Output','') for r in records)
    accepted = not skips and set(names)<=ran and 'DATA RACE' not in output and 'panic:' not in output
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
    receipt={'label':label,'command':command,'exit':code,'ran':sorted(n for n in ran if n),'passed':sorted(n for n in passed if n),'failed':sorted(failures),'skipped':sorted(n for n in skips if n),'expected_red':red,'accepted_scoped_result':bool(accepted),'tree':cmd(['git','write-tree'],path)}
    # Mutations are unstaged and have their exact diff archived independently.
    receipts.append(receipt)
    (root/'receipts.json').write_text(json.dumps(receipts,indent=2))
    if not accepted:failed.append(label)

old = checkout('old-base')
relative='internal/api/scan_reader_boundary_test.go'
(old/relative).write_bytes((candidate/relative).read_bytes())
for menu,parent in [('list','TestListScanBlockedKnownSubscribeGetsSnapshot'),('level2','TestLevel2ScanBlockedKnownSubscribeGetsSnapshot')]:
    for stage in ('Discover','Sample'):
        name=parent+'/'+stage
        run('base-red-'+menu+'-'+stage,old,'./internal/api','^'+parent+'$/^'+stage+'$',[name],red='catalog gate blocked known-ref SNAPSHOT')

parents=['TestListScanBlockedKnownSubscribeGetsSnapshot','TestLevel2ScanBlockedKnownSubscribeGetsSnapshot','TestScanSingleFlightAndOnePendingGeneration','TestCatalogSnapshotSequenceAtomicCommit','TestListReplyDeltaWatermarkContinuity','TestLevel2WorkspaceEpochRejectsOldCompletion','TestScanWaiterDeadlineCancelAndOverload','TestScanCoordinatorShutdownAndIdle']
children={parents[0]:['Discover','Sample'], parents[1]:['Discover','Sample'], parents[4]:['slow-peer'], parents[6]:['per-connection','global','deadline','scan-deadline','cancel-one-preserves-other'], parents[7]:['Discover','Sample','pending-List-gets-real-close','zero-auth-zero-L2']}
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
    pattern='/'.join('^'+part+'$' for part in name.split('/'))
    run('mutation-'+mutation,mutant,'./internal/api',pattern,[name],red=assertion)

(root/'result.json').write_text(json.dumps({'failed_gates':failed,'all_scoped_gates_accepted':not failed,'final_product_acceptance':False,'fixed_performance_baseline':'b98504e742ed1e7c7475767c512934b07eac592b'},indent=2))
if failed:raise SystemExit('failed gates: '+', '.join(failed))
print('PERF15 scoped hosted wave accepted; composition/user-performance remains separate')
