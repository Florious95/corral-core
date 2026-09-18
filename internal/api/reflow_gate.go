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
	syncFrame    uint64
	syncChanged  chan struct{}

	// Once a byte-exact snapshot/delta cut cannot be established, this mirror
	// stays in snapshot mode until unsubscribe. Mixing raw deltas back in
	// without a source watermark could lose or replay capture-window output.
	snapshotMode               bool
	snapshotDirty              bool
	snapshotCols, snapshotRows int
}

func newReflowGate() *reflowGate { return &reflowGate{} }

func (g *reflowGate) begin() (uint64, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.active {
		return g.epoch, false
	}
	g.active = true
	g.snapshotDirty = true
	g.revision = 0
	g.syncPrefix, g.syncOpen, g.syncComplete = 0, false, false
	g.syncFrame = 0
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
	if g.active || g.snapshotMode {
		g.revision++
		g.snapshotDirty = true
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
					g.syncFrame++
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

func (g *reflowGate) completedFrame() uint64 {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.syncComplete && !g.syncOpen {
		return g.syncFrame
	}
	return 0
}

// Do not erase a newer completion that arrived while tmux was being captured.
func (g *reflowGate) rejectCompletedFrame(frame uint64) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if frame != 0 && frame == g.syncFrame {
		g.syncComplete = false
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

func (g *reflowGate) setSnapshotGeometry(cols, rows int) {
	g.mu.Lock()
	if g.snapshotCols != cols || g.snapshotRows != rows {
		g.snapshotCols, g.snapshotRows = cols, rows
		g.snapshotDirty = true
	}
	g.mu.Unlock()
}

func (g *reflowGate) usesSnapshots() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.snapshotMode
}

// A fresh snapshot makes a busy mirror usable, but does NOT certify a raw
// delta cut. Keep draining and refresh full screen state instead. In particular,
// bytes arriving after this capture require another capture, not silent loss.
func (g *reflowGate) startSnapshotMode(ctx context.Context, capture func(context.Context) ([]byte, error), publish func([]byte) error) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	snap, err := capture(ctx)
	if err != nil {
		return err
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := publish(snap); err != nil {
		return err
	}
	g.snapshotMode, g.snapshotDirty, g.active = true, true, false
	return nil
}

// refreshSnapshot coalesces output into bounded-rate authoritative snapshots.
// Clearing dirty BEFORE capture preserves every notification racing capture or
// publication. A resize epoch supersedes an in-flight refresh, including its
// errors; it must never publish the old geometry after the new resize starts.
func (g *reflowGate) refreshSnapshot(ctx context.Context, capture func(context.Context, int, int) ([]byte, error), publish func(uint64, []byte) error) error {
	g.mu.Lock()
	if !g.snapshotMode || !g.snapshotDirty || g.active {
		g.mu.Unlock()
		return nil
	}
	g.snapshotDirty = false
	epoch, cols, rows := g.epoch, g.snapshotCols, g.snapshotRows
	g.mu.Unlock()

	if err := ctx.Err(); err != nil {
		return err
	}
	snap, err := capture(ctx, cols, rows)
	g.mu.Lock()
	defer g.mu.Unlock()
	if epoch != g.epoch || g.active || cols != g.snapshotCols || rows != g.snapshotRows {
		g.snapshotDirty = true
		return nil
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if err == nil {
		err = publish(epoch, snap)
	}
	if err != nil {
		g.snapshotDirty = true
	}
	return err
}
