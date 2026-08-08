package bridge

// Integration tests for the single-pane terminal bridge against a real tmux
// on an isolated socket. Engineering red lines observed here:
//
//   - every server is created inside t.TempDir() with an absolute socket path
//     and torn down by t.Cleanup, so a failing test can never leave a server
//     or socket behind;
//   - the TMUX and TMUX_TMPDIR env vars are stripped from every spawned tmux
//     so a nested tmux can never attach to the caller's real server (the
//     "never kill the real fleet" red line).
//
// All blocking reads carry a timeout so a misbehaving pipe cannot hang a
// test suite.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

var testSeq uint64

// testTMUX is a handle to one isolated tmux server and its scrubbed
// environment.
type testTMUX struct {
	t    *testing.T
	sock string
	env  []string
}

// newTestTMUX starts nothing yet: it owns a unique absolute socket path and
// the environment scrub, and guarantees the server is killed on cleanup.
func newTestTMUX(t *testing.T) *testTMUX {
	t.Helper()
	sock := filepath.Join(t.TempDir(), "sock")
	tt := &testTMUX{t: t, sock: sock, env: scrubbedEnv()}
	t.Cleanup(func() {
		cmd := exec.Command("tmux", "-S", sock, "kill-server")
		cmd.Env = tt.env
		_ = cmd.Run() // best effort: an already-dead server is fine
	})
	return tt
}

// scrubbedEnv returns the process environment with TMUX and TMUX_TMPDIR
// removed so nested tmux commands resolve their own socket only.
func scrubbedEnv() []string {
	out := make([]string, 0, len(os.Environ()))
	for _, kv := range os.Environ() {
		if strings.HasPrefix(kv, "TMUX=") || strings.HasPrefix(kv, "TMUX_TMPDIR=") {
			continue
		}
		out = append(out, kv)
	}
	return out
}

// run executes tmux -S <sock> with the scrubbed environment.
func (tt *testTMUX) run(args ...string) (string, error) {
	tt.t.Helper()
	cmd := exec.Command("tmux", append([]string{"-S", tt.sock}, args...)...)
	cmd.Env = tt.env
	out, err := cmd.CombinedOutput()
	return string(out), err
}

// newPane creates a fresh 80x24 session running cmd (default "cat") and
// returns a Pane bound to its bare pane id.
func (tt *testTMUX) newPane(t *testing.T, cmd string) *Pane {
	t.Helper()
	name := fmt.Sprintf("tb%d", atomic.AddUint64(&testSeq, 1))
	if cmd == "" {
		cmd = "cat"
	}
	if _, err := tt.run("new-session", "-d", "-x", "80", "-y", "24", "-s", name, "-c", t.TempDir(), cmd); err != nil {
		t.Fatalf("new-session %s: %v", name, err)
	}
	out, err := tt.run("list-panes", "-t", name, "-F", "#{pane_id}")
	if err != nil {
		t.Fatalf("resolve pane %s: %v", name, err)
	}
	id := strings.TrimSpace(out)
	if id == "" {
		t.Fatalf("no pane resolved for session %s", name)
	}
	return NewPane(tt.sock, id)
}

// deadPane creates a session whose command exits immediately, so by the time
// we use it the pane is gone.
func (tt *testTMUX) deadPane(t *testing.T) *Pane {
	t.Helper()
	name := fmt.Sprintf("tbdead%d", atomic.AddUint64(&testSeq, 1))
	if _, err := tt.run("new-session", "-d", "-x", "80", "-y", "24", "-s", name, "-c", t.TempDir(), "true"); err != nil {
		t.Fatalf("new-session %s: %v", name, err)
	}
	// The pane dies when its command exits; give tmux a moment to reap it.
	time.Sleep(400 * time.Millisecond)
	return NewPane(tt.sock, "%0")
}

// waitForStream drains ch until it has seen want as a substring or the
// deadline elapses. It is the positive control for "the pipe is actually
// delivering bytes".
func waitForStream(t *testing.T, ch <-chan []byte, want string) bool {
	t.Helper()
	deadline := time.After(5 * time.Second)
	var got bytes.Buffer
	for {
		select {
		case chunk, ok := <-ch:
			if !ok {
				t.Logf("stream closed before seeing %q; got %q", want, got.String())
				return false
			}
			got.Write(chunk)
			if bytes.Contains(got.Bytes(), []byte(want)) {
				return true
			}
		case <-deadline:
			t.Logf("timeout waiting for %q; got %q", want, got.String())
			return false
		}
	}
}

func TestSnapshotContainsPrintedOutput(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, `printf 'SNAP_MARK_12345\n'; sleep 300`)
	time.Sleep(300 * time.Millisecond)

	snap, err := p.Snapshot(context.Background())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if !bytes.Contains(snap, []byte("SNAP_MARK_12345")) {
		t.Errorf("snapshot missing printed mark; got %q", snap)
	}
}

func TestSnapshotPreservesColorEscapes(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, `printf '\033[31mREDMARK\033[0m\n'; sleep 300`)
	time.Sleep(300 * time.Millisecond)

	snap, err := p.Snapshot(context.Background())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if !bytes.Contains(snap, []byte("\x1b[31m")) || !bytes.Contains(snap, []byte("REDMARK")) {
		t.Errorf("snapshot must keep ANSI escapes with -e; got %q", snap)
	}
}

func TestSnapshotDeadPaneIsErrPaneNotFound(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.deadPane(t)

	if _, err := p.Snapshot(context.Background()); !errors.Is(err, ErrPaneNotFound) {
		t.Fatalf("Snapshot on dead pane: want ErrPaneNotFound, got %v", err)
	}
}

func TestDeadSocketIsErrServerUnreachable(t *testing.T) {
	p := NewPane(filepath.Join(t.TempDir(), "no-such-socket"), "%0")

	if _, err := p.Snapshot(context.Background()); !errors.Is(err, ErrServerUnreachable) {
		t.Fatalf("Snapshot on dead socket: want ErrServerUnreachable, got %v", err)
	}
}

func TestInjectEchoAppearsInStream(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	ch, cancel, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer cancel()

	if err := p.Inject(context.Background(), "HELLO_INJECT_42"); err != nil {
		t.Fatalf("Inject: %v", err)
	}
	if !waitForStream(t, ch, "HELLO_INJECT_42") {
		t.Fatal("injected text never appeared in the incremental stream")
	}
}

func TestInjectDeadPaneFails(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.deadPane(t)

	if err := p.Inject(context.Background(), "x"); !errors.Is(err, ErrPaneNotFound) {
		t.Fatalf("Inject on dead pane: want ErrPaneNotFound, got %v", err)
	}
}

func TestInjectMultiline(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	if err := p.Inject(context.Background(), "MULTI_A_1\nMULTI_B_2"); err != nil {
		t.Fatalf("Inject multiline: %v", err)
	}
	time.Sleep(400 * time.Millisecond)

	snap, err := p.Snapshot(context.Background())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if !bytes.Contains(snap, []byte("MULTI_A_1")) || !bytes.Contains(snap, []byte("MULTI_B_2")) {
		t.Errorf("multiline inject did not land in pane; snapshot %q", snap)
	}
}

func TestSubscribeIdempotent(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	c1, cancel1, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("first Subscribe: %v", err)
	}
	c2, cancel2, err := p.Subscribe(context.Background())
	if err != nil {
		t.Fatalf("second Subscribe: %v", err)
	}
	cancel1()
	cancel2()
	_ = c1
	_ = c2
}

func TestResizeChangesActualSize(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "cat")

	w, h, err := p.Resize(context.Background(), 120, 30)
	if err != nil {
		t.Fatalf("Resize: %v", err)
	}
	if w != 120 || h != 30 {
		t.Errorf("Resize read-back: want 120x30, got %dx%d", w, h)
	}
}

func TestScrollbackPagingRange(t *testing.T) {
	tt := newTestTMUX(t)
	name := fmt.Sprintf("tbscbk%d", atomic.AddUint64(&testSeq, 1))
	if _, err := tt.run("new-session", "-d", "-x", "40", "-y", "10", "-s", name, "-c", t.TempDir(), "bash"); err != nil {
		t.Fatalf("new-session: %v", err)
	}
	out, err := tt.run("list-panes", "-t", name, "-F", "#{pane_id}")
	if err != nil {
		t.Fatalf("resolve pane: %v", err)
	}
	p := NewPane(tt.sock, strings.TrimSpace(out))

	// Push 60 lines so history far exceeds the 10-row screen.
	if err := p.Inject(context.Background(), `for i in $(seq 1 60); do echo "SCBK_$i"; done`); err != nil {
		t.Fatalf("Inject loop: %v", err)
	}
	// Wait until the tail line is on screen before paging history.
	deadline := time.Now().Add(5 * time.Second)
	for !bytes.Contains(mustSnapshot(t, p), []byte("SCBK_60")) {
		if time.Now().After(deadline) {
			t.Fatal("loop output never reached the screen")
		}
		time.Sleep(100 * time.Millisecond)
	}

	page, err := p.Scrollback(context.Background(), -30, -21)
	if err != nil {
		t.Fatalf("Scrollback: %v", err)
	}
	lines := strings.Split(strings.TrimRight(string(page), "\n"), "\n")
	if len(lines) != 10 {
		t.Errorf("page -30..-21 should be exactly 10 lines, got %d: %q", len(lines), page)
	}
	if !bytes.Contains(page, []byte("SCBK_22")) {
		t.Errorf("page should contain SCBK_22 (older history), got %q", page)
	}
	if bytes.Contains(page, []byte("SCBK_60")) {
		t.Errorf("page should NOT contain on-screen tail SCBK_60, got %q", page)
	}
}

// mustSnapshot snapshots a pane and fails the test on error (helper for
// polling loops).
func mustSnapshot(t *testing.T, p *Pane) []byte {
	t.Helper()
	snap, err := p.Snapshot(context.Background())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	return snap
}

func TestRunTmuxTimeout(t *testing.T) {
	dir := t.TempDir()
	script := filepath.Join(dir, "slow-tmux")
	if err := os.WriteFile(script, []byte("#!/bin/sh\nsleep 5\n"), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()

	_, err := runTmux(context.Background(), "whatever", 100*time.Millisecond, "list-sessions")
	if !errors.Is(err, ErrTmuxTimeout) {
		t.Fatalf("runTmux with deadline: want ErrTmuxTimeout, got %v", err)
	}
}

func TestClassifyTmuxErrors(t *testing.T) {
	cases := []struct {
		stderr string
		want   error
	}{
		{"can't find pane: %0", ErrPaneNotFound},
		{"can't find session: foo", ErrPaneNotFound},
		{"can't find window: 0", ErrPaneNotFound},
		{"no server running on /x/sock", ErrServerUnreachable},
		{"error connecting to /x/sock (No such file or directory)", ErrServerUnreachable},
		{"error connecting to /x/sock (Connection refused)", ErrServerUnreachable},
	}
	for _, c := range cases {
		got := classifyTmuxError(c.stderr)
		if !errors.Is(got, c.want) {
			t.Errorf("classify(%q) = %v, want %v", c.stderr, got, c.want)
		}
	}
}
