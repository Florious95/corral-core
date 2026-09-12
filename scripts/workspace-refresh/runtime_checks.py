"""Real tmux/accepted nodeprobe/daemon/WS checks on newly owned fixtures.
Fault plan: SIGSTOP only fixture tmux servers, then SIGCONT as part of scenario.
Delete plan: kill only the sole session in the newly created DELETE workspace.
No environment teardown; all servers, files and evidence remain.
"""
from benchmark import *
import signal, threading

dest=ROOT/sys.argv[2];dest.mkdir()
base=pathlib.Path(tempfile.mkdtemp(prefix='wr-check-',dir='/private/tmp'))
sd=base/f'tmux-{os.getuid()}';sd.mkdir()
a=base/'A';b=base/'B';c=base/'C'
for d in (a,b,c):d.mkdir()
sa=sd/'a';sb=sd/'b';sc=sd/'c'
create(sa,a,'A0');create(sb,b,'B0');create(sc,c,'C0')
f=dict(socket_dir=str(sd),a=str(a),b=str(b),target=str(sa))
receipt=[]
def record(name,**kw):
    receipt.append(dict(case=name,**kw));(dest/'results.json').write_text(json.dumps(receipt,indent=2));print(json.dumps(receipt[-1]),flush=True)
def pid(sock):return int(tmux(sock,'display-message','-p','#{pid}'))
def peer(d):
    r=json.loads((d/'runtime.json').read_text());w=websocket.create_connection(f"ws://127.0.0.1:{r['port']}/ws",timeout=15)
    frame(w,'auth',token=(d/'token').read_text());assert receive(w,'auth_ack')['ok'];return w
def l2(w,cwd):
    frame(w,'level2_subscribe',workspace=str(cwd));return receive(w,'level2_frame',lambda x:x['workspace']==str(cwd))
def terminal(w,ref):
    t=time.monotonic();frame(w,'subscribe',ref=ref,rows=24,cols=80)
    while True:
        raw=w.recv()
        if isinstance(raw,bytes) and raw[3]==1:
            assert raw[5:5+raw[4]].decode()==ref
            assert b'WORKSPACE_REFRESH_READY' in raw
            return (time.monotonic()-t)*1000
        if isinstance(raw,str) and json.loads(raw)['type']=='error':raise RuntimeError(raw)
def error(w,cwd):
    t=time.monotonic();frame(w,'level2_subscribe',workspace=str(cwd))
    while True:
        raw=w.recv()
        if isinstance(raw,bytes):continue
        msg=json.loads(raw)
        assert msg['type']!='level2_frame',msg
        if msg['type']=='error':return dict(ms=(time.monotonic()-t)*1000,error=msg)

# Cold L2 routing/click while first global discovery cannot finish on B.
bp=pid(sb);os.kill(bp,signal.SIGSTOP)
(dest/'fault-plan.json').write_text(json.dumps(dict(owned_tmux_pids=[pid(sa),bp,pid(sc)],owned_socket_dir=str(sd),operation='stop/continue; delete only later DELETE'),indent=2))
w,p=launch(sys.argv[1],dest/'cold',f)
t=time.monotonic();rows=l2(w,a);l2ms=(time.monotonic()-t)*1000
assert len(rows['sessions'])==1
clickms=terminal(w,rows['sessions'][0]['ref'])
record('cold_route_and_actual_terminal_while_B_stopped',l2_ms=l2ms,click_ms=clickms,passed=l2ms+clickms<=1500)
assert l2ms+clickms<=1500
os.kill(bp,signal.SIGCONT);w.close()

w,p=launch(sys.argv[1],dest/'live',f)
frame(w,'list',req_id=1);receive(w,'listing');assert len(l2(w,a)['sessions'])==1

# Continuous creation: same socket, new socket for A, and brand-new workspace.
req=10
for name,sock,cwd,count in [('same_socket',sa,a,2),('new_socket_A',sd/'new-a',a,3),('new_workspace',sd/'new-d',base/'NEW',1)]:
    cwd.mkdir(exist_ok=True)
    t=time.monotonic();create(sock,cwd,name)
    l1ms=l2ms=None;latest=None
    while time.monotonic()-t<4:
        req+=1;frame(w,'list',req_id=req);listing=receive(w,'listing',lambda x:x['req_id']==req)
        ws=[x for x in listing.get('workspaces') or [] if x['cwd']==str(cwd)]
        if ws and ws[0]['session_count']==count and l1ms is None:l1ms=(time.monotonic()-t)*1000
        latest=l2(w,cwd)
        if len(latest.get('sessions') or [])==count and l2ms is None:l2ms=(time.monotonic()-t)*1000
        if l1ms is not None and l2ms is not None:break
    assert l1ms is not None and l2ms is not None,(name,latest)
    ref=next(x['ref'] for x in latest['sessions'] if x['name']==name)
    clickms=terminal(w,ref)
    record(name,l1_visible_ms=l1ms,l2_visible_ms=l2ms,click_ms=clickms,passed=max(l1ms,l2ms)<=4000 and clickms<=1500)
    assert clickms<=1500

# A shares a socket with an unrelated provider-shaped B and a non-Agent pane.
create(sa,b,'sharedB');create(sa,b,'not-agent',agent=False)
assert len(l2(w,a)['sessions'])==3
rows=l2(w,b);assert len(rows['sessions'])==2,rows
record('many_to_many_and_non_agent_exclusion',A_count=3,B_count=2,passed=True)

# Healthy A remains fresh when B is stopped. Target B gives explicit error.
os.kill(bp,signal.SIGSTOP)
t=time.monotonic();rows=l2(w,a);ms=(time.monotonic()-t)*1000
record('unrelated_slow_socket',ms=ms,passed=ms<=1000);assert ms<=1000
other=peer(dest/'live');failure=error(other,b);record('target_slow_socket',**failure,passed=failure['ms']<=13000);assert failure['ms']<=13000
other.close();os.kill(bp,signal.SIGCONT)

# Two occupied workers: a healthy third admission must succeed or explicitly
# fail by the same total budget. These are real stopped tmux dependencies.
cp=pid(sc);os.kill(bp,signal.SIGSTOP);os.kill(cp,signal.SIGSTOP)
peers=[peer(dest/'live'),peer(dest/'live')];failures=[]
threads=[threading.Thread(target=lambda ww,dd: failures.append(error(ww,dd)),args=(ww,dd)) for ww,dd in zip(peers,(b,c))]
for th in threads:th.start()
time.sleep(.2)
t=time.monotonic();frame(w,'level2_subscribe',workspace=str(a))
while True:
    msg=json.loads(w.recv())
    if msg['type'] in ('error','level2_frame'):
        if msg['type']=='level2_frame': assert len(msg['payload']['sessions'])==3
        saturated_outcome=msg;break
ms=(time.monotonic()-t)*1000
for th in threads:th.join(14)
assert len(failures)==2 and all(x['ms']<=13000 for x in failures)
record('saturated_pool',healthy_ms=ms,outcome=saturated_outcome,slow_results=failures,passed=ms<=13000);assert ms<=13000
os.kill(bp,signal.SIGCONT);os.kill(cp,signal.SIGCONT)
for ww in peers:ww.close()

delete=base/'DELETE';delete.mkdir();ds=sd/'delete';create(ds,delete,'sole')
req+=1;frame(w,'list',req_id=req);receive(w,'listing',lambda x:x['req_id']==req)
old=l2(w,delete);assert len(old['sessions'])==1
(dest/'delete-before.json').write_text(json.dumps(old,indent=2))
tmux(ds,'kill-session','-t','sole')
empty=l2(w,delete);assert empty['sessions'] is None,empty
w.close();w=peer(dest/'live');empty2=l2(w,delete);assert empty2['sessions'] is None
record('delete_last_session_reconnect_no_resurrection',frames=[empty,empty2],passed=True)
w.close()
