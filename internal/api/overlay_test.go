package api

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/overlay"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// countingOverlay is a test capturer: Start/Stop toggle the client count,
// Snapshot increments a capture counter and returns a changing frame.
type countingOverlay struct {
	captures atomic.Int64
	clients  atomic.Int64
	n        atomic.Int64
}

func (c *countingOverlay) Start(_ context.Context, _ string) error {
	c.clients.Store(1)
	return nil
}

func (c *countingOverlay) Snapshot(context.Context) ([]byte, error) {
	n := c.n.Add(1)
	c.captures.Add(1)
	return []byte(fmt.Sprintf("choose-tree frame %d ├─ ovp", n)), nil
}

func (c *countingOverlay) Stop() { c.clients.Store(0) }

func (c *countingOverlay) CaptureCount() int64 { return c.captures.Load() }
func (c *countingOverlay) ClientCount() int64  { return c.clients.Load() }

func overlayTestOpts(cap *countingOverlay) Options {
	return Options{
		Token:           "test-token",
		Discoverer:      &mutableDiscoverer{model: &discovery.Model{}},
		ListInterval:    time.Hour,
		OverlayInterval: 20 * time.Millisecond,
		OverlayCapturer: cap,
	}
}

func TestOverlayNoResourceWithoutSubscriber(t *testing.T) {
	cap := &countingOverlay{}
	e := startWS(t, overlayTestOpts(cap))
	e.auth()

	time.Sleep(120 * time.Millisecond)
	gotCap, gotCli := cap.CaptureCount(), cap.ClientCount()
	if gotCap != 0 || gotCli != 0 {
		t.Fatalf("no subscriber: captures=%d clients=%d → want 0/0 (idle gate broken)", gotCap, gotCli)
	}

	e.sendFrame(protocol.OverlaySubscribe{Socket: "test-sock"})
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && cap.ClientCount() == 0 {
		time.Sleep(10 * time.Millisecond)
	}
	if cap.ClientCount() == 0 {
		t.Fatal("subscribe did not start a capture client")
	}

	e.sendFrame(protocol.OverlayUnsubscribe{})
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && cap.ClientCount() != 0 {
		time.Sleep(10 * time.Millisecond)
	}
	after := cap.CaptureCount()
	time.Sleep(80 * time.Millisecond)
	if cap.ClientCount() != 0 {
		t.Fatalf("after unsubscribe clients=%d, want 0", cap.ClientCount())
	}
	if got := cap.CaptureCount(); got != after {
		t.Fatalf("captures grew after unsubscribe: %d -> %d", after, got)
	}
}

func waitOverlayFrame(t *testing.T, e *wsEnv, d time.Duration) protocol.OverlayFrame {
	t.Helper()
	got := waitTyped(t, e, time.Now().Add(d), func(typed protocol.Typed) bool {
		_, ok := typed.(protocol.OverlayFrame)
		return ok
	})
	return got.(protocol.OverlayFrame)
}

func TestOverlayFramesAreNonEmpty(t *testing.T) {
	cap := &countingOverlay{}
	e := startWS(t, overlayTestOpts(cap))
	e.auth()
	e.sendFrame(protocol.OverlaySubscribe{Socket: "test-sock"})
	got := waitOverlayFrame(t, e, 3*time.Second)
	if got.Text == "" || len([]rune(got.Text)) == 0 {
		t.Fatalf("overlay_frame text empty: %+v", got)
	}
	if got.Seq < 1 {
		t.Fatalf("overlay_frame seq=%d, want >= 1", got.Seq)
	}
}

func TestOverlayFramesChangeOverTime(t *testing.T) {
	cap := &countingOverlay{}
	e := startWS(t, overlayTestOpts(cap))
	e.auth()
	e.sendFrame(protocol.OverlaySubscribe{Socket: "test-sock"})
	first := waitOverlayFrame(t, e, 3*time.Second)
	deadline := time.Now().Add(3 * time.Second)
	var second protocol.OverlayFrame
	found := false
	for time.Now().Before(deadline) {
		got := waitTyped(t, e, deadline, func(typed protocol.Typed) bool {
			f, ok := typed.(protocol.OverlayFrame)
			return ok && f.Text != first.Text
		})
		second = got.(protocol.OverlayFrame)
		found = true
		break
	}
	if !found {
		t.Fatal("no second overlay_frame with different text (static screenshot?)")
	}
	if first.Text == second.Text {
		t.Fatalf("two frames identical: %q", first.Text)
	}
}

// TestOverlayLiveFirstFrameWithinProbeWindow is the end-to-end gate the
// cross-client probe enforces: real tmux capturer, first overlay_frame in
// 6s, then a different second frame. Fake capturers cannot catch PTY stall.
func TestOverlayLiveFirstFrameWithinProbeWindow(t *testing.T) {
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not in PATH")
	}
	dir := "/tmp/ov-dev-server-api"
	sock := dir + "/sock"
	_ = os.RemoveAll(dir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cmd := exec.Command("tmux", "-S", sock, "kill-server")
		cmd.Env = isolatedTmuxEnv()
		_ = cmd.Run()
		_ = os.RemoveAll(dir)
	})

	newSess := exec.Command("tmux", "-S", sock, "new-session", "-d", "-s", "ovp", "-n", "p0", "sleep", "3600")
	newSess.Env = isolatedTmuxEnv()
	if out, err := newSess.CombinedOutput(); err != nil {
		t.Fatalf("new-session: %v %s", err, out)
	}
	list := exec.Command("tmux", "-S", sock, "list-sessions", "-F", "#{session_name}")
	list.Env = isolatedTmuxEnv()
	got, err := list.CombinedOutput()
	if err != nil {
		t.Fatalf("list-sessions: %v %s", err, got)
	}
	if !strings.Contains(string(got), "ovp") {
		t.Fatalf("自检失败：会话不在隔离 socket（got=%q）", got)
	}

	cap := overlay.NewTmux(nil, []string{dir})
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      &mutableDiscoverer{model: &discovery.Model{}},
		ListInterval:    time.Hour,
		OverlayInterval: 100 * time.Millisecond,
		OverlayCapturer: cap,
	})
	e.auth()
	t0 := time.Now()
	e.sendFrame(protocol.OverlaySubscribe{Socket: sock})
	first := waitOverlayFrame(t, e, 6*time.Second)
	firstDur := time.Since(t0)
	if strings.TrimSpace(first.Text) == "" {
		t.Fatalf("first overlay_frame empty after %s: %+v", firstDur, first)
	}
	if firstDur > 6*time.Second {
		t.Fatalf("first overlay_frame at %s, probe window is 6s", firstDur)
	}
	deadline := time.Now().Add(5 * time.Second)
	second := waitTyped(t, e, deadline, func(typed protocol.Typed) bool {
		f, ok := typed.(protocol.OverlayFrame)
		return ok && f.Text != first.Text
	}).(protocol.OverlayFrame)
	if first.Text == second.Text {
		t.Fatalf("two frames identical: %q", first.Text)
	}
}

func TestOverlayHonorsRequestedSocket(t *testing.T) {
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not in PATH")
	}
	root := "/tmp/ov2-dev-server"
	dirA, dirB := root+"/a", root+"/b"
	sockA, sockB := dirA+"/sock", dirB+"/sock"
	_ = os.RemoveAll(root)
	if err := os.MkdirAll(dirA, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dirB, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		killIsolatedTmux(sockA)
		killIsolatedTmux(sockB)
		_ = os.RemoveAll(root)
	})
	startIsolatedSession(t, sockA, "sess-aaa", "cli-a")
	startIsolatedSession(t, sockB, "sess-bbb", "cli-b")

	cap := overlay.NewTmux(nil, []string{dirA, dirB})
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      &mutableDiscoverer{model: &discovery.Model{}},
		ListInterval:    time.Hour,
		OverlayInterval: 100 * time.Millisecond,
		OverlayCapturer: cap,
	})
	e.auth()

	e.sendFrame(protocol.OverlaySubscribe{Socket: sockA})
	fa := waitOverlayFrame(t, e, 6*time.Second)
	if !strings.Contains(fa.Text, "sess-aaa") {
		t.Fatalf("socket A frame missing sess-aaa (requested=%s):\n%s", sockA, fa.Text)
	}
	if strings.Contains(fa.Text, "sess-bbb") {
		t.Fatalf("socket A frame leaked sess-bbb (first-found bug? requested=%s):\n%s", sockA, fa.Text)
	}

	e.sendFrame(protocol.OverlayUnsubscribe{})
	e.sendFrame(protocol.OverlaySubscribe{Socket: sockB})
	deadline := time.Now().Add(6 * time.Second)
	fb := waitTyped(t, e, deadline, func(typed protocol.Typed) bool {
		f, ok := typed.(protocol.OverlayFrame)
		return ok && strings.Contains(f.Text, "sess-bbb")
	}).(protocol.OverlayFrame)
	if !strings.Contains(fb.Text, "sess-bbb") {
		t.Fatalf("socket B frame missing sess-bbb (requested=%s):\n%s", sockB, fb.Text)
	}
	if strings.Contains(fb.Text, "sess-aaa") {
		t.Fatalf("socket B frame leaked sess-aaa (stuck on first socket? requested=%s):\n%s", sockB, fb.Text)
	}
}

func TestOverlayExcludesScratchSession(t *testing.T) {
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not in PATH")
	}
	dir := "/tmp/ov2-dev-server-excl"
	sock := dir + "/sock"
	_ = os.RemoveAll(dir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		killIsolatedTmux(sock)
		_ = os.RemoveAll(dir)
	})
	startIsolatedSession(t, sock, "sess-user", "cli")

	cap := overlay.NewTmux(nil, []string{dir})
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      &mutableDiscoverer{model: &discovery.Model{}},
		ListInterval:    time.Hour,
		OverlayInterval: 100 * time.Millisecond,
		OverlayCapturer: cap,
	})
	e.auth()
	e.sendFrame(protocol.OverlaySubscribe{Socket: sock})
	got := waitOverlayFrame(t, e, 6*time.Second)
	if !strings.Contains(got.Text, "sess-user") {
		t.Fatalf("frame missing user session sess-user:\n%s", got.Text)
	}
	for _, tok := range []string{overlay.ScratchSession, "ov-spin", "tree*", "sleep*"} {
		if strings.Contains(got.Text, tok) {
			t.Fatalf("frame contains observer token %q (self-reflection):\n%s", tok, got.Text)
		}
	}
}

func startIsolatedSession(t *testing.T, sock, session, window string) {
	t.Helper()
	cmd := exec.Command("tmux", "-S", sock, "new-session", "-d", "-s", session, "-n", window, "tail", "-f", "/dev/null")
	cmd.Env = isolatedTmuxEnv()
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("new-session %s: %v %s", sock, err, out)
	}
	list := exec.Command("tmux", "-S", sock, "list-sessions", "-F", "#{session_name}")
	list.Env = isolatedTmuxEnv()
	got, err := list.CombinedOutput()
	if err != nil {
		t.Fatalf("list-sessions %s: %v %s", sock, err, got)
	}
	if !strings.Contains(string(got), session) {
		t.Fatalf("自检失败：会话 %s 不在隔离 socket %s（got=%q）", session, sock, got)
	}
}

func killIsolatedTmux(sock string) {
	cmd := exec.Command("tmux", "-S", sock, "kill-server")
	cmd.Env = isolatedTmuxEnv()
	_ = cmd.Run()
}

func isolatedTmuxEnv() []string {
	out := []string{"TERM=xterm-256color"}
	for _, e := range os.Environ() {
		if strings.HasPrefix(e, "TMUX=") || strings.HasPrefix(e, "TMUX_TMPDIR=") || strings.HasPrefix(e, "TERM=") {
			continue
		}
		out = append(out, e)
	}
	return out
}
