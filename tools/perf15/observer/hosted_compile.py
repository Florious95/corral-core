import hashlib,json,os,subprocess,shutil
from pathlib import Path
root=Path(__file__).resolve().parent
out=Path(os.environ['RUNNER_TEMP'])/'perf15-observer';out.mkdir()
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root).decode().strip()
files=[{'path':str(p.relative_to(root)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size} for p in root.rglob('*') if p.is_file() and 'build' not in p.parts and '.gradle' not in p.parts]
cmd=['gradle','--no-daemon','--no-build-cache','--rerun-tasks',':app:assembleDebug']
with (out/'gradle.log').open('w') as log: code=subprocess.run(cmd,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=1050).returncode
receipt={'head':head,'files':files,'command':cmd,'exit':code,'executed_behavior_tests':0,'behavior_pass':False,'product_apk_built':False}
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
if code==0 and apk.is_file():
 shutil.copy2(apk,out/'observer-hosted-debug.apk')
 receipt['apk']={'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signature':'hosted debug; must be re-signed locally with already-matched target debug cert before instrumentation'}
else: receipt['apk']=None
(out/'compile.json').write_text(json.dumps(receipt,indent=2)+'\n')
raise SystemExit(code if code else (0 if receipt['apk'] else 1))
