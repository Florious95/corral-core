package api

import (
	"context"
	"errors"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

// Recovery is event-driven, not a permanent 10Hz full-screen stream. While a
// dirty recovery is outstanding, debounce server-side capture attempts; the
// gate separately budgets progress snapshots and returns to deltas as soon as
// a capture is stable. In delta mode this worker sleeps without any polling.
func (c *wsConn) startSnapshotRefresh(sub *subscription, br *bridge.Pane) {
	if !sub.gate.usesSnapshots() {
		return
	}
	ctx := sub.ctx
	if ctx == nil { // direct fixtures use the connection's lifetime
		ctx = c.ctx
	}
	sub.snapshotRefreshOnce.Do(func() {
		go func() {
			for {
				select {
				case <-ctx.Done():
					return
				case <-sub.gate.snapshotWake:
				}
				if !sub.gate.usesSnapshots() {
					continue
				}
				timer := time.NewTimer(100 * time.Millisecond)
				select {
				case <-ctx.Done():
					timer.Stop()
					return
				case <-timer.C:
				}
				err := sub.gate.refreshSnapshot(ctx, func(ctx context.Context, cols, rows int) ([]byte, error) {
					if c.snapshotFn != nil {
						return c.snapshotFn(ctx, br)
					}
					frame, err := br.CaptureState(ctx)
					if err != nil {
						return nil, err
					}
					if err := validateCapturedGeometry(frame, cols, rows); err != nil {
						return nil, err
					}
					return snapshotFromCapture(frame), nil
				}, func(epoch uint64, snap []byte) error {
					return c.queueReflowSnapshot(sub.ref, epoch, snap)
				})
				if ctx.Err() != nil {
					return
				}
				if errors.Is(err, errReflowBackpressure) {
					// An already queued full snapshot remains valid. Keep dirty and
					// retry later, without buffering raw bytes or blocking the relay.
					continue
				}
				if err != nil {
					c.logErr("snapshot refresh", err)
					c.abortConnection("mirror_loss: cannot refresh mirror snapshot")
					return
				}
				if !sub.gate.usesSnapshots() {
					c.s.log.Info("perf_reflow_recovered", "conn", c.id, "ref", sub.ref)
				}
			}
		}()
	})
}
