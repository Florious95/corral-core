#!/usr/bin/env python3
"""Hosted-only failure-path checks around the immutable R3 runner.

No fake server, protocol or App. Inject process startup failure, an actual
JUnit precondition failure, or SIGSTOP of the owned real Go fixture process.
These are apparatus cleanup checks, never A6 behavior acceptance.
"""
import argparse
import json
import os
from pathlib import Path
import runpy
import signal
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser()
p.add_argument('--runner',type=Path,required=True)
p.add_argument('--binary',type=Path,required=True)
p.add_argument('--root',type=Path,required=True)
p.add_argument('--fault',choices=('fixture-start','proxy-start','junit-assert','fixture-stop'),required=True)
a=p.parse_args()
assert sys.platform=='darwin' and os.environ.get('GITHUB_ACTIONS')=='true', 'hosted macOS only'
runner=a.runner.resolve(); binary=a.binary.resolve(); root=a.root.resolve()
assert runner.is_file() and binary.is_file() and not root.exists()
original=subprocess.Popen
fired=threading.Event()
workers=[]

def injected(command,*args,**kwargs):
    words=[str(w) for w in command]
    fixture=words[0]==str(binary)
    proxy=any(w.endswith('/tools/perf16-a6/proxy.py') for w in words)
    gradle=words[0]=='./gradlew'
    if (a.fault=='fixture-start' and fixture) or (a.fault=='proxy-start' and proxy):
        fired.set()
        raise OSError('A6 intentional '+a.fault)
    if a.fault=='junit-assert' and gradle:
        # The unchanged named JUnit starts, then fails its first stage equality.
        kwargs['env']=dict(kwargs['env'],PERF16_A6_STAGE='intentional-wrong-stage')
        fired.set()
    child=original(command,*args,**kwargs)
    if a.fault=='fixture-stop' and fixture:
        def suspend():
            deadline=time.monotonic()+45
            while time.monotonic()<deadline and child.poll() is None:
                if (root/'endpoint').exists():
                    os.killpg(child.pid,signal.SIGSTOP)
                    fired.set()
                    return
                time.sleep(.02)
        worker=threading.Thread(target=suspend,daemon=True)
        workers.append(worker); worker.start()
    return child

subprocess.Popen=injected
sys.argv=[str(runner),'--stage','ws','--control','candidate','--binary',str(binary),'--root',str(root)]
failed=False
try:
    runpy.run_path(str(runner),run_name='__main__')
except SystemExit as error:
    failed=error.code not in (None,0)
finally:
    subprocess.Popen=original
    for worker in workers: worker.join(timeout=1)
assert fired.is_set(), 'fault did not reach intended interface'
assert failed, 'fault was incorrectly accepted'
cleanup=json.loads((root/'cleanup.json').read_text())
result=json.loads((root/'result.json').read_text())
assert result['behavior_pass'] is False and result['error']
if a.fault=='junit-assert':
    suite=ET.parse(root/'junit.xml').getroot()
    assert suite.get('tests')=='1' and suite.get('skipped','0')=='0'
    cases=suite.findall('testcase')
    assert [c.get('name') for c in cases]==['realGoServerTmuxWsQueueOverflow_servicePumpReauthListSubscribeClearsStaticScreen']
    failures=cases[0].findall('failure')
    assert any('intentional-wrong-stage' in (f.get('message','')+(f.text or '')) for f in failures), 'did not execute the injected JUnit assertion'
if a.fault=='fixture-stop':
    assert cleanup['exits']['fixture']==-signal.SIGKILL, cleanup
    assert 'fixture required TERM' in cleanup['errors'] and 'fixture required KILL' in cleanup['errors'], cleanup
    forbidden=[e for e in cleanup['errors'] if 'survived' in e or 'tmux/listeners' in e]
    assert not forbidden, forbidden
else:
    assert not cleanup['errors'], cleanup
(root/'fault-control.json').write_text(json.dumps({'fault':a.fault,'fired':True,'expected_red':True,'cleanup_checked':True,'behavior_pass':False},indent=2))
print('A6 apparatus failure-path check passed:',a.fault)
