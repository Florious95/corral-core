#!/bin/sh
# //! purpose: 在 successor6 投影门之后，从固定仓根独立重算来源、APK、运行内容、工具、报告与双份归档。
# //! contract: 0=真实 bundle 全部相符；1=实现/证据被反证；2=环境或必要外部量具不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-successor6-deep: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-successor6-deep: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
node="$repo_root/.team/nodes/baseline-bundle-impl"
manifest="$node/BUNDLE-MANIFEST.json"
route="$node/ROUTE.md"
focused="$repo_root/tools/perfbase/test-baseline-bundle.sh"

command -v git >/dev/null 2>&1 || unjudgeable "git unavailable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
command -v sh >/dev/null 2>&1 || unjudgeable "POSIX sh unavailable"
git_root=$(git -C "$repo_root" rev-parse --show-toplevel 2>/dev/null) || unjudgeable "acceptance copy is not in the real git worktree"
[ "$git_root" = "$repo_root" ] || unjudgeable "repository root mismatch actual=$git_root expected=$repo_root"
head=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null) || unjudgeable "cannot resolve worktree HEAD"
git -C "$repo_root" merge-base --is-ancestor a538117cc2e9832c88754ccfa9d6f9becb6a91b0 "$head" 2>/dev/null || unjudgeable "implementation worktree does not descend from fixed provenance base head=$head"

for f in "$route" "$node/IMPL.md" "$node/A2-EQUIVALENCE.md" "$node/BUILD.md" "$node/ARCHIVE.md" "$node/INSTALL.md" "$node/RETRIEVE.md" "$manifest" "$repo_root/.team/baseline-bundles/SCHEMA.md" "$repo_root/tools/perfbase/baseline-bundle.sh" "$repo_root/tools/perfbase/baseline_bundle.py" "$focused" "$repo_root/tools/perfbase/migrate-perf-regress.sh"; do
    [ -e "$f" ] || fail "missing required artifact $f"
    [ -r "$f" ] || unjudgeable "unreadable required artifact $f"
    [ -s "$f" ] || fail "empty required artifact $f"
done
[ "$(sed -n '$p' "$node/IMPL.md" 2>/dev/null)" = 'implementation: pass' ] || fail "IMPL.md does not end in implementation: pass"
for f in "$repo_root/tools/perfbase/baseline-bundle.sh" "$focused" "$repo_root/tools/perfbase/migrate-perf-regress.sh"; do
    sh -n "$f" >/dev/null 2>&1 || fail "invalid POSIX shell syntax $f"
    if grep -E '\[\[|\]\]|(^|[[:space:]])(local|declare|typeset|function)[[:space:]]|[<>]\(' "$f" >/dev/null 2>&1; then fail "bashism in $f"; fi
done
grep -F 'legacy_status: blocked_missing_baseline' "$route" >/dev/null 2>&1 || fail "ROUTE.md lacks honest missing-baseline status"
route_value=$(sed -n 's/^route: //p' "$route" 2>/dev/null | sed -n '1p') || unjudgeable "cannot read route"
case "$route_value" in recover_exact_artifact|rebaseline_with_equivalence_proof) ;; *) fail "unsupported route=$route_value" ;; esac

sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$repo_root/app/local.properties" 2>/dev/null | sed -n '1p')
[ -n "$sdk_dir" ] || unjudgeable "Android SDK path unavailable"
apksigner=$(find "$sdk_dir/build-tools" -type f -name apksigner -print 2>/dev/null | sort | tail -n 1)
aapt=$(find "$sdk_dir/build-tools" -type f -name aapt -print 2>/dev/null | sort | tail -n 1)
[ -x "$apksigner" ] || unjudgeable "apksigner unavailable"
[ -x "$aapt" ] || unjudgeable "aapt unavailable"

python3 - "$repo_root" "$manifest" "$route_value" "$apksigner" "$aapt" <<'PY'
import hashlib,json,os,re,shutil,subprocess,sys,zipfile
from pathlib import Path

root=Path(sys.argv[1]); manifest_path=Path(sys.argv[2]); route=sys.argv[3]
apksigner=Path(sys.argv[4]); aapt=Path(sys.argv[5])

def fail(msg): print("FAIL baseline-bundle-successor6-deep: "+msg,file=sys.stderr); raise SystemExit(1)
def uj(msg): print("UNJUDGEABLE baseline-bundle-successor6-deep: "+msg,file=sys.stderr); raise SystemExit(2)
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
def digest(v,n=64): return isinstance(v,str) and re.fullmatch(r"[0-9a-f]{%d}"%n,v) is not None
def need(obj,key,kind=None):
    if not isinstance(obj,dict) or key not in obj or obj[key] in (None,"",[]): fail("missing manifest field "+key)
    v=obj[key]
    if kind is not None and not isinstance(v,kind): fail("bad manifest type "+key)
    return v
def safe_rel(v,prefix):
    if not isinstance(v,str) or v.startswith("/") or ".." in Path(v).parts or not v.startswith(prefix): fail("unsafe or noncanonical path "+repr(v))
    p=root/v
    try: p.resolve().relative_to(root.resolve())
    except Exception: fail("path escapes repository "+v)
    return p
def runtime(apk):
    try:
        with zipfile.ZipFile(apk) as z:
            names=[]
            for info in z.infolist():
                name=info.filename
                upper=name.upper()
                if info.is_dir(): continue
                if upper=="META-INF/MANIFEST.MF" or (upper.startswith("META-INF/") and upper.endswith((".SF",".RSA",".DSA",".EC"))): continue
                names.append(name)
            if len(names)!=len(set(names)): fail("duplicate runtime ZIP entry")
            h=hashlib.sha256()
            for name in sorted(names):
                data=z.read(name); item=hashlib.sha256(data).hexdigest()
                h.update(name.encode()); h.update(b"\0"); h.update(str(len(data)).encode()); h.update(b"\0"); h.update(item.encode()); h.update(b"\n")
            return h.hexdigest(),len(names)
    except (OSError,zipfile.BadZipFile,KeyError) as e: uj(f"cannot normalize APK {apk}: {e}")
def apk_identity(apk):
    try:
        cert=subprocess.run([str(apksigner),"verify","--print-certs",str(apk)],text=True,capture_output=True,check=False)
        badging=subprocess.run([str(aapt),"dump","badging",str(apk)],text=True,capture_output=True,check=False)
    except OSError as e: uj("Android identity tool failed: "+str(e))
    if cert.returncode!=0 or badging.returncode!=0: uj("APK identity tools could not verify artifact")
    m=re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})",cert.stdout+cert.stderr)
    p=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",badging.stdout)
    if not m or not p: uj("cannot parse signer/package/version")
    return m.group(1).lower(),p.group(1),p.group(2),p.group(3)
try: d=json.load(manifest_path.open(encoding="utf-8"))
except Exception as e: uj("bad manifest: "+str(e))
if d.get("schema")!="agentmirror.baseline-bundle.v1": fail("schema drift")
bid=need(d,"bundle_id",str)
if not digest(bid): fail("bundle_id must be 64 lowercase hex")
source=need(d,"source",dict); run=need(d,"runtime",dict); artifact=need(d,"artifact",dict); build=need(d,"build",dict)
equiv=need(d,"equivalence",dict); archive=need(d,"archive",dict); reports=need(d,"reports",dict); implementation=need(d,"implementation",dict)
if source.get("tag")!="baseline-20260822-release" or source.get("commit")!="26f46642d3960b1bd96a39753b3f25516c5821eb" or source.get("dirty") is not False: fail("frozen source identity drift")
tree=subprocess.run(["git","-C",str(root),"ls-tree","-r","--full-tree",source["commit"]],capture_output=True,check=False)
if tree.returncode!=0: uj("cannot compute frozen source closure")
tree_digest=hashlib.sha256(tree.stdout).hexdigest()
if source.get("tree_sha256")!=tree_digest: fail("source closure digest mismatch")
for key in ("normalized_runtime_sha256",):
    if not digest(run.get(key)): fail("malformed runtime digest "+key)
if not isinstance(run.get("entry_count"),int) or run["entry_count"]<=0: fail("invalid runtime entry_count")
if run.get("package_name")!="dev.agentmirror.app" or not str(run.get("version_code","")).strip() or not str(run.get("version_name","")).strip(): fail("runtime package/version missing or drifted")
for key,n in (("apk_sha256",64),("apk_md5",32),("signer_certificate_sha256",64)):
    if not digest(artifact.get(key),n): fail("malformed artifact digest "+key)
if not isinstance(artifact.get("size_bytes"),int) or artifact["size_bytes"]<=0: fail("invalid artifact size")
if artifact["signer_certificate_sha256"]!="ea427eb4e14f95654a66802b6558fbbf6f93f1ca69d8117795fb7cef376cb13b": fail("signer drift")
primary_rel=need(archive,"primary_relpath",str); backup_rel=need(archive,"backup_relpath",str)
want_primary=f".team/private/baseline-vault/{bid}/baseline.apk"; want_backup=f".team/private/baseline-backup/{bid}/baseline.apk"
if primary_rel!=want_primary or backup_rel!=want_backup: fail("archive locations are not fixed content-addressed paths")
primary=safe_rel(primary_rel,".team/private/baseline-vault/"); backup=safe_rel(backup_rel,".team/private/baseline-backup/")
for p in (primary,backup):
    if not p.is_file(): fail("missing archived APK "+str(p))
    if p.is_symlink(): fail("archived APK is symlink "+str(p))
if os.stat(primary).st_ino==os.stat(backup).st_ino: fail("primary and backup share inode")
if os.stat(primary).st_mode & 0o222 or os.stat(backup).st_mode & 0o222: fail("sealed archive APK remains writable")
actual_sha=sha(primary); actual_md5=md5(primary); actual_size=primary.stat().st_size
if (actual_sha,actual_md5,actual_size)!=(artifact["apk_sha256"],artifact["apk_md5"],artifact["size_bytes"]): fail("artifact digest/size mismatch")
if sha(backup)!=actual_sha: fail("backup digest mismatch")
norm,count=runtime(primary); signer,package,vcode,vname=apk_identity(primary)
if (norm,count)!=(run["normalized_runtime_sha256"],run["entry_count"]): fail("runtime normalization mismatch")
if (signer,package,vcode,vname)!=(artifact["signer_certificate_sha256"],run["package_name"],str(run["version_code"]),str(run["version_name"])): fail("actual signer/package/version mismatch")
for key in ("primary_sha256","backup_sha256","recovered_sha256"):
    if archive.get(key)!=actual_sha: fail("archive digest mismatch "+key)
if archive.get("independent_inode") is not True or archive.get("primary_symlink") is not False or archive.get("backup_symlink") is not False or archive.get("sealed") is not True: fail("archive independence/seal fields invalid")
recover=manifest_path.parent/"tmp"/("acceptance-recover-%d.apk"%os.getpid())
try:
    recover.parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(backup,recover)
    if sha(recover)!=actual_sha: fail("independent backup retrieval digest mismatch")
except OSError as e:
    uj("independent backup retrieval unavailable: "+str(e))
finally:
    try: recover.unlink()
    except FileNotFoundError: pass
if equiv.get("route")!=route or equiv.get("matched") is not True or not digest(equiv.get("report_sha256")): fail("equivalence route/result/report invalid")
if route=="recover_exact_artifact":
    if (actual_md5,actual_size)!=("0907d6881bb1e034ef33a49f89afaa44",35044459): fail("exact A identity mismatch")
elif route=="rebaseline_with_equivalence_proof":
    builds=need(build,"independent_builds",list)
    if len(builds)!=2: fail("A2 requires exactly two retained independent builds")
    roots=set()
    for i,b in enumerate(builds,1):
        if not isinstance(b,dict): fail("bad independent build entry")
        p=safe_rel(need(b,"apk_relpath",str),".team/private/baseline-vault/")
        if not p.is_file() or p.is_symlink(): fail("missing/symlink independent build")
        roots.add(need(b,"build_root",str))
        if b.get("source_commit")!=source["commit"] or b.get("command")!="./gradlew :app:assembleRelease --rerun-tasks --no-build-cache" or b.get("cache_disabled") is not True: fail("independent build provenance invalid")
        bnorm,bcount=runtime(p); bsign,bpkg,bvc,bvn=apk_identity(p)
        if b.get("apk_sha256")!=sha(p) or b.get("normalized_runtime_sha256")!=bnorm: fail("independent build digest mismatch")
        if (bnorm,bcount,bsign,bpkg,bvc,bvn)!=(norm,count,signer,package,vcode,vname): fail("independent build runtime identity differs")
    if len(roots)!=2: fail("A2 build roots are not independent")
else: fail("unsupported route")
if build.get("command")!="./gradlew :app:assembleRelease --rerun-tasks --no-build-cache": fail("build command is not fixed no-cache command")
for key in ("gradle_wrapper_sha256","jdk","android_build_tools","agp"):
    if not str(build.get(key,"")).strip(): fail("missing build identity "+key)
wrapper=root/"app/gradle/wrapper/gradle-wrapper.jar"
if not wrapper.is_file() or build.get("gradle_wrapper_sha256")!=sha(wrapper): fail("Gradle wrapper identity mismatch")
if build.get("android_build_tools")!=apksigner.parent.name: fail("Android build-tools identity mismatch")
tool_map={"bundle_sh":"tools/perfbase/baseline-bundle.sh","bundle_py":"tools/perfbase/baseline_bundle.py","migration_sh":"tools/perfbase/migrate-perf-regress.sh","focused_test_sh":"tools/perfbase/test-baseline-bundle.sh","runner":"tools/perfbase/run-input-ab.sh"}
if implementation.get("provenance_base")!="a538117cc2e9832c88754ccfa9d6f9becb6a91b0": fail("implementation provenance base mismatch")
for key,rel in tool_map.items():
    item=implementation.get(key)
    if not isinstance(item,dict) or item.get("path")!=rel or item.get("sha256")!=sha(root/rel): fail("implementation entry digest mismatch "+key)
report_map={"build_sha256":"BUILD.md","equivalence_sha256":"A2-EQUIVALENCE.md","archive_sha256":"ARCHIVE.md","install_sha256":"INSTALL.md","retrieve_sha256":"RETRIEVE.md"}
for key,name in report_map.items():
    if reports.get(key)!=sha(manifest_path.parent/name): fail("report digest mismatch "+key)
if equiv.get("report_sha256")!=reports.get("equivalence_sha256"): fail("equivalence report cross-reference mismatch")
projection={k:d[k] for k in ("source","runtime","artifact","build","equivalence","implementation")}
calc_bid=hashlib.sha256(json.dumps(projection,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()).hexdigest()
if bid!=calc_bid: fail("bundle_id is not canonical identity projection SHA-256")
if route=="rebaseline_with_equivalence_proof" and equiv.get("reference")!="baseline-20260822-release behavior + frozen source commit": fail("A2 equivalence reference missing")
print(f"BUNDLE_REALITY_EVIDENCE bundle_id={bid} apk_sha256={actual_sha} runtime_sha256={norm} entries={count} primary_inode={os.stat(primary).st_ino} backup_inode={os.stat(backup).st_ino}")
PY
judge_rc=$?
case "$judge_rc" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "independent judge rc=$judge_rc" ;; esac

output=$(sh "$focused" 2>&1)
focused_rc=$?
printf '%s\n' "$output"
case "$focused_rc" in 0) ;; 1) fail "focused tests failed after independent bundle proof" ;; 2) unjudgeable "focused tests unavailable" ;; *) unjudgeable "focused tests rc=$focused_rc" ;; esac
printf '%s\n' "$output" | grep -F 'BASELINE_BUNDLE_EVIDENCE missing_route=true exact_route=true a2_equivalence=true runtime_mutation=true archive_restore=true migration_precheck=true' >/dev/null 2>&1 || fail "focused mutation evidence missing"
if git -C "$repo_root" ls-files '.team/private/baseline-vault/**' '.team/private/baseline-backup/**' '.team/private/baseline-candidates/**' 2>/dev/null | grep . >/dev/null 2>&1; then fail "private APK payload is tracked by git"; fi
printf '%s\n' "PASS baseline-bundle-successor6-deep: fixed-provenance real bundle independently reconstructed and archived"
