# Performance gate readiness

Status: **UNJUDGEABLE — no performance sample was run**.

## Scope and frozen identities

This is a node-local readiness check only. No product source, PR branch, contract,
threshold, old evidence, emulator-5590, production daemon, tailnet, or real worker
was changed or contacted.

- Product source: `corral-core` branch `pr/favorites-online-filter`, fixed HEAD
  `649f5f91f35a66de8afd73221045d8187f74664c`.
- Contract: `core/.team/nodes/input-full-auto/perf-design/CONTRACT.md`.
- Required A behavior identity: tag `baseline-20260822-release`, exact MD5
  `0907d6881bb1e034ef33a49f89afaa44`, 35,044,459 bytes.
- Current signed B artifact:
  `.team/nodes/favorites-online-filter/signing/AgentMirror-Favorites-Online-649f5f91-signed.apk`
  - source: `649f5f91f35a66de8afd73221045d8187f74664c`
  - bytes: `35,655,272`
  - MD5: `b456b0c9802ba145e5abc782d67f597c`
  - SHA256: `5d17659776f95026a5c22320e6c7d99b69184c9eb1d2afac5531d4ee21943263`

The frozen A APK is not present in the known local artifact paths. The old
35,044,459-byte APK is MD5 `aecdbd461deece5daec8f81c70af8e54`, so it is not a
permitted substitute.

## Existing sampler/parser finding

The later four-segment implementation is present in the fixed tree; the contract's
old `contract: blocked` text is not evidence that the files are absent:

- `core/tools/perfbase/run-input-ab.sh`
- `core/tools/perfbase/parse-input-ab.py`
- implementation note:
  `.team/nodes/input-full-auto/sampler-impl/IMPL.md`

The shell entry point is the intended A/B/A/B, three-fixture, `n>=10`, identity,
environment, raw-log, and four-segment path. The old
`.team/nodes/ca-emu/tmp/runab.sh` remains unsuitable (A-then-B and two segments)
and was not invoked.

Read-only checks completed:

```text
bash -n core/tools/perfbase/run-input-ab.sh                         PASS
AST parse core/tools/perfbase/parse-input-ab.py                      PASS
AST parse perf/perf_selftest.py                                     PASS
```

The normal sampler was not started because it would require a permitted exact A,
a passing environment/device measurement window, and the fixed raw-output scope;
none was silently substituted.

## Node-local parser self-test

`perf/perf_selftest.py` invokes the existing parser with all generated data under
`tmp/parser-selftest/`; it does not touch product code or devices. Result:
`tmp/parser-selftest/SELFTEST-RESULT.json`.

- Positive synthetic packet: **PASS**, 3 fixtures × 10 A/B logs, four segments,
  and all A/B counts 10.
- Wrong A MD5: **UNJUDGEABLE (rc=2)**.
- Identical A/B MD5: **UNJUDGEABLE (rc=2)**.
- Non-A/B/A/B order: **UNJUDGEABLE (rc=2)**.
- Missing event/sample: **UNJUDGEABLE (rc=2)**.
- Non-zero envcheck: **UNJUDGEABLE (rc=2)**.
- Non-monotonic packet: **parser tool gap** — existing parser returned PASS
  (rc=0), although the contract requires UNJUDGEABLE. This is recorded, not
  treated as a performance pass.
- Interleaved open-id packet: **UNJUDGEABLE (rc=2)** in this fixture; no claim is
  made that the parser generally rejects every interleaving shape.

## A reconstruction attempt (Grok Bot, not local compilation)

The exact frozen A was rebuilt from the authoritative tag through the authorized
Grok Bot path only:

```text
source tag: baseline-20260822-release
authoritative source commit: e6cb2bff9ec0cdda770bde718964c2e9de68a3d2
command: cd app && ANDROID_HOME=/home/box/Android/Sdk ANDROID_SDK_ROOT=/home/box/Android/Sdk ./gradlew --max-workers=2 --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease
result: BUILD SUCCESSFUL, 49s, 57 actionable tasks
bytes: 35044459
MD5: 6ae3a104825b51d7d2a8efee40ed4a85
SHA256: 8efb9800ba958d9dd589855aa701fb51bede32d3f8dbee6f8393b67eb6332504
artifact Git branch: artifacts/favorites-perf-baseline-20260822-release-v1
artifact commit: 578c0da4ace5f024bcaa1c28665787bf09ebf586
```

This size-matching rebuild still does **not** have the frozen A MD5 and therefore
cannot be used as A. Build receipt/log: `tmp/grok-baseline-release-build.log`.

## Gate result and minimum recovery

No A/B/A/B run was started; therefore there are no raw PerfTrace samples, sample
counts from a device, p50/p95 values, ratio results, or performance verdict to
report. The gate remains **UNJUDGEABLE**, not PASS and not FAIL.

Minimum recovery input: provide the exact frozen A artifact with MD5
`0907d6881bb1e034ef33a49f89afaa44` (and its source/path receipt), or make an
explicit contract-level decision about a replacement A. Do not substitute the
size-matching `6ae3a104825b51d7d2a8efee40ed4a85` rebuild or the old
`aecdbd461deece5daec8f81c70af8e54` APK.

## Follow-up correction: non-monotonic self-test

The earlier self-test's claimed parser gap was a fixture-construction error: it
attempted to replace `t=100070`, but that packet's `redraw_tui-01` timestamp was
`101070`, so the file stayed monotonic. It was not evidence of a parser false
accept.

After correction, the packet uses one `open_id` with `tap=101000`,
`route_enter=101010`, `first_frame_recv=101030`, and `first_draw=101020`. The
existing parser returns `rc=2` and reports `redraw_tui A #1: event timestamps are
not monotonic`. A legal packet with two complete, monotonic interleaved
`open_id` chains remains accepted (`rc=0`). No parser product fix or PR was
created. The corrected packet and exact output are under
`perf-parser-fix/tmp/nonmonotonic-repro/`; the corrected node self-test result is
`tmp/parser-selftest/SELFTEST-RESULT.json`.
