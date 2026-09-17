package api

import (
	"context"
	"sync"
	"time"
)

const (
	// These bounds are deliberately local to the resize epoch. They are not a
	// global output throttle: normal command output remains a live delta stream.
	reflowQuietPeriod = 90 * time.Millisecond
	reflowHardCap     = 800 * time.Millisecond
	reflowMaxCaptures = 3
)

// reflowGate is the synchronization seam between the pipe relay and the
// resize handler. It owns no terminal state; it only decides whether a pipe
// chunk is forwarded or drained while the handler obtains a screen snapshot.
type reflowGate struct {
	mu       sync.Mutex
	active   bool
	epoch    uint64
	activity chan struct{}
}

func newReflowGate() *reflowGate {
	return &reflowGate{activity: make(chan struct{}, 1)}
}

// begin activates the gate before tmux receives resize-window. The returned
// epoch tags all deltas that may remain queued before the convergence snapshot.
func (g *reflowGate) begin() (uint64, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.active {
		return g.epoch, false
	}
	g.active = true
	g.epoch++
	g.activity = make(chan struct{}, 1)
	return g.epoch, true
}

func (g *reflowGate) end() {
	g.mu.Lock()
	g.active = false
	g.mu.Unlock()
}

// route calls send while holding the gate lock, so begin cannot race between
// the active check and enqueue. During a gate it drains the chunk and emits a
// wake signal for quiet detection.
func (g *reflowGate) route(data []byte, send func(epoch uint64), discard func()) {
	g.mu.Lock()
	if g.active {
		select {
		case g.activity <- struct{}{}:
		default:
		}
		g.mu.Unlock()
		discard()
		return
	}
	epoch := g.epoch
	send(epoch)
	g.mu.Unlock()
}

// waitQuiet waits until no pipe chunk arrives during quiet, or until deadline.
// sawActivity tells the caller whether a post-capture redraw happened.
func (g *reflowGate) waitQuiet(ctx context.Context, deadline time.Time, quiet time.Duration) (sawActivity, timedOut bool, err error) {
	g.mu.Lock()
	activity := g.activity
	active := g.active
	g.mu.Unlock()
	if !active {
		return false, false, nil
	}

	quietTimer := time.NewTimer(quiet)
	defer quietTimer.Stop()
	remaining := time.Until(deadline)
	if remaining <= 0 {
		return false, true, nil
	}
	hardTimer := time.NewTimer(remaining)
	defer hardTimer.Stop()
	resetQuiet := func() {
		if !quietTimer.Stop() {
			select {
			case <-quietTimer.C:
			default:
			}
		}
		quietTimer.Reset(quiet)
	}

	for {
		select {
		case <-activity:
			sawActivity = true
			resetQuiet()
		case <-quietTimer.C:
			return sawActivity, false, nil
		case <-hardTimer.C:
			return sawActivity, true, nil
		case <-ctx.Done():
			return sawActivity, false, ctx.Err()
		}
	}
}
