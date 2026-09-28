package api

// attach_preview_test.go: pure-logic red tests for the requirement-057
// bookkeeping in attach_preview.go — no tmux involved, these test the
// record/consume/remainder math directly.
//
// T1: consume with no prior record → miss
// T2: consume with matching path → hit, elapsed measured, entry removed (one-shot)
// T3: consume with a DIFFERENT path than what was recorded → miss (the rule
//     leader called out as the key one: a swapped image must not reuse the
//     old timestamp)
// T4: a second AttachPreview for the same ref overwrites the first (only the
//     newest paste's timestamp matters — it's the bottleneck)
// T5: remainingSettleDelay: zero once elapsed already covers the full delay,
//     positive (and exactly delay-elapsed) otherwise

import (
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

func newTestServerForPreview() *Server {
	return &Server{attachPreviews: make(map[string]attachPreviewEntry)}
}

func TestConsumeAttachPreviewNoRecordMisses(t *testing.T) {
	s := newTestServerForPreview()
	_, ok := s.consumeAttachPreview("ref1", "/host/img.png")
	if ok {
		t.Fatal("consume with no prior record must miss")
	}
}

func TestConsumeAttachPreviewMatchingPathHits(t *testing.T) {
	s := newTestServerForPreview()
	s.recordAttachPreview("ref1", "/host/img.png")
	time.Sleep(20 * time.Millisecond)

	elapsed, ok := s.consumeAttachPreview("ref1", "/host/img.png")
	if !ok {
		t.Fatal("consume with matching path must hit")
	}
	if elapsed < 20*time.Millisecond {
		t.Errorf("elapsed = %v, want >= 20ms", elapsed)
	}
	// One-shot: consuming again must miss (the entry was removed).
	if _, ok := s.consumeAttachPreview("ref1", "/host/img.png"); ok {
		t.Error("second consume of the same preview must miss — consume is one-shot")
	}
}

func TestConsumeAttachPreviewMismatchedPathMisses(t *testing.T) {
	// The rule leader singled out: swapping the image must force a full
	// re-paste + full wait, never reuse the old preview's timestamp.
	s := newTestServerForPreview()
	s.recordAttachPreview("ref1", "/host/a.png")

	if _, ok := s.consumeAttachPreview("ref1", "/host/b.png"); ok {
		t.Fatal("consume with a different path than what was recorded must miss")
	}
	// The mismatched consume must not have deleted the original record either.
	elapsed, ok := s.consumeAttachPreview("ref1", "/host/a.png")
	if !ok {
		t.Fatal("original path must still be consumable after a mismatched attempt")
	}
	_ = elapsed
}

func TestRecordAttachPreviewOverwritesEarlierOne(t *testing.T) {
	// Requirement 057 clause 4: attachments accumulate in the pane, but the
	// settle-delay math only tracks the newest paste (the bottleneck).
	s := newTestServerForPreview()
	s.recordAttachPreview("ref1", "/host/a.png")
	s.recordAttachPreview("ref1", "/host/b.png")

	if _, ok := s.consumeAttachPreview("ref1", "/host/a.png"); ok {
		t.Error("the earlier preview's path must no longer be consumable after a newer one overwrote it")
	}
	if _, ok := s.consumeAttachPreview("ref1", "/host/b.png"); !ok {
		t.Error("the newest preview's path must be consumable")
	}
}

func TestRemainingSettleDelay(t *testing.T) {
	cases := []struct {
		name    string
		elapsed time.Duration
		want    time.Duration
	}{
		{"zero elapsed needs full delay", 0, bridge.PasteSettleDelay},
		{"half elapsed needs half remaining", bridge.PasteSettleDelay / 2, bridge.PasteSettleDelay / 2},
		{"elapsed exactly covers delay", bridge.PasteSettleDelay, 0},
		{"elapsed exceeds delay", bridge.PasteSettleDelay + 5*time.Second, 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := remainingSettleDelay(c.elapsed); got != c.want {
				t.Errorf("remainingSettleDelay(%v) = %v, want %v", c.elapsed, got, c.want)
			}
		})
	}
}
