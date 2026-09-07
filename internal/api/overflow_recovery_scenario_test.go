package api

// overflow_recovery_test.go freezes the Issue #16 transport scenario at the
// real API boundary.  The source is a real isolated tmux pane, both peers use
// the production WebSocket handler, and the recovery peer performs the same
// auth -> list -> subscribe sequence an App client uses after a dead socket.
//
// This is intentionally not an App acceptance test: it proves the server-side
// loss/abort and replay wire contract.  A6 still needs the real Android
// Connection/Manager/VM/TerminalEmulator chain in a separate hosted run.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

const (
	// These tokens are emitted as OSC title updates.  They are observable on
	// the raw pipe stream but do not change the visible pane grid, so the final
	// screen oracle remains static and complete.
	recoveryReadyToken = "P16_RECOVERY_READY"
	recoveryAfterToken = "P16_RECOVERY_AFTER"

	// A 16 MiB source burst crosses both bounded relay stages on a peer that is
	// not read: bridge subscriber buffering and the per-connection send queue.
	// The test never treats this byte count as evidence of overflow; the
	// post-burst OSC barrier and the observed dead socket are the evidence.
	recoveryBurstBytes = 16 << 20
)

// recoverySnapshotOracle is the complete static screen expected after the
// burst's clear-and-redraw.  tmux capture-pane -e canonicalizes SGR order and
// drops the trailing blank rows; snapshotWithCursor then appends the fixed
// cursor anchor (x=25,y=6, represented as CUP row 7 column 26).
func recoverySnapshotOracle() []byte {
	const screen = "\x1b[1m\x1b[34mRECOVERED TITLE\x1b[0m\n" +
		"\x1b[3;5m日本語 ✓\x1b[0m\n" +
		"\x1b[1;5mCURSOR_ORACLE\x1b[0m\n\n\n\n" +
		"            RECOVERY_DONE"
	return []byte(screen + "\x1b[7;26H")
}

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

type mirrorNotice struct {
	marker string
	err    error
}

// observeMirror continuously drains the healthy peer.  It keeps only a small
// suffix because the source burst is deliberately large; marker matching is
// chunk-boundary safe and uses observed bytes, never a guessed read count.
func observeMirror(ctx context.Context, e *wsEnv, ref string, markers []string, notices chan<- mirrorNotice) {
	seen := make(map[string]bool, len(markers))
	tail := make([]byte, 0, 256)
	for {
		readCtx, cancel := context.WithTimeout(ctx, 750*time.Millisecond)
		typ, data, err := e.conn.Read(readCtx)
		cancel()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if errors.Is(err, context.DeadlineExceeded) {
				continue
			}
			notices <- mirrorNotice{err: err}
			return
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			notices <- mirrorNotice{err: fmt.Errorf("decode healthy mirror: %w", err)}
			return
		}
		if payload.Ref != ref {
			notices <- mirrorNotice{err: fmt.Errorf("healthy mirror ref=%q, want %q", payload.Ref, ref)}
			return
		}
		tail = append(tail, payload.Data...)
		if len(tail) > 256 {
			tail = tail[len(tail)-256:]
		}
		for _, marker := range markers {
			if !seen[marker] && bytes.Contains(tail, []byte(marker)) {
				seen[marker] = true
				notices <- mirrorNotice{marker: marker}
			}
		}
	}
}

func waitMirrorMarker(t *testing.T, notices <-chan mirrorNotice, marker string, timeout time.Duration) {
	t.Helper()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		select {
		case notice := <-notices:
			if notice.err != nil {
				t.Fatalf("healthy client failed before %s: %v", marker, notice.err)
			}
			if notice.marker == marker {
				return
			}
		case <-timer.C:
			t.Fatalf("healthy client never observed %s", marker)
		}
	}
}

// waitSlowDisconnect starts reading only after the source barrier has arrived.
// Thus the peer is genuinely slow while both bounded send stages are tested;
// old code drains its queue into this reader and then remains open until the
// deadline, which is the deterministic red assertion.
func waitSlowDisconnect(conn *websocket.Conn, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	for {
		if _, _, err := conn.Read(ctx); err != nil {
			if ctx.Err() != nil {
				return fmt.Errorf("slow connection remained open: %w", err)
			}
			return nil
		}
	}
}

func readListingFor(t *testing.T, e *wsEnv, reqID uint32) protocol.Listing {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("reconnect list read: %v", err)
		}
		if typ != websocket.MessageText {
			continue
		}
		typed, err := protocol.UnmarshalFrame(data)
		if err != nil {
			t.Fatalf("decode reconnect list frame: %v", err)
		}
		listing, ok := typed.(protocol.Listing)
		if ok && listing.ReqID == reqID {
			return listing
		}
	}
	t.Fatalf("listing req_id=%d never arrived", reqID)
	return protocol.Listing{}
}

func readSnapshotFor(t *testing.T, e *wsEnv, ref string) protocol.BinaryPayload {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Until(deadline))
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("reconnect subscribe read: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode reconnect binary: %v", err)
		}
		if payload.Ref != ref {
			t.Fatalf("reconnect binary ref=%q, want %q", payload.Ref, ref)
		}
		if payload.Kind != protocol.KindSnapshot {
			t.Fatalf("reconnect first binary kind=%d, want snapshot", payload.Kind)
		}
		return payload
	}
	t.Fatal("reconnect snapshot never arrived")
	return protocol.BinaryPayload{}
}

// TestOverflowDisconnectReplayStaticOracle exercises the server-only half of
// S+A6 against real tmux and real WebSocket clients:
//
//  1. A slow subscribed peer receives no reads while one source burst crosses
//     both bounded fanout stages; a healthy peer drains continuously.
//  2. The slow peer must be forcibly terminated rather than silently frozen.
//  3. A fresh peer performs auth, List, and Subscribe and receives exactly the
//     complete static screen/cursor oracle, with no pre-snapshot stale delta.
//  4. The healthy peer remains live and receives output after recovery.
//
// This test deliberately does not claim Android recovery: the production App
// chain and A6 composition remain a separate acceptance obligation.
func TestOverflowDisconnectReplayStaticOracle(t *testing.T) {
	te := startTmuxEnv(t, "bash")
	ref := te.ref()

	// Slow peer: consume only the initial snapshot.  It becomes the controlled
	// backpressure endpoint once the source barrier is triggered.
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
	initial := te.readBinaryFrame()
	if initial.Kind != protocol.KindSnapshot {
		t.Fatalf("slow peer initial kind=%d, want snapshot", initial.Kind)
	}

	// Healthy peer shares the production bridge fanout but drains every frame.
	healthy := dialSameServer(t, te.wsEnv)
	healthy.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
	healthyInitial := readBinaryOn(t, healthy)
	if healthyInitial.Kind != protocol.KindSnapshot {
		t.Fatalf("healthy peer initial kind=%d, want snapshot", healthyInitial.Kind)
	}

	observeCtx, stopObserve := context.WithCancel(context.Background())
	t.Cleanup(stopObserve)
	notices := make(chan mirrorNotice, 8)
	go observeMirror(observeCtx, healthy, ref, []string{recoveryReadyToken, recoveryAfterToken}, notices)

	// The source completion barrier is an observed terminal byte after the
	// burst and final redraw, not a fixed number of pipe reads or WebSocket
	// writes.  The command stays alive so the static screen cannot disappear.
	if err := sendTmuxLine(te, recoveryBurstCommand()); err != nil {
		t.Fatalf("trigger controlled source burst: %v", err)
	}
	waitMirrorMarker(t, notices, recoveryReadyToken, 30*time.Second)

	metrics := te.wsEnv.srv.sendQueue.Snapshot()
	t.Logf("overflow source barrier observed: queue_peak=%d deltas_dropped=%d frames_sent=%d", metrics.QueuePeak, metrics.DeltasDropped, metrics.FramesSent)

	if err := waitSlowDisconnect(te.wsEnv.conn, 20*time.Second); err != nil {
		t.Fatal(err)
	}

	// Reconnect from a fresh protocol peer.  Explicitly require the sequence
	// auth -> list -> subscribe instead of asserting a future server helper.
	url := "ws" + strings.TrimPrefix(te.wsEnv.hsrv.URL, "http") + "/ws"
	conn, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		t.Fatalf("dial recovery peer: %v", err)
	}
	recovered := &wsEnv{t: t, srv: te.wsEnv.srv, hsrv: te.wsEnv.hsrv, conn: conn}
	t.Cleanup(func() { _ = conn.CloseNow() })
	recovered.auth()
	recovered.sendFrame(&protocol.List{ReqID: 416})
	listing := readListingFor(t, recovered, 416)
	found := false
	for _, ws := range listing.Workspaces {
		for _, session := range ws.Sessions {
			if session.Ref == ref {
				found = true
			}
		}
	}
	if !found {
		t.Fatalf("reconnect listing omitted current ref %q", ref)
	}
	recovered.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
	snapshot := readSnapshotFor(t, recovered, ref)
	if want := recoverySnapshotOracle(); !bytes.Equal(snapshot.Data, want) {
		t.Fatalf("reconnect snapshot differs from complete static oracle:\n got  %q\n want %q", snapshot.Data, want)
	}

	// Release the still-live source process only after replay has been checked;
	// the OSC marker leaves the visible grid untouched and proves the healthy
	// subscription survived the other client's forced termination.
	if err := sendTmuxLine(te, "release"); err != nil {
		t.Fatalf("release source after replay: %v", err)
	}
	waitMirrorMarker(t, notices, recoveryAfterToken, 10*time.Second)
}
