# Fixed04bf external observer — unexecuted candidate

This standalone Android application contains an Instrumentation targeting the existing
`dev.agentmirror.app`. It compiles only Java reflection/Android APIs, not App source.
Product source04bf and APK SHA bd4ababfcec3301c19ced02350ff2193f183311f9ec0bdd455145deab3b3e861 remain fixed.
No device launch or performance run is part of hosted compilation.

## Endpoint

The actual resumed Activity's attached visible TermSurfaceView provides its bound
SessionViewModel via the existing Kotlin onRemoteScrollBy function reference. Read
only the explicitly named presenter, emulator, history and manager connection fields.
Never register a replacement product listener, feed/replay frames, invoke a VM handler,
modify the grid, or force a draw. Read the emulator under its monitor and history lock.

A match requires exact socket-qualified ref; READY/no transient error; completed
initial prefetch and no pending history; every current-screen Cell (text, width,
fg/bg including RGB/index, bold/dim/italic/underline/inverse/strike), dimensions,
cursor position/visibility/alt flag; and every cell of the required latest20 history
rows. Older retained history is allowed and included in raw observations. These20
rows are the declared needed history of this fixture, not all possible history.
The actual presenter viewport must contain the full current screen.

The observer arms before a genuine UI tap or foreground launch. Android
elapsedRealtimeNanos records the action and reads. Only a real hardware frame-commit
callback after an OnDraw match can finish; a second read must still match the same
VM/connection identity. Reflection/layout failures, absent history or commit,
protocol errors, wrong ref/grid/cursor/style, and indistinguishable old generation
all fail closed. Missing callbacks are not replaced by first_draw/wire/screenshot.

Generation means a unique real-fixture output generation in expected cells, tied to
trial ID/ref and prior expected cells; it is not an invented server protocol epoch.
Record actual VM/connection identities too. Foreground need not create a new socket.
The previous generation's oracle must differ and must be rejected by the completed
observation. Read-only DTO negative controls also reject wrong ref, empty grid,
history pending and absent required history. These controls are implemented, not
claimed executed before device evidence. Runtime controls must additionally stage
old-generation output and withheld FIFO publication before releasing real fixture
output; no injected binary frames.

## Error and overhead

This is an observed application+frame-commit upper endpoint, not exact photons.
Each sample records readStart/readEnd, commit callback observed on the UI queue,
after-read completion, read attempt count and total pre-read cost. The conservative
completion interval is [successful readStart, frameCommitObserved]; callback queue
latency and instrumentation work widen that interval. The second read cost is also
recorded. No interval is silently collapsed to wire/first_draw time or subtracted
from reported latency. Same observer binary/settings on both variants. A/B conclusions
must survive interval bounds and measured observer overhead; otherwise UNJUDGEABLE.
Hardware acceleration and stable active Activity are required. Full-screen geometry
must be settled before sampling; partial keyboard viewport is not called complete.

## Build, signing and device interface

`python3 tools/perf15/observer/hosted_compile.py` builds with Gradle8.9/JDK17,
AGP8.7.3/SDK35, --rerun-tasks --no-build-cache. Hosted debug signature is provisional.
After hash/manifest verification, locally re-sign **only** this test APK with the
already-existing Android debug key. Public certificate SHA256 of the fixed APK and
local key was independently compared: ea427eb4e14f95654a66802b6558fbbf6f93f1ca69d8117795fb7cef376cb13b.
Use apksigner's standard debug-key signing input without reading/exporting key bytes;
verify testAPK certificate/hash after signing. Non-debuggable fixed APK therefore
has a matching instrumentation signature; never rebuild/re-sign the product APK.

Install only on owned emulator5596/adb5055 after the approved resource window and
envcheck gate. Never target5580/5038. Runner component:
`dev.agentmirror.perf15/dev.agentmirror.perf15.Observer`.
The fixed installed product APK is hashed inside the runner before any sample.

`sample_block.py --plan <own plan.json> --prepare <own prepare_trial.py>
--window-receipt <active window/envcheck receipt> --out <new own output>` runs one
block of10select+10resume. Input trials require id/generation/action/expected/
previousExpected/deadlineMs (<=35000), x/y for selection, backgroundMs(1..10000)
for resume, and fixture/pane/fifo/setupTap for the preparation hook. Preparation
happens outside timing through readiness events, with a120s bounded handoff; never
pre-place a go file. Plan/output live under the target's dedicated external-files
perf15-observer subdirectory. All files contain only owned synthetic fixture data.

`fixture_oracle.py emit <owned FIFO>` is the real long-lived tmux pane process.
Its20history+full-screen deterministic ANSI rows are the independent oracle;
`prepare_trial.py` verifies own exact socket/ref/process, clears own tmux history,
writes FIFO, waits for real captured tail/cursor, and uses existing normal UI
navigation. No WS fake and no actual user pane input. Fixture startup retains the
existing short-root/unsetTMUX/explicit-S/list-sessions proof rules. This carrier
prepares observation/fixture interfaces; actual1/10/50fixture setup, full plan
freezing and device observer negative controls remain unexecuted until a window.

After each block, collector stops only own target/instrumentation. Existing owned
fixture/daemon/device cleanup remains mandatory; unproven cleanup stops next block.
No overall performance PASS is produced by this runner. Full judge retains four
blocksA/B/A/B for each1/10/50,10samples per endpoint/block,p50/p95 B/A<=1.10,
failure-rate nonincrease and scan counts. Existing six scrollback reds remain reds;
if they prevent real required history, reject the sample, do not waive it.
