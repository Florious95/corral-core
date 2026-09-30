package api

// ws_test.go provides the test harness shared by the api package tests: an
// httptest server wired to the API handler, a real WebSocket client (coder/
// websocket, the same library production uses), and scripted Discoverers that
// return fixed or mutable discovery.Models so tests exercise the full wiring
// without touching any real tmux socket (term-bridge/discovery red line).

import (
	"context"
	"log/slog"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// discardLogger returns a logger that swallows everything.
func discardLogger() *slog.Logger {
	return slog.New(slog.DiscardHandler)
}

// scriptedDiscoverer returns a fixed model on every call. An err makes every
// call fail (used to test the discovery-failure path).
type scriptedDiscoverer struct {
	model *discovery.Model
	err   error
}

func (d scriptedDiscoverer) Discover(context.Context) (*discovery.Model, error) {
	return d.model, d.err
}

// mutableDiscoverer returns a model that can be swapped between scans, so a
// test can drive the listing loop's diff to observe list_delta emissions.
type mutableDiscoverer struct {
	mu    sync.Mutex
	model *discovery.Model
}

func (d *mutableDiscoverer) Discover(context.Context) (*discovery.Model, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.model, nil
}

func (d *mutableDiscoverer) set(m *discovery.Model) {
	d.mu.Lock()
	d.model = m
	d.mu.Unlock()
}

// wsEnv is a test server + connected client pair.
type wsEnv struct {
	t    *testing.T
	srv  *Server
	hsrv *httptest.Server
	conn *websocket.Conn
}

// startWS starts a test API server over httptest with the given options and
// connects one client. The test server is closed on cleanup.
func startWS(t *testing.T, opts Options) *wsEnv {
	t.Helper()
	if opts.Log == nil {
		opts.Log = discardLogger()
	}
	if opts.ListInterval == 0 {
		opts.ListInterval = 50 * time.Millisecond
	}
	srv := NewServer(opts)
	hsrv := httptest.NewServer(srv.Handler())
	t.Cleanup(func() {
		hsrv.Close()
		srv.Close()
	})

	// The WS endpoint is served at /ws on the test server; the dial URL must
	// carry that path or the mux answers 404 (an empty path requests "/").
	url := "ws" + strings.TrimPrefix(hsrv.URL, "http") + "/ws"
	conn, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		t.Fatalf("dial ws: %v", err)
	}
	t.Cleanup(func() { _ = conn.CloseNow() })
	return &wsEnv{t: t, srv: srv, hsrv: hsrv, conn: conn}
}

// readControl reads one message and decodes it as a control frame, failing the
// test if it is not a JSON text frame.
func (e *wsEnv) readControl() protocol.Typed {
	e.t.Helper()
	typ, data, err := e.conn.Read(context.Background())
	if err != nil {
		e.t.Fatalf("read frame: %v", err)
	}
	if typ != websocket.MessageText {
		e.t.Fatalf("expected control frame, got message type %v", typ)
	}
	typed, err := protocol.UnmarshalFrame(data)
	if err != nil {
		e.t.Fatalf("decode frame %q: %v", data, err)
	}
	return typed
}

// readControlDraining reads control frames, skipping (draining) any binary
// mirror frames that arrive first (e.g. the echo of an injected input). It
// fails the test if no control frame arrives within 5s.
func (e *wsEnv) readControlDraining() protocol.Typed {
	e.t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			e.t.Fatalf("read control (draining): %v", err)
		}
		if typ == websocket.MessageBinary {
			continue // mirror echo; not the reply we want
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			e.t.Fatalf("decode frame %q: %v", data, err)
		}
		return typed
	}
	e.t.Fatal("timed out waiting for a control frame")
	return nil
}

// sendFrame marshals and sends one control frame.
func (e *wsEnv) sendFrame(typed protocol.Typed) {
	e.t.Helper()
	body, err := protocol.MarshalFrame(typed)
	if err != nil {
		e.t.Fatalf("marshal frame: %v", err)
	}
	if err := e.conn.Write(context.Background(), websocket.MessageText, body); err != nil {
		e.t.Fatalf("write frame: %v", err)
	}
}

// auth authenticates the test client with the default token.
func (e *wsEnv) auth() {
	e.t.Helper()
	e.sendFrame(&protocol.Auth{Token: "test-token"})
	ack := e.readControl()
	if ack.FrameType() != protocol.TypeAuthAck {
		e.t.Fatalf("expected auth_ack, got %v", ack.FrameType())
	}
	aa := ack.(protocol.AuthAck)
	if !aa.OK {
		e.t.Fatalf("auth rejected: %s", aa.Reason)
	}
}

// testModel builds a small deterministic discovery model for tests: one
// workspace with two panes on the same socket.
func testModel() *discovery.Model {
	return &discovery.Model{
		Workspaces: []discovery.Workspace{
			{
				CWD: "/ws/a",
				Panes: []discovery.Pane{
					{Socket: "/tmp/sock1", Session: "alpha", PaneID: "%0", CWD: "/ws/a", Command: "claude", Width: 100, Height: 40},
					{Socket: "/tmp/sock1", Session: "beta", PaneID: "%1", CWD: "/ws/a", Command: "codex", Width: 80, Height: 24},
				},
			},
		},
	}
}
