package api

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// TestAstraInitialSubscribeDelayedRedraw proves a redraw that starts after
// the first quiet gap is still folded into the first public snapshot.
func TestAstraInitialSubscribeDelayedRedraw(t *testing.T) {
	script := filepath.Join(t.TempDir(), "delayed-redraw.py")
	const body = `#!/usr/bin/env python3
import os
import signal
import time


def redraw(_signum, _frame):
    time.sleep(0.30)
    os.write(1, b"\\x1b[H\\x1b[2JDELAYED_REFLOW_FINAL\\r\\n")


signal.signal(signal.SIGWINCH, redraw)
os.write(1, b"\\x1b]0;ASTRA_READY\\x07PRE_REFLOW_WIDE\\r\\n")
while True:
    time.sleep(0.01)
`
	if err := os.WriteFile(script, []byte(body), 0o700); err != nil {
		t.Fatal(err)
	}

	te := startTmuxEnv(t, "exec python3 "+script)
	time.Sleep(150 * time.Millisecond)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	for {
		typ, data, err := te.wsEnv.conn.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		frame, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatal(err)
		}
		if frame.Kind != protocol.KindSnapshot {
			t.Fatalf("first kind=%v, want snapshot", frame.Kind)
		}
		if !bytes.Contains(frame.Data, []byte("DELAYED_REFLOW_FINAL")) {
			t.Fatalf("first snapshot omitted delayed redraw: %q", frame.Data)
		}
		return
	}
}

// TestAstraInitialSnapshotNoOverlap proves bytes produced during capture are
// represented by the snapshot only, not replayed as a following delta.
func TestAstraInitialSnapshotNoOverlap(t *testing.T) {
	script := filepath.Join(t.TempDir(), "capture-boundary.py")
	const body = `#!/usr/bin/env python3
import os
import sys
import time

os.write(1, b"\\x1b]0;ASTRA_CAPTURE_READY\\x07")
sys.stdin.readline()
os.write(1, b"AFTER_QUIET_MARK\\r\\n\\x1b]0;ASTRA_CAPTURE_WROTE\\x07")
while True:
    time.sleep(0.01)
`
	if err := os.WriteFile(script, []byte(body), 0o700); err != nil {
		t.Fatal(err)
	}

	te := startTmuxEnv(t, "exec python3 "+script)
	time.Sleep(150 * time.Millisecond)
	c := newDirectWSConn(t, te.wsEnv.srv, 16)
	c.snapshotFn = func(ctx context.Context, br *bridge.Pane) ([]byte, error) {
		if err := sendTmuxLine(te, "go"); err != nil {
			return nil, err
		}
		time.Sleep(100 * time.Millisecond)
		return snapshotWithCursor(ctx, br)
	}
	c.handleSubscribe(protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	defer c.subscribeCancel(te.ref())

	var snapshotSeen bool
	deadline := time.After(2 * time.Second)
	for {
		select {
		case msg := <-c.sendCh:
			if msg.typ != wsBinary {
				continue
			}
			frame, err := protocol.DecodeBinary(msg.data)
			if err != nil {
				t.Fatal(err)
			}
			if frame.Kind == protocol.KindSnapshot {
				snapshotSeen = bytes.Contains(frame.Data, []byte("AFTER_QUIET_MARK"))
				if !snapshotSeen {
					t.Fatal("snapshot omitted capture-boundary output")
				}
				continue
			}
			if frame.Kind == protocol.KindDelta && bytes.Contains(frame.Data, []byte("AFTER_QUIET_MARK")) {
				t.Fatal("capture-boundary output replayed after snapshot")
			}
		case <-deadline:
			if !snapshotSeen {
				t.Fatal("initial snapshot was not queued")
			}
			return
		}
	}
}

// TestReflowBarrier keeps a SIGWINCH redraw local to the daemon. The pane emits
// a Cursor-sized burst (>2 MiB), but the client must receive only the converged
// snapshot, not the raw redraw stream.
func TestReflowBarrier(t *testing.T) {
	script := filepath.Join(t.TempDir(), "sigwinch-burst.sh")
	const body = `#!/usr/bin/env python3
import os
import signal
import time


def redraw(_signum, _frame):
    payload = ("\x1b[2K" + ("x" * 1024) + "\r") * 2300
    payload += "\x1b[2KREFLOW_FINAL\r\n"
    data = payload.encode()
    while data:
        written = os.write(1, data)
        data = data[written:]


signal.signal(signal.SIGWINCH, redraw)
while True:
    time.sleep(0.01)
`
	if err := os.WriteFile(script, []byte(body), 0o700); err != nil {
		t.Fatal(err)
	}

	te := startTmuxEnv(t, "exec python3 "+script)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()
	before := activeConnMetrics(t, te.wsEnv)

	te.wsEnv.sendFrame(&protocol.Resize{Ref: te.ref(), Rows: 25, Cols: 81})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var seenSnapshot bool
	var wireSamples []wireSample
	for !seenSnapshot {
		typ, data, err := te.wsEnv.conn.Read(ctx)
		if err != nil {
			t.Fatalf("resize read: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode binary: %v", err)
		}
		wireSamples = append(wireSamples, wireSample{at: time.Now(), bytes: len(data)})
		if payload.Kind == protocol.KindSnapshot {
			if !contains(payload.Data, "REFLOW_FINAL") {
				t.Fatalf("snapshot does not contain final screen marker")
			}
			assertPaneSnapshot(t, te, payload.Data)
			seenSnapshot = true
		}
	}

	// Give the gate's post-capture quiet period a chance to finish. We do not
	// call websocket Read with short-lived contexts: coder/websocket treats a
	// read context deadline as connection-level cancellation.
	time.Sleep(250 * time.Millisecond)
	after := activeConnMetrics(t, te.wsEnv)
	wireBytes := (after.DeltaWireBytes - before.DeltaWireBytes) +
		(after.SnapshotWireBytes - before.SnapshotWireBytes)
	if wireBytes >= 150*1024 {
		t.Fatalf("resize wire bytes = %d, want < 150KiB", wireBytes)
	}
	if peak := peakWindowBytes(wireSamples, 100*time.Millisecond); peak >= 30*1024 {
		t.Fatalf("resize 100ms peak = %d, want < 30KiB", peak)
	}
	if after.ReflowDiscardedBytes-before.ReflowDiscardedBytes < 2*1024*1024 {
		t.Fatalf("discarded bytes = %d, want at least 2MiB", after.ReflowDiscardedBytes-before.ReflowDiscardedBytes)
	}
	if after.ReflowEpochs-before.ReflowEpochs != 1 {
		t.Fatalf("reflow epochs = %d, want 1", after.ReflowEpochs-before.ReflowEpochs)
	}
}

// TestInitialSubscribeReflowBarrier proves the first subscribe snapshot is
// captured only after the phone resize burst has been drained locally. The
// client must never receive the pre-reflow wide frame or its raw redraw bytes.
func TestInitialSubscribeReflowBarrier(t *testing.T) {
	script := filepath.Join(t.TempDir(), "sigwinch-burst.sh")
	const body = `#!/usr/bin/env python3
import os
import signal
import time


def redraw(_signum, _frame):
    payload = ("\x1b[2K" + ("x" * 1024) + "\r") * 2300
    payload += "\x1b[2KREFLOW_FINAL\r\n"
    data = payload.encode()
    while data:
        written = os.write(1, data)
        data = data[written:]


signal.signal(signal.SIGWINCH, redraw)
while True:
    time.sleep(0.01)
`
	if err := os.WriteFile(script, []byte(body), 0o700); err != nil {
		t.Fatal(err)
	}

	te := startTmuxEnv(t, "exec python3 "+script)
	before := activeConnMetrics(t, te.wsEnv)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	typ, data, err := te.wsEnv.conn.Read(context.Background())
	if err != nil {
		t.Fatalf("initial subscribe read: %v", err)
	}
	if typ != websocket.MessageBinary {
		t.Fatalf("initial frame type = %v, want binary", typ)
	}
	payload, err := protocol.DecodeBinary(data)
	if err != nil {
		t.Fatalf("decode initial snapshot: %v", err)
	}
	if payload.Kind != protocol.KindSnapshot {
		t.Fatalf("initial frame kind = %v, want snapshot", payload.Kind)
	}
	if !contains(payload.Data, "REFLOW_FINAL") {
		t.Fatalf("initial snapshot does not contain final screen marker")
	}
	assertPaneSnapshot(t, te, payload.Data)

	time.Sleep(250 * time.Millisecond)
	after := activeConnMetrics(t, te.wsEnv)
	wireBytes := (after.DeltaWireBytes - before.DeltaWireBytes) +
		(after.SnapshotWireBytes - before.SnapshotWireBytes)
	if wireBytes >= 150*1024 {
		t.Fatalf("initial subscribe wire bytes = %d, want < 150KiB", wireBytes)
	}
	if after.ReflowDiscardedBytes-before.ReflowDiscardedBytes < 2*1024*1024 {
		t.Fatalf("initial subscribe discarded bytes = %d, want at least 2MiB", after.ReflowDiscardedBytes-before.ReflowDiscardedBytes)
	}
	if after.ReflowEpochs-before.ReflowEpochs != 1 {
		t.Fatalf("initial subscribe reflow epochs = %d, want 1", after.ReflowEpochs-before.ReflowEpochs)
	}
}

// TestReflowBarrierDoesNotDropNormalDelta is the non-resize control group:
// ordinary interactive output remains an immediate delta and does not consume
// the reflow discard budget.
func TestReflowBarrierDoesNotDropNormalDelta(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	_ = te.readBinaryFrame()
	before := activeConnMetrics(t, te.wsEnv)

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 91, Ref: te.ref(), Text: "NORMAL_DELTA_MARK"})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 92, Ref: te.ref(), Text: ""})
	te.waitForMirror("NORMAL_DELTA_MARK")

	after := activeConnMetrics(t, te.wsEnv)
	if after.ReflowDiscardedBytes != before.ReflowDiscardedBytes {
		t.Fatalf("normal output discarded %d bytes", after.ReflowDiscardedBytes-before.ReflowDiscardedBytes)
	}
	if after.DeltaWireBytes <= before.DeltaWireBytes {
		t.Fatalf("normal output did not produce delta bytes: before=%d after=%d", before.DeltaWireBytes, after.DeltaWireBytes)
	}
}

func activeConnMetrics(t *testing.T, e *wsEnv) ConnMetricsSnapshot {
	t.Helper()
	e.srv.trackersMu.Lock()
	defer e.srv.trackersMu.Unlock()
	for c := range e.srv.trackers {
		return c.connMetrics.snapshot()
	}
	t.Fatal("no active websocket connection")
	return ConnMetricsSnapshot{}
}

type wireSample struct {
	at    time.Time
	bytes int
}

func peakWindowBytes(samples []wireSample, window time.Duration) int {
	peak := 0
	for i := range samples {
		total := 0
		for j := i; j < len(samples) && samples[j].at.Sub(samples[i].at) <= window; j++ {
			total += samples[j].bytes
		}
		if total > peak {
			peak = total
		}
	}
	return peak
}

func assertPaneSnapshot(t *testing.T, te *tmuxEnv, got []byte) {
	t.Helper()
	raw, err := runTmuxCmd(te.env, te.sock, "capture-pane", "-e", "-p", "-t", te.paneID)
	if err != nil {
		t.Fatalf("capture pane: %v", err)
	}
	cursor, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{cursor_x},#{cursor_y}")
	if err != nil {
		t.Fatalf("cursor position: %v", err)
	}
	var x, y int
	if _, err := fmt.Sscanf(cursor, "%d,%d", &x, &y); err != nil {
		t.Fatalf("parse cursor %q: %v", cursor, err)
	}
	cup := fmt.Sprintf("\x1b[%d;%dH", y+1, x+1)
	want := append(bytes.TrimRight([]byte(raw), "\r\n"), []byte(cup)...)
	if len(got) < len(cup) {
		t.Fatalf("snapshot shorter than cursor suffix: got=%d suffix=%d", len(got), len(cup))
	}
	gotBody := got[:len(got)-len(cup)]
	wantBody := want[:len(want)-len(cup)]
	if !bytes.Equal(bytes.TrimRight(gotBody, "\r\n"), wantBody) {
		t.Fatalf("snapshot differs from capture-pane: got=%d want=%d got_tail=%q want_tail=%q", len(got), len(want), tail(got, 80), tail(want, 80))
	}
}

func tail(data []byte, n int) []byte {
	if len(data) <= n {
		return data
	}
	return data[len(data)-n:]
}

func contains(data []byte, want string) bool {
	for i := 0; i+len(want) <= len(data); i++ {
		if string(data[i:i+len(want)]) == want {
			return true
		}
	}
	return false
}
