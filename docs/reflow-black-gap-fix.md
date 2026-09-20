# Reflow black-gap fix

Base: `b57ca542d2821642972cf0983369993d3778b66f`

## Root cause

The delivered manifest let Android recreate `MainActivity` for orientation and physical `wm size` changes. The app already handles viewport changes in-place through `TermSurfaceView.onSizeChanged` / `onWindowVisibilityChanged` and `TermViewPresenter`; nevertheless, Activity recreation tore down and rebuilt the Compose + AndroidView tree. The resize evidence showed the old and new terminal/dock layers at different widths until the replacement tree settled.

## Fix

`MainActivity` now handles `orientation|screenSize|smallestScreenSize|screenLayout` configuration changes in place. This preserves the Compose/AndroidView tree while Android performs the normal relayout, so existing viewport callbacks update the terminal without a destroy/recreate transition.

## Verification

Red test before the manifest change:

```sh
./gradlew :app:testDebugUnitTest --tests dev.agentmirror.app.MainActivityNavTest.displayResizeKeepsActivityInstance --no-daemon
```

Failed with `AssertionError` because the baseline activity had no resize configuration flags.

Green focused regression:

```sh
./gradlew :app:testDebugUnitTest \
  --tests dev.agentmirror.app.MainActivityNavTest \
  --tests dev.agentmirror.app.termview.TermViewPresenterTest \
  --tests dev.agentmirror.app.session.SessionDockSourceTest \
  --no-daemon
```

Exit 0.

## Transient-frame follow-up

The strict settled screenshot still showed default-background holes inside the terminal's ANSI status row for the first frame after a viewport change. Those pixels were terminal default background (`#0e0e0e`) under the optimized per-cell background skip, not a Compose root background gap. `TermSurfaceView` now disables only the default-background skip for four frames after `onSizeChanged` or visible re-entry, covering rotation's multi-frame transition while retaining the steady-state fast path. `TermDrawMeterTest.viewportChangeForcesCompleteBackgroundOnNextFrame` and `viewportChangeKeepsCompleteBackgroundAcrossTransitionFrames` cover this transition. Emulator mutations also suppress damage-triggered captures until `feed`/snapshot/resize parsing releases the emulator monitor, preventing capture/feed lock contention.

Focused rendering verification:

```sh
./gradlew :app:testDebugUnitTest \
  --tests dev.agentmirror.app.termview.TermDrawMeterTest \
  --tests dev.agentmirror.app.termview.TermBgCjkAlignTest \
  --tests dev.agentmirror.app.termview.TermForegroundEconomyTest \
  --tests dev.agentmirror.app.termview.TermViewPresenterTest \
  --no-daemon
```

Exit 0.

Session/mutation verification:

```sh
./gradlew :app:testDebugUnitTest \
  --tests dev.agentmirror.app.session.SessionViewModelTest \
  --tests dev.agentmirror.app.session.SessionImeResizeProtocolRegressionTest \
  --tests dev.agentmirror.app.termview.TermViewPresenterTest \
  --no-daemon
```

Exit 0.
