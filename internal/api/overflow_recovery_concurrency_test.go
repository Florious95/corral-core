package api

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func awaitBoundary(t *testing.T, ch <-chan struct{}, name string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(5 * time.Second):
		t.Fatalf("boundary missing: %s", name)
	}
}

func requireTransportEnd(t *testing.T, peer *websocket.Conn) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, _, err := peer.Read(ctx)
	if err == nil || ctx.Err() != nil || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		t.Fatalf("expected transport termination, got err=%v context=%v", err, ctx.Err())
	}
}

func TestAbortDoesNotFlushFrameAlreadyDequeuedByFlush(t *testing.T) {
	srv := NewServer(Options{Token: "test-token", Log: discardLogger()})
	defer srv.Close()
	c, peer := newDirectWSPair(t, srv, 2)
	entered, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	var attempts int
	c.beforeWriterFrame = func(wsMsg) { close(entered); <-release }
	c.writeAttempt = func(wsMsg) { attempts++ }
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale")}
	c.cancel() // ordinary cancellation enters the real flush path
	go func() { c.flushQueued(); close(done) }()
	t.Cleanup(func() { unblock(); _ = c.conn.CloseNow(); awaitBoundary(t, done, "flush cleanup") })
	awaitBoundary(t, entered, "flush dequeued frame")
	c.abortConnection("mirror_loss: " + bridge.ErrSubscriberOverflow.Error())
	unblock()
	awaitBoundary(t, done, "flush exit")
	if n := attempts; n != 0 {
		t.Fatalf("flush attempted %d writes after loss", n)
	}
	requireTransportEnd(t, peer)
}

// pacedSource emits exactly one numbered OSC record per input line. Terminal
// input echo and ONLCR are disabled before any subscription, so the entire raw
// stream is independently known. Each healthy acknowledgement permits the next
// input; no queue size or read-chunk assumption substitutes for overflow.
func pacedSourceCommand() string {
	return `stty -echo -onlcr; python3 -u -c 'import sys; [(sys.stdout.write("\033]0;P16_%04d_"%int(line)+"abcdefgh"*64+"\007"),sys.stdout.flush()) for line in sys.stdin]'`
}
func pacedRecord(i int) []byte {
	return []byte(fmt.Sprintf("\x1b]0;P16_%04d_", i) + strings.Repeat("abcdefgh", 64) + "\x07")
}

func emitPacedBridge(t *testing.T, te *tmuxEnv, data <-chan []byte, n int) []byte {
	t.Helper()
	var all []byte
	for i := 0; i < n; i++ {
		if err := sendTmuxLine(te, fmt.Sprint(i)); err != nil {
			t.Fatal(err)
		}
		want := pacedRecord(i)
		var got []byte
		deadline := time.NewTimer(5 * time.Second)
		for !bytes.Contains(got, want) {
			select {
			case chunk, ok := <-data:
				if !ok {
					t.Fatal("healthy bridge terminated")
				}
				got = append(got, chunk...)
			case <-deadline.C:
				t.Fatalf("healthy record %d acknowledgement missing: got %d want %d", i, len(got), len(want))
			}
		}
		deadline.Stop()
		all = append(all, got...)
	}
	return all
}

func TestInitialPublishedLossWinsCaptureFailure(t *testing.T) {
	te := startTmuxEnv(t, pacedSourceCommand())
	c, _ := newDirectWSPair(t, te.wsEnv.srv, 4)
	relayEntered, release, failed, done := make(chan *subscription, 1), make(chan struct{}), make(chan struct{}), make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	c.beforeRelay = func(sub *subscription) { relayEntered <- sub; <-release }
	c.snapshotFn = func(ctx context.Context, _ *bridge.Pane) ([]byte, error) {
		select {
		case <-failed:
			return nil, errors.New("independent capture error")
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	go func() { c.handleSubscribe(protocol.Subscribe{Ref: te.ref(), Rows: 96, Cols: 108}); close(done) }()
	t.Cleanup(func() { c.cancel(); unblock(); awaitBoundary(t, done, "subscribe cleanup") })
	var sub *subscription
	select {
	case sub = <-relayEntered:
	case <-time.After(5 * time.Second):
		t.Fatal("relay did not start")
	}
	br, ok := c.resolveBridge(te.ref())
	if !ok {
		t.Fatal("pane missing")
	}
	data, loss, detach, err := br.SubscribeWithLoss(c.ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer detach()
	// The production subscriber now retains a 256-chunk snapshot backlog;
	// exceed that bound while the relay is held before the snapshot is ready.
	emitPacedBridge(t, te, data, 512)
	if len(sub.loss) != 1 {
		t.Fatalf("production loss not published: buffered=%d", len(sub.loss))
	}
	select {
	case err := <-loss:
		t.Fatalf("healthy bridge loss: %v", err)
	default:
	}
	close(failed)
	awaitBoundary(t, sub.initialFailed, "handler handed off independent capture failure")
	unblock()
	awaitMirrorAbort(t, c)
	awaitBoundary(t, done, "capture failure completion")
	assertNoLiveSubscription(t, c)
	if reason := fmt.Sprint(c.catalogCloseReason.Load()); !strings.Contains(reason, "bridge: subscriber queue overflow") {
		t.Fatalf("wrong abort cause: %s", reason)
	}
	detach()
	if got := waitPaneSize(te, "80x24"); got != "80x24" {
		t.Fatalf("geometry=%s", got)
	}
}

func TestReadyRelayOverflowWhileControlSendBlocked(t *testing.T) {
	te := startTmuxEnv(t, pacedSourceCommand())
	c, peer := newDirectWSPair(t, te.wsEnv.srv, 1)
	c.snapshotFn = func(context.Context, *bridge.Pane) ([]byte, error) { return []byte("snapshot"), nil }
	c.handleSubscribe(protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	taken, release, writerDone := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	var takenOnce sync.Once
	c.beforeWriterFrame = func(wsMsg) { takenOnce.Do(func() { close(taken) }); <-release }
	go func() { c.writeLoop(); close(writerDone) }()
	t.Cleanup(func() { c.cancel(); unblock(); _ = c.conn.CloseNow(); awaitBoundary(t, writerDone, "writer cleanup") })
	awaitBoundary(t, taken, "writer withheld snapshot")
	c.sendCh <- wsMsg{typ: wsText, data: []byte("full")}
	entered, producerDone := make(chan struct{}), make(chan struct{})
	c.controlEnqueue = func() { close(entered) }
	go func() { c.sendMsg(wsMsg{typ: wsText, data: []byte("blocked")}); close(producerDone) }()
	t.Cleanup(func() { c.cancel(); awaitBoundary(t, producerDone, "control sender cleanup") })
	awaitBoundary(t, entered, "control sender inside queue lock")
	br, ok := c.resolveBridge(te.ref())
	if !ok {
		t.Fatal("pane missing")
	}
	data, loss, detach, err := br.SubscribeWithLoss(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer detach()
	// The relay now waits behind a full send queue. Keep the writer blocked
	// long enough for the independent bridge queue to reach its 256-chunk
	// bound; that loss still aborts the connection promptly.
	emitPacedBridge(t, te, data, 320)
	awaitMirrorAbort(t, c)
	awaitBoundary(t, producerDone, "cancelled control producer")
	if !strings.Contains(fmt.Sprint(c.catalogCloseReason.Load()), bridge.ErrSubscriberOverflow.Error()) {
		t.Fatalf("wrong overflow cause: %v", c.catalogCloseReason.Load())
	}
	select {
	case err := <-loss:
		t.Fatalf("healthy bridge loss: %v", err)
	default:
	}
	if len(c.sendCh) != 0 {
		t.Fatal("abort retained queued frames")
	}
	requireTransportEnd(t, peer)
	unblock()
	awaitBoundary(t, writerDone, "writer exit")
}
