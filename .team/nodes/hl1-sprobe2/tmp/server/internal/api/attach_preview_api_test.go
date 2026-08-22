package api

// attach_preview_api_test.go: real-tmux red tests for the requirement-057
// two-step attach flow at the API layer — AttachPreview + Input routing
// through consumeAttachPreview, against an isolated tmux `cat` pane (never
// the production daemon or user tmux).
//
// T1: AttachPreview unsubscribed → ErrorFrame, matches ScrollWheel's pattern
// T2: AttachPreview dead pane → ErrorFrame
// T3: AttachPreview success → path lands in the mirror stream, no ack frame
// T4: Input with a matching preview does NOT re-paste (mirror shows the path
//     exactly once) and returns ok — this is the differentiator that proves
//     InjectAfterPreview ran instead of InjectWithAttachment's full path
// T5: Input with a path that was never previewed DOES paste (fallback,
//     mirror shows the path) — requirement 057's compatibility retreat
// T6: timing — preview then a long enough gap before send is near-instant;
//     preview then an immediate send pays close to the full settle delay.
//     This is the judge leader raised: normal path must be ~zero, not "a
//     little wait", and only the picked-then-immediately-sent edge case pays.

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestAttachPreviewUnsubscribedReturnsError(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.AttachPreview{Ref: te.ref(), Path: "/host/img.png"})

	env := readNextControl(t, te.wsEnv)
	if env.Type != string(protocol.TypeError) {
		t.Fatalf("expected %q frame, got %q", protocol.TypeError, env.Type)
	}
	var ef protocol.ErrorFrame
	if err := json.Unmarshal(env.Payload, &ef); err != nil {
		t.Fatalf("decode ErrorFrame: %v", err)
	}
	if ef.Code != protocol.ErrCodeSessionNotFound {
		t.Errorf("error code = %q, want %q", ef.Code, protocol.ErrCodeSessionNotFound)
	}
}

func TestAttachPreviewDeadPaneReturnsError(t *testing.T) {
	te := startTmuxEnv(t, "sh")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot

	runTmuxCmd(te.env, te.sock, "kill-pane", "-t", te.paneID)
	time.Sleep(100 * time.Millisecond)

	te.wsEnv.sendFrame(&protocol.AttachPreview{Ref: te.ref(), Path: "/host/img.png"})
	env := readNextControl(t, te.wsEnv)
	if env.Type != string(protocol.TypeError) {
		t.Fatalf("expected %q frame after dead-pane preview, got %q", protocol.TypeError, env.Type)
	}
}

func TestAttachPreviewPastesIntoMirrorWithNoAck(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot

	te.wsEnv.sendFrame(&protocol.AttachPreview{Ref: te.ref(), Path: "/host/PREVIEW_MARKER_1.png"})
	// No ack expected — same doctrine as ScrollWheel. The positive proof is
	// the path actually landing in the pane via the mirror stream.
	te.waitForMirror("PREVIEW_MARKER_1.png")
}

// TestInputWithMatchingPreviewSkipsRepaste is the functional half of the
// no-repaste proof: ack ok, and the caption lands next to the already-pasted
// path in the pane. It deliberately does NOT try to prove "no repaste
// happened" by counting substring occurrences in the `cat` pane — `cat` runs
// in cooked-mode pty local echo AND copies completed lines to its own
// stdout, so a single correctly-handled paste+Enter already shows the path
// twice on its own (verified manually: baseline right after AttachPreview,
// before any Enter, is 1 occurrence; after Input's Enter it becomes 2, with
// or without a repaste bug — occurrence counting cannot tell them apart on a
// `cat` pane). The actual "does InjectAfterPreview call pasteViaBuffer" proof
// belongs at the bridge layer with a fake-tmux argv assertion (see
// TestInjectAfterPreviewNeverPastes in inject_attachment_test.go), which is
// the mechanism-level check this needs, not a screen-content inference.
func TestInputWithMatchingPreviewSkipsRepaste(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot

	const path = "/host/MATCH_MARKER_2.png"
	te.wsEnv.sendFrame(&protocol.AttachPreview{Ref: te.ref(), Path: path})
	te.waitForMirror("MATCH_MARKER_2.png")

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "caption after preview", AttachmentPath: path})
	// finishAfterPaste types text BEFORE the settle wait, so the caption lands
	// on the mirror stream well before the ack (which waits out the ~2s
	// remainder first) — check the mirror first so this read never races the
	// ack frame arriving and being discarded by the wrong drain helper.
	te.waitForMirror("caption after preview")
	ack := te.wsEnv.readControlDraining()
	ia := ack.(protocol.InputAck)
	if !ia.OK {
		t.Fatalf("input_ack not ok: %s", ia.Reason)
	}
}

func TestInputWithoutMatchingPreviewFallsBackToFullPaste(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot

	// No AttachPreview call at all — an older client, or one that skipped the
	// preview step. The compatibility fallback must still paste it. The paste
	// (and the caption text) land on the mirror well before the ack, which
	// waits out the full settle delay first — check the mirror first so this
	// read never races the ack frame arriving and being discarded by the
	// wrong drain helper.
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "no preview caption", AttachmentPath: "/host/FALLBACK_MARKER_3.png"})
	te.waitForMirror("FALLBACK_MARKER_3.png")
	ack := te.wsEnv.readControlDraining()
	ia := ack.(protocol.InputAck)
	if !ia.OK {
		t.Fatalf("input_ack not ok: %s", ia.Reason)
	}
}

func TestInputWithMismatchedPreviewPathFallsBackToFullPaste(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot

	te.wsEnv.sendFrame(&protocol.AttachPreview{Ref: te.ref(), Path: "/host/OLD_MARKER_4.png"})
	te.waitForMirror("OLD_MARKER_4.png")

	// Send confirms a DIFFERENT path than what was previewed (the user swapped
	// the image) — must not reuse the stale timestamp, must paste the new one.
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "swapped", AttachmentPath: "/host/NEW_MARKER_5.png"})
	// Same ordering note as TestInputWithMatchingPreviewSkipsRepaste: the
	// pasted path (and then the text) lands on the mirror well before the
	// ack, which waits out the settle delay first.
	te.waitForMirror("NEW_MARKER_5.png")
	ack := te.wsEnv.readControlDraining()
	ia := ack.(protocol.InputAck)
	if !ia.OK {
		t.Fatalf("input_ack not ok: %s", ia.Reason)
	}
}

// TestInputAfterPreviewNearInstantWhenElapsedCoversDelay is the judge leader
// raised: once the user has spent long enough (here: simulated by sleeping
// past bridge.PasteSettleDelay) the confirm-send must be near-instant, not
// "wait a little". It measures the wall-clock time from sending Input to
// receiving its ack.
func TestInputAfterPreviewNearInstantWhenElapsedCoversDelay(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot

	const path = "/host/TIMING_COVERED_6.png"
	te.wsEnv.sendFrame(&protocol.AttachPreview{Ref: te.ref(), Path: path})
	te.waitForMirror("TIMING_COVERED_6.png")

	time.Sleep(bridge.PasteSettleDelay + 200*time.Millisecond) // simulate typing time

	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "typed a caption", AttachmentPath: path})
	ack := te.wsEnv.readControlDraining()
	elapsed := time.Since(start)

	ia := ack.(protocol.InputAck)
	if !ia.OK {
		t.Fatalf("input_ack not ok: %s", ia.Reason)
	}
	if elapsed > 500*time.Millisecond {
		t.Errorf("ack took %v after typing time already covered the settle delay; want near-instant (<500ms), not a partial wait", elapsed)
	}
}

// TestInputAfterPreviewPaysRemainderWhenSentImmediately is the extreme-path
// control: pick-then-send-with-no-typing must still pay (close to) the full
// settle delay — proving the remainder branch actually engages rather than
// always returning zero.
func TestInputAfterPreviewPaysRemainderWhenSentImmediately(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot

	const path = "/host/TIMING_IMMEDIATE_7.png"
	te.wsEnv.sendFrame(&protocol.AttachPreview{Ref: te.ref(), Path: path})
	te.waitForMirror("TIMING_IMMEDIATE_7.png")

	start := time.Now()
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "", AttachmentPath: path})
	ack := te.wsEnv.readControlDraining()
	elapsed := time.Since(start)

	ia := ack.(protocol.InputAck)
	if !ia.OK {
		t.Fatalf("input_ack not ok: %s", ia.Reason)
	}
	if elapsed < bridge.PasteSettleDelay-300*time.Millisecond {
		t.Errorf("ack took only %v after an immediate send; want close to the full %v settle delay (remainder branch did not engage)", elapsed, bridge.PasteSettleDelay)
	}
}
