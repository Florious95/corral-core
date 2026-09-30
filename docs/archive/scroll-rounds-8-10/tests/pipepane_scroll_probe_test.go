//go:build scrollprobe

// pipepane_scroll_probe_test.go: root-cause probe for the 回炉·第7轮 report
// (docs/remote-scroll-pipe-pane-probe.md). Isolated from the default test
// set by the scrollprobe build tag — it never runs in `go test ./...`.
//
// The only channel this probe judges on is the product's own Subscribe
// (pipe-pane -o -> FIFO) byte stream — the exact channel the Android app's
// screen is built from (server/internal/bridge/stream.go, doc.go). It never
// treats capture-pane output as a verdict, only as corroborating evidence
// printed for the human reader.
//
// Claim under test: tmux copy-mode scrolling (copy-mode -e; send-keys -X
// scroll-up -N n) changes what the *tmux client* renders for the pane, but
// writes nothing to the pane's own pty — so a pipe-pane subscriber (which
// only ever sees bytes the pane's running program writes) receives zero
// bytes for a scroll action.
//
// TestScrollStateAndScrollbackDeliverRealViewportContent (added
// feat-remote-scroll-forward v2.1, root-cause fix) is the flip side of the
// probe above: it does NOT contradict the finding — pipe-pane is still, and
// will always be, structurally blind to a scroll (that fact never changes,
// it is why copy-mode was abandoned entirely). What changed is that
// production (internal/api handleScrollWheel) no longer asks pipe-pane to
// carry the result at all. It reads the scrolled viewport directly via
// bridge.Pane.ScrollState + bridge.Pane.Scrollback (capture-pane -S/-E, no
// copy-mode ever entered — docs/remote-scroll-forward-design-v2.md 实测
// C/D) and pushes it to the client as its own SNAPSHOT frame over the
// WebSocket, a channel this probe's harness does not model (that path is
// exercised end-to-end by internal/api/scroll_forward_scenario_test.go and
// scroll_api_test.go). This test pins the primitive the fix is built on, at
// the same bridge-package isolation level as the probe above: the captured
// bytes must be non-empty AND must reflect the scrolled-back viewport
// specifically (first line differs from the live screen's first line) — not
// just "some bytes happened to come back".
package bridge

import (
	"bytes"
	"context"
	"fmt"
	"testing"
	"time"
)

func TestScrollStateAndScrollbackDeliverRealViewportContent(t *testing.T) {
	tt := newTestTMUX(t)
	pane := tt.newPane(t, "sh -c 'seq 1 400; exec sh'")
	ctx := context.Background()

	deadline := time.Now().Add(5 * time.Second)
	for {
		altScreen, historySize, err := pane.ScrollState(ctx)
		if err != nil {
			t.Fatalf("ScrollState: %v", err)
		}
		if altScreen {
			t.Fatal("precondition: bare sh must not report altScreen")
		}
		if historySize > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("timed out waiting for seq output to build scrollback")
		}
		time.Sleep(20 * time.Millisecond)
	}

	_, historySize, err := pane.ScrollState(ctx)
	if err != nil {
		t.Fatalf("ScrollState: %v", err)
	}

	liveScreen, err := pane.Snapshot(ctx)
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}

	offset := historySize
	const height = 24
	start := -offset
	end := start + height - 1
	scrolled, err := pane.Scrollback(ctx, start, end)
	if err != nil {
		t.Fatalf("Scrollback(%d, %d): %v", start, end, err)
	}

	t.Logf("historySize=%d, live screen first line=%q, scrolled-viewport first line=%q",
		historySize, firstLine(liveScreen), firstLine(scrolled))

	if len(bytes.TrimSpace(scrolled)) == 0 {
		t.Fatalf("PROBE DID NOT FLIP: capture-pane -S/-E returned 0 meaningful bytes for a pane with historySize=%d — the fix's core primitive delivers nothing", historySize)
	}
	if bytes.Equal(firstLine(scrolled), firstLine(liveScreen)) {
		t.Fatalf("PROBE DID NOT FLIP: scrolled-viewport content is identical to the live screen (%q) — capture-pane -S/-E did not actually reach further into history, simple non-zero bytes is not enough", firstLine(liveScreen))
	}
	fmt.Printf("PROBE FLIPPED: capture-pane -S/-E (no copy-mode) delivered %d bytes of real scrolled-back content, first line %q, distinct from the live screen's first line %q\n",
		len(scrolled), firstLine(scrolled), firstLine(liveScreen))
}

// firstLine returns the first newline-delimited line of b, or b itself if it
// has none.
func firstLine(b []byte) []byte {
	if i := bytes.IndexByte(b, '\n'); i >= 0 {
		return b[:i]
	}
	return b
}

// drainQuiet reads ch until no new chunk arrives for `quiet`, or `overall`
// elapses. Returns total bytes read and the number of chunks.
func drainQuiet(t *testing.T, ch <-chan []byte, quiet, overall time.Duration) (int, int) {
	t.Helper()
	total, chunks := 0, 0
	deadline := time.After(overall)
	timer := time.NewTimer(quiet)
	defer timer.Stop()
	for {
		select {
		case b, ok := <-ch:
			if !ok {
				return total, chunks
			}
			total += len(b)
			chunks++
			if !timer.Stop() {
				<-timer.C
			}
			timer.Reset(quiet)
		case <-timer.C:
			return total, chunks
		case <-deadline:
			return total, chunks
		}
	}
}

// TestPipePaneReceivesZeroBytesOnCopyModeScroll is the root-cause probe.
//
// Setup: a pane running `seq 1 400` followed by a live shell, so the pane
// has real scrollback beyond the 24-line viewport before we ever touch
// copy-mode. We Subscribe (the product's own channel) and drain the initial
// burst so the baseline is quiet. We then perform the exact tmux sequence
// InjectScroll used (copy-mode -e; send-keys -X -N 10 scroll-up) and drain
// again.
//
// Judgment is solely on Subscribe bytes. capture-pane is queried afterward
// only to print corroborating evidence (does tmux's own rendering show the
// scroll happened) — it is never part of the pass/fail condition.
func TestPipePaneReceivesZeroBytesOnCopyModeScroll(t *testing.T) {
	tt := newTestTMUX(t)
	pane := tt.newPane(t, "sh -c 'seq 1 400; exec sh'")
	ctx := context.Background()

	ch, detach, err := pane.Subscribe(ctx)
	if err != nil {
		t.Fatalf("Subscribe: %v", err)
	}
	defer detach()

	baseline, baseChunks := drainQuiet(t, ch, 500*time.Millisecond, 5*time.Second)
	t.Logf("baseline: %d bytes in %d chunks (seq 1..400 draining through pipe-pane)", baseline, baseChunks)
	if baseline == 0 {
		t.Fatal("probe invalid: baseline pipe-pane traffic was 0 bytes — the FIFO wiring itself is broken, not what we're testing")
	}

	// Corroborating evidence only: capture-pane BEFORE the scroll.
	beforeCapture, _ := tt.run("capture-pane", "-p", "-t", pane.target)

	// The exact tmux sequence InjectScroll (bridge.go, reverted in this
	// worktree) issued for a bare-shell scroll-up.
	if _, err := tt.run("copy-mode", "-e", "-t", pane.target); err != nil {
		t.Fatalf("copy-mode -e: %v", err)
	}
	if _, err := tt.run("send-keys", "-X", "-N", "10", "-t", pane.target, "scroll-up"); err != nil {
		t.Fatalf("send-keys -X scroll-up: %v", err)
	}

	// Corroborating evidence only: capture-pane AFTER the scroll, and the
	// tmux-reported copy-mode state.
	afterCapture, _ := tt.run("capture-pane", "-p", "-t", pane.target)
	inMode := queryFlagProbe(t, tt, pane, "#{pane_in_mode}")

	postScroll, postChunks := drainQuiet(t, ch, 500*time.Millisecond, 3*time.Second)

	t.Logf("post-scroll (product Subscribe channel): %d bytes in %d chunks", postScroll, postChunks)
	t.Logf("pane_in_mode after scroll: %s (tmux confirms copy-mode was entered)", inMode)
	t.Logf("capture-pane changed (corroborating only, NOT the verdict): %v", beforeCapture != afterCapture)

	if postScroll != 0 {
		t.Errorf("PROBE DID NOT HIT: expected 0 bytes on the product Subscribe channel after copy-mode scroll, got %d bytes in %d chunks — leader's suspicion is REFUTED, pipe-pane does emit bytes on scroll", postScroll, postChunks)
	} else {
		fmt.Printf("PROBE HIT: product Subscribe channel received 0 bytes for a copy-mode scroll that tmux itself confirms happened (pane_in_mode=%s, capture-pane changed=%v)\n", inMode, beforeCapture != afterCapture)
	}
}

func queryFlagProbe(t *testing.T, tt *testTMUX, pane *Pane, format string) string {
	t.Helper()
	out, err := tt.run("display-message", "-p", "-t", pane.target, format)
	if err != nil {
		t.Fatalf("display-message %q: %v", format, err)
	}
	return out
}
