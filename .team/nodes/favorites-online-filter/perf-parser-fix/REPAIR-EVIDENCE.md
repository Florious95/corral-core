# Parser false-green investigation

Status: **NO BUG CONFIRMED; no product fix and no PR created.**

The purported non-monotonic false-green was caused by the node self-test fixture,
not by `parse-input-ab.py`: the test attempted to replace `t=100070`, while the
actual generated `redraw_tui-01` timestamp was `t=101070`. The replacement was a
no-op and the packet remained monotonic.

## Exact source identity and scope

- Independent worktree:
  `.team/nodes/favorites-online-filter/perf-parser-fix/core`
- Branch: `pr/perf-parser-monotonicity` (no commit made)
- HEAD/base: `eaaa7d47d88e7e2de6c82988fe462e7adf29f86d`
- Parser blob at HEAD: `82519fa339dfe200cc1c5c0f1b8ec312ea91e9a6`
- Same parser blob at the previously inspected PR76 tree: `82519fa339dfe200cc1c5c0f1b8ec312ea91e9a6`
- Remote: `https://github.com/Florious95/corral-core.git`
- Intended PR base, if a real defect had existed: `pr/foreground-resume-refresh`
- Product changes: none; worktree is clean.

The parser's existing same-`open_id` monotonic check is the shared check used by
`tools/perfbase/run-input-ab.sh`; no caller-specific guard was added. Caller
search found only:

```text
core/tools/perfbase/run-input-ab.sh
core/tools/perfbase/test-envcheck-measurement-emulator.sh
core/tools/perfbase/test-run-input-ab-emulator-ownership.sh
```

## Minimal corrected counterexample and actual result

Packet root:
`tmp/nonmonotonic-repro/`

The invalid sample is one `open_id` with timestamps:

```text
D PerfTrace: open_id=A-redraw_tui-1 ev=tap t=101000
D PerfTrace: open_id=A-redraw_tui-1 ev=route_enter t=101010
D PerfTrace: open_id=A-redraw_tui-1 ev=first_frame_recv t=101030
D PerfTrace: open_id=A-redraw_tui-1 ev=first_draw t=101020
```

This violates monotonicity at `first_draw < first_frame_recv`. The packet retains
the other required synthetic fixture/sample files so the parser reaches the
monotonic check. Exact command is in `tmp/nonmonotonic-repro/command.txt`; exact
stdout, stderr, JSON, and exit code are retained there.

Observed on the base parser:

```text
rc=2
stdout: UNJUDGEABLE schema=perf-ab.v1 .../baseline-output.json
stderr: issue: redraw_tui A #1: event timestamps are not monotonic
        issue: redraw_tui.tap_to_route_enter.A: n=9 < 10
        issue: redraw_tui.route_enter_to_first_frame.A: n=9 < 10
        issue: redraw_tui.first_frame_to_first_draw.A: n=9 < 10
        issue: redraw_tui.tap_to_first_draw.A: n=9 < 10
```

Therefore the required illegal same-`open_id` case is already red; there is no
false-green parser defect to own.

A separate legal interleaving fixture has two complete monotonic `open_id`
chains with lines interleaved. The parser returns `rc=0`, `verdict=pass`; it is
not rejected merely because log lines are interleaved.

## Self-test and checks

Node-local command:

```text
python3 .team/nodes/favorites-online-filter/perf/perf_selftest.py
```

Corrected result: positive packet `rc=0/verdict=pass`; wrong A identity,
identical A/B identity, bad A/B order, missing event, non-monotonic event, and
non-zero envcheck each `rc=2/verdict=unjudgeable`; legal interleaved packet
`rc=0/verdict=pass`. Full result:
`.team/nodes/favorites-online-filter/tmp/parser-selftest/SELFTEST-RESULT.json`.

Additional checks on the independent base tree:

```text
bash -n core/tools/perfbase/run-input-ab.sh        PASS
AST parse core/tools/perfbase/parse-input-ab.py   PASS
git status --short                                 clean
```

No device, performance window, A artifact, product APK, production daemon,
worker, or contract was touched. The exact A identity gap remains unchanged and
is not masked by this correction. No PR URL/SHA exists because no source fix was
warranted.

## Remaining unknowns

- Frozen contract A MD5 remains unavailable; no real A/B/A/B run is judged.
- No performance samples, p50/p95 ratios, or device gate result were generated.
- The corrected parser check is validated only with the retained synthetic
  packet; full performance acceptance still requires the leader's separate A
  identity decision and authorized measurement path.
