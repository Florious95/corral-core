package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/coder/websocket"
)

func TestRelayLossAbortsConnectionAndDropsQueuedDeltas(t *testing.T) {
	accepted := make(chan *websocket.Conn, 1)
	hsrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err == nil {
			accepted <- conn
		}
	}))
	defer hsrv.Close()
	client, _, err := websocket.Dial(context.Background(), "ws"+strings.TrimPrefix(hsrv.URL, "http"), nil)
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
	c := &wsConn{
		s:               srv,
		id:              1,
		conn:            serverConn,
		ctx:             ctx,
		cancel:          cancel,
		subs:            make(map[string]*subscription),
		sendCh:          make(chan wsMsg, 2),
		mirrorAbortDone: make(chan struct{}),
	}

	relayCtx, relayCancel := context.WithCancel(ctx)
	defer relayCancel()
	data := make(chan []byte, 1)
	loss := make(chan error, 1)
	ready := make(chan struct{})
	sub := &subscription{
		ref:    "alpha",
		cancel: relayCancel,
		detach: func() {},
		loss:   loss,
		ready:  ready,
	}
	// This queued frame stands in for a stale pre-snapshot delta. The relay is
	// gated until snapshot queueing completes, but loss must still be consumed.
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale-delta")}
	go c.relay(relayCtx, sub, data)

	loss <- bridge.ErrSubscriberOverflow
	close(loss)
	select {
	case <-c.mirrorAbortDone:
	case <-time.After(2 * time.Second):
		t.Fatal("relay loss signal did not complete the connection abort")
	}
	if got := len(c.sendCh); got != 0 {
		t.Fatalf("relay loss retained %d stale queue entries", got)
	}
	readCtx, readCancel := context.WithTimeout(context.Background(), 2*time.Second)
	_, _, err = client.Read(readCtx)
	readCancel()
	if err == nil {
		t.Fatal("client read succeeded after relay loss abort")
	}
}
