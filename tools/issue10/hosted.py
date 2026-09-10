#!/usr/bin/env python3
"""One bounded hosted execution; never classify compile errors as behavior red."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

assert os.environ.get('GITHUB_ACTIONS') == 'true'
repo = Path.cwd()
out = Path(os.environ['RUNNER_TEMP']) / 'issue10-evidence'
out.mkdir()
base = 'b98504e742ed1e7c7475767c512934b07eac592b'
receipts = []

def run(label, argv, cwd=repo, timeout=360):
    with (out / (label + '.log')).open('wb') as log:
        result = subprocess.run(argv, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    receipts.append(dict(label=label, command=argv, exit=result.returncode))
    (out / 'commands.json').write_text(json.dumps(receipts, indent=2))
    return result.returncode

head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
assert not subprocess.check_output(['git', 'status', '--porcelain'])
(out / 'identity.json').write_text(json.dumps(dict(head=head, tree=tree, base=base), indent=2))
old = Path(os.environ['RUNNER_TEMP']) / 'issue10-base'
subprocess.run(['git', 'worktree', 'add', '--detach', str(old), base], check=True)
for name in ['scan_filter_test.go', 'socket_inventory_regression_test.go']:
    (old / 'internal/discovery' / name).write_bytes((repo / 'internal/discovery' / name).read_bytes())
red_names = ['TestDiscoverIncludesTeamSocketWithSamePaneIdentity', 'TestDiscoverScanFilterCommandBoundary']
red = run('baseline', ['go', 'test', '-race', '-count=1', '-timeout=3m', '-json', './internal/discovery', '-run', '^(' + '|'.join(red_names) + ')$'], old)
green = run('candidate', ['go', 'test', '-race', '-count=1', '-timeout=5m', '-json', './internal/discovery'])

def events(label):
    return [json.loads(line) for line in (out / (label + '.log')).read_text().splitlines() if line.startswith('{')]

r = events('baseline')
g = events('candidate')
assert red == 1, ('expected behavior exit1', red)
for name in red_names:
    assert sum(e.get('Test') == name and e['Action'] == 'run' for e in r) == 1
    assert sum(e.get('Test') == name and e['Action'] == 'fail' for e in r) == 1
assert 'DATA RACE' not in (out / 'baseline.log').read_text()
assert green == 0
assert not any(e['Action'] in ('skip', 'fail') for e in g)
required = red_names + ['TestDiscoverIncludesEveryCurrentUserSocket', 'TestIssue10ScopedLifecycle']
for name in required:
    assert sum(e.get('Test') == name and e['Action'] == 'pass' for e in g) == 1, name
assert 'DATA RACE' not in (out / 'candidate.log').read_text()
assert run('build', ['go', 'build', '-trimpath', '-o', str(out / 'agentmirrord'), './cmd/agentmirrord']) == 0
assert run('build-info', ['go', 'version', '-m', str(out / 'agentmirrord')]) == 0
info = (out / 'build-info.log').read_text()
assert 'vcs.revision=' + head in info and 'vcs.modified=false' in info
assert 'GOOS=darwin' in info and 'GOARCH=arm64' in info
binary = (out / 'agentmirrord').read_bytes()
(out / 'result.json').write_text(json.dumps(dict(head=head, tree=tree, base=base, baseline_named_fail=red_names, candidate_named_pass=required, candidate_test_pass=sum(e['Action']=='pass' and bool(e.get('Test')) for e in g), binary_sha256=hashlib.sha256(binary).hexdigest(), binary_bytes=len(binary), app_acceptance=False), indent=2))
