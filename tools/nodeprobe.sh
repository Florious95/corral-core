#!/usr/bin/env bash
# nodeprobe.sh — wrap tools/nodeprobe (requirement 063). Read-only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CRATE="$HERE/nodeprobe"

target_dir() {
  cargo metadata --format-version 1 --no-deps --manifest-path "$CRATE/Cargo.toml" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])'
}

BIN="$(target_dir)/release/nodeprobe"
if [[ ! -x "$BIN" ]]; then
  cargo build --release --manifest-path "$CRATE/Cargo.toml" >&2
fi
if [[ ! -x "$BIN" ]]; then
  echo "nodeprobe.sh: missing $BIN after cargo build --release" >&2
  exit 1
fi
exec "$BIN" "$@"
