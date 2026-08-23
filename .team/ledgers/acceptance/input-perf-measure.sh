#!/bin/sh
set -eu
python3 - <<'PY'
import json, math, statistics, sys
p='.team/nodes/input-full-auto/perf-measure/perf-ab.json'
try: d=json.load(open(p))
except Exception as e: print('UNJUDGEABLE bad json:',e); sys.exit(2)
if d.get('schema')!='perf-ab.v1': print('FAIL schema'); sys.exit(1)
if d.get('baseline_source')!='baseline-20260822-release': print('FAIL baseline source'); sys.exit(1)
if d.get('baseline_reference_md5')!='0907d6881bb1e034ef33a49f89afaa44': print('FAIL reference md5'); sys.exit(1)
if not d.get('baseline_measured_md5') or not d.get('candidate_md5') or d['baseline_measured_md5']==d['candidate_md5']:
 print('UNJUDGEABLE A/B identity'); sys.exit(2)
if (d.get('env') or {}).get('gate_exit')!=0: print('UNJUDGEABLE env gate'); sys.exit(2)
fixtures=['big_scrollback','real_claude_idle','redraw_tui']
segments=['tap_to_route_enter','route_enter_to_first_frame','first_frame_to_first_draw','tap_to_first_draw']
def q(xs,p):
 xs=sorted(float(x) for x in xs); return xs[max(0,math.ceil(p*len(xs))-1)]
for f in fixtures:
 fd=(d.get('fixtures') or {}).get(f)
 if not isinstance(fd,dict): print('UNJUDGEABLE missing fixture',f); sys.exit(2)
 for s in segments:
  sd=fd.get(s) or {}; a=sd.get('A') or []; b=sd.get('B') or []
  if len(a)<10 or len(b)<10: print('UNJUDGEABLE samples',f,s,len(a),len(b)); sys.exit(2)
  for pctl in (.5,.95):
   av=q(a,pctl); bv=q(b,pctl)
   if av<=0: print('UNJUDGEABLE nonpositive A',f,s); sys.exit(2)
   ratio=bv/av
   print(f'{f} {s} p{int(pctl*100)} A={av:.3f} B={bv:.3f} ratio={ratio:.4f}')
   if ratio>1.10: print('FAIL regression',f,s,pctl,ratio); sys.exit(1)
print('PASS fresh same-batch A/B')
PY
