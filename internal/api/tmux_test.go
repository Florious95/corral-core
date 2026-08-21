package api

// tmux_test.go provides an isolated-tmux harness for the API's mirroring
// paths, following the term-bridge/discovery red lines:
//
//   - every tmux server is started on a unique absolute socket inside a short
//     temp dir (sun_path ~104-byte limit) and torn down by t.Cleanup;
//   - every spawned tmux gets TMUX/TMUX_TMPDIR stripped, so a nested tmux can
//     never attach to the caller's real fleet (the "never kill the real team"
//     absolute red line);
//   - all blocking reads carry a timeout so a misbehaving mirror cannot hang
//     the suite.

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

var tmuxSeq uint64

// tmuxEnv is one isolated tmux server and the API server wired to it.
type tmuxEnv struct {
	t      *testing.T
	sock   string
	paneID string
	env    []string
	wsEnv  *wsEnv
}

// startTmuxEnv starts an isolated tmux server with a session running cmd, and
// wires an API server whose discoverer returns a model pointing at that
// socket's pane. The discovery model is fixed to the single isolated pane, so
// the API mirrors only that pane and nothing on the host.
func startTmuxEnv(t *testing.T, cmd string) *tmuxEnv {
	t.Helper()
	dir, err := os.MkdirTemp("", "wsapi")
	if err != nil {
		t.Fatalf("MkdirTemp: %v", err)
	}
	sock := filepath.Join(dir, "sock")
	env := scrubbedEnv()

	// Start the isolated server.
	name := fmt.Sprintf("wsapi%d", atomic.AddUint64(&tmuxSeq, 1))
	if out, err := runTmuxCmd(env, sock, "new-session", "-d", "-x", "80", "-y", "24", "-s", name, "-c", t.TempDir(), cmd); err != nil {
		t.Fatalf("new-session: %v\n%s", err, out)
	}
	t.Cleanup(func() {
		_, _ = runTmuxCmd(env, sock, "kill-server")
		_ = os.RemoveAll(dir)
	})

	// Resolve the pane id (bare %N, the exact-existence-check form).
	out, err := runTmuxCmd(env, sock, "list-panes", "-t", name, "-F", "#{pane_id}")
	if err != nil {
		t.Fatalf("resolve pane: %v\n%s", err, out)
	}
	paneID := strings.TrimSpace(out)

	// Wire the API server to a discovery model that points at this isolated
	// pane only.
	model := &discovery.Model{
		Workspaces: []discovery.Workspace{
			{
				CWD: "/ws/integ",
				Panes: []discovery.Pane{
					{Socket: sock, Session: name, PaneID: paneID, CWD: "/ws/integ", Command: "cat", Width: 80, Height: 24},
				},
			},
		},
	}
	md := &mutableDiscoverer{model: model}
	e := startWS(t, Options{Token: "test-token", Discoverer: md, ProviderFinder: staticProvider("claude_code")})
	e.auth()

	return &tmuxEnv{t: t, sock: sock, paneID: paneID, env: env, wsEnv: e}
}

// ref returns the client-facing ref for the isolated pane, built exactly as
// the server builds it (socket \x1f pane id) — the test must address the same
// string the listing would publish.
func (te *tmuxEnv) ref() string {
	return te.sock + "\x1f" + te.paneID
}

// readBinaryFrame reads one binary message and decodes it via protocol.
func (te *tmuxEnv) readBinaryFrame() protocol.BinaryPayload {
	te.t.Helper()
	typ, data, err := te.wsEnv.conn.Read(context.Background())
	if err != nil {
		te.t.Fatalf("read binary: %v", err)
	}
	if typ != websocket.MessageBinary {
		te.t.Fatalf("expected binary message, got %v", typ)
	}
	payload, err := protocol.DecodeBinary(data)
	if err != nil {
		te.t.Fatalf("decode binary %q: %v", data, err)
	}
	return payload
}

// waitForMirror drains frames until a binary mirror frame's data contains want
// as a substring. Control frames (list_delta, input_ack, …) that interleave on
// the same connection are skipped; the positive control is that the mirror
// stream is actually delivering the marker.
func (te *tmuxEnv) waitForMirror(want string) {
	te.t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var got bytes.Buffer
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			te.t.Fatalf("mirror read: %v", err)
		}
		if time.Now().After(deadline) {
			te.t.Fatalf("mirror never delivered %q; got %q", want, got.String())
		}
		if typ != websocket.MessageBinary {
			continue // control frame interleaving; skip
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			te.t.Fatalf("decode mirror frame: %v", err)
		}
		got.Write(payload.Data)
		if bytes.Contains(got.Bytes(), []byte(want)) {
			return
		}
	}
}

// runTmuxCmd runs tmux -S sock <args> with the scrubbed env.
func runTmuxCmd(env []string, sock string, args ...string) (string, error) {
	cmd := exec.Command("tmux", append([]string{"-S", sock}, args...)...)
	cmd.Env = env
	out, err := cmd.CombinedOutput()
	return string(out), err
}

// scrubbedEnv removes TMUX/TMUX_TMPDIR so nested tmux never touches the real
// fleet.
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
