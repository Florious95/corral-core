package api

// resize_readback_health_test.go keeps the same-size resize path honest: no
// snapshot is a valid no-op only when the WebSocket remains usable afterward.
// The hosted PERF17 workflow adds a tmux command observer around this test to
// prove the handler consumes Resize's actual readback instead of issuing a
// second Size command.

import (
	"bytes"
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func markPerf17Stage(t *testing.T, stage string) {
	t.Helper()
	path := os.Getenv("PERF17_STAGE_FILE")
	if path == "" {
		return
	}
	if err := os.WriteFile(path, []byte(stage+"\n"), 0o600); err != nil {
		t.Fatalf("write PERF17 stage %q: %v", stage, err)
	}
}

func resetPerf17Trace(t *testing.T) {
	t.Helper()
	for _, name := range []string{"PERF17_TMUX_TRACE", "PERF17_TMUX_TRACE_ALL", "PERF17_TMUX_COMMANDS"} {
		path := os.Getenv(name)
		if path == "" {
			continue
		}
		if err := os.WriteFile(path, nil, 0o600); err != nil {
			t.Fatalf("reset PERF17 trace %s: %v", name, err)
		}
	}
}

func waitPerf17InputAck(t *testing.T, frames <-chan struct {
	typ  websocket.MessageType
	data []byte
}, readErr <-chan error, marker string, reqID uint32) {
	t.Helper()
	var mirror bytes.Buffer
	var ack *protocol.InputAck
	health := time.NewTimer(5 * time.Second)
	defer health.Stop()
	for ack == nil || !bytes.Contains(mirror.Bytes(), []byte(marker)) {
		select {
		case frame := <-frames:
			if frame.typ == websocket.MessageBinary {
				payload, err := protocol.DecodeBinary(frame.data)
				if err != nil {
					t.Fatalf("decode input-ready binary: %v", err)
				}
				mirror.Write(payload.Data)
				continue
			}
			typed, err := protocol.UnmarshalFrame(frame.data)
			if err != nil {
				t.Fatalf("decode input-ready control: %v", err)
			}
			if candidate, ok := typed.(protocol.InputAck); ok && candidate.ReqID == reqID {
				copy := candidate
				ack = &copy
			}
		case err := <-readErr:
			t.Fatalf("reader closed before input-ready ack: %v", err)
		case <-health.C:
			t.Fatalf("input-ready ack/mirror timeout: marker=%q req_id=%d", marker, reqID)
		}
	}
	if !ack.OK {
		t.Fatalf("input-ready ack failed: %s", ack.Reason)
	}
}

func waitPerf17InputFailureAck(t *testing.T, frames <-chan struct {
	typ  websocket.MessageType
	data []byte
}, readErr <-chan error, reqID uint32) {
	t.Helper()
	deadline := time.NewTimer(5 * time.Second)
	defer deadline.Stop()
	for {
		select {
		case frame := <-frames:
			if frame.typ == websocket.MessageBinary {
				continue
			}
			typed, err := protocol.UnmarshalFrame(frame.data)
			if err != nil {
				t.Fatalf("decode shutdown control: %v", err)
			}
			if candidate, ok := typed.(protocol.InputAck); ok && candidate.ReqID == reqID {
				if candidate.OK {
					t.Fatalf("shutdown input unexpectedly succeeded")
				}
				return
			}
		case err := <-readErr:
			t.Fatalf("reader closed before shutdown ack: %v", err)
		case <-deadline.C:
			t.Fatalf("shutdown input ack timeout: req_id=%d", reqID)
		}
	}
}

// TestSameSizeResizeKeepsConnectionHealthy verifies the no-op resize contract:
// no snapshot repush, followed by a successful input ack and mirror delta on
// the same connection. A missing snapshot must never be treated as proof that
// the connection died or that the request was silently swallowed.
func TestSameSizeResizeKeepsConnectionHealthy(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	frames := make(chan struct {
		typ  websocket.MessageType
		data []byte
	}, 32)
	readErr := make(chan error, 1)
	stop := make(chan struct{})
	t.Cleanup(func() {
		close(stop)
		_ = te.wsEnv.conn.CloseNow()
	})
	go func() {
		for {
			typ, data, err := te.wsEnv.conn.Read(context.Background())
			if err != nil {
				readErr <- err
				return
			}
			select {
			case frames <- struct {
				typ  websocket.MessageType
				data []byte
			}{typ: typ, data: data}:
			case <-stop:
				return
			}
		}
	}()

	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	initial := time.NewTimer(5 * time.Second)
	for {
		select {
		case frame := <-frames:
			if frame.typ != websocket.MessageBinary {
				continue
			}
			payload, err := protocol.DecodeBinary(frame.data)
			if err != nil {
				t.Fatalf("decode subscribe binary: %v", err)
			}
			if payload.Kind == protocol.KindSnapshot {
				if payload.Ref != te.ref() {
					initial.Stop()
					t.Fatalf("subscribe snapshot ref = %q, want %q", payload.Ref, te.ref())
				}
				initial.Stop()
				goto subscribed
			}
		case err := <-readErr:
			initial.Stop()
			t.Fatalf("reader closed before subscribe snapshot: %v", err)
		case <-initial.C:
			t.Fatal("subscribe snapshot timeout")
		}
	}

subscribed:
	// The subscribe handler queues its snapshot before the read loop returns to
	// the next frame. A completed input ack is the explicit barrier that keeps
	// the later command trace limited to this handler's Resize.
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1700, Ref: te.ref(), Text: "PERF17_READY"})
	waitPerf17InputAck(t, frames, readErr, "PERF17_READY", 1700)
	markPerf17Stage(t, "handler")
	resetPerf17Trace(t)
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 24, Cols: 80})
	quiet := time.NewTimer(800 * time.Millisecond)
	for {
		select {
		case frame := <-frames:
			if frame.typ != websocket.MessageBinary {
				continue
			}
			payload, err := protocol.DecodeBinary(frame.data)
			if err != nil {
				quiet.Stop()
				t.Fatalf("decode same-size binary: %v", err)
			}
			if payload.Kind == protocol.KindSnapshot {
				quiet.Stop()
				t.Fatalf("same-size resize re-pushed a snapshot: data=%q", payload.Data)
			}
		case err := <-readErr:
			quiet.Stop()
			t.Fatalf("reader closed during same-size no-snapshot window: %v", err)
		case <-quiet.C:
			goto noSnapshot
		}
	}

noSnapshot:
	const reqID = 1701
	te.wsEnv.sendFrame(&protocol.Input{ReqID: reqID, Ref: te.ref(), Text: "PERF17_HEALTH"})
	var mirror bytes.Buffer
	var ack *protocol.InputAck
	health := time.NewTimer(5 * time.Second)
	for ack == nil || !bytes.Contains(mirror.Bytes(), []byte("PERF17_HEALTH")) {
		select {
		case frame := <-frames:
			if frame.typ == websocket.MessageBinary {
				payload, err := protocol.DecodeBinary(frame.data)
				if err != nil {
					health.Stop()
					t.Fatalf("decode health binary: %v", err)
				}
				if payload.Kind == protocol.KindSnapshot {
					health.Stop()
					t.Fatalf("same-size resize emitted a delayed snapshot while health ack/mirror was pending: %q", payload.Data)
				}
				mirror.Write(payload.Data)
				continue
			}
			typed, err := protocol.UnmarshalFrame(frame.data)
			if err != nil {
				health.Stop()
				t.Fatalf("decode health control: %v", err)
			}
			if candidate, ok := typed.(protocol.InputAck); ok && candidate.ReqID == reqID {
				copy := candidate
				ack = &copy
			}
		case err := <-readErr:
			health.Stop()
			t.Fatalf("reader closed before health ack: %v", err)
		case <-health.C:
			t.Fatal("same-size resize health ack/mirror timeout")
		}
	}
	health.Stop()
	if !ack.OK {
		t.Fatalf("same-size resize left connection unhealthy: input ack=%s", ack.Reason)
	}
	// Exclude the intentional geometry restore from the handler-stage oracle.
	// The deterministic relay-deletion barrier and named not-subscribed ack keep
	// cleanup from importing the B1 ConnMetrics teardown race into this oracle.
	finishPerf17Controlled(t, te, frames, readErr, 1702)
}

func waitPerf17ResizeSnapshot(t *testing.T, te *tmuxEnv, marker string) protocol.BinaryPayload {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read resize snapshot: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode resize binary: %v", err)
		}
		if payload.Kind != protocol.KindSnapshot {
			continue
		}
		if payload.Ref != te.ref() {
			t.Fatalf("resize snapshot ref = %q, want %q", payload.Ref, te.ref())
		}
		if marker != "" && !bytes.Contains(payload.Data, []byte(marker)) {
			t.Fatalf("resize snapshot misses live marker %q: %q", marker, payload.Data)
		}
		assertSnapshotCursorSuffix(t, te, payload.Data)
		return payload
	}
	t.Fatalf("resize snapshot timeout for ref %q", te.ref())
	return protocol.BinaryPayload{}
}

func waitPerf17SessionNotFound(t *testing.T, e *wsEnv) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read resize error: %v", err)
		}
		if typ != websocket.MessageText {
			if typ == websocket.MessageBinary {
				payload, decodeErr := protocol.DecodeBinary(data)
				if decodeErr != nil {
					t.Fatalf("decode unexpected resize binary: %v", decodeErr)
				}
				if payload.Kind == protocol.KindSnapshot {
					t.Fatalf("resize error path unexpectedly emitted snapshot: %q", payload.Data)
				}
			}
			continue
		}
		got, err := protocol.UnmarshalFrame(data)
		if err != nil {
			t.Fatalf("decode resize error frame: %v", err)
		}
		if got.FrameType() != protocol.TypeError {
			continue
		}
		ef := got.(protocol.ErrorFrame)
		if ef.Code != protocol.ErrCodeSessionNotFound {
			t.Fatalf("resize error code = %q, want %q", ef.Code, protocol.ErrCodeSessionNotFound)
		}
		return
	}
	t.Fatal("resize error frame timeout")
}

// waitPerf17RelayDetached cancels the controlled subscription and waits for
// relay's defer to remove it from c.subs. The lock hand-off is intentional:
// relay unlocks subsMu only after its final sendMirror/ConnMetrics write, so
// acquiring that same lock after observing deletion establishes a real Go
// happens-before edge before wsConn.teardown reads connMetrics. External tmux
// pipe state cannot provide that ordering.
func waitPerf17RelayDetached(t *testing.T, te *tmuxEnv) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var c *wsConn
	for time.Now().Before(deadline) {
		te.wsEnv.srv.trackersMu.Lock()
		if len(te.wsEnv.srv.trackers) == 1 {
			for candidate := range te.wsEnv.srv.trackers {
				c = candidate
			}
		}
		te.wsEnv.srv.trackersMu.Unlock()
		if c != nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if c == nil {
		t.Fatal("controlled-readback wsConn was not registered")
	}

	c.subsMu.Lock()
	sub := c.subs[te.ref()]
	c.subsMu.Unlock()
	if sub == nil {
		t.Fatalf("controlled-readback subscription %q was not registered", te.ref())
	}
	// Cancel only: relay's defer is the sole owner that removes the entry and
	// performs teardown. Calling subscribeCancel here would race that defer and
	// would not establish the required final-write-to-teardown ordering.
	sub.cancel()
	for time.Now().Before(deadline) {
		c.subsMu.Lock()
		_, stillSubscribed := c.subs[te.ref()]
		c.subsMu.Unlock()
		if !stillSubscribed {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("controlled-readback relay did not remove subscription before connection close")
}

func finishPerf17Controlled(t *testing.T, te *tmuxEnv, frames <-chan struct {
	typ  websocket.MessageType
	data []byte
}, readErr <-chan error, reqID uint32) {
	t.Helper()
	markPerf17Stage(t, "done")
	waitPerf17RelayDetached(t, te)
	te.wsEnv.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: reqID, Ref: te.ref(), Text: "PERF17_READBACK_SHUTDOWN"})
	waitPerf17InputFailureAck(t, frames, readErr, reqID)
	if err := te.wsEnv.conn.Close(websocket.StatusNormalClosure, "perf17 controlled readback done"); err != nil {
		t.Fatalf("close controlled-readback connection: %v", err)
	}
	select {
	case <-readErr:
	case <-time.After(5 * time.Second):
		t.Fatal("controlled-readback reader did not observe close")
	}
}

// finishPerf17Error performs the same deterministic relay shutdown for the
// error-path tests, which otherwise returned straight into t.Cleanup after a
// bare unsubscribe. The named failed InputAck is sent only after relay's
// subsMu deletion barrier, so the subsequent close cannot race ConnMetrics.
func finishPerf17Error(t *testing.T, te *tmuxEnv, reqID uint32) {
	t.Helper()
	frames := make(chan struct {
		typ  websocket.MessageType
		data []byte
	}, 32)
	readErr := make(chan error, 1)
	stop := make(chan struct{})
	go func() {
		for {
			typ, data, err := te.wsEnv.conn.Read(context.Background())
			if err != nil {
				readErr <- err
				return
			}
			select {
			case frames <- struct {
				typ  websocket.MessageType
				data []byte
			}{typ: typ, data: data}:
			case <-stop:
				return
			}
		}
	}()
	waitPerf17RelayDetached(t, te)
	te.wsEnv.sendFrame(&protocol.Unsubscribe{Ref: te.ref()})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: reqID, Ref: te.ref(), Text: "PERF17_ERROR_SHUTDOWN"})
	waitPerf17InputFailureAck(t, frames, readErr, reqID)
	if err := te.wsEnv.conn.Close(websocket.StatusNormalClosure, "perf17 error-path done"); err != nil {
		t.Fatalf("close error-path connection: %v", err)
	}
	select {
	case <-readErr:
		close(stop)
	case <-time.After(5 * time.Second):
		t.Fatal("error-path reader did not observe close")
	}
}

// TestResizeBeforeReadFailureStillRepushesSnapshot proves a failed before
// Size cannot be treated as a same-size no-op. The hosted tmux seam fails only
// that read, while Resize itself succeeds and returns the real 120x30 size.
func TestResizeBeforeReadFailureStillRepushesSnapshot(t *testing.T) {
	if os.Getenv("PERF17_CONTROL_MODE") != "before-size-failure" || os.Getenv("PERF17_FAILURE_STATE") == "" {
		t.Fatal("PERF17_CONTROL_MODE=before-size-failure and PERF17_FAILURE_STATE are required")
	}
	te := startTmuxEnv(t, "cat")
	defer markPerf17Stage(t, "done")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = readBinary(te.wsEnv, te.ref())
	const marker = "PERF17_BEFORE_SIZE_FAILURE"
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1705, Ref: te.ref(), Text: marker})
	te.waitForMirror(marker)
	markPerf17Stage(t, "handler")
	resetPerf17Trace(t)
	if err := os.WriteFile(os.Getenv("PERF17_FAILURE_STATE"), nil, 0o600); err != nil {
		t.Fatalf("reset before-size failure state: %v", err)
	}
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 30, Cols: 120})
	waitPerf17ResizeSnapshot(t, te, marker)
	if got, err := os.ReadFile(os.Getenv("PERF17_FAILURE_STATE")); err != nil || strings.TrimSpace(string(got)) != "1" {
		t.Fatalf("before-size failure calls = %q, err=%v; want one failed before Size", strings.TrimSpace(string(got)), err)
	}
	markPerf17Stage(t, "done")
	if got := paneSize(te); got != "120x30" {
		t.Fatalf("Resize success geometry = %s, want 120x30", got)
	}
	finishPerf17Error(t, te, 1706)
}

// TestResizeInternalFailureReturnsSessionNotFound proves a failed resize
// command is surfaced by handleResize and does not emit a misleading snapshot.
func TestResizeInternalFailureReturnsSessionNotFound(t *testing.T) {
	if os.Getenv("PERF17_CONTROL_MODE") != "resize-failure" || os.Getenv("PERF17_FAILURE_STATE") == "" {
		t.Fatal("PERF17_CONTROL_MODE=resize-failure and PERF17_FAILURE_STATE are required")
	}
	te := startTmuxEnv(t, "cat")
	defer markPerf17Stage(t, "done")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = readBinary(te.wsEnv, te.ref())
	markPerf17Stage(t, "handler")
	resetPerf17Trace(t)
	if err := os.WriteFile(os.Getenv("PERF17_FAILURE_STATE"), nil, 0o600); err != nil {
		t.Fatalf("reset resize failure state: %v", err)
	}
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 30, Cols: 120})
	waitPerf17SessionNotFound(t, te.wsEnv)
	if got, err := os.ReadFile(os.Getenv("PERF17_FAILURE_STATE")); err != nil || strings.TrimSpace(string(got)) != "1" {
		t.Fatalf("resize failure calls = %q, err=%v; want one failed resize command", strings.TrimSpace(string(got)), err)
	}
	markPerf17Stage(t, "done")
	if got := paneSize(te); got != "80x24" {
		t.Fatalf("failed Resize changed pane geometry to %s, want 80x24", got)
	}
	finishPerf17Error(t, te, 1707)
}

// TestResizeUsesActualReadback proves the no-op decision consumes the value
// returned by Resize rather than the request dimensions or a second Size.
// The hosted tmux wrapper supplies a controlled readback sequence in the real
// handler path: before=80x24, Resize's readback=80x24, and (only on the old
// implementation) the extra Size=120x30. The request is 120x30, so the old
// handler incorrectly reflows while the fixed handler correctly skips it.
func TestResizeUsesActualReadback(t *testing.T) {
	if os.Getenv("PERF17_READBACK_SEQUENCE") == "" || os.Getenv("PERF17_READBACK_STATE") == "" {
		t.Fatal("PERF17_READBACK_SEQUENCE and PERF17_READBACK_STATE are required for this controlled-readback test")
	}
	te := startTmuxEnv(t, "cat")
	frames := make(chan struct {
		typ  websocket.MessageType
		data []byte
	}, 32)
	readErr := make(chan error, 1)
	stop := make(chan struct{})
	t.Cleanup(func() {
		close(stop)
		_ = te.wsEnv.conn.CloseNow()
	})
	go func() {
		for {
			typ, data, err := te.wsEnv.conn.Read(context.Background())
			if err != nil {
				readErr <- err
				return
			}
			select {
			case frames <- struct {
				typ  websocket.MessageType
				data []byte
			}{typ: typ, data: data}:
			case <-stop:
				return
			}
		}
	}()

	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	initial := time.NewTimer(5 * time.Second)
	for {
		select {
		case frame := <-frames:
			if frame.typ != websocket.MessageBinary {
				continue
			}
			payload, err := protocol.DecodeBinary(frame.data)
			if err != nil {
				initial.Stop()
				t.Fatalf("decode controlled-readback subscribe binary: %v", err)
			}
			if payload.Kind == protocol.KindSnapshot {
				if payload.Ref != te.ref() {
					initial.Stop()
					t.Fatalf("controlled-readback subscribe ref = %q, want %q", payload.Ref, te.ref())
				}
				initial.Stop()
				goto subscribed
			}
		case err := <-readErr:
			initial.Stop()
			t.Fatalf("reader closed before controlled-readback subscribe: %v", err)
		case <-initial.C:
			t.Fatal("controlled-readback subscribe snapshot timeout")
		}
	}

subscribed:
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1703, Ref: te.ref(), Text: "PERF17_READBACK_READY"})
	waitPerf17InputAck(t, frames, readErr, "PERF17_READBACK_READY", 1703)
	markPerf17Stage(t, "handler")
	state := os.Getenv("PERF17_READBACK_STATE")
	if err := os.WriteFile(state, nil, 0o600); err != nil {
		t.Fatalf("reset controlled-readback state: %v", err)
	}
	resetPerf17Trace(t)
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 30, Cols: 120})
	quiet := time.NewTimer(800 * time.Millisecond)
	for {
		select {
		case frame := <-frames:
			if frame.typ != websocket.MessageBinary {
				continue
			}
			payload, err := protocol.DecodeBinary(frame.data)
			if err != nil {
				quiet.Stop()
				t.Fatalf("decode controlled-readback binary: %v", err)
			}
			if payload.Kind == protocol.KindSnapshot {
				quiet.Stop()
				// Close the handler stage before reporting the intentional B1
				// failure, so teardown's restore commands cannot pollute the
				// handler readback oracle.
				finishPerf17Controlled(t, te, frames, readErr, 1704)
				t.Fatalf("resize used request/second-size dimensions instead of Resize readback: request=120x30 readback=80x24 snapshot=%q", payload.Data)
			}
		case err := <-readErr:
			quiet.Stop()
			t.Fatalf("reader closed during controlled-readback no-snapshot window: %v", err)
		case <-quiet.C:
			goto noSnapshot
		}
	}

noSnapshot:
	gotState, err := os.ReadFile(state)
	if err != nil {
		t.Fatalf("read controlled-readback state: %v", err)
	}
	if got := strings.TrimSpace(string(gotState)); got != "2" {
		t.Fatalf("controlled readback calls = %q, want 2 (before Size + Resize's internal readback)", got)
	}
	finishPerf17Controlled(t, te, frames, readErr, 1704)
}

// TestResizeUnsubscribedKnownRefIsNoOp verifies that a valid catalog ref with
// no live subscription is ignored and does not touch its pane geometry.
func TestResizeUnsubscribedKnownRefIsNoOp(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	before, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_width}x#{pane_height}")
	if err != nil {
		t.Fatalf("read initial pane size: %v", err)
	}
	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 30, Cols: 120})
	time.Sleep(300 * time.Millisecond)
	after, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_width}x#{pane_height}")
	if err != nil {
		t.Fatalf("read unsubscribed pane size: %v", err)
	}
	if strings.TrimSpace(after) != strings.TrimSpace(before) {
		t.Fatalf("unsubscribed resize changed pane from %q to %q", strings.TrimSpace(before), strings.TrimSpace(after))
	}
}

// TestResizeUnknownRefReturnsError verifies the resize error path stays
// explicit when a client addresses a ref that was never listed. This test is
// intentionally independent of the same-size command-count oracle.
func TestResizeUnknownRefReturnsError(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Resize{Ref: "no-such-session-ref", Rows: 24, Cols: 80})
	got := te.wsEnv.readControl()
	if got.FrameType() != protocol.TypeError {
		t.Fatalf("expected error frame, got %v", got.FrameType())
	}
	errFrame := got.(protocol.ErrorFrame)
	if errFrame.Code != protocol.ErrCodeSessionNotFound {
		t.Errorf("error code = %q, want %q", errFrame.Code, protocol.ErrCodeSessionNotFound)
	}
}
