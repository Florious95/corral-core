package api

import (
	"context"
	"errors"
	"time"

	"github.com/agentmirror/agentmirror/internal/bridge"
)

// Snapshot mode is only the busy-source fallback. Coalesce its dirty output at
// most ten times per second; idle mirrors perform no captures. This avoids both
// an unbounded retry loop in the WebSocket reader and a lossy return to deltas.
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
			ticker := time.NewTicker(100 * time.Millisecond)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
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
			}
		}()
	})
}
