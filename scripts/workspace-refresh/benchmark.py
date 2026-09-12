import json, os, pathlib, secrets, socket, subprocess, sys, tempfile, time, statistics
import websocket

ROOT = pathlib.Path(os.environ.get('CORRAL_WORKSPACE_EVIDENCE_DIR',str(pathlib.Path(__file__).resolve().parent))).resolve()
TMUX = '/opt/homebrew/bin/tmux'
def run(args, **kw):
    return subprocess.check_output(args, text=True, **kw).strip()
def tmux(sock, *args):
    env = dict(os.environ); env.pop('TMUX', None)
    return run([TMUX, '-f', '/dev/null', '-S', str(sock), *args], env=env)
def frame(ws, kind, **payload):
    ws.send(json.dumps(dict(v=1, type=kind, payload=payload)))
def receive(ws, kind, predicate=lambda p: True):
    while True:
        raw = ws.recv()
        if isinstance(raw, bytes): continue
        msg = json.loads(raw)
        if msg['type'] == 'error': raise RuntimeError(msg)
        if msg['type'] == kind and predicate(msg.get('payload', {})): return msg['payload']
def summary(values):
    a = sorted(values)
    return dict(n=len(a), p50_ms=statistics.median(a), p95_ms=a[int(.95*(len(a)-1))], max_ms=max(a))
def create(sock, cwd, name, agent=True):
    exe = str(ROOT/'bin/claude') if agent else '/bin/cat'
    tmux(sock, 'new-session', '-d', '-s', name, '-n', name, '-c', str(cwd), exe)

def setup():
    # A short fresh directory avoids Unix socket length limits. No cleanup.
    root = pathlib.Path(tempfile.mkdtemp(prefix='wr-20260912-', dir='/private/tmp'))
    env = dict(os.environ); env.pop('TMUX', None)
    fixtures = []
    for name, servers, panes in [('small',1,2), ('separate',17,2), ('shared',1,66)]:
        base=root/name; sd=base/f'tmux-{os.getuid()}'; sd.mkdir(parents=True)
        a=base/'A'; b=base/'B'; a.mkdir(); b.mkdir()
        target=sd/'a'
        create(target,a,'a0'); create(target,a,'a1')
        if name == 'separate':
            for i in range(servers-1):
                s=sd/f'b{i:02d}'
                for j in range(panes): create(s,b,f'b{j}')
        elif name == 'shared':
            for i in range(panes-2): create(target,b,f'b{i:02d}',agent=(i%2==0))
        fixtures.append(dict(name=name,socket_dir=str(sd),a=str(a),b=str(b),target=str(target),servers=servers,panes=2+(servers-1)*panes if name=='separate' else panes))
    out=ROOT/os.environ.get('WR_FIXTURES','fixtures.json'); out.write_text(json.dumps(fixtures,indent=2)); print(out)

def make_observer(directory, trace):
    import shlex
    directory.mkdir()
    for name,exe in [('tmux',TMUX),('ps','/bin/ps'),('date','/bin/date')]:
        body="#!/bin/zsh -f\nzmodload zsh/datetime\nprintf '%s\\tstart\\t%s\\t%s\\t%s\\n' \"$EPOCHREALTIME\" \"$$\" \"$PPID\" "+name+" >> "+shlex.quote(str(trace))+"\n"+shlex.quote(exe)+" \"$@\"\nresult=$?\nprintf '%s\\tend\\t%s\\t%s\\t%s\\n' \"$EPOCHREALTIME\" \"$$\" \"$PPID\" "+name+" >> "+shlex.quote(str(trace))+"\nexit $result\n"
        wrapper=directory/name;wrapper.write_text(body);wrapper.chmod(0o755)

def launch(binary, d, f):
    d.mkdir(); token=secrets.token_urlsafe(24)
    # Token is retained privately for this new fixture; never printed.
    tf=d/'token'; tf.write_text(token); tf.chmod(0o600)
    with socket.socket() as s: s.bind(('127.0.0.1',0)); port=s.getsockname()[1]
    env = {k:v for k,v in os.environ.items() if k in ('PATH','LANG','LC_CTYPE','TMPDIR','HOME')}
    env['PATH']='/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin'
    if os.environ.get('WR_OBSERVER'):
        make_observer(d/'observer',d/'process-events.tsv')
        env['PATH']=str(d/'observer')+':'+env['PATH']
    env.update(AGENTMIRROR_NODEPROBE_BIN=str(ROOT/'accepted/nodeprobe'), NODEPROBE_FIXTURES=str(ROOT/'accepted/titles.tsv'), NODEPROBE_PROVIDERS=str(ROOT/'accepted/providers.tsv'),AGENTMIRROR_NODEPROBE_PI_EXTENSION=str(ROOT/'accepted/nodeprobe-pi-activity.js'), AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS=f['socket_dir'], AGENTMIRROR_TOKEN=token)
    log=open(d/'server.log','w'); guide=open(d/'guide-private.txt','w'); os.chmod(d/'guide-private.txt',0o600)
    p=subprocess.Popen([str(pathlib.Path(binary).resolve()),'-listen',f'127.0.0.1:{port}','-state-dir',str(d/'state'),'-upload-dir',str(d/'uploads'),'-log-level','debug'], env=env,stdout=guide,stderr=log,start_new_session=True)
    (d/'runtime.json').write_text(json.dumps(dict(pid=p.pid,port=port,binary=str(binary),fixture=f),indent=2))
    for _ in range(100):
        if p.poll() is not None: raise RuntimeError('daemon failed; see private log')
        try: ws=websocket.create_connection(f'ws://127.0.0.1:{port}/ws',timeout=45); break
        except ConnectionRefusedError: time.sleep(.1)
    frame(ws,'auth',token=token); assert receive(ws,'auth_ack')['ok']
    return ws, p

def bench(binary, label):
    dest=ROOT/label; dest.mkdir()
    results=[]
    for index,f in enumerate(json.loads((ROOT/os.environ.get('WR_FIXTURES','fixtures.json')).read_text())):
        d=dest/f['name']; ws,p=launch(binary,d,f)
        timings={'l1':[],'l2':[]}; raw=[]
        for i in range(int(os.environ.get('WR_SAMPLES','25'))):
            t=time.monotonic(); frame(ws,'list',req_id=i+1)
            listing=receive(ws,'listing',lambda x:x['req_id']==i+1)
            elapsed=(time.monotonic()-t)*1000; timings['l1'].append(elapsed)
            workspaces=listing.get('workspaces') or []
            found=[w for w in workspaces if w['cwd']==f['a']]
            assert len(found)==1 and found[0]['session_count']==2, workspaces
            t=time.monotonic(); frame(ws,'level2_subscribe',workspace=f['a'])
            l2=receive(ws,'level2_frame',lambda x:x['workspace']==f['a'])
            elapsed2=(time.monotonic()-t)*1000; timings['l2'].append(elapsed2)
            assert len(l2.get('sessions') or [])==2,l2
            frame(ws,'level2_unsubscribe')
            usage=run(['/bin/ps','-p',str(p.pid),'-o','%cpu=,rss='])
            raw.append(dict(iteration=i,l1_ms=elapsed,l2_ms=elapsed2,resource=usage,workspaces=len(workspaces),sessions=sum(w['session_count'] for w in workspaces)))
            (d/'raw.json').write_text(json.dumps(raw,indent=2))
        ws.close()
        result=dict(scenario=f,metrics={k:summary(v) for k,v in timings.items()})
        results.append(result); (dest/'summary.json').write_text(json.dumps(results,indent=2)); print(json.dumps(result),flush=True)
    # Processes and fixture sockets remain for inspection.

if __name__=='__main__':
    if sys.argv[1]=='setup': setup()
    else: bench(sys.argv[1],sys.argv[2])
