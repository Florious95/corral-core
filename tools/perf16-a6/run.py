#!/usr/bin/env python3
"""Run one real A6 stage; own every process and gate success after cleanup."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--stage', choices=('bridge', 'ws'), required=True)
parser.add_argument('--root', type=Path, required=True)
parser.add_argument('--binary', type=Path, required=True)
parser.add_argument('--control', choices=('candidate', 'no-abort', 'shutdown-race', 'screen-corruption'), default='candidate')
args = parser.parse_args()
repo = Path.cwd()
root = args.root.resolve()
root.mkdir(parents=True, exist_ok=False)
sock = str(root / 'tmux')
assert len(os.fsencode(sock)) < 104, 'socket path too long'
env = dict(os.environ)
env.pop('TMUX', None)
env.update(PERF16_A6_FIXTURE_DIR=str(root), PERF16_A6_STAGE=args.stage,
           PERF16_A6_TMUX_SOCKET=sock, PERF16_A6_TMUX_SESSION='a6',
           PERF16_A6_TOKEN='perf16-a6-token', PERF16_A6_CONTROL=args.control,
           PERF16_A6_BURST_DONE_FILE=str(root/'burst-done'),
           PERF16_A6_BRIDGE_READY_FILE=str(root/'bridge-ready'))
processes = {}
handles = []
tmux_pids = []
owned_socket = False
ports = []
cleanup_errors = []
result_error = None
exits = {}

def spawn(name, command, cwd=repo):
    log = (root/(name+'.log')).open('wb')
    handles.append(log)
    proc = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    processes[name] = proc
    return proc

def command(argv, check=True):
    return subprocess.run(argv, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=3, check=check)

def prove_socket():
    actual = command(['tmux','-S',sock,'list-sessions','-F','#{socket_path}']).stdout.strip()
    assert actual == sock, f'wrong socket {actual!r}'

def tmux(*args):
    prove_socket()
    result = command(['tmux','-S',sock,*args])
    prove_socket()
    return result.stdout.strip()

def wait_for(description, predicate, seconds):
    deadline = time.monotonic()+seconds
    while time.monotonic() < deadline:
        if predicate(): return
        for name in ('fixture','proxy'):
            if name in processes and processes[name].poll() is not None:
                raise AssertionError(f'{name} exited while waiting for {description}')
        time.sleep(.02)
    raise AssertionError(f'timed out: {description}')

def listening(port):
    with socket.socket() as conn:
        conn.settimeout(.2)
        return conn.connect_ex(('127.0.0.1',port)) == 0

def terminate(name, grace):
    proc=processes.get(name)
    if proc is None: return
    try:
        code=proc.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        cleanup_errors.append(name+' required TERM')
        os.killpg(proc.pid,signal.SIGTERM)
        try: code=proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            cleanup_errors.append(name+' required KILL')
            os.killpg(proc.pid,signal.SIGKILL)
            code=proc.wait(timeout=2)
    exits[name]=code
    # A group can outlive its leader (Gradle/JVM children). Do not equate the
    # direct child's exit with cleanup; terminate only this owned group.
    group_deadline=time.monotonic()+2
    while True:
        try: os.killpg(proc.pid,0)
        except ProcessLookupError: return
        if time.monotonic()>=group_deadline: break
        time.sleep(.02)
    cleanup_errors.append(name+' left process-group descendants')
    os.killpg(proc.pid,signal.SIGKILL)
    deadline=time.monotonic()+2
    while time.monotonic()<deadline:
        try: os.killpg(proc.pid,0)
        except ProcessLookupError: return
        time.sleep(.02)
    cleanup_errors.append(name+' process group survived KILL deadline')

def cancelled(signum, frame):
    raise RuntimeError(f'cancelled by signal {signum}')

signal.signal(signal.SIGTERM,cancelled)
signal.signal(signal.SIGINT,cancelled)
try:
    assert not Path(sock).exists()
    source=repo/'tools/perf16-a6/source.py'
    import shlex
    command(['tmux','-S',sock,'-f','/dev/null','new-session','-d','-x','120','-y','40','-s','a6','-c',str(root),'python3 '+shlex.quote(str(source))])
    prove_socket()
    owned_socket = True
    tmux_pids=[int(p) for p in tmux('list-panes','-F','#{pid} #{pane_pid}').split()]
    pane=tmux('display-message','-p','-t','a6:0.0','#{pane_id}')
    env['PERF16_A6_TMUX_PANE']=pane
    env['PERF16_A6_REF']=sock+'\x1f'+pane
    spawn('fixture',[str(args.binary.resolve()),'-test.run=^TestPerf16A6FixtureProcess$','-test.count=1','-test.v','-test.timeout=180s'])
    wait_for('fixture endpoint',lambda:(root/'endpoint').exists(),45)
    url=(root/'endpoint').read_text().strip()
    port=int(url.rsplit(':',1)[1]); ports.append(port)
    direct='ws'+url[4:]+'/ws'
    env['PERF16_A6_DIRECT_WS_URL']=direct
    if args.stage == 'ws':
        with socket.socket() as reserve:
            reserve.bind(('127.0.0.1',0)); proxy_port=reserve.getsockname()[1]
        ports.append(proxy_port)
        spawn('proxy',['python3',str(repo/'tools/perf16-a6/proxy.py'),str(port),str(proxy_port),str(root/'proxy-release')])
        wait_for('proxy listener',lambda:'P16_PROXY_READY' in (root/'proxy.log').read_text(),6)
        env['PERF16_A6_PROXY_WS_URL']=f'ws://127.0.0.1:{proxy_port}/ws'
    else:
        env['PERF16_A6_PROXY_WS_URL']=direct
    method={'bridge':'realGoServerTmuxBridgeOverflow_servicePumpReauthListSubscribeClearsStaticScreen',
            'ws':'realGoServerTmuxWsQueueOverflow_servicePumpReauthListSubscribeClearsStaticScreen'}[args.stage]
    xml=repo/'app/app/build/test-results/testDebugUnitTest/TEST-dev.agentmirror.app.session.Perf16AppRecoveryA6ScenarioTest.xml'
    xml.unlink(missing_ok=True)  # prior stage is already archived; never accept stale JUnit XML
    gradle=spawn('gradle',['./gradlew','--no-daemon','--rerun-tasks','-Pkotlin.compiler.execution.strategy=in-process','-x',':app:compileDebugKotlin','-x',':app:compileDebugUnitTestKotlin',':app:testDebugUnitTest','--tests','dev.agentmirror.app.session.Perf16AppRecoveryA6ScenarioTest.'+method],repo/'app')
    deadline=time.monotonic()+180
    receipt=None
    while gradle.poll() is None:
        loss=root/'loss.json'
        if loss.exists() and receipt is None:
            receipt=json.loads(loss.read_text())
            expected=('mirror_loss: ref='+env['PERF16_A6_REF']+': bridge: subscriber queue overflow' if args.stage=='bridge' else 'mirror_loss: cause=ws: mirror send queue overflow')
            assert receipt['stage']==args.stage and receipt['ref']==env['PERF16_A6_REF']
            assert receipt['accepted_number']==2 and receipt['cause']==expected
            assert receipt['abort_complete'] is True and receipt['queue_remaining']==0
            (root/'proxy-release').touch()
        if time.monotonic()>deadline: raise AssertionError('JUnit process deadline')
        time.sleep(.02)
    exits['gradle']=gradle.returncode
    content=xml.read_bytes(); (root/'junit.xml').write_bytes(content)
    suite=ET.fromstring(content)
    tests=suite.findall('testcase')
    assert len(tests)==1 and tests[0].get('name')==method, 'named JUnit absent'
    assert suite.get('tests')=='1' and suite.get('skipped','0')=='0'
    failures=tests[0].findall('failure')+tests[0].findall('error')
    if args.control=='no-abort':
        assert gradle.returncode!=0 and failures, 'abort-disabled control was not red'
        assert any('no RECONNECTING event' in (f.get('message','')+(f.text or '')) for f in failures), 'negative did not reach App loss oracle'
        healthy=json.loads((root/'healthy-burst.json').read_text())
        assert healthy=={'bytes':16*1024*1024,'contiguous':True,'closed':False}, healthy
        gate=json.loads((root/'bridge-ready').read_text())
        assert gate['stage']==args.stage and gate['ref']==env['PERF16_A6_REF']
        if args.stage=='bridge':
            boundary=json.loads((root/'queue-boundary').read_text())
            assert boundary['conn']==gate['conn'] and boundary['ref']==gate['ref'] and boundary['buffered_loss']==1
            cause='bridge: subscriber queue overflow'
            cause_source='buffered subscriber loss boundary mapped to fixed ErrSubscriberOverflow; abort reason suppressed'
        else:
            boundary=json.loads((root/'writer-ready').read_text())
            assert boundary['conn']==gate['conn'] and boundary['ref']==gate['ref']
            records=[json.loads(line) for line in (root/'fixture.log').read_text().splitlines() if line.startswith('{')]
            assert any(r.get('conn')==gate['conn'] and r.get('msg')=='ws: mirror queue overflow; aborting connection' for r in records), 'target WS queue did not overflow'
            cause='ws: mirror send queue overflow'
            cause_source='target queue-overflow log mapped to fixed errWSQueueOverflow; abort reason suppressed'
        assert not (root/'loss.json').exists(), 'abort-disabled target published normal abort completion'
        helper=repo/'tools/perf16-a6/perf16_a6_fixture_process_test.go'
        recipe='accepted_number=2; PERF16_A6_CONTROL=no-abort; c.mirrorAbortOnce.Do(func(){})'
        (root/'mutation.json').write_text(json.dumps({'mode':'no-abort','recipe':recipe,'recipe_sha256':hashlib.sha256(recipe.encode()).hexdigest(),'helper_sha256':hashlib.sha256(helper.read_bytes()).hexdigest(),'target_conn':gate['conn'],'target_ref':gate['ref'],'cause_mapping':cause,'cause_source':cause_source,'actual_abort_reason':None,'healthy':healthy,'expected_junit_failure':'no RECONNECTING event'},indent=2))
    elif args.control=='screen-corruption':
        assert gradle.returncode!=0 and failures, 'corrupt real screen escaped the Cell oracle'
        assert any('cell [20,0]' in (f.get('message','')+(f.text or '')) for f in failures), 'negative did not reach the full Cell oracle'
        assert receipt is not None, 'screen negative lacked real stage loss'
        healthy=json.loads((root/'healthy-burst.json').read_text())
        assert healthy=={'bytes':16*1024*1024,'contiguous':True,'closed':False}, healthy
        recipe='PERF16_A6_CONTROL=screen-corruption; source emits ESC[21;1HX ESC[7;26H after unchanged final screen'
        (root/'mutation.json').write_text(json.dumps({'mode':args.control,'recipe':recipe,'recipe_sha256':hashlib.sha256(recipe.encode()).hexdigest(),'source_sha256':hashlib.sha256((repo/'tools/perf16-a6/source.py').read_bytes()).hexdigest(),'target_conn':receipt['conn'],'target_ref':receipt['ref'],'actual_cause':receipt['cause'],'expected_junit_failure':'cell [20,0]','healthy':healthy},indent=2))
    else:
        assert gradle.returncode==0 and not failures, 'JUnit failed'
        assert receipt is not None, 'stage loss receipt absent'
        if args.stage=='ws':
            proxy_log=(root/'proxy.log').read_text()
            for marker in ('P16_PROXY_GATE_AFTER_INITIAL_BINARY','P16_PROXY_RELEASE_AFTER_SERVER_LOSS','P16_PROXY_UPSTREAM_FIN'):
                assert marker in proxy_log, 'missing '+marker
except BaseException as error:
    result_error=f'{type(error).__name__}: {error}'
finally:
    (root/'fixture-stop').touch()
    (root/'proxy-release').touch()
    # Stop JUnit before fixture to stop creation of further clients.
    for name,grace in [('gradle',0),('fixture',30)]:
        try: terminate(name,grace)
        except BaseException as error: cleanup_errors.append(f'{name}: {error}')
    proxy=processes.get('proxy')
    if proxy is not None and proxy.poll() is None:
        os.killpg(proxy.pid,signal.SIGTERM)
    try: terminate('proxy',2)
    except BaseException as error: cleanup_errors.append(f'proxy: {error}')
    try:
        if owned_socket:
            prove_socket()
            command(['tmux','-S',sock,'kill-server'])
            stop_deadline=time.monotonic()+3
            while time.monotonic()<stop_deadline:
                alive=[]
                for pid in tmux_pids:
                    stat=command(['ps','-p',str(pid),'-o','stat='],check=False).stdout.strip()
                    if stat and not stat.startswith('Z'): alive.append(pid)
                if not alive: break
                time.sleep(.02)
            assert not alive, f'tmux descendants remain: {alive}'
            assert command(['tmux','-S',sock,'list-sessions'],check=False).returncode!=0
        for port in ports: assert not listening(port), f'listener remains: {port}'
    except BaseException as error: cleanup_errors.append(f'tmux/listeners: {error}')
    for handle in handles: handle.close()
    (root/'cleanup.json').write_text(json.dumps({'exits':exits,'errors':cleanup_errors,'tmux_pids':tmux_pids,'ports':ports},indent=2))
# Scan complete logs only AFTER fixture shutdown; exit-time race is fatal.
fixture_log=(root/'fixture.log').read_text(errors='replace') if (root/'fixture.log').exists() else ''
if 'DATA RACE' in fixture_log: result_error='fixture race, including shutdown phase'
elif args.control=='shutdown-race': result_error='shutdown race control did not produce its expected race report'
if args.control in ('candidate','screen-corruption') and result_error is None:
    try:
        loss=json.loads((root/'loss.json').read_text())
        records=[]
        for line in fixture_log.splitlines():
            if line.startswith('{'): records.append(json.loads(line))
        hits=[r for r in records if r.get('msg')=='ws: sendq health' and r.get('conn')==loss['conn']]
        assert len(hits)==1, f'target health/loss records={len(hits)}, want exactly 1'
        assert hits[0].get('close_reason')==loss['cause'], 'final close cause differs from stage receipt'
    except (AssertionError, ValueError, OSError) as error:
        result_error=f'final stage attribution: {error}' 
if exits.get('fixture')!=0 or '--- PASS: TestPerf16A6FixtureProcess' not in fixture_log:
    result_error=result_error or 'fixture did not pass and exit zero'
if cleanup_errors: result_error=result_error or 'cleanup failed'
(root/'result.json').write_text(json.dumps({'stage':args.stage,'control':args.control,'error':result_error,'behavior_pass':args.control=='candidate' and result_error is None},indent=2))
if result_error: raise SystemExit(result_error)
print('A6 candidate PASS' if args.control=='candidate' else 'A6 '+args.control+' App oracle RED as expected')
