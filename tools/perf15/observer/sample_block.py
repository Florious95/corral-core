"""One owned-device block. Requires approved window and real-fixture prepare hook.
No product/service start, device shutdown, source injection, or acceptance waiver.
"""
import argparse,hashlib,json,os,subprocess,sys,threading
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--plan',type=Path,required=True);p.add_argument('--prepare',type=Path,required=True);p.add_argument('--window-receipt',type=Path,required=True);p.add_argument('--out',type=Path,required=True);args=p.parse_args()
 own=Path('.team/nodes/real-avd-astra2').resolve()
 for path in (args.plan,args.prepare,args.out):
  if not path.resolve().is_relative_to(own):raise SystemExit('paths must stay in own node')
 receipt=json.loads(args.window_receipt.read_text())
 if not receipt.get('exclusive_window_active') or receipt.get('envcheck_exit')!=0:raise SystemExit('exclusive window/envcheck not available')
 plan=json.loads(args.plan.read_text());trials={x['id']:x for x in plan['trials']}
 if len(trials)!=20 or len(plan['trials'])!=20:raise SystemExit('one measured block requires 10 select + 10 resume unique trials')
 if [x['action'] for x in trials.values()].count('select')!=10 or [x['action'] for x in trials.values()].count('resume')!=10:raise SystemExit('sample composition mismatch')
 args.out.mkdir(parents=True,exist_ok=False)
 adb=['adb','-P','5055','-s','emulator-5596']; remote='/sdcard/Android/data/dev.agentmirror.app/files/perf15-observer'
 def run(cmd,**kw):return subprocess.run(cmd,check=True,timeout=120,**kw)
 run(adb+['shell','mkdir','-p',remote]);run(adb+['push',str(args.plan),remote+'/plan.json'])
 # A new trial ID is mandatory; observer rejects stale trigger files.
 command=adb+['shell','am','instrument','-w','-r','-e','plan','plan.json','dev.agentmirror.perf15/dev.agentmirror.perf15.Observer']
 proc=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,start_new_session=True)
 timed_out=[]
 def abort():timed_out.append(True);proc.terminate()
 timer=threading.Timer(3300,abort);timer.start();seen=[]
 try:
  with (args.out/'instrumentation.log').open('w') as log:
   for line in proc.stdout:
    log.write(line);log.flush()
    prefix='INSTRUMENTATION_STATUS: readyId='
    if line.startswith(prefix):
     ident=line[len(prefix):].strip()
     if ident not in trials or ident in seen:raise RuntimeError('unexpected/repeated trial readiness')
     # Hook must generate real isolated tmux output + normal UI navigation;
     # it must verify oracle provenance and must never call VM/feed APIs.
     run([sys.executable,str(args.prepare),str(args.plan),ident],stdout=log,stderr=subprocess.STDOUT)
     run(adb+['shell','touch',remote+'/'+ident+'.go'])
     seen.append(ident)
  code=proc.wait(timeout=10)
  run(adb+['pull',remote+'/result.json',str(args.out/'result.json')])
  result=json.loads((args.out/'result.json').read_text())
  if code or timed_out or result.get('failure') or len(result.get('records',[]))!=20:raise RuntimeError('block incomplete; stop next block and clean owned fixtures')
  (args.out/'receipt.json').write_text(json.dumps({'plan_sha256':hashlib.sha256(args.plan.read_bytes()).hexdigest(),'prepare_sha256':hashlib.sha256(args.prepare.read_bytes()).hexdigest(),'instrument_exit':code,'records':20,'performance_pass':False},indent=2))
 finally:
  timer.cancel()
  if proc.poll() is None:proc.terminate();proc.wait(timeout=10)
  # Instrumentation must not remain attached after a failed runner/host read.
  run(adb+['shell','am','force-stop','dev.agentmirror.app'])
  run(adb+['shell','am','force-stop','dev.agentmirror.perf15'])

if __name__=='__main__':main()
