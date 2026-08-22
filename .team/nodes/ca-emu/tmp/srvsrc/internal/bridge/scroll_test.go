package bridge

// scroll_test.go: red tests for InjectScroll, PaneInMode, ExitCopyMode.
// All tests use an isolated tmux socket (newTestTMUX) — production daemon
// and user tmux are never touched (bridge red line).
//
// T1: bare shell (mouse_any_flag=0) → copy-mode entered, zero raw bytes sent
// T2: vim+mouse (mouse_any_flag=1) → raw SGR wheel bytes sent, copy-mode
//     never entered (see feat-remote-scroll-mouse-wheel probe: the earlier
//     "send-keys -H is ineffective" verdict was measured against less/vim
//     only and never re-tested against Claude Code; re-run against a real
//     `claude` pane produced a visible scroll + "Jump to bottom" indicator)
// T3: fake-tmux argv pin — mouse_any_flag=1 dispatches the exact SGR hex
//     bytes, not just "some send-keys call"
// T4: mutation self-check — hardcoding the branch to always report
//     mouse_any_flag=1 must turn the mouse_any_flag=0 contamination test red
// T5: PaneInMode reports correctly
// T6: ExitCopyMode exits copy-mode (pane_in_mode 1→0)
// T7: InjectScroll on dead pane → ErrPaneNotFound
// T8: already-in-copy-mode → scroll only (enteredCopyMode=false)

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
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

// TestInjectScrollMouseTrackingNeverEntersCopyMode verifies that when
// mouse_any_flag=1 (app has mouse tracking, e.g. vim with mouse=a or Claude
// Code), InjectScroll takes the raw-byte path and never enters copy-mode.
// This is the real-pane behavioral half of the mouse_any_flag=1 assertion;
// TestInjectScrollArgvExactShapeMouseTrackingOn (below) pins the exact SGR
// hex bytes dispatched, per leader's requirement to assert the constructed
// tmux args, not just that "some send-keys call" happened.
//
// vim itself does not visibly react to the injected bytes (see the doc
// comment on InjectScroll: the original "ineffective" experiment measured
// exactly this — less/vim do not respond to synthesised SGR bytes). That is
// unrelated to what this test checks: whether *our code* still reaches for
// copy-mode when mouse tracking is on. It must not, regardless of whether
// the target app acts on the bytes.
func TestInjectScrollMouseTrackingNeverEntersCopyMode(t *testing.T) {
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
		t.Error("expected enteredCopyMode=false: mouse_any_flag=1 must take the raw-byte path, not copy-mode")
	}
	if queryFlag(t, tt, pane, "#{pane_in_mode}") != "0" {
		t.Error("pane_in_mode should stay 0: copy-mode must never be entered when mouse_any_flag=1")
	}
}

// fakeTmuxScrollScript writes a fake tmux that answers list-panes and
// display-message deterministically (mouseFlag controls #{mouse_any_flag};
// #{pane_in_mode} is always "0", i.e. not yet in copy-mode) and appends every
// copy-mode / send-keys invocation's argv (socket flag stripped) to logPath,
// one line per call — so a test can assert on the exact bytes tmux received,
// not merely that some call happened.
func fakeTmuxScrollScript(t *testing.T, mouseFlag string) (script, logPath string) {
	t.Helper()
	dir := t.TempDir()
	logPath = filepath.Join(dir, "argv.log")
	script = filepath.Join(dir, "fake-tmux")
	body := fmt.Sprintf(`#!/bin/sh
case "$3" in
  list-panes) echo "%%0"; exit 0;;
  display-message)
    case "$*" in
      *mouse_any_flag*) echo "%s"; exit 0;;
      *pane_in_mode*) echo "0"; exit 0;;
      *) exit 1;;
    esac
    ;;
  copy-mode) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`, mouseFlag)
	if err := os.WriteFile(script, []byte(body), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	return script, logPath
}

// sgrWheelHexLine builds the exact space-separated hex byte line InjectScroll
// must dispatch for one SGR-1006 wheel event, computed independently of the
// production fmt.Sprintf call so this test does not just mirror the
// implementation. button is 64 (up) or 65 (down); coordinate is fixed 1;1.
func sgrWheelHexLine(button int) string {
	// "\x1b[<%d;1;1M" — %d is always two ASCII digits (64 or 65).
	chars := []byte{0x1b, '[', '<',
		byte('0' + button/10), byte('0' + button%10),
		';', '1', ';', '1', 'M'}
	hex := make([]string, len(chars))
	for i, c := range chars {
		hex[i] = fmt.Sprintf("%02x", c)
	}
	return strings.Join(hex, " ")
}

// TestInjectScrollArgvExactShapeMouseTrackingOn pins the exact tmux argv for
// the mouse_any_flag=1 path: one send-keys -H call carrying count SGR wheel
// events back to back, and confirms copy-mode is never invoked.
func TestInjectScrollArgvExactShapeMouseTrackingOn(t *testing.T) {
	script, logPath := fakeTmuxScrollScript(t, "1")
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	enteredCopyMode, err := p.InjectScroll(context.Background(), 2) // scroll down x2
	if err != nil {
		t.Fatalf("InjectScroll: %v", err)
	}
	if enteredCopyMode {
		t.Error("expected enteredCopyMode=false on the mouse-tracking path")
	}

	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	one := sgrWheelHexLine(65) // down
	want := "send-keys -H -t %0 " + one + " " + one
	if got := strings.TrimSpace(string(data)); got != want {
		t.Errorf("send-keys argv = %q, want %q", got, want)
	}
}

// TestInjectScrollArgvExactShapeBareShell pins the exact tmux argv for the
// mouse_any_flag=0 path: copy-mode is entered, scrolling goes through
// send-keys -X, and — the anti-contamination assertion — no -H raw-byte call
// is ever issued. This is the regression guard for "裸壳被打进字面量字节".
func TestInjectScrollArgvExactShapeBareShell(t *testing.T) {
	script, logPath := fakeTmuxScrollScript(t, "0")
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	enteredCopyMode, err := p.InjectScroll(context.Background(), -3) // scroll up x3
	if err != nil {
		t.Fatalf("InjectScroll: %v", err)
	}
	if !enteredCopyMode {
		t.Error("expected enteredCopyMode=true on the bare-shell path")
	}

	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	want := []string{"copy-mode -e -t %0", "send-keys -X -N 3 -t %0 scroll-up"}
	if len(lines) != len(want) {
		t.Fatalf("tmux calls = %q, want %q", lines, want)
	}
	for i := range want {
		if lines[i] != want[i] {
			t.Errorf("call %d = %q, want %q", i, lines[i], want[i])
		}
	}
	for _, line := range lines {
		if strings.Contains(line, "-H") {
			t.Errorf("mouse_any_flag=0 must never dispatch a -H raw-byte call, got %q", line)
		}
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
