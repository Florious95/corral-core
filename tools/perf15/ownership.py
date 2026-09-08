"""Linux hosted test ownership. No host process scan; only registered descendants."""
import ctypes
import fcntl
import json
import os
from pathlib import Path
import select
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time


def identity(pid):
    try:
        p = Path('/proc')/str(pid)
        fields = (p/'stat').read_text().rsplit(') ', 1)[1].split()
        return dict(pid=pid, uid=p.stat().st_uid, start=int(fields[19]), group=int(fields[2]))
    except FileNotFoundError:
        return None


def live(item):
    current = identity(item['pid'])
    return current is not None and all(current[key] == item[key] for key in ('pid', 'uid', 'start'))


def children(item):
    if not live(item):
        return []
    result = set()
    # Go may fork from any OS thread. Inspect only this proven PID's threads.
    try:
        threads=list((Path('/proc')/str(item['pid'])/'task').iterdir())
    except FileNotFoundError:
        return []
    for thread in threads:
        try:
            result.update(int(pid) for pid in (thread/'children').read_text().split())
        except FileNotFoundError:
            continue
    return sorted(result)


def descendants(item, found):
    if not live(item):
        return
    found[item['pid']] = item
    for pid in children(item):
        child = identity(pid)
        if child:
            assert child['uid'] == os.getuid(), 'foreign descendant UID'
            if pid not in found:
                descendants(child, found)


def reap_children():
    # WNOHANG=0 means a LIVE child remains, never an empty child set.
    while True:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
            if pid == 0:
                return False
        except ChildProcessError:
            return True


def startup_barrier(root, stage):
    if os.environ.get('PERF15_FIXTURE_FAULT') != 'handoff-'+stage:
        return
    ready = root/'handoff-ready'
    try:
        fd = os.open(ready, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        return
    os.write(fd, stage.encode()); os.close(fd)
    # The outer test starts cleanup while this admitted shim retains its SH
    # lease. It releases us only AFTER publishing the closing marker.
    while not (root/'handoff-release').exists():
        time.sleep(0.005)
    assert (root/'closing').exists(), 'handoff control did not overlap cleanup'
    append(root, {'handoff':stage, 'cleanup_started':True})


def append(root, record):
    fd = os.open(root/'fixtures.jsonl', os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    try:
        os.write(fd, (json.dumps(record)+'\n').encode())
    finally:
        os.close(fd)


def socket_identity(sock):
    st = sock.lstat()
    assert stat.S_ISSOCK(st.st_mode) and st.st_uid == os.getuid(), 'foreign/non-socket fixture'
    return dict(path=str(sock), uid=st.st_uid, dev=st.st_dev, inode=st.st_ino)


def register(root, sock, real, timeout=3):
    if not sock.exists():
        return
    sid = socket_identity(sock)
    result = subprocess.run([real, '-S', str(sock), 'list-panes', '-a', '-F', '#{socket_path}\t#{pid}\t#{pane_pid}'], capture_output=True, text=True, timeout=timeout)
    assert result.returncode == 0, 'owned socket metadata unavailable'
    processes = {}
    for line in result.stdout.splitlines():
        path, server, pane = line.split('\t')
        assert path == str(sock), 'socket self-proof mismatch'
        for pid in (server, pane):
            item = identity(int(pid))
            assert item and item['uid'] == os.getuid() and item['start'] >= json.loads((root/'owner.json').read_text())['start_ticks'], 'fixture identity absent/foreign/older than case'
            descendants(item, processes)
    assert processes, 'fixture has no registered processes'
    append(root, dict(socket=sid, processes=list(processes.values())))


def shim():
    root = Path(os.environ['PERF15_OWNERSHIP'])
    real = os.environ['PERF15_REAL_TMUX']
    args = sys.argv[2:]
    if args == ['-V']:
        os.execv(real, [real]+args)
    assert '-S' in args and args.count('-S') == 1 and '-L' not in args, 'explicit socket required'
    sock = Path(args[args.index('-S')+1])
    assert sock.is_absolute() and len(os.fsencode(sock)) < 104, 'socket path bound'
    if not sock.parent.exists():
        raise SystemExit(1)  # ordinary nonexistent fixture; never fallback
    # Cleanup publishes closing before taking EX. A shim admitted before that
    # boundary must finish post-register (or be canceled) before EX can freeze it.
    with (root/'enrollment.lock').open('rb') as lease:
        fcntl.flock(lease, fcntl.LOCK_SH)
        if (root/'closing').exists():
            raise SystemExit('fixture enrollment closed')
        # Only new private directories reached through this owned child can enroll.
        # Existing compatibility amcid fixtures use /tmp explicitly; bind their
        # fresh parent identity instead of broadening to pre-existing /tmp sockets.
        parent = sock.parent.lstat()
        case = json.loads((root/'owner.json').read_text())
        assert sock.parent.resolve() == sock.parent, 'symlink socket parent'
        private = stat.S_ISDIR(parent.st_mode) and parent.st_uid == os.getuid() and stat.S_IMODE(parent.st_mode) == 0o700
        existing = [json.loads(x) for x in (root/'fixtures.jsonl').read_text().splitlines()] if (root/'fixtures.jsonl').exists() else []
        enrolled = any(x.get('intent') == str(sock) for x in existing)
        assert private and (enrolled or parent.st_ctime_ns >= case['started_ns']), 'not a fresh owned fixture directory'
        if not enrolled:
            assert not sock.exists(), 'cannot enroll existing socket'
            append(root, dict(intent=str(sock), parent=dict(path=str(sock.parent), uid=parent.st_uid, dev=parent.st_dev, inode=parent.st_ino)))
        if not enrolled:
            startup_barrier(root, 'intent')
        # Metadata before destructive calls preserves identities even if Go cleanup
        # unlinks the socket. All commands retain their original stdout/stderr.
        register(root, sock, real)
        code = subprocess.call([real]+args)
        if not enrolled:
            startup_barrier(root, 'post')
        if sock.exists():
            # kill-server can leave a dead pathname; a previously registered identity
            # suffices in that one case. No other metadata failure is hidden.
            if 'kill-server' not in args and 'kill-session' not in args:
                register(root, sock, real)
        raise SystemExit(code)


def supervisor():
    fd = int(sys.argv[2])
    child = subprocess.Popen(sys.argv[3:])
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    code = child.wait()
    os.write(fd, (str(code)+'\n').encode())
    os.close(fd)
    # Keep the group leader identity alive until the outer finally owns cleanup.
    sys.stdin.buffer.read()


def execute(command, cwd, log, receipt, timeout, fault=None):
    assert sys.platform == 'linux' and os.environ.get('GITHUB_ACTIONS') == 'true'
    # Reap daemonized fixture children too; no reliance on runner PID1 hygiene.
    assert ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) == 0
    runner = identity(os.getpid())
    assert not children(runner), 'runner has unrelated children before case'
    root = Path(tempfile.mkdtemp(prefix='p15-', dir=os.environ['RUNNER_TEMP']))
    (root/'enrollment.lock').touch(mode=0o600)
    started = time.time_ns()
    (root/'owner.json').write_text(json.dumps(dict(started_ns=started, uid=os.getuid(), start_ticks=int(time.clock_gettime(time.CLOCK_BOOTTIME)*os.sysconf('SC_CLK_TCK'))-1)))
    binpath=root/'bin'; binpath.mkdir()
    wrapper=binpath/'tmux'
    import shlex
    wrapper.write_text('#!/bin/sh\nexec '+shlex.quote(sys.executable)+' '+shlex.quote(str(Path(__file__).resolve()))+' shim "$@"\n')
    wrapper.chmod(0o700)
    env=os.environ.copy()
    env.pop('TMUX',None);env.pop('TMUX_TMPDIR',None)
    env.update(TMPDIR=str(root), PERF15_OWNERSHIP=str(root), PERF15_REAL_TMUX=shutil.which('tmux'), PATH=str(binpath)+os.pathsep+env['PATH'])
    if fault:env['PERF15_FIXTURE_FAULT']=fault
    readfd,writefd=os.pipe()
    process=None; owner=None; code=None; timed_out=False; errors=[]; registered=[]; owned={}
    try:
        with log.open('wb') as output:
            process=subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'supervisor',str(writefd)]+command,cwd=cwd,env=env,stdout=output,stderr=subprocess.STDOUT,stdin=subprocess.PIPE,pass_fds=(writefd,),start_new_session=True)
            os.close(writefd);writefd=None
            owner=identity(process.pid)
            assert owner and owner['group']==process.pid and owner['uid']==os.getuid()
            end=time.monotonic()+timeout
            while True:
                remaining=end-time.monotonic()
                if remaining<=0:timed_out=True;break
                ready,_,_=select.select([readfd],[],[],min(remaining,0.1) if fault in ('outer-timeout','handoff-intent','handoff-post') else remaining)
                if ready:
                    data=os.read(readfd,64)
                    if not data:raise RuntimeError('supervisor lost completion interface')
                    code=int(data);break
                if fault in ('handoff-intent','handoff-post') and (root/'handoff-ready').exists():
                    timed_out=True;break
                if fault=='outer-timeout' and b'PERF15_OWNED_FIXTURE_READY' in log.read_bytes():
                    timed_out=True;break
    finally:
        os.close(readfd)
        if writefd is not None:os.close(writefd)
        # One unchanged cleanup budget: TERM/handoff up to 2s, KILL/reap up
        # to 3s more. No business deadline is extended by enrollment sealing.
        term_end=time.monotonic()+2
        kill_end=term_end+3
        lease=(root/'enrollment.lock').open('rb')
        sealed=False
        omitted=None
        registry=root/'fixtures.jsonl'
        frozen=b''
        def collect():
            # Baseline had no child and execute is serial. All current children
            # of this subreaper belong to this case, including escaped daemons.
            for pid in children(runner):
                item=identity(pid)
                if item:
                    assert item['uid']==os.getuid(), 'foreign adopted child UID'
                    descendants(item,owned)
        def terminate(sig):
            collect()
            if owner and live(owner):
                assert identity(owner['pid'])['group']==owner['group']
                try:os.killpg(owner['group'],sig)
                except ProcessLookupError:pass
            for item in list(owned.values()):
                if item != owner and item != omitted and live(item):
                    try:os.kill(item['pid'],sig)
                    except ProcessLookupError:pass
        try:
            # Closing marker prevents NEW admission; EX waits for every older
            # intent/launch/post-register transaction, not a registry snapshot.
            (root/'closing').touch(mode=0o600)
            if fault in ('handoff-intent','handoff-post'):
                (root/'handoff-release').touch(mode=0o600)
            while not sealed:
                try:
                    fcntl.flock(lease,fcntl.LOCK_EX | fcntl.LOCK_NB)
                    sealed=True
                except BlockingIOError:
                    if time.monotonic()>=term_end:
                        # Cancel a stuck admitted command, then collect again
                        # after EX proves no shim can still create a daemon.
                        terminate(signal.SIGKILL)
                    if time.monotonic()>=kill_end:
                        raise AssertionError('enrollment seal not proven')
                    time.sleep(0.005)
            # Frozen registry is now stable even if waiting shims survive: they
            # cannot pass closing and the outer retains EX through verification.
            registered=[json.loads(x) for x in registry.read_text().splitlines()] if registry.exists() else []
            for entry in [x for x in registered if 'intent' in x]:
                sock=Path(entry['intent']);parent=entry['parent'];st=sock.parent.stat() if sock.parent.exists() else None
                if st is None:continue
                assert (st.st_uid,st.st_dev,st.st_ino)==(parent['uid'],parent['dev'],parent['inode']), 'fixture directory replaced'
                if sock.exists() and not any(x.get('socket',{}).get('path')==str(sock) for x in registered):
                    # Socket belongs to predeclared, inode-bound intent. Adopted
                    # children are independently collected even if metadata fails.
                    register(root,sock,env['PERF15_REAL_TMUX'],max(0.001,kill_end-time.monotonic()))
            frozen=registry.read_bytes() if registry.exists() else b''
            registered=[json.loads(x) for x in frozen.splitlines()]
            for entry in registered:
                for item in entry.get('processes',[]):
                    if live(item):descendants(item,owned)
            if fault=='omit-adopted':
                omitted=json.loads((root/'omitted.json').read_text())
                assert live(omitted) and omitted['group']!=owner['group'], 'negative control is not live and detached'
            terminate(signal.SIGTERM)
            while time.monotonic()<term_end:
                collect()
                for item in list(owned.values()):
                    if item==owner or item==omitted:continue
                    try:os.waitpid(item['pid'],os.WNOHANG)
                    except ChildProcessError:pass
                if all(item==owner or item==omitted or not live(item) for item in owned.values()):break
                time.sleep(0.005)
            terminate(signal.SIGKILL)
            if process:process.wait(timeout=max(0.001,kill_end-time.monotonic()))
            while True:
                # Kill/reap late adoptions after group exit as well. Enumeration
                # is restricted to our own thread children, never the host.
                collect()
                for item in list(owned.values()):
                    if item!=omitted and live(item):
                        try:os.kill(item['pid'],signal.SIGKILL)
                        except ProcessLookupError:pass
                empty=reap_children()
                if empty:break
                if omitted is not None:
                    raise AssertionError('live adopted children remain')
                if time.monotonic()>=kill_end:
                    raise AssertionError('live adopted children remain')
                time.sleep(0.005)
            assert not children(runner), 'runner thread children remain'
            assert all(not live(item) for item in owned.values()), 'owned process survives cleanup'
            if owner:
                try:os.killpg(owner['group'],0)
                except ProcessLookupError:pass
                else:raise AssertionError('owned process group survives cleanup')
            assert (registry.read_bytes() if registry.exists() else b'')==frozen, 'registry changed after seal'
            for entry in registered:
                sid=entry.get('socket')
                if sid and Path(sid['path']).exists():
                    assert socket_identity(Path(sid['path']))==sid, 'socket inode replaced'
                    Path(sid['path']).unlink()
            assert all(not Path(x['intent']).exists() for x in registered if 'intent' in x), 'socket survives cleanup'
        except Exception as exc:
            errors.append(str(exc))
            # An unproven case never advances. Best-effort recovery uses exactly
            # the same proven descendant boundary; errors/root remain evidence.
            omitted=None
            while time.monotonic()<kill_end:
                terminate(signal.SIGKILL)
                if process and process.poll() is not None:process.wait()
                if reap_children():break
                time.sleep(0.005)
        finally:
            lease.close()
        result=dict(exit=code,external_timeout=timed_out,owner=owner,fixtures=registered,processes=list(owned.values()),errors=errors,cleanup_proven=not errors,enrollment_sealed=sealed,runner_children=children(runner),fixture_registered=any('socket' in x for x in registered),retained_root=str(root) if errors else None)
        receipt.write_text(json.dumps(result,indent=2))
        if errors:raise CleanupUnproven(result)
        shutil.rmtree(root)

    return result


class CleanupUnproven(RuntimeError):
    def __init__(self, result):
        self.result=result
        super().__init__('cleanup unproven; stop wave: '+str(result['errors']))


def adopted_negative_control(cwd, root):
    receipt=root/'apparatus-omit-adopted-cleanup.json'
    try:
        execute([sys.executable,str(Path(__file__).resolve()),'orphan-control'],cwd,root/'apparatus-omit-adopted.log',receipt,150,'omit-adopted')
    except CleanupUnproven as exc:
        result=exc.result
        assert result['errors']==['live adopted children remain'], result
        assert not result['cleanup_proven'] and Path(result['retained_root']).is_dir()
        # The negative checker must reject BEFORE its emergency recovery. Only
        # this explicitly named control can consume that rejection; no product
        # gate runs unless emergency cleanup objectively left zero children.
        assert not result['runner_children'] and reap_children()
        assert all(not live(item) for item in result['processes'])
        (root/'apparatus-omit-adopted.json').write_text(json.dumps(dict(apparatus_control=True,behavior_pass=False,false_clean_rejected=True,unproven_root_retained=True,recovery_children_zero=True),indent=2))
    else:
        raise AssertionError('live adopted child incorrectly accepted as clean')


def orphan_control():
    root=Path(os.environ['PERF15_OWNERSHIP'])
    child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(300)'],start_new_session=True)
    (root/'omitted.json').write_text(json.dumps(identity(child.pid)))
    # Exiting this parent makes the real live child an adopted child of execute.


if __name__=='__main__':
    if sys.argv[1]=='shim':shim()
    elif sys.argv[1]=='supervisor':supervisor()
    elif sys.argv[1]=='orphan-control':orphan_control()
    else:raise SystemExit('unknown ownership entry')
