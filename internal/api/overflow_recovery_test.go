package api

// overflow_recovery_test.go locks the WS-level loss contract. A full send
// queue is created before the production sendMirror path is called; the
// server must abort immediately rather than silently discard a delta or flush
// stale queue contents into a new transport.

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func TestWSQueueOverflowAbortsOnceAndDiscardsQueue(t *testing.T) {
	accepted := make(chan *websocket.Conn, 1)
	hsrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err == nil {
			accepted <- conn
		}
	}))
	defer hsrv.Close()

	url := "ws" + strings.TrimPrefix(hsrv.URL, "http")
	client, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		t.Fatalf("dial websocket: %v", err)
	}
	defer client.CloseNow()

	var serverConn *websocket.Conn
	select {
	case serverConn = <-accepted:
	case <-time.After(5 * time.Second):
		t.Fatal("server websocket was not accepted")
	}
	defer serverConn.CloseNow()

	srv := NewServer(Options{Token: "test-token", Log: discardLogger()})
	defer srv.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	writeCtx, writeStop := context.WithCancel(context.Background())
	defer writeStop()
	c := &wsConn{
		s:         srv,
		id:        1,
		conn:      serverConn,
		ctx:       ctx,
		cancel:    cancel,
		writeCtx:  writeCtx,
		writeStop: writeStop,
		subs:      make(map[string]*subscription),
		sendCh:    make(chan wsMsg, 1),
	}

	// This frame represents old deltas that must never be flushed after loss.
	stale, err := protocol.EncodeBinary(protocol.BinaryPayload{
		Kind: protocol.KindDelta,
		Ref:  "stale",
		Data: []byte("stale-delta"),
	})
	if err != nil {
		t.Fatalf("encode stale frame: %v", err)
	}
	c.sendCh <- wsMsg{typ: wsBinary, data: stale}
	c.sendMirror([]byte("new-delta-that-overflows"))

	select {
	case <-c.ctx.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("WS queue overflow did not abort the connection")
	}
	if got := len(c.sendCh); got != 0 {
		t.Fatalf("overflow abort retained %d stale queue entries", got)
	}

	// A second overflow on this connection is a no-op: it must not enqueue a
	// fresh frame or initiate another close path after the once-only abort.
	c.sendMirror([]byte("second-overflow"))
	if got := len(c.sendCh); got != 0 {
		t.Fatalf("second overflow enqueued data after abort: len=%d", got)
	}

	readCtx, readCancel := context.WithTimeout(context.Background(), 2*time.Second)
	_, _, err = client.Read(readCtx)
	readCancel()
	if err == nil {
		t.Fatal("client read succeeded after overflow; transport was not aborted")
	}
}
