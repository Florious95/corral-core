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
	"crypto/sha256"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
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

	// Legacy capture-window tests use this burst to provoke production loss.
	// The scenario below instead controls each queue stage independently.
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

// TestOverflowDisconnectReplayStaticOracle runs each queue stage separately.
// Every controlled payload byte is compared and hashed on the healthy peer;
// a frozen relay or writer forces the target stage, and its exact loss reason
// must be observed before transport termination and static snapshot replay.
// This is still a server protocol peer, not App A6 acceptance.
func TestOverflowDisconnectReplayStaticOracle(t *testing.T) {
	for _, stage := range []string{"bridge", "ws"} {
		t.Run(stage, func(t *testing.T) {
			// FINAL redraws a known screen without output afterward. Numbered records
			// are OSC-only and cannot alter that static screen or its cursor.
			command := `stty -echo -onlcr; python3 -u -c 'import sys
for line in sys.stdin:
 if line.strip()=="FINAL":
  sys.stdout.write("\033[2J\033[H\033[1;34mRECOVERED TITLE\033[0m\r\n\033[3;5m日本語 ✓\033[0m\r\n\033[5;1mCURSOR_ORACLE\033[0m\033[7;13HRECOVERY_DONE")
 else:
  sys.stdout.write("\033]0;P16_%04d_"%int(line)+"abcdefgh"*64+"\007")
 sys.stdout.flush()'`
			te := startTmuxEnv(t, command)
			ref := te.ref()
			// The first connection is already authenticated. All hooks are installed
			// before the additional slow connection starts its reader/writer goroutines.
			slowConn := make(chan *wsConn, 1)
			relayEntered := make(chan *subscription, 1)
			writerEntered := make(chan struct{})
			forwarded := make(chan struct{}, 1)
			release := make(chan struct{})
			var releaseOnce, writerOnce sync.Once
			unblock := func() { releaseOnce.Do(func() { close(release) }) }
			var accepted atomic.Int64
			te.wsEnv.srv.connInit = func(c *wsConn) {
				if accepted.Add(1) != 1 {
					return
				}
				if stage == "bridge" {
					c.beforeRelay = func(sub *subscription) { relayEntered <- sub; <-release }
				}
				if stage == "ws" {
					c.mirrorForwarded = func() { forwarded <- struct{}{} }
					c.beforeWriterFrame = func(m wsMsg) {
						if m.typ != wsBinary {
							return
						}
						p, err := protocol.DecodeBinary(m.data)
						if err == nil && p.Kind == protocol.KindDelta {
							writerOnce.Do(func() { close(writerEntered); <-release })
						}
					}
				}
				slowConn <- c
			}
			slow := dialSameServer(t, te.wsEnv)
			var target *wsConn
			select {
			case target = <-slowConn:
			case <-time.After(5 * time.Second):
				t.Fatal("slow connection missing")
			}
			t.Cleanup(func() { target.cancel(); unblock(); _ = target.conn.CloseNow() })
			slow.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
			readSnapshotFor(t, slow, ref)
			var heldSub *subscription
			if stage == "bridge" {
				select {
				case heldSub = <-relayEntered:
				case <-time.After(5 * time.Second):
					t.Fatal("relay gate missing")
				}
			}
			healthy := te.wsEnv
			healthy.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
			readSnapshotFor(t, healthy, ref)

			// One continuous Read context for the whole observation. Stage waits live
			// outside Read; no timed-out websocket is ever reused as a polling device.
			observeCtx, stopObserve := context.WithCancel(context.Background())
			chunks := make(chan []byte, 4)
			readErrors := make(chan error, 1)
			observerDone := make(chan struct{})
			go func() {
				defer close(observerDone)
				for {
					typ, wire, err := healthy.conn.Read(observeCtx)
					if err != nil {
						if observeCtx.Err() == nil {
							readErrors <- err
						}
						return
					}
					if typ != wsBinary {
						frame, err := protocol.UnmarshalFrame(wire)
						if err != nil {
							readErrors <- err
							return
						}
						if _, ok := frame.(protocol.ErrorFrame); ok {
							readErrors <- fmt.Errorf("healthy subscription ended: %v", frame)
							return
						}
						continue
					}
					p, err := protocol.DecodeBinary(wire)
					if err != nil {
						readErrors <- err
						return
					}
					if p.Ref != ref || p.Kind != protocol.KindDelta {
						readErrors <- fmt.Errorf("unexpected healthy frame ref=%q kind=%d", p.Ref, p.Kind)
						return
					}
					select {
					case chunks <- p.Data:
					case <-observeCtx.Done():
						return
					}
				}
			}()
			t.Cleanup(func() { stopObserve(); awaitBoundary(t, observerDone, "healthy observer cleanup") })
			gotHash, wantHash := sha256.New(), sha256.New()
			total := 0
			expect := func(want []byte) {
				t.Helper()
				var got []byte
				timer := time.NewTimer(5 * time.Second)
				defer timer.Stop()
				for len(got) < len(want) {
					select {
					case b := <-chunks:
						got = append(got, b...)
					case err := <-readErrors:
						t.Fatalf("healthy observer: %v", err)
					case <-timer.C:
						t.Fatalf("healthy stream incomplete: got %d want %d", len(got), len(want))
					}
				}
				if !bytes.Equal(got, want) {
					t.Fatalf("healthy stream mismatch at byte %d: got=%d want=%d", total, len(got), len(want))
				}
				gotHash.Write(got)
				wantHash.Write(want)
				total += len(got)
			}
			n := 32
			if stage == "ws" {
				n = 270
			}
			for i := 0; i < n; i++ {
				if err := sendTmuxLine(te, fmt.Sprint(i)); err != nil {
					t.Fatal(err)
				}
				expect(pacedRecord(i))
				if stage == "ws" {
					select {
					case <-forwarded:
					case <-target.mirrorAbortDone:
					case <-time.After(5 * time.Second):
						t.Fatal("slow relay did not acknowledge enqueue")
					}
				}
				if stage == "ws" && i == 0 {
					awaitBoundary(t, writerEntered, "writer holds first delta")
				}
			}
			cause := "ws: mirror send queue overflow"
			if stage == "bridge" {
				if len(heldSub.loss) != 1 {
					t.Fatalf("bridge loss not committed: buffered=%d", len(heldSub.loss))
				}
				cause = "bridge: subscriber queue overflow"
				unblock()
			}
			awaitBoundary(t, target.mirrorAbortDone, "stage-specific abort")
			if reason := fmt.Sprint(target.mirrorLossReason.Load()); !strings.Contains(reason, cause) {
				t.Fatalf("wrong stage attribution: got %s want %s", reason, cause)
			}
			if err := waitSlowDisconnect(slow.conn, 5*time.Second); err != nil {
				t.Fatal(err)
			}
			unblock()
			final := []byte("\x1b[2J\x1b[H\x1b[1;34mRECOVERED TITLE\x1b[0m\r\n\x1b[3;5m日本語 ✓\x1b[0m\r\n\x1b[5;1mCURSOR_ORACLE\x1b[0m\x1b[7;13HRECOVERY_DONE")
			if err := sendTmuxLine(te, "FINAL"); err != nil {
				t.Fatal(err)
			}
			expect(final)

			recovered := dialSameServer(t, healthy)
			recovered.sendFrame(&protocol.List{ReqID: 416})
			listing := readListingFor(t, recovered, 416)
			found := false
			for _, workspace := range listing.Workspaces {
				for _, session := range workspace.Sessions {
					if session.Ref == ref {
						found = true
					}
				}
			}
			if !found {
				t.Fatal("reconnect listing omitted current ref")
			}
			recovered.sendFrame(&protocol.Subscribe{Ref: ref, Rows: 24, Cols: 80})
			snapshot := readSnapshotFor(t, recovered, ref)
			if want := recoverySnapshotOracle(); !bytes.Equal(snapshot.Data, want) {
				t.Fatalf("static screen/cursor differs: got %q want %q", snapshot.Data, want)
			}
			// The healthy original subscription must still receive the entire next
			// record without auth, re-subscribe or a replacement snapshot.
			if err := sendTmuxLine(te, "999"); err != nil {
				t.Fatal(err)
			}
			expect(pacedRecord(999))
			if !bytes.Equal(gotHash.Sum(nil), wantHash.Sum(nil)) {
				t.Fatal("healthy hash differs")
			}
			t.Logf("stage=%s cause=%s healthy_bytes=%d healthy_sha256=%x", stage, cause, total, gotHash.Sum(nil))
		})
	}
}
