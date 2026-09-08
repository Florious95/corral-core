package api

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
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
	var attempts atomic.Int64
	c.beforeFlushFrame = func() { close(entered); <-release }
	c.writeAttempt = func(wsMsg) { attempts.Add(1) }
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("stale")}
	c.cancel() // ordinary cancellation enters the real flush path
	go func() { c.flushQueued(); close(done) }()
	t.Cleanup(func() { unblock(); _ = c.conn.CloseNow(); awaitBoundary(t, done, "flush cleanup") })
	awaitBoundary(t, entered, "flush dequeued frame")
	c.abortMirrorLoss("alpha", bridge.ErrSubscriberOverflow)
	unblock()
	awaitBoundary(t, done, "flush exit")
	if n := attempts.Load(); n != 0 {
		t.Fatalf("flush attempted %d writes after loss", n)
	}
	requireTransportEnd(t, peer)
}

func TestConcurrentEnqueueAbortStopsAllProducers(t *testing.T) {
	srv := NewServer(Options{Token: "test-token", Log: discardLogger()})
	defer srv.Close()
	c, peer := newDirectWSPair(t, srv, 1)
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("full")}
	entered := make(chan struct{}, 8)
	c.controlEnqueue = func() { entered <- struct{}{} }
	var producers sync.WaitGroup
	for i := 0; i < 8; i++ {
		producers.Add(1)
		go func() { defer producers.Done(); c.sendMsg(wsMsg{typ: wsText, data: []byte("reply")}) }()
	}
	producerDone := make(chan struct{})
	go func() { producers.Wait(); close(producerDone) }()
	t.Cleanup(func() { c.cancel(); awaitBoundary(t, producerDone, "all producer cleanup") })
	for i := 0; i < 8; i++ {
		awaitBoundary(t, entered, "concurrent control sender")
	}
	var losses sync.WaitGroup
	for i := 0; i < 8; i++ {
		losses.Add(1)
		go func() { defer losses.Done(); c.abortMirrorLoss("alpha", bridge.ErrSubscriberOverflow) }()
	}
	done := make(chan struct{})
	go func() { losses.Wait(); producers.Wait(); close(done) }()
	t.Cleanup(func() { c.cancel(); awaitBoundary(t, done, "producer cleanup") })
	awaitBoundary(t, done, "all enqueue and abort callers")
	if len(c.sendCh) != 0 {
		t.Fatalf("producers refilled aborted queue: %d", len(c.sendCh))
	}
	c.sendMirror([]byte("late"))
	c.sendClose(websocket.StatusNormalClosure, "late")
	if len(c.sendCh) != 0 {
		t.Fatal("post-abort producer enqueued")
	}
	requireTransportEnd(t, peer)
}

func TestLossReasonSurvivesLateWriterError(t *testing.T) {
	var log bytes.Buffer
	srv := NewServer(Options{Token: "test-token", Log: slog.New(slog.NewTextHandler(&log, nil))})
	defer srv.Close()
	c, _ := newDirectWSPair(t, srv, 1)
	entered, release, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	c.writeAttempt = func(wsMsg) { close(entered); <-release }
	c.sendCh <- wsMsg{typ: wsBinary, data: []byte("in-flight")}
	go func() { c.writeLoop(); close(done) }()
	t.Cleanup(func() { c.cancel(); unblock(); _ = c.conn.CloseNow(); awaitBoundary(t, done, "writer cleanup") })
	awaitBoundary(t, entered, "writer before transport call")
	c.abortMirrorLoss("alpha", bridge.ErrSubscriberOverflow)
	logSelected, logRelease, teardownDone := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var logOnce sync.Once
	unblockLog := func() { logOnce.Do(func() { close(logRelease) }) }
	c.beforeHealthLog = func() { close(logSelected); <-logRelease }
	go func() { c.teardown(); close(teardownDone) }()
	t.Cleanup(func() { unblockLog(); awaitBoundary(t, teardownDone, "teardown cleanup") })
	awaitBoundary(t, logSelected, "teardown selected loss reason")
	unblock()
	awaitBoundary(t, done, "late writer error")
	if !strings.HasPrefix(c.getCloseReason(), "write_error:") {
		t.Fatalf("late writer error not exercised: %s", c.getCloseReason())
	}
	unblockLog()
	awaitBoundary(t, teardownDone, "health log complete")
	got := log.String()
	if strings.Count(got, "ws: sendq health") != 1 || !strings.Contains(got, "mirror_loss: ref=alpha: bridge: subscriber queue overflow") {
		t.Fatalf("loss attribution missing or duplicated: %s", got)
	}
}

func TestConnMetricsConcurrentSnapshotIsolation(t *testing.T) {
	var a, b ConnMetrics
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := 0; n < 1000; n++ {
				a.recordDrop()
				a.recordSnapshot()
				a.recordResizeSnapshot()
				a.recordSubscribe()
				a.recordFramesSent()
				_ = a.snapshot()
				_ = b.snapshot()
			}
		}()
	}
	wg.Wait()
	got := a.snapshot()
	if got != (ConnMetricsSnapshot{4000, 4000, 4000, 4000, 4000}) || b.snapshot() != (ConnMetricsSnapshot{}) {
		t.Fatalf("per-connection counts leaked: a=%+v b=%+v", got, b.snapshot())
	}
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
		for len(got) < len(want) {
			select {
			case chunk, ok := <-data:
				if !ok {
					t.Fatal("healthy bridge terminated")
				}
				got = append(got, chunk...)
			case <-deadline.C:
				t.Fatal("healthy bridge acknowledgement missing")
			}
		}
		deadline.Stop()
		if !bytes.Equal(got, want) {
			t.Fatalf("healthy record %d differs: got %d want %d", i, len(got), len(want))
		}
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
	emitPacedBridge(t, te, data, 32)
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
	awaitBoundary(t, c.mirrorAbortDone, "published loss abort")
	awaitBoundary(t, done, "capture failure completion")
	assertNoLiveSubscription(t, c)
	if reason := fmt.Sprint(c.mirrorLossReason.Load()); !strings.Contains(reason, "bridge: subscriber queue overflow") {
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
	c.writerTaken = taken
	c.writerGate = release
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
	emitPacedBridge(t, te, data, 32)
	// With the old exclusive sendMu, the relay blocks behind sendMsg and its
	// real bridge queue overflows. The fixed path detects WS loss immediately.
	awaitBoundary(t, c.mirrorAbortDone, "ready relay abort before writer release")
	awaitBoundary(t, producerDone, "cancelled control producer")
	if !strings.Contains(fmt.Sprint(c.mirrorLossReason.Load()), "ws: mirror send queue overflow") {
		t.Fatalf("wrong overflow cause: %v", c.mirrorLossReason.Load())
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
