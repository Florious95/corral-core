#!/bin/sh
# //! purpose: 从固定真实 APK、order 与非空 raw 独立重建三夹具 A/B/A/B nearest-rank 性能门。
# //! contract: 0=有效样本全格<=1.10；1=有效回归或证据自相矛盾；2=环境/身份/样本不足不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-measure: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-measure: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
node="$repo_root/.team/nodes/baseline-bundle-measure"
measure="$node/MEASURE.md"
result="$node/perf-ab-bundle.json"
pre="$node/PRE-MEASURE.json"
order="$node/raw/order.tsv"
manifest="$repo_root/.team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json"

for f in "$measure" "$result" "$pre" "$order" "$manifest"; do
    [ -e "$f" ] || unjudgeable "missing $f"
    [ -r "$f" ] || unjudgeable "unreadable $f"
    [ -s "$f" ] || unjudgeable "empty $f"
done
last=$(sed -n '$p' "$measure" 2>/dev/null) || unjudgeable "cannot read measurement verdict"
case "$last" in 'measurement: pass'|'measurement: fail') ;; 'measurement: unjudgeable') unjudgeable "measurement reports unjudgeable" ;; *) unjudgeable "bad measurement verdict" ;; esac
for d in "$node/raw/A" "$node/raw/B"; do [ -d "$d" ] && [ -r "$d" ] || unjudgeable "raw directory unavailable $d"; done
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
git_root=$(git -C "$repo_root" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "not in real git worktree"
[ "$git_root" = "$repo_root" ] || unjudgeable "repository root mismatch"

python3 - "$repo_root" "$result" "$manifest" "$pre" "$order" "$last" <<'PY'
import hashlib,json,math,re,sys
from pathlib import Path

root,result_path,manifest_path,pre_path,order_path=map(Path,sys.argv[1:6]); report_line=sys.argv[6]
FIXTURES=("big_scrollback","real_claude_idle","redraw_tui")
EVENTS=("tap","route_enter","first_frame_recv","first_draw")
SEGMENTS=(("tap_to_route_enter","tap","route_enter"),("route_enter_to_first_frame","route_enter","first_frame_recv"),("first_frame_to_first_draw","first_frame_recv","first_draw"),("tap_to_first_draw","tap","first_draw"))
LINE=re.compile(r"\bopen_id=(\S+)\s+ev=(\S+)\s+t=(-?\d+)(.*)$")
LOG=re.compile(r"^(big_scrollback|real_claude_idle|redraw_tui)-(\d{2,})\.log$")
HEX64=re.compile(r"[0-9a-f]{64}"); HEX32=re.compile(r"[0-9a-f]{32}")
def fail(msg): print("FAIL baseline-bundle-measure: "+msg,file=sys.stderr); raise SystemExit(1)
def uj(msg): print("UNJUDGEABLE baseline-bundle-measure: "+msg,file=sys.stderr); raise SystemExit(2)
def load(path):
    try: return json.load(path.open(encoding="utf-8"))
    except Exception as e: uj(f"cannot parse {path}: {e}")
def sha(path):
    h=hashlib.sha256()
    try:
        with path.open("rb") as f:
            for b in iter(lambda:f.read(1024*1024),b""): h.update(b)
    except OSError as e: uj(f"cannot hash {path}: {e}")
    return h.hexdigest()
def md5(path):
    h=hashlib.md5()
    with path.open("rb") as f:
        for b in iter(lambda:f.read(1024*1024),b""): h.update(b)
    return h.hexdigest()
def relpath(value,prefix):
    if not isinstance(value,str) or value.startswith("/") or ".." in Path(value).parts or not value.startswith(prefix): uj("unsafe/noncanonical APK path")
    p=root/value
    try: p.resolve().relative_to(root.resolve())
    except Exception: uj("APK path escapes repository")
    if not p.is_file() or p.is_symlink(): uj("APK path missing or symlink")
    return p
def meta(lines):
    out={}
    for line in lines:
        if not line.startswith("# "): continue
        body=line[2:]
        if "=" in body:
            k,v=body.split("=",1)
            if k in out: fail("duplicate provenance header "+k)
            out[k]=v
    return out
def nr(values,q):
    if len(values)<10: uj("sample count below 10")
    s=sorted(values); return s[max(0,math.ceil(q*len(s))-1)]
def close(a,b): return isinstance(b,(int,float)) and math.isfinite(float(b)) and abs(float(a)-float(b))<=1e-9

d=load(result_path); m=load(manifest_path); p=load(pre_path)
if d.get("schema")!="perf-ab.v1": fail("result schema drift")
bid=m.get("bundle_id"); art=m.get("artifact") or {}; archive=m.get("archive") or {}; implementation=m.get("implementation") or {}
if not isinstance(bid,str) or not HEX64.fullmatch(bid): uj("manifest bundle id unavailable")
a_sha=art.get("apk_sha256"); a_md5=art.get("apk_md5")
if not isinstance(a_sha,str) or not HEX64.fullmatch(a_sha) or not isinstance(a_md5,str) or not HEX32.fullmatch(a_md5): uj("manifest A identity unavailable")
primary=relpath(archive.get("primary_relpath"),f".team/private/baseline-vault/{bid}/")
if sha(primary)!=a_sha or md5(primary)!=a_md5: uj("retrieved A APK does not match bundle")
required_zero=("bundle_retrieve_exit","archive_restore_exit","install_exit","envcheck_gate_exit")
if any(p.get(k)!=0 for k in required_zero): uj("pre-measure retrieve/restore/install/envcheck gate did not pass")
batch=p.get("batch_id"); runner_path=p.get("runner_path"); runner_sha=p.get("runner_sha256")
if not isinstance(batch,str) or not re.fullmatch(r"[A-Za-z0-9._-]{8,128}",batch): uj("batch identity missing")
if runner_path!="tools/perfbase/run-input-ab.sh": uj("runner path is not fixed")
runner=root/runner_path
if not runner.is_file() or not isinstance(runner_sha,str) or not HEX64.fullmatch(runner_sha) or sha(runner)!=runner_sha: uj("runner provenance mismatch")
runner_item=implementation.get("runner") or {}
if runner_item.get("path")!=runner_path or runner_item.get("sha256")!=runner_sha: uj("bundle manifest is not bound to measurement runner")
b_sha=p.get("candidate_apk_sha256"); b_md5=p.get("candidate_apk_md5"); b_rev=p.get("candidate_revision")
if not isinstance(b_sha,str) or not HEX64.fullmatch(b_sha) or not isinstance(b_md5,str) or not HEX32.fullmatch(b_md5) or not str(b_rev or "").strip(): uj("candidate identity missing")
candidate=relpath(p.get("candidate_apk_relpath"),f".team/private/baseline-candidates/{b_sha}/")
if sha(candidate)!=b_sha or md5(candidate)!=b_md5: uj("candidate APK digest mismatch")
if b_sha==a_sha or b_md5==a_md5 or b_md5=="3ebc9c55703c780c842a2f410b85034e": fail("candidate is A or prohibited old B")
identity={"batch_id":batch,"runner_sha256":runner_sha,"baseline_bundle_id":bid,"a_apk_sha256":a_sha,"a_apk_md5":a_md5,"b_apk_sha256":b_sha,"b_apk_md5":b_md5,"b_revision":str(b_rev)}
for k,v in identity.items():
    if str(p.get(k))!=v: uj("PRE-MEASURE identity mismatch "+k)
for k,v in (("baseline_bundle_id",bid),("baseline_measured_sha256",a_sha),("baseline_measured_md5",a_md5),("candidate_sha256",b_sha),("candidate_md5",b_md5),("candidate_revision",str(b_rev)),("batch_id",batch),("runner_sha256",runner_sha)):
    if str(d.get(k))!=v: uj("result identity mismatch "+k)
if (d.get("env") or {}).get("gate_exit")!=0: uj("result env gate did not pass")

order_lines=order_path.read_text(encoding="utf-8",errors="replace").splitlines()
om=meta(order_lines)
for k,v in identity.items():
    if om.get(k)!=v: uj("order provenance mismatch "+k)
rows={fx:[] for fx in FIXTURES}
for n,line in enumerate(order_lines,1):
    if not line or line.startswith("#") or line.lower().startswith("fixture\t"): continue
    fields=line.split("\t")
    if len(fields)!=3 or fields[0] not in rows or fields[2] not in ("A","B"): uj(f"bad order row {n}")
    try: seq=int(fields[1])
    except ValueError: uj(f"bad order sequence row {n}")
    rows[fields[0]].append((seq,fields[2]))

raw={fx:{"A":{},"B":{}} for fx in FIXTURES}
for package in ("A","B"):
    directory=order_path.parent/package
    paths=list(directory.glob("*.log"))
    if not paths: uj("no raw logs for "+package)
    for path in paths:
        match=LOG.fullmatch(path.name)
        if not match: uj("unknown raw log "+path.name)
        fx,seq=match.group(1),int(match.group(2))
        if seq in raw[fx][package]: uj("duplicate raw sequence")
        try: lines=path.read_text(encoding="utf-8",errors="replace").splitlines()
        except OSError as e: uj("cannot read raw log: "+str(e))
        if not lines or path.stat().st_size<=0: uj("empty raw log "+path.name)
        hm=meta(lines)
        expected={**identity,"fixture":fx,"sequence":str(seq),"package":package}
        for k,v in expected.items():
            if hm.get(k)!=v: uj(f"raw provenance mismatch {path.name} {k}")
        opens={}; order_ids=[]
        for line in lines:
            q=LINE.search(line)
            if not q: continue
            oid,ev,stamp,extra=q.group(1),q.group(2),int(q.group(3)),q.group(4)
            if ev not in EVENTS or "emitted=0" in extra: continue
            if oid not in opens: opens[oid]={}; order_ids.append(oid)
            opens[oid].setdefault(ev,stamp)
        complete=[opens[oid] for oid in order_ids if all(ev in opens[oid] for ev in EVENTS)]
        if len(complete)!=1: uj("raw log must contain exactly one complete open_id chain "+path.name)
        stamps=[complete[0][ev] for ev in EVENTS]
        if any(x>=y for x,y in zip(stamps,stamps[1:])): uj("nonmonotonic raw event chain "+path.name)
        raw[fx][package][seq]=complete[0]

values={fx:{seg:{"A":[],"B":[]} for seg,_,_ in SEGMENTS} for fx in FIXTURES}
for fx in FIXTURES:
    rr=rows[fx]
    if len(rr)<20 or len(rr)%2: uj(f"{fx}: order has fewer than 10 A/B pairs")
    if [pkg for _,pkg in rr] != [x for _ in range(len(rr)//2) for x in ("A","B")]: uj(f"{fx}: order is not strict A/B/A/B")
    seqs=sorted({seq for seq,_ in rr})
    if seqs!=list(range(1,len(seqs)+1)) or len(seqs)<10: uj(f"{fx}: order sequences are not contiguous n>=10")
    for seq in seqs:
        if rr.count((seq,"A"))!=1 or rr.count((seq,"B"))!=1: uj(f"{fx}: order package rows mismatch")
    if set(raw[fx]["A"])!=set(seqs) or set(raw[fx]["B"])!=set(seqs): uj(f"{fx}: raw/order sequence mismatch")
    for package in ("A","B"):
        for seq in seqs:
            ev=raw[fx][package][seq]
            for seg,start,end in SEGMENTS: values[fx][seg][package].append(float(ev[end]-ev[start]))

reg=[]
fixtures=d.get("fixtures") or {}
for fx in FIXTURES:
    fd=fixtures.get(fx)
    if not isinstance(fd,dict): fail("result missing fixture "+fx)
    for seg,_,_ in SEGMENTS:
        sd=fd.get(seg)
        if not isinstance(sd,dict): fail("result missing segment "+fx+"."+seg)
        av,bv=values[fx][seg]["A"],values[fx][seg]["B"]
        for package,want in (("A",av),("B",bv)):
            got=sd.get(package)
            if not isinstance(got,list) or len(got)!=len(want) or any(not close(a,b) for a,b in zip(want,got)): fail(f"raw/result array mismatch {fx}.{seg}.{package}")
            if (sd.get("n") or {}).get(package)!=len(want): fail(f"sample count mismatch {fx}.{seg}.{package}")
        for metric,q in (("p50",.50),("p95",.95)):
            aa,bb=nr(av,q),nr(bv,q); ratio=bb/aa if aa>0 else None
            if ratio is None: uj("nonpositive A duration")
            if not close(aa,(sd.get(metric) or {}).get("A")) or not close(bb,(sd.get(metric) or {}).get("B")): fail(f"nearest-rank mismatch {fx}.{seg}.{metric}")
            ratios=sd.get("ratio_b_over_a") or sd.get("B_over_A") or {}
            if not close(ratio,ratios.get(metric)): fail(f"ratio mismatch {fx}.{seg}.{metric}")
            print(f"MEASURE_RAW_EVIDENCE fixture={fx} segment={seg} metric={metric} A={aa:.3f} B={bb:.3f} ratio={ratio:.6f} n={len(av)}")
            if ratio>1.10: reg.append(f"{fx}.{seg}.{metric}={ratio:.6f}")
decl=d.get("measurement") or d.get("verdict")
if reg:
    if report_line!="measurement: fail" or decl!="fail": fail("valid regression is not honestly declared fail")
    fail("valid B/A regression above 1.10: "+", ".join(reg))
if report_line!="measurement: pass" or d.get("measurement")!="pass" or d.get("verdict")!="pass": fail("all-green raw evidence is not honestly declared pass")
print(f"MEASURE_BATCH_EVIDENCE batch_id={batch} A2={a_sha} B={b_sha} order=ABAB fixtures=3 min_n={min(len(rows[x])//2 for x in FIXTURES)}")
PY
rc=$?
case "$rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "raw judge unsupported rc=$rc" ;; esac
if git -C "$repo_root" ls-files '.team/private/baseline-candidates/**' 2>/dev/null | grep . >/dev/null 2>&1; then fail "candidate APK payload is tracked by git"; fi
printf '%s\n' "PASS baseline-bundle-measure: nonempty raw A/B/A/B independently passes nearest-rank 1.10"
