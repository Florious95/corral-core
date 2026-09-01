#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
manifest="$root/server/internal/nodeprobe/accepted-source.json"
bin=${AGENTMIRROR_NODEPROBE_BIN:-}
if [ -z "$bin" ]; then
  bin=$(command -v nodeprobe 2>/dev/null || true)
fi
if [ -z "$bin" ]; then
  bin=/Users/alauda/.local/bin/nodeprobe
fi
extension=${AGENTMIRROR_NODEPROBE_PI_EXTENSION:-"${HOME:?}/.pi/agent/extensions/nodeprobe-pi-activity.mjs"}

python3 - "$manifest" "$root" "$bin" "$extension" <<'PY'
import hashlib, json, os, platform, sys
manifest_path, root, binary, extension = sys.argv[1:]
m = json.load(open(manifest_path))
actual_platform = f"{platform.system().lower()}/{platform.machine().lower()}"
if actual_platform != m["platform"]:
    raise SystemExit(f"nodeprobe: unsupported platform {actual_platform}; accepted {m['platform']}")
def verify(path, coordinate, label):
    path = os.path.abspath(path)
    st = os.stat(path)
    if not os.path.isfile(path) or st.st_size != coordinate["size"]:
        raise SystemExit(f"nodeprobe: {label} coordinate mismatch: {path}")
    got = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if got != coordinate["sha256"]:
        raise SystemExit(f"nodeprobe: {label} sha256 mismatch: {path}")
verify(binary, m["binary"], "binary")
try:
    verify(extension, m["pi_extension"], "Pi extension")
except (OSError, SystemExit) as e:
    print(f"nodeprobe: visible Pi capability fault: {e}", file=sys.stderr)
for c in m["corpora"]:
    path = os.path.join(root, c["path"])
    got = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if got != c["sha256"]:
        raise SystemExit(f"nodeprobe: canonical corpus mismatch: {path}")
PY

export NODEPROBE_FIXTURES="$root/tools/nodeprobe/fixtures/titles.tsv"
export NODEPROBE_PROVIDERS="$root/tools/nodeprobe/fixtures/providers.tsv"
exec "$bin" "$@"
