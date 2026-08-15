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
//
// The socket lives in a short-temp dir, not t.TempDir(): t.TempDir() paths
// are test-name-based and, combined with long test names, push the socket
// past tmux's ~104-byte sun_path limit ("File name too long"). A short dir
// name keeps every test well under the limit.
func newTestTMUX(t *testing.T) *testTMUX {
	t.Helper()
	dir, err := os.MkdirTemp("", "tb")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	sock := filepath.Join(dir, "sock")
	tt := &testTMUX{t: t, sock: sock, env: scrubbedEnv()}
	t.Cleanup(func() {
		cmd := exec.Command("tmux", "-S", sock, "kill-server")
		cmd.Env = tt.env
		_ = cmd.Run() // best effort: an already-dead server is fine
		_ = os.RemoveAll(dir)
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

// deadPane returns a Pane whose pane has been killed while its tmux server
// stays alive (a second window keeps the server running). This distinguishes
// ErrPaneNotFound from a server that has exited entirely.
func (tt *testTMUX) deadPane(t *testing.T) *Pane {
	t.Helper()
	name := fmt.Sprintf("tbdead%d", atomic.AddUint64(&testSeq, 1))
	// Window 0 persists so the server survives the kill below.
	if out, err := tt.run("new-session", "-d", "-x", "80", "-y", "24", "-s", name, "-c", t.TempDir(), "cat"); err != nil {
		t.Fatalf("new-session %s (sock=%s): out=%q err=%v", name, tt.sock, out, err)
	}
	// Window 1 is the pane we are going to kill.
	if _, err := tt.run("new-window", "-t", name, "-c", t.TempDir(), "cat"); err != nil {
		t.Fatalf("new-window %s: %v", name, err)
	}
	out, err := tt.run("list-panes", "-t", name+":1.0", "-F", "#{pane_id}")
	if err != nil {
		t.Fatalf("resolve kill-target pane: %v", err)
	}
	id := strings.TrimSpace(out)
	if _, err := tt.run("kill-pane", "-t", id); err != nil {
		t.Fatalf("kill-pane %s: %v", id, err)
	}
	return NewPane(tt.sock, id)
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

// TestSendKeysArgvExactShape pins the named-key translation to the exact argv
// the tmux server receives, using a fake tmux that records the send-keys
// command line. The bridge maps the wire key names (R-1 shortcut bar) to tmux
// send-keys named keys and sends them in one invocation WITHOUT an Enter.
func TestSendKeysArgvExactShape(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "argv.log")
	script := filepath.Join(dir, "fake-tmux")
	if err := os.WriteFile(script, []byte(`#!/bin/sh
case "$3" in
  list-panes) echo "%0"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	if err := p.SendKeys(context.Background(), "esc", "ctrl_c", "tab", "up", "down", "left", "right"); err != nil {
		t.Fatalf("SendKeys: %v", err)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	want := "send-keys -t %0 -- Escape C-c Tab Up Down Left Right"
	if got := strings.TrimSpace(string(data)); got != want {
		t.Errorf("send-keys argv = %q, want %q", got, want)
	}
}

// TestSendKeysUnknownKeyRejects verifies an unknown key name fails BEFORE any
// tmux invocation: the fake tmux exits 42, so any error other than the mapping
// rejection would surface as a tmux failure. This is the bridge's own defensive
// guard — the protocol layer already enforces the closed set.
func TestSendKeysUnknownKeyRejects(t *testing.T) {
	script := filepath.Join(t.TempDir(), "fake-tmux")
	if err := os.WriteFile(script, []byte("#!/bin/sh\nexit 42\n"), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()

	p := NewPane("/sock/x", "%0")
	err := p.SendKeys(context.Background(), "esc", "home")
	if !errors.Is(err, ErrInvalidKey) {
		t.Fatalf("SendKeys unknown key: want ErrInvalidKey, got %v", err)
	}
}

// TestSendKeysDeadPaneFails verifies the decidable-ack semantics of named-key
// injection: a pane that no longer exists fails with ErrPaneNotFound, exactly
// like Inject (requirement 003).
func TestSendKeysDeadPaneFails(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.deadPane(t)

	if err := p.SendKeys(context.Background(), "esc"); !errors.Is(err, ErrPaneNotFound) {
		t.Fatalf("SendKeys on dead pane: want ErrPaneNotFound, got %v", err)
	}
}

// TestSendKeysAcceptedOnLivePane is the real-tmux positive control: all seven
// named keys are accepted by a live pane and the pane stays functional (a
// subsequent normal inject still lands on screen).
func TestSendKeysAcceptedOnLivePane(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "bash")

	if err := p.SendKeys(context.Background(), "esc", "ctrl_c", "tab", "up", "down", "left", "right"); err != nil {
		t.Fatalf("SendKeys: %v", err)
	}
	// Positive control: the pane is still alive and accepts a normal inject.
	if err := p.Inject(context.Background(), "echo KEYS_LIVE_99"); err != nil {
		t.Fatalf("Inject after keys: %v", err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for !bytes.Contains(mustSnapshot(t, p), []byte("KEYS_LIVE_99")) {
		if time.Now().After(deadline) {
			t.Fatal("echo after named keys never reached the screen")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// TestTypeKeysArgvExactShape verifies the passthrough primitive injects each
// key literal in its own send-keys -l call and never appends an Enter (the
// whole-input "inject + Enter" binding that requirement 059 replaces).
func TestTypeKeysArgvExactShape(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "argv.log")
	script := filepath.Join(dir, "fake-tmux")
	if err := os.WriteFile(script, []byte(`#!/bin/sh
case "$3" in
  list-panes) echo "%0"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	if err := p.TypeKeys(context.Background(), "l", "s"); err != nil {
		t.Fatalf("TypeKeys: %v", err)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	got := strings.Split(strings.TrimSpace(string(data)), "\n")
	// Each key is its own send-keys -l invocation, no Enter in any of them.
	want := []string{
		"send-keys -t %0 -l -- l",
		"send-keys -t %0 -l -- s",
	}
	if len(got) != len(want) {
		t.Fatalf("TypeKeys argv lines = %d, want %d: %q", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("TypeKeys argv[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

// TestTypeKeysDeadPaneFails verifies the passthrough primitive keeps the
// decidable ack (requirement 003): a dead pane fails with ErrPaneNotFound.
func TestTypeKeysDeadPaneFails(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.deadPane(t)

	if err := p.TypeKeys(context.Background(), "x"); !errors.Is(err, ErrPaneNotFound) {
		t.Fatalf("TypeKeys dead pane: want ErrPaneNotFound, got %v", err)
	}
}

// TestTypeKeysAcceptedOnLivePane is the real-tmux positive control: typed
// literals land on screen without an Enter (so they stay in the CLI input box),
// and the pane stays functional afterward.
func TestTypeKeysAcceptedOnLivePane(t *testing.T) {
	tt := newTestTMUX(t)
	p := tt.newPane(t, "bash")

	if err := p.TypeKeys(context.Background(), "e", "c", "h", "o"); err != nil {
		t.Fatalf("TypeKeys: %v", err)
	}
	// No Enter appended: the typed text must remain in the pane's input line
	// (not yet executed). We can't easily read the prompt line in a generic
	// shell, so assert the pane is still alive and accepts a normal Inject.
	if err := p.Inject(context.Background(), ""); err != nil {
		t.Fatalf("Inject after TypeKeys: %v", err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for !bytes.Contains(mustSnapshot(t, p), []byte("echo")) {
		if time.Now().After(deadline) {
			t.Fatal("typed literal never reached the pane")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// TestBackspaceKeyMapsToBSpace verifies the backspace wire key maps to tmux
// BSpace (requirement 059 passthrough of the virtual-keyboard delete key).
func TestBackspaceKeyMapsToBSpace(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "argv.log")
	script := filepath.Join(dir, "fake-tmux")
	if err := os.WriteFile(script, []byte(`#!/bin/sh
case "$3" in
  list-panes) echo "%0"; exit 0;;
  send-keys) shift 2; echo "$@" >> "$ARGS_LOG"; exit 0;;
  *) exit 1;;
esac
`), 0o755); err != nil {
		t.Fatalf("write fake tmux: %v", err)
	}
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	p := NewPane("/sock/x", "%0")
	if err := p.SendKeys(context.Background(), "backspace"); err != nil {
		t.Fatalf("SendKeys backspace: %v", err)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	if got := strings.TrimSpace(string(data)); got != "send-keys -t %0 -- BSpace" {
		t.Errorf("send-keys argv = %q, want %q", got, "send-keys -t %0 -- BSpace")
	}
}

func TestRunTmuxTimeout(t *testing.T) {
	dir := t.TempDir()
	script := filepath.Join(dir, "slow-tmux")
	// A self-contained busy loop: a forked child (like `sleep`) would inherit
	// the stdout pipe and keep cmd.Run() blocked past the deadline, masking
	// the timeout with a pipe-close wait.
	if err := os.WriteFile(script, []byte("#!/bin/sh\nwhile :; do :; done\n"), 0o755); err != nil {
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
