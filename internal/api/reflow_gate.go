package api

import (
	"context"
	"errors"
	"sync"
	"time"
)

const (
	// Silence is not a SIGWINCH completion acknowledgement. Programs without
	// a completed synchronized-output frame keep this bounded fallback.
	reflowHardCap     = 800 * time.Millisecond
	reflowMaxCaptures = 3
)

var errReflowUnstable = errors.New("reflow changed during every capture")

// reflowGate drains redraw output until a fresh snapshot replaces it. revision
// invalidates a capture if the relay drains any more bytes during that capture.
// Publication and switching back to deltas share the routing lock.
type reflowGate struct {
	mu       sync.Mutex
	active   bool
	epoch    uint64
	revision uint64

	syncPrefix   int
	syncOpen     bool
	syncComplete bool
	syncChanged  chan struct{}
}

func newReflowGate() *reflowGate { return &reflowGate{} }

func (g *reflowGate) begin() (uint64, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.active {
		return g.epoch, false
	}
	g.active = true
	g.revision = 0
	g.syncPrefix, g.syncOpen, g.syncComplete = 0, false, false
	g.syncChanged = make(chan struct{}, 1)
	g.epoch++
	return g.epoch, true
}

func (g *reflowGate) end() {
	g.mu.Lock()
	g.active = false
	g.mu.Unlock()
}

// A resize whose actual readback is unchanged needs no replacement snapshot
// only if nothing was drained. The check and release must be indivisible.
func (g *reflowGate) endIfClean() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.revision != 0 {
		return false
	}
	g.active = false
	return true
}

func (g *reflowGate) isActive() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.active
}

func (g *reflowGate) route(data []byte, send func(epoch uint64), discard func()) {
	g.mu.Lock()
	if g.active {
		g.revision++
		g.observeSynchronizedOutput(data)
		g.mu.Unlock()
		discard()
		return
	}
	send(g.epoch)
	g.mu.Unlock()
}

// resetSynchronizedOutput starts the resize observation after pipe attachment.
// A completion already drained before the resize request cannot unlock it.
func (g *reflowGate) resetSynchronizedOutput() {
	g.mu.Lock()
	g.syncPrefix, g.syncOpen, g.syncComplete = 0, false, false
	g.mu.Unlock()
}

// observeSynchronizedOutput is a bounded streaming matcher for Pi's exact DEC
// 2026 begin/end sequences. It preserves split prefixes across pipe chunks;
// an unpaired end cannot certify a frame started before this observation epoch.
// Caller holds mu.
func (g *reflowGate) observeSynchronizedOutput(data []byte) {
	const prefix = "\x1b[?2026"
	for _, b := range data {
		if g.syncPrefix == len(prefix) {
			switch b {
			case 'h':
				g.syncOpen, g.syncComplete = true, false
			case 'l':
				if g.syncOpen {
					g.syncOpen, g.syncComplete = false, true
				}
			}
			select {
			case g.syncChanged <- struct{}{}:
			default:
			}
			g.syncPrefix = 0
		}
		if b == prefix[g.syncPrefix] {
			g.syncPrefix++
		} else if b == '\x1b' {
			g.syncPrefix = 1
		} else {
			g.syncPrefix = 0
		}
	}
}

// waitForReflow returns early only for a completed observed frame, not silence.
// onlyOpen is the capture guard: no wait is needed outside a synchronized frame,
// but an unterminated frame at the deadline must fail rather than publish half.
func (g *reflowGate) waitForReflow(ctx context.Context, deadline time.Time, onlyOpen bool) error {
	for {
		g.mu.Lock()
		ready := g.syncComplete && !g.syncOpen || onlyOpen && !g.syncOpen
		changed := g.syncChanged
		g.mu.Unlock()
		if ready {
			return nil
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			if onlyOpen {
				return errReflowUnstable
			}
			return nil
		}
		timer := time.NewTimer(remaining)
		select {
		case <-changed:
			timer.Stop()
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		}
	}
}

// captureAndPublish takes a fresh snapshot after the readiness decision. A
// capture raced by drained bytes or an open frame is retried, not published. publish
// MUST be nonblocking: the relay must remain able to observe cancellation/loss
// if the client cannot accept the snapshot. Failure leaves the gate closed;
// the caller must tear down the mirror rather than resume an incomplete stream.
func (g *reflowGate) captureAndPublish(ctx context.Context, capture func(context.Context) ([]byte, error), publish func([]byte) error) error {
	for attempt := 0; attempt < reflowMaxCaptures; attempt++ {
		if err := ctx.Err(); err != nil {
			return err
		}
		g.mu.Lock()
		revision := g.revision
		g.mu.Unlock()
		snap, err := capture(ctx)
		if err != nil {
			return err
		}
		g.mu.Lock()
		if revision != g.revision || g.syncOpen {
			g.mu.Unlock()
			continue
		}
		err = ctx.Err()
		if err == nil {
			err = publish(snap)
		}
		if err == nil {
			g.active = false
		}
		g.mu.Unlock()
		return err
	}
	return errReflowUnstable
}
