# REWORK-001 verification receipt

- Frozen parent: `f1c4fdd4da64efa220d15ee4a9b1759709e6c1a5`
- Test-fix commit: `22483b6706a0e631f80690cc0619da3901be887d`
- Code scope: only `server/internal/api/level2_test.go`
- Production code, accepted `ff316dc0` source, canonical corpora, Provider UI, and session UI: unchanged

## Single-run verification

1. `cd server && go test -count=20 ./internal/api -run '^(TestL2NoPollWithoutSubscriber|TestLevel2StopsWhenNoSubscriber)$'`
   - PASS, exit 0, 18 seconds; package reported 15.823s
   - Log: `targeted-count20.log`
2. `cd server && go test -count=1 ./cmd/agentmirrord ./internal/nodeprobe ./internal/api ./internal/protocol`
   - PASS, exit 0, 54 seconds
   - Log: `go-four-packages.log`
3. `python3 tools/gate/nodeprobe-status-core.py`
   - PASS, exit 0, 1 second
   - Accepted commit `ff316dc0afe8ab280e61d30934e7624579be6224`; accepted tree `5217a41aa914ddcb72c27f39f1b4af9ead68b1b6`
   - Log: `provenance-gate.log`

Each declared command was invoked exactly once; no retry-to-green occurred. Managed mirror heads and OPEN PR state are recorded in the PR metadata and final delivery result after the evidence commit is mirrored.
