package api

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func astraRound4Pane(t *testing.T, body string) *tmuxEnv {
	t.Helper()
	path := filepath.Join(t.TempDir(), "source.py")
	if err := os.WriteFile(path, []byte(body), 0700); err != nil {
		t.Fatal(err)
	}
	te := startTmuxEnv(t, "exec python3 "+path)
	waitPaneTitle(t, te, "ASTRA_R4_READY")
	return te
}
func astraRound4Read(t *testing.T, te *tmuxEnv) protocol.BinaryPayload {
	t.Helper()
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
		return frame
	}
}

// Positive control: reproduce the previous exact 300ms delayed redraw model,
// now with the correct oracle and real ESC/CRLF bytes plus source-ready ack.
func TestAstraInitialSubscribeDelayedRedraw(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,time

def redraw(_s,_f):
    time.sleep(0.30)
    os.write(1,b"\x1b[H\x1b[2JDELAYED_REFLOW_FINAL\r\n")
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07PRE_REFLOW_WIDE\r\n")
while True: time.sleep(0.01)
`)
	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	frame := astraRound4Read(t, te)
	if frame.Kind != protocol.KindSnapshot || !bytes.Contains(frame.Data, []byte("DELAYED_REFLOW_FINAL")) {
		t.Fatalf("first snapshot is not the delayed final: %q", frame.Data)
	}
	t.Logf("FIXED: original single delayed redraw present in first snapshot at %v", time.Since(start))
}

// Positive control: capture-window bytes must appear once, while genuinely
// post-release output must still reach the client. The latter is a wire fence.
func TestAstraInitialSnapshotNoOverlap(t *testing.T) {
	te := astraRound4Pane(t, `import os,sys,time
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07")
sys.stdin.readline()
os.write(1,b"CAPTURE_ONCE_MARK\r\n\x1b]0;ASTRA_CAPTURED\x07")
sys.stdin.readline()
os.write(1,b"POST_RELEASE_LIVE\r\n")
while True: time.sleep(0.01)
`)
	c := newDirectWSConn(t, te.wsEnv.srv, 16)
	var triggerOnce sync.Once
	c.snapshotFn = func(ctx context.Context, br *bridge.Pane) ([]byte, error) {
		triggerOnce.Do(func() {
			if err := sendTmuxLine(te, "before"); err != nil {
				t.Fatal(err)
			}
			waitPaneTitle(t, te, "ASTRA_CAPTURED")
		})
		return snapshotWithCursor(ctx, br)
	}
	c.handleSubscribe(protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	defer c.subscribeCancel(te.ref())
	if err := sendTmuxLine(te, "after"); err != nil {
		t.Fatal(err)
	}
	timer := time.NewTimer(4 * time.Second)
	defer timer.Stop()
	snapSeen := false
	var delta []byte
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
				snapSeen = bytes.Contains(frame.Data, []byte("CAPTURE_ONCE_MARK"))
			}
			if frame.Kind == protocol.KindDelta {
				delta = append(delta, frame.Data...)
			}
			if bytes.Contains(delta, []byte("POST_RELEASE_LIVE")) {
				if !snapSeen || bytes.Contains(delta, []byte("CAPTURE_ONCE_MARK")) {
					t.Fatalf("bad cut: snapshot=%v deltas=%q", snapSeen, delta)
				}
				t.Log("FIXED: pre-capture marker only in snapshot; post-release marker delivered normally")
				return
			}
		case <-timer.C:
			t.Fatal("post-release liveness missing")
		}
	}
}

// Regression oracle within the 800ms budget: the first output is not the
// final redraw. Neither a short gap nor the first activity certifies completion.
func TestInitialReflowTwoStageFirstFrame(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,time

def redraw(_s,_f):
    time.sleep(0.25)
    os.write(1,b"\x1b[H\x1b[2JREFLOW_STAGE_ONE\r\n")
    time.sleep(0.35)
    os.write(1,b"\x1b[H\x1b[2JTWO_STAGE_FINAL\r\n")
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07PRE_REFLOW_WIDE\r\n")
while True: time.sleep(0.01)
`)
	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	first := astraRound4Read(t, te)
	if first.Kind != protocol.KindSnapshot || !bytes.Contains(first.Data, []byte("TWO_STAGE_FINAL")) || bytes.Contains(first.Data, []byte("REFLOW_STAGE_ONE")) {
		t.Fatalf("first frame exposes unfinished two-stage redraw: %q", first.Data)
	}
	if got := paneSize(te); got != "46x42" {
		t.Fatalf("final geometry=%s, want 46x42", got)
	}
	assertPaneSnapshot(t, te, first.Data)
	t.Logf("two-stage final in first snapshot at %v", time.Since(start))
}

// Regression oracle: the source keeps updating until the hard cap. Screen
// content must survive exactly once, and normal output must resume afterward.
func TestInitialReflowHardCapNoLoss(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,time

def redraw(_s,_f):
    time.sleep(0.20)
    os.write(1,b"\x1b[H\x1b[2JPOST_CAPTURE_MUST_SURVIVE\r\n")
    for i in range(22):
        os.write(1,("\x1b]0;tick-%d\x07"%i).encode())
        time.sleep(0.05)
    os.write(1,b"LIVENESS_AFTER_CAP\r\n")
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07OLD_CAPTURE_ONLY\r\n")
while True: time.sleep(0.01)
`)
	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	first := astraRound4Read(t, te)
	firstAt := time.Since(start)
	if first.Kind != protocol.KindSnapshot || !bytes.Contains(first.Data, []byte("POST_CAPTURE_MUST_SURVIVE")) {
		t.Fatalf("fresh cap snapshot lost current screen, got %q", first.Data)
	}
	wire := append([]byte{}, first.Data...)
	for {
		f := astraRound4Read(t, te)
		wire = append(wire, f.Data...)
		if bytes.Contains(wire, []byte("LIVENESS_AFTER_CAP")) {
			break
		}
	}
	pane, err := runTmuxCmd(te.env, te.sock, "capture-pane", "-p", "-t", te.paneID)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains([]byte(pane), []byte("POST_CAPTURE_MUST_SURVIVE")) {
		t.Fatal("source marker is not present in real pane")
	}
	if bytes.Count(wire, []byte("POST_CAPTURE_MUST_SURVIVE")) != 1 {
		t.Fatalf("current screen must reach client exactly once: %q", wire)
	}
	t.Logf("fresh cap snapshot at %v; pane marker delivered exactly once through liveness fence at %v", firstAt, time.Since(start))
}
