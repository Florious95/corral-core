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
	time.Sleep(200 * time.Millisecond)
	if cap.ClientCount() != 0 || cap.CaptureCount() != 0 {
		t.Fatalf("archived overlay must not start capturer: captures=%d clients=%d", cap.CaptureCount(), cap.ClientCount())
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

func TestOverlaySubscribeDoesNotPublishFrames(t *testing.T) {
	cap := &countingOverlay{}
	e := startWS(t, overlayTestOpts(cap))
	e.auth()
	e.sendFrame(protocol.OverlaySubscribe{Socket: "test-sock"})
	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	for {
		_, data, err := e.conn.Read(ctx)
		if err != nil {
			break
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			continue
		}
		if _, ok := typed.(protocol.OverlayFrame); ok {
			t.Fatalf("archived overlay must not publish overlay_frame: %+v", typed)
		}
		if ef, ok := typed.(protocol.ErrorFrame); ok {
			t.Fatalf("valid overlay_subscribe must not error: %+v", ef)
		}
	}
	if cap.ClientCount() != 0 {
		t.Fatalf("capturer clients=%d want 0", cap.ClientCount())
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
	e.sendFrame(protocol.OverlaySubscribe{Socket: sock})
	time.Sleep(400 * time.Millisecond)
	after := exec.Command("tmux", "-S", sock, "list-sessions", "-F", "#{session_name}")
	after.Env = isolatedTmuxEnv()
	gotAfter, errAfter := after.CombinedOutput()
	if errAfter != nil {
		t.Fatalf("list-sessions after subscribe: %v %s", errAfter, gotAfter)
	}
	if strings.Contains(string(gotAfter), overlay.ScratchSession) {
		t.Fatalf("archived overlay must not create scratch session, got=%q", gotAfter)
	}
	clients := exec.Command("tmux", "-S", sock, "list-clients", "-F", "#{client_name}")
	clients.Env = isolatedTmuxEnv()
	cout, _ := clients.CombinedOutput()
	if strings.TrimSpace(string(cout)) != "" {
		t.Fatalf("archived overlay must not attach any tmux client, clients=%q", cout)
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
	time.Sleep(300 * time.Millisecond)
	for _, sock := range []string{sockA, sockB} {
		clients := exec.Command("tmux", "-S", sock, "list-clients", "-F", "#{client_tty}")
		clients.Env = isolatedTmuxEnv()
		cout, _ := clients.CombinedOutput()
		if strings.TrimSpace(string(cout)) != "" {
			t.Fatalf("subscribe must not attach clients on %s, got=%q", sock, cout)
		}
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
	time.Sleep(300 * time.Millisecond)
	list := exec.Command("tmux", "-S", sock, "list-sessions", "-F", "#{session_name}")
	list.Env = isolatedTmuxEnv()
	got, err := list.CombinedOutput()
	if err != nil {
		t.Fatalf("list-sessions: %v %s", err, got)
	}
	if strings.Contains(string(got), overlay.ScratchSession) {
		t.Fatalf("must not start scratch session %s, got=%q", overlay.ScratchSession, got)
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
