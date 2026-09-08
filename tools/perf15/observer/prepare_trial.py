"""Bounded real fixture preparation outside timed action. Only own socket/panes.
Requires existing own fixture processes and the normal App navigation helper.
"""
import hashlib,json,os,select,stat,subprocess,sys,time
from pathlib import Path
from fixture_oracle import generate

def main():
 plan=json.loads(Path(sys.argv[1]).read_text());t=next(t for t in plan['trials'] if t['id']==sys.argv[2]);spec=t['fixture']
 own=Path('.team/nodes/real-avd-astra2').resolve();sock=Path('/Volumes/nvme/Projects/远程Agent安卓/.team/s/msr2-20260908/tmux-501/default')
 if spec['ref']!=str(sock)+'\x1f'+t['pane']:raise SystemExit('fixture ref/socket mismatch')
 fifo=Path(t['fifo']).resolve()
 if not fifo.is_relative_to(own) or not stat.S_ISFIFO(fifo.stat().st_mode) or fifo.stat().st_uid!=os.getuid():raise SystemExit('not own FIFO')
 env=os.environ.copy();env.pop('TMUX',None)
 def tmux(*args):return subprocess.check_output(['tmux','-S',str(sock),*args],env=env,timeout=5).decode()
 rows=tmux('list-panes','-a','-F','#{pane_id}|#{pane_start_command}').splitlines()
 matches=[r for r in rows if r.split('|',1)[0]==t['pane']]
 if len(matches)!=1 or str(own) not in matches[0] or 'fixture_oracle.py' not in matches[0]:raise SystemExit('pane process not own deterministic fixture')
 _,expected=generate(**spec)
 if expected!=t['expected']:raise SystemExit('oracle not derived from real fixture source')
 helper=own/'tmp/perf15-e2e/runtime/app.py'
 if t['action']=='resume':subprocess.run([sys.executable,str(helper),'background'],check=True,timeout=15)
 else:
  # Frozen normal UI navigation steps; these are preparation, not timed selection.
  for label in t['setupTap']:subprocess.run([sys.executable,str(helper),'tap',label],check=True,timeout=15)
 tmux('resize-window','-t',t['pane'],'-x',str(spec['cols']),'-y',str(spec['rows']))
 tmux('clear-history','-t',t['pane'])
 fd=os.open(fifo,os.O_WRONLY|os.O_NONBLOCK)
 try:os.write(fd,(json.dumps(spec)+'\n').encode())
 finally:os.close(fd)
 # Real tmux metadata/content barrier, before instrumentation starts the clock.
 end=time.monotonic()+5
 while time.monotonic()<end:
  screen=tmux('capture-pane','-p','-t',t['pane']);cursor=tmux('display-message','-p','-t',t['pane'],'#{cursor_x},#{cursor_y}').strip()
  if f"P15{spec['generation']}S{spec['rows']-1:02d}" in screen and cursor=='2,1':break
  time.sleep(.02)
 else:raise SystemExit('real fixture did not settle before action')
 print(json.dumps({'trial':t['id'],'ref':spec['ref'],'generation':spec['generation'],'fixture_source_sha256':hashlib.sha256(Path(__file__).with_name('fixture_oracle.py').read_bytes()).hexdigest(),'prepared':True}))
if __name__=='__main__':main()
