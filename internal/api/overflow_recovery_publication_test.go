package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// The HTTP handler reaches the connection dispatch while hook publication is
// still locked. Dial is only transport setup: trackersMu publishes the callback
// and its captured state. The callback reenters that lock and gates only the
// first connection; a later authenticated connection must proceed independently.
func TestConnInitPublicationAndCallbackIsolation(t *testing.T) {
	srv := NewServer(Options{Token: "test-token", Log: discardLogger()})
	accepted := make(chan struct{}, 2)
	hsrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		accepted <- struct{}{}
		srv.serveConn(conn)
	}))
	release := make(chan struct{})
	var lockOps sync.WaitGroup
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	srv.trackersMu.Lock()
	locked := true
	t.Cleanup(func() {
		if locked {
			srv.trackersMu.Unlock()
		}
		unblock()
		srv.Close()
		hsrv.Close()
		joined := make(chan struct{})
		go func() { lockOps.Wait(); close(joined) }()
		awaitBoundary(t, joined, "publication lock operation cleanup")
	})
	dial := func() *wsEnv {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(hsrv.URL, "http"), nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = conn.CloseNow() })
		return &wsEnv{t: t, srv: srv, hsrv: hsrv, conn: conn}
	}
	first := dial()
	awaitBoundary(t, accepted, "HTTP handler accepted before hook publication")
	firstEntered, secondEntered := make(chan struct{}), make(chan struct{})
	var calls atomic.Int64
	captured := 0
	srv.connInit = func(c *wsConn) {
		// If serveConn invokes the callback while holding trackersMu, this cannot
		// complete. No client-side network ordering can substitute for this check.
		valueRead := make(chan int, 1)
		lockOps.Add(1)
		go func() {
			defer lockOps.Done()
			srv.trackersMu.Lock()
			value := captured
			srv.trackersMu.Unlock()
			valueRead <- value
		}()
		select {
		case value := <-valueRead:
			if value != 16 {
				t.Errorf("captured publication value=%d, want16", value)
			}
		case <-release:
			// A failure cleanup releases the callback even if an incorrect
			// lock-held invocation prevents its lock operation from completing.
			return
		}
		switch calls.Add(1) {
		case 1:
			close(firstEntered)
			<-release
		case 2:
			close(secondEntered)
		default:
			t.Error("callback reached an unexpected connection")
		}
	}
	captured = 16
	srv.trackersMu.Unlock()
	locked = false
	awaitBoundary(t, firstEntered, "first callback reentered trackersMu")
	second := dial()
	awaitBoundary(t, secondEntered, "second callback bypassed first connection gate")
	second.auth()
	if calls.Load() != 2 {
		t.Fatalf("callback count=%d, want2", calls.Load())
	}
	unblock()
	first.auth()
}
