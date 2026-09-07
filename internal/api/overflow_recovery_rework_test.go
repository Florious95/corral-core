package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func TestAbortDoesNotFlushDequeuedWriterFrame(t *testing.T) {
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
	writeCtx, writeStop := context.WithCancel(context.Background())
	defer writeStop()
	gate := make(chan struct{})
	taken := make(chan struct{})
	writerDone := make(chan struct{})
	c := &wsConn{
		s:               srv,
		id:              1,
		conn:            serverConn,
		ctx:             ctx,
		cancel:          cancel,
		writeCtx:        writeCtx,
		writeStop:       writeStop,
		subs:            make(map[string]*subscription),
		sendCh:          make(chan wsMsg, 2),
		mirrorAbortDone: make(chan struct{}),
		writerGate:      gate,
		writerTaken:     taken,
	}
	stale1, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: "alpha", Data: []byte("stale-1")})
	if err != nil {
		t.Fatalf("encode stale1: %v", err)
	}
	stale2, err := protocol.EncodeBinary(protocol.BinaryPayload{Kind: protocol.KindDelta, Ref: "alpha", Data: []byte("stale-2")})
	if err != nil {
		t.Fatalf("encode stale2: %v", err)
	}
	c.sendCh <- wsMsg{typ: wsBinary, data: stale1}
	c.sendCh <- wsMsg{typ: wsBinary, data: stale2}
	go func() {
		c.writeLoop()
		close(writerDone)
	}()
	select {
	case <-taken:
	case <-time.After(2 * time.Second):
		t.Fatal("writer did not reach controlled dequeue barrier")
	}
	if len(c.sendCh) != 1 {
		t.Fatalf("controlled writer queue len=%d, want one queued stale frame", len(c.sendCh))
	}

	c.abortMirrorLoss("alpha", bridge.ErrSubscriberOverflow)
	select {
	case <-c.mirrorAbortDone:
	case <-time.After(2 * time.Second):
		t.Fatal("abort completion barrier did not fire")
	}
	if len(c.sendCh) != 0 {
		t.Fatalf("abort left %d stale queued frames", len(c.sendCh))
	}
	close(gate)
	select {
	case <-writerDone:
	case <-time.After(2 * time.Second):
		t.Fatal("writer did not terminate after abort release")
	}

	readCtx, readCancel := context.WithTimeout(context.Background(), time.Second)
	start := time.Now()
	_, _, err = client.Read(readCtx)
	readCancel()
	if err == nil {
		t.Fatal("client received a frame after mirror-loss abort")
	}
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Fatalf("transport close was not observable promptly: %v", elapsed)
	}
}
