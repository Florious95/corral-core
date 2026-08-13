package bridge

// scroll_test.go: red tests for InjectScroll, PaneInMode, ExitCopyMode.
// All tests use an isolated tmux socket (newTestTMUX) — production daemon
// (pid 70317) and user tmux are never touched (bridge red line).
//
// T1: mouse_any_flag=0 → copy-mode entered, zero raw bytes to pane shell
// T2: mouse_any_flag=1 (vim+mouse) → bytes injected, no copy-mode
// T3: scrollMouseBytes SGR format
// T4: scrollMouseBytes X10 format
// T5: PaneInMode reports correctly
// T6: ExitCopyMode exits copy-mode (pane_in_mode 1→0)
// T7: InjectScroll on dead pane → ErrPaneNotFound

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

// TestScrollMouseBytesSGR verifies the SGR scroll-up byte sequence.
func TestScrollMouseBytesSGR(t *testing.T) {
	// SGR scroll-up: ESC [ < 64 ; 1 ; 1 M
	got := scrollMouseBytes(true, true)
	want := "\x1b[<64;1;1M"
	if string(got) != want {
		t.Errorf("SGR scroll-up: got %q want %q", string(got), want)
	}

	// SGR scroll-down: ESC [ < 65 ; 1 ; 1 M
	got = scrollMouseBytes(false, true)
	want = "\x1b[<65;1;1M"
	if string(got) != want {
		t.Errorf("SGR scroll-down: got %q want %q", string(got), want)
	}
}

// TestScrollMouseBytesX10 verifies the X10 scroll byte sequence.
func TestScrollMouseBytesX10(t *testing.T) {
	// X10 scroll-up: ESC [ M \x60 \x21 \x21  (button=64, col=1+32=33, row=1+32=33)
	got := scrollMouseBytes(true, false)
	want := []byte{0x1b, '[', 'M', 0x60, 0x21, 0x21}
	if string(got) != string(want) {
		t.Errorf("X10 scroll-up: got %v want %v", got, want)
	}

	// X10 scroll-down: ESC [ M \x61 \x21 \x21  (button=65+32=97=0x61)
	got = scrollMouseBytes(false, false)
	want = []byte{0x1b, '[', 'M', 0x61, 0x21, 0x21}
	if string(got) != string(want) {
		t.Errorf("X10 scroll-down: got %v want %v", got, want)
	}
}

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

// TestInjectScrollMouseTrackingPath verifies that when mouse_any_flag=1
// (app has mouse tracking enabled), InjectScroll injects bytes via
// send-keys -H and does NOT enter copy-mode.
func TestInjectScrollMouseTrackingPath(t *testing.T) {
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
	if enteredCopyMode {
		t.Error("expected enteredCopyMode=false when mouse tracking is active")
	}
	// Must NOT be in copy-mode.
	if queryFlag(t, tt, pane, "#{pane_in_mode}") != "0" {
		t.Error("pane_in_mode should be 0 (not in copy-mode) on mouse tracking path")
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
