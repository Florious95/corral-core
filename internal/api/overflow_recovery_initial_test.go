package api

// overflow_recovery_initial_test.go covers the initial-subscribe ownership
// window. The production relay starts before capture and first-frame queueing;
// these tests hold each fallible boundary while a real bridge source overflows,
// then assert the connection abort and pane geometry cleanup are observable.

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// newDirectWSConn gives a test a production wsConn without starting readLoop.
// The bridge and catalog remain real; only the HTTP acceptor is a small test
// fixture so handleSubscribe can be held at an exact boundary.
func newDirectWSConn(t *testing.T, srv *Server, queueSize int) *wsConn {
	t.Helper()
	c, _ := newDirectWSPair(t, srv, queueSize)
	return c
}

func newDirectWSPair(t *testing.T, srv *Server, queueSize int) (*wsConn, *websocket.Conn) {
	t.Helper()
	accepted := make(chan *websocket.Conn, 1)
	hsrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err == nil {
			accepted <- conn
		}
	}))
	url := "ws" + strings.TrimPrefix(hsrv.URL, "http")
	client, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		hsrv.Close()
		t.Fatalf("dial direct websocket: %v", err)
	}
	var serverConn *websocket.Conn
	select {
	case serverConn = <-accepted:
	case <-time.After(5 * time.Second):
		_ = client.CloseNow()
		hsrv.Close()
		t.Fatal("direct websocket was not accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	writeCtx, writeStop := context.WithCancel(context.Background())
	c := &wsConn{
		s:         srv,
		id:        1,
		conn:      serverConn,
		ctx:       ctx,
		cancel:    cancel,
		writeCtx:  writeCtx,
		writeStop: writeStop,
		subs:      make(map[string]*subscription),
		sendCh:    make(chan wsMsg, queueSize),
	}
	t.Cleanup(func() {
		cancel()
		writeStop()
		_ = client.CloseNow()
		_ = serverConn.CloseNow()
		hsrv.Close()
	})
	return c, client
}

func assertNoLiveSubscription(t *testing.T, c *wsConn) {
	t.Helper()
	c.subsMu.Lock()
	defer c.subsMu.Unlock()
	if len(c.subs) != 0 {
		t.Fatalf("initial subscribe left %d live subscriptions", len(c.subs))
	}
}

func waitSubscribeDone(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("handleSubscribe did not finish")
	}
}

// TestInitialSubscribeLossCancelsCapture proves that a loss arriving while
// capture is blocked is handled before capture returns. The test source is a
// real isolated tmux pane and the overflow reaches the production bridge
// fanout; the snapshot seam only supplies a deterministic capture barrier.
func TestInitialSubscribeLossCancelsCapture(t *testing.T) {
	te := startTmuxEnv(t, "bash")
	c := newDirectWSConn(t, te.wsEnv.srv, 4)
	captureStarted := make(chan struct{})
	c.snapshotFn = func(ctx context.Context, _ *bridge.Pane) ([]byte, error) {
		close(captureStarted)
		<-ctx.Done()
		return nil, ctx.Err()
	}
	done := make(chan struct{})
	go func() {
		c.handleSubscribe(protocol.Subscribe{Ref: te.ref(), Rows: 96, Cols: 108})
		close(done)
	}()
	t.Cleanup(func() { c.cancel(); waitSubscribeDone(t, done) })
	select {
	case <-captureStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("capture barrier was not reached")
	}
	if err := sendTmuxLine(te, recoveryBurstCommand()); err != nil {
		t.Fatalf("trigger capture-window overflow: %v", err)
	}
	select {
	case <-c.ctx.Done():
	case <-time.After(10 * time.Second):
		t.Fatal("loss did not abort while capture was blocked")
	}
	awaitMirrorAbort(t, c)
	waitSubscribeDone(t, done)
	assertNoLiveSubscription(t, c)
	if got := waitPaneSize(te, "80x24"); got != "80x24" {
		t.Fatalf("capture-loss teardown left pane at %s, want 80x24", got)
	}
}

// TestInitialSubscribeLossCancelsFirstFrameQueue proves that the same loss
// owner remains live after capture and cancels a blocked initial snapshot send.
func TestInitialSubscribeLossCancelsFirstFrameQueue(t *testing.T) {
	te := startTmuxEnv(t, "bash")
	c := newDirectWSConn(t, te.wsEnv.srv, 1)
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale-before-snapshot")}
	sendStarted := make(chan struct{})
	c.sendBinaryFn = func(frame []byte) {
		close(sendStarted)
		c.sendBinary(frame)
	}
	c.snapshotFn = func(context.Context, *bridge.Pane) ([]byte, error) {
		return []byte("controlled-snapshot"), nil
	}
	done := make(chan struct{})
	go func() {
		c.handleSubscribe(protocol.Subscribe{Ref: te.ref(), Rows: 96, Cols: 108})
		close(done)
	}()
	t.Cleanup(func() { c.cancel(); waitSubscribeDone(t, done) })
	select {
	case <-sendStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("initial snapshot send barrier was not reached")
	}
	if err := sendTmuxLine(te, recoveryBurstCommand()); err != nil {
		t.Fatalf("trigger first-frame-window overflow: %v", err)
	}
	select {
	case <-c.ctx.Done():
	case <-time.After(10 * time.Second):
		t.Fatal("loss did not abort while first snapshot send was blocked")
	}
	awaitMirrorAbort(t, c)
	waitSubscribeDone(t, done)
	if got := len(c.sendCh); got != 0 {
		t.Fatalf("first-frame loss retained %d stale queue entries", got)
	}
	assertNoLiveSubscription(t, c)
	if got := waitPaneSize(te, "80x24"); got != "80x24" {
		t.Fatalf("first-frame-loss teardown left pane at %s, want 80x24", got)
	}
}

// TestInitialSubscribeCaptureErrorReleasesGeometry proves a plain capture
// failure follows the same idempotent detach/geometry release path, without
// requiring a loss signal.
func TestInitialSubscribeCaptureErrorReleasesGeometry(t *testing.T) {
	te := startTmuxEnv(t, "bash")
	c := newDirectWSConn(t, te.wsEnv.srv, 4)
	started := make(chan struct{})
	c.snapshotFn = func(context.Context, *bridge.Pane) ([]byte, error) {
		close(started)
		return nil, errors.New("controlled capture failure")
	}
	done := make(chan struct{})
	go func() {
		c.handleSubscribe(protocol.Subscribe{Ref: te.ref(), Rows: 96, Cols: 108})
		close(done)
	}()
	t.Cleanup(func() { c.cancel(); waitSubscribeDone(t, done) })
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("capture error barrier was not reached")
	}
	waitSubscribeDone(t, done)
	assertNoLiveSubscription(t, c)
	if c.catalogAborted.Load() || c.ctx.Err() != nil {
		t.Fatal("ordinary capture failure aborted a healthy connection")
	}
	select {
	case m := <-c.sendCh:
		frame, err := protocol.UnmarshalFrame(m.data)
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := frame.(protocol.ErrorFrame); !ok {
			t.Fatalf("capture error reply=%T", frame)
		}
	default:
		t.Fatal("ordinary capture failure omitted ErrorFrame")
	}
	if got := waitPaneSize(te, "80x24"); got != "80x24" {
		t.Fatalf("capture-error teardown left pane at %s, want 80x24", got)
	}
}

func awaitMirrorAbort(t *testing.T, c *wsConn) {
	t.Helper()
	awaitBoundary(t, c.ctx.Done(), "connection cancelled by overflow")
	c.catalogAbortOnce.Do(func() { t.Error("cancellation did not run abort") })
	if !c.catalogAborted.Load() {
		t.Fatal("missing abort state")
	}
}

const (
	// These tokens are emitted as OSC title updates.  They are observable on
	// the raw pipe stream but do not change the visible pane grid, so the final
	// screen oracle remains static and complete.
	recoveryReadyToken = "P16_RECOVERY_READY"
	recoveryAfterToken = "P16_RECOVERY_AFTER"

	// Legacy capture-window tests use this burst to provoke production loss.
	// The scenario below instead controls each queue stage independently.
	recoveryBurstBytes = 16 << 20
)

// recoveryBurstCommand writes one source burst, then the static screen and an
// out-of-band completion barrier.  It stays alive waiting for the test's
// post-reconnect release line, preventing a shell prompt or process exit from
// changing the snapshot oracle before replay is exercised.
func recoveryBurstCommand() string {
	return fmt.Sprintf(`python3 -c 'import sys,time;sys.stdout.write("X"*%d);sys.stdout.flush();sys.stdout.write("\033[2J\033[H\033[1;34mRECOVERED TITLE\033[0m\n\033[3;5m日本語 ✓\033[0m\n\033[5;1mCURSOR_ORACLE\033[0m\033[7;13HRECOVERY_DONE");sys.stdout.write("\033]0;%s\007");sys.stdout.flush();sys.stdin.readline();sys.stdout.write("\033]0;%s\007");sys.stdout.flush();time.sleep(120)'`, recoveryBurstBytes, recoveryReadyToken, recoveryAfterToken)
}

func sendTmuxLine(t *tmuxEnv, line string) error {
	if _, err := runTmuxCmd(t.env, t.sock, "send-keys", "-t", t.paneID, "-l", "--", line); err != nil {
		return err
	}
	_, err := runTmuxCmd(t.env, t.sock, "send-keys", "-t", t.paneID, "Enter")
	return err
}
