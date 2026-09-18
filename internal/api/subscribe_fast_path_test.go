package api

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// The harness bound distinguishes the old unconditional 800ms wait from a
// real fast path, allowing CI scheduling overhead. Device tap->glyph <250ms
// remains an independent end-to-end acceptance measurement.
func TestInitialSubscribeSameGeometryAvoidsReflowBudget(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	frame := astraRound4Read(t, te)
	elapsed := time.Since(start)
	if frame.Kind != protocol.KindSnapshot {
		t.Fatalf("first kind=%v, want snapshot", frame.Kind)
	}
	if elapsed >= 400*time.Millisecond {
		t.Fatalf("same geometry waited for reflow budget: %v", elapsed)
	}
	t.Logf("unchanged geometry snapshot: %v", elapsed)
}

func TestInitialSubscribeSplitSynchronizedFrameWaitsForEnd(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,time

def redraw(_s,_f):
    os.write(1,b"\x1b[?20")
    time.sleep(0.015)
    os.write(1,b"26h\x1b[H\x1b[2JINCOMPLETE_FRAME\r\n")
    time.sleep(0.075)
    os.write(1,b"\x1b[H\x1b[2JSPLIT_FRAME_FINAL\r\n\x1b[?202")
    time.sleep(0.015)
    os.write(1,b"6l")
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07OLD_FRAME\r\n")
while True: time.sleep(0.01)
`)
	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	first := astraRound4Read(t, te)
	if first.Kind != protocol.KindSnapshot || !bytes.Contains(first.Data, []byte("SPLIT_FRAME_FINAL")) {
		t.Fatalf("published before complete split frame: %q", first.Data)
	}
	if elapsed := time.Since(start); elapsed >= 400*time.Millisecond {
		t.Fatalf("split synchronization was not recognized: %v", elapsed)
	}
}

// A source already computing an old-width render can finish it after SIGWINCH.
// Its valid 2026 end is not proof that wrapping matches the new pane grid.
func TestInitialSubscribeOldWidthSyncFrameWaitsForCorrectGrid(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,time

def frame(cols,label):
    body = "\x1b[?2026h\x1b[H\x1b[2J"+label+"\r\n"
    body += (("x"*(cols-1))+"\r\n")*8
    os.write(1,(body+"\x1b[?2026l").encode())
def redraw(_s,_f):
    frame(80,"OLD_SOURCE_WIDTH_80")
    time.sleep(0.22)
    frame(os.get_terminal_size(1).columns,"NEW_SOURCE_WIDTH_46")
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07INITIAL_FRAME\r\n")
while True: time.sleep(0.01)
`)
	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	first := astraRound4Read(t, te)
	if first.Kind != protocol.KindSnapshot || !bytes.Contains(first.Data, []byte("NEW_SOURCE_WIDTH_46")) {
		t.Fatalf("old-width synchronized frame exposed: %q", first.Data)
	}
	if elapsed := time.Since(start); elapsed >= 600*time.Millisecond {
		t.Fatalf("valid new-width frame did not take fast path: %v", elapsed)
	}
}

func TestInitialSubscribeWrappedSynchronizedOutputKeepsBoundedFallback(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,time

def redraw(_s,_f):
    os.write(1,b"\x1b[?2026h\x1b[H\x1b[2JLEGITIMATE_WRAP_"+b"x"*120+b"\r\n\x1b[?2026l")
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07INITIAL\r\n")
while True: time.sleep(0.01)
`)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	first := astraRound4Read(t, te)
	if first.Kind != protocol.KindSnapshot || !bytes.Contains(first.Data, []byte("LEGITIMATE_WRAP_")) {
		t.Fatalf("bounded fallback lost legitimate wrapped output: %q", first.Data)
	}
	assertPaneSnapshot(t, te, first.Data)
}

func TestInitialSubscribeUnpairedSyncEndIsNotCompletion(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,time

def redraw(_s,_f):
    os.write(1,b"\x1b[?2026l")
    time.sleep(0.30)
    os.write(1,b"\x1b[H\x1b[2JREAL_LATE_FINAL\r\n")
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07OLD_FRAME\r\n")
while True: time.sleep(0.01)
`)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	first := astraRound4Read(t, te)
	if first.Kind != protocol.KindSnapshot || !bytes.Contains(first.Data, []byte("REAL_LATE_FINAL")) {
		t.Fatalf("a frame begun outside the resize epoch unlocked early: %q", first.Data)
	}
}

func TestReflowSynchronizedOutputEverySplit(t *testing.T) {
	stream := []byte("unrelated\x1b[32m中文\x1b[?2026hframe\x1b[?2026l")
	for split := 0; split <= len(stream); split++ {
		t.Run(fmt.Sprint(split), func(t *testing.T) {
			g := newReflowGate()
			g.begin()
			for _, part := range [][]byte{stream[:split], stream[split:]} {
				g.route(part, func(uint64) { t.Fatal("gate opened before snapshot") }, func() {})
			}
			if g.syncOpen || !g.syncComplete {
				t.Fatalf("lost DEC2026 frame at split %d", split)
			}
			if err := g.waitForReflow(context.Background(), time.Now().Add(time.Second), false); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestReflowRejectKeepsNewerCompletion(t *testing.T) {
	g := newReflowGate()
	g.begin()
	frame := func() { g.route([]byte("\x1b[?2026hframe\x1b[?2026l"), func(uint64) {}, func() {}) }
	frame()
	old := g.completedFrame()
	frame()
	newer := g.completedFrame()
	g.rejectCompletedFrame(old)
	if old == 0 || newer <= old || g.completedFrame() != newer {
		t.Fatal("rejecting a captured old frame erased a newer completion")
	}
	g.rejectCompletedFrame(newer)
	if g.completedFrame() != 0 {
		t.Fatal("rejected frame still unlocks fast path")
	}
}

func TestReflowSynchronizedOutputRequiresCurrentCompleteFrame(t *testing.T) {
	g := newReflowGate()
	g.begin()
	route := func(s string) { g.route([]byte(s), func(uint64) { t.Fatal("gate opened") }, func() {}) }
	route("\x1b[?2026l")
	if g.syncComplete {
		t.Fatal("unpaired end accepted")
	}
	route("\x1b[?2026hfirst\x1b[?2026l\x1b[?2026hsecond incomplete")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := g.waitForReflow(ctx, time.Now().Add(time.Second), false); !errors.Is(err, context.Canceled) {
		t.Fatalf("previous end unlocked an open subsequent frame: %v", err)
	}
	if err := g.waitForReflow(context.Background(), time.Now(), true); !errors.Is(err, errReflowUnstable) {
		t.Fatalf("unterminated frame accepted at hard cap: %v", err)
	}
	route("\x1b[?2026l")
	g.resetSynchronizedOutput()
	if g.syncComplete || g.syncOpen || g.syncPrefix != 0 {
		t.Fatal("pre-resize completion survived epoch reset")
	}
	if err := g.waitForReflow(ctx, time.Now().Add(time.Second), false); !errors.Is(err, context.Canceled) {
		t.Fatalf("stale completion notification unlocked resize: %v", err)
	}
}

// Pi's actual main/alternate renderers bracket a complete output frame with
// DEC 2026. A small complete post-resize frame is stronger evidence than an
// arbitrary short silent interval; the first snapshot must carry real new dims.
func TestInitialSubscribeSmallSynchronizedReflowAvoidsBudget(t *testing.T) {
	te := astraRound4Pane(t, `import os,signal,time

def redraw(_s,_f):
    cols, rows = os.get_terminal_size(1)
    os.write(1,("\x1b[?2026h\x1b[H\x1b[2JPI_FRAME_%d_%d\r\n\x1b[?2026l"%(cols,rows)).encode())
signal.signal(signal.SIGWINCH,redraw)
os.write(1,b"\x1b]0;ASTRA_R4_READY\x07OLD_WIDE_FRAME\r\n")
while True: time.sleep(0.01)
`)
	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 42, Cols: 46})
	first := astraRound4Read(t, te)
	elapsed := time.Since(start)
	if first.Kind != protocol.KindSnapshot || !bytes.Contains(first.Data, []byte("PI_FRAME_46_42")) {
		t.Fatalf("first frame is not the completed phone-width frame: %q", first.Data)
	}
	if elapsed >= 400*time.Millisecond {
		t.Fatalf("completed small frame waited for reflow budget: %v", elapsed)
	}
	t.Logf("completed small synchronized frame: %v", elapsed)
}
