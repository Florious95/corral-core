package api

// ws_test.go provides the test harness shared by the api package tests: an
// httptest server wired to the API handler, a real WebSocket client (coder/
// websocket, the same library production uses), and a scripted Discoverer that
// returns a fixed discovery.Model so tests exercise the full wiring without
// touching any real tmux socket (term-bridge/discovery red line).

import (
	"context"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/remote-agent/agentmirror/internal/discovery"
	"github.com/remote-agent/agentmirror/internal/protocol"
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

// wsEnv is a test server + connected client pair.
type wsEnv struct {
	t    *testing.T
	srv  *Server
	hsrv *httptest.Server
	conn *websocket.Conn
}

// startWS starts a test API server over httptest with the given discoverer and
// connects one client. The test server is closed on cleanup.
func startWS(t *testing.T, d Discoverer) *wsEnv {
	t.Helper()
	srv := NewServer(Options{
		Token:        "test-token",
		Discoverer:   d,
		ListInterval: 50 * time.Millisecond,
		Log:          discardLogger(),
	})
	hsrv := httptest.NewServer(srv.Handler())
	t.Cleanup(func() {
		hsrv.Close()
		srv.Close()
	})

	url := "ws" + strings.TrimPrefix(hsrv.URL, "http")
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

// readBinary reads one binary message (snapshot/delta/scrollback frame).
func (e *wsEnv) readBinary() []byte {
	e.t.Helper()
	typ, data, err := e.conn.Read(context.Background())
	if err != nil {
		e.t.Fatalf("read binary: %v", err)
	}
	if typ != websocket.MessageBinary {
		e.t.Fatalf("expected binary message, got %v", typ)
	}
	return data
}

// auth authenticates the test client with the default token.
func (e *wsEnv) auth() {
	e.t.Helper()
	e.sendFrame(&protocol.Auth{Token: "test-token"})
	ack := e.readControl()
	if ack.FrameType() != protocol.TypeAuthAck {
		e.t.Fatalf("expected auth_ack, got %v", ack.FrameType())
	}
	aa := ack.(*protocol.AuthAck)
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
