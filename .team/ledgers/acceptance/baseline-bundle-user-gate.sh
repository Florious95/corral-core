#!/bin/sh
# //! purpose: 机械绑定用户真机“蜂窝+广州中转、秒开无空白”裁定到确切 bundle。
# //! contract: 0=用户通过；1=用户确认倒退；2=未测或证据不可判。
# ledger: expected_exit_code=0; unjudgeable_exit_codes=[2]

set -u
fail() { printf '%s\n' "FAIL baseline-bundle-user: $*" >&2; exit 1; }
unjudgeable() { printf '%s\n' "UNJUDGEABLE baseline-bundle-user: $*" >&2; exit 2; }
script_dir=$(CDPATH='' cd "$(dirname "$0")" 2>/dev/null && pwd) || unjudgeable "cannot resolve script directory"
repo_root=$(CDPATH='' cd "$script_dir/../../.." 2>/dev/null && pwd) || unjudgeable "cannot resolve repository root"
gate="$repo_root/.team/nodes/baseline-bundle-user/USER-GATE.json"
manifest="$repo_root/.team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json"
[ -e "$gate" ] || unjudgeable "user gate not recorded"
[ -r "$gate" ] || unjudgeable "user gate unreadable"
[ -s "$gate" ] || unjudgeable "user gate empty"
[ -r "$manifest" ] || unjudgeable "bundle manifest unavailable"
command -v python3 >/dev/null 2>&1 || unjudgeable "python3 unavailable"
python3 - "$gate" "$manifest" <<'PY'
import json, sys
try: g=json.load(open(sys.argv[1],encoding="utf-8")); m=json.load(open(sys.argv[2],encoding="utf-8"))
except Exception as exc: print("UNJUDGEABLE baseline-bundle-user:",exc,file=sys.stderr); sys.exit(2)
v=g.get("verdict")
if v=="fail": print("FAIL baseline-bundle-user: user reports regression",file=sys.stderr); sys.exit(1)
if v!="pass": print("UNJUDGEABLE baseline-bundle-user: no pass verdict",file=sys.stderr); sys.exit(2)
checks=[
 ((g.get("reported_by") or {}).get("kind")=="user","reported_by.kind"),
 (g.get("bundle_id")==m.get("bundle_id"),"bundle_id"),
 (g.get("apk_sha256")==((m.get("artifact") or {}).get("apk_sha256")),"apk_sha256"),
 (g.get("signer_certificate_sha256")==((m.get("artifact") or {}).get("signer_certificate_sha256")),"signer"),
 (g.get("network")=="cellular","network"),(g.get("relay")=="广州中转","relay"),
 (g.get("real_alt_screen_agent_cli") is True,"real_alt_screen_agent_cli"),
 (g.get("session_open")=="秒开","session_open"),(g.get("blank_frame") is False,"blank_frame"),
 (bool(g.get("observed_at")),"observed_at")]
bad=[name for ok,name in checks if not ok]
if bad: print("UNJUDGEABLE baseline-bundle-user: missing/mismatched "+", ".join(bad),file=sys.stderr); sys.exit(2)
print("user-gate-ok")
PY
case "$?" in 0) ;; 1) exit 1 ;; 2) exit 2 ;; *) unjudgeable "user gate judge unsupported rc" ;; esac
printf '%s\n' "PASS baseline-bundle-user: exact bundle passed real-device gold standard"

