package bridge

// scroll_test.go: red tests for InjectScroll, PaneInMode, ExitCopyMode.
// All tests use an isolated tmux socket (newTestTMUX) — production daemon
// and user tmux are never touched (bridge red line).
//
// T1: bare shell (mouse_any_flag=0) → copy-mode entered
// T2: vim+mouse (mouse_any_flag=1) → copy-mode entered (unified path;
//     send-keys -H mouse bytes proved ineffective, see design doc §已知局限)
// T5: PaneInMode reports correctly
// T6: ExitCopyMode exits copy-mode (pane_in_mode 1→0)
// T7: InjectScroll on dead pane → ErrPaneNotFound
// T8: already-in-copy-mode → scroll only (enteredCopyMode=false)

import (
	"context"
	"strings"
	"testing"
	"time"
)

// queryFlag is a helper to read a single tmux format variable from the pane.
func queryFlag(t *testing.T, tt *testTMUX, pane *Pane, format string) string {
	t.Helper()
	out, err := tt.run("display-message", "-p", "-t", pane.target, format)
	if err != nil {
		t.Fatalf("display-message %q: %v", format, err)
	}
	return strings.TrimSpace(out)
}

// waitForPane waits until the pane's running program has settled (brief sleep
// so e.g. vim has time to draw its screen and set mouse flags).
func waitForPane(d time.Duration) { time.Sleep(d) }

// TestInjectScrollEntersCopyModeForBareShell verifies that when mouse_any_flag=0
// (bare shell), InjectScroll enters copy-mode (no raw bytes reach the pane's
// input). This is the safety-critical path: injecting mouse bytes into a bare
// shell would produce garbage on the command line.
func TestInjectScrollEntersCopyModeForBareShell(t *testing.T) {
	tt := newTestTMUX(t)
	// bare sh: mouse_any_flag=0
	pane := tt.newPane(t, "sh")
	ctx := context.Background()

	// Confirm mouse_any_flag is 0 before the call.
	if queryFlag(t, tt, pane, "#{mouse_any_flag}") != "0" {
		t.Fatal("precondition: mouse_any_flag should be 0 for bare sh")
	}

	enteredCopyMode, err := pane.InjectScroll(ctx, -1) // scroll up
	if err != nil {
		t.Fatalf("InjectScroll: %v", err)
	}
	if !enteredCopyMode {
		t.Error("expected enteredCopyMode=true for bare shell scroll-up")
	}

	// Pane must be in copy-mode after the call.
	if queryFlag(t, tt, pane, "#{pane_in_mode}") != "1" {
		t.Error("pane_in_mode should be 1 after entering copy-mode")
	}
}

// TestInjectScrollDoesNotEnterCopyModeWhenAlreadyInIt verifies that when the
// pane is already in copy-mode, InjectScroll scrolls without re-entering
// copy-mode (enteredCopyMode=false — no state transition, no PaneModeChanged).
func TestInjectScrollDoesNotEnterCopyModeWhenAlreadyInIt(t *testing.T) {
	tt := newTestTMUX(t)
	pane := tt.newPane(t, "sh")
	ctx := context.Background()

	// Manually enter copy-mode.
	if _, err := tt.run("copy-mode", "-e", "-t", pane.target); err != nil {
		t.Fatalf("enter copy-mode: %v", err)
	}
	if queryFlag(t, tt, pane, "#{pane_in_mode}") != "1" {
		t.Fatal("precondition: pane_in_mode should be 1")
	}

	enteredCopyMode, err := pane.InjectScroll(ctx, -1)
	if err != nil {
		t.Fatalf("InjectScroll in copy-mode: %v", err)
	}
	if enteredCopyMode {
		t.Error("expected enteredCopyMode=false when pane already in copy-mode")
	}
	// Still in copy-mode.
	if queryFlag(t, tt, pane, "#{pane_in_mode}") != "1" {
		t.Error("pane should still be in copy-mode")
	}
}

// TestInjectScrollMouseTrackingPathFallsToCopyMode verifies that even when
// mouse_any_flag=1 (app has mouse tracking), InjectScroll uses copy-mode.
// Rationale: send-keys -H mouse bytes are silently ineffective (proved by
// experiment — less/vim do not respond); copy-mode is the only reliable path
// for non-alt-screen panes regardless of mouse_any_flag.
func TestInjectScrollMouseTrackingPathFallsToCopyMode(t *testing.T) {
	tt := newTestTMUX(t)
	// vim with set mouse=a: mouse_any_flag=1
	pane := tt.newPane(t, "vim -c 'set mouse=a' /dev/null")
	ctx := context.Background()

	waitForPane(1500 * time.Millisecond) // let vim draw and set mouse mode

	flag := queryFlag(t, tt, pane, "#{mouse_any_flag}")
	if flag != "1" {
		t.Skipf("vim+mouse did not enable mouse tracking (flag=%q); skipping", flag)
	}

	enteredCopyMode, err := pane.InjectScroll(ctx, -1) // scroll up
	if err != nil {
		t.Fatalf("InjectScroll (mouse tracking): %v", err)
	}
	// Unified path: always enters copy-mode.
	if !enteredCopyMode {
		t.Error("expected enteredCopyMode=true (unified copy-mode path regardless of mouse_any_flag)")
	}
	if queryFlag(t, tt, pane, "#{pane_in_mode}") != "1" {
		t.Error("pane_in_mode should be 1 (in copy-mode) on unified path")
	}
}

// TestPaneInMode verifies PaneInMode reports correctly for both states.
func TestPaneInMode(t *testing.T) {
	tt := newTestTMUX(t)
	pane := tt.newPane(t, "sh")
	ctx := context.Background()

	inMode, err := pane.PaneInMode(ctx)
	if err != nil {
		t.Fatalf("PaneInMode (normal): %v", err)
	}
	if inMode {
		t.Error("PaneInMode should be false for normal shell")
	}

	if _, err := tt.run("copy-mode", "-e", "-t", pane.target); err != nil {
		t.Fatalf("enter copy-mode: %v", err)
	}

	inMode, err = pane.PaneInMode(ctx)
	if err != nil {
		t.Fatalf("PaneInMode (copy-mode): %v", err)
	}
	if !inMode {
		t.Error("PaneInMode should be true after entering copy-mode")
	}
}

// TestExitCopyMode verifies ExitCopyMode transitions pane_in_mode from 1 to 0.
func TestExitCopyMode(t *testing.T) {
	tt := newTestTMUX(t)
	pane := tt.newPane(t, "sh")
	ctx := context.Background()

	if _, err := tt.run("copy-mode", "-e", "-t", pane.target); err != nil {
		t.Fatalf("enter copy-mode: %v", err)
	}
	if queryFlag(t, tt, pane, "#{pane_in_mode}") != "1" {
		t.Fatal("precondition: pane_in_mode should be 1")
	}

	if err := pane.ExitCopyMode(ctx); err != nil {
		t.Fatalf("ExitCopyMode: %v", err)
	}

	if queryFlag(t, tt, pane, "#{pane_in_mode}") != "0" {
		t.Error("pane_in_mode should be 0 after ExitCopyMode")
	}
}

// TestInjectScrollDeadPane verifies InjectScroll returns ErrPaneNotFound on a
// dead pane (requirePane fires before any tmux action).
func TestInjectScrollDeadPane(t *testing.T) {
	tt := newTestTMUX(t)
	dead := tt.deadPane(t)
	ctx := context.Background()

	_, err := dead.InjectScroll(ctx, -1)
	if !isErrPaneNotFound(err) {
		t.Errorf("expected ErrPaneNotFound, got %v", err)
	}
}

// isErrPaneNotFound reports whether err is or wraps ErrPaneNotFound.
func isErrPaneNotFound(err error) bool {
	if err == nil {
		return false
	}
	return strings.Contains(err.Error(), ErrPaneNotFound.Error()) ||
		err == ErrPaneNotFound
}
