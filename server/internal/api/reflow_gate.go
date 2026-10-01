package api

import (
	"bytes"
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
	// A contended recovery may display progress, but must not repeatedly
	// rebuild the client's terminal at the server's capture sampling rate.
	reflowSnapshotInterval = time.Second
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

	// Snapshot recovery is temporary: the first uncontended capture returns
	// to raw deltas. Until then, coalesce changes and rate-limit full frames.
	snapshotMode               bool
	snapshotDirty              bool
	snapshotCols, snapshotRows int
	snapshotWake               chan struct{}
	lastSnapshot               []byte
	lastSnapshotAt             time.Time
}

func newReflowGate() *reflowGate {
	return &reflowGate{snapshotWake: make(chan struct{}, 1)}
}

func (g *reflowGate) begin() (uint64, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.active {
		return g.epoch, false
	}
	g.active = true
	g.snapshotDirty = true
	g.lastSnapshot = nil
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
	g.wakeSnapshotLocked()
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
	g.wakeSnapshotLocked()
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
		g.wakeSnapshotLocked()
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
			g.finishCaptureLocked()
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
		g.wakeSnapshotLocked()
	}
	g.mu.Unlock()
}

// Caller holds mu, including across snapshot admission and delta release.
func (g *reflowGate) finishCaptureLocked() {
	g.active, g.snapshotMode, g.snapshotDirty = false, false, false
	g.lastSnapshot = nil
}

func (g *reflowGate) wakeSnapshotLocked() {
	if g.snapshotMode && g.snapshotDirty && !g.active {
		select {
		case g.snapshotWake <- struct{}{}:
		default:
		}
	}
}

func (g *reflowGate) usesSnapshots() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.snapshotMode
}

// Recheck the fresh fallback capture itself. If it is uncontended, the FIRST
// published frame can already release deltas; prior failed attempts must not
// condemn the subscription to permanent full-screen replays.
func (g *reflowGate) startSnapshotMode(ctx context.Context, capture func(context.Context) ([]byte, error), publish func([]byte) error) error {
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
	defer g.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := publish(snap); err != nil {
		return err
	}
	if revision == g.revision && !g.syncOpen {
		g.finishCaptureLocked()
	} else {
		g.snapshotMode, g.snapshotDirty, g.active = true, true, false
		g.lastSnapshot, g.lastSnapshotAt = bytes.Clone(snap), time.Now()
		g.wakeSnapshotLocked()
	}
	return nil
}

// refreshSnapshot seeks a clean delta cut, not a permanent snapshot stream.
// Contended captures may publish changed progress at most once per second.
// A clean capture publishes one final handoff frame and immediately releases
// deltas, even if the recovery progress-frame budget has not elapsed.
// Clearing dirty BEFORE capture retains output racing capture/publication.
// A resize supersedes an in-flight capture AND its errors.
func (g *reflowGate) refreshSnapshot(ctx context.Context, capture func(context.Context, int, int) ([]byte, error), publish func(uint64, []byte) error) error {
	g.mu.Lock()
	if !g.snapshotMode || !g.snapshotDirty || g.active {
		g.mu.Unlock()
		return nil
	}
	g.snapshotDirty = false
	epoch, revision, cols, rows := g.epoch, g.revision, g.snapshotCols, g.snapshotRows
	g.mu.Unlock()

	if err := ctx.Err(); err != nil {
		return err
	}
	snap, err := capture(ctx, cols, rows)
	g.mu.Lock()
	defer g.mu.Unlock()
	defer g.wakeSnapshotLocked()
	if epoch != g.epoch || g.active || cols != g.snapshotCols || rows != g.snapshotRows {
		g.snapshotDirty = true
		return nil
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	stable := revision == g.revision && !g.syncOpen
	if err == nil && !stable {
		if bytes.Equal(snap, g.lastSnapshot) {
			return nil
		}
		if time.Since(g.lastSnapshotAt) < reflowSnapshotInterval {
			g.snapshotDirty = true
			return nil
		}
	}
	if err == nil {
		err = publish(epoch, snap)
	}
	if err != nil {
		g.snapshotDirty = true
		return err
	}
	if stable {
		g.finishCaptureLocked()
	} else {
		g.lastSnapshot, g.lastSnapshotAt = bytes.Clone(snap), time.Now()
	}
	return nil
}
