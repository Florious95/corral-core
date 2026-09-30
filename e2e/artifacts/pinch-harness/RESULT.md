# Pinch harness result

Date: 2026-08-12

## Verdict

v2 pinch is not functionally broken. A standards-shaped two-pointer `MotionEvent` sequence makes
`ScaleGestureDetector` enter and leave scaling and changes `TermViewPresenter.cellWidth/cellHeight`
in the Robolectric regression test. Independently, the user reported on a real phone that pinch
"看起来完全正常". Therefore the earlier `/dev/input/event1` observation is evidence that the adb
injection did not form an App-recognizable pinch, not that the App pinch path was broken.

## Permanent JVM guard

`TermSurfacePinchGestureTest` sends this complete stream to `TermSurfaceView.onTouchEvent`:

`ACTION_DOWN -> ACTION_POINTER_DOWN -> ACTION_MOVE x3 -> ACTION_POINTER_UP -> ACTION_UP`

Every two-pointer event carries two stable pointer ids, finger tool types, touchscreen source,
non-zero pressure/size, and changing coordinates. The test separately asserts that the private
`ScaleGestureDetector` enters/exits `isInProgress` and that presenter cell geometry grows. Removing
or breaking the View's scale-detector-to-presenter wiring makes this test fail.

## Instrumented status

The test-only `PinchHarnessInstrumentation` builds and installs without product-code or Gradle
changes. It launches an isolated `ComponentActivity`, hosts a real `TermSurfaceView`, and calls
`UiAutomation.injectInputEvent(event, true)` for the same multi-pointer stream.

Runtime status is **RED / unresolved**, not pass: every injection returned `true`, but presenter
geometry remained `10x20 -> 10x20`. The two-run diagnosis budget is exhausted. This result does not
distinguish window dispatch/focus from event timing or another harness issue. Also, `am instrument`
returns shell exit 0 for this custom runner even when its result bundle says `pinch_harness=FAIL`;
automation must treat that bundle field, not the shell exit code, as truth.

## Verification

- `bash -lc 'cd app && env -u TEAM_AGENT_* ./gradlew :app:testDebugUnitTest --tests dev.agentmirror.app.termview.TermSurfacePinchGestureTest'` — PASS (`jvm-focused.log`)
- `bash -lc 'cd app && env -u TEAM_AGENT_* ./gradlew :app:testDebugUnitTest'` — PASS (`jvm-full.log`), including `TermSurfaceSessionBindingRegressionTest`
- `bash -lc 'cd app && env -u TEAM_AGENT_* ./gradlew :app:assembleDebugAndroidTest'` — PASS (`androidtest-build-final.log` and rebuild logs)
- Emulator runtime — RED: `INSTRUMENTATION_RESULT: pinch_harness=FAIL`, injection accepted but cells unchanged

No product source, build configuration, real tmux pane, or production daemon was touched.
