#!/usr/bin/env python3
"""Fail-closed provenance/static gate for the accepted nodeprobe cutover."""
from pathlib import Path
import hashlib, json, subprocess, sys

ROOT = Path(__file__).resolve().parents[2]
UP = Path('/Users/alauda/team-agent-scratch/nodeprobe')
COMMIT = '863c316ff66add549f8fa6dd215cedeedd037f8b'
TREE = 'ce667e3c464d0efb2ac9483862ff84a38008e674'
SYNC = ['Cargo.toml','Cargo.lock','src/classify.rs','src/lib.rs','src/main.rs','src/pi_activity.rs','src/proctree.rs','src/providers.rs','src/web.rs','pi/nodeprobe-pi-activity.js','tests/fake_tmux.rs','tests/pi-deterministic.js','tests/pi_extension.mjs','README.md','docs/api-architecture.md','docs/consumer-migration-map.md']

def fail(msg): print('nodeprobe-gate:', msg, file=sys.stderr); raise SystemExit(1)
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
if subprocess.check_output(['git','-C',str(UP),'rev-parse',COMMIT+'^{tree}'],text=True).strip()!=TREE: fail('accepted tree mismatch')
accepted_hashes={}
for rel in SYNC:
    accepted=subprocess.check_output(['git','-C',str(UP),'show',f'{COMMIT}:{rel}'])
    product=(ROOT/'tools/nodeprobe'/rel).read_bytes()
    if product!=accepted: fail(f'synchronized blob differs: {rel}')
    accepted_hashes[rel]=hashlib.sha256(accepted).hexdigest()
manifest=json.loads((ROOT/'server/internal/nodeprobe/accepted-source.json').read_text())
if (manifest['source_commit'],manifest['source_tree'])!=(COMMIT,TREE): fail('manifest source coordinate mismatch')
if manifest.get('sources') != accepted_hashes: fail('manifest synchronized-path hashes mismatch')
coords={'tools/nodeprobe/fixtures/titles.tsv':'c0ec2de2e9aae61c200e1fd65e9865664bd4bcddc4a17361c1eb6bb2673e91ae','tools/nodeprobe/fixtures/providers.tsv':'c68f50115b33ae6a6806b463cc27c71d8bbfc92273435a533502e9bb84b8b522'}
for rel,want in coords.items():
    if sha(ROOT/rel)!=want: fail(f'canonical corpus mismatch: {rel}')
tracked=subprocess.check_output(['git','-C',str(ROOT),'ls-files'],text=True).splitlines()
for name in ('titles.tsv','providers.tsv'):
    hits=[p for p in tracked if Path(p).name==name]
    if hits!=[f'tools/nodeprobe/fixtures/{name}']: fail(f'{name} authorities={hits}')
prod='\n'.join((ROOT/p).read_text(errors='ignore') for p in tracked if (ROOT/p).is_file() and (p.startswith('server/') or p.startswith('app/') or p == 'tools/nodeprobe.sh') and p.endswith(('.go','.kt','.kts','.sh','.rs')) and not p.endswith('_test.go'))
for forbidden in ('ProviderFinder','procFinder','identifyModel','filterModel','classifyForProvider','registerL2Detector','backgroundTasksFor','provider.Load'):
    if forbidden in prod: fail(f'legacy authority edge remains: {forbidden}')
if 'cargo build' in (ROOT/'tools/nodeprobe.sh').read_text(): fail('runtime Cargo build remains')
changed=subprocess.check_output(['git','-C',str(ROOT),'diff','--name-only','4605951e427f9ba6627375498dcb3c757c05bf36'],text=True).splitlines()
for p in changed:
    if p in coords: fail(f'canonical corpus modified: {p}')
    if 'provider' in p.lower() and ('drawable' in p or 'icon' in p.lower()): fail(f'provider UI changed: {p}')
    if 'session-ui' in p: fail(f'session-ui changed: {p}')
print(json.dumps({'status':'passed','accepted_commit':COMMIT,'accepted_tree':TREE,'synchronized_files':len(SYNC),'canonical_corpora':list(coords),'changed_paths':len(changed)},sort_keys=True))
