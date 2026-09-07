package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// TestCandidateDaemonNodeprobeGSAFE exercises the compiled daemon, its
// production nodeprobe Runner, and a private tmux socket. The shell workflow
// supplies the isolated wrappers and exact N binary; no host socket or pane
// body is read.
func TestCandidateDaemonNodeprobeGSAFE(t *testing.T) {
	if os.Getenv("AGENTMIRROR_GSAFE_DAEMON") != "1" {
		t.Skip("candidate daemon G-SAFE is enabled only by the hosted workflow")
	}
	bin := requiredEnv(t, "AGENTMIRROR_DAEMON_BIN")
	socket := requiredEnv(t, "AGENTMIRROR_GSAFE_SOCKET")
	addr := requiredEnv(t, "AGENTMIRROR_GSAFE_ADDR")
	url := requiredEnv(t, "AGENTMIRROR_GSAFE_URL")
	token := requiredEnv(t, "AGENTMIRROR_GSAFE_TOKEN")
	realTmux := requiredEnv(t, "AGENTMIRROR_GSAFE_TMUX_REAL")
	root := t.TempDir()
	state := filepath.Join(root, "state")
	uploads := filepath.Join(root, "uploads")
	if err := os.MkdirAll(filepath.Dir(socket), 0o700); err != nil {
		t.Fatal(err)
	}
	fixture := exec.Command(realTmux, "-S", socket, "-f", "/dev/null", "new-session", "-d", "-s", "gsafe", "-n", "main", "-c", root, "sleep", "60")
	fixture.Env = append(os.Environ(), "TMUX=")
	if out, err := fixture.CombinedOutput(); err != nil {
		t.Fatalf("private tmux fixture: %v: %s", err, strings.TrimSpace(string(out)))
	}
	t.Cleanup(func() {
		_ = exec.Command(realTmux, "-S", socket, "-f", "/dev/null", "kill-server").Run()
		_ = os.Remove(socket)
	})

	if out, err := exec.Command(realTmux, "-S", socket, "list-sessions", "-F", "#{socket_path}").Output(); err != nil || strings.TrimSpace(string(out)) != socket {
		t.Fatalf("private socket self-check failed: %v", err)
	}

	child := exec.Command(bin, "-listen", addr, "-token", token, "-state-dir", state, "-upload-dir", uploads)
	child.Stdout = nilWriter{}
	child.Stderr = nilWriter{}
	child.Env = append(os.Environ(), "TMUX=")
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	stopped := false
	t.Cleanup(func() {
		if !stopped && child.Process != nil {
			_ = child.Process.Signal(os.Interrupt)
			_ = child.Process.Kill()
			_ = child.Wait()
		}
	})

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	var conn *websocket.Conn
	var err error
	for ctx.Err() == nil {
		conn, _, err = websocket.Dial(ctx, url, nil)
		if err == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("daemon websocket: %v", err)
	}
	defer conn.CloseNow()
	writeFrame(t, conn, &protocol.Auth{Token: token})
	if got := readFrame(t, conn); got.FrameType() != protocol.TypeAuthAck || !got.(protocol.AuthAck).OK {
		t.Fatalf("auth=%+v", got)
	}
	writeFrame(t, conn, &protocol.List{ReqID: 1})
	var listing protocol.Listing
	for {
		got := readFrame(t, conn)
		if got.FrameType() == protocol.TypeListing {
			listing = got.(protocol.Listing)
			break
		}
	}
	if len(listing.Workspaces) != 1 || len(listing.Workspaces[0].Sessions) != 1 {
		t.Fatalf("listing workspace/session count=%d/%d: %+v", len(listing.Workspaces), len(listing.Workspaces[0].Sessions), listing)
	}
	sess := listing.Workspaces[0].Sessions[0]
	if sess.Ref == "" || sess.Provider != "codex" || sess.Activity != "idle" || sess.Status != sess.Activity {
		t.Fatalf("listing session=%+v", sess)
	}
	t.Logf("daemon_listing workspace_count=%d session_count=%d ref_present=%t provider=%s activity=%s", len(listing.Workspaces), len(listing.Workspaces[0].Sessions), sess.Ref != "", sess.Provider, sess.Activity)
	if child.Process != nil {
		_ = child.Process.Signal(os.Interrupt)
	}
	wait := make(chan error, 1)
	go func() { wait <- child.Wait() }()
	select {
	case err := <-wait:
		if err != nil {
			t.Fatalf("daemon shutdown: %v", err)
		}
		stopped = true
	case <-time.After(5 * time.Second):
		t.Fatal("daemon did not cleanly stop")
	}
}

type nilWriter struct{}

func (nilWriter) Write(p []byte) (int, error) { return len(p), nil }

func requiredEnv(t *testing.T, key string) string {
	t.Helper()
	value := os.Getenv(key)
	if value == "" {
		t.Fatalf("missing %s", key)
	}
	return value
}

func writeFrame(t *testing.T, conn *websocket.Conn, frame protocol.Typed) {
	t.Helper()
	body, err := protocol.MarshalFrame(frame)
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Write(context.Background(), websocket.MessageText, body); err != nil {
		t.Fatal(err)
	}
}

func readFrame(t *testing.T, conn *websocket.Conn) protocol.Typed {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	typ, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if typ != websocket.MessageText {
		t.Fatalf("message type=%v", typ)
	}
	frame, err := protocol.UnmarshalFrame(data)
	if err != nil {
		t.Fatal(err)
	}
	return frame
}
