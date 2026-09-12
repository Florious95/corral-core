#!/usr/bin/env bash
# Run only in an independent environment. The script does not launch agentmirrord
# or issue tmux commands; API unit tests use isolated httptest listeners.
# Evidence, build outputs and caches are deliberately retained.
set -euo pipefail
: "${CORRAL_VERIFICATION_DIR:?Set an absolute NEW evidence directory outside the checkout}"
case "$CORRAL_VERIFICATION_DIR" in /*) ;; *) echo 'Evidence directory must be absolute' >&2; exit 2;; esac
root="$(git rev-parse --show-toplevel)"
case "$CORRAL_VERIFICATION_DIR/" in "$root/"*) echo 'Evidence must be outside checkout' >&2; exit 2;; esac
cd "$root"
git diff --quiet && git diff --cached --quiet || { echo 'Use a clean independent checkout; do not overwrite local changes' >&2; exit 2; }
# Refuse to overwrite earlier evidence. No trap removes anything.
mkdir "$CORRAL_VERIFICATION_DIR"
evidence="$CORRAL_VERIFICATION_DIR"
export GOCACHE="$evidence/gocache" GOMODCACHE="$evidence/gomodcache" GOPATH="$evidence/gopath"
export GOTOOLCHAIN=local GOWORK=off
mkdir -p "$GOCACHE" "$GOMODCACHE" "$GOPATH" "$evidence/bin"
git rev-parse HEAD > "$evidence/head.txt"
git rev-parse HEAD^{tree} > "$evidence/tree.txt"
go version | tee "$evidence/go-version.txt"
printf '%s\n' 'These checks are NOT real-tmux benchmarks or Android acceptance.' | tee "$evidence/SCOPE.txt"
go test -race -count=1 -v ./internal/discovery -run '^TestWorkspaceIndex' 2>&1 | tee "$evidence/index-tests.log"
go test -race -count=1 -v ./internal/api -run '^TestWorkspaceRefresh' 2>&1 | tee "$evidence/api-tests.log"
go build -trimpath -o "$evidence/bin/agentmirrord" ./cmd/agentmirrord 2>&1 | tee "$evidence/build.log"
if command -v sha256sum >/dev/null; then
  sha256sum "$evidence/bin/agentmirrord" > "$evidence/server-sha256.txt"
else
  shasum -a 256 "$evidence/bin/agentmirrord" > "$evidence/server-sha256.txt"
fi
printf '%s\n' 'Build and selected regressions finished. Full regression, L1 separation, real-tmux A/B and Android acceptance are still required.'
