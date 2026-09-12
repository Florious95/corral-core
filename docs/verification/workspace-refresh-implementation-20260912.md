# Workspace refresh: implementation and reproducible acceptance

The original host refresh enumerated every socket and sampled every pane before replying to either list level. With two A panes fixed and unrelated sockets increased to 17, the real baseline L1/L2 p95 was 3422.6/3347.7 ms. The baseline source is `655bd4441b57563d39457954420ae3557dc28ac4`; initial candidate `e395156df8b894236f02b9befb44bd2117fdea8d` was a starting point, not an accepted result.

## Changes

- L1 freshly enumerates structural membership, retaining all compatible session fields. Accepted observations for unchanged socket inventories are reused; two bounded workers revalidate them, including unknown providers, so starting an Agent in an existing shell is eventually discovered. New/changed inventories require a fresh accepted identity join, with at most four cold sampling workers. Cold identity validation still costs sampling; warm L1 does not wait for whole-host status refresh. No pane is identified as an Agent from its title alone.
- Four bounded enumeration workers publish per-socket routes before unrelated slow queries complete. L2 freshly enumerates only indexed sockets, joins only the requested workspace, and uses the original two-worker/coalescing/epoch coordinator. Shared sockets update all routes they actually observed. Observation start times prevent a delayed global inventory from revoking fresher scoped routes.
- A ref already in the scoped catalog can enter its terminal before the first global catalog completes. New refs and deletion overlays keep their original epoch protection.
- Real stopped-tmux testing exposed an additional timeout boundary: tmux passes output descriptors to its server, so killing the querying client alone can leave Go waiting indefinitely for pipe EOF. `WaitDelay=250ms` bounds that drain after cancellation; the socket query itself retains its five-second timeout. The accepted nodeprobe child drain is bounded too.
- Exiting the last tmux session can leave a socket file without a listener. Explicit `ECONNREFUSED`/`ENOENT` after a failed query proves absence and allows the authoritative empty frame; timeout and other errors never become empty. The socket file is preserved.

L1 observations are last accepted values, refreshed asynchronously; L2 is the fresh current-workspace status authority. At most two background identity probes, four cold probes and two L2 workers can run concurrently. Unknown/new identities are not permanently filtered. The accepted nodeprobe binary/report/corpora remain unchanged; a shared-socket probe still inspects the whole socket, and measured shared-socket latency determines whether that is sufficient. No cross-repository product change or new APK is needed.

## Evidence and boundaries

The isolated task root is `.team/nodes/developer/workspace-refresh-20260912/` in the corral-core workspace; source is in `serve/`, baseline in `baseline/`, and durable raw receipts in `evidence/`. Socket paths use uniquely created `/private/tmp/wr-*` directories because the checkout path exceeds the Unix socket path limit. Every daemon receives an explicit socket-directory allowlist; no host default is added. Newly created tmux servers use `-f /dev/null`. Failed attempts and their environments are retained.

`evidence/goal-start.json` contains the real native create_goal/get_goal active receipt, without a token budget. Initial dependency-backed Go 1.27.1 tests executed five index cases and seven API cases with race detection, and built the daemon. Final toolchain validation uses the repository's Go 1.26.5. These tests are distinct from the real runtime scenarios.

The first instrumented candidate measured L1 p95 26.8/118.8/23.0 ms and L2 p95 161.8/175.5/150.8 ms (small/separate/shared). These are development-candidate results, not the final frozen artifact. The initial small baseline may overlap compilation; supplemental final comparison must use the same clean-config fixtures and matching toolchain without concurrent builds. Thresholds were frozen before any candidate measurements in [workspace-refresh-thresholds-20260912.md](workspace-refresh-thresholds-20260912.md), and are not relaxed by later runs.

`evidence/revision7-runtime/results.json` records real tmux + accepted nodeprobe + WebSocket checks: cold A plus actual terminal marker while B was stopped (216ms + 152ms); same-socket/new-socket/new-workspace visibility and terminal access; A across two sockets and B sharing one of them; non-Agent exclusion; healthy A with a stopped unrelated socket; explicit slow-target error around 5.27s; saturated workers releasing and serving A around 5.42s; and deletion of the last session followed by reconnect producing `sessions:null` without resurrection. These are synthetic terminal workloads with provider-shaped executable names, not real model sessions. The sampler, tmux and daemon are real.

Earlier failures are preserved: an incorrect fixture-window-name lookup was an apparatus error; the cold harness originally checked the separate warm-L2 threshold instead of the frozen combined cold-to-terminal bound (corrected to sum both phases); stopped-server descriptor retention and stale socket-file deletion were product findings and caused code fixes. An old concrete discoverer-type assertion was updated to the new production type while preserving every actual scope/object assertion.

App acceptance uses the unchanged PR98 APK `0a6a4ed818260ffbda965663726e47660ce0e3a8`, SHA256 `3fe9b1d589b22315d7b9d32a4643e03e791e05abc5adcb6cc5934d208701527a`, on a newly isolated emulator 5596/ADB 5068. Exact final source/binary identities, actual final tests, latency tables, UI evidence and limitations belong to the PR receipt; this implementation note does not substitute for them. Production 9900/5580/5055 is protected. No merge, deployment or automatic teardown is authorized.

## Reproduction

Use a new absolute `CORRAL_WORKSPACE_EVIDENCE_DIR` outside the checkout. Copy the exact manifest-verified accepted binary, corpora and Pi extension into its `accepted/` directory, named `nodeprobe`, `titles.tsv`, `providers.tsv`, and `nodeprobe-pi-activity.js`. Compile `scripts/workspace-refresh/fixture.c` as `$CORRAL_WORKSPACE_EVIDENCE_DIR/bin/claude`. The executable is explicitly a synthetic terminal fixture, not a replacement sampler.

With real tmux installed at `/opt/homebrew/bin/tmux`, Python `websocket-client`, and actual baseline/candidate daemon builds:

```sh
python3 scripts/workspace-refresh/benchmark.py setup
python3 scripts/workspace-refresh/benchmark.py /absolute/baseline-agentmirrord baseline-perf
# Freeze thresholds before observing candidate data.
python3 scripts/workspace-refresh/benchmark.py /absolute/candidate-agentmirrord candidate-perf
python3 scripts/workspace-refresh/runtime_checks.py /absolute/candidate-agentmirrord runtime-checks
```

The benchmark writes 25 real L1 and 25 L2 measurements for each of three fixed scenarios. `WR_FIXTURES` chooses an explicitly named inventory receipt; `WR_SAMPLES` changes the declared sample count for supplemental instrumentation only. `WR_OBSERVER=1` inserts transparent executable observers that forward real tmux/ps/date stdout and exit codes, recording actual child start/end events; observer overhead must be separated from the primary latency comparison. Private pairing tokens and guides are never printed or committed. Scripts retain daemons, fixture sockets, logs and failed environments. Runtime fault cases stop/continue only their newly owned tmux PIDs and delete only their newly created DELETE session.
