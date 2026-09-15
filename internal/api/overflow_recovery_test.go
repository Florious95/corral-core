package api

// overflow_recovery_test.go locks the WS-level loss contract. A full send
// queue is created before the production sendMirror path is called; the
// server must abort immediately rather than silently discard a delta or flush
// stale queue contents into a new transport.

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
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

	readCtx, readCancel := context.WithTimeout(context.Background(), 2*time.Second)
	_, _, err = client.Read(readCtx)
	readContextErr := readCtx.Err()
	readCancel()
	if readContextErr != nil || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		t.Fatalf("read deadline/cancellation is not transport termination: %v (context %v)", err, readContextErr)
	}
	if err == nil {
		t.Fatal("client read succeeded after overflow; transport was not aborted")
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

}

// TestMirrorQueueBackpressureWaitsForWriter proves a full queue is a
// transport backpressure boundary, not immediate mirror loss. Once the writer
// consumes one queued frame, the waiting delta must be enqueued intact.
func TestMirrorQueueBackpressureWaitsForWriter(t *testing.T) {
	srv := NewServer(Options{Token: "test-token", Log: discardLogger()})
	defer srv.Close()
	c, _ := newDirectWSPair(t, srv, 1)
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale")}
	loss := make(chan error, 1)
	done := make(chan bool, 1)
	go func() { done <- c.sendMirrorWait(c.ctx, loss, []byte("queued-delta")) }()
	select {
	case <-done:
		t.Fatal("full mirror queue returned before writer made progress")
	case <-time.After(100 * time.Millisecond):
	}
	<-c.sendCh
	select {
	case ok := <-done:
		if !ok {
			t.Fatal("mirror enqueue was canceled despite queue progress")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("mirror enqueue did not resume after writer progress")
	}
	select {
	case msg := <-c.sendCh:
		if string(msg.data) != "queued-delta" {
			t.Fatalf("queued mirror data=%q", msg.data)
		}
	default:
		t.Fatal("waiting mirror frame was not enqueued")
	}
	if c.catalogAborted.Load() {
		t.Fatal("ordinary queue backpressure aborted the connection")
	}
}

// TestMirrorQueueBackpressureObservesSubscriberLoss proves a relay waiting
// behind a slow websocket still aborts promptly when bridge loss is published.
func TestMirrorQueueBackpressureObservesSubscriberLoss(t *testing.T) {
	srv := NewServer(Options{Token: "test-token", Log: discardLogger()})
	defer srv.Close()
	c, _ := newDirectWSPair(t, srv, 1)
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale")}
	loss := make(chan error, 1)
	done := make(chan bool, 1)
	go func() { done <- c.sendMirrorWait(c.ctx, loss, []byte("must-not-send")) }()
	select {
	case <-done:
		t.Fatal("full mirror queue returned before loss or cancellation")
	case <-time.After(100 * time.Millisecond):
	}
	loss <- bridge.ErrSubscriberOverflow
	select {
	case ok := <-done:
		if ok {
			t.Fatal("loss boundary reported successful mirror enqueue")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("subscriber loss did not abort blocked mirror enqueue")
	}
	if !c.catalogAborted.Load() {
		t.Fatal("subscriber loss did not abort the connection")
	}
	if got := len(c.sendCh); got != 0 {
		t.Fatalf("loss abort retained %d queued frames", got)
	}
}

// TestMirrorQueueBackpressureObservesContext proves a canceled subscription
// leaves a relay blocked behind a full queue without aborting its connection.
func TestMirrorQueueBackpressureObservesContext(t *testing.T) {
	srv := NewServer(Options{Token: "test-token", Log: discardLogger()})
	defer srv.Close()
	c, _ := newDirectWSPair(t, srv, 1)
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale")}
	ctx, cancel := context.WithCancel(c.ctx)
	loss := make(chan error, 1)
	done := make(chan bool, 1)
	go func() { done <- c.sendMirrorWait(ctx, loss, []byte("must-not-send")) }()
	select {
	case <-done:
		t.Fatal("full mirror queue returned before context cancellation")
	case <-time.After(100 * time.Millisecond):
	}
	cancel()
	select {
	case ok := <-done:
		if ok {
			t.Fatal("canceled subscription reported successful mirror enqueue")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("subscription cancellation did not unblock mirror enqueue")
	}
	if c.catalogAborted.Load() {
		t.Fatal("subscription cancellation aborted the connection")
	}
}

// TestWSOverflowStopsDequeuedWriterFrame proves the loss barrier also wins
// after the writer has dequeued an old frame but before the network write.
// The queue is refilled to full, then production sendMirror overflows it;
// abort must prevent the held frame's writeAttempt and discard all stale data.
func TestWSOverflowStopsDequeuedWriterFrame(t *testing.T) {
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
	c := &wsConn{
		s:         srv,
		id:        2,
		conn:      serverConn,
		ctx:       ctx,
		cancel:    cancel,
		writeCtx:  writeCtx,
		writeStop: writeStop,
		subs:      make(map[string]*subscription),
		sendCh:    make(chan wsMsg, 2),
	}
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale-in-flight")}
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale-queued")}

	entered, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var enterOnce, releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	var attempts int
	c.beforeWriterFrame = func(wsMsg) {
		enterOnce.Do(func() { close(entered) })
		<-release
	}
	c.writeAttempt = func(wsMsg) { attempts++ }
	go func() {
		c.writeLoop()
		close(done)
	}()
	t.Cleanup(func() { unblock(); cancel(); _ = serverConn.CloseNow(); <-done })
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("writer did not dequeue the held stale frame")
	}

	// Refill the one free slot, then hit the real full-queue sendMirror path.
	c.sendMirror([]byte("stale-refill"))
	c.sendMirror([]byte("overflow-trigger"))
	deadline := time.Now().Add(2 * time.Second)
	for !c.catalogAborted.Load() && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !c.catalogAborted.Load() {
		t.Fatal("overflow did not abort the connection")
	}
	unblock()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("writer did not stop after overflow")
	}
	if attempts != 0 {
		t.Fatalf("writer attempted %d stale writes after overflow", attempts)
	}
	if got := len(c.sendCh); got != 0 {
		t.Fatalf("overflow retained %d queued stale frames", got)
	}
	readCtx, readCancel := context.WithTimeout(context.Background(), 2*time.Second)
	_, _, err = client.Read(readCtx)
	readContextErr := readCtx.Err()
	readCancel()
	if err == nil || readContextErr != nil || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		t.Fatalf("overflow did not terminate transport: %v (context %v)", err, readContextErr)
	}
}
