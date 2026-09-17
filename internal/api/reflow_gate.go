package api

import (
	"context"
	"errors"
	"sync"
	"time"
)

const (
	// tmux reports geometry, not completion of the application's SIGWINCH
	// handler. Observe the whole local budget: a short silence between redraw
	// stages is not an acknowledgement that reflow has finished.
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
		g.mu.Unlock()
		discard()
		return
	}
	send(g.epoch)
	g.mu.Unlock()
}

// captureAndPublish never reuses a snapshot from before the observation budget
// expired. A capture raced by drained bytes is retried, not published. publish
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
		if revision != g.revision {
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
